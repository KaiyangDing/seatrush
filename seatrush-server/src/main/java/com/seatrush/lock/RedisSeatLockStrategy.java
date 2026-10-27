package com.seatrush.lock;

import java.time.Duration;
import java.util.List;
import java.util.SortedSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import com.seatrush.event.Seat;
import com.seatrush.event.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * A distributed lock in Redis, so it still works when several instances of the application run side
 * by side. Every seat has a key {@code seatrush:seat-lock:<seatId>}, and two small Lua scripts take
 * and release them. Redis runs a script from start to end without anything in between, so a request
 * gets either all of its seats or none of them.
 * <p>
 * A key holds a random token of its owner and has a lease (seatrush.lock.redis-lease): only the owner
 * deletes it, and if an instance dies while holding seats they come free on their own when the lease
 * runs out. As with the in-memory strategy, the seats are locked before the transaction starts and
 * released after it commits. Should a transaction ever outlast the lease, the @Version column on
 * Seat still stops a double hold.
 * <p>
 * Redis cannot make a client wait for a key, so a request that finds a seat taken asks again after a
 * short random pause. That is not fair: whoever happens to ask right after a release gets the seat.
 * (On a Redis Cluster the keys of one script would need a common hash tag; this project uses one Redis.)
 */
@Component
@Order(4)
public class RedisSeatLockStrategy implements SeatLockStrategy {

    private static final Logger log = LoggerFactory.getLogger(RedisSeatLockStrategy.class);

    static final String KEY_PREFIX = "seatrush:seat-lock:";

    /** KEYS = the seat keys, ARGV = token, lease in ms. Sets every key, or none if any is taken. */
    private static final RedisScript<Long> ACQUIRE = RedisScript.of("""
            for _, key in ipairs(KEYS) do
                if redis.call('EXISTS', key) == 1 then
                    return 0
                end
            end
            for _, key in ipairs(KEYS) do
                redis.call('SET', key, ARGV[1], 'PX', ARGV[2])
            end
            return 1
            """, Long.class);

    /** KEYS = the seat keys, ARGV = token. Deletes only keys that are still ours: after a lease ran out, a key may belong to someone else. */
    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            local released = 0
            for _, key in ipairs(KEYS) do
                if redis.call('GET', key) == ARGV[1] then
                    redis.call('DEL', key)
                    released = released + 1
                end
            end
            return released
            """, Long.class);

    private static final long MAX_PAUSE_MILLIS = 20;

    private final StringRedisTemplate redis;
    private final SeatRepository seatRepository;
    private final Duration waitTimeout;
    private final Duration lease;

    public RedisSeatLockStrategy(StringRedisTemplate redis, SeatRepository seatRepository, LockProperties properties) {
        this.redis = redis;
        this.seatRepository = seatRepository;
        this.waitTimeout = properties.waitTimeout();
        this.lease = properties.redisLease();
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public <T> T runTransaction(SortedSet<Long> seatIds, Supplier<T> transaction) {
        List<String> keys = seatIds.stream().map(seatId -> KEY_PREFIX + seatId).toList();
        String token = UUID.randomUUID().toString();
        acquire(keys, token, seatIds);
        try {
            return transaction.get();
        } finally {
            release(keys, token);
        }
    }

    @Override
    public List<Seat> loadSeats(SortedSet<Long> seatIds) {
        return seatRepository.findByIdInOrderByIdAsc(seatIds);
    }

    /** Asks again after short random pauses that grow up to 20 ms, until the wait timeout has passed. */
    private void acquire(List<String> keys, String token, SortedSet<Long> seatIds) {
        long deadline = System.nanoTime() + waitTimeout.toNanos();
        long pause = 1;
        while (!tryAcquire(keys, token)) {
            if (System.nanoTime() - deadline > 0) {
                throw new SeatsBusyException(seatIds);
            }
            try {
                // Random, so requests that collided once do not collide again in lockstep
                Thread.sleep(ThreadLocalRandom.current().nextLong(pause, 2 * pause + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SeatsBusyException(seatIds);
            }
            pause = Math.min(2 * pause, MAX_PAUSE_MILLIS);
        }
    }

    private boolean tryAcquire(List<String> keys, String token) {
        Long acquired = redis.execute(ACQUIRE, keys, token, String.valueOf(lease.toMillis()));
        return Long.valueOf(1).equals(acquired);
    }

    private void release(List<String> keys, String token) {
        try {
            redis.execute(RELEASE, keys, token);
        } catch (RuntimeException e) {
            // The transaction is already over, so only the lock is left behind; the lease frees it soon
            log.warn("Could not release seat locks {}, they expire within {}", keys, lease, e);
        }
    }
}

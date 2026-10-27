package com.seatrush.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The Redis locking against a real Redis. The "transactions" are plain lambdas, and keys written by
 * hand play another instance of the application. The seat ids are made up, so no database is touched.
 */
@SpringBootTest
class RedisSeatLockStrategyTest {

    private static final long SEAT_A = 990_001;
    private static final long SEAT_B = 990_002;

    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void cleanUp() {
        redis.delete(List.of(key(SEAT_A), key(SEAT_B)));
    }

    @Test
    void seatsAreLockedDuringTheTransactionAndFreedAfterIt() {
        RedisSeatLockStrategy strategy = withWaitTimeout(Duration.ofMillis(300));

        String inside = strategy.runTransaction(seats(SEAT_A, SEAT_B), () -> redis.opsForValue().get(key(SEAT_B)));

        assertThat(inside).isNotBlank();
        assertThat(redis.hasKey(key(SEAT_A))).isFalse();
        assertThat(redis.hasKey(key(SEAT_B))).isFalse();
    }

    @Test
    void seatsAreFreedWhenTheTransactionFails() {
        RedisSeatLockStrategy strategy = withWaitTimeout(Duration.ofMillis(300));

        assertThatThrownBy(() -> strategy.runTransaction(seats(SEAT_A), () -> {
            throw new IllegalStateException("rolled back");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(redis.hasKey(key(SEAT_A))).isFalse();
    }

    @Test
    void oneTakenSeatMeansNoneAreTakenAndTheOtherOwnerKeepsIt() {
        RedisSeatLockStrategy strategy = withWaitTimeout(Duration.ofMillis(300));
        redis.opsForValue().set(key(SEAT_B), "other-instance", Duration.ofSeconds(30));
        AtomicBoolean ran = new AtomicBoolean();

        assertThatThrownBy(() -> strategy.runTransaction(seats(SEAT_A, SEAT_B), () -> ran.getAndSet(true)))
                .isInstanceOf(SeatsBusyException.class);

        assertThat(ran).isFalse();
        // All or nothing: seat A was never locked on its own, and the other owner's key is untouched
        assertThat(redis.hasKey(key(SEAT_A))).isFalse();
        assertThat(redis.opsForValue().get(key(SEAT_B))).isEqualTo("other-instance");
    }

    @Test
    void seatsOfACrashedInstanceComeFreeWhenTheLeaseRunsOut() {
        RedisSeatLockStrategy strategy = withWaitTimeout(Duration.ofSeconds(5));
        // Locked by an instance that died before it could release the key
        redis.opsForValue().set(key(SEAT_A), "crashed-instance", Duration.ofMillis(300));

        assertThat(strategy.runTransaction(seats(SEAT_A), () -> "got it")).isEqualTo("got it");
    }

    private RedisSeatLockStrategy withWaitTimeout(Duration waitTimeout) {
        // runTransaction never reads seats, so no repository is needed
        return new RedisSeatLockStrategy(redis, null, new LockProperties("redis", waitTimeout, Duration.ofSeconds(10)));
    }

    private static String key(long seatId) {
        return RedisSeatLockStrategy.KEY_PREFIX + seatId;
    }

    private static SortedSet<Long> seats(Long... seatIds) {
        return new TreeSet<>(List.of(seatIds));
    }
}

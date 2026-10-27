package com.seatrush.lock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import com.seatrush.event.Seat;
import com.seatrush.event.SeatRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Locks the seats inside this JVM with {@link ReentrantLock}s, before the transaction starts and
 * until after it has committed. Requests that wait for a seat wait here, without holding a database
 * connection, and the transaction itself only needs a plain read.
 * <p>
 * Lock striping: instead of one lock per seat (a map that would grow forever) there is a fixed
 * array of locks, and a seat uses the lock at {@code seatId mod STRIPES}. Two seats can share a lock,
 * which only means they are sometimes queued together. The locks are fair, so waiting requests get
 * the seat in the order they arrived.
 * <p>
 * Only correct while the application runs as a single instance: a second instance has its own
 * locks and knows nothing about these. That is what the Redis strategy solves.
 */
@Component
@Order(3)
public class InMemorySeatLockStrategy implements SeatLockStrategy {

    static final int STRIPES = 1024;

    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];
    private final SeatRepository seatRepository;
    private final Duration waitTimeout;

    public InMemorySeatLockStrategy(SeatRepository seatRepository, LockProperties properties) {
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new ReentrantLock(true);
        }
        this.seatRepository = seatRepository;
        this.waitTimeout = properties.waitTimeout();
    }

    @Override
    public String name() {
        return "in-memory";
    }

    @Override
    public <T> T runTransaction(SortedSet<Long> seatIds, Supplier<T> transaction) {
        // Each stripe once, always in ascending order, so two requests can never wait for each other in a circle
        SortedSet<Integer> needed = new TreeSet<>();
        seatIds.forEach(seatId -> needed.add(Math.floorMod(seatId, STRIPES)));

        long deadline = System.nanoTime() + waitTimeout.toNanos();
        List<ReentrantLock> taken = new ArrayList<>();
        try {
            for (int stripe : needed) {
                ReentrantLock lock = stripes[stripe];
                if (!lock.tryLock(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
                    throw new SeatsBusyException(seatIds);
                }
                taken.add(lock);
            }
            // The commit happens inside this call, so the next request only gets the lock after it
            return transaction.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SeatsBusyException(seatIds);
        } finally {
            taken.reversed().forEach(ReentrantLock::unlock);
        }
    }

    @Override
    public List<Seat> loadSeats(SortedSet<Long> seatIds) {
        return seatRepository.findByIdInOrderByIdAsc(seatIds);
    }
}

package com.seatrush.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The locking on its own: no Spring and no database. The "transactions" are plain lambdas, and a
 * background thread plays the other request that holds a seat.
 */
class InMemorySeatLockStrategyTest {

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stopThreads() {
        release.countDown();
        pool.shutdownNow();
    }

    @Test
    void secondRequestWaitsUntilTheFirstHasFinished() throws Exception {
        InMemorySeatLockStrategy strategy = withWaitTimeout(Duration.ofSeconds(5));
        keepLocked(strategy, 1);

        Future<String> second = pool.submit(() -> strategy.runTransaction(seats(1), () -> "second"));

        Thread.sleep(200);
        assertThat(second).isNotDone();
        release.countDown();
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("second");
    }

    @Test
    void givesUpWithBusyOnceTheWaitTimeoutHasPassed() throws Exception {
        InMemorySeatLockStrategy strategy = withWaitTimeout(Duration.ofMillis(200));
        keepLocked(strategy, 1);
        AtomicBoolean ran = new AtomicBoolean();

        assertThatThrownBy(() -> strategy.runTransaction(seats(1), () -> ran.getAndSet(true)))
                .isInstanceOf(SeatsBusyException.class);
        assertThat(ran).isFalse();
    }

    @Test
    void locksAlreadyTakenAreGivenBackWhenTheRestTimesOut() throws Exception {
        InMemorySeatLockStrategy strategy = withWaitTimeout(Duration.ofMillis(200));
        keepLocked(strategy, 2);

        // Gets seat 1, then times out waiting for seat 2
        assertThatThrownBy(() -> strategy.runTransaction(seats(1, 2), () -> "never"))
                .isInstanceOf(SeatsBusyException.class);

        // Seat 1 has to be free again for everybody else
        assertThat(pool.submit(() -> strategy.runTransaction(seats(1), () -> "free")).get(5, TimeUnit.SECONDS))
                .isEqualTo("free");
    }

    @Test
    void locksAreGivenBackWhenTheTransactionFails() throws Exception {
        InMemorySeatLockStrategy strategy = withWaitTimeout(Duration.ofMillis(200));

        assertThatThrownBy(() -> strategy.runTransaction(seats(1), () -> {
            throw new IllegalStateException("rolled back");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(pool.submit(() -> strategy.runTransaction(seats(1), () -> "free")).get(5, TimeUnit.SECONDS))
                .isEqualTo("free");
    }

    @Test
    void seatsSharingAStripeAreQueuedTogether() throws Exception {
        InMemorySeatLockStrategy strategy = withWaitTimeout(Duration.ofMillis(200));
        keepLocked(strategy, 1);

        assertThat(strategy.runTransaction(seats(2), () -> "other stripe")).isEqualTo("other stripe");
        assertThatThrownBy(() -> strategy.runTransaction(seats(1 + InMemorySeatLockStrategy.STRIPES), () -> "same stripe"))
                .isInstanceOf(SeatsBusyException.class);
    }

    /** Another request takes the seats on a background thread and keeps them until the test ends. */
    private void keepLocked(InMemorySeatLockStrategy strategy, long... seatIds) throws InterruptedException {
        CountDownLatch locked = new CountDownLatch(1);
        pool.submit(() -> strategy.runTransaction(seats(seatIds), () -> {
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static InMemorySeatLockStrategy withWaitTimeout(Duration waitTimeout) {
        // runTransaction never reads seats, so no repository is needed
        return new InMemorySeatLockStrategy(null, new LockProperties("in-memory", waitTimeout));
    }

    private static SortedSet<Long> seats(long... seatIds) {
        return LongStream.of(seatIds).boxed().collect(Collectors.toCollection(TreeSet::new));
    }
}

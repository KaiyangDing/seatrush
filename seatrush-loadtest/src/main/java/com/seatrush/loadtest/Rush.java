package com.seatrush.loadtest;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.seatrush.loadtest.Outcome.Attempt;
import com.seatrush.loadtest.Outcome.Kind;

/**
 * Sends every user's hold request at the same moment. Each request gets its own virtual thread,
 * which waits at a CountDownLatch until all of them are ready; then they are let go together, the
 * way a crowd hits the "buy" button the second sales open.
 */
public final class Rush {

    private Rush() {
    }

    public static RushResult run(SeatRushClient client, long eventId, List<Attempt> attempts) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(attempts.size());
        CountDownLatch go = new CountDownLatch(1);
        // Each thread writes only its own slot; closing the executor makes all the writes visible here
        Outcome[] outcomes = new Outcome[attempts.size()];
        long started;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < attempts.size(); i++) {
                int index = i;
                executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    outcomes[index] = attempt(client, eventId, attempts.get(index));
                    return null;
                });
            }
            ready.await();
            started = System.nanoTime();
            go.countDown();
        } // close() waits until every request has its answer

        return new RushResult(Arrays.asList(outcomes), System.nanoTime() - started);
    }

    private static Outcome attempt(SeatRushClient client, long eventId, Attempt attempt) {
        long start = System.nanoTime();
        try {
            return Outcome.of(attempt, client.hold(attempt.token(), eventId, attempt.seatIds(), UUID.randomUUID().toString()));
        } catch (IOException e) {
            return Outcome.failed(attempt, System.nanoTime() - start, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.failed(attempt, System.nanoTime() - start, e);
        }
    }

    /** Cancels every hold the rush created, so the seats are free for the next run. Returns how many failed. */
    public static int cancelHolds(SeatRushClient client, RushResult result) {
        AtomicInteger failures = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Outcome outcome : result.outcomes()) {
                if (outcome.kind() == Kind.HELD) {
                    executor.submit(() -> {
                        if (!client.cancel(outcome.attempt().token(), outcome.holdId()).isSuccess()) {
                            failures.incrementAndGet();
                        }
                        return null;
                    });
                }
            }
        }
        return failures.get();
    }
}

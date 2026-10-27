package com.seatrush.hold;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.DelayQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Releases holds when their deadline passes. Every ACTIVE hold sits in a DelayQueue, and one
 * background thread takes each one out when it is due and expires it.
 * <p>
 * The queue lives in memory, so it is empty after a restart. On start-up it is refilled from the
 * database: every hold that is still ACTIVE goes back in, and the overdue ones come out at once.
 */
@Component
public class HoldExpiryScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryScheduler.class);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    private final DelayQueue<ExpiringHold> queue = new DelayQueue<>();
    private final HoldService holdService;
    private final HoldRepository holdRepository;
    private volatile Thread worker;

    public HoldExpiryScheduler(HoldService holdService, HoldRepository holdRepository) {
        this.holdService = holdService;
        this.holdRepository = holdRepository;
    }

    /** Only called after the hold has been committed, so a rolled-back hold never gets scheduled. */
    @TransactionalEventListener
    void onHoldCreated(HoldCreatedEvent event) {
        schedule(event.holdId(), event.expiresAt());
    }

    /** Puts every ACTIVE hold back in the queue; overdue ones are expired straight away. */
    private void recoverActiveHolds() {
        List<Hold> active = holdRepository.findByStatus(HoldStatus.ACTIVE);
        active.forEach(hold -> schedule(hold.getId(), hold.getExpiresAt()));
        log.info("Recovered {} active holds", active.size());
    }

    private void schedule(long holdId, Instant expiresAt) {
        queue.put(new ExpiringHold(holdId, expiresAt));
    }

    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            ExpiringHold due;
            try {
                due = queue.take(); // blocks until the earliest deadline has passed
            } catch (InterruptedException e) {
                return; // stop() interrupts the thread
            }
            try {
                holdService.expireHold(due.holdId());
            } catch (RuntimeException e) {
                // For example the database is briefly unreachable: try again shortly instead of losing the hold
                log.warn("Could not expire hold {}, retrying in {}", due.holdId(), RETRY_DELAY, e);
                schedule(due.holdId(), Instant.now().plus(RETRY_DELAY));
            }
        }
    }

    @Override
    public void start() {
        worker = Thread.ofPlatform().name("hold-expiry").daemon(true).start(this::run);
        recoverActiveHolds();
    }

    @Override
    public void stop() {
        Thread thread = worker;
        worker = null;
        if (thread != null) {
            thread.interrupt();
            try {
                // Let an expiry that is already running finish before the database connections close
                thread.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return worker != null;
    }
}

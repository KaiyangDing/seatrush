package com.seatrush.hold;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/** An entry in the DelayQueue: take() hands it out only once its deadline has passed. */
record ExpiringHold(long holdId, Instant expiresAt) implements Delayed {

    @Override
    public long getDelay(TimeUnit unit) {
        // Zero or negative means "due now"
        return unit.convert(Duration.between(Instant.now(), expiresAt));
    }

    @Override
    public int compareTo(Delayed other) {
        // The queue keeps the earliest deadline at its head
        return expiresAt.compareTo(((ExpiringHold) other).expiresAt);
    }
}

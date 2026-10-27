package com.seatrush.hold;

import java.time.Instant;

/** Published inside the transaction that created the hold; listeners only see it once that transaction commits. */
public record HoldCreatedEvent(long holdId, Instant expiresAt) {
}

package com.seatrush.hold;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "holds")
public class Hold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private HoldStatus status;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    protected Hold() {
    }

    public Hold(Long userId, Long eventId, String idempotencyKey, Instant expiresAt) {
        this.userId = userId;
        this.eventId = eventId;
        this.idempotencyKey = idempotencyKey;
        this.expiresAt = expiresAt;
        this.status = HoldStatus.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getEventId() {
        return eventId;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /** ACTIVE -> CONFIRMED. The service has already checked the status and the deadline. */
    public void confirm(Instant now) {
        requireActive();
        this.status = HoldStatus.CONFIRMED;
        this.confirmedAt = now;
    }

    /** ACTIVE -> CANCELLED. */
    public void cancel() {
        requireActive();
        this.status = HoldStatus.CANCELLED;
    }

    /** ACTIVE -> EXPIRED: the deadline passed before the hold was confirmed or cancelled. */
    public void expire() {
        requireActive();
        this.status = HoldStatus.EXPIRED;
    }

    private void requireActive() {
        if (status != HoldStatus.ACTIVE) {
            throw new IllegalStateException("Hold " + id + " is " + status + ", not ACTIVE");
        }
    }
}

package com.seatrush.hold;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response bodies for seat holds. */
public final class HoldDtos {

    private HoldDtos() {
    }

    /** The client generates idempotencyKey (for example a UUID) once per attempt and reuses it on retries. */
    public record CreateHoldRequest(
            @NotEmpty @Size(max = 4) List<@NotNull Long> seatIds,
            @NotBlank @Size(max = 64) String idempotencyKey) {
    }

    public record HoldResponse(Long id, Long eventId, HoldStatus status, List<Long> seatIds, Instant expiresAt) {
    }
}
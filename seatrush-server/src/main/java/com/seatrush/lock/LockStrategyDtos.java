package com.seatrush.lock;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

/** Request and response bodies for switching the seat lock strategy. */
public final class LockStrategyDtos {

    private LockStrategyDtos() {
    }

    public record SwitchLockStrategyRequest(@NotBlank String strategy) {
    }

    public record LockStrategyResponse(String current, List<String> available) {
    }
}

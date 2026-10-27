package com.seatrush.lock;

import com.seatrush.lock.LockStrategyDtos.LockStrategyResponse;
import com.seatrush.lock.LockStrategyDtos.SwitchLockStrategyRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only: SecurityConfig restricts /api/admin/** to ROLE_ADMIN. The switch takes effect for the
 * next hold request; requests already running finish with the strategy they started with.
 */
@RestController
@RequestMapping("/api/admin/lock-strategy")
public class LockStrategyAdminController {

    private final SeatLockStrategies strategies;

    public LockStrategyAdminController(SeatLockStrategies strategies) {
        this.strategies = strategies;
    }

    @GetMapping
    public LockStrategyResponse getStrategy() {
        return response();
    }

    @PutMapping
    public LockStrategyResponse switchStrategy(@Valid @RequestBody SwitchLockStrategyRequest request) {
        strategies.use(request.strategy());
        return response();
    }

    private LockStrategyResponse response() {
        return new LockStrategyResponse(strategies.current().name(), strategies.names());
    }
}

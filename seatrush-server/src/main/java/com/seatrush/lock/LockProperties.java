package com.seatrush.lock;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param strategy    the seat lock strategy new holds use at start-up, see {@link SeatLockStrategies}
 * @param waitTimeout how long a request waits for a seat lock before it gives up with 409
 */
@ConfigurationProperties(prefix = "seatrush.lock")
public record LockProperties(String strategy, Duration waitTimeout) {
}

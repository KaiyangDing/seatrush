package com.seatrush.lock;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param strategy the seat lock strategy new holds use at start-up, see {@link SeatLockStrategies}
 */
@ConfigurationProperties(prefix = "seatrush.lock")
public record LockProperties(String strategy) {
}

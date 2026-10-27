package com.seatrush.hold;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param duration how long a hold keeps its seats before they go back on sale
 * @param precheck reject a request for seats that are already taken before queueing for a lock;
 *                 switched off only to measure the lock strategies on their own
 */
@ConfigurationProperties(prefix = "seatrush.holds")
public record HoldProperties(Duration duration, @DefaultValue("true") boolean precheck) {
}

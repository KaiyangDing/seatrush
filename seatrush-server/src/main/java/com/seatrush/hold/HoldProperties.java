package com.seatrush.hold;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "seatrush.holds")
public record HoldProperties(Duration duration) {
}
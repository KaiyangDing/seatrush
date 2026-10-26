package com.seatrush.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "seatrush.security")
public record SecurityProperties(
        String jwtSecret,
        String pepper,
        Duration tokenTtl,
        String adminUsername,
        String adminPassword) {
}
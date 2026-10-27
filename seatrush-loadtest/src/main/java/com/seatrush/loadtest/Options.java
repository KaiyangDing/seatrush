package com.seatrush.loadtest;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Command-line options in the form --name value; every option has a default. */
record Options(URI baseUrl, String adminUser, String adminPassword, int users, Scenario scenario, long seed) {

    private static final Set<String> NAMES = Set.of("base-url", "admin", "admin-password", "users", "scenario", "seed");

    static final String USAGE = """
            Usage: java -jar seatrush-loadtest/target/seatrush-loadtest.jar [options]
              --base-url        server to test               (default http://localhost:8080)
              --admin           admin username               (default admin)
              --admin-password  admin password               (default admin12345)
              --users           users in the rush            (default 300)
              --scenario        hot-seat, hot-row or spread  (default hot-row)
              --seed            seed for the seats users pick (default 42)
            """;

    static Options parse(String... args) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            String name = args[i].startsWith("--") ? args[i].substring(2) : "";
            if (!NAMES.contains(name) || i + 1 >= args.length) {
                throw new IllegalArgumentException("Unknown or incomplete option: " + args[i] + "\n\n" + USAGE);
            }
            values.put(name, args[i + 1]);
        }
        int users = Integer.parseInt(values.getOrDefault("users", "300"));
        if (users < 1 || users > 9999) {
            throw new IllegalArgumentException("--users must be between 1 and 9999");
        }
        return new Options(
                URI.create(values.getOrDefault("base-url", "http://localhost:8080")),
                values.getOrDefault("admin", "admin"),
                values.getOrDefault("admin-password", "admin12345"),
                users,
                Scenario.fromId(values.getOrDefault("scenario", "hot-row")),
                Long.parseLong(values.getOrDefault("seed", "42")));
    }
}

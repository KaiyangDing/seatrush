package com.seatrush.loadtest;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command-line options in the form --name value; every option has a default.
 *
 * @param strategies empty for a single rush per scenario with the server's current strategy;
 *                   otherwise the strategies to compare, or ["all"]
 */
record Options(URI baseUrl, String adminUser, String adminPassword, int users, List<Scenario> scenarios, long seed,
               List<String> strategies, int rounds, Path out) {

    private static final Set<String> NAMES = Set.of(
            "base-url", "admin", "admin-password", "users", "scenario", "seed", "compare", "rounds", "out");

    static final String USAGE = """
            Usage: java -jar seatrush-loadtest/target/seatrush-loadtest.jar [options]
              --base-url        server to test                         (default http://localhost:8080)
              --admin           admin username                         (default admin)
              --admin-password  admin password                         (default admin12345)
              --users           users in each rush                     (default 300)
              --scenario        hot-seat, hot-row, spread or a list    (default all three)
              --seed            seed for the seats users pick          (default 42)
              --compare         all, or strategies like optimistic,redis: run every scenario with
                                each of them and write a report        (default: one rush, current strategy)
              --rounds          rushes per strategy and scenario       (default 3)
              --out             folder for the comparison report       (default loadtest-results)
            """;

    boolean compare() {
        return !strategies.isEmpty();
    }

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
        int rounds = Integer.parseInt(values.getOrDefault("rounds", "3"));
        if (rounds < 1) {
            throw new IllegalArgumentException("--rounds must be at least 1");
        }
        return new Options(
                URI.create(values.getOrDefault("base-url", "http://localhost:8080")),
                values.getOrDefault("admin", "admin"),
                values.getOrDefault("admin-password", "admin12345"),
                users,
                list(values.getOrDefault("scenario", "hot-seat,hot-row,spread")).stream().map(Scenario::fromId).toList(),
                Long.parseLong(values.getOrDefault("seed", "42")),
                values.containsKey("compare") ? list(values.get("compare")) : List.of(),
                rounds,
                Path.of(values.getOrDefault("out", "loadtest-results")));
    }

    private static List<String> list(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}

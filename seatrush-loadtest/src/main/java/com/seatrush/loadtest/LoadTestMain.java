package com.seatrush.loadtest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import com.seatrush.loadtest.Outcome.Attempt;
import com.seatrush.loadtest.Outcome.Kind;

/**
 * One rush against a running SeatRush server, with whatever lock strategy it currently uses:
 * N users are logged in, a fresh event is created, and then all of them try to hold seats at the
 * same moment. Afterwards the seat map is checked for seats held twice, and the holds are cancelled.
 */
public final class LoadTestMain {

    private LoadTestMain() {
    }

    public static void main(String[] args) throws Exception {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        SeatRushClient client = new SeatRushClient(options.baseUrl());
        System.out.println("SeatRush load test against " + client.baseUri());

        String admin = client.login(options.adminUser(), options.adminPassword());
        long start = System.nanoTime();
        Map<String, String> tokens = Users.logIn(client, options.users());
        System.out.printf("Logged in %d users in %.1f s%n", tokens.size(), (System.nanoTime() - start) / 1e9);

        Scenario scenario = options.scenario();
        long eventId = client.createEvent(admin, eventName(scenario), scenario.rows(), scenario.seatsPerRow());
        List<Long> seatIds = List.copyOf(client.seatMap(eventId).keySet());
        List<Attempt> attempts = scenario.attempts(seatIds, tokens, options.seed());
        String strategy = client.currentStrategy(admin);

        RushResult result = Rush.run(client, eventId, attempts);
        List<String> problems = result.problems(client.seatMap(eventId));
        System.out.println();
        System.out.println(summary(scenario, strategy, result, problems));

        int notCancelled = Rush.cancelHolds(client, result);
        if (notCancelled > 0) {
            System.out.println("Warning: " + notCancelled + " holds could not be cancelled; they expire on their own");
        }
        System.exit(problems.isEmpty() && result.count(Kind.ERROR) == 0 ? 0 : 1);
    }

    static String eventName(Scenario scenario) {
        return "Load test · " + scenario.id() + " · " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    static String summary(Scenario scenario, String strategy, RushResult result, List<String> problems) {
        StringBuilder text = new StringBuilder();
        text.append("Scenario  %s: %s%n".formatted(scenario.id(), scenario.description()));
        text.append("Strategy  %s%n".formatted(strategy));
        text.append("Users     %d, all released at once%n%n".formatted(result.outcomes().size()));
        text.append("  held %6d    taken %6d    busy %6d    errors %4d%n".formatted(
                result.count(Kind.HELD), result.count(Kind.TAKEN), result.count(Kind.BUSY), result.count(Kind.ERROR)));
        text.append("  wall %6.0f ms    throughput %6.0f req/s%n".formatted(result.wallMillis(), result.throughput()));
        text.append("  latency  p50 %.0f ms   p95 %.0f ms   p99 %.0f ms   max %.0f ms%n".formatted(
                result.latencyMillis(50), result.latencyMillis(95), result.latencyMillis(99), result.latencyMillis(100)));
        if (problems.isEmpty()) {
            text.append("  check    OK, no seat was held twice (%d seats held)%n".formatted(result.heldSeats().size()));
        } else {
            text.append("  check    FAILED%n".formatted());
            problems.forEach(problem -> text.append("           ").append(problem).append(System.lineSeparator()));
        }
        result.outcomes().stream()
                .filter(outcome -> outcome.kind() == Kind.ERROR)
                .limit(5)
                .forEach(outcome -> text.append("  error    HTTP %d %s%n".formatted(outcome.status(), outcome.detail())));
        return text.toString();
    }
}

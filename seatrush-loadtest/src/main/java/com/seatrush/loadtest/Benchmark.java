package com.seatrush.loadtest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.seatrush.loadtest.Outcome.Attempt;
import com.seatrush.loadtest.Outcome.Kind;

/**
 * Runs the same rushes once per lock strategy, switching the strategy through the admin API.
 * <p>
 * To keep the comparison fair, all strategies of a round face exactly the same requests (same
 * seed), each round starts with a different strategy, and an unrecorded warm-up rush per scenario
 * lets the JVM and the connection pools warm up first. After each rush its holds are cancelled, so
 * every rush starts from an empty hall.
 */
final class Benchmark {

    /** One recorded rush. */
    record Run(Scenario scenario, String strategy, int round, RushResult result, List<String> problems) {
    }

    private static final long PAUSE_MILLIS = 500;

    private final SeatRushClient client;
    private final String adminToken;
    private final Map<String, String> tokens;

    Benchmark(SeatRushClient client, String adminToken, Map<String, String> tokens) {
        this.client = client;
        this.adminToken = adminToken;
        this.tokens = tokens;
    }

    List<Run> run(List<Scenario> scenarios, List<String> strategies, int rounds, long seed) throws Exception {
        String original = client.currentStrategy(adminToken);
        List<Run> runs = new ArrayList<>();
        try {
            for (Scenario scenario : scenarios) {
                long eventId = client.createEvent(adminToken, LoadTestMain.eventName(scenario), scenario.rows(), scenario.seatsPerRow());
                List<Long> seatIds = List.copyOf(client.seatMap(eventId).keySet());

                client.useStrategy(adminToken, strategies.getFirst());
                rushOnce(eventId, scenario.attempts(seatIds, tokens, seed - 1));
                System.out.printf("%-9s warm-up done%n", scenario.id());

                for (int round = 0; round < rounds; round++) {
                    List<Attempt> attempts = scenario.attempts(seatIds, tokens, seed + round);
                    for (int i = 0; i < strategies.size(); i++) {
                        String strategy = strategies.get((i + round) % strategies.size());
                        client.useStrategy(adminToken, strategy);
                        RushResult result = Rush.run(client, eventId, attempts);
                        Run run = new Run(scenario, strategy, round + 1, result, result.problems(client.seatMap(eventId)));
                        runs.add(run);
                        System.out.println(progressLine(run, rounds));
                        clearHall(eventId, result);
                    }
                }
            }
        } finally {
            client.useStrategy(adminToken, original);
        }
        return runs;
    }

    private void rushOnce(long eventId, List<Attempt> attempts) throws Exception {
        clearHall(eventId, Rush.run(client, eventId, attempts));
    }

    /** Cancels the rush's holds and makes sure the next rush finds every seat free again. */
    private void clearHall(long eventId, RushResult result) throws Exception {
        Rush.cancelHolds(client, result);
        long stillTaken = client.seatMap(eventId).values().stream().filter(status -> !"AVAILABLE".equals(status)).count();
        if (stillTaken > 0) {
            throw new IllegalStateException(stillTaken + " seats are still taken after cancelling the rush's holds");
        }
        Thread.sleep(PAUSE_MILLIS);
    }

    private static String progressLine(Run run, int rounds) {
        RushResult r = run.result();
        return "%-9s %-12s round %d/%d   held %4d  taken %4d  busy %4d  errors %3d   %6.0f ms   p95 %5.0f ms   %s".formatted(
                run.scenario().id(), run.strategy(), run.round(), rounds,
                r.count(Kind.HELD), r.count(Kind.TAKEN), r.count(Kind.BUSY), r.count(Kind.ERROR),
                r.wallMillis(), r.latencyMillis(95), run.problems().isEmpty() ? "OK" : "PROBLEMS");
    }
}

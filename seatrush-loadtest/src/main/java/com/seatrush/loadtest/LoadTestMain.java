package com.seatrush.loadtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.seatrush.loadtest.Benchmark.Run;
import com.seatrush.loadtest.Outcome.Attempt;
import com.seatrush.loadtest.Outcome.Kind;

/**
 * Load test for a running SeatRush server. N users are logged in, then for each scenario a fresh
 * event is created and all of them try to hold seats at the same moment. Afterwards the seat map is
 * checked for seats held twice, and the holds are cancelled again.
 * <p>
 * By default every scenario gets one rush with the server's current lock strategy. With --compare
 * the same rushes run once per strategy and round, and a Markdown and CSV report are written.
 */
public final class LoadTestMain {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
        System.out.printf("Logged in %d users in %.1f s%n%n", tokens.size(), (System.nanoTime() - start) / 1e9);

        boolean passed = options.compare() ? compare(options, client, admin, tokens) : rushEach(options, client, admin, tokens);
        System.exit(passed ? 0 : 1);
    }

    /** One rush per scenario with whatever strategy the server uses right now. */
    private static boolean rushEach(Options options, SeatRushClient client, String admin, Map<String, String> tokens)
            throws Exception {
        String strategy = client.currentStrategy(admin);
        boolean passed = true;
        for (Scenario scenario : options.scenarios()) {
            long eventId = client.createEvent(admin, eventName(scenario), scenario.rows(), scenario.seatsPerRow());
            List<Long> seatIds = List.copyOf(client.seatMap(eventId).keySet());
            List<Attempt> attempts = scenario.attempts(seatIds, tokens, options.seed());

            RushResult result = Rush.run(client, eventId, attempts);
            List<String> problems = result.problems(client.seatMap(eventId));
            System.out.println(summary(scenario, strategy, result, problems));

            int notCancelled = Rush.cancelHolds(client, result);
            if (notCancelled > 0) {
                System.out.println("Warning: " + notCancelled + " holds could not be cancelled; they expire on their own");
            }
            passed &= problems.isEmpty() && result.count(Kind.ERROR) == 0;
        }
        return passed;
    }

    /** Every scenario with every strategy, several rounds each; writes the report files. */
    private static boolean compare(Options options, SeatRushClient client, String admin, Map<String, String> tokens)
            throws Exception {
        List<String> strategies = options.strategies().equals(List.of("all"))
                ? client.availableStrategies(admin)
                : options.strategies();
        String started = LocalDateTime.now().format(STAMP);
        List<Run> runs = new Benchmark(client, admin, tokens).run(options.scenarios(), strategies, options.rounds(), options.seed());

        List<String> header = new ArrayList<>();
        header.add("- Server: %s, %s, %d CPU cores on the load-test machine".formatted(
                client.baseUri(), started, Runtime.getRuntime().availableProcessors()));
        header.add("- %d users per rush, all released at once; %d round%s per strategy and scenario, seed %d".formatted(
                options.users(), options.rounds(), options.rounds() == 1 ? "" : "s", options.seed()));
        header.add("- Every number is the median over the rounds. Held, taken, busy and errors count requests;");
        header.add("  throughput is answered requests per second of the whole rush.");
        String markdown = BenchmarkReport.markdown(runs, String.join("\n", header));

        Files.createDirectories(options.out());
        String file = "comparison-" + started.replace(":", "").replace(" ", "-");
        Path report = options.out().resolve(file + ".md");
        Files.writeString(report, markdown);
        Files.writeString(options.out().resolve(file + ".csv"), BenchmarkReport.csv(runs));
        System.out.println();
        System.out.println(markdown);
        System.out.println("Report written to " + report.toAbsolutePath() + " (and a .csv of every run next to it)");

        return runs.stream().allMatch(run -> run.problems().isEmpty() && run.result().count(Kind.ERROR) == 0);
    }

    static String eventName(Scenario scenario) {
        return "Load test · " + scenario.id() + " · " + LocalDateTime.now().format(STAMP);
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

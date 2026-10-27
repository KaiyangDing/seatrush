package com.seatrush.loadtest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import com.seatrush.loadtest.Benchmark.Run;
import com.seatrush.loadtest.Outcome.Kind;

/** Turns the runs of a comparison into a Markdown report (medians over the rounds) and a CSV of every run. */
final class BenchmarkReport {

    private BenchmarkReport() {
    }

    static String markdown(List<Run> runs, String header) {
        StringBuilder md = new StringBuilder("# SeatRush lock strategy comparison\n\n").append(header).append("\n");
        Map<Scenario, List<Run>> byScenario = runs.stream()
                .collect(Collectors.groupingBy(Run::scenario, LinkedHashMap::new, Collectors.toList()));
        byScenario.forEach((scenario, scenarioRuns) -> {
            md.append("\n## %s: %s (%d x %d seats)\n\n".formatted(
                    scenario.id(), scenario.description(), scenario.rows(), scenario.seatsPerRow()));
            md.append("| Strategy | Held | Taken | Busy | Errors | Rush time (ms) | Throughput (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | Seats held twice |\n");
            md.append("|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|\n");
            Map<String, List<Run>> byStrategy = scenarioRuns.stream()
                    .collect(Collectors.groupingBy(Run::strategy, LinkedHashMap::new, Collectors.toList()));
            byStrategy.forEach((strategy, strategyRuns) -> md.append(row(strategy, strategyRuns)));
        });
        return md.toString();
    }

    private static String row(String strategy, List<Run> runs) {
        long problems = runs.stream().mapToLong(run -> run.problems().size()).sum();
        return "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n".formatted(strategy,
                format(median(runs, r -> r.count(Kind.HELD))),
                format(median(runs, r -> r.count(Kind.TAKEN))),
                format(median(runs, r -> r.count(Kind.BUSY))),
                format(median(runs, r -> r.count(Kind.ERROR))),
                format(median(runs, RushResult::wallMillis)),
                format(median(runs, RushResult::throughput)),
                format(median(runs, r -> r.latencyMillis(50))),
                format(median(runs, r -> r.latencyMillis(95))),
                format(median(runs, r -> r.latencyMillis(99))),
                problems == 0 ? "none" : "**" + problems + " problems**");
    }

    static String csv(List<Run> runs) {
        StringBuilder csv = new StringBuilder(
                "scenario,strategy,round,users,held,taken,busy,errors,rush_ms,throughput,p50_ms,p95_ms,p99_ms,max_ms,problems\n");
        for (Run run : runs) {
            RushResult r = run.result();
            csv.append(String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%d%n",
                    run.scenario().id(), run.strategy(), run.round(), r.outcomes().size(),
                    r.count(Kind.HELD), r.count(Kind.TAKEN), r.count(Kind.BUSY), r.count(Kind.ERROR),
                    r.wallMillis(), r.throughput(), r.latencyMillis(50), r.latencyMillis(95), r.latencyMillis(99),
                    r.latencyMillis(100), run.problems().size()));
        }
        return csv.toString();
    }

    /** The middle value, or the mean of the two middle values for an even number of rounds. */
    static double median(List<Run> runs, ToDoubleFunction<RushResult> metric) {
        double[] values = runs.stream().mapToDouble(run -> metric.applyAsDouble(run.result())).sorted().toArray();
        int middle = values.length / 2;
        return values.length % 2 == 1 ? values[middle] : (values[middle - 1] + values[middle]) / 2;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }
}

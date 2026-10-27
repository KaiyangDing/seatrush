package com.seatrush.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.seatrush.loadtest.Benchmark.Run;
import com.seatrush.loadtest.Outcome.Attempt;
import com.seatrush.loadtest.Outcome.Kind;
import org.junit.jupiter.api.Test;

class BenchmarkReportTest {

    @Test
    void medianTakesTheMiddleRoundOrTheMeanOfTheTwoMiddleOnes() {
        List<Run> odd = List.of(run("redis", 1, 300), run("redis", 2, 100), run("redis", 3, 200));
        List<Run> even = List.of(run("redis", 1, 100), run("redis", 2, 400));

        assertThat(BenchmarkReport.median(odd, RushResult::wallMillis)).isEqualTo(200);
        assertThat(BenchmarkReport.median(even, RushResult::wallMillis)).isEqualTo(250);
    }

    @Test
    void markdownHasOneTablePerScenarioAndOneRowPerStrategy() {
        List<Run> runs = List.of(run("pessimistic", 1, 100), run("pessimistic", 2, 300), run("redis", 1, 500));

        String markdown = BenchmarkReport.markdown(runs, "header");

        assertThat(markdown).contains("## hot-seat: everybody wants the same seat (1 x 10 seats)");
        assertThat(markdown).contains("| pessimistic | 1 | 3 | 0 | 0 | 200 |");
        assertThat(markdown).contains("| redis | 1 | 3 | 0 | 0 | 500 |");
        assertThat(markdown).doesNotContain("problems**");
    }

    @Test
    void csvHasOneLinePerRun() {
        String csv = BenchmarkReport.csv(List.of(run("optimistic", 1, 100), run("optimistic", 2, 100)));

        assertThat(csv.lines()).hasSize(3);
        assertThat(csv.lines().skip(1)).allSatisfy(line -> assertThat(line).startsWith("hot-seat,optimistic,"));
    }

    /** A hot-seat rush of four requests: one held, three taken, lasting the given time. */
    private static Run run(String strategy, int round, long millis) {
        List<Outcome> outcomes = new ArrayList<>();
        Attempt attempt = new Attempt("u", "t", List.of(1L));
        outcomes.add(new Outcome(attempt, Kind.HELD, 201, 1_000_000, 1, ""));
        for (int i = 0; i < 3; i++) {
            outcomes.add(new Outcome(attempt, Kind.TAKEN, 409, 2_000_000, 0, ""));
        }
        return new Run(Scenario.HOT_SEAT, strategy, round, new RushResult(outcomes, millis * 1_000_000), List.of());
    }
}

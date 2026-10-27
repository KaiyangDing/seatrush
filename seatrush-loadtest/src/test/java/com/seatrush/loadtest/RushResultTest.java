package com.seatrush.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.seatrush.loadtest.Outcome.Attempt;
import com.seatrush.loadtest.Outcome.Kind;
import com.seatrush.loadtest.SeatRushClient.Reply;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RushResultTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void repliesAreSortedIntoHeldTakenBusyAndError() {
        Attempt attempt = new Attempt("u", "t", List.of(1L));

        assertThat(Outcome.of(attempt, reply(201, "{\"id\": 5}")).kind()).isEqualTo(Kind.HELD);
        assertThat(Outcome.of(attempt, reply(201, "{\"id\": 5}")).holdId()).isEqualTo(5);
        assertThat(Outcome.of(attempt, reply(409, "{\"unavailableSeatIds\": [1]}")).kind()).isEqualTo(Kind.TAKEN);
        assertThat(Outcome.of(attempt, reply(409, "{\"detail\": \"Seats [1] are busy\"}")).kind()).isEqualTo(Kind.BUSY);
        assertThat(Outcome.of(attempt, reply(500, "")).kind()).isEqualTo(Kind.ERROR);
    }

    @Test
    void latencyPercentilesUseTheNearestRank() {
        RushResult result = new RushResult(List.of(
                outcome(Kind.HELD, 10, 1L), outcome(Kind.TAKEN, 20, 1L), outcome(Kind.TAKEN, 30, 1L),
                outcome(Kind.TAKEN, 40, 1L)), 100_000_000);

        assertThat(result.latencyMillis(50)).isEqualTo(20);
        assertThat(result.latencyMillis(95)).isEqualTo(40);
        assertThat(result.latencyMillis(100)).isEqualTo(40);
        assertThat(result.throughput()).isEqualTo(40);
        assertThat(result.count(Kind.TAKEN)).isEqualTo(3);
    }

    @Test
    void correctRushHasNoProblems() {
        RushResult result = new RushResult(List.of(outcome(Kind.HELD, 1, 1L, 2L), outcome(Kind.TAKEN, 1, 2L, 3L)), 1);

        assertThat(result.problems(Map.of(1L, "HELD", 2L, "HELD", 3L, "AVAILABLE"))).isEmpty();
        assertThat(result.heldSeats()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void seatGivenToTwoRequestsIsReported() {
        RushResult result = new RushResult(List.of(outcome(Kind.HELD, 1, 1L, 2L), outcome(Kind.HELD, 1, 2L, 3L)), 1);

        assertThat(result.problems(Map.of(1L, "HELD", 2L, "HELD", 3L, "HELD")))
                .containsExactly("Seat 2 was given to two requests");
    }

    @Test
    void serverThatDisagreesWithTheAnswersIsReported() {
        RushResult result = new RushResult(List.of(outcome(Kind.HELD, 1, 1L)), 1);

        assertThat(result.problems(Map.of(1L, "AVAILABLE", 2L, "HELD", 3L, "HELD"))).containsExactly(
                "Seat 1 was given out but the server shows it AVAILABLE",
                "The server shows 2 held seats, the successful requests got 1");
    }

    private Reply reply(int status, String body) {
        return new Reply(status, body.isEmpty() ? json.missingNode() : json.readTree(body), 1);
    }

    private static Outcome outcome(Kind kind, long millis, Long... seats) {
        return new Outcome(new Attempt("u", "t", List.of(seats)), kind, 0, millis * 1_000_000, 0, "");
    }
}

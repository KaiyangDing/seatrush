package com.seatrush.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import com.seatrush.loadtest.Outcome.Attempt;
import org.junit.jupiter.api.Test;

class ScenarioTest {

    @Test
    void hotSeatAlwaysWantsTheFirstSeat() {
        List<Attempt> attempts = Scenario.HOT_SEAT.attempts(hall(Scenario.HOT_SEAT), users(20), 1);

        assertThat(attempts).allSatisfy(attempt -> assertThat(attempt.seatIds()).containsExactly(1000L));
    }

    @Test
    void neighboursAreAlwaysInTheSameRow() {
        Scenario spread = Scenario.SPREAD;
        List<Long> seats = hall(spread);

        for (Attempt attempt : spread.attempts(seats, users(2000), 7)) {
            int first = seats.indexOf(attempt.seatIds().get(0));
            int second = seats.indexOf(attempt.seatIds().get(1));
            assertThat(second).isEqualTo(first + 1);
            assertThat(first / spread.seatsPerRow()).isEqualTo(second / spread.seatsPerRow());
        }
    }

    @Test
    void sameSeedGivesTheSameRequests() {
        List<Long> seats = hall(Scenario.HOT_ROW);

        assertThat(Scenario.HOT_ROW.attempts(seats, users(50), 42))
                .isEqualTo(Scenario.HOT_ROW.attempts(seats, users(50), 42))
                .isNotEqualTo(Scenario.HOT_ROW.attempts(seats, users(50), 43));
    }

    @Test
    void scenariosAreFoundByTheirId() {
        assertThat(Scenario.fromId("hot-row")).isEqualTo(Scenario.HOT_ROW);
        assertThatThrownBy(() -> Scenario.fromId("cold")).isInstanceOf(IllegalArgumentException.class);
    }

    /** Seat ids as the seat map would list them: row by row, starting at 1000. */
    private static List<Long> hall(Scenario scenario) {
        return LongStream.range(1000, 1000 + (long) scenario.rows() * scenario.seatsPerRow()).boxed().toList();
    }

    private static Map<String, String> users(int count) {
        Map<String, String> tokens = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            tokens.put("user" + i, "token" + i);
        }
        return tokens;
    }
}

package com.seatrush.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.SortedSet;

import com.seatrush.event.Seat;
import org.junit.jupiter.api.Test;

/** The registry on its own, with stand-in strategies; no Spring and no database needed. */
class SeatLockStrategiesTest {

    private final SeatLockStrategy first = new NamedStrategy("first");
    private final SeatLockStrategy second = new NamedStrategy("second");

    @Test
    void startsWithTheConfiguredStrategy() {
        SeatLockStrategies strategies = new SeatLockStrategies(List.of(first, second), new LockProperties("second"));

        assertThat(strategies.current()).isSameAs(second);
        assertThat(strategies.names()).containsExactly("first", "second");
    }

    @Test
    void switchesAtRuntime() {
        SeatLockStrategies strategies = new SeatLockStrategies(List.of(first, second), new LockProperties("first"));

        assertThat(strategies.use("second")).isSameAs(second);
        assertThat(strategies.current()).isSameAs(second);
    }

    @Test
    void unknownNameIsRejectedAndNothingChanges() {
        SeatLockStrategies strategies = new SeatLockStrategies(List.of(first, second), new LockProperties("first"));

        assertThatThrownBy(() -> strategies.use("magic"))
                .isInstanceOf(UnknownLockStrategyException.class)
                .hasMessageContaining("[first, second]");
        assertThat(strategies.current()).isSameAs(first);
    }

    @Test
    void misconfiguredStartStrategyStopsTheApplication() {
        assertThatThrownBy(() -> new SeatLockStrategies(List.of(first), new LockProperties("magic")))
                .isInstanceOf(UnknownLockStrategyException.class);
    }

    @Test
    void twoStrategiesWithTheSameNameAreRejected() {
        assertThatThrownBy(() -> new SeatLockStrategies(List.of(first, new NamedStrategy("first")), new LockProperties("first")))
                .isInstanceOf(IllegalStateException.class);
    }

    private record NamedStrategy(String name) implements SeatLockStrategy {

        @Override
        public List<Seat> loadSeats(SortedSet<Long> seatIds) {
            return List.of();
        }
    }
}

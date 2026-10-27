package com.seatrush.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.junit.jupiter.api.Test;

class OptionsTest {

    @Test
    void everyOptionHasADefault() {
        Options options = Options.parse();

        assertThat(options.baseUrl()).isEqualTo(URI.create("http://localhost:8080"));
        assertThat(options.users()).isEqualTo(300);
        assertThat(options.scenarios()).containsExactly(Scenario.HOT_SEAT, Scenario.HOT_ROW, Scenario.SPREAD);
        assertThat(options.compare()).isFalse();
        assertThat(options.rounds()).isEqualTo(3);
    }

    @Test
    void optionsAreReadAsNameValuePairs() {
        Options options = Options.parse("--users", "50", "--scenario", "hot-seat", "--base-url", "http://server:9000");

        assertThat(options.users()).isEqualTo(50);
        assertThat(options.scenarios()).containsExactly(Scenario.HOT_SEAT);
        assertThat(options.baseUrl()).isEqualTo(URI.create("http://server:9000"));
    }

    @Test
    void compareTakesAListOfStrategies() {
        Options options = Options.parse("--compare", "optimistic, redis", "--scenario", "spread,hot-row", "--rounds", "5");

        assertThat(options.compare()).isTrue();
        assertThat(options.strategies()).containsExactly("optimistic", "redis");
        assertThat(options.scenarios()).containsExactly(Scenario.SPREAD, Scenario.HOT_ROW);
        assertThat(options.rounds()).isEqualTo(5);
    }

    @Test
    void unknownOrIncompleteOptionsAreRejected() {
        assertThatThrownBy(() -> Options.parse("--speed", "fast")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Options.parse("--users")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Options.parse("--users", "0")).isInstanceOf(IllegalArgumentException.class);
    }
}

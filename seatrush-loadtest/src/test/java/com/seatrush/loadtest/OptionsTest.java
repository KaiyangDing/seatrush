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
        assertThat(options.scenario()).isEqualTo(Scenario.HOT_ROW);
    }

    @Test
    void optionsAreReadAsNameValuePairs() {
        Options options = Options.parse("--users", "50", "--scenario", "hot-seat", "--base-url", "http://server:9000");

        assertThat(options.users()).isEqualTo(50);
        assertThat(options.scenario()).isEqualTo(Scenario.HOT_SEAT);
        assertThat(options.baseUrl()).isEqualTo(URI.create("http://server:9000"));
    }

    @Test
    void unknownOrIncompleteOptionsAreRejected() {
        assertThatThrownBy(() -> Options.parse("--speed", "fast")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Options.parse("--users")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Options.parse("--users", "0")).isInstanceOf(IllegalArgumentException.class);
    }
}

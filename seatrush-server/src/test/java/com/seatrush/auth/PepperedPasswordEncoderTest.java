package com.seatrush.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PepperedPasswordEncoderTest {

    private final PepperedPasswordEncoder encoder = new PepperedPasswordEncoder("test-pepper");

    @Test
    void matchesOnlyTheOriginalPassword() {
        String hash = encoder.encode("correct horse");

        assertThat(hash).doesNotContain("correct horse");
        assertThat(encoder.matches("correct horse", hash)).isTrue();
        assertThat(encoder.matches("wrong horse", hash)).isFalse();
    }

    @Test
    void samePasswordGetsADifferentSaltEachTime() {
        assertThat(encoder.encode("secret123")).isNotEqualTo(encoder.encode("secret123"));
    }

    @Test
    void hashIsUselessWithoutThePepper() {
        String hash = encoder.encode("secret123");

        assertThat(new PepperedPasswordEncoder("other-pepper").matches("secret123", hash)).isFalse();
    }
}
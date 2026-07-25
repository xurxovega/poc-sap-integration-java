package com.poc.sap.common.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationResultTest {

    @Test
    void validFactoryProducesValid() {
        assertThat(ValidationResult.success().valid()).isTrue();
        assertThat(ValidationResult.success().errors()).isEmpty();
    }

    @Test
    void invalidFactoryCollectsErrors() {
        ValidationResult r = ValidationResult.invalid("a", "b");
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).containsExactly("a", "b");
    }

    @Test
    void andChainingAccumulatesAllFailures() {
        ValidationResult r = ValidationResult.success()
                .and(v -> false, "first failure")
                .and(v -> true, "passes")
                .and(v -> false, "second failure");
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).containsExactly("first failure", "second failure");
    }

    @Test
    void andChainingPassesThroughWhenAllValid() {
        ValidationResult r = ValidationResult.success()
                .and(v -> true, "ok1")
                .and(v -> true, "ok2");
        assertThat(r.valid()).isTrue();
    }
}

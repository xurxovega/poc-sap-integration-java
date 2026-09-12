package com.poc.sap.common.sap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec sdd/common/observabilidad.md R-4 / AC-4 (auditoria A10). */
class RetryBudgetGuardTest {

    /** Defaults del cliente SAP: 5 llamadas x (3 x 20 s + 0,5 s + 1 s) = 307,5 s. */
    @Test
    void worstCaseAddsTimeoutsAndExponentialBackoffPerCall() {
        assertThat(RetryBudgetGuard.worstCaseMillis(3, 500, 20_000, 5)).isEqualTo(307_500L);
        assertThat(RetryBudgetGuard.worstCaseMillis(1, 500, 20_000, 1)).isEqualTo(20_000L);
    }

    /** Con el max.poll.interval por defecto de Kafka (5 min) el presupuesto no cabe: no se arranca. */
    @Test
    void failsAtStartupWhenBudgetReachesMaxPollInterval() {
        RetryBudgetGuard guard = new RetryBudgetGuard(3, 500, 20_000, 5, 300_000);

        assertThatThrownBy(guard::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("307500 ms")
                .hasMessageContaining("KAFKA_MAX_POLL_INTERVAL_MS");
    }

    /** Con el max.poll.interval que fija application-common.yml (15 min) arranca. */
    @Test
    void passesWithTheProjectDefaults() {
        assertThatCode(new RetryBudgetGuard(3, 500, 20_000, 5, 900_000)::validate)
                .doesNotThrowAnyException();
    }
}

package com.poc.sap.common.sap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec sdd/common/observabilidad.md R-4 / AC-4 (auditoria A10) y upsert-idempotente-sap R-6 (2A-12). */
class RetryBudgetGuardTest {

    /** Presupuesto de una sola clase de llamada (como antes de separar lectura y escritura). */
    @Test
    void worstCaseAddsTimeoutsAndExponentialBackoffPerCall() {
        assertThat(RetryBudgetGuard.worstCaseMillis(0, 3, 500, 5_000, 20_000, 0, 5, false))
                .isEqualTo(307_500L);
        assertThat(RetryBudgetGuard.worstCaseMillis(0, 1, 500, 5_000, 20_000, 0, 1, false))
                .isEqualTo(20_000L);
    }

    /**
     * upsert AC-9 (2A-12): con la verificacion previa cada feature hace dos llamadas,
     * pero no cuestan lo mismo. Sumarlas con la formula antigua (una sola
     * multiplicacion con el timeout de escritura y los 3 intentos) daria 676.500 ms,
     * al borde de max.poll.interval.ms. Separadas —los lookups con timeout de 5 s y
     * 3 intentos, las escrituras con 20 s y 2— el peor caso real BAJA respecto a hoy.
     */
    @Test
    void budgetSeparatesLookupsFromWrites() {
        long lookups = 6L * (3 * 5_000 + 1_500);      // 99.000
        long writes = 5L * (2 * 20_000 + 500);        // 202.500

        long worst = RetryBudgetGuard.worstCaseMillis(3, 2, 500, 5_000, 20_000, 6, 5, false);

        assertThat(worst).isEqualTo(lookups + writes).isEqualTo(301_500L);
        assertThat(worst).isLessThan(RetryBudgetGuard.worstCaseMillis(0, 3, 500, 5_000, 20_000, 0, 5, false));
    }

    /** El fetch CSRF va FUERA del retry y se paga dos veces (fetch + refresco tras 403). */
    @Test
    void csrfFetchAddsItsOwnCost() {
        long sinCsrf = RetryBudgetGuard.worstCaseMillis(3, 2, 500, 5_000, 20_000, 6, 5, false);

        long conCsrf = RetryBudgetGuard.worstCaseMillis(3, 2, 500, 5_000, 20_000, 6, 5, true);

        assertThat(conCsrf - sinCsrf).isEqualTo(2 * 20_000L);
    }

    /**
     * ADR-0011 + upsert R-9: el peor caso de un mensaje no acaba cuando SAP
     * responde. Si el circuito esta abierto, el {@code DefaultErrorHandler} duerme
     * el hilo del consumidor con su backoff largo
     * ({@code app.kafka.retry.circuit-open-backoff-ms}) tantas veces como intentos
     * ({@code app.kafka.retry.max-attempts}), y ese tiempo cuenta igual para
     * {@code max.poll.interval.ms}. Sin sumarlo, el presupuesto mentia en 90 s.
     */
    @Test
    void budgetIncludesTheKafkaCircuitOpenBackoff() {
        long sinKafka = RetryBudgetGuard.worstCaseMillis(3, 2, 500, 5_000, 20_000, 6, 5, false, 0, 0);

        long conKafka = RetryBudgetGuard.worstCaseMillis(3, 2, 500, 5_000, 20_000, 6, 5, false, 3, 30_000);

        assertThat(sinKafka).isEqualTo(301_500L);
        assertThat(conKafka - sinKafka).isEqualTo(3 * 30_000L);
        assertThat(conKafka).isEqualTo(391_500L).isLessThan(900_000L);
    }

    /** Con el max.poll.interval por defecto de Kafka (5 min) el presupuesto no cabe: no se arranca. */
    @Test
    void failsAtStartupWhenBudgetReachesMaxPollInterval() {
        RetryBudgetGuard guard = new RetryBudgetGuard(3, 2, 500, 20_000, 5_000, 5, 6, false, 3, 30_000, 300_000);

        assertThatThrownBy(guard::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("391500 ms")
                .hasMessageContaining("KAFKA_MAX_POLL_INTERVAL_MS");
    }

    /** Con el max.poll.interval que fija application-common.yml (15 min) arranca. */
    @Test
    void passesWithTheProjectDefaults() {
        assertThatCode(new RetryBudgetGuard(3, 2, 500, 20_000, 5_000, 5, 6, false, 3, 30_000, 900_000)::validate)
                .doesNotThrowAnyException();
    }
}

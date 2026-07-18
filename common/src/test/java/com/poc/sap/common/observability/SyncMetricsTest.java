package com.poc.sap.common.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test unit del {@link SyncMetrics} (SPEC.md §7; TECH.md §9). Usa
 * {@link SimpleMeterRegistry} de Micrometer para no levantar Prometheus.
 */
class SyncMetricsTest {

    private SimpleMeterRegistry registry;
    private SyncMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new SyncMetrics(registry);
    }

    @Test
    void incrementStateRegistersCounter() {
        metrics.incrementState("customer", "SENT_SAP");

        Double count = registry.get("sap_sync_state_total")
                .tag("domain", "customer")
                .tag("state", "SENT_SAP")
                .counter().count();

        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void incrementStateTwiceAccumulates() {
        metrics.incrementState("article", "VALID");
        metrics.incrementState("article", "VALID");

        Double count = registry.get("sap_sync_state_total")
                .tag("domain", "article")
                .tag("state", "VALID")
                .counter().count();

        assertThat(count).isEqualTo(2.0);
    }

    @Test
    void recordStageDurationRegistersTimer() {
        metrics.recordStageDuration("customer", "fetch", 42L);

        assertThat(
                registry.get("sap_sync_stage_duration")
                        .tag("domain", "customer")
                        .tag("stage", "fetch")
                        .timer())
                .isNotNull();
    }

    @Test
    void incrementStateIsSafeWhenCounterAlreadyRegistered() {
        metrics.incrementState("supplier", "ERROR");
        metrics.incrementState("supplier", "ERROR");
        metrics.incrementState("supplier", "SAP_ERROR");

        // reusar el mismo counter no explota
        assertThat(registry.get("sap_sync_state_total")
                .tag("domain", "supplier")
                .tag("state", "ERROR")
                .counter().count()).isEqualTo(2.0);
    }
}
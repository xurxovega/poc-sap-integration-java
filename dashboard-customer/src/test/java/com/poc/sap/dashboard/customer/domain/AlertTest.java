package com.poc.sap.dashboard.customer.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests del record {@link Alert} (UI-001 H-2 F-9): alerta emitida por el
 * customer-app en el topic {@code sap.sync.alerts}. El dashboard la persiste
 * en Mongo {@code alerts} con TTL 30 dias (F-9 promoted) y permite al
 * operador reconocerla (F-9, AC-6).
 */
class AlertTest {

    @Test
    void carriesEntityAndDetail() {
        Instant opened = Instant.parse("2026-09-18T10:00:00Z");
        Alert a = new Alert("a-1", "C-1", "FAILURE", "kafka",
                "IBAN rejected: ES7621000418401234567890", opened, null, null);

        assertThat(a.alertId()).isEqualTo("a-1");
        assertThat(a.entityId()).isEqualTo("C-1");
        assertThat(a.severity()).isEqualTo("FAILURE");
        assertThat(a.origin()).isEqualTo("kafka");
        assertThat(a.detail()).contains("ES7621");
        assertThat(a.openedAt()).isEqualTo(opened);
        assertThat(a.ackedAt()).isNull();
        assertThat(a.ackedBy()).isNull();
    }

    @Test
    void acknowledgementIsAcked() {
        Instant opened = Instant.parse("2026-09-18T10:00:00Z");
        Instant acked = opened.plusSeconds(60);
        Alert acked1 = new Alert("a-1", "C-1", "FAILURE", "kafka", "x", opened, acked, "sap-write:ana");

        assertThat(acked1.ackedAt()).isEqualTo(acked);
        assertThat(acked1.ackedBy()).isEqualTo("sap-write:ana");
    }
}

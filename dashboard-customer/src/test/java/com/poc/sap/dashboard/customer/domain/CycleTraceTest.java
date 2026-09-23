package com.poc.sap.dashboard.customer.domain;

import com.poc.sap.common.domain.SyncState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Traza de pasos de un envio: cabecera y lista de pasos por linea.
 */
class CycleTraceTest {

    @Test
    void holdsStepsInOrder() {
        Instant t0 = Instant.parse("2026-09-18T10:00:00Z");
        Instant t1 = t0.plusSeconds(1);
        CycleTrace.Step s1 = new CycleTrace.Step("C-1", SyncState.RECEIVED, null, t0);
        CycleTrace.Step s2 = new CycleTrace.Step("C-1:BANKING", SyncState.SAP_ERROR, "IBAN invalido", t1);

        CycleTrace trace = new CycleTrace("cyc-7", "h-1", t0, t1, List.of(s1, s2));

        assertThat(trace.cycleId()).isEqualTo("cyc-7");
        assertThat(trace.payloadHash()).isEqualTo("h-1");
        assertThat(trace.steps()).hasSize(2);
        assertThat(trace.steps().get(0).state()).isEqualTo(SyncState.RECEIVED);
        assertThat(trace.steps().get(1).state()).isEqualTo(SyncState.SAP_ERROR);
    }
}

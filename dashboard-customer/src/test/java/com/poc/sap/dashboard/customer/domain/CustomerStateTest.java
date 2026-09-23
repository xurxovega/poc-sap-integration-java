package com.poc.sap.dashboard.customer.domain;

import com.poc.sap.common.domain.SyncState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Estado que sirve el dashboard: cabecera agregada + estado de cada
 * feature + traza del ultimo ciclo (lo que uso el operador cuando llega
 * una alerta de sincronizacion parcial, ADR-0010).
 */
class CustomerStateTest {

    @Test
    void holdsAggregateFeaturesAndTrace() {
        Instant at = Instant.parse("2026-09-18T10:00:00Z");
        FeatureState agg = new FeatureState(null, SyncState.SENT_SAP, "h-2", "cyc-7", null, at);
        FeatureState banking = new FeatureState("BANKING", SyncState.SAP_ERROR, "h-2", "cyc-7", "IBAN invalido", at);
        CycleTrace.Step step = new CycleTrace.Step("C-1:BANKING", SyncState.SAP_ERROR, "IBAN invalido", at);

        CustomerState s = new CustomerState("C-1", agg, Map.of("BANKING", banking),
                new CycleTrace("cyc-7", "h-2", at, at, List.of(step)));

        assertThat(s.entityId()).isEqualTo("C-1");
        assertThat(s.aggregate()).isSameAs(agg);
        assertThat(s.features()).containsKey("BANKING");
        assertThat(s.lastCycle().cycleId()).isEqualTo("cyc-7");
    }
}

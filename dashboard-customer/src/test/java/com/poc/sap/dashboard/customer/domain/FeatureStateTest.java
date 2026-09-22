package com.poc.sap.dashboard.customer.domain;

import com.poc.sap.common.domain.SyncState;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Una linea del estado de sincronizacion: feature, ultimo estado, motivo,
 * hash, ciclo, instante.
 */
class FeatureStateTest {

    @Test
    void carriesEveryField() {
        Instant at = Instant.parse("2026-09-18T10:00:00Z");
        FeatureState f = new FeatureState("ADDRESS", SyncState.SENT_SAP, "h-1", "cyc-7", null, at);

        assertThat(f.feature()).isEqualTo("ADDRESS");
        assertThat(f.state()).isEqualTo(SyncState.SENT_SAP);
        assertThat(f.payloadHash()).isEqualTo("h-1");
        assertThat(f.cycleId()).isEqualTo("cyc-7");
        assertThat(f.detail()).isNull();
        assertThat(f.at()).isEqualTo(at);
    }
}

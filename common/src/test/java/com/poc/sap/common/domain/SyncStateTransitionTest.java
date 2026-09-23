package com.poc.sap.common.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

class SyncStateTransitionTest {

    @Test
    void buildsWithDefaultsTimestamp() {
        SyncStateTransition t = new SyncStateTransition(
                "C-1", "customer", null, SyncState.RECEIVED, "cdc", "hash123", null);
        assertThat(t.entityId()).isEqualTo("C-1");
        assertThat(t.domain()).isEqualTo("customer");
        assertThat(t.from()).isNull();
        assertThat(t.to()).isEqualTo(SyncState.RECEIVED);
        assertThat(t.origin()).isEqualTo("cdc");
        assertThat(t.payloadHash()).isEqualTo("hash123");
        assertThat(t.timestamp()).isNotNull();
        assertThat(t.cycleId()).isNull();
        assertThat(t.detail()).isNull();
    }

    @Test
    void rejectsBlankEntityId() {
        assertThatThrownBy(() -> new SyncStateTransition(
                "", "customer", null, SyncState.RECEIVED, "cdc", "h", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullTo() {
        assertThatThrownBy(() -> new SyncStateTransition(
                "C-1", "customer", null, null, "cdc", "h", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * AC-17 (sdd/common/maquina-de-estados.md): toda transicion a un estado de
     * error persiste su motivo truncado a {@code MAX_DETAIL} caracteres. La
     * respuesta de SAP puede traer un cuerpo enorme y la coleccion de estado no
     * puede crecer sin limite por ello.
     */
    @Test
    void detailIsTruncated() {
        String huge = "x".repeat(SyncStateTransition.MAX_DETAIL + 300);

        SyncStateTransition t = new SyncStateTransition(
                "C-1", "customer", SyncState.SENDING_SAP, SyncState.SAP_ERROR,
                "address", "h-1", Instant.now(), "cyc-1", huge);

        assertThat(SyncStateTransition.MAX_DETAIL).isEqualTo(512);
        assertThat(t.detail()).hasSize(SyncStateTransition.MAX_DETAIL);
    }

    /**
     * AC-18 (sdd/common/maquina-de-estados.md): el identificador de ciclo viaja en
     * la transicion; es lo que permite unir las lineas de un mismo envio. Las
     * transiciones anteriores a este cambio no lo llevan y se siguen leyendo.
     */
    @Test
    void cycleIdIsCarriedThrough() {
        SyncStateTransition withCycle = new SyncStateTransition(
                "C-1:ADDRESS", "customer", SyncState.VALID, SyncState.SENDING_SAP,
                "address", "h-1", Instant.now(), "cyc-42", null);
        SyncStateTransition legacy = new SyncStateTransition(
                "C-1:ADDRESS", "customer", null, SyncState.VALIDATING,
                "address", "h-1", Instant.now());

        assertThat(withCycle.cycleId()).isEqualTo("cyc-42");
        assertThat(withCycle.detail()).isNull();
        assertThat(legacy.cycleId()).isNull();
    }
}

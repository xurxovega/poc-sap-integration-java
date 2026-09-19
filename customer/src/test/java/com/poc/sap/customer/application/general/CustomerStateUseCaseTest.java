package com.poc.sap.customer.application.general;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec sdd/customer/sincronizacion-cliente.md AC-10 y AC-15 (ADR-0010): donde se quedo cada parte. */
@ExtendWith(MockitoExtension.class)
class CustomerStateUseCaseTest {

    @Mock SyncStateRepositoryPort stateRepo;

    private static SyncStateTransition t(String id, SyncState to, String hash, String ts) {
        return t(id, to, hash, ts, null, null);
    }

    private static SyncStateTransition t(String id, SyncState to, String hash, String ts,
                                         String cycleId, String detail) {
        return new SyncStateTransition(id, "customer", null, to, "cdc", hash, Instant.parse(ts), cycleId, detail);
    }

    @Test
    void reportsAggregateAndEveryFeatureLineWithItsLastState() {
        lenient().when(stateRepo.lastTransition(eq("customer"), anyString())).thenReturn(Optional.empty());
        when(stateRepo.lastTransition("customer", "C-1"))
                .thenReturn(Optional.of(t("C-1", SyncState.SAP_ERROR, "h-2", "2026-09-12T10:00:01Z")));
        when(stateRepo.lastTransition("customer", "C-1:ADDRESS"))
                .thenReturn(Optional.of(t("C-1:ADDRESS", SyncState.SENT_SAP, "h-2", "2026-09-12T10:00:00Z")));
        when(stateRepo.lastTransition("customer", "C-1:BANKING"))
                .thenReturn(Optional.of(t("C-1:BANKING", SyncState.SAP_ERROR, "h-2", "2026-09-12T10:00:01Z")));

        var state = new CustomerStateUseCase(stateRepo).of("C-1");

        assertThat(state.aggregate().state()).isEqualTo(SyncState.SAP_ERROR);
        assertThat(state.aggregate().payloadHash()).isEqualTo("h-2");
        assertThat(state.features().keySet()).containsExactly("ADDRESS", "BANKING");
        assertThat(state.features().get("BANKING").state()).isEqualTo(SyncState.SAP_ERROR);
        assertThat(state.features().get("ADDRESS").state()).isEqualTo(SyncState.SENT_SAP);
        assertThat(state.lastCycle()).isNull();   // ciclo anterior al cycleId: no hubo traza
        // N8: no se lee el historial entero de cinco lineas para saber donde estan
        verify(stateRepo, never()).history(anyString(), anyString());
    }

    @Test
    void unknownEntityHasNoAggregateAndNoFeatures() {
        when(stateRepo.lastTransition(eq("customer"), anyString())).thenReturn(Optional.empty());

        var state = new CustomerStateUseCase(stateRepo).of("C-404");

        assertThat(state.aggregate()).isNull();
        assertThat(state.features()).isEmpty();
        assertThat(state.lastCycle()).isNull();
    }

    /**
     * AC-15 (sdd/customer/sincronizacion-cliente.md): tras un fallo parcial, el
     * estado devuelve la traza ordenada de pasos del ultimo ciclo, con el motivo de
     * cada parte. Es la respuesta a "donde ha dado el error" de una alerta.
     */
    @Test
    void lastCycleTraceListsEveryStepWithItsReason() {
        lenient().when(stateRepo.lastTransition(eq("customer"), anyString())).thenReturn(Optional.empty());
        when(stateRepo.lastTransition("customer", "C-1")).thenReturn(Optional.of(
                t("C-1", SyncState.SAP_ERROR, "h-2", "2026-09-12T10:00:03Z", "cyc-7", null)));
        when(stateRepo.lastTransition("customer", "C-1:ADDRESS")).thenReturn(Optional.of(
                t("C-1:ADDRESS", SyncState.SENT_SAP, "h-2", "2026-09-12T10:00:01Z", "cyc-7", null)));
        when(stateRepo.lastTransition("customer", "C-1:BANKING")).thenReturn(Optional.of(
                t("C-1:BANKING", SyncState.COMMUNICATION_ERROR, "h-2", "2026-09-12T10:00:02Z", "cyc-7",
                        "circuito SAP abierto")));
        when(stateRepo.cycle("customer", "cyc-7")).thenReturn(List.of(
                t("C-1", SyncState.SENDING_SAP, "h-2", "2026-09-12T10:00:00Z", "cyc-7", null),
                t("C-1:ADDRESS", SyncState.SENT_SAP, "h-2", "2026-09-12T10:00:01Z", "cyc-7", null),
                t("C-1:BANKING", SyncState.COMMUNICATION_ERROR, "h-2", "2026-09-12T10:00:02Z", "cyc-7",
                        "circuito SAP abierto"),
                t("C-1", SyncState.SAP_ERROR, "h-2", "2026-09-12T10:00:03Z", "cyc-7", null)));

        var state = new CustomerStateUseCase(stateRepo).of("C-1");

        assertThat(state.aggregate().cycleId()).isEqualTo("cyc-7");
        assertThat(state.features().get("BANKING").detail()).isEqualTo("circuito SAP abierto");
        assertThat(state.lastCycle().cycleId()).isEqualTo("cyc-7");
        assertThat(state.lastCycle().payloadHash()).isEqualTo("h-2");
        assertThat(state.lastCycle().startedAt()).isEqualTo(Instant.parse("2026-09-12T10:00:00Z"));
        assertThat(state.lastCycle().endedAt()).isEqualTo(Instant.parse("2026-09-12T10:00:03Z"));
        assertThat(state.lastCycle().steps()).extracting(CustomerStateUseCase.Step::line)
                .containsExactly("C-1", "C-1:ADDRESS", "C-1:BANKING", "C-1");
        assertThat(state.lastCycle().steps()).extracting(CustomerStateUseCase.Step::detail)
                .containsExactly(null, null, "circuito SAP abierto", null);
    }
}

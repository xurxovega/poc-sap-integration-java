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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Spec sdd/customer/sincronizacion-cliente.md AC-10 (ADR-0010): donde se quedo cada parte. */
@ExtendWith(MockitoExtension.class)
class CustomerStateUseCaseTest {

    @Mock SyncStateRepositoryPort stateRepo;

    private static SyncStateTransition t(String id, SyncState to, String hash, String ts) {
        return new SyncStateTransition(id, "customer", null, to, "cdc", hash, Instant.parse(ts));
    }

    @Test
    void reportsAggregateAndEveryFeatureLineWithItsLastState() {
        when(stateRepo.history(eq("customer"), anyString())).thenReturn(List.of());
        when(stateRepo.history("customer", "C-1")).thenReturn(List.of(
                t("C-1", SyncState.SENDING_SAP, "h-2", "2026-09-12T10:00:00Z"),
                t("C-1", SyncState.SAP_ERROR, "h-2", "2026-09-12T10:00:01Z")));
        when(stateRepo.history("customer", "C-1:ADDRESS")).thenReturn(List.of(t("C-1:ADDRESS", SyncState.SENT_SAP, "h-2", "2026-09-12T10:00:00Z")));
        when(stateRepo.history("customer", "C-1:BANKING")).thenReturn(List.of(t("C-1:BANKING", SyncState.SAP_ERROR, "h-2", "2026-09-12T10:00:01Z")));

        var state = new CustomerStateUseCase(stateRepo).of("C-1");

        assertThat(state.aggregate().state()).isEqualTo(SyncState.SAP_ERROR);
        assertThat(state.aggregate().payloadHash()).isEqualTo("h-2");
        assertThat(state.features().keySet()).containsExactly("ADDRESS", "BANKING");
        assertThat(state.features().get("BANKING").state()).isEqualTo(SyncState.SAP_ERROR);
        assertThat(state.features().get("ADDRESS").state()).isEqualTo(SyncState.SENT_SAP);
    }

    @Test
    void unknownEntityHasNoAggregateAndNoFeatures() {
        when(stateRepo.history(eq("customer"), anyString())).thenReturn(List.of());

        var state = new CustomerStateUseCase(stateRepo).of("C-404");

        assertThat(state.aggregate()).isNull();
        assertThat(state.features()).isEmpty();
    }
}

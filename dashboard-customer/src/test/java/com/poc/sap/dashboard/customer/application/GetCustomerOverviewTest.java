package com.poc.sap.dashboard.customer.application;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.dashboard.customer.domain.AddressData;
import com.poc.sap.dashboard.customer.domain.CycleTrace;
import com.poc.sap.dashboard.customer.domain.CustomerSnapshot;
import com.poc.sap.dashboard.customer.domain.FeatureState;
import com.poc.sap.dashboard.customer.application.GetCustomerOverview;
import com.poc.sap.dashboard.customer.domain.port.CustomerImageReader;
import com.poc.sap.dashboard.customer.domain.port.CustomerStateReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Vista por entidad (UI-001 H-2 AC-1): orquesta {@link CustomerImageReader},
 * {@link CustomerStateReader} y compone el {@code CustomerState}.
 */
@ExtendWith(MockitoExtension.class)
class GetCustomerOverviewTest {

    @Mock CustomerImageReader images;
    @Mock CustomerStateReader states;

    @InjectMocks GetCustomerOverview useCase;

    private static final Instant AT = Instant.parse("2026-09-18T10:00:00Z");

    @Test
    void returnsEmptyWhenNoSnapshotExists() {
        when(images.findById("C-1")).thenReturn(Optional.empty());

        Optional<CustomerSnapshot> result = useCase.snapshot("C-1");

        assertThat(result).isEmpty();
    }

    @Test
    void returnsTheSnapshot() {
        CustomerSnapshot snap = new CustomerSnapshot("C-1", "CUST-001", "Acme",
                CustomerSnapshot.Status.ACTIVE,
                new AddressData("Calle 1", "Madrid", "28001", "ES", "M"), null, null, null);
        when(images.findById("C-1")).thenReturn(Optional.of(snap));

        Optional<CustomerSnapshot> result = useCase.snapshot("C-1");

        assertThat(result).contains(snap);
    }

    @Test
    void composesStateFromImageAndStateReader() {
        CustomerSnapshot snap = new CustomerSnapshot("C-1", "CUST-001", "Acme",
                CustomerSnapshot.Status.ACTIVE,
                new AddressData("Calle 1", "Madrid", "28001", "ES", "M"), null, null, null);
        when(images.findById("C-1")).thenReturn(Optional.of(snap));
        FeatureState agg = new FeatureState(null, SyncState.SENT_SAP, "h-1", "cyc-7", null, AT);
        FeatureState bank = new FeatureState("BANKING", SyncState.SAP_ERROR, "h-1", "cyc-7", "IBAN invalido", AT);
        when(states.aggregateOf("C-1")).thenReturn(agg);
        when(states.featuresOf("C-1")).thenReturn(Map.of("BANKING", bank));
        when(states.lastCycleOf("C-1")).thenReturn(
                new CycleTrace("cyc-7", "h-1", AT, AT, List.of(
                        new CycleTrace.Step("C-1", SyncState.SENT_SAP, null, AT),
                        new CycleTrace.Step("C-1:BANKING", SyncState.SAP_ERROR, "IBAN invalido", AT))));

        var result = useCase.stateOf("C-1").orElseThrow();

        assertThat(result.entityId()).isEqualTo("C-1");
        assertThat(result.aggregate()).isSameAs(agg);
        assertThat(result.features()).containsKey("BANKING");
        assertThat(result.lastCycle().cycleId()).isEqualTo("cyc-7");
    }

    @Test
    void returnsEmptyForStateWhenNoSnapshot() {
        when(images.findById("C-1")).thenReturn(Optional.empty());

        assertThat(useCase.stateOf("C-1")).isEmpty();
    }
}

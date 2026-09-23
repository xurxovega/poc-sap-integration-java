package com.poc.sap.dashboard.customer.application;

import com.poc.sap.dashboard.customer.domain.AddressData;
import com.poc.sap.dashboard.customer.domain.CustomerSnapshot;
import com.poc.sap.dashboard.customer.domain.port.CustomerHistoryReader;
import com.poc.sap.dashboard.customer.application.GetHistoryDiff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Diff entre dos versiones del historico (UI-001 H-2): compara
 * directamente los payloads (sin util diff externa) para no meter JsonDiff
 * en dashboard-customer (la lib es del modulo common pero reusar el servicio
 * diff del customer atravesaria la frontera).
 */
@ExtendWith(MockitoExtension.class)
class GetHistoryDiffTest {

    @Mock CustomerHistoryReader history;
    @InjectMocks GetHistoryDiff useCase;

    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-02T00:00:00Z");

    private static CustomerSnapshot snap(String name) {
        return new CustomerSnapshot("C-1", "CUST-001", name,
                CustomerSnapshot.Status.ACTIVE,
                new AddressData("Calle 1", "Madrid", "28001", "ES", null), null, null, null);
    }

    @Test
    void comparesLatestAgainstPreviousWhenNoHashes() {
        when(history.historyOf("C-1")).thenReturn(List.of(
                new CustomerHistoryReader.HistoryVersion("C-1", "h-2", T2, snap("ACME NUEVA")),
                new CustomerHistoryReader.HistoryVersion("C-1", "h-1", T1, snap("ACME VIEJA"))));

        GetHistoryDiff.Diff d = useCase.diff("C-1", null, null);

        assertThat(d.fromHash()).isEqualTo("h-1");
        assertThat(d.toHash()).isEqualTo("h-2");
        assertThat(d.changedFields()).contains("name");
        assertThat(d.changes().get("name").from()).isEqualTo("ACME VIEJA");
        assertThat(d.changes().get("name").to()).isEqualTo("ACME NUEVA");
    }

    @Test
    void throwsWhenEntityHasNoHistory() {
        when(history.historyOf("missing")).thenReturn(List.of());

        assertThatThrownBy(() -> useCase.diff("missing", null, null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void throwsWhenTargetHashMissing() {
        when(history.historyOf("C-1")).thenReturn(List.of(
                new CustomerHistoryReader.HistoryVersion("C-1", "h-1", T1, snap("ACME"))));

        assertThatThrownBy(() -> useCase.diff("C-1", null, "h-unknown"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("h-unknown");
    }
}

package com.poc.sap.dashboard.customer.application;

import com.poc.sap.dashboard.customer.domain.CustomerSnapshot;
import com.poc.sap.dashboard.customer.domain.port.CustomerHistoryReader;
import com.poc.sap.dashboard.customer.application.GetHistory;
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
 * Historico (UI-001 H-2): delega en {@link CustomerHistoryReader}.
 */
@ExtendWith(MockitoExtension.class)
class GetHistoryTest {

    @Mock CustomerHistoryReader history;

    @InjectMocks GetHistory useCase;

    private static final Instant AT = Instant.parse("2026-09-18T10:00:00Z");

    @Test
    void delegatesToHistoryReader() {
        CustomerSnapshot s = new CustomerSnapshot("C-1", "CUST-001", "Acme",
                CustomerSnapshot.Status.ACTIVE, null, null, null, null);
        when(history.historyOf("C-1")).thenReturn(List.of(
                new CustomerHistoryReader.HistoryVersion("C-1", "h-2", AT, s)));

        var versions = useCase.of("C-1");

        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).payloadHash()).isEqualTo("h-2");
    }

    @Test
    void unknownEntityThrows() {
        when(history.historyOf("missing")).thenReturn(List.of());

        assertThatThrownBy(() -> useCase.of("missing"))
                .isInstanceOf(NoSuchElementException.class);
    }
}

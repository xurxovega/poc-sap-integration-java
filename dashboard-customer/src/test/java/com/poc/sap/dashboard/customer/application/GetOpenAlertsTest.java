package com.poc.sap.dashboard.customer.application;

import com.poc.sap.dashboard.customer.domain.Alert;
import com.poc.sap.dashboard.customer.domain.port.AlertRepository;
import com.poc.sap.dashboard.customer.application.GetOpenAlerts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Lista de alertas abiertas (UI-001 H-2 F-9). Lo que pinta la pestana
 * "Alerts" del dashboard.
 */
@ExtendWith(MockitoExtension.class)
class GetOpenAlertsTest {

    @Mock AlertRepository repo;
    @InjectMocks GetOpenAlerts useCase;

    private static final Instant AT = Instant.parse("2026-09-18T10:00:00Z");

    @Test
    void returnsAllOpenAlerts() {
        Alert a1 = new Alert("a-1", "C-1", "FAILURE", "kafka", "x", AT, null, null);
        Alert a2 = new Alert("a-2", "C-2", "WARN",    "kafka", "y", AT.plusSeconds(1), null, null);
        when(repo.open()).thenReturn(List.of(a1, a2));

        List<Alert> open = useCase.all();

        assertThat(open).containsExactly(a1, a2);
    }

    @Test
    void filtersByEntity() {
        Alert a1 = new Alert("a-1", "C-1", "FAILURE", "kafka", "x", AT, null, null);
        when(repo.openByEntity("C-1")).thenReturn(List.of(a1));

        assertThat(useCase.byEntity("C-1")).containsExactly(a1);
    }
}

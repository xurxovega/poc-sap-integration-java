package com.poc.sap.dashboard.customer.application;

import com.poc.sap.dashboard.customer.domain.Alert;
import com.poc.sap.dashboard.customer.domain.port.AlertRepository;
import com.poc.sap.dashboard.customer.application.AcknowledgeAlert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Acknowledge (UI-001 H-2 F-9 AC-6): marca una alerta como reconocida.
 * El {@code ackedBy} lo compone el caller a partir del JWT:
 * {@code <rol>:<subject>}, p. ej. {@code sap-write:ana}.
 */
@ExtendWith(MockitoExtension.class)
class AcknowledgeAlertTest {

    @Mock AlertRepository repo;
    private AcknowledgeAlert useCase;
    private Clock clock;

    private static final Instant OPENED = Instant.parse("2026-09-18T10:00:00Z");

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(OPENED.plusSeconds(120), ZoneOffset.UTC);
        useCase = new AcknowledgeAlert(repo, clock);
    }

    @Test
    void setsAckedAtAndAckedByFromCurrentInstant() {
        Alert stored = new Alert("a-1", "C-1", "FAILURE", "kafka", "IBAN invalido", OPENED, null, null);
        when(repo.findById("a-1")).thenReturn(java.util.Optional.of(stored));

        Alert acked = useCase.acknowledge("a-1", "sap-write:ana");

        assertThat(acked.ackedBy()).isEqualTo("sap-write:ana");
        assertThat(acked.ackedAt()).isEqualTo(OPENED.plusSeconds(120));
        assertThat(acked.alertId()).isEqualTo("a-1");

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().ackedAt()).isNotNull();
    }

    @Test
    void rejectsBlankActor() {
        assertThatThrownBy(() -> useCase.acknowledge("a-1", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actor");
    }

    @Test
    void unknownAlertThrows() {
        when(repo.findById("a-x")).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> useCase.acknowledge("a-x", "sap-write:ana"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void acknowledgesAnAlertThatWasAlreadyAckedAndReloaded() {
        // Si la alerta ya estaba acked, la nueva ack debe persistir la fecha nueva.
        Instant prevAck = OPENED.plusSeconds(60);
        Alert stored = new Alert("a-1", "C-1", "FAILURE", "kafka", "x", OPENED, prevAck, "sap-write:otro");
        when(repo.findById("a-1")).thenReturn(java.util.Optional.of(stored));

        Alert acked = useCase.acknowledge("a-1", "sap-write:ana");

        assertThat(acked.ackedBy()).isEqualTo("sap-write:ana");
        assertThat(acked.ackedAt()).isEqualTo(OPENED.plusSeconds(120)).isAfter(prevAck);
    }
}

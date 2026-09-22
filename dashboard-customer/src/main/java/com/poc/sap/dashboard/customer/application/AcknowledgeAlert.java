package com.poc.sap.dashboard.customer.application;

import com.poc.sap.dashboard.customer.domain.Alert;
import com.poc.sap.dashboard.customer.domain.port.AlertRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;

/**
 * Reconocimiento de alertas (UI-001 H-2 F-9 AC-6): un operador con rol
 * {@code sap-write} pulsa "Ack" en el dashboard. El {@code ackedBy} lo
 * compone el caller a partir del JWT con el formato {@code <rol>:<subject>}.
 */
public class AcknowledgeAlert {

    private final AlertRepository repo;
    private final Clock clock;

    public AcknowledgeAlert(AlertRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public Alert acknowledge(String alertId, String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor obligatorio");
        }
        Alert stored = repo.findById(alertId)
                .orElseThrow(() -> new NoSuchElementException("Alerta " + alertId + " no existe"));
        Instant now = Instant.now(clock);
        Alert acked = new Alert(stored.alertId(), stored.entityId(), stored.severity(),
                stored.origin(), stored.detail(), stored.openedAt(), now, actor);
        repo.save(acked);
        return acked;
    }
}

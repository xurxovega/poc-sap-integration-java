package com.poc.sap.dashboard.customer.domain.port;

import com.poc.sap.dashboard.customer.domain.Alert;

import java.util.List;
import java.util.Optional;

/**
 * Puerto de gestion de alertas operativas (UI-001 H-2 F-9). Persistencia en
 * Mongo {@code alerts} con TTL 30 dias (los indices los crea el script
 * {@code external-services/mongodb/init.js}). El dashboard NO consume el
 * topic Kafka directamente: alguien del flujo las materializa en {@code alerts}
 * (en este MVP, el propio arranque del modulo o un consumidor; ampliado despues).
 */
public interface AlertRepository {

    /** Persiste una alerta nueva (idempotente por alertId: si ya existe, no la duplica). */
    void save(Alert alert);

    /** Lista alertas que siguen abiertas (no reconocidas), ordenadas de mas vieja a mas nueva. */
    List<Alert> open();

    /** Lista abierta pero filtrada por cliente. */
    List<Alert> openByEntity(String entityId);

    /** Recupera una alerta por identificador para mostrar el detalle al ack. */
    Optional<Alert> findById(String alertId);
}

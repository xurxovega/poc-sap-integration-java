package com.poc.sap.dashboard.customer.domain;

import java.time.Instant;

/**
 * Alerta operativa del customer-app (UI-001 H-2 F-9). El dashboard la
 * persiste en Mongo {@code alerts} con TTL de 30 dias para que el operador
 * pueda reconocerla (reconocida = {@code ackedAt}/{@code ackedBy} rellenos).
 *
 * @param alertId   identificador unico (viene del mensaje en {@code sap.sync.alerts})
 * @param entityId  cliente al que se refiere
 * @param severity  nivel ("WARN" o "FAILURE")
 * @param origin    origen del aviso (cdc/kafka/rest)
 * @param detail    motivo (libre: el operador lo quiere ver entero, pero el
 *                  dashboard lo enmascara si el lector es {@code sap-external-read})
 * @param openedAt  instante en que el emision la registro
 * @param ackedAt   instante del reconocimiento; null si sigue abierta
 * @param ackedBy   usuario que la reconocio (formato {@code <rol>:<subject>}); null si sigue abierta
 */
public record Alert(
        String alertId,
        String entityId,
        String severity,
        String origin,
        String detail,
        Instant openedAt,
        Instant ackedAt,
        String ackedBy
) {
    public Alert {
        if (alertId == null || alertId.isBlank()) {
            throw new IllegalArgumentException("alertId obligatorio");
        }
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("entityId obligatorio");
        }
    }

    /** True si la alerta sigue abierta (no reconocida por nadie). */
    public boolean isOpen() {
        return ackedAt == null;
    }
}

package com.poc.sap.common.domain;

import java.time.Instant;

/**
 * Transicion de estado persistida para trazabilidad (OVERVIEW.md §5).
 * Value object inmutable.
 *
 * @param entityId   identificador de la entidad del dominio
 * @param domain     nombre del dominio (customer, article, supplier)
 * @param from       estado origen (nullable para la primera transicion)
 * @param to         estado destino
 * @param origin     origen de la ingestion (cdc, kafka, rest)
 * @param payloadHash hash de idempotencia sobre payload + entityId
 * @param timestamp  instante de la transicion
 */
public record SyncStateTransition(
        String entityId,
        String domain,
        SyncState from,
        SyncState to,
        String origin,
        String payloadHash,
        Instant timestamp
) {
    public SyncStateTransition {
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("entityId obligatorio");
        }
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain obligatorio");
        }
        if (to == null) {
            throw new IllegalArgumentException("to obligatorio");
        }
        if (origin == null || origin.isBlank()) {
            throw new IllegalArgumentException("origin obligatorio");
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
}

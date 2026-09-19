package com.poc.sap.common.domain;

import java.time.Instant;

/**
 * Transicion de estado persistida para trazabilidad (OVERVIEW.md §5).
 * Value object inmutable.
 *
 * @param entityId   identificador de la entidad del dominio
 * @param domain     nombre del dominio (customer, article, supplier)
 * @param from       estado origen (nullable para la primera transicion). Es lo que
 *                   DECLARA quien llama: el repositorio lo compara con el estado
 *                   real para distinguir una colision entre instancias de un error
 *                   de programacion (sdd/common/maquina-de-estados.md R-7)
 * @param to         estado destino
 * @param origin     origen de la ingestion (cdc, kafka, rest)
 * @param payloadHash hash de idempotencia sobre payload + entityId
 * @param timestamp  instante de la transicion
 * @param cycleId    identificador del ciclo de sincronizacion: lo comparten la
 *                   linea del agregado y las de sus features, y es lo que permite
 *                   reconstruir la traza de pasos de un envio. {@code null} solo en
 *                   documentos anteriores a su introduccion
 * @param detail     motivo del error, a lo sumo {@link #MAX_DETAIL} caracteres. Se
 *                   escribe solo en transiciones a un estado de error y nunca lleva
 *                   el payload enviado, solo la respuesta de SAP
 */
public record SyncStateTransition(
        String entityId,
        String domain,
        SyncState from,
        SyncState to,
        String origin,
        String payloadHash,
        Instant timestamp,
        String cycleId,
        String detail
) {
    /** Tope del motivo persistido: un cuerpo de error de SAP puede ser enorme. */
    public static final int MAX_DETAIL = 512;

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
        if (detail != null && detail.length() > MAX_DETAIL) {
            detail = detail.substring(0, MAX_DETAIL);
        }
    }

    /** Transicion sin ciclo ni motivo: lecturas de documentos antiguos y tests. */
    public SyncStateTransition(String entityId, String domain, SyncState from, SyncState to,
                               String origin, String payloadHash, Instant timestamp) {
        this(entityId, domain, from, to, origin, payloadHash, timestamp, null, null);
    }
}

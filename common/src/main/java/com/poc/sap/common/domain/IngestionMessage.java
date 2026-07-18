package com.poc.sap.common.domain;

/**
 * Mensaje de entrada comun a todas las fuentes de ingestion (SPEC.md §4).
 * Contrato dominio-agnostico: cada dominio lo especializa con su payload tipado.
 *
 * @param entityId    identificador de la entidad
 * @param domain      nombre del dominio
 * @param operation   tipo de operacion (create/update/delete)
 * @param origin      origen de la ingestion (cdc/kafka/rest)
 * @param payloadHash hash de idempotencia sobre payload + entityId
 * @param payload     payload crudo (JSON u otro); el dominio lo parsea
 */
public record IngestionMessage(
        String entityId,
        String domain,
        OperationType operation,
        IngestionOrigin origin,
        String payloadHash,
        String payload
) {
    public IngestionMessage {
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("entityId obligatorio");
        }
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain obligatorio");
        }
        if (operation == null) {
            throw new IllegalArgumentException("operation obligatorio");
        }
        if (origin == null) {
            throw new IllegalArgumentException("origin obligatorio");
        }
        if (payloadHash == null || payloadHash.isBlank()) {
            throw new IllegalArgumentException("payloadHash obligatorio");
        }
    }
}

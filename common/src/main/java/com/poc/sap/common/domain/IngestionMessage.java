package com.poc.sap.common.domain;

/**
 * Mensaje de entrada comun a todas las fuentes de ingestion (TECH.md §6).
 * Contrato dominio-agnostico: cada dominio lo especializa con su payload tipado.
 *
 * @param entityId    identificador de la entidad
 * @param domain      nombre del dominio
 * @param operation   tipo de operacion (create/update/delete)
 * @param origin      origen de la ingestion (cdc/kafka/rest)
 * @param payloadHash hash de idempotencia que traia el mensaje. <b>Opcional</b>
 *                    desde el mensaje fino (ADR-0013): el hash que manda es el que
 *                    calcula el consumidor sobre el snapshot releido del legacy
 *                    ({@link PayloadHasher}); este solo se registra como pista
 * @param payload     payload crudo que traia el mensaje. <b>Opcional y nunca usado
 *                    como fuente de datos</b> (ADR-0013): se conserva para poder
 *                    seguir consumiendo mensajes antiguos sin romperlos
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
        // Mensaje fino (ADR-0013): sin hash y sin payload. Un hash en blanco es
        // "ausente", no un hash valido: se normaliza para que nadie deduplique
        // por cadena vacia.
        if (payloadHash != null && payloadHash.isBlank()) {
            payloadHash = null;
        }
    }

    /** Mensaje fino: identidad del cambio y nada mas (ADR-0013). */
    public static IngestionMessage thin(String entityId, String domain,
                                        OperationType operation, IngestionOrigin origin) {
        return new IngestionMessage(entityId, domain, operation, origin, null, null);
    }
}

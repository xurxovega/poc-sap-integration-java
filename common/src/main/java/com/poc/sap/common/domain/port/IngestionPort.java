package com.poc.sap.common.domain.port;

import com.poc.sap.common.domain.IngestionMessage;

/**
 * Puerto de ingestion de cambios (SPEC.md §4).
 * Tres adaptadores equivalentes: CDC (Debezium Kafka), eventos Kafka directos,
 * REST. Todos alimentan el mismo caso de uso del dominio.
 *
 * <p>Este puerto es la abstraccion comun; cada dominio define su propio
 * handler tipado que recibe su payload ya parseado.
 */
public interface IngestionPort {

    /**
     * Entrega un mensaje de ingestion al dominio.
     * Idempotente: reintentos con mismo {@code payloadHash} no duplican efectos.
     */
    void ingest(IngestionMessage message);
}

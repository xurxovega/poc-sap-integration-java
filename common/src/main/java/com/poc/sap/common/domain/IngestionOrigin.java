package com.poc.sap.common.domain;

/**
 * Origen de la ingestion de un cambio (TECH.md §6): CDC (Debezium), Kafka
 * directo o REST. Viaja en la transicion de estado como {@code origin}.
 */
public enum IngestionOrigin {
    CDC,
    KAFKA,
    REST;
}

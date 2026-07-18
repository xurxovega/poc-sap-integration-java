package com.poc.sap.common.domain;

/**
 * Origen de la ingestion de un cambio (SPEC.md §4).
 * Determina que adaptador de {@code IngestionPort} lo produjo.
 */
public enum IngestionOrigin {
    CDC,
    KAFKA,
    REST;
}

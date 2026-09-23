package com.poc.sap.customer.domain;

/**
 * Subconjunto de features de Customer que puede ejecutar el orchestrador
 * general (OVERVIEW.md §2). Permite invocar todos o solo los relevantes para una
 * funcionalidad de negocio concreta.
 */
public enum CustomerFeature {
    ADDRESS,
    FISCAL,
    CONTACT,
    BANKING;
}
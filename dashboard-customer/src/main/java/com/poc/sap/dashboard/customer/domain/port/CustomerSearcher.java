package com.poc.sap.dashboard.customer.domain.port;

import com.poc.sap.dashboard.customer.domain.CustomerSnapshot;

import java.util.List;

/**
 * Busqueda por clave de dominio (UI-001 H-2 AC-3): por defecto por
 * {@code fiscal.taxId}. La fuente es Mongo {@code customers_current}
 * (busqueda simple por indice sobre taxId; si crece, se mueve a ES, pero
 * hoy el catalogueo es chico).
 */
public interface CustomerSearcher {

    /**
     * Busca por clave. Devuelve matches ordenados por {@code code} ascendente.
     * Si {@code taxId} es null o blank, devuelve una lista vacia (no falla).
     */
    List<CustomerSnapshot> findByTaxId(String taxId);
}

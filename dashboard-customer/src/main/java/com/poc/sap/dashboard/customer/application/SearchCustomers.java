package com.poc.sap.dashboard.customer.application;

import com.poc.sap.dashboard.customer.domain.CustomerSnapshot;
import com.poc.sap.dashboard.customer.domain.port.CustomerSearcher;

import java.util.List;

/**
 * Busqueda por clave fiscal (UI-001 H-2 AC-3).
 */
public class SearchCustomers {

    private final CustomerSearcher searcher;

    public SearchCustomers(CustomerSearcher searcher) {
        this.searcher = searcher;
    }

    public List<CustomerSnapshot> byTaxId(String taxId) {
        if (taxId == null || taxId.isBlank()) {
            return List.of();
        }
        return searcher.findByTaxId(taxId);
    }
}

package com.poc.sap.customer.domain.port;

import java.util.List;
import java.util.Optional;

/**
 * Puerto de lectura de Business Partners desde SAP S/4HANA OData.
 *
 * <p>Operaciones de solo lectura (GET/search) contra la API
 * {@code API_BUSINESS_PARTNER}. Complementario a {@code SapOutboundPort}
 * que solo envia datos (POST/PATCH/DELETE).
 */
public interface BusinessPartnerReadPort {

    record BusinessPartnerSummary(String code, String name, String category) {}

    /** Busca un BP por su codigo. */
    Optional<BusinessPartnerSummary> findById(String businessPartnerCode);

    /** Lista los primeros N BPs filtrados por categoria OData. */
    List<BusinessPartnerSummary> searchByCategory(String category, int top);

    /** Lista los primeros N clientes (categoria=2 en SAP). */
    List<BusinessPartnerSummary> findCustomers(int top);

    /** Lista los primeros N acreedores (categoria=1 en SAP). */
    List<BusinessPartnerSummary> findSuppliers(int top);
}

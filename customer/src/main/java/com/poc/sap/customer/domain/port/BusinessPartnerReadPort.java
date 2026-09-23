package com.poc.sap.customer.domain.port;

import com.poc.sap.common.domain.port.SapOutboundPort.SapLookup;

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

    /**
     * Busca un BP por su codigo. Devuelve vacio tanto si SAP no lo tiene como si
     * no responde: vale para consultar, <b>no</b> para decidir alta o actualizacion.
     */
    Optional<BusinessPartnerSummary> findById(String businessPartnerCode);

    /**
     * Verificacion previa del agregado: distingue «no lo tiene» de «no responde» y
     * trae el ETag para el {@code If-Match} (spec
     * {@code docs/sdd/common/upsert-idempotente-sap.md} AC-1..AC-3).
     */
    SapLookup lookupById(String businessPartnerCode);

    /** Lista los primeros N BPs filtrados por categoria OData. */
    List<BusinessPartnerSummary> searchByCategory(String category, int top);

    /** Lista los primeros N clientes (categoria=2 en SAP). */
    List<BusinessPartnerSummary> findCustomers(int top);

    /** Lista los primeros N acreedores (categoria=1 en SAP). */
    List<BusinessPartnerSummary> findSuppliers(int top);
}

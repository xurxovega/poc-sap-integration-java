package com.poc.sap.customer.domain.port;

import com.poc.sap.common.domain.port.SapOutboundPort;
import com.poc.sap.customer.domain.Customer;

/**
 * Puerto de envio a SAP para Customer (TECH.md §8).
 * Mapea Customer al contrato SAP y delega en {@code SapClient} de common.
 */
public interface CustomerSapOutboundPort extends SapOutboundPort<Customer> {

    /**
     * Baja del cliente en SAP (sdd/customer/baja-cliente.md R-2). Es una operacion
     * distinta de {@link #send}: antes un {@code Customer} nulo hacia de senal de
     * borrado y el adaptador mandaba {@code POST {}} (auditoria B2).
     *
     * @param entityId    identificador del cliente
     * @param payloadHash hash del evento, para trazabilidad
     */
    SapResponse delete(String entityId, String payloadHash);
}

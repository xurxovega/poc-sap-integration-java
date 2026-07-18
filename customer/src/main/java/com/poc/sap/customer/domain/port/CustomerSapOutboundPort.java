package com.poc.sap.customer.domain.port;

import com.poc.sap.common.domain.port.SapOutboundPort;
import com.poc.sap.customer.domain.Customer;

/**
 * Puerto de envio a SAP para Customer (SPEC.md §5).
 * Mapea Customer al contrato SAP y delega en {@code SapClient} de common.
 */
public interface CustomerSapOutboundPort extends SapOutboundPort<Customer> {
}

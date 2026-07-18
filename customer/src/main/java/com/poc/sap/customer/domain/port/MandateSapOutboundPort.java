package com.poc.sap.customer.domain.port;

import com.poc.sap.common.domain.port.SapOutboundPort;
import com.poc.sap.customer.domain.Mandate;

/**
 * Puerto de envio a SAP para Mandate (SPEC.md §3, §5).
 */
public interface MandateSapOutboundPort extends SapOutboundPort<Mandate> {
}

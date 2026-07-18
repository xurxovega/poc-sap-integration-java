package com.poc.sap.customer.domain.port;

import com.poc.sap.common.domain.port.SapOutboundPort;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;

/** Puerto de envio a SAP para la feature FISCAL (SPEC.md §5). */
public interface FiscalSapPort extends SapOutboundPort<FiscalData> {
}
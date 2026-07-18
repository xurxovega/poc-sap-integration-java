package com.poc.sap.customer.domain.port;

import com.poc.sap.common.domain.port.SapOutboundPort;
import com.poc.sap.customer.domain.feature.address.AddressData;

/** Puerto de envio a SAP para la feature ADDRESS (SPEC.md §5). */
public interface AddressSapPort extends SapOutboundPort<AddressData> {
}
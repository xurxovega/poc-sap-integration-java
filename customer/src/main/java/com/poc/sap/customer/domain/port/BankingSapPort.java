package com.poc.sap.customer.domain.port;

import com.poc.sap.common.domain.port.SapOutboundPort;
import com.poc.sap.customer.domain.feature.banking.BankingData;

/** Puerto de envio a SAP para la feature BANKING (SPEC.md §5). */
public interface BankingSapPort extends SapOutboundPort<BankingData> {
}
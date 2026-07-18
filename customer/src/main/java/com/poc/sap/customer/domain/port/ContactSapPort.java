package com.poc.sap.customer.domain.port;

import com.poc.sap.common.domain.port.SapOutboundPort;
import com.poc.sap.customer.domain.feature.contact.ContactData;

/** Puerto de envio a SAP para la feature CONTACT (SPEC.md §5). */
public interface ContactSapPort extends SapOutboundPort<ContactData> {
}
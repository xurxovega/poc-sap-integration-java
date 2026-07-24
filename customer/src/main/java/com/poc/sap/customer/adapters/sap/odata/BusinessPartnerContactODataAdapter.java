package com.poc.sap.customer.adapters.sap.odata;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.json.SapJsonMapper;
import com.poc.sap.common.sap.odata.ODataPayload;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import com.poc.sap.customer.domain.port.ContactSapPort;
import com.poc.sap.integration.api.customer.model.APIBUSINESSPARTNERABusinessPartnerContactTypeCreate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adaptador OData S/4HANA para la feature CONTACT.
 * Usa modelo generado desde {@code API_BUSINESS_PARTNER.yaml}.
 */
@Component
@ConditionalOnProperty(name = "sap.odata.contact.enabled", havingValue = "true")
public class BusinessPartnerContactODataAdapter implements ContactSapPort {

    private final SapClient sapClient;
    private final String path;

    public BusinessPartnerContactODataAdapter(
            SapClient sapClient,
            @Value("${sap.odata.contact-path:/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartnerContact}") String path) {
        this.sapClient = sapClient;
        this.path = path;
    }

    @Override
    public SapResponse send(String entityId, String payloadHash, ContactData c) {
        if (c == null) {
            return sapClient.send(SapDestination.S4_NATIVE, path, entityId, payloadHash, "{}");
        }
        String body = SapJsonMapper.write(ODataPayload.wrap(toSapPayload(c)));
        return sapClient.send(SapDestination.S4_NATIVE, path, entityId, payloadHash, body);
    }

    private APIBUSINESSPARTNERABusinessPartnerContactTypeCreate toSapPayload(ContactData c) {
        var contact = new APIBUSINESSPARTNERABusinessPartnerContactTypeCreate();
        contact.setBusinessPartnerCompany("");
        contact.setRelationshipCategory("BUR001");   // contact person
        return contact;
    }

    private static String n(String s) { return s == null ? "" : s; }
}

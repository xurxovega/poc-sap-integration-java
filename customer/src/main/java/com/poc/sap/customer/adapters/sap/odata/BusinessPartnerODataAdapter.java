package com.poc.sap.customer.adapters.sap.odata;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.json.SapJsonMapper;
import com.poc.sap.common.sap.odata.ODataPayload;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.port.CustomerSapOutboundPort;
import com.poc.sap.integration.api.customer.model.APIBUSINESSPARTNERABusinessPartnerTypeCreate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adaptador OData S/4HANA para el aggregate Customer.
 * Usa modelo generado desde {@code API_BUSINESS_PARTNER.yaml}.
 */
@Component
@ConditionalOnProperty(name = "sap.odata.customer.enabled", havingValue = "true")
public class BusinessPartnerODataAdapter implements CustomerSapOutboundPort {

    private final SapClient sapClient;
    private final String path;

    public BusinessPartnerODataAdapter(
            SapClient sapClient,
            @Value("${sap.odata.customer-path:/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner}") String path) {
        this.sapClient = sapClient;
        this.path = path;
    }

    @Override
    public SapResponse send(String entityId, String payloadHash, Customer customer) {
        if (customer == null) {
            return sapClient.send(SapDestination.S4_NATIVE, path, entityId, payloadHash, "{}");
        }
        String body = SapJsonMapper.write(ODataPayload.wrap(toSapPayload(customer)));
        return sapClient.send(SapDestination.S4_NATIVE, path, entityId, payloadHash, body);
    }

    private APIBUSINESSPARTNERABusinessPartnerTypeCreate toSapPayload(Customer c) {
        var bp = new APIBUSINESSPARTNERABusinessPartnerTypeCreate();
        bp.setBusinessPartnerCategory("2");           // 2 = Organization (Customer)
        bp.setBusinessPartnerGrouping("BPEE");        // external business partner
        bp.setOrganizationBPName1(n(c.name()));
        return bp;
    }

    private static String n(String s) { return s == null ? "" : s; }
}

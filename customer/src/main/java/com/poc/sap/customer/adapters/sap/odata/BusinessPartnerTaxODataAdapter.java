package com.poc.sap.customer.adapters.sap.odata;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.json.SapJsonMapper;
import com.poc.sap.common.sap.odata.ODataPayload;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;
import com.poc.sap.customer.domain.port.FiscalSapPort;
import com.poc.sap.integration.api.customer.model.APIBUSINESSPARTNERABusinessPartnerTaxNumberTypeCreate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adaptador OData S/4HANA para la feature FISCAL.
 * Usa modelo generado desde {@code API_BUSINESS_PARTNER.yaml}.
 */
@Component
@ConditionalOnProperty(name = "sap.odata.fiscal.enabled", havingValue = "true")
public class BusinessPartnerTaxODataAdapter implements FiscalSapPort {

    private final SapClient sapClient;
    private final String path;

    public BusinessPartnerTaxODataAdapter(
            SapClient sapClient,
            @Value("${sap.odata.fiscal-path:/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartnerTaxNumber}") String path) {
        this.sapClient = sapClient;
        this.path = path;
    }

    @Override
    public SapResponse send(String entityId, String payloadHash, FiscalData f) {
        if (f == null) {
            return sapClient.send(SapDestination.S4_NATIVE, path, entityId, payloadHash, "{}");
        }
        String body = SapJsonMapper.write(ODataPayload.wrap(toSapPayload(f)));
        return sapClient.send(SapDestination.S4_NATIVE, path, entityId, payloadHash, body);
    }

    private APIBUSINESSPARTNERABusinessPartnerTaxNumberTypeCreate toSapPayload(FiscalData f) {
        var tax = new APIBUSINESSPARTNERABusinessPartnerTaxNumberTypeCreate();
        tax.setBusinessPartner("");
        tax.setBpTaxNumber(n(f.taxId()));
        return tax;
    }

    private static String n(String s) { return s == null ? "" : s; }
}

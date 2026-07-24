package com.poc.sap.customer.adapters.sap.odata;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.json.SapJsonMapper;
import com.poc.sap.common.sap.odata.ODataPayload;
import com.poc.sap.customer.domain.feature.address.AddressData;
import com.poc.sap.customer.domain.port.AddressSapPort;
import com.poc.sap.integration.api.customer.model.APIBUSINESSPARTNERABusinessPartnerAddressTypeCreate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adaptador OData S/4HANA para la feature ADDRESS.
 * Usa modelo generado desde {@code API_BUSINESS_PARTNER.yaml}.
 */
@Component
@ConditionalOnProperty(name = "sap.odata.address.enabled", havingValue = "true")
public class BusinessPartnerAddressODataAdapter implements AddressSapPort {

    private final SapClient sapClient;
    private final String path;

    public BusinessPartnerAddressODataAdapter(
            SapClient sapClient,
            @Value("${sap.odata.address-path:/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartnerAddress}") String path) {
        this.sapClient = sapClient;
        this.path = path;
    }

    @Override
    public SapResponse send(String entityId, String payloadHash, AddressData a) {
        if (a == null) {
            return sapClient.send(SapDestination.S4_NATIVE, path, entityId, payloadHash, "{}");
        }
        String body = SapJsonMapper.write(ODataPayload.wrap(toSapPayload(a)));
        return sapClient.send(SapDestination.S4_NATIVE, path, entityId, payloadHash, body);
    }

    private APIBUSINESSPARTNERABusinessPartnerAddressTypeCreate toSapPayload(AddressData a) {
        var addr = new APIBUSINESSPARTNERABusinessPartnerAddressTypeCreate();
        addr.setBusinessPartner("");
        addr.setStreetName(n(a.street()));
        addr.setCityName(n(a.city()));
        addr.setPostalCode(n(a.postalCode()));
        addr.setCountry(n(a.country()));
        addr.setRegion(n(a.region()));
        return addr;
    }

    private static String n(String s) { return s == null ? "" : s; }
}

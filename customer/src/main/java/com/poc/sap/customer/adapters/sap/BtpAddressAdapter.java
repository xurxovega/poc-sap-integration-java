package com.poc.sap.customer.adapters.sap;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.customer.domain.feature.address.AddressData;
import com.poc.sap.customer.domain.port.AddressSapPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Adaptador SAP BTP para la feature ADDRESS (SPEC.md §5, TECH.md §8).
 * Mapea AddressData al contrato BTP y delega en {@link SapClient} de common.
 */
@Component
public class BtpAddressAdapter implements AddressSapPort {

    private final SapClient sapClient;
    private final String path;

    public BtpAddressAdapter(SapClient sapClient,
                             @Value("${sap.customer.btp.address-path:/sap/btp/odata/CustomerAddress}") String path) {
        this.sapClient = sapClient;
        this.path = path;
    }

    @Override
    public SapResponse send(String entityId, String payloadHash, AddressData a) {
        String body = a == null ? "{}" : toJson(a);
        return sapClient.send(SapDestination.BTP, path, entityId, payloadHash, body);
    }

    private String toJson(AddressData a) {
        return """
                {"BusinessPartner":"%s","Street":"%s","City":"%s","PostalCode":"%s","Country":"%s","Region":"%s"}"""
                .formatted(
                        "",
                        n(a.street()), n(a.city()), n(a.postalCode()),
                        n(a.country()), n(a.region()));
    }

    private static String n(String s) { return s == null ? "" : s; }
}
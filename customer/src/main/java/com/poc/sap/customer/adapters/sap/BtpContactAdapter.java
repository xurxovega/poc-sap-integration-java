package com.poc.sap.customer.adapters.sap;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import com.poc.sap.customer.domain.port.ContactSapPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Adaptador SAP BTP para la feature CONTACT. */
@Component
public class BtpContactAdapter implements ContactSapPort {

    private final SapClient sapClient;
    private final String path;

    public BtpContactAdapter(SapClient sapClient,
                             @Value("${sap.customer.btp.contact-path:/sap/btp/odata/CustomerContact}") String path) {
        this.sapClient = sapClient;
        this.path = path;
    }

    @Override
    public SapResponse send(String entityId, String payloadHash, ContactData c) {
        String body = c == null ? "{}" : toJson(c);
        return sapClient.send(SapDestination.BTP, path, entityId, payloadHash, body);
    }

    private String toJson(ContactData c) {
        return """
                {"BusinessPartner":"%s","Email":"%s","Phone":"%s","Fax":"%s","Website":"%s"}"""
                .formatted("", n(c.email()), n(c.phone()), n(c.fax()), n(c.website()));
    }

    private static String n(String s) { return s == null ? "" : s; }
}
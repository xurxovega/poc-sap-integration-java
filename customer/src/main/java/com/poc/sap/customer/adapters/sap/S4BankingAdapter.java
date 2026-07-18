package com.poc.sap.customer.adapters.sap;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import com.poc.sap.customer.domain.port.BankingSapPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Adaptador SAP S/4 nativo para la feature BANKING (incluye mandates).
 * Reemplaza al antiguo S4MandateAdapter.
 */
@Component
public class S4BankingAdapter implements BankingSapPort {

    private final SapClient sapClient;
    private final String path;

    public S4BankingAdapter(SapClient sapClient,
                            @Value("${sap.mandate.s4.path:/sap/opu/odata/sap/API_CUSTOMER_MANDATE}") String path) {
        this.sapClient = sapClient;
        this.path = path;
    }

    @Override
    public SapResponse send(String entityId, String payloadHash, BankingData b) {
        String body = b == null ? "{}" : toJson(b);
        return sapClient.send(SapDestination.S4_NATIVE, path, entityId, payloadHash, body);
    }

    private String toJson(BankingData b) {
        String mandates = b.mandateIds() == null ? "" : String.join(",", b.mandateIds());
        return """
                {"CustomerID":"%s","IBAN":"%s","BIC":"%s","Mandates":"%s"}"""
                .formatted("", n(b.iban()), n(b.bic()), mandates);
    }

    private static String n(String s) { return s == null ? "" : s; }
}
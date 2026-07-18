package com.poc.sap.customer.adapters.sap;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;
import com.poc.sap.customer.domain.port.FiscalSapPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Adaptador SAP BTP para la feature FISCAL. */
@Component
public class BtpFiscalAdapter implements FiscalSapPort {

    private final SapClient sapClient;
    private final String path;

    public BtpFiscalAdapter(SapClient sapClient,
                            @Value("${sap.customer.btp.fiscal-path:/sap/btp/odata/CustomerFiscal}") String path) {
        this.sapClient = sapClient;
        this.path = path;
    }

    @Override
    public SapResponse send(String entityId, String payloadHash, FiscalData f) {
        String body = f == null ? "{}" : toJson(f);
        return sapClient.send(SapDestination.BTP, path, entityId, payloadHash, body);
    }

    private String toJson(FiscalData f) {
        return """
                {"BusinessPartner":"%s","TaxNumber":"%s","VATNumber":"%s","LegalName":"%s","TaxResidency":"%s"}"""
                .formatted("", n(f.taxId()), n(f.vatNumber()),
                        n(f.legalName()), n(f.taxResidency()));
    }

    private static String n(String s) { return s == null ? "" : s; }
}
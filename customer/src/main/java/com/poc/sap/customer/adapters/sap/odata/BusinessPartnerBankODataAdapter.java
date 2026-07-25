package com.poc.sap.customer.adapters.sap.odata;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.json.SapJsonMapper;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import com.poc.sap.customer.domain.port.BankingSapPort;
import com.poc.sap.integration.api.customer.model.APIBUSINESSPARTNERABusinessPartnerBankTypeCreate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adaptador OData S/4HANA para la feature BANKING.
 * Usa modelo generado desde {@code API_BUSINESS_PARTNER.yaml}.
 */
@Component
@ConditionalOnProperty(name = "sap.odata.banking.enabled", havingValue = "true")
public class BusinessPartnerBankODataAdapter implements BankingSapPort {

    private final SapClient sapClient;
    private final String path;

    public BusinessPartnerBankODataAdapter(
            SapClient sapClient,
            @Value("${sap.odata.banking-path:/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartnerBank}") String path) {
        this.sapClient = sapClient;
        this.path = path;
    }

    @Override
    public SapResponse send(String entityId, String payloadHash, BankingData b) {
        if (b == null) {
            return sapClient.send(SapDestination.S4_NATIVE, path, entityId, payloadHash, "{}");
        }
        String body = SapJsonMapper.write(toSapPayload(entityId, b));
        return sapClient.send(SapDestination.S4_NATIVE, path, entityId, payloadHash, body);
    }

    private APIBUSINESSPARTNERABusinessPartnerBankTypeCreate toSapPayload(String entityId, BankingData b) {
        var bank = new APIBUSINESSPARTNERABusinessPartnerBankTypeCreate();
        bank.setBusinessPartner(entityId);
        bank.setBankIdentification(n(b.bic()));
        bank.setIBAN(n(b.iban()));
        return bank;
    }

    private static String n(String s) { return s == null ? "" : s; }
}

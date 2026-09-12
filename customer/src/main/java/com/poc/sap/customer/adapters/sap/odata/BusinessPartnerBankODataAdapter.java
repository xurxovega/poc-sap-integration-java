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
 * Adaptador OData S/4HANA para la feature BANKING
 * (sdd/customer/sincronizacion-datos-bancarios.md §5, ruta OData).
 * Usa modelo generado desde {@code API_BUSINESS_PARTNER.yaml}.
 *
 * <p>Contrato real de {@code A_BusinessPartnerBank} (auditoria B3):
 * {@code BankIdentification} es el identificador <b>secuencial</b> de la cuenta
 * dentro del BP (4 caracteres), no el BIC. El BIC/SWIFT no vive en esta entidad:
 * pertenece al maestro de bancos y al mandato SEPA ({@code SenderBankSWIFTCode}).
 * {@code BankCountryKey} se deriva del IBAN.
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

    /** Primera (y por ahora unica) cuenta del BP. Con varias cuentas seria 0002, 0003... */
    static final String FIRST_BANK_IDENTIFICATION = "0001";

    private APIBUSINESSPARTNERABusinessPartnerBankTypeCreate toSapPayload(String entityId, BankingData b) {
        var bank = new APIBUSINESSPARTNERABusinessPartnerBankTypeCreate();
        bank.setBusinessPartner(entityId);
        bank.setBankIdentification(FIRST_BANK_IDENTIFICATION);
        bank.setIBAN(n(b.iban()));
        bank.setBankCountryKey(countryOf(b.iban()));
        return bank;
    }

    /** Los dos primeros caracteres del IBAN son el pais ISO (ES76... -> ES). */
    static String countryOf(String iban) {
        if (iban == null) return "";
        String t = iban.replace(" ", "");
        return t.length() >= 2 ? t.substring(0, 2).toUpperCase(java.util.Locale.ROOT) : "";
    }

    private static String n(String s) { return s == null ? "" : s; }
}

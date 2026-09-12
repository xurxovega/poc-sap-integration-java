package com.poc.sap.customer.adapters.sap.odata;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.json.SapJsonMapper;
import com.poc.sap.customer.domain.Mandate;
import com.poc.sap.customer.domain.port.MandateSapOutboundPort;
import com.poc.sap.integration.api.customer.sepamandate.model.APIAPARNOAPPSEPAMANDATESRVSEPAMandateTypeCreate;
import com.poc.sap.integration.api.customer.sepamandate.model.APIAPARNOAPPSEPAMANDATESRVSEPAMandateTypeUpdate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Adaptador OData S/4HANA del mandato SEPA sobre {@code API_APAR_SEPA_MANDATE_SRV}
 * (sdd/customer/sincronizacion-datos-bancarios.md §5; sdd/customer/baja-mandato-sepa.md).
 * Modelos generados desde {@code API_APAR_SEPA_MANDATE_SRV.yaml}.
 *
 * <p>Sustituye al envio de mandatos que iba a {@code API_CUSTOMER_MANDATE}, una API
 * que no existe (auditoria B3). La clave del mandato en S/4 es
 * {@code (Creditor, SEPAMandate)}: el identificador de acreedor SEPA de la
 * empresa es configuracion ({@code sap.sepa.creditor-id}) y sin el no se puede
 * hablar con esta API.
 *
 * <p>Estados S/4 ({@code SEPAMandateStatus}): 0 introducido, 1 activo, 2 bloqueado,
 * 3 cancelado, 4 completado. La correspondencia con {@link Mandate.Status} se
 * valida contra el tenant de test (plan, Fase 3).
 */
@Component
public class SepaMandateODataAdapter implements MandateSapOutboundPort {

    static final String STATUS_ACTIVE = "1";
    static final String STATUS_CANCELLED = "3";
    static final String STATUS_COMPLETED = "4";
    static final String SENDER_TYPE_BUSINESS_PARTNER = "BUS1006";

    private final SapClient sapClient;
    private final String path;
    private final String creditorId;
    private final String application;

    public SepaMandateODataAdapter(
            SapClient sapClient,
            @Value("${sap.odata.mandate-path:/sap/opu/odata/sap/API_APAR_SEPA_MANDATE_SRV/SEPAMandateSet}") String path,
            @Value("${sap.sepa.creditor-id:}") String creditorId,
            @Value("${sap.sepa.application:F}") String application) {
        this.sapClient = sapClient;
        this.path = path;
        this.creditorId = creditorId;
        this.application = application;
    }

    @Override
    public SapResponse send(String entityId, String payloadHash, Mandate m) {
        if (m == null) {
            throw new IllegalArgumentException("mandato obligatorio para enviar a SAP; para revocar usa revoke()");
        }
        requireCreditor();
        String body = SapJsonMapper.write(toSapPayload(m));
        return sapClient.send(SapDestination.S4_NATIVE, path, entityId, payloadHash, body);
    }

    @Override
    public SapResponse revoke(String mandateId, String payloadHash) {
        requireCreditor();
        var update = new APIAPARNOAPPSEPAMANDATESRVSEPAMandateTypeUpdate();
        update.setSePAMandateStatus(STATUS_CANCELLED);
        return sapClient.patch(SapDestination.S4_NATIVE, keyPath(mandateId), mandateId, payloadHash,
                SapJsonMapper.write(update));
    }

    private APIAPARNOAPPSEPAMANDATESRVSEPAMandateTypeCreate toSapPayload(Mandate m) {
        var mandate = new APIAPARNOAPPSEPAMANDATESRVSEPAMandateTypeCreate();
        mandate.setSePAMandateApplication(application);
        mandate.setCreditor(creditorId);
        mandate.setSePAMandate(m.id());
        mandate.setSenderType(SENDER_TYPE_BUSINESS_PARTNER);
        mandate.setSender(m.customerId());
        mandate.setSenderIBAN(m.iban());
        mandate.setSenderBankSWIFTCode(n(m.bic()));
        mandate.setSePASignatureDate(n(m.signatureDate()));
        mandate.setSePAMandateStatus(statusOf(m.status()));
        return mandate;
    }

    static String statusOf(Mandate.Status status) {
        if (status == null) return STATUS_ACTIVE;
        return switch (status) {
            case ACTIVE -> STATUS_ACTIVE;
            case REVOKED -> STATUS_CANCELLED;
            case EXPIRED -> STATUS_COMPLETED;
        };
    }

    /** {@code SEPAMandateSet(Creditor='...',SEPAMandate='...')}: la comilla simple se dobla. */
    private String keyPath(String mandateId) {
        return path + "(Creditor='" + esc(creditorId) + "',SEPAMandate='" + esc(mandateId) + "')";
    }

    private void requireCreditor() {
        if (creditorId == null || creditorId.isBlank()) {
            throw new IllegalStateException(
                    "sap.sepa.creditor-id no configurado: la API de mandatos SEPA exige el identificador de acreedor");
        }
    }

    private static String esc(String v) { return v == null ? "" : v.replace("'", "''"); }
    private static String n(String s) { return s == null ? "" : s; }
}

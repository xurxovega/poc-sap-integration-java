package com.poc.sap.customer.adapters.sap.odata;

import com.poc.sap.common.domain.port.SapOutboundPort.SapLookup;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.SapUpsertSettings;
import com.poc.sap.common.sap.json.SapJsonMapper;
import com.poc.sap.common.sap.odata.ODataLookups;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;
import com.poc.sap.customer.domain.port.FiscalSapPort;
import com.poc.sap.integration.api.customer.model.APIBUSINESSPARTNERABusinessPartnerTaxNumberTypeCreate;
import com.poc.sap.integration.api.customer.model.APIBUSINESSPARTNERABusinessPartnerTaxNumberTypeUpdate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adaptador OData S/4HANA para la feature FISCAL.
 * Usa modelo generado desde {@code API_BUSINESS_PARTNER.yaml}.
 *
 * <p>Upsert: la clave es compuesta y <b>determinista</b>
 * {@code (BusinessPartner, BPTaxType)} —el tipo sale de configuracion—, asi que
 * no hay nada que persistir.
 */
@Component
@ConditionalOnProperty(name = "sap.odata.fiscal.enabled", havingValue = "true")
public class BusinessPartnerTaxODataAdapter implements FiscalSapPort {

    private final SapClient sapClient;
    private final String path;
    private final String taxType;
    private final SapUpsertSettings upsert;

    public BusinessPartnerTaxODataAdapter(
            SapClient sapClient,
            @Value("${sap.odata.fiscal-path:/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartnerTaxNumber}") String path,
            @Value("${sap.odata.fiscal.tax-type:ES0}") String taxType,
            SapUpsertSettings upsert) {
        this.sapClient = sapClient;
        this.path = path;
        this.taxType = taxType;
        this.upsert = upsert;
    }

    @Override
    public SapResponse send(String entityId, String payloadHash, FiscalData f) {
        if (f == null) {
            return sapClient.send(SapDestination.S4_NATIVE, path, entityId, payloadHash, "{}");
        }
        String body = SapJsonMapper.write(toSapPayload(entityId, f));
        return sapClient.send(SapDestination.S4_NATIVE, path, entityId, payloadHash, body);
    }

    /** Verificacion previa por la clave compuesta, sin filtros ni navegacion. */
    @Override
    public SapLookup lookup(String entityId, FiscalData fiscal) {
        if (!upsert.lookupEnabled()) {
            return SapLookup.notSupported();
        }
        return ODataLookups.fromSingle(
                sapClient.get(SapDestination.S4_NATIVE, keyPath(entityId)), taxType);
    }

    /** Actualizacion: solo el numero fiscal; el tipo forma parte de la clave. */
    @Override
    public SapResponse update(String entityId, String payloadHash, FiscalData f, SapLookup found) {
        var tax = new APIBUSINESSPARTNERABusinessPartnerTaxNumberTypeUpdate();
        tax.setBpTaxNumber(f == null ? "" : n(f.taxId()));
        return sapClient.patch(SapDestination.S4_NATIVE, keyPath(entityId), entityId, payloadHash,
                SapJsonMapper.write(tax), found.etag());
    }

    private String keyPath(String entityId) {
        return path + "(BusinessPartner='" + ODataLookups.esc(entityId)
                + "',BPTaxType='" + ODataLookups.esc(taxType) + "')";
    }

    private APIBUSINESSPARTNERABusinessPartnerTaxNumberTypeCreate toSapPayload(String entityId, FiscalData f) {
        var tax = new APIBUSINESSPARTNERABusinessPartnerTaxNumberTypeCreate();
        tax.setBusinessPartner(entityId);
        tax.setBpTaxType(taxType);
        tax.setBpTaxNumber(n(f.taxId()));
        return tax;
    }

    private static String n(String s) { return s == null ? "" : s; }
}

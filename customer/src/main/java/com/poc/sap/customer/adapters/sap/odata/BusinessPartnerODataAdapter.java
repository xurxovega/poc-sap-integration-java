package com.poc.sap.customer.adapters.sap.odata;

import com.poc.sap.common.domain.port.SapOutboundPort.SapLookup;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.SapUpsertSettings;
import com.poc.sap.common.sap.json.SapJsonMapper;
import com.poc.sap.common.sap.odata.ODataLookups;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.port.CustomerSapOutboundPort;
import com.poc.sap.integration.api.customer.model.APIBUSINESSPARTNERABusinessPartnerTypeCreate;
import com.poc.sap.integration.api.customer.model.APIBUSINESSPARTNERABusinessPartnerTypeUpdate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adaptador OData S/4HANA para el aggregate Customer.
 * Usa modelo generado desde {@code API_BUSINESS_PARTNER.yaml}.
 *
 * <p>Upsert (spec {@code docs/sdd/common/upsert-idempotente-sap.md}): la clave del
 * BP es nuestro propio {@code entityId} porque el alta usa numeracion externa
 * ({@code BusinessPartnerGrouping = "BPEE"}). <b>A confirmar en tenant</b>: que
 * {@code BPEE} deje fijar la clave externa y que el {@code GET} devuelva {@code ETag}.
 */
@Component
@ConditionalOnProperty(name = "sap.odata.customer.enabled", havingValue = "true")
public class BusinessPartnerODataAdapter implements CustomerSapOutboundPort {

    private final SapClient sapClient;
    private final String path;
    private final SapUpsertSettings upsert;

    public BusinessPartnerODataAdapter(
            SapClient sapClient,
            @Value("${sap.odata.customer-path:/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner}") String path,
            SapUpsertSettings upsert) {
        this.sapClient = sapClient;
        this.path = path;
        this.upsert = upsert;
    }

    @Override
    public SapResponse send(String entityId, String payloadHash, Customer customer) {
        if (customer == null) {
            // Un Customer nulo ya no es una senal de borrado (auditoria B2).
            throw new IllegalArgumentException("customer obligatorio para enviar a SAP; para la baja usa delete()");
        }
        String body = SapJsonMapper.write(toSapPayload(entityId, customer));
        return sapClient.send(SapDestination.S4_NATIVE, path, entityId, payloadHash, body);
    }

    /** Verificacion previa: {@code GET A_BusinessPartner('<entityId>')}. */
    @Override
    public SapLookup lookup(String entityId, Customer customer) {
        if (!upsert.lookupEnabled()) {
            return SapLookup.notSupported();
        }
        return ODataLookups.fromSingle(sapClient.get(SapDestination.S4_NATIVE, keyPath(entityId)), entityId);
    }

    /**
     * Actualizacion: {@code PATCH} sobre la clave con {@code If-Match}, nunca un
     * alta. El cuerpo NO reenvia la clave ni el {@code BusinessPartnerGrouping}:
     * son inmutables y S/4 rechaza el cambio.
     */
    @Override
    public SapResponse update(String entityId, String payloadHash, Customer customer, SapLookup found) {
        if (customer == null) {
            throw new IllegalArgumentException("customer obligatorio para actualizar en SAP");
        }
        var bp = new APIBUSINESSPARTNERABusinessPartnerTypeUpdate();
        bp.setOrganizationBPName1(n(customer.name()));
        return sapClient.patch(SapDestination.S4_NATIVE, keyPath(entityId), entityId, payloadHash,
                SapJsonMapper.write(bp), found.etag());
    }

    /**
     * Baja del Business Partner (sdd/customer/baja-cliente.md R-2). Que operacion
     * representa la baja en S/4 (flag de bloqueo vs borrado) se valida contra el
     * tenant de test en la Fase 3 del plan; hoy es el DELETE OData sobre la clave.
     */
    @Override
    public SapResponse delete(String entityId, String payloadHash) {
        return sapClient.delete(SapDestination.S4_NATIVE, keyPath(entityId));
    }

    private String keyPath(String entityId) {
        return path + "('" + ODataLookups.esc(entityId) + "')";
    }

    private APIBUSINESSPARTNERABusinessPartnerTypeCreate toSapPayload(String entityId, Customer c) {
        var bp = new APIBUSINESSPARTNERABusinessPartnerTypeCreate();
        bp.setBusinessPartner(entityId);
        bp.setBusinessPartnerCategory("2");           // 2 = Organization (Customer)
        bp.setBusinessPartnerGrouping("BPEE");        // external business partner
        bp.setOrganizationBPName1(n(c.name()));
        return bp;
    }

    private static String n(String s) { return s == null ? "" : s; }
}

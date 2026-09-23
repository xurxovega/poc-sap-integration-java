package com.poc.sap.customer.adapters.sap.odata;

import com.poc.sap.common.domain.port.SapKeyStorePort;
import com.poc.sap.common.domain.port.SapOutboundPort.SapLookup;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.SapUpsertSettings;
import com.poc.sap.common.sap.json.SapJsonMapper;
import com.poc.sap.common.sap.odata.ODataLookups;
import com.poc.sap.customer.domain.CustomerFeature;
import com.poc.sap.customer.domain.feature.address.AddressData;
import com.poc.sap.customer.domain.port.AddressSapPort;
import com.poc.sap.integration.api.customer.model.APIBUSINESSPARTNERABusinessPartnerAddressTypeCreate;
import com.poc.sap.integration.api.customer.model.APIBUSINESSPARTNERABusinessPartnerAddressTypeUpdate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Adaptador OData S/4HANA para la feature ADDRESS.
 * Usa modelo generado desde {@code API_BUSINESS_PARTNER.yaml}.
 *
 * <p>Upsert (spec {@code docs/sdd/common/upsert-idempotente-sap.md} R-4): el
 * {@code AddressID} lo <b>asigna SAP</b> y no es deducible, asi que se persiste en
 * {@link SapKeyStorePort}. Sin el, cada ciclo daria de alta una direccion nueva
 * (hallazgo 2B-6). Si no esta guardado se resuelve navegando desde el BP
 * ({@code to_BusinessPartnerAddress}) y se guarda.
 *
 * <p><b>A confirmar en tenant</b>: el formato del {@code AddressID}, si la
 * navegacion devuelve {@code __metadata.etag} por elemento y si el PATCH parcial
 * de la direccion se acepta.
 */
@Component
@ConditionalOnProperty(name = "sap.odata.address.enabled", havingValue = "true")
public class BusinessPartnerAddressODataAdapter implements AddressSapPort {

    /** Dominio del almacen de claves; el mismo que usa la maquina de estados. */
    static final String DOMAIN = "customer";

    private final SapClient sapClient;
    private final String path;
    private final String bpPath;
    private final SapKeyStorePort keyStore;
    private final SapUpsertSettings upsert;

    public BusinessPartnerAddressODataAdapter(
            SapClient sapClient,
            @Value("${sap.odata.address-path:/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartnerAddress}") String path,
            @Value("${sap.odata.bp-path:/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner}") String bpPath,
            SapKeyStorePort keyStore,
            SapUpsertSettings upsert) {
        this.sapClient = sapClient;
        this.path = path;
        this.bpPath = bpPath;
        this.keyStore = keyStore;
        this.upsert = upsert;
    }

    @Override
    public SapResponse send(String entityId, String payloadHash, AddressData a) {
        if (a == null) {
            return sapClient.send(SapDestination.S4_NATIVE, path, entityId, payloadHash, "{}");
        }
        String body = SapJsonMapper.write(toSapPayload(entityId, a));
        return sapClient.send(SapDestination.S4_NATIVE, path, entityId, payloadHash, body);
    }

    /**
     * Verificacion previa. Con la clave guardada va directa a ella; sin ella navega
     * desde el BP, y guarda lo que encuentre.
     */
    @Override
    public SapLookup lookup(String entityId, AddressData address) {
        if (!upsert.lookupEnabled()) {
            return SapLookup.notSupported();
        }
        Optional<String> stored = keyStore.find(DOMAIN, entityId, CustomerFeature.ADDRESS.name());
        if (stored.isPresent()) {
            return ODataLookups.fromSingle(
                    sapClient.get(SapDestination.S4_NATIVE, keyPath(entityId, stored.get())), stored.get());
        }
        SapLookup resolved = ODataLookups.fromCollection(
                sapClient.get(SapDestination.S4_NATIVE, navigationPath(entityId)),
                "AddressID", upsert.ambiguousFails());
        if (resolved.isFound()) {
            keyStore.save(DOMAIN, entityId, CustomerFeature.ADDRESS.name(), resolved.key());
        }
        return resolved;
    }

    /**
     * Actualizacion: {@code PATCH A_BusinessPartnerAddress(BusinessPartner,AddressID)}
     * con {@code If-Match}. El cuerpo no reenvia la clave: es inmutable.
     */
    @Override
    public SapResponse update(String entityId, String payloadHash, AddressData a, SapLookup found) {
        keyStore.save(DOMAIN, entityId, CustomerFeature.ADDRESS.name(), found.key());
        var addr = new APIBUSINESSPARTNERABusinessPartnerAddressTypeUpdate();
        if (a != null) {
            addr.setStreetName(n(a.street()));
            addr.setCityName(n(a.city()));
            addr.setPostalCode(n(a.postalCode()));
            addr.setCountry(n(a.country()));
            addr.setRegion(n(a.region()));
        }
        return sapClient.patch(SapDestination.S4_NATIVE, keyPath(entityId, found.key()), entityId,
                payloadHash, SapJsonMapper.write(addr), found.etag());
    }

    private String keyPath(String entityId, String addressId) {
        return path + "(BusinessPartner='" + ODataLookups.esc(entityId)
                + "',AddressID='" + ODataLookups.esc(addressId) + "')";
    }

    /** {@code $top=2}: con dos ya sabemos que el resultado es ambiguo, y no se pide mas. */
    private String navigationPath(String entityId) {
        return bpPath + "('" + ODataLookups.esc(entityId) + "')/to_BusinessPartnerAddress?$top=2";
    }

    private APIBUSINESSPARTNERABusinessPartnerAddressTypeCreate toSapPayload(String entityId, AddressData a) {
        var addr = new APIBUSINESSPARTNERABusinessPartnerAddressTypeCreate();
        addr.setBusinessPartner(entityId);
        addr.setStreetName(n(a.street()));
        addr.setCityName(n(a.city()));
        addr.setPostalCode(n(a.postalCode()));
        addr.setCountry(n(a.country()));
        addr.setRegion(n(a.region()));
        return addr;
    }

    private static String n(String s) { return s == null ? "" : s; }
}

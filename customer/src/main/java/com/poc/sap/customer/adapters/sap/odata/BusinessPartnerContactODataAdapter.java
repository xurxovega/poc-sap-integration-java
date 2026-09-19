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
import com.poc.sap.customer.domain.feature.contact.ContactData;
import com.poc.sap.customer.domain.port.ContactSapPort;
import com.poc.sap.integration.api.customer.model.APIBUSINESSPARTNERAAddressEmailAddressTypeCreate;
import com.poc.sap.integration.api.customer.model.APIBUSINESSPARTNERAAddressEmailAddressTypeUpdate;
import com.poc.sap.integration.api.customer.model.APIBUSINESSPARTNERAAddressFaxNumberTypeCreate;
import com.poc.sap.integration.api.customer.model.APIBUSINESSPARTNERAAddressFaxNumberTypeUpdate;
import com.poc.sap.integration.api.customer.model.APIBUSINESSPARTNERAAddressHomePageURLTypeCreate;
import com.poc.sap.integration.api.customer.model.APIBUSINESSPARTNERAAddressHomePageURLTypeUpdate;
import com.poc.sap.integration.api.customer.model.APIBUSINESSPARTNERAAddressPhoneNumberTypeCreate;
import com.poc.sap.integration.api.customer.model.APIBUSINESSPARTNERAAddressPhoneNumberTypeUpdate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Adaptador OData S/4HANA para la feature CONTACT
 * (spec {@code docs/sdd/customer/sincronizacion-contacto.md}).
 *
 * <p><b>Que entidad de SAP recibe estos datos (hallazgo 2B-5).</b> Antes se
 * enviaba a {@code A_BusinessPartnerContact} un cuerpo <b>sin un solo dato</b> de
 * {@link ContactData}, y no podia llevarlos: esa entidad modela la <b>relacion con
 * una persona de contacto</b> y exige {@code BusinessPartnerPerson} y
 * {@code RelationshipNumber}, que no tenemos ni sabemos generar. Email, telefono,
 * fax y web de un cliente son datos de comunicacion <b>de su direccion</b>, y en
 * {@code API_BUSINESS_PARTNER} viven en cuatro entidades hijas de la direccion:
 * {@code A_AddressEmailAddress}, {@code A_AddressPhoneNumber},
 * {@code A_AddressFaxNumber} y {@code A_AddressHomePageURL}. Su clave empieza por
 * el {@code AddressID} que asigna SAP, que es el que persiste la feature ADDRESS.
 *
 * <p><b>A confirmar en tenant</b>: el {@code Person} vacio y el
 * {@code OrdinalNumber} "0" para la primera entrada de una organizacion, y la
 * clave del {@code A_AddressHomePageURL}, que ademas lleva {@code ValidityStartDate}
 * e {@code IsDefaultURLAddress}.
 */
@Component
@ConditionalOnProperty(name = "sap.odata.contact.enabled", havingValue = "true")
public class BusinessPartnerContactODataAdapter implements ContactSapPort {

    static final String DOMAIN = "customer";
    /** Direccion de una organizacion: no cuelga de una persona. A confirmar en tenant. */
    static final String PERSON = "";
    /** Primera (y por ahora unica) entrada de cada tipo de comunicacion. */
    static final String ORDINAL = "0";
    /** S/4 solo admite 00010101 como inicio de validez de la URL. */
    static final String URL_VALIDITY_START = "datetime'0001-01-01T00:00:00'";

    private final SapClient sapClient;
    private final String apiBase;
    private final String bpPath;
    private final SapKeyStorePort keyStore;
    private final SapUpsertSettings upsert;

    public BusinessPartnerContactODataAdapter(
            SapClient sapClient,
            @Value("${sap.odata.bp-api-base:/sap/opu/odata/sap/API_BUSINESS_PARTNER}") String apiBase,
            @Value("${sap.odata.bp-path:/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner}") String bpPath,
            SapKeyStorePort keyStore,
            SapUpsertSettings upsert) {
        this.sapClient = sapClient;
        this.apiBase = apiBase;
        this.bpPath = bpPath;
        this.keyStore = keyStore;
        this.upsert = upsert;
    }

    /**
     * Alta de los datos de comunicacion. Con la verificacion previa activa, cada
     * campo se comprueba antes de escribirlo; sin ella, se envian altas directas.
     */
    @Override
    public SapResponse send(String entityId, String payloadHash, ContactData c) {
        SapLookup address = resolveAddress(entityId);
        if (address.isUnavailable()) {
            return new SapResponse(0, address.detail(), null);
        }
        return write(entityId, payloadHash, c, address.key(), upsert.lookupEnabled());
    }

    /**
     * Verificacion previa: resolver el {@code AddressID} del BP. No hay
     * {@code NOT_FOUND} posible, porque sin direccion en SAP los datos de
     * comunicacion no pueden colgar de ningun sitio: eso es «todavia no toca»
     * (UNAVAILABLE, se reintenta), no «hay que darlos de alta».
     */
    @Override
    public SapLookup lookup(String entityId, ContactData contact) {
        if (!upsert.lookupEnabled()) {
            return SapLookup.notSupported();
        }
        return resolveAddress(entityId);
    }

    /** Actualizacion campo a campo: PATCH con {@code If-Match} lo que existe, POST lo que falta. */
    @Override
    public SapResponse update(String entityId, String payloadHash, ContactData c, SapLookup found) {
        return write(entityId, payloadHash, c, found.key(), true);
    }

    // ---------------------------------------------------------------------

    /**
     * El {@code AddressID} del BP: primero del almacen de claves (lo guardo la
     * feature ADDRESS), y si no esta, navegando desde el BP.
     */
    private SapLookup resolveAddress(String entityId) {
        Optional<String> stored = keyStore.find(DOMAIN, entityId, CustomerFeature.ADDRESS.name());
        if (stored.isPresent()) {
            return SapLookup.found(stored.get(), null);
        }
        SapLookup resolved = ODataLookups.fromCollection(
                sapClient.get(SapDestination.S4_NATIVE,
                        bpPath + "('" + ODataLookups.esc(entityId) + "')/to_BusinessPartnerAddress?$top=2"),
                "AddressID", upsert.ambiguousFails());
        if (resolved.isFound()) {
            keyStore.save(DOMAIN, entityId, CustomerFeature.ADDRESS.name(), resolved.key());
            return resolved;
        }
        return SapLookup.unavailable("sin AddressID para " + entityId
                + ": la direccion del BP todavia no existe en SAP ("
                + (resolved.detail() == null ? "sin resultados" : resolved.detail()) + ")");
    }

    /**
     * Escribe cada campo con valor. A la primera respuesta que no sea de exito se
     * para: escribir el resto a ciegas solo empeora el fallo parcial.
     */
    private SapResponse write(String entityId, String payloadHash, ContactData c, String addressId,
                              boolean verifyEachField) {
        SapResponse last = new SapResponse(204, "", null);
        for (Field field : fieldsOf(c, addressId)) {
            SapResponse r = verifyEachField
                    ? upsertField(entityId, payloadHash, field)
                    : sapClient.send(SapDestination.S4_NATIVE, field.collectionPath(), entityId, payloadHash,
                            field.createBody());
            if (!r.isSuccess()) {
                return r;
            }
            last = r;
        }
        return last;
    }

    private SapResponse upsertField(String entityId, String payloadHash, Field field) {
        SapResponse got = sapClient.get(SapDestination.S4_NATIVE, field.keyPath());
        if (got.isSuccess()) {
            return sapClient.patch(SapDestination.S4_NATIVE, field.keyPath(), entityId, payloadHash,
                    field.updateBody(), ODataLookups.etagOf(got));
        }
        if (got.httpStatus() == 404) {
            return sapClient.send(SapDestination.S4_NATIVE, field.collectionPath(), entityId, payloadHash,
                    field.createBody());
        }
        return got;   // no sabemos que hay: no se escribe
    }

    /** Un campo de comunicacion con valor, con sus dos cuerpos y sus dos rutas. */
    private record Field(String collectionPath, String keyPath, String createBody, String updateBody) {}

    private List<Field> fieldsOf(ContactData c, String addressId) {
        List<Field> fields = new ArrayList<>();
        if (c == null) {
            return fields;
        }
        if (has(c.email())) {
            var create = new APIBUSINESSPARTNERAAddressEmailAddressTypeCreate();
            create.setAddressID(addressId);
            create.setPerson(PERSON);
            create.setOrdinalNumber(ORDINAL);
            create.setEmailAddress(c.email());
            create.setIsDefaultEmailAddress(true);
            var update = new APIBUSINESSPARTNERAAddressEmailAddressTypeUpdate();
            update.setEmailAddress(c.email());
            fields.add(new Field(collection("A_AddressEmailAddress"),
                    commKey("A_AddressEmailAddress", addressId),
                    SapJsonMapper.write(create), SapJsonMapper.write(update)));
        }
        if (has(c.phone())) {
            var create = new APIBUSINESSPARTNERAAddressPhoneNumberTypeCreate();
            create.setAddressID(addressId);
            create.setPerson(PERSON);
            create.setOrdinalNumber(ORDINAL);
            create.setPhoneNumber(c.phone());
            create.setIsDefaultPhoneNumber(true);
            var update = new APIBUSINESSPARTNERAAddressPhoneNumberTypeUpdate();
            update.setPhoneNumber(c.phone());
            fields.add(new Field(collection("A_AddressPhoneNumber"),
                    commKey("A_AddressPhoneNumber", addressId),
                    SapJsonMapper.write(create), SapJsonMapper.write(update)));
        }
        if (has(c.fax())) {
            var create = new APIBUSINESSPARTNERAAddressFaxNumberTypeCreate();
            create.setAddressID(addressId);
            create.setPerson(PERSON);
            create.setOrdinalNumber(ORDINAL);
            create.setFaxNumber(c.fax());
            create.setIsDefaultFaxNumber(true);
            var update = new APIBUSINESSPARTNERAAddressFaxNumberTypeUpdate();
            update.setFaxNumber(c.fax());
            fields.add(new Field(collection("A_AddressFaxNumber"),
                    commKey("A_AddressFaxNumber", addressId),
                    SapJsonMapper.write(create), SapJsonMapper.write(update)));
        }
        if (has(c.website())) {
            var create = new APIBUSINESSPARTNERAAddressHomePageURLTypeCreate();
            create.setAddressID(addressId);
            create.setPerson(PERSON);
            create.setOrdinalNumber(ORDINAL);
            create.setValidityStartDate(URL_VALIDITY_START);
            create.setIsDefaultURLAddress(true);
            create.setWebsiteURL(c.website());
            var update = new APIBUSINESSPARTNERAAddressHomePageURLTypeUpdate();
            update.setWebsiteURL(c.website());
            fields.add(new Field(collection("A_AddressHomePageURL"),
                    urlKey(addressId),
                    SapJsonMapper.write(create), SapJsonMapper.write(update)));
        }
        return fields;
    }

    private String collection(String entity) {
        return apiBase + "/" + entity;
    }

    private String commKey(String entity, String addressId) {
        return collection(entity) + "(AddressID='" + ODataLookups.esc(addressId)
                + "',Person='" + PERSON + "',OrdinalNumber='" + ORDINAL + "')";
    }

    /** La URL lleva dos claves mas: fecha de validez e indicador de direccion por defecto. */
    private String urlKey(String addressId) {
        return collection("A_AddressHomePageURL") + "(AddressID='" + ODataLookups.esc(addressId)
                + "',Person='" + PERSON + "',OrdinalNumber='" + ORDINAL
                + "',ValidityStartDate=" + URL_VALIDITY_START + ",IsDefaultURLAddress=true)";
    }

    private static boolean has(String s) {
        return s != null && !s.isBlank();
    }
}

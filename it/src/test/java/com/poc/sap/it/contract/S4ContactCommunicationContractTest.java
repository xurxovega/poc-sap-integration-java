package com.poc.sap.it.contract;

import com.poc.sap.common.domain.port.SapKeyStorePort;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapUpsertSettings;
import com.poc.sap.customer.adapters.sap.odata.BusinessPartnerContactODataAdapter;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato S/4 de los datos de comunicacion del cliente (feature CONTACT),
 * ejercitando el adaptador REAL (spec docs/sdd/customer/sincronizacion-contacto.md
 * AC-1; hallazgo 2B-5).
 *
 * <p>Este test es la prueba de que el payload <b>ya lleva</b> los datos de
 * {@code ContactData}: antes salia un cuerpo con {@code BusinessPartnerCompany} y
 * {@code RelationshipCategory} y nada mas.
 */
class S4ContactCommunicationContractTest extends AbstractSapContractTest {

    private static final String BASE = "/sap/opu/odata/sap/API_BUSINESS_PARTNER";
    private static final String BP_PATH = BASE + "/A_BusinessPartner";
    private static final String EMAIL = BASE + "/A_AddressEmailAddress";
    private static final String PHONE = BASE + "/A_AddressPhoneNumber";
    private static final String EMAIL_KEY = EMAIL + "(AddressID='0000123456',Person='',OrdinalNumber='0')";
    private static final String PHONE_KEY = PHONE + "(AddressID='0000123456',Person='',OrdinalNumber='0')";

    private final Map<String, String> keys = new HashMap<>(
            Map.of("customer:C-1:ADDRESS", "0000123456"));

    private final SapKeyStorePort keyStore = new SapKeyStorePort() {
        @Override public Optional<String> find(String domain, String entityId, String feature) {
            return Optional.ofNullable(keys.get(domain + ":" + entityId + ":" + feature));
        }
        @Override public void save(String domain, String entityId, String feature, String key) {
            keys.put(domain + ":" + entityId + ":" + feature, key);
        }
        @Override public void delete(String domain, String entityId) {
            keys.keySet().removeIf(k -> k.startsWith(domain + ":" + entityId + ":"));
        }
    };

    private BusinessPartnerContactODataAdapter adapter() {
        return new BusinessPartnerContactODataAdapter(sapClient, BASE, BP_PATH, keyStore,
                SapUpsertSettings.defaults());
    }

    /**
     * AC-1: email y telefono viajan a las entidades de comunicacion de la direccion,
     * colgados del {@code AddressID}, con los datos reales del cliente.
     */
    @Test
    void realAdapterPostsEmailAndPhoneUnderTheAddressId() {
        sap.stubFor(get(urlEqualTo(EMAIL_KEY)).willReturn(aResponse().withStatus(404)));
        sap.stubFor(get(urlEqualTo(PHONE_KEY)).willReturn(aResponse().withStatus(404)));
        sap.stubFor(post(urlPathEqualTo(EMAIL)).willReturn(aResponse().withStatus(201).withBody("{}")));
        sap.stubFor(post(urlPathEqualTo(PHONE)).willReturn(aResponse().withStatus(201).withBody("{}")));

        SapResponse r = adapter().send("C-1", "h-1",
                new ContactData("cliente@example.com", "+34910000000", null, null));

        assertThat(r.isSuccess()).isTrue();
        sap.verify(postRequestedFor(urlPathEqualTo(EMAIL))
                .withHeader("Idempotency-Key", equalTo("h-1"))
                .withRequestBody(matchingJsonPath("$.AddressID", equalTo("0000123456")))
                .withRequestBody(matchingJsonPath("$.EmailAddress", equalTo("cliente@example.com"))));
        sap.verify(postRequestedFor(urlPathEqualTo(PHONE))
                .withRequestBody(matchingJsonPath("$.AddressID", equalTo("0000123456")))
                .withRequestBody(matchingJsonPath("$.PhoneNumber", equalTo("+34910000000"))));
    }
}

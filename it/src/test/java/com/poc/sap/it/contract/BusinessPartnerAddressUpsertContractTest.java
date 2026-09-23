package com.poc.sap.it.contract;

import com.poc.sap.common.domain.port.SapKeyStorePort;
import com.poc.sap.common.domain.port.SapOutboundPort.SapLookup;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapUpsertSettings;
import com.poc.sap.customer.adapters.sap.odata.BusinessPartnerAddressODataAdapter;
import com.poc.sap.customer.domain.feature.address.AddressData;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato S/4 del upsert de la direccion, ejercitando el adaptador REAL
 * (spec docs/sdd/common/upsert-idempotente-sap.md AC-6/AC-8).
 *
 * <p>Es el caso que mas duele si falla: sin el {@code AddressID} persistido, cada
 * ciclo daba de alta una direccion nueva sobre el mismo Business Partner (2B-6).
 */
class BusinessPartnerAddressUpsertContractTest extends AbstractSapContractTest {

    private static final String PATH = "/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartnerAddress";
    private static final String BP_PATH = "/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner";
    private static final String NAV = BP_PATH + "('C-1')/to_BusinessPartnerAddress?$top=2";
    private static final String KEY = PATH + "(BusinessPartner='C-1',AddressID='0000123456')";

    /** Almacen de claves en memoria: lo que interesa aqui es el contrato HTTP. */
    private final Map<String, String> keys = new HashMap<>();

    private final SapKeyStorePort keyStore = new SapKeyStorePort() {
        @Override public Optional<String> find(String domain, String entityId, String feature) {
            return Optional.ofNullable(keys.get(domain + ":" + entityId + ":" + feature));
        }
        @Override public void save(String domain, String entityId, String feature, String key) {
            if (key != null && !key.isBlank()) {
                keys.put(domain + ":" + entityId + ":" + feature, key);
            }
        }
        @Override public void delete(String domain, String entityId) {
            keys.keySet().removeIf(k -> k.startsWith(domain + ":" + entityId + ":"));
        }
    };

    private BusinessPartnerAddressODataAdapter adapter() {
        return new BusinessPartnerAddressODataAdapter(sapClient, PATH, BP_PATH, keyStore,
                SapUpsertSettings.defaults());
    }

    private static AddressData address() {
        return new AddressData("Calle Mayor 1", "Madrid", "28013", "ES", "M");
    }

    /**
     * AC-6/AC-8: sin clave guardada se navega desde el BP, se guarda el
     * {@code AddressID} y el segundo lookup ya no navega.
     */
    @Test
    void realAdapterResolvesTheAddressIdByNavigationAndThenReusesIt() {
        sap.stubFor(get(urlEqualTo(NAV)).willReturn(aResponse().withStatus(200).withBody(
                "{\"d\":{\"results\":[{\"AddressID\":\"0000123456\",\"CityName\":\"Madrid\"}]}}")));
        sap.stubFor(get(urlEqualTo(KEY)).willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "W/\"a1\"").withBody("{\"d\":{\"AddressID\":\"0000123456\"}}")));

        assertThat(adapter().lookup("C-1", address()).key()).isEqualTo("0000123456");
        assertThat(adapter().lookup("C-1", address()).key()).isEqualTo("0000123456");

        sap.verify(1, getRequestedFor(urlEqualTo(NAV)));
        sap.verify(1, getRequestedFor(urlEqualTo(KEY)));
    }

    /** AC-8: la actualizacion es un PATCH sobre la clave compuesta con If-Match. */
    @Test
    void realAdapterPatchesTheAddressWithIfMatch() {
        sap.stubFor(patch(urlEqualTo(KEY)).willReturn(aResponse().withStatus(204)));

        SapResponse r = adapter().update("C-1", "h-1", address(), SapLookup.found("0000123456", "W/\"a1\""));

        assertThat(r.httpStatus()).isEqualTo(204);
        sap.verify(patchRequestedFor(urlEqualTo(KEY))
                .withHeader("If-Match", equalTo("W/\"a1\""))
                .withRequestBody(matchingJsonPath("$.CityName", equalTo("Madrid")))
                .withRequestBody(matchingJsonPath("$.PostalCode", equalTo("28013"))));
        assertThat(keys).containsEntry("customer:C-1:ADDRESS", "0000123456");
    }

    /** AC-4/AC-8: dos direcciones es un resultado no concluyente; no se escribe nada. */
    @Test
    void realAdapterRefusesToGuessWhenTheNavigationIsAmbiguous() {
        sap.stubFor(get(urlEqualTo(NAV)).willReturn(aResponse().withStatus(200).withBody(
                "{\"d\":{\"results\":[{\"AddressID\":\"1\"},{\"AddressID\":\"2\"}]}}")));

        SapLookup found = adapter().lookup("C-1", address());

        assertThat(found.outcome()).isEqualTo(SapLookup.Outcome.UNAVAILABLE);
        assertThat(found.detail()).contains("ambiguo");
        assertThat(keys).isEmpty();
    }
}

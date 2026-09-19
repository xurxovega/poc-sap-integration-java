package com.poc.sap.it.contract;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.customer.adapters.sap.BtpAddressAdapter;
import com.poc.sap.customer.domain.feature.address.AddressData;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato BTP de la direccion, ejercitando el adaptador REAL
 * (sdd/customer/sincronizacion-direccion.md §5 mapeo; auditoria B6).
 */
class BtpAddressContractTest extends AbstractSapContractTest {

    private static final String PATH = "/sap/btp/odata/CustomerAddress";

    @Test
    void realAdapterPostsMappedAddressWithAuthAndIdempotencyKey() {
        sap.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse()
                .withStatus(201)
                .withHeader("Location", "https://btp/Customer('C-1')/Address")
                .withBody("{\"BusinessPartner\":\"C-1\"}")));

        SapResponse r = new BtpAddressAdapter(sapClient, PATH)
                .send("C-1", "h-1", new AddressData("Calle 1", "Madrid", "28001", "ES", "M"));

        assertThat(r.httpStatus()).isEqualTo(201);
        assertThat(r.location()).isEqualTo("https://btp/Customer('C-1')/Address");
        sap.verify(postRequestedFor(urlPathEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("Idempotency-Key", equalTo("h-1"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.BusinessPartner", equalTo("C-1")))
                .withRequestBody(matchingJsonPath("$.Street", equalTo("Calle 1")))
                .withRequestBody(matchingJsonPath("$.City", equalTo("Madrid")))
                .withRequestBody(matchingJsonPath("$.PostalCode", equalTo("28001")))
                .withRequestBody(matchingJsonPath("$.Country", equalTo("ES")))
                .withRequestBody(matchingJsonPath("$.Region", equalTo("M"))));
    }

    /**
     * El retry del cliente real sobre una ESCRITURA: un 5xx se reporta como fallo
     * <b>sin reintentar</b>.
     *
     * <p>Este test afirmaba lo contrario («se reintenta 3 veces») y consagraba el
     * defecto 2B-3: SAP pudo haber aplicado ese POST, y reintentarlo crea un
     * duplicado. La regla vigente es
     * {@code docs/sdd/common/resiliencia-cliente-sap.md} R-1 reescrita: una
     * escritura no idempotente solo se reintenta si el fallo ocurrió ANTES de que
     * la petición saliera. El reintento del 5xx sigue existiendo para las lecturas
     * ({@code RestClientSapClientTest#getIsStillRetriedOnServerError}).
     */
    @Test
    void serverErrorOnAWriteIsReportedWithoutRetrying() {
        sap.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        SapResponse r = new BtpAddressAdapter(sapClient, PATH)
                .send("C-1", "h-2", new AddressData("Calle 1", "Madrid", "28001", "ES", "M"));

        assertThat(r.isSuccess()).isFalse();
        assertThat(r.httpStatus()).isEqualTo(503);
        sap.verify(1, postRequestedFor(urlPathEqualTo(PATH)));
    }
}

package com.poc.sap.it.contract;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de contrato SAP S/4 nativo para la feature BANKING / mandates
 * (TECH.md §8, §10). Reemplaza al antiguo S4MandateAdapter.
 */
class S4BankingContractTest extends AbstractSapContractTest {

    @Test
    void bankingEndpointAccepted() throws Exception {
        sap.stubFor(post(urlPathEqualTo("/sap/opu/odata/sap/API_CUSTOMER_MANDATE"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withBody("{\"CustomerID\":\"C-1\",\"IBAN\":\"ES76...\"}")));

        HttpResponse<String> resp = postJson(
                "/sap/opu/odata/sap/API_CUSTOMER_MANDATE",
                """
                {"CustomerID":"C-1","IBAN":"ES7621000418401234567890","BIC":"BBVAESMM","Mandates":"M-1,M-2"}""");

        assertThat(resp.statusCode()).isEqualTo(202);
        assertThat(resp.body()).contains("IBAN");
        sap.verify(postRequestedFor(urlPathEqualTo("/sap/opu/odata/sap/API_CUSTOMER_MANDATE")));
    }

    @Test
    void s4ReturnsErrorOnInvalidAuth() throws Exception {
        sap.stubFor(post(urlPathEqualTo("/sap/opu/odata/sap/API_CUSTOMER_MANDATE"))
                .willReturn(aResponse().withStatus(401).withBody("Unauthorized")));

        HttpResponse<String> resp = postJson(
                "/sap/opu/odata/sap/API_CUSTOMER_MANDATE",
                """
                {"CustomerID":"C-1","IBAN":"bad","BIC":"x"}""");

        assertThat(resp.statusCode()).isEqualTo(401);
    }
}
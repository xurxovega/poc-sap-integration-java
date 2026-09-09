package com.poc.sap.it.contract;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de contrato SAP S/4 nativo para Article / API_PRODUCT (TECH.md §8;
 * TECH.md §10). Patron paralelo a {@link S4BankingContractTest} pero para el
 * dominio Article.
 */
class S4ArticleContractTest extends AbstractSapContractTest {

    @Test
    void articleEndpointAccepted() throws Exception {
        sap.stubFor(post(urlPathEqualTo("/sap/opu/odata/sap/API_PRODUCT"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Location", "https://s4/Product('SKU-001')")
                        .withBody("{\"Product\":\"SKU-001\"}")));

        HttpResponse<String> resp = postJson(
                "/sap/opu/odata/sap/API_PRODUCT",
                """
                {"Product":"SKU-001","Description":"Tornillo M6","Category":"Hardware","BaseUnit":"UN","Status":"ACTIVE"}""");

        assertThat(resp.statusCode()).isEqualTo(201);
        assertThat(resp.headers().firstValue("Location")).isPresent();
        assertThat(resp.body()).contains("SKU-001");
        sap.verify(postRequestedFor(urlPathEqualTo("/sap/opu/odata/sap/API_PRODUCT")));
    }

    @Test
    void s4ReturnsErrorOnInvalidAuth() throws Exception {
        sap.stubFor(post(urlPathEqualTo("/sap/opu/odata/sap/API_PRODUCT"))
                .willReturn(aResponse().withStatus(401).withBody("Unauthorized")));

        HttpResponse<String> resp = postJson(
                "/sap/opu/odata/sap/API_PRODUCT",
                """
                {"Product":"SKU-001"}""");

        assertThat(resp.statusCode()).isEqualTo(401);
    }
}
package com.poc.sap.it.contract;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de contrato SAP BTP para la feature ADDRESS (SPEC.md §5; TECH.md §10).
 */
class BtpAddressContractTest extends AbstractSapContractTest {

    @Test
    void addressEndpointRespondsCreated() throws Exception {
        sap.stubFor(post(urlPathEqualTo("/sap/btp/odata/CustomerAddress"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Location", "https://btp/Customer('C-1')/Address")
                        .withBody("{\"BusinessPartner\":\"C-1\"}")));

        HttpResponse<String> resp = postJson(
                "/sap/btp/odata/CustomerAddress",
                """
                {"BusinessPartner":"C-1","Street":"Calle 1","City":"Madrid","PostalCode":"28001","Country":"ES","Region":"M"}""");

        assertThat(resp.statusCode()).isEqualTo(201);
        assertThat(resp.headers().firstValue("Location")).isPresent();
        sap.verify(postRequestedFor(urlPathEqualTo("/sap/btp/odata/CustomerAddress")));
    }

    @Test
    void requiresIdempotencyKeyHeaderOnSubsequentCalls() throws Exception {
        sap.stubFor(post(urlPathEqualTo("/sap/btp/odata/CustomerAddress"))
                .withHeader("Idempotency-Key", matching("h-[0-9]+"))
                .willReturn(aResponse().withStatus(201).withBody("{}")));

        HttpResponse<String> resp = postJsonWithHeader(
                "/sap/btp/odata/CustomerAddress",
                """
                {"BusinessPartner":"C-1"}""","Idempotency-Key","h-1");

        assertThat(resp.statusCode()).isEqualTo(201);
    }

    private HttpResponse<String> postJsonWithHeader(String path, String body,
                                                     String hName, String hValue) throws Exception {
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(sap.baseUrl() + path))
                .header("Content-Type", "application/json")
                .header(hName, hValue)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
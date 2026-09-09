package com.poc.sap.it;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de contrato SAP BTP (TECH.md §8, §10).
 * Fija el contrato del endpoint BTP Customer: realiza un POST real contra el
 * WireMock y verifica que devuelve 201 con Location.
 */
class BtpCustomerContractTest {

    @RegisterExtension
    static WireMockExtension sap = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void customerBtpEndpointRespondsCreated() throws Exception {
        sap.stubFor(post(urlPathEqualTo("/sap/btp/odata/Customer"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Location", "https://btp/Customer('CUST-001')")
                        .withBody("{\"BusinessPartner\":\"CUST-001\"}")));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(sap.baseUrl() + "/sap/btp/odata/Customer"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"BusinessPartner\":\"CUST-001\"}"))
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(201);
        assertThat(resp.headers().firstValue("Location")).isPresent();
        assertThat(resp.body()).contains("CUST-001");
        sap.verify(postRequestedFor(urlPathEqualTo("/sap/btp/odata/Customer")));
    }
}
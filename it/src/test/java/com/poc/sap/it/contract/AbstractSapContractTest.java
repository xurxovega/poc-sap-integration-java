package com.poc.sap.it.contract;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Base para tests de contrato SAP (SPEC.md §5; TECH.md §10).
 * Provee un WireMock por test y un helper POST HTTP.
 */
abstract class AbstractSapContractTest {

    @RegisterExtension
    static final WireMockExtension sap = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    protected final HttpClient http = HttpClient.newHttpClient();

    /** POST JSON a un path bajo el WireMock y devuelve respuesta cruda. */
    protected HttpResponse<String> postJson(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(sap.baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
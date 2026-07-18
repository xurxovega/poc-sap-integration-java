package com.poc.sap.it.contract;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de contrato SAP BTP para la feature CONTACT (SPEC.md §5; TECH.md §10).
 */
class BtpContactContractTest extends AbstractSapContractTest {

    @Test
    void contactEndpointRespondsCreated() throws Exception {
        sap.stubFor(post(urlPathEqualTo("/sap/btp/odata/CustomerContact"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withBody("{\"Email\":\"info@acme.com\"}")));

        HttpResponse<String> resp = postJson(
                "/sap/btp/odata/CustomerContact",
                """
                {"BusinessPartner":"C-1","Email":"info@acme.com","Phone":"+34 600000000","Fax":"","Website":""}""");

        assertThat(resp.statusCode()).isEqualTo(201);
        sap.verify(postRequestedFor(urlPathEqualTo("/sap/btp/odata/CustomerContact")));
    }

    @Test
    void badRequestWhenContactBodyMalformed() throws Exception {
        sap.stubFor(post(urlPathEqualTo("/sap/btp/odata/CustomerContact"))
                .willReturn(aResponse().withStatus(400).withBody("malformed contact")));

        HttpResponse<String> resp = postJson(
                "/sap/btp/odata/CustomerContact",
                """
                {"BusinessPartner":"C-1"}""");

        assertThat(resp.statusCode()).isEqualTo(400);
    }
}
package com.poc.sap.it.contract;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de contrato SAP BTP para la feature FISCAL (SPEC.md §5; TECH.md §10).
 */
class BtpFiscalContractTest extends AbstractSapContractTest {

    @Test
    void fiscalEndpointRespondsAccepted() throws Exception {
        sap.stubFor(post(urlPathEqualTo("/sap/btp/odata/CustomerFiscal"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withBody("{\"TaxNumber\":\"A12345678\"}")));

        HttpResponse<String> resp = postJson(
                "/sap/btp/odata/CustomerFiscal",
                """
                {"BusinessPartner":"C-1","TaxNumber":"A12345678","VATNumber":"","LegalName":"Acme","TaxResidency":"ES"}""");

        assertThat(resp.statusCode()).isEqualTo(202);
        assertThat(resp.body()).contains("A12345678");
        sap.verify(postRequestedFor(urlPathEqualTo("/sap/btp/odata/CustomerFiscal")));
    }

    @Test
    void conflictWhenTaxIdAlreadyExists() throws Exception {
        sap.stubFor(post(urlPathEqualTo("/sap/btp/odata/CustomerFiscal"))
                .willReturn(aResponse().withStatus(409).withBody("TaxNumber already exists")));

        HttpResponse<String> resp = postJson(
                "/sap/btp/odata/CustomerFiscal",
                """
                {"BusinessPartner":"C-1","TaxNumber":"DUP","LegalName":"Acme","TaxResidency":"ES"}""");

        assertThat(resp.statusCode()).isEqualTo(409);
    }
}
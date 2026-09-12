package com.poc.sap.it.contract;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.customer.adapters.sap.BtpFiscalAdapter;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** Contrato BTP de los datos fiscales, ejercitando el adaptador REAL (auditoria B6). */
class BtpFiscalContractTest extends AbstractSapContractTest {

    private static final String PATH = "/sap/btp/odata/CustomerFiscal";

    @Test
    void realAdapterPostsMappedFiscalData() {
        sap.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(201).withBody("{}")));

        SapResponse r = new BtpFiscalAdapter(sapClient, PATH)
                .send("C-1", "h-1", new FiscalData("A12345678", "ESA12345678", "Acme S.L.", "ES"));

        assertThat(r.httpStatus()).isEqualTo(201);
        sap.verify(postRequestedFor(urlPathEqualTo(PATH))
                .withHeader("Idempotency-Key", equalTo("h-1"))
                .withRequestBody(matchingJsonPath("$.BusinessPartner", equalTo("C-1")))
                .withRequestBody(matchingJsonPath("$.TaxNumber", equalTo("A12345678")))
                .withRequestBody(matchingJsonPath("$.VATNumber", equalTo("ESA12345678")))
                .withRequestBody(matchingJsonPath("$.LegalName", equalTo("Acme S.L.")))
                .withRequestBody(matchingJsonPath("$.TaxResidency", equalTo("ES"))));
    }
}

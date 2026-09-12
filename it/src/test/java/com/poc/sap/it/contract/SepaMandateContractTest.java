package com.poc.sap.it.contract;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.customer.adapters.sap.odata.SepaMandateODataAdapter;
import com.poc.sap.customer.domain.Mandate;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato S/4 real de API_APAR_SEPA_MANDATE_SRV, ejercitando el adaptador REAL
 * (sdd/customer/sincronizacion-datos-bancarios.md AC-4; sdd/customer/baja-mandato-sepa.md AC-3).
 */
class SepaMandateContractTest extends AbstractSapContractTest {

    private static final String PATH = "/sap/opu/odata/sap/API_APAR_SEPA_MANDATE_SRV/SEPAMandateSet";
    private static final String CREDITOR = "ES98ZZZ12345678901";

    private SepaMandateODataAdapter adapter() {
        return new SepaMandateODataAdapter(sapClient, PATH, CREDITOR, "F");
    }

    @Test
    void realAdapterCreatesMandateOnSepaMandateSet() {
        sap.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(201).withBody("{}")));

        SapResponse r = adapter().send("M-1", "h-1",
                new Mandate("M-1", "C-1", "ES7621000418401234567890", "BBVAESMM", "2026-01-15", Mandate.Status.ACTIVE));

        assertThat(r.httpStatus()).isEqualTo(201);
        sap.verify(postRequestedFor(urlPathEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("Idempotency-Key", equalTo("h-1"))
                .withRequestBody(matchingJsonPath("$.Creditor", equalTo(CREDITOR)))
                .withRequestBody(matchingJsonPath("$.SEPAMandate", equalTo("M-1")))
                .withRequestBody(matchingJsonPath("$.Sender", equalTo("C-1")))
                .withRequestBody(matchingJsonPath("$.SenderIBAN", equalTo("ES7621000418401234567890")))
                .withRequestBody(matchingJsonPath("$.SenderBankSWIFTCode", equalTo("BBVAESMM")))
                .withRequestBody(matchingJsonPath("$.SEPAMandateStatus", equalTo("1"))));
    }

    @Test
    void realAdapterRevokesByPatchingStatusOnTheKey() {
        sap.stubFor(patch(anyUrl()).willReturn(aResponse().withStatus(204)));

        SapResponse r = adapter().revoke("M-1", "h-rev");

        assertThat(r.isSuccess()).isTrue();
        sap.verify(patchRequestedFor(urlPathEqualTo(PATH + "(Creditor='" + CREDITOR + "',SEPAMandate='M-1')"))
                .withHeader("Idempotency-Key", equalTo("h-rev"))
                .withRequestBody(equalToJson("{\"SEPAMandateStatus\":\"3\"}")));
        sap.verify(0, postRequestedFor(anyUrl()));
        sap.verify(0, deleteRequestedFor(anyUrl()));
    }
}

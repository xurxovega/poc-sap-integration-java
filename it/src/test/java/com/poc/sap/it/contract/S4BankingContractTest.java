package com.poc.sap.it.contract;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.customer.adapters.sap.S4BankingAdapter;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato S/4 nativo de los datos bancarios, ejercitando el adaptador REAL
 * (auditoria B6). El path apunta a una API que la auditoria senala como
 * inexistente en S/4 (B3, Fase 3): este test fija lo que hoy se envia, no lo
 * que S/4 espera.
 */
class S4BankingContractTest extends AbstractSapContractTest {

    private static final String PATH = "/sap/opu/odata/sap/API_CUSTOMER_MANDATE";

    @Test
    void realAdapterPostsMappedBankingData() {
        sap.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(201).withBody("{}")));

        SapResponse r = new S4BankingAdapter(sapClient, PATH).send("C-1", "h-1",
                new BankingData("ES7621000418401234567890", "BBVAESMM", List.of("M-1")));

        assertThat(r.httpStatus()).isEqualTo(201);
        sap.verify(postRequestedFor(urlPathEqualTo(PATH))
                .withHeader("Idempotency-Key", equalTo("h-1"))
                .withRequestBody(matchingJsonPath("$.CustomerID", equalTo("C-1")))
                .withRequestBody(matchingJsonPath("$.IBAN", equalTo("ES7621000418401234567890")))
                .withRequestBody(matchingJsonPath("$.BIC", equalTo("BBVAESMM"))));
    }
}

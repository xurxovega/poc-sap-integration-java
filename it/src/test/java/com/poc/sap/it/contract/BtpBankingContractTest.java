package com.poc.sap.it.contract;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.customer.adapters.sap.BtpBankingAdapter;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato BTP de los datos bancarios, ejercitando el adaptador REAL
 * (sdd/customer/sincronizacion-datos-bancarios.md AC-1; auditoria B3/B6).
 * Antes S4BankingContractTest, contra una API S/4 que no existe.
 */
class BtpBankingContractTest extends AbstractSapContractTest {

    private static final String PATH = "/sap/btp/odata/CustomerBanking";

    @Test
    void realAdapterPostsBankingContractWithMandateList() {
        sap.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(201).withBody("{}")));

        SapResponse r = new BtpBankingAdapter(sapClient, PATH).send("C-1", "h-1",
                new BankingData("ES7621000418401234567890", "BBVAESMM", List.of("M-1")));

        assertThat(r.httpStatus()).isEqualTo(201);
        sap.verify(postRequestedFor(urlPathEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("Idempotency-Key", equalTo("h-1"))
                .withRequestBody(equalToJson(
                        "{\"BusinessPartner\":\"C-1\",\"IBAN\":\"ES7621000418401234567890\","
                        + "\"BIC\":\"BBVAESMM\",\"Mandates\":[\"M-1\"]}")));
    }
}

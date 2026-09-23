package com.poc.sap.it.contract;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapUpsertSettings;
import com.poc.sap.customer.adapters.sap.odata.BusinessPartnerBankODataAdapter;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato S/4 real de A_BusinessPartnerBank, ejercitando el adaptador REAL
 * (sdd/customer/sincronizacion-datos-bancarios.md AC-2/AC-3; auditoria B3).
 * BankIdentification es un ordinal y el BIC no viaja.
 */
class S4BankODataContractTest extends AbstractSapContractTest {

    private static final String PATH = "/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartnerBank";

    @Test
    void realAdapterPostsBankWithoutBicAndWithCountryFromIban() {
        sap.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(201).withBody("{}")));

        SapResponse r = new BusinessPartnerBankODataAdapter(sapClient, PATH, SapUpsertSettings.defaults()).send("C-1", "h-1",
                new BankingData("ES7621000418401234567890", "BBVAESMM", List.of()));

        assertThat(r.httpStatus()).isEqualTo(201);
        sap.verify(postRequestedFor(urlPathEqualTo(PATH))
                .withHeader("Idempotency-Key", equalTo("h-1"))
                .withRequestBody(matchingJsonPath("$.BusinessPartner", equalTo("C-1")))
                .withRequestBody(matchingJsonPath("$.BankIdentification", equalTo("0001")))
                .withRequestBody(matchingJsonPath("$.BankCountryKey", equalTo("ES")))
                .withRequestBody(matchingJsonPath("$.IBAN", equalTo("ES7621000418401234567890")))
                .withRequestBody(notMatching(".*BBVAESMM.*")));
    }
}

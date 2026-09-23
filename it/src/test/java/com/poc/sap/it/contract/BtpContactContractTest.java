package com.poc.sap.it.contract;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.customer.adapters.sap.BtpContactAdapter;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** Contrato BTP del contacto, ejercitando el adaptador REAL (auditoria B6). */
class BtpContactContractTest extends AbstractSapContractTest {

    private static final String PATH = "/sap/btp/odata/CustomerContact";

    @Test
    void realAdapterPostsMappedContact() {
        sap.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(201).withBody("{}")));

        SapResponse r = new BtpContactAdapter(sapClient, PATH)
                .send("C-1", "h-1", new ContactData("info@acme.com", "+34 600 000 000", null, "https://acme.com"));

        assertThat(r.httpStatus()).isEqualTo(201);
        sap.verify(postRequestedFor(urlPathEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("Idempotency-Key", equalTo("h-1"))
                .withRequestBody(matchingJsonPath("$.BusinessPartner", equalTo("C-1")))
                .withRequestBody(matchingJsonPath("$.Email", equalTo("info@acme.com")))
                .withRequestBody(matchingJsonPath("$.Phone", equalTo("+34 600 000 000")))
                .withRequestBody(matchingJsonPath("$.Website", equalTo("https://acme.com"))));
    }
}

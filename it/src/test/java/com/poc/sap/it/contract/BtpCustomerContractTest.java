package com.poc.sap.it.contract;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.customer.adapters.sap.BtpCustomerAdapter;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.feature.address.AddressData;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato BTP del agregado Customer y de su baja, ejercitando el adaptador REAL
 * (sdd/customer/baja-cliente.md AC-2; auditoria B2/B6).
 */
class BtpCustomerContractTest extends AbstractSapContractTest {

    private static final String PATH = "/sap/btp/odata/Customer";

    private static Customer customer() {
        return new Customer("C-1", "CUST-001", "Acme", Customer.Status.ACTIVE,
                new AddressData("Calle 1", "Madrid", "28001", "ES", "M"),
                new FiscalData("A12345678", null, "Acme", "ES"),
                new ContactData("info@acme.com", "+34 600 000 000", null, null),
                new BankingData("ES7621000418401234567890", "BBVAESMM", List.of()));
    }

    @Test
    void realAdapterPostsCustomerHeader() {
        sap.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(201).withBody("{}")));

        SapResponse r = new BtpCustomerAdapter(sapClient, PATH).send("C-1", "h-1", customer());

        assertThat(r.httpStatus()).isEqualTo(201);
        sap.verify(postRequestedFor(urlPathEqualTo(PATH))
                .withHeader("Idempotency-Key", equalTo("h-1"))
                .withRequestBody(matchingJsonPath("$.BusinessPartner", equalTo("CUST-001")))
                .withRequestBody(matchingJsonPath("$.Name", equalTo("Acme")))
                .withRequestBody(matchingJsonPath("$.Status", equalTo("ACTIVE"))));
    }

    /**
     * AC-2 (sdd/customer/baja-cliente.md): la baja es un DELETE HTTP sobre la
     * clave de la entidad. Hasta la Fase 1 era un POST {} (auditoria B2).
     */
    @Test
    void realAdapterIssuesHttpDeleteOnEntityKey() {
        sap.stubFor(delete(anyUrl()).willReturn(aResponse().withStatus(204)));

        SapResponse r = new BtpCustomerAdapter(sapClient, PATH).delete("C-1", "h-del");

        assertThat(r.isSuccess()).isTrue();
        sap.verify(deleteRequestedFor(urlPathEqualTo(PATH + "('C-1')"))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
        sap.verify(0, postRequestedFor(anyUrl()));
    }
}

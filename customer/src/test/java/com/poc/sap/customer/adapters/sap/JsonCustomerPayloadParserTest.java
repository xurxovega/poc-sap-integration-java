package com.poc.sap.customer.adapters.sap;

import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.IngestionOrigin;
import com.poc.sap.common.domain.OperationType;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test unit del {@link JsonCustomerPayloadParser} (TECH.md §4).
 * Test directo (sin Spring) — usa ObjectMapper internamente.
 */
class JsonCustomerPayloadParserTest {

    private final JsonCustomerPayloadParser parser = new JsonCustomerPayloadParser();

    @Test
    void parsesFullPayload() {
        String json = """
                {
                  "code": "CUST-001",
                  "name": "Acme",
                  "status": "ACTIVE",
                  "address": { "street":"Calle 1","city":"Madrid","postalCode":"28001","country":"ES","region":"M" },
                  "fiscal": { "taxId":"A12345678","vatNumber":null,"legalName":"Acme","taxResidency":"ES" },
                  "contact": { "email":"info@acme.com","phone":"+34 600000000","fax":null,"website":null },
                  "banking": { "iban":"ES7621000418401234567890","bic":"BBVAESMM","mandateIds":["M-1","M-2"] }
                }
                """;
        IngestionMessage msg = ingestion(json);

        Customer c = parser.parse(msg);

        assertThat(c.id()).isEqualTo("C-1");
        assertThat(c.code()).isEqualTo("CUST-001");
        assertThat(c.name()).isEqualTo("Acme");
        assertThat(c.status()).isEqualTo(Customer.Status.ACTIVE);
        assertThat(c.address().country()).isEqualTo("ES");
        assertThat(c.fiscal().taxId()).isEqualTo("A12345678");
        assertThat(c.contact().email()).isEqualTo("info@acme.com");
        assertThat(c.banking().iban()).isEqualTo("ES7621000418401234567890");
        assertThat(c.banking().mandateIds()).containsExactly("M-1", "M-2");
    }

    @Test
    void defaultsStatusToActiveWhenMissing() {
        String json = """
                {"code":"CUST-001","name":"Acme"}
                """;
        Customer c = parser.parse(ingestion(json));
        assertThat(c.status()).isEqualTo(Customer.Status.ACTIVE);
    }

    @Test
    void defaultsCountryToEsWhenMissing() {
        String json = """
                {"code":"CUST-001","name":"Acme"}
                """;
        Customer c = parser.parse(ingestion(json));
        assertThat(c.address().country()).isEqualTo("ES");
    }

    @Test
    void parsesNullBankingFields() {
        Customer c = parser.parse(ingestion("""
                {"code":"CUST-001","name":"Acme"}
                """));
        assertThat(c.banking().iban()).isNull();
        assertThat(c.banking().mandateIds()).isEmpty();
    }

    @Test
    void invalidJsonThrowsIllegalArgument() {
        assertThatThrownBy(() -> parser.parse(ingestion("not json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Payload de Customer");
    }

    private IngestionMessage ingestion(String payload) {
        return new IngestionMessage(
                "C-1", "customer", OperationType.UPDATE,
                IngestionOrigin.REST, "hash-1", payload);
    }
}
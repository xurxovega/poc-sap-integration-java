package com.poc.sap.customer.adapters.sap;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.customer.application.CustomerFixtures;
import com.poc.sap.customer.domain.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test unit del {@link BtpCustomerAdapter} (TECH.md §8). Mockea
 * {@link SapClient} y verifica el mapeo {@code Customer -> JSON de contrato
 * BTP} y la delegacion al cliente de bajo nivel.
 */
@ExtendWith(MockitoExtension.class)
class BtpCustomerAdapterTest {

    private static final String PATH = "/sap/btp/odata/Customer";

    @Mock SapClient sapClient;
    private BtpCustomerAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BtpCustomerAdapter(sapClient, PATH);
    }

    @Test
    void sendsNormalizedBodyToBtp() {
        Customer c = CustomerFixtures.validCustomer();
        when(sapClient.send(eq(SapDestination.BTP), eq(PATH), eq("C-1"), eq("h"),
                anyString()))
                .thenReturn(new SapResponse(201, "", "loc"));

        SapResponse r = adapter.send("C-1", "h", c);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sapClient).send(eq(SapDestination.BTP), eq(PATH), eq("C-1"), eq("h"),
                body.capture());
        assertThat(r.httpStatus()).isEqualTo(201);
        assertThat(r.location()).isEqualTo("loc");
        assertThat(body.getValue())
                .contains("\"BusinessPartner\":\"CUST-001\"")
                .contains("\"Name\":\"Acme\"")
                .contains("\"Status\":\"ACTIVE\"");
    }

    @Test
    void nullCustomerSendsEmptyObject() {
        when(sapClient.send(any(), any(), any(), any(), anyString()))
                .thenReturn(new SapResponse(201, "", null));

        adapter.send("C-1", "h", null);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sapClient).send(eq(SapDestination.BTP), eq(PATH), eq("C-1"), eq("h"),
                body.capture());
        assertThat(body.getValue()).isEqualTo("{}");
    }
}
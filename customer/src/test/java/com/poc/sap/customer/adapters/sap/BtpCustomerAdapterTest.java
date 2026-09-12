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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;

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

    /**
     * AC-2 (sdd/customer/baja-cliente.md): la baja emite un DELETE HTTP sobre la
     * clave de la entidad. Antes mandaba POST {} (auditoria B2).
     */
    @Test
    void deleteIssuesHttpDeleteOnEntityKey() {
        when(sapClient.delete(SapDestination.BTP, PATH + "('C-1')"))
                .thenReturn(new SapResponse(204, "", null));

        SapResponse r = adapter.delete("C-1", "h");

        assertThat(r.httpStatus()).isEqualTo(204);
        verify(sapClient, never()).send(any(), any(), any(), any(), any());
    }

    /** AC-6: un Customer nulo ya no es una senal de borrado; se rechaza. */
    @Test
    void nullCustomerIsRejected() {
        assertThatThrownBy(() -> adapter.send("C-1", "h", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
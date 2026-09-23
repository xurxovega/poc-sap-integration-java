package com.poc.sap.customer.adapters.sap;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test unit del {@link BtpContactAdapter} (TECH.md §8).
 */
@ExtendWith(MockitoExtension.class)
class BtpContactAdapterTest {

    private static final String PATH = "/sap/btp/odata/CustomerContact";

    @Mock SapClient sapClient;
    private BtpContactAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BtpContactAdapter(sapClient, PATH);
    }

    @Test
    void sendsNormalizedBodyToBtp() {
        ContactData c = new ContactData("info@acme.com", "+34 600000000", "fax", "acme.com");
        when(sapClient.send(eq(SapDestination.BTP), eq(PATH), eq("C-1"), eq("h"),
                anyString()))
                .thenReturn(new SapResponse(201, "", null));

        adapter.send("C-1", "h", c);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sapClient).send(eq(SapDestination.BTP), eq(PATH), eq("C-1"), eq("h"),
                body.capture());
        assertThat(body.getValue())
                .contains("\"BusinessPartner\":\"C-1\"")
                .contains("\"Email\":\"info@acme.com\"")
                .contains("\"Phone\":\"+34 600000000\"")
                .contains("\"Fax\":\"fax\"")
                .contains("\"Website\":\"acme.com\"");
    }

    @Test
    void nullDataSendsEmptyObject() {
        when(sapClient.send(any(), any(), any(), any(), anyString()))
                .thenReturn(new SapResponse(201, "", null));

        adapter.send("C-1", "h", null);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sapClient).send(eq(SapDestination.BTP), eq(PATH), eq("C-1"), eq("h"),
                body.capture());
        assertThat(body.getValue()).isEqualTo("{}");
    }
}
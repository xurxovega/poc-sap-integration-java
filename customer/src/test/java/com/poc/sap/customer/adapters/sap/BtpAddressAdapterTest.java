package com.poc.sap.customer.adapters.sap;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.customer.domain.feature.address.AddressData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test unit del {@link BtpAddressAdapter} (TECH.md §8).
 */
@ExtendWith(MockitoExtension.class)
class BtpAddressAdapterTest {

    private static final String PATH = "/sap/btp/odata/CustomerAddress";

    @Mock SapClient sapClient;
    private BtpAddressAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BtpAddressAdapter(sapClient, PATH);
    }

    @Test
    void sendsNormalizedBodyToBtp() {
        AddressData a = new AddressData("Calle 1", "Madrid", "28001", "ES", "M");
        when(sapClient.send(eq(SapDestination.BTP), eq(PATH), eq("C-1"), eq("h"),
                anyString()))
                .thenReturn(new SapResponse(201, "", null));

        adapter.send("C-1", "h", a);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sapClient).send(eq(SapDestination.BTP), eq(PATH), eq("C-1"), eq("h"),
                body.capture());
        assertThat(body.getValue())
                .contains("\"BusinessPartner\":\"C-1\"")
                .contains("\"Street\":\"Calle 1\"")
                .contains("\"City\":\"Madrid\"")
                .contains("\"PostalCode\":\"28001\"")
                .contains("\"Country\":\"ES\"")
                .contains("\"Region\":\"M\"");
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
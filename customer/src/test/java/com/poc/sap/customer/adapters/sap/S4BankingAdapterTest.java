package com.poc.sap.customer.adapters.sap;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test unit del {@link S4BankingAdapter} (TECH.md §8). A diferencia de los
 * adapters de feature, este destino es S/4 nativo (no BTP).
 */
@ExtendWith(MockitoExtension.class)
class S4BankingAdapterTest {

    private static final String PATH = "/sap/opu/odata/sap/API_CUSTOMER_MANDATE";

    @Mock SapClient sapClient;
    private S4BankingAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new S4BankingAdapter(sapClient, PATH);
    }

    @Test
    void sendsNormalizedBodyToS4() {
        BankingData b = new BankingData("ES7621000418401234567890", "BBVAESMM",
                List.of("M-1", "M-2"));
        when(sapClient.send(eq(SapDestination.S4_NATIVE), eq(PATH), eq("C-1"), eq("h"),
                anyString()))
                .thenReturn(new SapResponse(202, "", null));

        adapter.send("C-1", "h", b);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sapClient).send(eq(SapDestination.S4_NATIVE), eq(PATH), eq("C-1"), eq("h"),
                body.capture());
        assertThat(body.getValue())
                .contains("\"IBAN\":\"ES7621000418401234567890\"")
                .contains("\"BIC\":\"BBVAESMM\"")
                .contains("\"Mandates\":\"M-1,M-2\"");
    }

    @Test
    void emptyMandateListSerializesAsEmpty() {
        BankingData b = new BankingData("ES76...", "BBVAESMM", List.of());
        when(sapClient.send(any(), any(), any(), any(), anyString()))
                .thenReturn(new SapResponse(202, "", null));

        adapter.send("C-1", "h", b);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sapClient).send(eq(SapDestination.S4_NATIVE), eq(PATH), eq("C-1"), eq("h"),
                body.capture());
        assertThat(body.getValue()).contains("\"Mandates\":\"\"");
    }

    @Test
    void nullDataSendsEmptyObject() {
        when(sapClient.send(any(), any(), any(), any(), anyString()))
                .thenReturn(new SapResponse(201, "", null));

        adapter.send("C-1", "h", null);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sapClient).send(eq(SapDestination.S4_NATIVE), eq(PATH), eq("C-1"), eq("h"),
                body.capture());
        assertThat(body.getValue()).isEqualTo("{}");
    }
}
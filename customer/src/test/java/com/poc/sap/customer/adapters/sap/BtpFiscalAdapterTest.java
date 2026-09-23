package com.poc.sap.customer.adapters.sap;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test unit del {@link BtpFiscalAdapter} (TECH.md §8).
 */
@ExtendWith(MockitoExtension.class)
class BtpFiscalAdapterTest {

    private static final String PATH = "/sap/btp/odata/CustomerFiscal";

    @Mock SapClient sapClient;
    private BtpFiscalAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BtpFiscalAdapter(sapClient, PATH);
    }

    @Test
    void sendsNormalizedBodyToBtp() {
        FiscalData f = new FiscalData("A12345678", null, "Acme", "ES");
        when(sapClient.send(eq(SapDestination.BTP), eq(PATH), eq("C-1"), eq("h"),
                anyString()))
                .thenReturn(new SapResponse(201, "", null));

        adapter.send("C-1", "h", f);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sapClient).send(eq(SapDestination.BTP), eq(PATH), eq("C-1"), eq("h"),
                body.capture());
        assertThat(body.getValue())
                .contains("\"BusinessPartner\":\"C-1\"")
                .contains("\"TaxNumber\":\"A12345678\"")
                .contains("\"VATNumber\":\"\"")
                .contains("\"LegalName\":\"Acme\"")
                .contains("\"TaxResidency\":\"ES\"");
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
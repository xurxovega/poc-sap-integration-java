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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec sdd/customer/sincronizacion-datos-bancarios.md §5 (ruta BTP), AC-1.
 * Antes S4BankingAdapterTest: el adaptador apuntaba a una API S/4 inexistente.
 */
@ExtendWith(MockitoExtension.class)
class BtpBankingAdapterTest {

    private static final String PATH = "/sap/btp/odata/CustomerBanking";

    @Mock SapClient sapClient;
    private BtpBankingAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BtpBankingAdapter(sapClient, PATH);
    }

    private String sentBody() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sapClient).send(eq(SapDestination.BTP), eq(PATH), eq("C-1"), eq("h"), body.capture());
        return body.getValue();
    }

    @Test
    void sendsBankingContractToBtpWithMandatesAsList() {
        BankingData b = new BankingData("ES7621000418401234567890", "BBVAESMM", List.of("M-1", "M-2"));
        when(sapClient.send(eq(SapDestination.BTP), eq(PATH), eq("C-1"), eq("h"), anyString()))
                .thenReturn(new SapResponse(202, "", null));

        adapter.send("C-1", "h", b);

        assertThat(sentBody())
                .contains("\"BusinessPartner\":\"C-1\"")
                .contains("\"IBAN\":\"ES7621000418401234567890\"")
                .contains("\"BIC\":\"BBVAESMM\"")
                .contains("\"Mandates\":[\"M-1\",\"M-2\"]");
    }

    @Test
    void emptyMandateListSerializesAsEmptyArray() {
        when(sapClient.send(any(), any(), any(), any(), anyString()))
                .thenReturn(new SapResponse(202, "", null));

        adapter.send("C-1", "h", new BankingData("ES76...", "BBVAESMM", List.of()));

        assertThat(sentBody()).contains("\"Mandates\":[]");
    }

    @Test
    void nullDataSendsEmptyObject() {
        when(sapClient.send(any(), any(), any(), any(), anyString()))
                .thenReturn(new SapResponse(201, "", null));

        adapter.send("C-1", "h", null);

        assertThat(sentBody()).isEqualTo("{}");
    }
}

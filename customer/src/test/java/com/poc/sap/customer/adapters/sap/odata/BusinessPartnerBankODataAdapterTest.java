package com.poc.sap.customer.adapters.sap.odata;

import com.fasterxml.jackson.databind.JsonNode;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.json.SapJsonMapper;
import com.poc.sap.customer.domain.feature.banking.BankingData;
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

/** Spec sdd/customer/sincronizacion-datos-bancarios.md §5 (ruta OData), AC-2 y AC-3. */
@ExtendWith(MockitoExtension.class)
class BusinessPartnerBankODataAdapterTest {

    private static final String PATH = "/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartnerBank";

    @Mock SapClient sapClient;

    private JsonNode sent(BankingData b) throws Exception {
        when(sapClient.send(eq(SapDestination.S4_NATIVE), eq(PATH), eq("C-1"), eq("h"), anyString()))
                .thenReturn(new SapResponse(201, "", null));
        new BusinessPartnerBankODataAdapter(sapClient, PATH).send("C-1", "h", b);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sapClient).send(any(), any(), any(), any(), body.capture());
        return SapJsonMapper.mapper().readTree(body.getValue());
    }

    /** AC-2: el BIC no viaja en A_BusinessPartnerBank (pertenece al mandato / maestro de bancos). */
    @Test
    void bankIdentificationIsAnOrdinalAndBicIsNotSent() throws Exception {
        JsonNode json = sent(new BankingData("ES76 2100 0418 4012 3456 7890", "BBVAESMM", List.of()));

        assertThat(json.path("BusinessPartner").asText()).isEqualTo("C-1");
        assertThat(json.path("BankIdentification").asText()).isEqualTo("0001");
        assertThat(json.path("IBAN").asText()).isEqualTo("ES76 2100 0418 4012 3456 7890");
        assertThat(json.toString()).doesNotContain("BBVAESMM");
    }

    /** AC-3: el pais del banco se deriva del IBAN. */
    @Test
    void bankCountryKeyComesFromTheIban() throws Exception {
        JsonNode json = sent(new BankingData("de89370400440532013000", null, List.of()));

        assertThat(json.path("BankCountryKey").asText()).isEqualTo("DE");
        assertThat(BusinessPartnerBankODataAdapter.countryOf(null)).isEmpty();
        assertThat(BusinessPartnerBankODataAdapter.countryOf("E")).isEmpty();
    }
}

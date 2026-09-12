package com.poc.sap.customer.adapters.sap.odata;

import com.fasterxml.jackson.databind.JsonNode;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.json.SapJsonMapper;
import com.poc.sap.customer.domain.Mandate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Spec sdd/customer/sincronizacion-datos-bancarios.md §5 (mandato SEPA, AC-4/AC-5)
 * y sdd/customer/baja-mandato-sepa.md (AC-3).
 */
@ExtendWith(MockitoExtension.class)
class SepaMandateODataAdapterTest {

    private static final String PATH = "/sap/opu/odata/sap/API_APAR_SEPA_MANDATE_SRV/SEPAMandateSet";

    @Mock SapClient sapClient;

    private SepaMandateODataAdapter adapter(String creditor) {
        return new SepaMandateODataAdapter(sapClient, PATH, creditor, "F");
    }

    private static Mandate mandate(Mandate.Status status) {
        return new Mandate("M-1", "C-1", "ES7621000418401234567890", "BBVAESMM", "2026-01-15", status);
    }

    /** AC-4: alta del mandato sobre API_APAR_SEPA_MANDATE_SRV con clave (Creditor, SEPAMandate) y BIC en SenderBankSWIFTCode. */
    @Test
    void createPostsTheRealSepaMandateContract() throws Exception {
        when(sapClient.send(eq(SapDestination.S4_NATIVE), eq(PATH), eq("M-1"), eq("h"), anyString()))
                .thenReturn(new SapResponse(201, "", null));

        adapter("ES98ZZZ12345678901").send("M-1", "h", mandate(Mandate.Status.ACTIVE));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sapClient).send(any(), any(), any(), any(), body.capture());
        JsonNode json = SapJsonMapper.mapper().readTree(body.getValue());
        assertThat(json.path("Creditor").asText()).isEqualTo("ES98ZZZ12345678901");
        assertThat(json.path("SEPAMandate").asText()).isEqualTo("M-1");
        assertThat(json.path("SEPAMandateApplication").asText()).isEqualTo("F");
        assertThat(json.path("Sender").asText()).isEqualTo("C-1");
        assertThat(json.path("SenderIBAN").asText()).isEqualTo("ES7621000418401234567890");
        assertThat(json.path("SenderBankSWIFTCode").asText()).isEqualTo("BBVAESMM");
        assertThat(json.path("SEPASignatureDate").asText()).isEqualTo("2026-01-15");
        assertThat(json.path("SEPAMandateStatus").asText()).isEqualTo("1");
    }

    /** baja-mandato-sepa AC-3: revocar es un PATCH de estado sobre la clave, no un DELETE ni un POST. */
    @Test
    void revokePatchesStatusCancelledOnTheMandateKey() {
        when(sapClient.patch(eq(SapDestination.S4_NATIVE),
                eq(PATH + "(Creditor='ES98ZZZ12345678901',SEPAMandate='M-1')"), eq("M-1"), eq("h"), anyString()))
                .thenReturn(new SapResponse(204, "", null));

        SapResponse r = adapter("ES98ZZZ12345678901").revoke("M-1", "h");

        assertThat(r.isSuccess()).isTrue();
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sapClient).patch(any(), any(), any(), any(), body.capture());
        assertThat(body.getValue()).isEqualTo("{\"SEPAMandateStatus\":\"3\"}");
    }

    /** AC-5: sin identificador de acreedor no se llama a SAP; el fallo es de configuracion y se dice. */
    @Test
    void missingCreditorFailsFastWithoutCallingSap() {
        assertThatThrownBy(() -> adapter("").send("M-1", "h", mandate(Mandate.Status.ACTIVE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sap.sepa.creditor-id");
        assertThatThrownBy(() -> adapter(" ").revoke("M-1", "h"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(sapClient);
    }

    @Test
    void statusMappingFollowsS4Codes() {
        assertThat(SepaMandateODataAdapter.statusOf(Mandate.Status.ACTIVE)).isEqualTo("1");
        assertThat(SepaMandateODataAdapter.statusOf(Mandate.Status.REVOKED)).isEqualTo("3");
        assertThat(SepaMandateODataAdapter.statusOf(Mandate.Status.EXPIRED)).isEqualTo("4");
        assertThat(SepaMandateODataAdapter.statusOf(null)).isEqualTo("1");
    }
}

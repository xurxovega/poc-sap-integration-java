package com.poc.sap.customer.adapters.sap.odata;

import com.poc.sap.common.domain.port.SapOutboundPort.SapLookup;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.SapUpsertSettings;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec docs/sdd/common/upsert-idempotente-sap.md AC-1/AC-2 y
 * docs/sdd/common/upsert-idempotente-sap.md §5.1: la clave es compuesta y
 * determinista {@code (BusinessPartner, BPTaxType)}, asi que no se persiste nada.
 */
@ExtendWith(MockitoExtension.class)
class BusinessPartnerTaxODataAdapterTest {

    private static final String PATH = "/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartnerTaxNumber";
    private static final String KEY_PATH = PATH + "(BusinessPartner='C-1',BPTaxType='ES0')";

    @Mock SapClient sapClient;

    private BusinessPartnerTaxODataAdapter adapter() {
        return new BusinessPartnerTaxODataAdapter(sapClient, PATH, "ES0", SapUpsertSettings.defaults());
    }

    /** AC-2: el lookup va a la clave compuesta, sin filtros ni navegacion. */
    @Test
    void lookupUsesTheCompositeKey() {
        when(sapClient.get(SapDestination.S4_NATIVE, KEY_PATH))
                .thenReturn(new SapResponse(200, "{\"d\":{\"BPTaxType\":\"ES0\"}}", null, "W/\"t1\""));

        SapLookup found = adapter().lookup("C-1", new FiscalData("B12345678", null, null, null));

        assertThat(found.outcome()).isEqualTo(SapLookup.Outcome.FOUND);
        assertThat(found.key()).isEqualTo("ES0");
        assertThat(found.etag()).isEqualTo("W/\"t1\"");
    }

    /** AC-2: el PATCH lleva If-Match y solo el numero fiscal; el tipo es parte de la clave. */
    @Test
    void updateSendsOnlyTheTaxNumberWithIfMatch() {
        when(sapClient.patch(eq(SapDestination.S4_NATIVE), eq(KEY_PATH), eq("C-1"), eq("h"), anyString(),
                eq("W/\"t1\""))).thenReturn(new SapResponse(204, "", null));

        SapResponse r = adapter().update("C-1", "h", new FiscalData("B12345678", null, null, null),
                SapLookup.found("ES0", "W/\"t1\""));

        assertThat(r.httpStatus()).isEqualTo(204);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sapClient).patch(any(), anyString(), anyString(), anyString(), body.capture(), anyString());
        assertThat(body.getValue()).contains("B12345678").doesNotContain("BPTaxType");
    }
}

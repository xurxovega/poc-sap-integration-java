package com.poc.sap.customer.adapters.sap.odata;

import com.poc.sap.common.domain.port.SapOutboundPort.SapLookup;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.SapUpsertSettings;
import com.poc.sap.customer.domain.Customer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec docs/sdd/common/upsert-idempotente-sap.md AC-1/AC-2/AC-3 y
 * docs/sdd/customer/sincronizacion-cliente.md §6: el agregado se verifica antes
 * de escribir y se actualiza en vez de darse de alta dos veces.
 */
@ExtendWith(MockitoExtension.class)
class BusinessPartnerODataAdapterTest {

    private static final String PATH = "/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner";
    private static final String KEY_PATH = PATH + "('C-1')";

    @Mock SapClient sapClient;

    private BusinessPartnerODataAdapter adapter() {
        return new BusinessPartnerODataAdapter(sapClient, PATH, SapUpsertSettings.defaults());
    }

    private static Customer customer() {
        return new Customer("C-1", "C-1", "Cliente de prueba", Customer.Status.ACTIVE,
                null, null, null, null);
    }

    /** AC-2: SAP ya lo tiene, con su ETag: la clave del lookup es nuestro propio entityId (BPEE). */
    @Test
    void lookupReturnsTheBusinessPartnerAndItsEtag() {
        when(sapClient.get(SapDestination.S4_NATIVE, KEY_PATH))
                .thenReturn(new SapResponse(200, "{\"d\":{\"BusinessPartner\":\"C-1\"}}", null, "W/\"v1\""));

        SapLookup found = adapter().lookup("C-1", customer());

        assertThat(found.outcome()).isEqualTo(SapLookup.Outcome.FOUND);
        assertThat(found.key()).isEqualTo("C-1");
        assertThat(found.etag()).isEqualTo("W/\"v1\"");
    }

    /** AC-1: un 404 es «SAP no lo tiene»: toca alta. */
    @Test
    void missingBusinessPartnerIsNotFound() {
        when(sapClient.get(SapDestination.S4_NATIVE, KEY_PATH)).thenReturn(new SapResponse(404, "", null));

        assertThat(adapter().lookup("C-1", customer()).outcome()).isEqualTo(SapLookup.Outcome.NOT_FOUND);
    }

    /**
     * AC-3: un fallo de transporte NO es «no lo tiene». Confundirlos degradaria a
     * alta directa y crearia el duplicado que este bloque existe para evitar.
     */
    @Test
    void transportFailureIsUnavailableNotNotFound() {
        when(sapClient.get(SapDestination.S4_NATIVE, KEY_PATH)).thenReturn(new SapResponse(0, "timeout", null));

        SapLookup found = adapter().lookup("C-1", customer());

        assertThat(found.outcome()).isEqualTo(SapLookup.Outcome.UNAVAILABLE);
        assertThat(found.detail()).contains("transporte");
    }

    /** AC-2: la actualizacion es un PATCH sobre la clave con If-Match, nunca un POST. */
    @Test
    void existingBusinessPartnerIsUpdatedNotCreated() {
        when(sapClient.patch(eq(SapDestination.S4_NATIVE), eq(KEY_PATH), eq("C-1"), eq("h"), anyString(), eq("W/\"v1\"")))
                .thenReturn(new SapResponse(204, "", null));

        SapResponse r = adapter().update("C-1", "h", customer(), SapLookup.found("C-1", "W/\"v1\""));

        assertThat(r.httpStatus()).isEqualTo(204);
        verify(sapClient, never()).send(any(), anyString(), anyString(), anyString(), anyString());
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sapClient).patch(any(), anyString(), anyString(), anyString(), body.capture(), anyString());
        // El PATCH no reenvia la clave ni el grouping: son inmutables y S/4 rechaza el cambio.
        assertThat(body.getValue()).contains("OrganizationBPName1")
                .doesNotContain("BusinessPartnerGrouping");
    }

    /** AC-5: con la verificacion previa apagada el adaptador se comporta como antes: alta directa. */
    @Test
    void lookupDisabledFallsBackToTheOldBehaviour() {
        var adapter = new BusinessPartnerODataAdapter(sapClient, PATH,
                new SapUpsertSettings(false, true, true));

        assertThat(adapter.lookup("C-1", customer()).outcome()).isEqualTo(SapLookup.Outcome.NOT_SUPPORTED);
        verify(sapClient, never()).get(any(), anyString());
    }
}

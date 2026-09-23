package com.poc.sap.customer.adapters.sap.odata;

import com.poc.sap.common.domain.port.SapKeyStorePort;
import com.poc.sap.common.domain.port.SapOutboundPort.SapLookup;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.SapUpsertSettings;
import com.poc.sap.customer.domain.feature.address.AddressData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec docs/sdd/common/upsert-idempotente-sap.md AC-1..AC-4 y AC-6, y
 * docs/sdd/customer/sincronizacion-direccion.md §6: el {@code AddressID} lo
 * asigna SAP, no es deducible, y hay que guardarlo o cada ciclo crea una
 * direccion nueva (2B-6).
 */
@ExtendWith(MockitoExtension.class)
class BusinessPartnerAddressODataAdapterTest {

    private static final String PATH = "/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartnerAddress";
    private static final String BP_PATH = "/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner";
    private static final String KEY_PATH = PATH + "(BusinessPartner='C-1',AddressID='0000123456')";
    private static final String NAV_PATH = BP_PATH + "('C-1')/to_BusinessPartnerAddress?$top=2";

    @Mock SapClient sapClient;
    @Mock SapKeyStorePort keyStore;

    private BusinessPartnerAddressODataAdapter adapter() {
        return new BusinessPartnerAddressODataAdapter(sapClient, PATH, BP_PATH, keyStore,
                SapUpsertSettings.defaults());
    }

    private static AddressData address() {
        return new AddressData("Calle Mayor 1", "Madrid", "28013", "ES", "M");
    }

    /** AC-2/AC-6: con la clave guardada, el lookup va directo a ella y devuelve su ETag. */
    @Test
    void lookupReturnsTheAddressIdAndEtag() {
        when(keyStore.find("customer", "C-1", "ADDRESS")).thenReturn(Optional.of("0000123456"));
        when(sapClient.get(SapDestination.S4_NATIVE, KEY_PATH))
                .thenReturn(new SapResponse(200, "{\"d\":{\"AddressID\":\"0000123456\"}}", null, "W/\"a1\""));

        SapLookup found = adapter().lookup("C-1", address());

        assertThat(found.outcome()).isEqualTo(SapLookup.Outcome.FOUND);
        assertThat(found.key()).isEqualTo("0000123456");
        assertThat(found.etag()).isEqualTo("W/\"a1\"");
    }

    /** AC-1: sin clave guardada y sin direcciones en SAP, es un alta. */
    @Test
    void missingAddressIsNotFound() {
        when(keyStore.find("customer", "C-1", "ADDRESS")).thenReturn(Optional.empty());
        when(sapClient.get(SapDestination.S4_NATIVE, NAV_PATH))
                .thenReturn(new SapResponse(200, "{\"d\":{\"results\":[]}}", null));

        assertThat(adapter().lookup("C-1", address()).outcome()).isEqualTo(SapLookup.Outcome.NOT_FOUND);
    }

    /** AC-3: transporte caido no es «no existe»: no se escribe nada. */
    @Test
    void transportFailureIsUnavailableNotNotFound() {
        when(keyStore.find("customer", "C-1", "ADDRESS")).thenReturn(Optional.empty());
        when(sapClient.get(SapDestination.S4_NATIVE, NAV_PATH)).thenReturn(new SapResponse(0, "timeout", null));

        assertThat(adapter().lookup("C-1", address()).outcome()).isEqualTo(SapLookup.Outcome.UNAVAILABLE);
    }

    /** AC-4: dos direcciones en SAP es un resultado no concluyente: no se coge la primera. */
    @Test
    void ambiguousLookupNeverGuesses() {
        when(keyStore.find("customer", "C-1", "ADDRESS")).thenReturn(Optional.empty());
        when(sapClient.get(SapDestination.S4_NATIVE, NAV_PATH)).thenReturn(new SapResponse(200,
                "{\"d\":{\"results\":[{\"AddressID\":\"1\"},{\"AddressID\":\"2\"}]}}", null));

        SapLookup found = adapter().lookup("C-1", address());

        assertThat(found.outcome()).isEqualTo(SapLookup.Outcome.UNAVAILABLE);
        assertThat(found.detail()).contains("ambiguo");
    }

    /** AC-6: el AddressID resuelto por navegacion se guarda para no volver a resolverlo. */
    @Test
    void resolvesAndStoresTheAddressIdOnFirstUpdate() {
        when(keyStore.find("customer", "C-1", "ADDRESS")).thenReturn(Optional.empty());
        when(sapClient.get(SapDestination.S4_NATIVE, NAV_PATH)).thenReturn(new SapResponse(200,
                "{\"d\":{\"results\":[{\"AddressID\":\"0000123456\",\"__metadata\":{\"etag\":\"W/\\\"a9\\\"\"}}]}}",
                null));

        SapLookup found = adapter().lookup("C-1", address());

        assertThat(found.key()).isEqualTo("0000123456");
        assertThat(found.etag()).isEqualTo("W/\"a9\"");
        verify(keyStore).save("customer", "C-1", "ADDRESS", "0000123456");
    }

    /** AC-6: con la clave ya guardada no se navega desde el BP: una llamada menos. */
    @Test
    void reusesTheStoredAddressId() {
        when(keyStore.find("customer", "C-1", "ADDRESS")).thenReturn(Optional.of("0000123456"));
        when(sapClient.get(SapDestination.S4_NATIVE, KEY_PATH)).thenReturn(new SapResponse(200, "{}", null));

        adapter().lookup("C-1", address());

        verify(sapClient, never()).get(SapDestination.S4_NATIVE, NAV_PATH);
    }

    /** AC-2: la actualizacion es PATCH sobre (BusinessPartner, AddressID) con If-Match. */
    @Test
    void updateUsesPatchWithIfMatch() {
        when(sapClient.patch(eq(SapDestination.S4_NATIVE), eq(KEY_PATH), eq("C-1"), eq("h"), anyString(),
                eq("W/\"a1\""))).thenReturn(new SapResponse(204, "", null));

        SapResponse r = adapter().update("C-1", "h", address(), SapLookup.found("0000123456", "W/\"a1\""));

        assertThat(r.httpStatus()).isEqualTo(204);
        verify(sapClient, never()).send(any(), anyString(), anyString(), anyString(), anyString());
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sapClient).patch(any(), anyString(), anyString(), anyString(), body.capture(), anyString());
        assertThat(body.getValue()).contains("Madrid").doesNotContain("\"BusinessPartner\"");
        verify(keyStore).save("customer", "C-1", "ADDRESS", "0000123456");
    }
}

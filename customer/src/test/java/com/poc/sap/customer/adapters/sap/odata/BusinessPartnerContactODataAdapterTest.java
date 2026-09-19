package com.poc.sap.customer.adapters.sap.odata;

import com.poc.sap.common.domain.port.SapKeyStorePort;
import com.poc.sap.common.domain.port.SapOutboundPort.SapLookup;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.SapUpsertSettings;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec docs/sdd/customer/sincronizacion-contacto.md AC-1..AC-4 y
 * docs/sdd/common/upsert-idempotente-sap.md AC-2/AC-3 (hallazgo 2B-5).
 *
 * <p>El payload que se enviaba a SAP no llevaba <b>ni un solo dato</b> de
 * {@link ContactData}: solo {@code BusinessPartnerCompany} y
 * {@code RelationshipCategory}. Y no podia llevarlos:
 * {@code A_BusinessPartnerContact} modela una <b>persona</b> de contacto y exige
 * {@code BusinessPartnerPerson}, que no tenemos. Email, telefono, fax y web de un
 * cliente son datos de comunicacion <b>de su direccion</b>, y viven en
 * {@code A_AddressEmailAddress} / {@code A_AddressPhoneNumber} /
 * {@code A_AddressFaxNumber} / {@code A_AddressHomePageURL}, cuya clave empieza
 * por el {@code AddressID} que asigna SAP.
 */
@ExtendWith(MockitoExtension.class)
class BusinessPartnerContactODataAdapterTest {

    private static final String BASE = "/sap/opu/odata/sap/API_BUSINESS_PARTNER";
    private static final String BP_PATH = BASE + "/A_BusinessPartner";
    private static final String NAV_PATH = BP_PATH + "('C-1')/to_BusinessPartnerAddress?$top=2";
    private static final String EMAIL_KEY =
            BASE + "/A_AddressEmailAddress(AddressID='0000123456',Person='',OrdinalNumber='0')";
    private static final String PHONE_KEY =
            BASE + "/A_AddressPhoneNumber(AddressID='0000123456',Person='',OrdinalNumber='0')";

    @Mock SapClient sapClient;
    @Mock SapKeyStorePort keyStore;

    private BusinessPartnerContactODataAdapter adapter() {
        return new BusinessPartnerContactODataAdapter(sapClient, BASE, BP_PATH, keyStore,
                SapUpsertSettings.defaults());
    }

    private static ContactData contact() {
        return new ContactData("cliente@example.com", "+34910000000", null, null);
    }

    /**
     * AC-1 (2B-5): el payload lleva los datos de {@link ContactData}. Antes salia
     * un cuerpo sin email, telefono, fax ni web y SAP recibia un contacto vacio.
     */
    @Test
    void payloadCarriesTheContactData() {
        when(keyStore.find("customer", "C-1", "ADDRESS")).thenReturn(Optional.of("0000123456"));
        when(sapClient.get(eq(SapDestination.S4_NATIVE), anyString())).thenReturn(new SapResponse(404, "", null));
        when(sapClient.send(eq(SapDestination.S4_NATIVE), anyString(), eq("C-1"), eq("h"), anyString()))
                .thenReturn(new SapResponse(201, "", null));

        SapResponse r = adapter().send("C-1", "h", contact());

        assertThat(r.isSuccess()).isTrue();
        ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(sapClient, org.mockito.Mockito.times(2))
                .send(any(), paths.capture(), anyString(), anyString(), bodies.capture());

        List<String> sent = bodies.getAllValues();
        assertThat(paths.getAllValues())
                .containsExactly(BASE + "/A_AddressEmailAddress", BASE + "/A_AddressPhoneNumber");
        assertThat(sent.get(0)).contains("cliente@example.com").contains("0000123456");
        assertThat(sent.get(1)).contains("+34910000000").contains("0000123456");
    }

    /** AC-2: los campos sin valor no generan llamada: no se escriben cadenas vacias en SAP. */
    @Test
    void emptyFieldsAreNotSent() {
        when(keyStore.find("customer", "C-1", "ADDRESS")).thenReturn(Optional.of("0000123456"));

        SapResponse r = adapter().send("C-1", "h", new ContactData(null, "  ", null, null));

        assertThat(r.isSuccess()).isTrue();
        verify(sapClient, never()).send(any(), anyString(), anyString(), anyString(), anyString());
    }

    /**
     * AC-3: la clave de esta feature es el {@code AddressID} de la direccion del BP,
     * que se reutiliza del almacen de claves (lo guardo la feature ADDRESS).
     */
    @Test
    void lookupResolvesTheAddressIdOfTheBusinessPartner() {
        when(keyStore.find("customer", "C-1", "ADDRESS")).thenReturn(Optional.of("0000123456"));

        SapLookup found = adapter().lookup("C-1", contact());

        assertThat(found.outcome()).isEqualTo(SapLookup.Outcome.FOUND);
        assertThat(found.key()).isEqualTo("0000123456");
        verify(sapClient, never()).get(any(), anyString());
    }

    /**
     * AC-4: sin direccion en SAP no hay {@code AddressID} y los datos de
     * comunicacion no se pueden colgar de ningun sitio. No es un alta: es que
     * todavia no toca, y se reintenta con el ciclo siguiente.
     */
    @Test
    void withoutAnAddressInSapNothingIsWritten() {
        when(keyStore.find("customer", "C-1", "ADDRESS")).thenReturn(Optional.empty());
        when(sapClient.get(SapDestination.S4_NATIVE, NAV_PATH))
                .thenReturn(new SapResponse(200, "{\"d\":{\"results\":[]}}", null));

        SapLookup found = adapter().lookup("C-1", contact());

        assertThat(found.outcome()).isEqualTo(SapLookup.Outcome.UNAVAILABLE);
        assertThat(found.detail()).contains("AddressID");
    }

    /**
     * AC-2 del upsert: lo que ya existe se actualiza con {@code If-Match} y lo que
     * falta se da de alta, campo a campo.
     */
    @Test
    void existingCommunicationIsPatchedAndMissingOnesArePosted() {
        when(sapClient.get(SapDestination.S4_NATIVE, EMAIL_KEY))
                .thenReturn(new SapResponse(200, "{\"d\":{}}", null, "W/\"e1\""));
        when(sapClient.get(SapDestination.S4_NATIVE, PHONE_KEY))
                .thenReturn(new SapResponse(404, "", null));
        when(sapClient.patch(eq(SapDestination.S4_NATIVE), eq(EMAIL_KEY), eq("C-1"), eq("h"), anyString(),
                eq("W/\"e1\""))).thenReturn(new SapResponse(204, "", null));
        when(sapClient.send(eq(SapDestination.S4_NATIVE), eq(BASE + "/A_AddressPhoneNumber"), eq("C-1"), eq("h"),
                anyString())).thenReturn(new SapResponse(201, "", null));

        SapResponse r = adapter().update("C-1", "h", contact(), SapLookup.found("0000123456", null));

        assertThat(r.isSuccess()).isTrue();
        verify(sapClient).patch(any(), eq(EMAIL_KEY), anyString(), anyString(), anyString(), eq("W/\"e1\""));
        verify(sapClient).send(any(), eq(BASE + "/A_AddressPhoneNumber"), anyString(), anyString(), anyString());
    }

    /** AC-4: si el GET de un campo no responde, se para: no se escribe a ciegas. */
    @Test
    void aFailedFieldLookupStopsTheWrite() {
        when(sapClient.get(SapDestination.S4_NATIVE, EMAIL_KEY)).thenReturn(new SapResponse(0, "timeout", null));

        SapResponse r = adapter().update("C-1", "h", contact(), SapLookup.found("0000123456", null));

        assertThat(r.httpStatus()).isZero();
        verify(sapClient, never()).send(any(), anyString(), anyString(), anyString(), anyString());
        verify(sapClient, never()).patch(any(), anyString(), anyString(), anyString(), anyString(), anyString());
    }
}

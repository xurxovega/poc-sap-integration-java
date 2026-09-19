package com.poc.sap.customer.adapters.sap;

import com.poc.sap.common.domain.port.SapOutboundPort.SapLookup;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Spec docs/sdd/common/upsert-idempotente-sap.md AC-3 (auditoria B13): el
 * adaptador de lectura se tragaba TODAS las excepciones devolviendo
 * {@code Optional.empty()}, que confunde «SAP no lo tiene» con «SAP no
 * responde» — justo la distincion de la que depende no duplicar datos.
 */
@ExtendWith(MockitoExtension.class)
class BusinessPartnerReadAdapterTest {

    private static final String PATH = "/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner";

    @Mock SapClient sapClient;

    private BusinessPartnerReadAdapter adapter() {
        return new BusinessPartnerReadAdapter(sapClient, PATH);
    }

    /** AC-2: el lookup del agregado devuelve la clave y el ETag para el If-Match. */
    @Test
    void lookupByIdReturnsTheKeyAndTheEtag() {
        when(sapClient.get(SapDestination.S4_NATIVE, PATH + "('C-1')")).thenReturn(
                new SapResponse(200, "{\"d\":{\"BusinessPartner\":\"C-1\"}}", null, "W/\"b1\""));

        SapLookup found = adapter().lookupById("C-1");

        assertThat(found.outcome()).isEqualTo(SapLookup.Outcome.FOUND);
        assertThat(found.key()).isEqualTo("C-1");
        assertThat(found.etag()).isEqualTo("W/\"b1\"");
    }

    /** AC-1: un 404 es «no lo tiene». */
    @Test
    void missingBusinessPartnerIsNotFound() {
        when(sapClient.get(SapDestination.S4_NATIVE, PATH + "('C-9')"))
                .thenReturn(new SapResponse(404, "", null));

        assertThat(adapter().lookupById("C-9").outcome()).isEqualTo(SapLookup.Outcome.NOT_FOUND);
        assertThat(adapter().findById("C-9")).isEmpty();
    }

    /** AC-3: un fallo de transporte NO se confunde con «no existe». */
    @Test
    void transportFailureIsNotConfusedWithNotFound() {
        when(sapClient.get(SapDestination.S4_NATIVE, PATH + "('C-1')"))
                .thenReturn(new SapResponse(0, "connection reset", null));

        SapLookup found = adapter().lookupById("C-1");

        assertThat(found.outcome()).isEqualTo(SapLookup.Outcome.UNAVAILABLE);
        assertThat(found.detail()).contains("transporte");
    }

    /** AC-3: un 5xx agotado tampoco es «no existe». */
    @Test
    void serverErrorIsUnavailable() {
        when(sapClient.get(SapDestination.S4_NATIVE, PATH + "('C-1')"))
                .thenReturn(new SapResponse(503, "unavailable", null));

        assertThat(adapter().lookupById("C-1").outcome()).isEqualTo(SapLookup.Outcome.UNAVAILABLE);
    }

    /** La comilla simple de un literal OData se dobla antes de concatenarla en la URL. */
    @Test
    void odataLiteralsAreEscaped() {
        when(sapClient.get(SapDestination.S4_NATIVE, PATH + "('O''Neill')"))
                .thenReturn(new SapResponse(404, "", null));

        assertThat(adapter().lookupById("O'Neill").outcome()).isEqualTo(SapLookup.Outcome.NOT_FOUND);
    }
}

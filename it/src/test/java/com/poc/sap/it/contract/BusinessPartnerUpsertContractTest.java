package com.poc.sap.it.contract;

import com.poc.sap.common.domain.port.SapOutboundPort.SapLookup;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapUpsertSettings;
import com.poc.sap.customer.adapters.sap.odata.BusinessPartnerODataAdapter;
import com.poc.sap.customer.domain.Customer;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato S/4 del upsert del Business Partner, ejercitando el adaptador REAL
 * sobre el {@link com.poc.sap.common.sap.RestClientSapClient} real
 * (spec docs/sdd/common/upsert-idempotente-sap.md AC-8).
 *
 * <p>Si el adaptador cambia el path, el metodo, la cabecera {@code If-Match} o el
 * cuerpo del PATCH, aqui se rompe.
 */
class BusinessPartnerUpsertContractTest extends AbstractSapContractTest {

    private static final String PATH = "/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner";
    private static final String KEY = PATH + "('C-1')";

    private BusinessPartnerODataAdapter adapter() {
        return new BusinessPartnerODataAdapter(sapClient, PATH, SapUpsertSettings.defaults());
    }

    private static Customer customer() {
        return new Customer("C-1", "C-1", "Cliente de prueba", Customer.Status.ACTIVE,
                null, null, null, null);
    }

    /** AC-8: el lookup es un GET por clave y devuelve el ETag que SAP pone en la cabecera. */
    @Test
    void realAdapterLooksUpTheBusinessPartnerByKeyAndReadsTheEtag() {
        sap.stubFor(get(urlEqualTo(KEY)).willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "W/\"datetime'2026-09-18T10%3A00%3A00'\"")
                .withBody("{\"d\":{\"BusinessPartner\":\"C-1\"}}")));

        SapLookup found = adapter().lookup("C-1", customer());

        assertThat(found.outcome()).isEqualTo(SapLookup.Outcome.FOUND);
        assertThat(found.etag()).startsWith("W/\"datetime'2026-09-18");
        sap.verify(getRequestedFor(urlEqualTo(KEY))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
    }

    /** AC-8: existiendo el BP, se actualiza con PATCH + If-Match; NUNCA se hace un POST. */
    @Test
    void realAdapterPatchesWithIfMatchInsteadOfCreatingADuplicate() {
        sap.stubFor(patch(urlEqualTo(KEY)).willReturn(aResponse().withStatus(204)));
        sap.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(201)));

        SapResponse r = adapter().update("C-1", "h-1", customer(), SapLookup.found("C-1", "W/\"v1\""));

        assertThat(r.httpStatus()).isEqualTo(204);
        sap.verify(patchRequestedFor(urlEqualTo(KEY))
                .withHeader("If-Match", equalTo("W/\"v1\""))
                .withHeader("Idempotency-Key", equalTo("h-1"))
                .withRequestBody(matchingJsonPath("$.OrganizationBPName1", equalTo("Cliente de prueba"))));
        sap.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    /** AC-8: un 404 del lookup es «no lo tiene» y lleva al alta por POST. */
    @Test
    void realAdapterCreatesWhenSapDoesNotHaveIt() {
        sap.stubFor(get(urlEqualTo(KEY)).willReturn(aResponse().withStatus(404)));
        sap.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(201).withBody("{}")));

        assertThat(adapter().lookup("C-1", customer()).outcome()).isEqualTo(SapLookup.Outcome.NOT_FOUND);
        assertThat(adapter().send("C-1", "h-1", customer()).httpStatus()).isEqualTo(201);

        sap.verify(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.BusinessPartnerGrouping", equalTo("BPEE"))));
    }

    /** AC-3/AC-8: un 503 agotado NO es «no lo tiene»; el pipeline no debe escribir. */
    @Test
    void realAdapterReportsUnavailableWhenSapIsDown() {
        sap.stubFor(get(urlEqualTo(KEY)).willReturn(aResponse().withStatus(503)));

        assertThat(adapter().lookup("C-1", customer()).outcome()).isEqualTo(SapLookup.Outcome.UNAVAILABLE);
    }
}

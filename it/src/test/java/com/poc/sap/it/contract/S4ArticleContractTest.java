package com.poc.sap.it.contract;

import com.poc.sap.article.adapters.sap.S4ArticleAdapter;
import com.poc.sap.article.domain.Article;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** Contrato S/4 nativo del articulo, ejercitando el adaptador REAL (auditoria B6). */
class S4ArticleContractTest extends AbstractSapContractTest {

    private static final String PATH = "/sap/opu/odata/sap/API_PRODUCT";

    @Test
    void realAdapterPostsMappedProduct() {
        sap.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(201).withBody("{}")));

        SapResponse r = new S4ArticleAdapter(sapClient, PATH).send("A-1", "h-1",
                new Article("A-1", "SKU-001", "Tornillo M6", "Hardware", "UN", Article.Status.ACTIVE));

        assertThat(r.httpStatus()).isEqualTo(201);
        sap.verify(postRequestedFor(urlPathEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("Idempotency-Key", equalTo("h-1"))
                .withRequestBody(matchingJsonPath("$.Product", equalTo("SKU-001")))
                .withRequestBody(matchingJsonPath("$.Description", equalTo("Tornillo M6")))
                .withRequestBody(matchingJsonPath("$.BaseUnit", equalTo("UN")))
                .withRequestBody(matchingJsonPath("$.Status", equalTo("ACTIVE"))));
    }
}

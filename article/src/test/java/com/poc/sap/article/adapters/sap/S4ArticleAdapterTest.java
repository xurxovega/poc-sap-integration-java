package com.poc.sap.article.adapters.sap;

import com.poc.sap.article.domain.Article;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test unit del {@link S4ArticleAdapter} (TECH.md §8). Mockea {@link SapClient}
 * y verifica el mapeo {@code Article -> JSON de contrato S/4 OData
 * API_PRODUCT} y la delegacion al cliente de bajo nivel.
 */
@ExtendWith(MockitoExtension.class)
class S4ArticleAdapterTest {

    private static final String PATH = "/sap/opu/odata/sap/API_PRODUCT";

    @Mock SapClient sapClient;
    private S4ArticleAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new S4ArticleAdapter(sapClient, PATH);
    }

    @Test
    void sendsNormalizedBodyToS4() {
        Article a = new Article("A-1", "SKU-001", "Tornillo M6", "Hardware", "UN",
                Article.Status.ACTIVE);
        when(sapClient.send(eq(SapDestination.S4_NATIVE), eq(PATH), eq("A-1"), eq("h"),
                anyString()))
                .thenReturn(new SapResponse(201, "", null));

        adapter.send("A-1", "h", a);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sapClient).send(eq(SapDestination.S4_NATIVE), eq(PATH), eq("A-1"), eq("h"),
                body.capture());
        assertThat(body.getValue())
                .contains("\"Product\":\"SKU-001\"")
                .contains("\"Description\":\"Tornillo M6\"")
                .contains("\"Category\":\"Hardware\"")
                .contains("\"BaseUnit\":\"UN\"")
                .contains("\"Status\":\"ACTIVE\"");
    }

    @Test
    void nullArticleSendsEmptyObject() {
        when(sapClient.send(any(), any(), any(), any(), anyString()))
                .thenReturn(new SapResponse(201, "", null));

        adapter.send("A-1", "h", null);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sapClient).send(eq(SapDestination.S4_NATIVE), eq(PATH), eq("A-1"), eq("h"),
                body.capture());
        assertThat(body.getValue()).isEqualTo("{}");
    }

    /** sincronizacion-articulo §5: un campo sin valor se OMITE, no viaja como "" (A19/C10). */
    @Test
    void nullFieldsAreOmittedInsteadOfSentAsEmptyStrings() {
        Article a = new Article("A-1", "SKU-001", null, null, null,
                Article.Status.DISCONTINUED);
        when(sapClient.send(any(), any(), any(), any(), anyString()))
                .thenReturn(new SapResponse(201, "", null));

        adapter.send("A-1", "h", a);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sapClient).send(eq(SapDestination.S4_NATIVE), eq(PATH), eq("A-1"), eq("h"),
                body.capture());
        assertThat(body.getValue())
                .doesNotContain("Description")
                .doesNotContain("Category")
                .doesNotContain("BaseUnit")
                .contains("\"Product\":\"SKU-001\"")
                .contains("\"Status\":\"DISCONTINUED\"");
    }
}
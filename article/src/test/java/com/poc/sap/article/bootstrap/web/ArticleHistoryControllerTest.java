package com.poc.sap.article.bootstrap.web;

import com.poc.sap.article.application.ArticleHistoryUseCase;
import com.poc.sap.article.domain.Article;
import com.poc.sap.common.diff.JsonDiff;
import com.poc.sap.common.domain.port.HistoryIndexerPort.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test del controller de historico de Article (MockMvc standalone).
 */
class ArticleHistoryControllerTest {

    private MockMvc mvc;
    private final ArticleHistoryUseCase useCase = mock(ArticleHistoryUseCase.class);

    private final Instant t1 = Instant.parse("2026-07-01T00:00:00Z");

    private Article article() {
        return new Article("A-1", "SKU-001", "Tornillo M6", "Hardware", "UN",
                Article.Status.ACTIVE);
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ArticleHistoryController(useCase)).build();
    }

    @Test
    void historyReturnsVersions() throws Exception {
        when(useCase.history("A-1")).thenReturn(List.of(new Snapshot<>("h-1", t1, article())));

        mvc.perform(get("/articles/A-1/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value("A-1"))
                .andExpect(jsonPath("$.versions[0].payloadHash").value("h-1"));
    }

    @Test
    void diffReturnsChanges() throws Exception {
        var diff = new ArticleHistoryUseCase.HistoryDiff("A-1",
                new ArticleHistoryUseCase.VersionRef("h-1", t1),
                new ArticleHistoryUseCase.VersionRef("h-2", t1),
                Map.of("description", new JsonDiff.Change("Tornillo M6", "Tornillo M8")));
        when(useCase.diff("A-1", null, null)).thenReturn(diff);

        mvc.perform(get("/articles/A-1/history/diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.description.after").value("Tornillo M8"));
    }

    @Test
    void unknownHashReturns404() throws Exception {
        when(useCase.diff("A-1", "h-99", null))
                .thenThrow(new NoSuchElementException("Version h-99 no existe"));

        mvc.perform(get("/articles/A-1/history/diff").param("from", "h-99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Version h-99 no existe"));
    }
}

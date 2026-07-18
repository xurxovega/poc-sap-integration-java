package com.poc.sap.article.adapters.index;

import com.poc.sap.article.domain.Article;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test unit del {@link ElasticsearchArticleIndexer} (TECH.md §7).
 */
@ExtendWith(MockitoExtension.class)
class ElasticsearchArticleIndexerTest {

    @Mock ArticleHistoryRepository repo;
    private ElasticsearchArticleIndexer indexer;

    @BeforeEach
    void setUp() {
        indexer = new ElasticsearchArticleIndexer(repo);
    }

    private Article valid() {
        return new Article("A-1", "SKU-001", "Tornillo M6", "Hardware", "UN",
                Article.Status.ACTIVE);
    }

    @Test
    void indexDelegatesToRepoWithHashedId() {
        indexer.index("A-1", valid(), "hash-1");

        ArgumentCaptor<ArticleHistoryDoc> captor =
                ArgumentCaptor.forClass(ArticleHistoryDoc.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("A-1-hash-1");
        assertThat(captor.getValue().getArticleId()).isEqualTo("A-1");
    }

    @Test
    void historyMapsAndSortsById() {
        ArticleHistoryDoc d2 = ArticleHistoryDoc.from(
                new Article("A-2", "SKU-002", "Tuerca", "Hardware", "UN",
                        Article.Status.ACTIVE),
                "h2", Instant.parse("2026-01-02T00:00:00Z"));
        ArticleHistoryDoc d1 = ArticleHistoryDoc.from(valid(), "h1",
                Instant.parse("2026-01-01T00:00:00Z"));
        when(repo.findByArticleIdOrderByTimestampDesc("A-1"))
                .thenReturn(List.of(d2, d1));

        List<Article> history = indexer.history("A-1");

        assertThat(history).extracting(Article::id).containsExactly("A-1", "A-2");
    }

    @Test
    void historyEmptyReturnsEmptyList() {
        when(repo.findByArticleIdOrderByTimestampDesc("A-1")).thenReturn(List.of());

        assertThat(indexer.history("A-1")).isEmpty();
    }
}
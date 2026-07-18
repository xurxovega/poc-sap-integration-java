package com.poc.sap.article.adapters.persistence;

import com.poc.sap.article.domain.Article;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test unit del {@link PostgresArticleRepository} (TECH.md §7). Mockea el Spring
 * Data JPA repo y verifica el mapeo {@code ArticleEntity -> Article}.
 */
@ExtendWith(MockitoExtension.class)
class PostgresArticleRepositoryTest {

    @Mock ArticleJpaRepository jpa;
    private PostgresArticleRepository repo;

    @BeforeEach
    void setUp() {
        repo = new PostgresArticleRepository(jpa);
    }

    private ArticleEntity entity() {
        ArticleEntity e = new ArticleEntity();
        e.setId("A-1");
        e.setSku("SKU-001");
        e.setDescription("Tornillo M6");
        e.setCategory("Hardware");
        e.setUnit("UN");
        e.setStatus("ACTIVE");
        return e;
    }

    @Test
    void fetchEmptyReturnsOptional() {
        when(jpa.findById("A-missing")).thenReturn(Optional.empty());

        assertThat(repo.fetch("A-missing")).isEmpty();
    }

    @Test
    void fetchMapsAllFields() {
        when(jpa.findById("A-1")).thenReturn(Optional.of(entity()));

        Article a = repo.fetch("A-1").orElseThrow();

        assertThat(a.id()).isEqualTo("A-1");
        assertThat(a.sku()).isEqualTo("SKU-001");
        assertThat(a.description()).isEqualTo("Tornillo M6");
        assertThat(a.category()).isEqualTo("Hardware");
        assertThat(a.unit()).isEqualTo("UN");
        assertThat(a.status()).isEqualTo(Article.Status.ACTIVE);
    }

    @Test
    void statusStringIsMappedToEnumValue() {
        ArticleEntity e = entity();
        e.setStatus("DISCONTINUED");
        when(jpa.findById("A-1")).thenReturn(Optional.of(e));

        Article a = repo.fetch("A-1").orElseThrow();

        assertThat(a.status()).isEqualTo(Article.Status.DISCONTINUED);
    }
}
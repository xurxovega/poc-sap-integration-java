package com.poc.sap.article.adapters.persistence;

import com.poc.sap.article.domain.Article;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test unit del {@link MongoArticleImageStore} (TECH.md §7). Mockea el Spring
 * Data Mongo repo y verifica el mapeo ida/vuelta con {@link ArticleDocument}.
 */
@ExtendWith(MockitoExtension.class)
class MongoArticleImageStoreTest {

    @Mock ArticleMongoRepository mongo;
    private MongoArticleImageStore store;

    @BeforeEach
    void setUp() {
        store = new MongoArticleImageStore(mongo);
    }

    private Article valid() {
        return new Article("A-1", "SKU-001", "Tornillo M6", "Hardware", "UN",
                Article.Status.ACTIVE);
    }

    @Test
    void saveDelegatesToMongo() {
        when(mongo.save(any(ArticleDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        store.save("A-1", valid());

        ArgumentCaptor<ArticleDocument> captor =
                ArgumentCaptor.forClass(ArticleDocument.class);
        verify(mongo).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("A-1");
    }

    @Test
    void findEmptyReturnsOptional() {
        when(mongo.findById("A-x")).thenReturn(Optional.empty());

        assertThat(store.find("A-x")).isEmpty();
    }

    @Test
    void findRoundtripsArticle() {
        when(mongo.findById("A-1"))
                .thenReturn(Optional.of(ArticleDocument.fromDomain(valid())));

        Article a = store.find("A-1").orElseThrow();

        assertThat(a.id()).isEqualTo("A-1");
        assertThat(a.sku()).isEqualTo("SKU-001");
        assertThat(a.description()).isEqualTo("Tornillo M6");
        assertThat(a.category()).isEqualTo("Hardware");
        assertThat(a.unit()).isEqualTo("UN");
        assertThat(a.status()).isEqualTo(Article.Status.ACTIVE);
    }

    @Test
    void deleteDelegatesToMongo() {
        store.delete("A-1");

        verify(mongo).deleteById("A-1");
    }
}
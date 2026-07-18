package com.poc.sap.article.adapters.persistence;

import com.poc.sap.article.domain.Article;
import com.poc.sap.article.domain.port.ArticleLegacyRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class PostgresArticleRepository implements ArticleLegacyRepositoryPort {

    private final ArticleJpaRepository jpa;

    public PostgresArticleRepository(ArticleJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Article> fetch(String entityId) {
        return jpa.findById(entityId).map(this::toDomain);
    }

    private Article toDomain(ArticleEntity e) {
        return new Article(e.getId(), e.getSku(), e.getDescription(),
                e.getCategory(), e.getUnit(),
                Article.Status.valueOf(e.getStatus()));
    }
}
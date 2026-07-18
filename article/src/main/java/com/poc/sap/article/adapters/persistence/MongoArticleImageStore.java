package com.poc.sap.article.adapters.persistence;

import com.poc.sap.article.domain.Article;
import com.poc.sap.article.domain.port.ArticleImageStorePort;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class MongoArticleImageStore implements ArticleImageStorePort {

    private final ArticleMongoRepository mongo;

    public MongoArticleImageStore(ArticleMongoRepository mongo) {
        this.mongo = mongo;
    }

    @Override
    public void save(String entityId, Article entity) {
        mongo.save(ArticleDocument.fromDomain(entity));
    }

    @Override
    public Optional<Article> find(String entityId) {
        return mongo.findById(entityId).map(ArticleDocument::toDomain);
    }

    @Override
    public void delete(String entityId) {
        mongo.deleteById(entityId);
    }
}
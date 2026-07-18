package com.poc.sap.article.adapters.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ArticleMongoRepository extends MongoRepository<ArticleDocument, String> {
}
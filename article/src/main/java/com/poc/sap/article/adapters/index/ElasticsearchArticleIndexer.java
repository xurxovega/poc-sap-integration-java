package com.poc.sap.article.adapters.index;

import com.poc.sap.article.domain.Article;
import com.poc.sap.article.domain.port.ArticleHistoryIndexerPort;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class ElasticsearchArticleIndexer implements ArticleHistoryIndexerPort {

    private final ArticleHistoryRepository repo;

    public ElasticsearchArticleIndexer(ArticleHistoryRepository repo) {
        this.repo = repo;
    }

    @Override
    public void index(String entityId, Article entity, String payloadHash) {
        repo.save(ArticleHistoryDoc.from(entity, payloadHash, Instant.now()));
    }

    @Override
    public List<Article> history(String entityId) {
        return repo.findByArticleIdOrderByTimestampDesc(entityId).stream()
                .map(ArticleHistoryDoc::toDomain)
                .toList();
    }
}
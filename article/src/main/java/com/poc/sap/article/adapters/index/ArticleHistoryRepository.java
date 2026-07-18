package com.poc.sap.article.adapters.index;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface ArticleHistoryRepository
        extends ElasticsearchRepository<ArticleHistoryDoc, String> {

    List<ArticleHistoryDoc> findByArticleIdOrderByTimestampDesc(String articleId);
}
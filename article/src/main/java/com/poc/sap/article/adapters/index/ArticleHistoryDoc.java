package com.poc.sap.article.adapters.index;

import com.poc.sap.article.domain.Article;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;

@Document(indexName = "articles_history")
public class ArticleHistoryDoc {

    @Id
    private String id;

    @Field(type = FieldType.Keyword)
    private String articleId;

    @Field(type = FieldType.Keyword)
    private String sku;

    @Field(type = FieldType.Text)
    private String description;

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Keyword)
    private String status;

    @Field(type = FieldType.Keyword)
    private String payloadHash;

    @Field(type = FieldType.Date, name = "@timestamp")
    private Instant timestamp;

    public static ArticleHistoryDoc from(Article a, String payloadHash, Instant ts) {
        ArticleHistoryDoc d = new ArticleHistoryDoc();
        d.id = a.id() + "-" + payloadHash;
        d.articleId = a.id();
        d.sku = a.sku();
        d.description = a.description();
        d.category = a.category();
        d.status = a.status() != null ? a.status().name() : null;
        d.payloadHash = payloadHash;
        d.timestamp = ts;
        return d;
    }

    public Article toDomain() {
        return new Article(articleId, sku, description, category, null,
                status != null ? Article.Status.valueOf(status) : null);
    }

    public String getId() { return id; }
    public String getArticleId() { return articleId; }
}
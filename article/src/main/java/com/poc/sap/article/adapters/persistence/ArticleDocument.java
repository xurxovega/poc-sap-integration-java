package com.poc.sap.article.adapters.persistence;

import com.poc.sap.article.domain.Article;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "articles_current")
public class ArticleDocument {

    @Id
    private String id;
    private String sku;
    private String description;
    private String category;
    private String unit;
    private String status;

    public static ArticleDocument fromDomain(Article a) {
        ArticleDocument d = new ArticleDocument();
        d.id = a.id();
        d.sku = a.sku();
        d.description = a.description();
        d.category = a.category();
        d.unit = a.unit();
        d.status = a.status() != null ? a.status().name() : null;
        return d;
    }

    public Article toDomain() {
        return new Article(id, sku, description, category, unit,
                status != null ? Article.Status.valueOf(status) : null);
    }

    public String getId() { return id; }
}
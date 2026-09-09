package com.poc.sap.article.adapters.sap;

import com.poc.sap.article.domain.Article;
import com.poc.sap.article.domain.port.ArticleSapOutboundPort;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Adaptador SAP S/4 nativo para Article (TECH.md §8).
 */
@Component
public class S4ArticleAdapter implements ArticleSapOutboundPort {

    private final SapClient sapClient;
    private final String path;

    public S4ArticleAdapter(SapClient sapClient,
                            @Value("${sap.article.s4.path:/sap/opu/odata/sap/API_PRODUCT}") String path) {
        this.sapClient = sapClient;
        this.path = path;
    }

    @Override
    public SapResponse send(String entityId, String payloadHash, Article article) {
        String body = article != null ? toJson(article) : "{}";
        return sapClient.send(SapDestination.S4_NATIVE, path, entityId, payloadHash, body);
    }

    private String toJson(Article a) {
        return """
                {"Product":"%s","Description":"%s","Category":"%s","BaseUnit":"%s","Status":"%s"}"""
                .formatted(
                        a.sku() != null ? a.sku() : "",
                        a.description() != null ? a.description() : "",
                        a.category() != null ? a.category() : "",
                        a.unit() != null ? a.unit() : "",
                        a.status() != null ? a.status().name() : "");
    }
}
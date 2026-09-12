package com.poc.sap.article.adapters.sap;

import com.poc.sap.article.adapters.sap.dto.S4ProductDto;
import com.poc.sap.article.domain.Article;
import com.poc.sap.article.domain.port.ArticleSapOutboundPort;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.json.SapJsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Adaptador SAP S/4 nativo para Article (sdd/article/sincronizacion-articulo.md §5).
 * Serializa con {@link SapJsonMapper} via {@link S4ProductDto}; antes construia el
 * JSON con String.format y convertia null en "" (auditoria A19/C10).
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
        String body = article != null ? SapJsonMapper.write(S4ProductDto.from(article)) : "{}";
        return sapClient.send(SapDestination.S4_NATIVE, path, entityId, payloadHash, body);
    }
}
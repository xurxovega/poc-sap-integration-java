package com.poc.sap.article.bootstrap;

import com.poc.sap.article.application.ArticleHistoryUseCase;
import com.poc.sap.article.application.SyncArticleUseCase;
import com.poc.sap.article.domain.port.ArticleHistoryIndexerPort;
import com.poc.sap.article.domain.port.ArticleImageStorePort;
import com.poc.sap.article.domain.port.ArticleLegacyRepositoryPort;
import com.poc.sap.article.domain.port.ArticleSapOutboundPort;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wiring de los use cases del dominio article: el unico punto de contacto de
 * {@code application} con Spring (auditoria A4; plan Fase 7).
 */
@Configuration
public class ArticleUseCaseConfig {

    /**
     * Reloj unico del dominio. Los use cases no llaman a {@code Instant.now()}:
     * el instante de cada paso de la traza tiene que ser verificable en un test
     * (anexo 04, lista "O"). Es {@code @Bean} porque {@code application} no puede
     * depender de Spring (AGENTS.md §1.5).
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SyncArticleUseCase syncArticleUseCase(ArticleLegacyRepositoryPort legacy, ArticleImageStorePort image,
                                          ArticleHistoryIndexerPort history, ArticleSapOutboundPort sap,
                                          SyncStateRepositoryPort state, MetricsPort metrics, Clock clock) {
        return new SyncArticleUseCase(legacy, image, history, sap, state, metrics, clock);
    }

    @Bean
    ArticleHistoryUseCase articleHistoryUseCase(ArticleHistoryIndexerPort history) {
        return new ArticleHistoryUseCase(history);
    }
}

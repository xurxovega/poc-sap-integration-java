package com.poc.sap.article.application;

import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.ValidationResult;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.article.domain.Article;
import com.poc.sap.article.domain.ArticleValidations;
import com.poc.sap.article.domain.port.ArticleHistoryIndexerPort;
import com.poc.sap.article.domain.port.ArticleImageStorePort;
import com.poc.sap.article.domain.port.ArticleLegacyRepositoryPort;
import com.poc.sap.article.domain.port.ArticleSapOutboundPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Use case principal de Article: fetch → validate → index → send to SAP
 * (OVERVIEW.md §2, §5; TECH.md §6). Idempotente por payloadHash.
 */
@Service
public class SyncArticleUseCase {

    private static final Logger log = LoggerFactory.getLogger(SyncArticleUseCase.class);
    private static final String DOMAIN = "article";

    private final ArticleLegacyRepositoryPort legacyRepo;
    private final ArticleImageStorePort imageStore;
    private final ArticleHistoryIndexerPort historyIndexer;
    private final ArticleSapOutboundPort sapOutbound;
    private final SyncStateRepositoryPort stateRepo;
    private final SyncMetrics metrics;

    public SyncArticleUseCase(ArticleLegacyRepositoryPort legacyRepo,
                              ArticleImageStorePort imageStore,
                              ArticleHistoryIndexerPort historyIndexer,
                              ArticleSapOutboundPort sapOutbound,
                              SyncStateRepositoryPort stateRepo,
                              SyncMetrics metrics) {
        this.legacyRepo = legacyRepo;
        this.imageStore = imageStore;
        this.historyIndexer = historyIndexer;
        this.sapOutbound = sapOutbound;
        this.stateRepo = stateRepo;
        this.metrics = metrics;
    }

    public SyncState execute(IngestionMessage message) {
        if (stateRepo.alreadySent(DOMAIN, message.entityId(), message.payloadHash())) {
            log.info("SyncArticle dedupe entityId={} payloadHash={} ya enviado a SAP, se omite",
                    message.entityId(), message.payloadHash());
            return SyncState.SENT_SAP;
        }
        log.info("SyncArticle inicio entityId={} origin={}", message.entityId(), message.origin());

        boolean lastCycleSent = stateRepo.currentState(DOMAIN, message.entityId())
                .filter(s -> s == SyncState.SENT_SAP)
                .isPresent();

        transition(message, null, SyncState.RECEIVED);
        transition(message, SyncState.RECEIVED, SyncState.FETCHING);

        Optional<Article> fetched = legacyRepo.fetch(message.entityId());
        if (fetched.isEmpty()) {
            transition(message, SyncState.FETCHING, SyncState.ERROR);
            return SyncState.ERROR;
        }
        Article article = fetched.get();

        transition(message, SyncState.FETCHING, SyncState.VALIDATING);
        ValidationResult validation = ArticleValidations.validate(article);
        if (!validation.valid()) {
            log.warn("Article invalido entityId={} errors={}", message.entityId(), validation.errors());
            transition(message, SyncState.VALIDATING, SyncState.INVALID);
            return SyncState.INVALID;
        }

        transition(message, SyncState.VALIDATING, SyncState.VALID);

        // Sin cambios reales: ciclo anterior SENT_SAP + snapshot identico a la
        // imagen staging (Mongo) → no se reindexa ni se reenvia a SAP.
        if (lastCycleSent && imageStore.find(article.id()).filter(article::equals).isPresent()) {
            log.info("SyncArticle sin cambios reales entityId={} (snapshot == imagen staging), no se reenvia",
                    message.entityId());
            transition(message, SyncState.VALID, SyncState.SENT_SAP);
            return SyncState.SENT_SAP;
        }

        transition(message, SyncState.VALID, SyncState.INDEXING);
        imageStore.save(article.id(), article);
        historyIndexer.index(article.id(), article, message.payloadHash());
        transition(message, SyncState.INDEXING, SyncState.INDEXED);

        transition(message, SyncState.INDEXED, SyncState.SENDING_SAP);
        var response = sapOutbound.send(article.id(), message.payloadHash(), article);
        SyncState finalState = response.isSuccess()
                ? SyncState.SENT_SAP
                : SyncState.SAP_ERROR;
        transition(message, SyncState.SENDING_SAP, finalState);

        log.info("SyncArticle fin entityId={} state={} http={}",
                message.entityId(), finalState, response.httpStatus());
        return finalState;
    }

    private void transition(IngestionMessage msg, SyncState from, SyncState to) {
        stateRepo.transition(DOMAIN, msg.entityId(), new SyncStateTransition(
                msg.entityId(), DOMAIN, from, to,
                msg.origin().name().toLowerCase(), msg.payloadHash(), Instant.now()));
        metrics.incrementState(DOMAIN, to.name());
    }
}
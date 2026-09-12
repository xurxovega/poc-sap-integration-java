package com.poc.sap.article.application;

import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.ValidationResult;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.article.domain.Article;
import com.poc.sap.article.domain.ArticleValidations;
import com.poc.sap.article.domain.port.ArticleHistoryIndexerPort;
import com.poc.sap.article.domain.port.ArticleImageStorePort;
import com.poc.sap.article.domain.port.ArticleLegacyRepositoryPort;
import com.poc.sap.article.domain.port.ArticleSapOutboundPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Use case principal de Article: fetch → validate → index → send to SAP
 * (OVERVIEW.md §2, §5; TECH.md §6). Idempotente por payloadHash.
 */
public class SyncArticleUseCase {

    private static final Logger log = LoggerFactory.getLogger(SyncArticleUseCase.class);
    private static final String DOMAIN = "article";

    private final ArticleLegacyRepositoryPort legacyRepo;
    private final ArticleImageStorePort imageStore;
    private final ArticleHistoryIndexerPort historyIndexer;
    private final ArticleSapOutboundPort sapOutbound;
    private final SyncStateRepositoryPort stateRepo;
    private final MetricsPort metrics;

    public SyncArticleUseCase(ArticleLegacyRepositoryPort legacyRepo,
                              ArticleImageStorePort imageStore,
                              ArticleHistoryIndexerPort historyIndexer,
                              ArticleSapOutboundPort sapOutbound,
                              SyncStateRepositoryPort stateRepo,
                              MetricsPort metrics) {
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

        // Un evento nuevo siempre abre ciclo (sdd/common/maquina-de-estados.md R-3).
        beginCycle(message, SyncState.RECEIVED);
        transition(message, SyncState.RECEIVED, SyncState.FETCHING);

        Optional<Article> fetched = timed("fetch", () -> legacyRepo.fetch(message.entityId()));
        if (fetched.isEmpty()) {
            transition(message, SyncState.FETCHING, SyncState.ERROR);
            return SyncState.ERROR;
        }
        Article article = fetched.get();

        transition(message, SyncState.FETCHING, SyncState.VALIDATING);
        ValidationResult validation = timed("validate", () -> ArticleValidations.validate(article));
        if (!validation.valid()) {
            log.warn("Article invalido entityId={} errors={}", message.entityId(), validation.errors());
            transition(message, SyncState.VALIDATING, SyncState.INVALID);
            return SyncState.INVALID;
        }

        transition(message, SyncState.VALIDATING, SyncState.VALID);

        // Fallo de infraestructura tras VALID → ERROR y se propaga (auditoria B12/C2).
        try {
            // Sin cambios reales: ciclo anterior SENT_SAP + snapshot identico a la
            // imagen staging (Mongo) → no se reindexa ni se reenvia a SAP.
            if (lastCycleSent && imageStore.find(article.id()).filter(article::equals).isPresent()) {
                log.info("SyncArticle sin cambios reales entityId={} (snapshot == imagen staging), no se reenvia",
                        message.entityId());
                transition(message, SyncState.VALID, SyncState.SENT_SAP);
                return SyncState.SENT_SAP;
            }

            transition(message, SyncState.VALID, SyncState.INDEXING);
            // Historico = lo que se va a enviar, un documento por intento (idempotencia-y-dedupe R-5).
            timed("index", () -> {
                historyIndexer.index(article.id(), article, message.payloadHash());
                return null;
            });
            transition(message, SyncState.INDEXING, SyncState.INDEXED);

            transition(message, SyncState.INDEXED, SyncState.SENDING_SAP);
            var response = timed("send", () -> sapOutbound.send(article.id(), message.payloadHash(), article));
            SyncState finalState = response.isSuccess()
                    ? SyncState.SENT_SAP
                    : SyncState.SAP_ERROR;
            if (finalState == SyncState.SENT_SAP) {
                // Imagen = lo que SAP tiene: solo tras el ACK (idempotencia-y-dedupe R-4).
                imageStore.save(article.id(), article);
            }
            transition(message, SyncState.SENDING_SAP, finalState);

            log.info("SyncArticle fin entityId={} state={} http={}",
                    message.entityId(), finalState, response.httpStatus());
            return finalState;
        } catch (RuntimeException e) {
            markError(message, e);
            throw e;
        }
    }

    /** Duracion de cada etapa en sap_sync_stage_duration (sdd/common/observabilidad.md R-2). */
    private <T> T timed(String stage, Supplier<T> body) {
        long start = System.nanoTime();
        try {
            return body.get();
        } finally {
            metrics.recordStageDuration(DOMAIN, stage, (System.nanoTime() - start) / 1_000_000);
        }
    }

    private void beginCycle(IngestionMessage msg, SyncState entry) {
        stateRepo.beginCycle(DOMAIN, msg.entityId(), new SyncStateTransition(
                msg.entityId(), DOMAIN, null, entry,
                msg.origin().name().toLowerCase(), msg.payloadHash(), Instant.now()));
        metrics.incrementState(DOMAIN, entry.name());
    }

    private void markError(IngestionMessage msg, RuntimeException cause) {
        try {
            transition(msg, null, SyncState.ERROR);
        } catch (RuntimeException e) {
            log.error("No se pudo registrar ERROR entityId={} tras fallo '{}'", msg.entityId(), cause.toString(), e);
        }
    }

    private void transition(IngestionMessage msg, SyncState from, SyncState to) {
        stateRepo.transition(DOMAIN, msg.entityId(), new SyncStateTransition(
                msg.entityId(), DOMAIN, from, to,
                msg.origin().name().toLowerCase(), msg.payloadHash(), Instant.now()));
        metrics.incrementState(DOMAIN, to.name());
    }
}
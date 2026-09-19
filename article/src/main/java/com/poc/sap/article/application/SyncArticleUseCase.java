package com.poc.sap.article.application;

import com.poc.sap.common.application.SyncCycleRecorder;
import com.poc.sap.common.application.SyncCycleRecorder.Cycle;
import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.PayloadHasher;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
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

import java.time.Clock;
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
    private final SyncCycleRecorder cycle;

    public SyncArticleUseCase(ArticleLegacyRepositoryPort legacyRepo,
                              ArticleImageStorePort imageStore,
                              ArticleHistoryIndexerPort historyIndexer,
                              ArticleSapOutboundPort sapOutbound,
                              SyncStateRepositoryPort stateRepo,
                              MetricsPort metrics,
                              Clock clock) {
        this.legacyRepo = legacyRepo;
        this.imageStore = imageStore;
        this.historyIndexer = historyIndexer;
        this.sapOutbound = sapOutbound;
        this.stateRepo = stateRepo;
        this.metrics = metrics;
        this.cycle = new SyncCycleRecorder(DOMAIN, stateRepo, metrics, clock);
    }

    public SyncState execute(IngestionMessage message) {
        log.info("SyncArticle inicio entityId={} origin={}", message.entityId(), message.origin());

        // ADR-0013 (mensaje fino): el aviso solo dice QUE articulo cambio; los datos
        // y el hash de idempotencia salen del snapshot que se acaba de leer.
        Optional<Article> fetched = timed("fetch", () -> legacyRepo.fetch(message.entityId()));
        if (fetched.isEmpty()) {
            Cycle failed = cycle.beginCycle(message.entityId(), origin(message),
                    message.payloadHash(), SyncState.RECEIVED);
            cycle.advance(failed, SyncState.RECEIVED, SyncState.FETCHING);
            cycle.advance(failed, SyncState.FETCHING, SyncState.ERROR, "no existe en el legacy");
            return SyncState.ERROR;
        }
        Article article = fetched.get();

        String payloadHash = PayloadHasher.hash(article);
        if (message.payloadHash() != null && !message.payloadHash().equals(payloadHash)) {
            log.debug("SyncArticle hash del mensaje {} != hash del snapshot {} entityId={}",
                    message.payloadHash(), payloadHash, message.entityId());
        }

        if (stateRepo.alreadySent(DOMAIN, message.entityId(), payloadHash)) {
            log.info("SyncArticle dedupe entityId={} payloadHash={} (del snapshot) ya enviado a SAP, se omite",
                    message.entityId(), payloadHash);
            return SyncState.SENT_SAP;
        }

        boolean lastCycleSent = stateRepo.currentState(DOMAIN, message.entityId())
                .filter(s -> s == SyncState.SENT_SAP)
                .isPresent();

        // Un evento nuevo siempre abre ciclo (sdd/common/maquina-de-estados.md R-3).
        Cycle c = cycle.beginCycle(message.entityId(), origin(message), payloadHash, SyncState.RECEIVED);
        cycle.advance(c, SyncState.RECEIVED, SyncState.FETCHING);
        cycle.advance(c, SyncState.FETCHING, SyncState.VALIDATING);
        ValidationResult validation = timed("validate", () -> ArticleValidations.validate(article));
        if (!validation.valid()) {
            log.warn("Article invalido entityId={} errors={}", message.entityId(), validation.errors());
            cycle.advance(c, SyncState.VALIDATING, SyncState.INVALID, String.join("; ", validation.errors()));
            return SyncState.INVALID;
        }

        cycle.advance(c, SyncState.VALIDATING, SyncState.VALID);

        // Fallo de infraestructura tras VALID → ERROR y se propaga (auditoria B12/C2).
        try {
            // Sin cambios reales: ciclo anterior SENT_SAP + snapshot identico a la
            // imagen staging (Mongo) → no se reindexa ni se reenvia a SAP.
            if (lastCycleSent && imageStore.find(article.id()).filter(article::equals).isPresent()) {
                log.info("SyncArticle sin cambios reales entityId={} (snapshot == imagen staging), no se reenvia",
                        message.entityId());
                cycle.advance(c, SyncState.VALID, SyncState.SENT_SAP);
                return SyncState.SENT_SAP;
            }

            cycle.advance(c, SyncState.VALID, SyncState.INDEXING);
            // Historico = lo que se va a enviar, un documento por intento (idempotencia-y-dedupe R-5).
            timed("index", () -> {
                historyIndexer.index(article.id(), article, payloadHash);
                return null;
            });
            cycle.advance(c, SyncState.INDEXING, SyncState.INDEXED);

            cycle.advance(c, SyncState.INDEXED, SyncState.SENDING_SAP);
            var response = timed("send", () -> sapOutbound.send(article.id(), payloadHash, article));
            SyncState finalState = response.isSuccess()
                    ? SyncState.SENT_SAP
                    : SyncState.SAP_ERROR;
            if (finalState == SyncState.SENT_SAP) {
                // Imagen = lo que SAP tiene: solo tras el ACK (idempotencia-y-dedupe R-4).
                imageStore.save(article.id(), article);
            }
            cycle.advance(c, SyncState.SENDING_SAP, finalState,
                    finalState == SyncState.SENT_SAP ? null : "HTTP " + response.httpStatus() + " enviando a SAP");

            log.info("SyncArticle fin entityId={} state={} http={}",
                    message.entityId(), finalState, response.httpStatus());
            return finalState;
        } catch (RuntimeException e) {
            markError(c, message, e);
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

    private static String origin(IngestionMessage msg) {
        return msg.origin().name().toLowerCase();
    }

    private void markError(Cycle c, IngestionMessage msg, RuntimeException cause) {
        try {
            // from = null a proposito: cerrar en ERROR no puede fallar por una colision.
            cycle.advance(c, null, SyncState.ERROR,
                    cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (RuntimeException e) {
            log.error("No se pudo registrar ERROR entityId={} tras fallo '{}'", msg.entityId(), cause.toString(), e);
        }
    }
}
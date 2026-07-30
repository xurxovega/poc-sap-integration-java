package com.poc.sap.article.application;

import com.poc.sap.article.domain.Article;
import com.poc.sap.article.domain.port.ArticleHistoryIndexerPort;
import com.poc.sap.common.diff.JsonDiff;
import com.poc.sap.common.domain.port.HistoryIndexerPort.Snapshot;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Consulta del historico de versiones enviadas a SAP (Elasticsearch) y diff
 * entre dos versiones. Mismo contrato que el homologo de customer.
 */
@Service
public class ArticleHistoryUseCase {

    private final ArticleHistoryIndexerPort historyIndexer;

    public ArticleHistoryUseCase(ArticleHistoryIndexerPort historyIndexer) {
        this.historyIndexer = historyIndexer;
    }

    /** Versiones de la entidad, de mas reciente a mas antigua. */
    public List<Snapshot<Article>> history(String articleId) {
        return historyIndexer.snapshots(articleId);
    }

    /**
     * Diff entre dos versiones del historico.
     *
     * @param toHash   version destino ({@code null} → la mas reciente)
     * @param fromHash version base ({@code null} → la inmediatamente anterior a {@code to})
     * @throws NoSuchElementException si no hay historico, el hash no existe o
     *         no hay version anterior
     */
    public HistoryDiff diff(String articleId, String fromHash, String toHash) {
        List<Snapshot<Article>> snapshots = historyIndexer.snapshots(articleId);
        if (snapshots.isEmpty()) {
            throw new NoSuchElementException("Sin historico para article " + articleId);
        }
        Snapshot<Article> to = toHash == null ? snapshots.get(0) : byHash(snapshots, toHash, articleId);
        Snapshot<Article> from = fromHash == null ? previousOf(snapshots, to, articleId)
                : byHash(snapshots, fromHash, articleId);
        return new HistoryDiff(articleId,
                new VersionRef(from.payloadHash(), from.timestamp()),
                new VersionRef(to.payloadHash(), to.timestamp()),
                JsonDiff.diff(from.entity(), to.entity()));
    }

    private static Snapshot<Article> byHash(List<Snapshot<Article>> snapshots,
                                            String hash, String articleId) {
        return snapshots.stream()
                .filter(s -> hash.equals(s.payloadHash()))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "Version " + hash + " no existe en el historico de article " + articleId));
    }

    private static Snapshot<Article> previousOf(List<Snapshot<Article>> snapshots,
                                                Snapshot<Article> to, String articleId) {
        int idx = snapshots.indexOf(to);
        if (idx < 0 || idx + 1 >= snapshots.size()) {
            throw new NoSuchElementException(
                    "No hay version anterior a " + to.payloadHash() + " para article " + articleId);
        }
        return snapshots.get(idx + 1);
    }

    /** Referencia de version: hash del evento + instante de indexacion. */
    public record VersionRef(String payloadHash, Instant timestamp) {}

    /** Resultado del diff: rutas cambiadas con valor anterior y nuevo. */
    public record HistoryDiff(String entityId, VersionRef from, VersionRef to,
                              Map<String, JsonDiff.Change> changes) {}
}

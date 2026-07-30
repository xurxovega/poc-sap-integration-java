package com.poc.sap.customer.application.general;

import com.poc.sap.common.diff.JsonDiff;
import com.poc.sap.common.domain.port.HistoryIndexerPort.Snapshot;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.port.CustomerHistoryIndexerPort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Consulta del historico de versiones enviadas a SAP (Elasticsearch) y diff
 * entre dos versiones: auditar que cambio realmente entre dos envios.
 *
 * <p>Las versiones se identifican por su {@code payloadHash} (id del doc ES =
 * {@code customerId-payloadHash}). Sin parametros, el diff compara la ultima
 * version contra la anterior.
 */
@Service
public class CustomerHistoryUseCase {

    private final CustomerHistoryIndexerPort historyIndexer;

    public CustomerHistoryUseCase(CustomerHistoryIndexerPort historyIndexer) {
        this.historyIndexer = historyIndexer;
    }

    /** Versiones de la entidad, de mas reciente a mas antigua. */
    public List<Snapshot<Customer>> history(String customerId) {
        return historyIndexer.snapshots(customerId);
    }

    /**
     * Diff entre dos versiones del historico.
     *
     * @param toHash   version destino ({@code null} → la mas reciente)
     * @param fromHash version base ({@code null} → la inmediatamente anterior a {@code to})
     * @throws NoSuchElementException si la entidad no tiene historico, el hash
     *         pedido no existe o no hay version anterior con la que comparar
     */
    public HistoryDiff diff(String customerId, String fromHash, String toHash) {
        List<Snapshot<Customer>> snapshots = historyIndexer.snapshots(customerId);
        if (snapshots.isEmpty()) {
            throw new NoSuchElementException("Sin historico para customer " + customerId);
        }
        Snapshot<Customer> to = toHash == null ? snapshots.get(0) : byHash(snapshots, toHash, customerId);
        Snapshot<Customer> from = fromHash == null ? previousOf(snapshots, to, customerId)
                : byHash(snapshots, fromHash, customerId);
        return new HistoryDiff(customerId,
                new VersionRef(from.payloadHash(), from.timestamp()),
                new VersionRef(to.payloadHash(), to.timestamp()),
                JsonDiff.diff(from.entity(), to.entity()));
    }

    private static Snapshot<Customer> byHash(List<Snapshot<Customer>> snapshots,
                                             String hash, String customerId) {
        return snapshots.stream()
                .filter(s -> hash.equals(s.payloadHash()))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "Version " + hash + " no existe en el historico de customer " + customerId));
    }

    private static Snapshot<Customer> previousOf(List<Snapshot<Customer>> snapshots,
                                                 Snapshot<Customer> to, String customerId) {
        int idx = snapshots.indexOf(to);
        if (idx < 0 || idx + 1 >= snapshots.size()) {
            throw new NoSuchElementException(
                    "No hay version anterior a " + to.payloadHash() + " para customer " + customerId);
        }
        return snapshots.get(idx + 1);
    }

    /** Referencia de version: hash del evento + instante de indexacion. */
    public record VersionRef(String payloadHash, Instant timestamp) {}

    /** Resultado del diff: rutas cambiadas con valor anterior y nuevo. */
    public record HistoryDiff(String entityId, VersionRef from, VersionRef to,
                              Map<String, JsonDiff.Change> changes) {}
}

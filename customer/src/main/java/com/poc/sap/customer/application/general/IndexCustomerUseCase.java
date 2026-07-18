package com.poc.sap.customer.application.general;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.port.CustomerHistoryIndexerPort;
import com.poc.sap.customer.domain.port.CustomerImageStorePort;
import com.poc.sap.customer.domain.port.CustomerLegacyRepositoryPort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Use case de indexacion aislada del aggregate Customer: imagen actual (Mongo)
 * + historico (Elasticsearch). No envia a SAP.
 */
@Service
public class IndexCustomerUseCase {

    private static final String DOMAIN = "customer";

    private final CustomerLegacyRepositoryPort legacyRepo;
    private final CustomerImageStorePort imageStore;
    private final CustomerHistoryIndexerPort historyIndexer;
    private final SyncStateRepositoryPort stateRepo;
    private final SyncMetrics metrics;

    public IndexCustomerUseCase(CustomerLegacyRepositoryPort legacyRepo,
                                CustomerImageStorePort imageStore,
                                CustomerHistoryIndexerPort historyIndexer,
                                SyncStateRepositoryPort stateRepo,
                                SyncMetrics metrics) {
        this.legacyRepo = legacyRepo;
        this.imageStore = imageStore;
        this.historyIndexer = historyIndexer;
        this.stateRepo = stateRepo;
        this.metrics = metrics;
    }

    public SyncState execute(String entityId, String payloadHash) {
        transition(entityId, payloadHash, null, SyncState.INDEXING);
        Optional<Customer> fetched = legacyRepo.fetch(entityId);
        if (fetched.isEmpty()) {
            transition(entityId, payloadHash, SyncState.INDEXING, SyncState.ERROR);
            return SyncState.ERROR;
        }
        Customer c = fetched.get();
        imageStore.save(c.id(), c);
        historyIndexer.index(c.id(), c, payloadHash);
        transition(entityId, payloadHash, SyncState.INDEXING, SyncState.INDEXED);
        return SyncState.INDEXED;
    }

    private void transition(String entityId, String payloadHash,
                            SyncState from, SyncState to) {
        stateRepo.transition(DOMAIN, entityId, new SyncStateTransition(
                entityId, DOMAIN, from, to, "rest", payloadHash, Instant.now()));
        metrics.incrementState(DOMAIN, to.name());
    }
}
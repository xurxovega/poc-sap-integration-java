package com.poc.sap.common.adapters.persistence;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateMachine;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Implementacion reusable (MongoDB) de {@link SyncStateRepositoryPort}.
 * Vivir en {@code common} para que customer/article/supplier la reusen sin
 * duplicar ni crear dependencias cross-dominio.
 */
@Repository
public class MongoSyncStateRepository implements SyncStateRepositoryPort {

    private final SyncStateMongoRepository mongo;
    private final SyncStateMachine machine = new SyncStateMachine();

    public MongoSyncStateRepository(SyncStateMongoRepository mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<SyncState> currentState(String domain, String entityId) {
        return mongo.findByDomainAndEntityIdOrderByTimestampDesc(domain, entityId).stream()
                .findFirst()
                .map(SyncStateDoc::stateCode)
                .map(SyncState::ofCode);
    }

    @Override
    public SyncState transition(String domain, String entityId, SyncStateTransition t) {
        SyncState from = currentState(domain, entityId).orElse(null);
        SyncState to = machine.transition(from, t.to());
        mongo.save(SyncStateDoc.from(domain, entityId, t, to.code()));
        return to;
    }

    @Override
    public List<SyncStateTransition> history(String domain, String entityId) {
        return mongo.findByDomainAndEntityIdOrderByTimestampAsc(domain, entityId).stream()
                .map(SyncStateDoc::toTransition)
                .sorted(Comparator.comparing(SyncStateTransition::timestamp))
                .toList();
    }
}
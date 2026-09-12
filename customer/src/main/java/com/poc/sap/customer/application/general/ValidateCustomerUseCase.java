package com.poc.sap.customer.application.general;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.customer.domain.CustomerValidations;
import com.poc.sap.customer.domain.port.CustomerLegacyRepositoryPort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;

/**
 * Use case de validacion aislada del aggregate Customer (todas las features).
 */
@Service
public class ValidateCustomerUseCase {

    private static final String DOMAIN = "customer";

    private final CustomerLegacyRepositoryPort legacyRepo;
    private final SyncStateRepositoryPort stateRepo;
    private final SyncMetrics metrics;

    public ValidateCustomerUseCase(CustomerLegacyRepositoryPort legacyRepo,
                                   SyncStateRepositoryPort stateRepo,
                                   SyncMetrics metrics) {
        this.legacyRepo = legacyRepo;
        this.stateRepo = stateRepo;
        this.metrics = metrics;
    }

    public SyncState execute(String entityId, String payloadHash) {
        return execute(entityId, payloadHash, EnumSet.allOf(com.poc.sap.customer.domain.CustomerFeature.class));
    }

    public SyncState execute(String entityId, String payloadHash,
                              java.util.Set<com.poc.sap.customer.domain.CustomerFeature> features) {
        beginCycle(entityId, payloadHash, SyncState.VALIDATING);
        var fetched = legacyRepo.fetch(entityId);
        if (fetched.isEmpty()) {
            transition(entityId, payloadHash, SyncState.VALIDATING, SyncState.ERROR);
            return SyncState.ERROR;
        }
        var r = CustomerValidations.validate(fetched.get(), features);
        SyncState target = r.valid() ? SyncState.VALID : SyncState.INVALID;
        transition(entityId, payloadHash, SyncState.VALIDATING, target);
        return target;
    }

    /** Abre un ciclo nuevo (sdd/common/maquina-de-estados.md R-3): legal desde cualquier estado previo. */
    private void beginCycle(String entityId, String payloadHash, SyncState entry) {
        stateRepo.beginCycle(DOMAIN, entityId, new SyncStateTransition(
                entityId, DOMAIN, null, entry, "rest", payloadHash, Instant.now()));
        metrics.incrementState(DOMAIN, entry.name());
    }

    private void transition(String entityId, String payloadHash,
                            SyncState from, SyncState to) {
        stateRepo.transition(DOMAIN, entityId, new SyncStateTransition(
                entityId, DOMAIN, from, to, "rest", payloadHash, Instant.now()));
        metrics.incrementState(DOMAIN, to.name());
    }
}
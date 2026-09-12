package com.poc.sap.customer.application.contact;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.feature.contact.ContactValidator;

import java.time.Instant;

/** Use case de validacion aislada de la feature CONTACT. */
public class ValidateContactUseCase {

    private final SyncStateRepositoryPort stateRepo;
    private final MetricsPort metrics;

    public ValidateContactUseCase(SyncStateRepositoryPort stateRepo, MetricsPort metrics) {
        this.stateRepo = stateRepo;
        this.metrics = metrics;
    }

    public SyncState execute(Customer c, String payloadHash) {
        String entityId = SyncContactUseCase.featureEntityId(c.id());
        beginCycle(entityId, payloadHash, SyncState.VALIDATING);
        var r = ContactValidator.validate(c.contact());
        SyncState target = r.valid() ? SyncState.VALID : SyncState.INVALID;
        transition(entityId, payloadHash, SyncState.VALIDATING, target);
        return target;
    }

    /** Abre un ciclo nuevo (sdd/common/maquina-de-estados.md R-3): legal desde cualquier estado previo. */
    private void beginCycle(String entityId, String payloadHash, SyncState entry) {
        stateRepo.beginCycle("customer", entityId, new SyncStateTransition(
                entityId, "customer", null, entry, "contact", payloadHash, Instant.now()));
        metrics.incrementState("customer", entry.name());
    }

    private void transition(String entityId, String payloadHash,
                            SyncState from, SyncState to) {
        stateRepo.transition("customer", entityId, new SyncStateTransition(
                entityId, "customer", from, to, "contact", payloadHash, Instant.now()));
        metrics.incrementState("customer", to.name());
    }
}
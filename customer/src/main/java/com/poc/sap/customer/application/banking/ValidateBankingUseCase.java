package com.poc.sap.customer.application.banking;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.feature.banking.BankingValidator;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Use case de validacion aislada de la feature BANKING. */
@Service
public class ValidateBankingUseCase {

    private final SyncStateRepositoryPort stateRepo;
    private final SyncMetrics metrics;

    public ValidateBankingUseCase(SyncStateRepositoryPort stateRepo, SyncMetrics metrics) {
        this.stateRepo = stateRepo;
        this.metrics = metrics;
    }

    public SyncState execute(Customer c, String payloadHash) {
        String entityId = SyncBankingUseCase.featureEntityId(c.id());
        transition(entityId, payloadHash, null, SyncState.VALIDATING);
        var r = BankingValidator.validate(c.banking());
        SyncState target = r.valid() ? SyncState.VALID : SyncState.INVALID;
        transition(entityId, payloadHash, SyncState.VALIDATING, target);
        return target;
    }

    private void transition(String entityId, String payloadHash,
                            SyncState from, SyncState to) {
        stateRepo.transition("customer", entityId, new SyncStateTransition(
                entityId, "customer", from, to, "banking", payloadHash, Instant.now()));
        metrics.incrementState("customer", to.name());
    }
}
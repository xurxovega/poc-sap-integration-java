package com.poc.sap.customer.application.banking;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.customer.domain.port.BankingSapPort;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Use case de borrado de Mandate (OVERVIEW.md §2; TECH.md §8).
 * Forma parte de la feature BANKING: notifica a SAP la revocacion del mandato
 * bancario.
 */
@Service
public class DeleteMandateUseCase {

    private static final String DOMAIN = "customer";
    private static final String STAGE = "banking";

    private final BankingSapPort sapPort;
    private final SyncStateRepositoryPort stateRepo;
    private final SyncMetrics metrics;

    public DeleteMandateUseCase(BankingSapPort sapPort,
                                SyncStateRepositoryPort stateRepo,
                                SyncMetrics metrics) {
        this.sapPort = sapPort;
        this.stateRepo = stateRepo;
        this.metrics = metrics;
    }

    public SyncState execute(String mandateId, String customerId, String payloadHash) {
        BankingData payload = new BankingData(null, null, List.of(mandateId));
        String entityId = customerId + ":" + "BANKING";
        transition(entityId, payloadHash, null, SyncState.SENDING_SAP);
        var response = sapPort.send(mandateId, payloadHash, payload);
        SyncState target = response.isSuccess() ? SyncState.SENT_SAP : SyncState.SAP_ERROR;
        transition(entityId, payloadHash, SyncState.SENDING_SAP, target);
        return target;
    }

    private void transition(String entityId, String payloadHash,
                            SyncState from, SyncState to) {
        stateRepo.transition(DOMAIN, entityId, new SyncStateTransition(
                entityId, DOMAIN, from, to, STAGE, payloadHash, Instant.now()));
        metrics.incrementState(DOMAIN, to.name());
    }
}
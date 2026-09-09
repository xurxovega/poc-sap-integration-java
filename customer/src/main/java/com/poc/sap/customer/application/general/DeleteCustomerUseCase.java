package com.poc.sap.customer.application.general;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.customer.domain.port.CustomerImageStorePort;
import com.poc.sap.customer.domain.port.CustomerSapOutboundPort;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Use case de borrado del aggregate Customer (OVERVIEW.md §2): elimina imagen
 * actual y notifica a SAP (delete OData via el port general del Customer).
 */
@Service
public class DeleteCustomerUseCase {

    private static final String DOMAIN = "customer";

    private final CustomerImageStorePort imageStore;
    private final CustomerSapOutboundPort sapOutbound;
    private final SyncStateRepositoryPort stateRepo;
    private final SyncMetrics metrics;

    public DeleteCustomerUseCase(CustomerImageStorePort imageStore,
                                 CustomerSapOutboundPort sapOutbound,
                                 SyncStateRepositoryPort stateRepo,
                                 SyncMetrics metrics) {
        this.imageStore = imageStore;
        this.sapOutbound = sapOutbound;
        this.stateRepo = stateRepo;
        this.metrics = metrics;
    }

    public SyncState execute(String customerId, String payloadHash) {
        transition(customerId, payloadHash, null, SyncState.SENDING_SAP);
        var response = sapOutbound.send(customerId, payloadHash, null);
        if (response.isSuccess()) {
            imageStore.delete(customerId);
            transition(customerId, payloadHash, SyncState.SENDING_SAP, SyncState.SENT_SAP);
            return SyncState.SENT_SAP;
        }
        transition(customerId, payloadHash, SyncState.SENDING_SAP, SyncState.SAP_ERROR);
        return SyncState.SAP_ERROR;
    }

    private void transition(String entityId, String payloadHash,
                            SyncState from, SyncState to) {
        stateRepo.transition(DOMAIN, entityId, new SyncStateTransition(
                entityId, DOMAIN, from, to, "rest", payloadHash, Instant.now()));
        metrics.incrementState(DOMAIN, to.name());
    }
}
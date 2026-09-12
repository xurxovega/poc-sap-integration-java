package com.poc.sap.customer.application.banking;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.customer.domain.port.MandateSapOutboundPort;

import java.time.Instant;

/**
 * Baja (revocacion) de un mandato SEPA (sdd/customer/baja-mandato-sepa.md).
 * Forma parte de la feature BANKING. En S/4 un mandato no se borra: se cancela
 * ({@code SEPAMandateStatus = 3}) a traves de {@link MandateSapOutboundPort#revoke}.
 * Hasta la Fase 3.2 mandaba un payload bancario ficticio a una API inexistente
 * (auditoria B3).
 */
public class DeleteMandateUseCase {

    private static final String DOMAIN = "customer";
    private static final String STAGE = "banking";

    private final MandateSapOutboundPort sapPort;
    private final SyncStateRepositoryPort stateRepo;
    private final MetricsPort metrics;

    public DeleteMandateUseCase(MandateSapOutboundPort sapPort,
                                SyncStateRepositoryPort stateRepo,
                                MetricsPort metrics) {
        this.sapPort = sapPort;
        this.stateRepo = stateRepo;
        this.metrics = metrics;
    }

    public SyncState execute(String mandateId, String customerId, String payloadHash) {
        String entityId = customerId + ":" + "BANKING";
        beginCycle(entityId, payloadHash, SyncState.SENDING_SAP);
        var response = sapPort.revoke(mandateId, payloadHash);
        SyncState target = response.isSuccess() ? SyncState.SENT_SAP : SyncState.SAP_ERROR;
        transition(entityId, payloadHash, SyncState.SENDING_SAP, target);
        return target;
    }

    /** Abre un ciclo nuevo (sdd/common/maquina-de-estados.md R-3): legal desde cualquier estado previo. */
    private void beginCycle(String entityId, String payloadHash, SyncState entry) {
        stateRepo.beginCycle(DOMAIN, entityId, new SyncStateTransition(
                entityId, DOMAIN, null, entry, STAGE, payloadHash, Instant.now()));
        metrics.incrementState(DOMAIN, entry.name());
    }

    private void transition(String entityId, String payloadHash,
                            SyncState from, SyncState to) {
        stateRepo.transition(DOMAIN, entityId, new SyncStateTransition(
                entityId, DOMAIN, from, to, STAGE, payloadHash, Instant.now()));
        metrics.incrementState(DOMAIN, to.name());
    }
}
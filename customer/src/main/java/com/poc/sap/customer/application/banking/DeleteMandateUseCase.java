package com.poc.sap.customer.application.banking;

import com.poc.sap.common.application.SyncCycleRecorder;
import com.poc.sap.common.application.SyncCycleRecorder.Cycle;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.customer.domain.port.MandateSapOutboundPort;

import java.time.Clock;


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
    private final SyncCycleRecorder cycle;

    public DeleteMandateUseCase(MandateSapOutboundPort sapPort,
                                SyncStateRepositoryPort stateRepo,
                                MetricsPort metrics,
                                Clock clock) {
        this.sapPort = sapPort;
        this.stateRepo = stateRepo;
        this.metrics = metrics;
        this.cycle = new SyncCycleRecorder(DOMAIN, stateRepo, metrics, clock);
    }

    public SyncState execute(String mandateId, String customerId, String payloadHash) {
        String entityId = customerId + ":" + "BANKING";
        Cycle c = cycle.beginCycle(entityId, STAGE, payloadHash, SyncState.SENDING_SAP);
        var response = sapPort.revoke(mandateId, payloadHash);
        SyncState target = response.isSuccess() ? SyncState.SENT_SAP : SyncState.SAP_ERROR;
        cycle.advance(c, SyncState.SENDING_SAP, target,
                response.isSuccess() ? null : "HTTP " + response.httpStatus() + " revocando el mandato");
        return target;
    }
}
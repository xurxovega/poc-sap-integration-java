package com.poc.sap.customer.application.general;

import com.poc.sap.common.application.SyncCycleRecorder;
import com.poc.sap.common.application.SyncCycleRecorder.Cycle;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.customer.domain.CustomerValidations;
import com.poc.sap.customer.domain.port.CustomerLegacyRepositoryPort;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Optional;

/**
 * Use case de validacion aislada del aggregate Customer (todas las features).
 */
public class ValidateCustomerUseCase {

    private static final String DOMAIN = "customer";

    private final CustomerLegacyRepositoryPort legacyRepo;
    private final SyncStateRepositoryPort stateRepo;
    private final MetricsPort metrics;
    private final SyncCycleRecorder cycle;

    public ValidateCustomerUseCase(CustomerLegacyRepositoryPort legacyRepo,
                                   SyncStateRepositoryPort stateRepo,
                                   MetricsPort metrics,
                                   Clock clock) {
        this.legacyRepo = legacyRepo;
        this.stateRepo = stateRepo;
        this.metrics = metrics;
        this.cycle = new SyncCycleRecorder(DOMAIN, stateRepo, metrics, clock);
    }

    public SyncState execute(String entityId, String payloadHash) {
        return execute(entityId, payloadHash, EnumSet.allOf(com.poc.sap.customer.domain.CustomerFeature.class));
    }

    public SyncState execute(String entityId, String payloadHash,
                              java.util.Set<com.poc.sap.customer.domain.CustomerFeature> features) {
        Cycle c = cycle.beginCycle(entityId, "rest", payloadHash, SyncState.VALIDATING);
        var fetched = legacyRepo.fetch(entityId);
        if (fetched.isEmpty()) {
            cycle.advance(c, SyncState.VALIDATING, SyncState.ERROR, "no existe en el legacy");
            return SyncState.ERROR;
        }
        var r = CustomerValidations.validate(fetched.get(), features);
        SyncState target = r.valid() ? SyncState.VALID : SyncState.INVALID;
        cycle.advance(c, SyncState.VALIDATING, target,
                r.valid() ? null : String.join("; ", r.errors()));
        return target;
    }
}
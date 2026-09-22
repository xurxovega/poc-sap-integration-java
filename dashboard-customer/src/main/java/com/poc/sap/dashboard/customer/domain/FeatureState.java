package com.poc.sap.dashboard.customer.domain;

import com.poc.sap.common.domain.SyncState;

import java.time.Instant;

/**
 * Una linea del estado de sincronizacion (UI-001 H-2). Es lo que
 * {@code CustomerState.lastCycle.steps[]} materializa: feature, ultimo
 * estado, motivo si termino en error, hash, ciclo, instante. La feature
 * puede ser {@code null} cuando representa al agregado.
 */
public record FeatureState(
        String feature,
        SyncState state,
        String payloadHash,
        String cycleId,
        String detail,
        Instant at
) {
}

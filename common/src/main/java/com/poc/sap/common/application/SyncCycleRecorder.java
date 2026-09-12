package com.poc.sap.common.application;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;

import java.time.Instant;

/**
 * Registra el ciclo de una entidad en la maquina de estados y en las metricas
 * (sdd/common/maquina-de-estados.md R-3; plan Fase 7, auditoria A5). Sustituye
 * las 14 copias de {@code beginCycle()}/{@code transition()} que tenia cada use
 * case: la regla vive en un sitio y se prueba una vez.
 */
public final class SyncCycleRecorder {

    private final String domain;
    private final SyncStateRepositoryPort stateRepo;
    private final MetricsPort metrics;

    public SyncCycleRecorder(String domain, SyncStateRepositoryPort stateRepo, MetricsPort metrics) {
        this.domain = domain;
        this.stateRepo = stateRepo;
        this.metrics = metrics;
    }

    /** Abre un ciclo nuevo: legal desde cualquier estado previo; {@code entry} debe ser de entrada. */
    public SyncState beginCycle(String entityId, String origin, String payloadHash, SyncState entry) {
        SyncState to = stateRepo.beginCycle(domain, entityId, new SyncStateTransition(
                entityId, domain, null, entry, origin, payloadHash, Instant.now()));
        metrics.incrementState(domain, entry.name());
        return to;
    }

    /** Avanza dentro del ciclo abierto; el repositorio valida la transicion contra el estado almacenado. */
    public SyncState advance(String entityId, String origin, String payloadHash, SyncState from, SyncState to) {
        SyncState result = stateRepo.transition(domain, entityId, new SyncStateTransition(
                entityId, domain, from, to, origin, payloadHash, Instant.now()));
        metrics.incrementState(domain, to.name());
        return result;
    }
}

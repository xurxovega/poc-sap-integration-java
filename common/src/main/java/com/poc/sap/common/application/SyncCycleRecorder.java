package com.poc.sap.common.application;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;

import java.time.Clock;
import java.util.UUID;

/**
 * Registra el ciclo de una entidad en la maquina de estados y en las metricas
 * (sdd/common/maquina-de-estados.md R-3; plan Fase 7, auditoria A5). Sustituye
 * las 14 copias de {@code beginCycle()}/{@code transition()} que tenia cada use
 * case: la regla vive en un sitio y se prueba una vez.
 *
 * <p>Abrir un ciclo devuelve un {@link Cycle}, que se lleva de la mano hasta
 * cerrarlo. Asi el {@code cycleId}, el origen y el hash no pueden mezclarse entre
 * ciclos, y la linea del agregado y las de sus features comparten el mismo
 * identificador: es lo que permite reconstruir la traza de pasos de un envio.
 */
public final class SyncCycleRecorder {

    /**
     * Identidad de un ciclo abierto. Se propaga del agregado a sus lineas de
     * feature (sdd/common/maquina-de-estados.md §5).
     */
    public record Cycle(String entityId, String cycleId, String origin, String payloadHash) {}

    private final String domain;
    private final SyncStateRepositoryPort stateRepo;
    private final MetricsPort metrics;
    private final Clock clock;

    public SyncCycleRecorder(String domain, SyncStateRepositoryPort stateRepo, MetricsPort metrics, Clock clock) {
        this.domain = domain;
        this.stateRepo = stateRepo;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Abre un ciclo NUEVO: genera su identificador. {@code entry} debe ser un estado de entrada. */
    public Cycle beginCycle(String entityId, String origin, String payloadHash, SyncState entry) {
        return beginCycle(entityId, UUID.randomUUID().toString(), origin, payloadHash, entry);
    }

    /**
     * Abre la linea de un ciclo YA abierto: hereda el {@code cycleId} del agregado.
     * Legal desde cualquier estado previo de esa linea.
     */
    public Cycle beginCycle(String entityId, String cycleId, String origin, String payloadHash, SyncState entry) {
        Cycle cycle = new Cycle(entityId, cycleId, origin, payloadHash);
        stateRepo.beginCycle(domain, entityId, new SyncStateTransition(
                entityId, domain, null, entry, origin, payloadHash, clock.instant(), cycleId, null));
        metrics.incrementState(domain, entry.name());
        return cycle;
    }

    /** Avanza dentro del ciclo abierto; el repositorio valida la transicion contra el estado almacenado. */
    public SyncState advance(Cycle cycle, SyncState from, SyncState to) {
        return advance(cycle, from, to, null);
    }

    /**
     * Avanza dejando escrito el motivo. {@code detail} solo se rellena en
     * transiciones a un estado de error y nunca lleva el payload enviado
     * (sdd/common/maquina-de-estados.md R-9).
     */
    public SyncState advance(Cycle cycle, SyncState from, SyncState to, String detail) {
        SyncState result = stateRepo.transition(domain, cycle.entityId(), new SyncStateTransition(
                cycle.entityId(), domain, from, to, cycle.origin(), cycle.payloadHash(),
                clock.instant(), cycle.cycleId(), detail));
        metrics.incrementState(domain, to.name());
        return result;
    }
}

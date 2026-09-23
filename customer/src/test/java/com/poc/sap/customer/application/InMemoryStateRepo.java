package com.poc.sap.customer.application;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateMachine;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repositorio de estado en memoria que aplica la {@link SyncStateMachine} REAL.
 *
 * Sustituye al mock del puerto en los tests de use case: mockear un puerto que
 * valida invariantes esconde justo los fallos que importan (los seis defectos
 * del 2026-09-09 y el B1 de la auditoria vivieron detras de ese mock). Es
 * compartido por todos los tests de application (TEST-3 del backlog).
 */
public final class InMemoryStateRepo implements SyncStateRepositoryPort {

    /** Estados con los que puede terminar un ciclo, como en el adaptador Mongo. */
    private static final List<SyncState> CYCLE_END = List.of(
            SyncState.SENT_SAP, SyncState.SAP_ERROR, SyncState.INVALID,
            SyncState.COMMUNICATION_ERROR, SyncState.ERROR);

    private final SyncStateMachine machine = new SyncStateMachine();
    private final Map<String, List<SyncState>> byEntity = new HashMap<>();
    private final List<SyncStateTransition> written = new ArrayList<>();
    /** Como termino el ultimo ciclo de cada entidad y con que hash (idempotencia-y-dedupe R-1, R-6). */
    private final Map<String, SyncStateTransition> lastCycleEnd = new HashMap<>();

    /** Estados registrados para una entidad, en orden. */
    public List<SyncState> states(String entityId) {
        return byEntity.getOrDefault(entityId, List.of());
    }

    /** Transiciones escritas, en orden, con su ciclo y su motivo. */
    public List<SyncStateTransition> written() {
        return List.copyOf(written);
    }

    /** Deja a la entidad en un estado previo, como si viniera de un ciclo anterior. */
    public void seed(String entityId, SyncState state) {
        byEntity.computeIfAbsent(entityId, k -> new ArrayList<>()).add(state);
    }

    /** Deja escrito como termino el ultimo ciclo de la entidad (para el dedupe). */
    public void seedCycleEnd(String entityId, SyncState state, String payloadHash) {
        seed(entityId, state);
        lastCycleEnd.put(entityId, new SyncStateTransition(
                entityId, "customer", null, state, "cdc", payloadHash, null, null, null));
    }

    @Override
    public Optional<SyncState> currentState(String domain, String entityId) {
        List<SyncState> s = byEntity.get(entityId);
        return s == null || s.isEmpty() ? Optional.empty() : Optional.of(s.get(s.size() - 1));
    }

    @Override
    public SyncState beginCycle(String domain, String entityId, SyncStateTransition t) {
        SyncState to = machine.beginCycle(currentState(domain, entityId).orElse(null), t.to());
        return record(entityId, t, to);
    }

    @Override
    public SyncState transition(String domain, String entityId, SyncStateTransition t) {
        SyncState to = machine.advance(currentState(domain, entityId).orElse(null), t.to());
        return record(entityId, t, to);
    }

    private SyncState record(String entityId, SyncStateTransition t, SyncState to) {
        byEntity.computeIfAbsent(entityId, k -> new ArrayList<>()).add(to);
        written.add(t);
        if (CYCLE_END.contains(to)) {
            lastCycleEnd.put(entityId, t);
        }
        return to;
    }

    @Override
    public List<SyncStateTransition> history(String domain, String entityId) {
        return written.stream().filter(t -> t.entityId().equals(entityId)).toList();
    }

    @Override
    public List<SyncStateTransition> cycle(String domain, String cycleId) {
        return written.stream().filter(t -> cycleId != null && cycleId.equals(t.cycleId())).toList();
    }

    @Override
    public Optional<SyncStateTransition> lastTransition(String domain, String entityId) {
        return written.stream().filter(t -> t.entityId().equals(entityId)).reduce((a, b) -> b);
    }

    /**
     * Mismo criterio que el adaptador Mongo: solo deduplica si el ULTIMO ciclo
     * termino en SENT_SAP con este hash. Si termino en fallo, SAP tiene una mezcla
     * y hay que reenviar (auditoria 2026-09-18 N4).
     */
    @Override
    public boolean alreadySent(String domain, String entityId, String payloadHash) {
        SyncStateTransition end = lastCycleEnd.get(entityId);
        return payloadHash != null && end != null
                && end.to() == SyncState.SENT_SAP && payloadHash.equals(end.payloadHash());
    }
}

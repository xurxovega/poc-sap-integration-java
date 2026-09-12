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

    private final SyncStateMachine machine = new SyncStateMachine();
    private final Map<String, List<SyncState>> byEntity = new HashMap<>();
    /** Hash del ultimo SENT_SAP por entidad: lo que SAP tiene ahora (idempotencia-y-dedupe R-1). */
    private final Map<String, String> lastSentHash = new HashMap<>();

    /** Estados registrados para una entidad, en orden. */
    public List<SyncState> states(String entityId) {
        return byEntity.getOrDefault(entityId, List.of());
    }

    /** Deja a la entidad en un estado previo, como si viniera de un ciclo anterior. */
    public void seed(String entityId, SyncState state) {
        byEntity.computeIfAbsent(entityId, k -> new ArrayList<>()).add(state);
    }

    @Override
    public Optional<SyncState> currentState(String domain, String entityId) {
        List<SyncState> s = byEntity.get(entityId);
        return s == null || s.isEmpty() ? Optional.empty() : Optional.of(s.get(s.size() - 1));
    }

    @Override
    public SyncState beginCycle(String domain, String entityId, SyncStateTransition t) {
        SyncState to = machine.beginCycle(currentState(domain, entityId).orElse(null), t.to());
        byEntity.computeIfAbsent(entityId, k -> new ArrayList<>()).add(to);
        return to;
    }

    @Override
    public SyncState transition(String domain, String entityId, SyncStateTransition t) {
        SyncState to = machine.advance(currentState(domain, entityId).orElse(null), t.to());
        byEntity.computeIfAbsent(entityId, k -> new ArrayList<>()).add(to);
        if (to == SyncState.SENT_SAP) {
            lastSentHash.put(entityId, t.payloadHash());
        }
        return to;
    }

    @Override
    public List<SyncStateTransition> history(String domain, String entityId) {
        return List.of();
    }

    @Override
    public boolean alreadySent(String domain, String entityId, String payloadHash) {
        return payloadHash != null && payloadHash.equals(lastSentHash.get(entityId));
    }
}

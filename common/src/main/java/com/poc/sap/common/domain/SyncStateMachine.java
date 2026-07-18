package com.poc.sap.common.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.poc.sap.common.domain.SyncState.*;

/**
 * Maquina de estados de sincronizacion (SPEC.md §8).
 * Dominio-agnostica, reside en el shared kernel.
 *
 * <pre>
 * RECEIVED → FETCHING → VALIDATING → VALID | INVALID
 *                                     VALID → INDEXING → INDEXED
 *                                                     INDEXED → SENDING_SAP → SENT_SAP
 * Errores: ERROR, SAP_ERROR, COMMUNICATION_ERROR
 * </pre>
 */
public final class SyncStateMachine {

    private static final Map<SyncState, Set<SyncState>> TRANSITIONS = new EnumMap<>(SyncState.class);

    static {
        TRANSITIONS.put(RECEIVED, EnumSet.of(FETCHING, ERROR));
        TRANSITIONS.put(FETCHING, EnumSet.of(VALIDATING, ERROR, COMMUNICATION_ERROR));
        TRANSITIONS.put(VALIDATING, EnumSet.of(VALID, INVALID, ERROR));
        TRANSITIONS.put(VALID, EnumSet.of(INDEXING, ERROR));
        TRANSITIONS.put(INVALID, EnumSet.noneOf(SyncState.class));
        TRANSITIONS.put(INDEXING, EnumSet.of(INDEXED, ERROR));
        TRANSITIONS.put(INDEXED, EnumSet.of(SENDING_SAP, ERROR));
        TRANSITIONS.put(SENDING_SAP, EnumSet.of(SENT_SAP, SAP_ERROR, COMMUNICATION_ERROR));
        TRANSITIONS.put(SENT_SAP, EnumSet.noneOf(SyncState.class));
        TRANSITIONS.put(SAP_ERROR, EnumSet.of(SENDING_SAP, ERROR));
        TRANSITIONS.put(ERROR, EnumSet.of(RECEIVED));
        TRANSITIONS.put(COMMUNICATION_ERROR, EnumSet.of(FETCHING, SENDING_SAP, ERROR));
    }

    public boolean canTransition(SyncState from, SyncState to) {
        Set<SyncState> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    public SyncState transition(SyncState from, SyncState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException(
                "Transicion no permitida: " + from + " → " + to);
        }
        return to;
    }

    public Set<SyncState> nextStates(SyncState from) {
        Set<SyncState> allowed = TRANSITIONS.get(from);
        return allowed == null ? Set.of() : EnumSet.copyOf(allowed);
    }

    public boolean isTerminal(SyncState state) {
        Set<SyncState> allowed = TRANSITIONS.get(state);
        return allowed == null || allowed.isEmpty();
    }
}

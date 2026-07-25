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
 * (inicio) → RECEIVED → FETCHING → VALIDATING → VALID | INVALID
 *                                     VALID → INDEXING → INDEXED
 *                                                     INDEXED → SENDING_SAP → SENT_SAP
 * Errores: ERROR, SAP_ERROR, COMMUNICATION_ERROR
 * Re-sincronizacion: SENT_SAP → RECEIVED e INVALID → RECEIVED (un nuevo
 * evento de la misma entidad reabre el ciclo).
 * </pre>
 *
 * <p>Estados iniciales: una entidad sin historial puede entrar por
 * {@code RECEIVED} (pipeline de aggregate) o {@code VALIDATING} (pipeline
 * por feature, que arranca directamente en validacion).
 */
public final class SyncStateMachine {

    private static final Map<SyncState, Set<SyncState>> TRANSITIONS = new EnumMap<>(SyncState.class);

    /** Estados por los que puede arrancar una entidad sin historial previo. */
    private static final Set<SyncState> INITIAL_STATES = EnumSet.of(RECEIVED, VALIDATING);

    static {
        TRANSITIONS.put(RECEIVED, EnumSet.of(FETCHING, ERROR));
        TRANSITIONS.put(FETCHING, EnumSet.of(VALIDATING, ERROR, COMMUNICATION_ERROR));
        TRANSITIONS.put(VALIDATING, EnumSet.of(VALID, INVALID, ERROR));
        TRANSITIONS.put(VALID, EnumSet.of(INDEXING, ERROR));
        TRANSITIONS.put(INVALID, EnumSet.of(RECEIVED));
        TRANSITIONS.put(INDEXING, EnumSet.of(INDEXED, ERROR));
        TRANSITIONS.put(INDEXED, EnumSet.of(SENDING_SAP, ERROR));
        TRANSITIONS.put(SENDING_SAP, EnumSet.of(SENT_SAP, SAP_ERROR, INVALID, COMMUNICATION_ERROR));
        TRANSITIONS.put(SENT_SAP, EnumSet.of(RECEIVED));
        TRANSITIONS.put(SAP_ERROR, EnumSet.of(SENDING_SAP, ERROR));
        TRANSITIONS.put(ERROR, EnumSet.of(RECEIVED));
        TRANSITIONS.put(COMMUNICATION_ERROR, EnumSet.of(FETCHING, SENDING_SAP, ERROR));
    }

    public boolean canTransition(SyncState from, SyncState to) {
        if (from == null) {
            return INITIAL_STATES.contains(to);
        }
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

    /**
     * Terminal = fin de un ciclo de sincronizacion. {@code SENT_SAP} e
     * {@code INVALID} son terminales del ciclo aunque admitan re-entrada a
     * {@code RECEIVED} cuando llega un nuevo evento de la entidad.
     */
    public boolean isTerminal(SyncState state) {
        return state == SENT_SAP || state == INVALID;
    }
}

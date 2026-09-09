package com.poc.sap.common.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.poc.sap.common.domain.SyncState.*;

/**
 * Maquina de estados de sincronizacion (OVERVIEW.md §5).
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
        // VALID → SENT_SAP: atajo "sin cambios reales" — el snapshot re-leido del
        // legacy es identico a la imagen ya sincronizada; no hay nada que enviar.
        // SENDING_SAP: el pipeline por feature valida y envia sin indexar (la
        // imagen y el historico son del cliente agregado, no de cada feature).
        // INDEXING es el camino del pipeline agregado.
        TRANSITIONS.put(VALID, EnumSet.of(INDEXING, SENDING_SAP, SENT_SAP, ERROR));
        TRANSITIONS.put(INVALID, EnumSet.of(RECEIVED, VALIDATING));
        TRANSITIONS.put(INDEXING, EnumSet.of(INDEXED, ERROR));
        TRANSITIONS.put(INDEXED, EnumSet.of(SENDING_SAP, ERROR));
        TRANSITIONS.put(SENDING_SAP, EnumSet.of(SENT_SAP, SAP_ERROR, INVALID, COMMUNICATION_ERROR));
        // Re-entrada con un evento nuevo: el agregado vuelve a RECEIVED y una
        // linea de feature a VALIDATING, que es su estado de entrada. La
        // idempotencia la garantiza el dedupe por payloadHash, no el bloqueo de
        // la maquina (sdd/common/maquina-de-estados.md R-3, R-4).
        TRANSITIONS.put(SENT_SAP, EnumSet.of(RECEIVED, VALIDATING));
        TRANSITIONS.put(SAP_ERROR, EnumSet.of(SENDING_SAP, VALIDATING, ERROR));
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

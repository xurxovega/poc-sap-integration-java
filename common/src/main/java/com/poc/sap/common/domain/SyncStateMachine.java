package com.poc.sap.common.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.poc.sap.common.domain.SyncState.COMMUNICATION_ERROR;
import static com.poc.sap.common.domain.SyncState.ERROR;
import static com.poc.sap.common.domain.SyncState.FETCHING;
import static com.poc.sap.common.domain.SyncState.INDEXED;
import static com.poc.sap.common.domain.SyncState.INDEXING;
import static com.poc.sap.common.domain.SyncState.INVALID;
import static com.poc.sap.common.domain.SyncState.RECEIVED;
import static com.poc.sap.common.domain.SyncState.SAP_ERROR;
import static com.poc.sap.common.domain.SyncState.SENDING_SAP;
import static com.poc.sap.common.domain.SyncState.SENT_SAP;
import static com.poc.sap.common.domain.SyncState.VALID;
import static com.poc.sap.common.domain.SyncState.VALIDATING;

/**
 * Maquina de estados de sincronizacion (OVERVIEW.md §5;
 * sdd/common/maquina-de-estados.md).
 *
 * Distingue dos intenciones que antes compartian una sola tabla:
 * <ul>
 *   <li>{@link #beginCycle}: abrir un ciclo nuevo porque llega un evento. Legal
 *       desde CUALQUIER estado actual, por cualquier estado de {@link #ENTRY_STATES}.
 *       La idempotencia la garantiza el dedupe por payloadHash, no la maquina.</li>
 *   <li>{@link #advance}: avanzar dentro del ciclo abierto, solo por la tabla.</li>
 * </ul>
 * Mientras ambas fueron la misma operacion, cada camino nuevo (feature, baja,
 * error, proceso interrumpido) descubria "una fila que faltaba" y fallaba igual:
 * IllegalStateException, tres reintentos, DLT. Tres veces (fingerprint
 * sync-state:reentrada-no-permitida). Con la apertura separada ya no puede faltar.
 */
public final class SyncStateMachine {

    /**
     * Puntos de entrada reales de los pipelines: RECEIVED (agregado), VALIDATING
     * (linea de feature), SENDING_SAP (baja), INDEXING (indexacion). Abrir ciclo
     * por cualquier otro estado es un error de programacion, no de datos.
     */
    public static final Set<SyncState> ENTRY_STATES =
            Collections.unmodifiableSet(EnumSet.of(RECEIVED, VALIDATING, SENDING_SAP, INDEXING));

    /** Estados en los que un ciclo esta a medias; los demas lo cierran. */
    private static final Set<SyncState> IN_FLIGHT =
            EnumSet.of(RECEIVED, FETCHING, VALIDATING, VALID, INDEXING, INDEXED, SENDING_SAP);

    private static final Map<SyncState, Set<SyncState>> TRANSITIONS = new EnumMap<>(SyncState.class);

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
        // ERROR desde SENDING_SAP: un fallo de infraestructura durante el envio no
        // puede dejar la entidad colgada (auditoria B12/C2).
        TRANSITIONS.put(SENDING_SAP, EnumSet.of(SENT_SAP, SAP_ERROR, INVALID, COMMUNICATION_ERROR, ERROR));
        // Las re-entradas siguen en la tabla por compatibilidad con canTransition,
        // pero la operacion que las gobierna es beginCycle.
        TRANSITIONS.put(SENT_SAP, EnumSet.of(RECEIVED, VALIDATING));
        TRANSITIONS.put(SAP_ERROR, EnumSet.of(SENDING_SAP, VALIDATING, ERROR));
        TRANSITIONS.put(ERROR, EnumSet.of(RECEIVED));
        TRANSITIONS.put(COMMUNICATION_ERROR, EnumSet.of(FETCHING, SENDING_SAP, ERROR));
    }

    /**
     * Abre un ciclo nuevo. Legal desde cualquier {@code current} (incluido
     * {@code null}, sin historial); solo exige que {@code entry} sea un estado de
     * entrada. Quien llama puede consultar {@link #isInFlight} sobre
     * {@code current} para registrar que el ciclo anterior quedo a medias.
     *
     * @throws IllegalStateException si {@code entry} no es un estado de entrada
     */
    public SyncState beginCycle(SyncState current, SyncState entry) {
        if (entry == null || !ENTRY_STATES.contains(entry)) {
            throw new IllegalStateException(
                    "Estado de entrada no permitido: " + entry + " (permitidos: " + ENTRY_STATES + ")");
        }
        return entry;
    }

    /** {@code true} si el estado corresponde a un ciclo a medias. */
    public boolean isInFlight(SyncState state) {
        return state != null && IN_FLIGHT.contains(state);
    }

    /**
     * Avanza dentro del ciclo abierto.
     *
     * @throws IllegalStateException si no hay ciclo abierto ({@code from == null})
     *                               o la transicion no esta en la tabla
     */
    public SyncState advance(SyncState from, SyncState to) {
        if (from == null) {
            throw new IllegalStateException(
                    "No hay ciclo abierto para avanzar a " + to + ": usa beginCycle");
        }
        Set<SyncState> allowed = TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new IllegalStateException("Transicion no permitida: " + from + " → " + to);
        }
        return to;
    }

    public boolean canTransition(SyncState from, SyncState to) {
        if (from == null) {
            return ENTRY_STATES.contains(to);
        }
        Set<SyncState> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * Compatibilidad: sin historial abre ciclo, con historial avanza. Los use
     * cases deben usar {@link #beginCycle} y {@link #advance} explicitamente.
     */
    public SyncState transition(SyncState from, SyncState to) {
        return from == null ? beginCycle(null, to) : advance(from, to);
    }

    public Set<SyncState> nextStates(SyncState from) {
        Set<SyncState> allowed = TRANSITIONS.get(from);
        return allowed == null ? Set.of() : EnumSet.copyOf(allowed);
    }

    /**
     * Terminal = fin de un ciclo de sincronizacion. {@code SENT_SAP} e
     * {@code INVALID} son terminales del ciclo aunque admitan abrir uno nuevo
     * cuando llega otro evento de la entidad.
     */
    public boolean isTerminal(SyncState state) {
        return state == SENT_SAP || state == INVALID;
    }
}

package com.poc.sap.common.domain.port;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateTransition;

import java.util.Optional;

/**
 * Puerto de persistencia del estado de sincronizacion (SPEC.md §8).
 * Permite consultar y avanzar la maquina de estados por entidad.
 */
public interface SyncStateRepositoryPort {

    /**
     * Consulta el estado actual de una entidad de un dominio.
     */
    Optional<SyncState> currentState(String domain, String entityId);

    /**
     * Persiste una transicion de estado y devuelve el nuevo estado.
     * Lanza IllegalStateException si la transicion no es permitida.
     */
    SyncState transition(String domain, String entityId, SyncStateTransition transition);

    /**
     * Historial de transiciones de una entidad (para auditoria/trazabilidad).
     */
    java.util.List<SyncStateTransition> history(String domain, String entityId);

    /**
     * Dedupe de idempotencia: true si ya existe una transicion SENT_SAP de
     * esta entidad con el mismo payloadHash (el mensaje ya fue procesado y
     * enviado a SAP; un reintento no debe duplicar efectos).
     */
    boolean alreadySent(String domain, String entityId, String payloadHash);
}

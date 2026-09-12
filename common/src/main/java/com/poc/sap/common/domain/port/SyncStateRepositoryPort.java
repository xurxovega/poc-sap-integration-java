package com.poc.sap.common.domain.port;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateTransition;

import java.util.Optional;

/**
 * Puerto de persistencia del estado de sincronizacion (OVERVIEW.md §5).
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
     * Abre un ciclo nuevo para la entidad porque llega un evento. Legal desde
     * cualquier estado almacenado (incluido ninguno); {@code transition.to()}
     * debe ser un estado de entrada. Si el ciclo anterior quedo a medias, la
     * implementacion lo registra (sdd/common/maquina-de-estados.md R-3, R-4).
     *
     * @return el estado de entrada registrado
     * @throws IllegalStateException si {@code to} no es un estado de entrada
     */
    SyncState beginCycle(String domain, String entityId, SyncStateTransition transition);

    /**
     * Historial de transiciones de una entidad (para auditoria/trazabilidad).
     */
    java.util.List<SyncStateTransition> history(String domain, String entityId);

    /**
     * Dedupe de idempotencia: true si el <b>ultimo</b> SENT_SAP de esta entidad
     * lleva el mismo payloadHash, es decir, si lo que SAP tiene ahora es
     * exactamente este payload (sdd/common/idempotencia-y-dedupe.md R-1). Un
     * SENT_SAP anterior con ese hash no cuenta: SAP ya tiene otra cosa.
     */
    boolean alreadySent(String domain, String entityId, String payloadHash);
}

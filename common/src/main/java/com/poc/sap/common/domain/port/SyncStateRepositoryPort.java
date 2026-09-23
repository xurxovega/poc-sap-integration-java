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
     *
     * @throws IllegalStateException si la transicion no es permitida por la
     *         maquina: error de programacion, no reintentable
     * @throws com.poc.sap.common.domain.ConcurrentTransitionException si el estado
     *         declarado en {@code transition.from()} ya no es el real, o si la
     *         cabecera pertenece a otro ciclo (fencing por {@code cycleId}). Es
     *         transitoria: se reintenta releyendo el estado
     *         (sdd/common/maquina-de-estados.md R-7, R-8)
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
     * Todas las transiciones de un ciclo, de todas sus lineas (agregado y
     * features), en orden. Es la traza de pasos de un envio: las lineas de feature
     * son entityId distintos ({@code C1:ADDRESS}), asi que {@link #history} no las
     * cruza (sdd/common/maquina-de-estados.md §5).
     */
    java.util.List<SyncStateTransition> cycle(String domain, String cycleId);

    /**
     * Ultima transicion de una linea, sin traerse el historial entero
     * (auditoria 2026-09-18 N8).
     */
    Optional<SyncStateTransition> lastTransition(String domain, String entityId);

    /**
     * Dedupe de idempotencia: true si el <b>ultimo</b> ciclo de esta entidad
     * termino en SENT_SAP con este mismo payloadHash, es decir, si lo que SAP tiene
     * ahora es exactamente este payload (sdd/common/idempotencia-y-dedupe.md R-1,
     * R-6). Un SENT_SAP anterior con ese hash no cuenta: SAP ya tiene otra cosa. Y
     * si el ultimo ciclo termino en fallo (parcial o no), tampoco: SAP tiene una
     * mezcla y hay que reenviar (auditoria 2026-09-18 N4).
     */
    boolean alreadySent(String domain, String entityId, String payloadHash);
}

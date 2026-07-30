package com.poc.sap.common.domain.port;

/**
 * Puerto de indexacion del historico (SPEC.md).
 * Elasticsearch en la implementacion de referencia.
 *
 * @param <E> tipo de la entidad del dominio
 */
public interface HistoryIndexerPort<E> {

    /**
     * Version historizada de la entidad con sus metadatos de sincronizacion.
     *
     * @param payloadHash hash del evento que origino esta version
     * @param timestamp   instante de indexacion (momento del envio a SAP)
     * @param entity      snapshot completo enviado
     */
    record Snapshot<E>(String payloadHash, java.time.Instant timestamp, E entity) {}

    /**
     * Indexa una version de la entidad en el historico.
     */
    void index(String entityId, E entity, String payloadHash);

    /**
     * Recupera el historico de versiones de una entidad (solo snapshots).
     */
    java.util.List<E> history(String entityId);

    /**
     * Recupera el historico de versiones con metadatos (hash + timestamp),
     * ordenado de mas reciente a mas antiguo.
     */
    java.util.List<Snapshot<E>> snapshots(String entityId);
}

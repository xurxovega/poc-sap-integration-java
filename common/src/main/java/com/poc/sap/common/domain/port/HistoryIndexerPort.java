package com.poc.sap.common.domain.port;

/**
 * Puerto de indexacion del historico (SPEC.md).
 * Elasticsearch en la implementacion de referencia.
 *
 * @param <E> tipo de la entidad del dominio
 */
public interface HistoryIndexerPort<E> {

    /**
     * Indexa una version de la entidad en el historico.
     */
    void index(String entityId, E entity, String payloadHash);

    /**
     * Recupera el historico de versiones de una entidad.
     */
    java.util.List<E> history(String entityId);
}

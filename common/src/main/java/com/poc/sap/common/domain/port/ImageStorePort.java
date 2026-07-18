package com.poc.sap.common.domain.port;

/**
 * Puerto de almacenamiento de la imagen actual de la entidad (SPEC.md).
 * MongoDB en la implementacion de referencia.
 *
 * @param <E> tipo de la entidad del dominio
 */
public interface ImageStorePort<E> {

    /**
     * Guarda (upsert) la imagen actual de la entidad.
     */
    void save(String entityId, E entity);

    /**
     * Recupera la imagen actual de la entidad.
     */
    java.util.Optional<E> find(String entityId);

    /**
     * Elimina la imagen actual (para operaciones delete).
     */
    void delete(String entityId);
}

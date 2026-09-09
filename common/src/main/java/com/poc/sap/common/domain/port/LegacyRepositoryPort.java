package com.poc.sap.common.domain.port;

import java.util.Optional;

/**
 * Puerto de lectura del repositorio legacy (OVERVIEW.md §4).
 * SQL Server (customer) / PostgreSQL (article) en la implementacion de referencia.
 *
 * @param <E> tipo de la entidad del dominio
 */
public interface LegacyRepositoryPort<E> {

    /**
     * Recupera la entidad legacy por identificador.
     */
    Optional<E> fetch(String entityId);
}

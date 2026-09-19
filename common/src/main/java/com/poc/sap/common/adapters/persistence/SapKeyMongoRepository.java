package com.poc.sap.common.adapters.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data repo de {@link SapKeyDoc} (TECH.md §7). */
public interface SapKeyMongoRepository extends MongoRepository<SapKeyDoc, String> {

    /** Baja de la entidad: todas sus claves, de todas las features. */
    void deleteByDomainAndEntityId(String domain, String entityId);
}

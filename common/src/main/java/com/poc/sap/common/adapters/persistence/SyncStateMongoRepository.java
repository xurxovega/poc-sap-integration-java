package com.poc.sap.common.adapters.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SyncStateMongoRepository extends MongoRepository<SyncStateDoc, String> {
    List<SyncStateDoc> findByDomainAndEntityIdOrderByTimestampDesc(String domain, String entityId);
    List<SyncStateDoc> findByDomainAndEntityIdOrderByTimestampAsc(String domain, String entityId);
}
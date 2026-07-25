package com.poc.sap.common.adapters.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SyncStateMongoRepository extends MongoRepository<SyncStateDoc, String> {
    List<SyncStateDoc> findByDomainAndEntityIdOrderByTimestampDesc(String domain, String entityId);
    List<SyncStateDoc> findByDomainAndEntityIdOrderByTimestampAsc(String domain, String entityId);
    Optional<SyncStateDoc> findFirstByDomainAndEntityIdOrderByTimestampDesc(String domain, String entityId);
    boolean existsByDomainAndEntityIdAndPayloadHashAndStateCode(String domain, String entityId,
                                                                String payloadHash, int stateCode);
}

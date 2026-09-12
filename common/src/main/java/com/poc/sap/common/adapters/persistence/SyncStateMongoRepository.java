package com.poc.sap.common.adapters.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repo de {@link SyncStateDoc} (TECH.md §7).
 *
 * El orden canonico es por {@code seq} y, como desempate para documentos
 * anteriores a la secuencia (que no la tienen), por {@code timestamp}. Los
 * metodos ordenados solo por timestamp se conservan para lecturas de
 * diagnostico; el estado actual NO debe resolverse con ellos (auditoria B11).
 */
public interface SyncStateMongoRepository extends MongoRepository<SyncStateDoc, String> {

    Optional<SyncStateDoc> findFirstByDomainAndEntityIdOrderBySeqDescTimestampDesc(String domain, String entityId);

    List<SyncStateDoc> findByDomainAndEntityIdOrderBySeqAscTimestampAsc(String domain, String entityId);

    List<SyncStateDoc> findByDomainAndEntityIdOrderByTimestampDesc(String domain, String entityId);

    List<SyncStateDoc> findByDomainAndEntityIdOrderByTimestampAsc(String domain, String entityId);

    Optional<SyncStateDoc> findFirstByDomainAndEntityIdOrderByTimestampDesc(String domain, String entityId);

    boolean existsByDomainAndEntityIdAndPayloadHashAndStateCode(String domain, String entityId,
                                                                String payloadHash, int stateCode);
}

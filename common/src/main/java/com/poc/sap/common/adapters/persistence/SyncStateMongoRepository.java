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

    /** Ultima transicion a un estado concreto (p. ej. el ultimo SENT_SAP), por secuencia. */
    Optional<SyncStateDoc> findFirstByDomainAndEntityIdAndStateCodeOrderBySeqDescTimestampDesc(
            String domain, String entityId, int stateCode);

    /**
     * Ultima transicion a cualquiera de los estados indicados. Con los estados
     * finales de ciclo responde "como termino el ultimo ciclo de esta entidad",
     * que es lo que el dedupe necesita saber (idempotencia-y-dedupe R-6).
     */
    Optional<SyncStateDoc> findFirstByDomainAndEntityIdAndStateCodeInOrderBySeqDescTimestampDesc(
            String domain, String entityId, java.util.Collection<Integer> stateCodes);

    /**
     * Todas las transiciones de un ciclo, de todas sus lineas (agregado y
     * features). Es la traza de pasos de un envio y se sirve por
     * {@code dom_cycle_idx}.
     */
    List<SyncStateDoc> findByDomainAndCycleIdOrderBySeqAscTimestampAsc(String domain, String cycleId);
}

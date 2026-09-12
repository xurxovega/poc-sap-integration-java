package com.poc.sap.common.adapters.persistence;

import com.poc.sap.common.domain.ConcurrentTransitionException;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateMachine;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Adaptador Mongo del estado de sincronizacion (OVERVIEW.md §5; TECH.md §7;
 * sdd/common/maquina-de-estados.md).
 *
 * <p>Calcula el estado actual leyendo la ultima transicion por {@code seq} y
 * aplica la maquina REAL: un use case no puede saltarse la secuencia declarando
 * un {@code from} conveniente. Distingue abrir ciclo ({@link #beginCycle}) de
 * avanzar ({@link #transition}).
 *
 * <p>Version optimista: cada escritura lleva {@code seq = ultima + 1} y el indice
 * unico {@code dom_ent_seq_uk} hace que, si otra instancia escribio antes, la
 * nuestra falle con {@link DuplicateKeyException}, traducida a
 * {@link ConcurrentTransitionException}. Nunca se pisa el estado en silencio.
 */
@Repository
public class MongoSyncStateRepository implements SyncStateRepositoryPort {

    private static final Logger log = LoggerFactory.getLogger(MongoSyncStateRepository.class);

    private final SyncStateMongoRepository mongo;
    private final SyncStateMachine machine = new SyncStateMachine();

    public MongoSyncStateRepository(SyncStateMongoRepository mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<SyncState> currentState(String domain, String entityId) {
        return head(domain, entityId).map(SyncStateDoc::stateCode).map(SyncState::ofCode);
    }

    @Override
    public SyncState beginCycle(String domain, String entityId, SyncStateTransition t) {
        Optional<SyncStateDoc> head = head(domain, entityId);
        SyncState current = head.map(SyncStateDoc::stateCode).map(SyncState::ofCode).orElse(null);
        SyncState to = machine.beginCycle(current, t.to());
        if (machine.isInFlight(current)) {
            // El proceso anterior murio a mitad (OPS-1). No se bloquea: se registra.
            log.warn("Ciclo anterior en vuelo ({}) abandonado por evento nuevo domain={} entityId={}",
                    current, domain, entityId);
        }
        return persist(domain, entityId, t, to, nextSeq(head));
    }

    @Override
    public SyncState transition(String domain, String entityId, SyncStateTransition t) {
        Optional<SyncStateDoc> head = head(domain, entityId);
        SyncState from = head.map(SyncStateDoc::stateCode).map(SyncState::ofCode).orElse(null);
        SyncState to = machine.advance(from, t.to());
        return persist(domain, entityId, t, to, nextSeq(head));
    }

    @Override
    public List<SyncStateTransition> history(String domain, String entityId) {
        return mongo.findByDomainAndEntityIdOrderBySeqAscTimestampAsc(domain, entityId).stream()
                .map(SyncStateDoc::toTransition)
                .toList();
    }

    @Override
    public boolean alreadySent(String domain, String entityId, String payloadHash) {
        if (payloadHash == null || payloadHash.isBlank()) {
            return false;
        }
        return mongo.existsByDomainAndEntityIdAndPayloadHashAndStateCode(
                domain, entityId, payloadHash, SyncState.SENT_SAP.code());
    }

    private Optional<SyncStateDoc> head(String domain, String entityId) {
        return mongo.findFirstByDomainAndEntityIdOrderBySeqDescTimestampDesc(domain, entityId);
    }

    private static long nextSeq(Optional<SyncStateDoc> head) {
        return head.map(SyncStateDoc::seq).orElse(0L) + 1;
    }

    private SyncState persist(String domain, String entityId, SyncStateTransition t,
                              SyncState to, long seq) {
        try {
            mongo.save(SyncStateDoc.from(domain, entityId, t, to.code(), seq));
        } catch (DuplicateKeyException e) {
            throw new ConcurrentTransitionException(domain, entityId, seq, e);
        }
        return to;
    }
}

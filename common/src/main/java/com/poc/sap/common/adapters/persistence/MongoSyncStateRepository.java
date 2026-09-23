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

    /** Estados con los que puede terminar un ciclo: son los que responden "como acabo". */
    private static final List<Integer> CYCLE_END_CODES = List.of(
            SyncState.SENT_SAP.code(), SyncState.SAP_ERROR.code(), SyncState.INVALID.code(),
            SyncState.COMMUNICATION_ERROR.code(), SyncState.ERROR.code());

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

    /**
     * Avanza dentro del ciclo abierto. Antes de aplicar la maquina hace dos
     * comprobaciones, y el ORDEN importa: si se dejaran para despues,
     * {@code machine.advance} lanzaria {@link IllegalStateException} primero y una
     * colision entre instancias seria indistinguible de un error de programacion
     * (auditoria 2026-09-18 N2, 2B-2).
     *
     * <ol>
     *   <li>lo declarado frente a lo real: si {@code t.from()} no es el estado de
     *       la cabecera, otra instancia la movio (R-7);</li>
     *   <li>fencing por ciclo: aunque el estado coincida, la cabecera puede
     *       pertenecer a otro ciclo (R-8).</li>
     * </ol>
     *
     * <p>{@code from == null} significa "no compruebes" (ninguna de las dos):
     * cerrar en ERROR tras un fallo no puede fallar a su vez por una colision, o el
     * estado se quedaria sin cerrar.
     */
    @Override
    public SyncState transition(String domain, String entityId, SyncStateTransition t) {
        Optional<SyncStateDoc> head = head(domain, entityId);
        SyncState from = head.map(SyncStateDoc::stateCode).map(SyncState::ofCode).orElse(null);

        if (t.from() != null) {
            if (from != t.from()) {
                throw ConcurrentTransitionException.staleHead(domain, entityId, t.from(), from, nextSeq(head));
            }
            String headCycle = head.map(SyncStateDoc::getCycleId).orElse(null);
            if (t.cycleId() != null && headCycle != null && !t.cycleId().equals(headCycle)) {
                throw ConcurrentTransitionException.foreignCycle(domain, entityId, t.cycleId(), headCycle, nextSeq(head));
            }
        }

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
    public List<SyncStateTransition> cycle(String domain, String cycleId) {
        if (cycleId == null || cycleId.isBlank()) {
            return List.of();
        }
        return mongo.findByDomainAndCycleIdOrderBySeqAscTimestampAsc(domain, cycleId).stream()
                .map(SyncStateDoc::toTransition)
                .toList();
    }

    @Override
    public Optional<SyncStateTransition> lastTransition(String domain, String entityId) {
        return head(domain, entityId).map(SyncStateDoc::toTransition);
    }

    /**
     * Dedupe contra el <b>ultimo estado final</b> del agregado, no contra
     * cualquier {@code SENT_SAP} del historico (sdd/common/idempotencia-y-dedupe.md
     * R-1 y R-6):
     *
     * <ul>
     *   <li>con la secuencia A -&gt; B -&gt; A, el tercer evento se reenvia porque
     *       SAP tiene B (auditoria A1);</li>
     *   <li>si el ultimo ciclo termino en fallo, SAP tiene una MEZCLA aunque un
     *       SENT_SAP anterior lleve este hash: tambien se reenvia (auditoria
     *       2026-09-18 N4, 2A-5).</li>
     * </ul>
     */
    @Override
    public boolean alreadySent(String domain, String entityId, String payloadHash) {
        if (payloadHash == null || payloadHash.isBlank()) {
            return false;
        }
        return mongo.findFirstByDomainAndEntityIdAndStateCodeInOrderBySeqDescTimestampDesc(
                        domain, entityId, CYCLE_END_CODES)
                .filter(d -> d.stateCode() == SyncState.SENT_SAP.code())
                .map(SyncStateDoc::getPayloadHash)
                .filter(payloadHash::equals)
                .isPresent();
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
            throw ConcurrentTransitionException.duplicateSeq(domain, entityId, seq, e);
        }
        return to;
    }
}

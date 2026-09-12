package com.poc.sap.common.adapters.persistence;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import org.springframework.dao.DuplicateKeyException;
import com.poc.sap.common.domain.ConcurrentTransitionException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;

/**
 * Test unit del {@link MongoSyncStateRepository} (OVERVIEW.md §5; TECH.md §7).
 * Mockea el Spring Data Mongo repo para no levantar Testcontainers.
 */
@ExtendWith(MockitoExtension.class)
class MongoSyncStateRepositoryTest {

    @Mock SyncStateMongoRepository mongo;
    private SyncStateRepositoryPort repo;

    @BeforeEach
    void setUp() {
        repo = new MongoSyncStateRepository(mongo);
    }

    private SyncStateTransition transition(SyncState to, Instant ts) {
        return new SyncStateTransition(
                "A-1", "article",
                SyncState.INDEXED, to,
                "cdc", "h-1", ts);
    }

    @Test
    void currentStateEmptyWhenNoHistory() {
        when(mongo.findFirstByDomainAndEntityIdOrderBySeqDescTimestampDesc("article", "A-1"))
                .thenReturn(Optional.empty());

        Optional<SyncState> current = repo.currentState("article", "A-1");

        assertThat(current).isEmpty();
    }

    @Test
    void currentStateResolvesToLatestState() {
        SyncStateTransition t = transition(SyncState.INDEXED, Instant.parse("2026-01-01T00:00:00Z"));
        SyncStateDoc doc = SyncStateDoc.from("article", "A-1", t, SyncState.INDEXED.code());
        when(mongo.findFirstByDomainAndEntityIdOrderBySeqDescTimestampDesc("article", "A-1"))
                .thenReturn(Optional.of(doc));

        Optional<SyncState> current = repo.currentState("article", "A-1");

        assertThat(current).contains(SyncState.INDEXED);
    }

    /**
     * Sin historial, avanzar no tiene sentido: hay que abrir ciclo. Es la regla que
     * separa las dos intenciones (sdd/common/maquina-de-estados.md §3).
     */
    @Test
    void advanceWithoutOpenCycleIsRejected() {
        when(mongo.findFirstByDomainAndEntityIdOrderBySeqDescTimestampDesc("article", "A-1"))
                .thenReturn(Optional.empty());
        SyncStateTransition t = new SyncStateTransition(
                "A-1", "article", null, SyncState.RECEIVED,
                "cdc", "h-1", Instant.parse("2026-01-01T00:00:00Z"));

        assertThatThrownBy(() -> repo.transition("article", "A-1", t))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("beginCycle");
    }

    @Test
    void firstCycleOfNewEntityOpensThroughBeginCycle() {
        when(mongo.findFirstByDomainAndEntityIdOrderBySeqDescTimestampDesc("article", "A-1"))
                .thenReturn(Optional.empty());

        SyncStateTransition t = new SyncStateTransition(
                "A-1", "article", null, SyncState.RECEIVED,
                "cdc", "h-1", Instant.parse("2026-01-01T00:00:00Z"));

        SyncState result = repo.beginCycle("article", "A-1", t);

        assertThat(result).isEqualTo(SyncState.RECEIVED);
        ArgumentCaptor<SyncStateDoc> save = ArgumentCaptor.forClass(SyncStateDoc.class);
        verify(mongo).save(save.capture());
        assertThat(save.getValue().stateCode()).isEqualTo(SyncState.RECEIVED.code());
    }

    @Test
    void transitionFromExistingStatePersistsAndReturns() {
        SyncStateTransition prev = transition(SyncState.INDEXED, Instant.parse("2026-01-01T00:00:00Z"));
        when(mongo.findFirstByDomainAndEntityIdOrderBySeqDescTimestampDesc("article", "A-1"))
                .thenReturn(Optional.of(SyncStateDoc.from("article", "A-1", prev, SyncState.INDEXED.code())));

        SyncStateTransition t = transition(SyncState.SENDING_SAP,
                Instant.parse("2026-01-02T00:00:00Z"));

        SyncState result = repo.transition("article", "A-1", t);

        assertThat(result).isEqualTo(SyncState.SENDING_SAP);
        ArgumentCaptor<SyncStateDoc> save = ArgumentCaptor.forClass(SyncStateDoc.class);
        verify(mongo).save(save.capture());
        assertThat(save.getValue().stateCode()).isEqualTo(SyncState.SENDING_SAP.code());
    }

    @Test
    void resyncFromSentSapIsAllowed() {
        SyncStateTransition prev = transition(SyncState.SENT_SAP, Instant.parse("2026-01-01T00:00:00Z"));
        when(mongo.findFirstByDomainAndEntityIdOrderBySeqDescTimestampDesc("article", "A-1"))
                .thenReturn(Optional.of(SyncStateDoc.from("article", "A-1", prev, SyncState.SENT_SAP.code())));

        SyncStateTransition t = new SyncStateTransition(
                "A-1", "article", SyncState.SENT_SAP, SyncState.RECEIVED,
                "cdc", "h-2", Instant.parse("2026-01-02T00:00:00Z"));

        assertThat(repo.transition("article", "A-1", t)).isEqualTo(SyncState.RECEIVED);
    }

    @Test
    void historyPreservesRepositoryOrder() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-01-02T00:00:00Z");
        SyncStateDoc d1 = SyncStateDoc.from("article", "A-1",
                transition(SyncState.RECEIVED, t1), SyncState.RECEIVED.code());
        SyncStateDoc d2 = SyncStateDoc.from("article", "A-1",
                transition(SyncState.INDEXED, t2), SyncState.INDEXED.code());
        when(mongo.findByDomainAndEntityIdOrderBySeqAscTimestampAsc("article", "A-1"))
                .thenReturn(List.of(d1, d2));

        List<SyncStateTransition> history = repo.history("article", "A-1");

        assertThat(history).extracting(SyncStateTransition::timestamp)
                .containsExactly(t1, t2);
    }

    /**
     * idempotencia-y-dedupe AC-1 (auditoria A1): el dedupe mira el ULTIMO SENT_SAP.
     * Secuencia A -> B -> A: el tercer evento (hash A) NO esta deduplicado porque
     * SAP tiene B; solo lo esta si el ultimo envio fue exactamente A.
     */
    @Test
    void alreadySentOnlyMatchesTheLatestSentSap() {
        SyncStateTransition sentB = new SyncStateTransition("A-1", "article",
                SyncState.SENDING_SAP, SyncState.SENT_SAP, "cdc", "hash-B", Instant.now());
        when(mongo.findFirstByDomainAndEntityIdAndStateCodeOrderBySeqDescTimestampDesc(
                "article", "A-1", SyncState.SENT_SAP.code()))
                .thenReturn(Optional.of(SyncStateDoc.from("article", "A-1", sentB, SyncState.SENT_SAP.code(), 9L)));

        assertThat(repo.alreadySent("article", "A-1", "hash-A")).isFalse();
        assertThat(repo.alreadySent("article", "A-1", "hash-B")).isTrue();
        assertThat(repo.alreadySent("article", "A-1", null)).isFalse();
        assertThat(repo.alreadySent("article", "A-1", "")).isFalse();
        verify(mongo, never()).existsByDomainAndEntityIdAndPayloadHashAndStateCode(any(), any(), any(), anyInt());
    }

    /**
     * AC-11 (sdd/common/maquina-de-estados.md): el estado actual se resuelve por
     * secuencia, no por timestamp. Las transiciones se escriben en rafaga y el
     * orden por milisegundos empata (auditoria B11/C1).
     */
    @Test
    void currentStateIsResolvedBySequenceNotTimestamp() {
        Instant same = Instant.parse("2026-01-01T00:00:00.000Z");
        SyncStateDoc second = SyncStateDoc.from("article", "A-1",
                transition(SyncState.FETCHING, same), SyncState.FETCHING.code(), 2L);
        when(mongo.findFirstByDomainAndEntityIdOrderBySeqDescTimestampDesc("article", "A-1"))
                .thenReturn(Optional.of(second));

        assertThat(repo.currentState("article", "A-1")).contains(SyncState.FETCHING);
        verify(mongo, never()).findFirstByDomainAndEntityIdOrderByTimestampDesc(any(), any());
    }

    /**
     * AC-12: dos escrituras concurrentes sobre la misma entidad no se pisan en
     * silencio. El indice unico sobre la secuencia hace que la segunda falle con
     * DuplicateKeyException, que se traduce a una excepcion de dominio.
     */
    @Test
    void concurrentWriteIsRejectedNotSilentlyOverwritten() {
        SyncStateDoc head = SyncStateDoc.from("article", "A-1",
                transition(SyncState.RECEIVED, Instant.now()), SyncState.RECEIVED.code(), 1L);
        when(mongo.findFirstByDomainAndEntityIdOrderBySeqDescTimestampDesc("article", "A-1"))
                .thenReturn(Optional.of(head));
        when(mongo.save(any())).thenThrow(new DuplicateKeyException("seq"));

        assertThatThrownBy(() -> repo.transition("article", "A-1",
                transition(SyncState.FETCHING, Instant.now())))
                .isInstanceOf(ConcurrentTransitionException.class);
    }

    /** AC-13: abrir ciclo asigna la secuencia siguiente a la ultima almacenada. */
    @Test
    void beginCycleAssignsNextSequence() {
        SyncStateDoc head = SyncStateDoc.from("article", "A-1",
                transition(SyncState.SAP_ERROR, Instant.now()), SyncState.SAP_ERROR.code(), 7L);
        when(mongo.findFirstByDomainAndEntityIdOrderBySeqDescTimestampDesc("article", "A-1"))
                .thenReturn(Optional.of(head));
        SyncStateTransition t = new SyncStateTransition("A-1", "article", null,
                SyncState.RECEIVED, "cdc", "h-2", Instant.now());

        assertThat(repo.beginCycle("article", "A-1", t)).isEqualTo(SyncState.RECEIVED);
        ArgumentCaptor<SyncStateDoc> save = ArgumentCaptor.forClass(SyncStateDoc.class);
        verify(mongo).save(save.capture());
        assertThat(save.getValue().seq()).isEqualTo(8L);
    }
}

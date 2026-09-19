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
     * idempotencia-y-dedupe AC-1 (auditoria A1): el dedupe mira el ULTIMO estado
     * final del agregado. Secuencia A -> B -> A: el tercer evento (hash A) NO esta
     * deduplicado porque SAP tiene B; solo lo esta si el ultimo envio fue A.
     */
    @Test
    void alreadySentOnlyMatchesTheLatestSentSap() {
        SyncStateTransition sentB = new SyncStateTransition("A-1", "article",
                SyncState.SENDING_SAP, SyncState.SENT_SAP, "cdc", "hash-B", Instant.now());
        when(mongo.findFirstByDomainAndEntityIdAndStateCodeInOrderBySeqDescTimestampDesc(
                eq("article"), eq("A-1"), any()))
                .thenReturn(Optional.of(SyncStateDoc.from("article", "A-1", sentB, SyncState.SENT_SAP.code(), 9L)));

        assertThat(repo.alreadySent("article", "A-1", "hash-A")).isFalse();
        assertThat(repo.alreadySent("article", "A-1", "hash-B")).isTrue();
        assertThat(repo.alreadySent("article", "A-1", null)).isFalse();
        assertThat(repo.alreadySent("article", "A-1", "")).isFalse();
        verify(mongo, never()).existsByDomainAndEntityIdAndPayloadHashAndStateCode(any(), any(), any(), anyInt());
    }

    /**
     * idempotencia-y-dedupe AC-14 (auditoria 2026-09-18 N4, 2A-5): tras un ciclo
     * que termino en fallo parcial, SAP tiene una MEZCLA. Aunque el ultimo
     * SENT_SAP lleve este hash, el dedupe no puede afirmar que SAP este en
     * sincronia: si el ultimo estado final del agregado no es SENT_SAP, se reenvia.
     */
    @Test
    void dedupeIsHonestWhenTheLastCycleEndedInPartialFailure() {
        SyncStateTransition partial = new SyncStateTransition("A-1", "article",
                SyncState.SENDING_SAP, SyncState.SAP_ERROR, "cdc", "hash-B", Instant.now(),
                "cyc-2", "HTTP 400: rechazado");
        when(mongo.findFirstByDomainAndEntityIdAndStateCodeInOrderBySeqDescTimestampDesc(
                eq("article"), eq("A-1"), any()))
                .thenReturn(Optional.of(SyncStateDoc.from("article", "A-1", partial, SyncState.SAP_ERROR.code(), 20L)));

        assertThat(repo.alreadySent("article", "A-1", "hash-A")).isFalse();
        assertThat(repo.alreadySent("article", "A-1", "hash-B")).isFalse();
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
                new SyncStateTransition("A-1", "article", SyncState.RECEIVED, SyncState.FETCHING,
                        "cdc", "h-1", Instant.now())))
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

    /**
     * AC-18 (sdd/common/maquina-de-estados.md): el ciclo y el motivo del error se
     * persisten en el documento y se recuperan al leerlo. Sin eso no hay traza de
     * pasos que reconstruir.
     */
    @Test
    void everyLineOfACycleSharesTheCycleId() {
        when(mongo.findFirstByDomainAndEntityIdOrderBySeqDescTimestampDesc("article", "A-1"))
                .thenReturn(Optional.empty());
        SyncStateTransition t = new SyncStateTransition("A-1", "article", null, SyncState.RECEIVED,
                "cdc", "h-1", Instant.now(), "cyc-7", null);

        repo.beginCycle("article", "A-1", t);

        ArgumentCaptor<SyncStateDoc> save = ArgumentCaptor.forClass(SyncStateDoc.class);
        verify(mongo).save(save.capture());
        assertThat(save.getValue().getCycleId()).isEqualTo("cyc-7");
        assertThat(save.getValue().toTransition().cycleId()).isEqualTo("cyc-7");
    }

    /**
     * AC-18: las lineas de un ciclo son entityId distintos (A-1, A-1:ADDRESS), asi
     * que {@code history} no las cruza. La consulta por ciclo las devuelve todas de
     * una sola vez, ordenadas.
     */
    @Test
    void cycleReturnsTheTransitionsOfEveryLine() {
        SyncStateTransition agg = new SyncStateTransition("A-1", "article",
                SyncState.INDEXED, SyncState.SENDING_SAP, "cdc", "h-1", Instant.now(), "cyc-7", null);
        SyncStateTransition line = new SyncStateTransition("A-1:ADDRESS", "article",
                SyncState.SENDING_SAP, SyncState.SAP_ERROR, "address", "h-1", Instant.now(),
                "cyc-7", "HTTP 400: falta ciudad");
        when(mongo.findByDomainAndCycleIdOrderBySeqAscTimestampAsc("article", "cyc-7"))
                .thenReturn(List.of(SyncStateDoc.from("article", "A-1", agg, SyncState.SENDING_SAP.code(), 3L),
                        SyncStateDoc.from("article", "A-1:ADDRESS", line, SyncState.SAP_ERROR.code(), 4L)));

        List<SyncStateTransition> steps = repo.cycle("article", "cyc-7");

        assertThat(steps).extracting(SyncStateTransition::entityId).containsExactly("A-1", "A-1:ADDRESS");
        assertThat(steps).extracting(SyncStateTransition::detail)
                .containsExactly(null, "HTTP 400: falta ciudad");
    }

    /**
     * AC-18 (auditoria N8): saber donde esta una linea no puede costar leerse su
     * historial entero; {@code lastTransition} lee solo la cabecera.
     */
    @Test
    void lastTransitionDoesNotReadTheWholeHistory() {
        SyncStateTransition last = new SyncStateTransition("A-1", "article",
                SyncState.SENDING_SAP, SyncState.SAP_ERROR, "cdc", "h-1", Instant.now(), "cyc-7", "HTTP 500");
        when(mongo.findFirstByDomainAndEntityIdOrderBySeqDescTimestampDesc("article", "A-1"))
                .thenReturn(Optional.of(SyncStateDoc.from("article", "A-1", last, SyncState.SAP_ERROR.code(), 9L)));

        Optional<SyncStateTransition> t = repo.lastTransition("article", "A-1");

        assertThat(t).isPresent();
        assertThat(t.get().to()).isEqualTo(SyncState.SAP_ERROR);
        assertThat(t.get().detail()).isEqualTo("HTTP 500");
        assertThat(t.get().cycleId()).isEqualTo("cyc-7");
        verify(mongo, never()).findByDomainAndEntityIdOrderBySeqAscTimestampAsc(any(), any());
    }

    /**
     * AC-20 (sdd/common/maquina-de-estados.md; auditoria N2, 2B-2): si la cabecera
     * se movio bajo nuestros pies, la transicion no es un error de programacion:
     * es una colision, y debe ser reintentable. Antes salia IllegalStateException,
     * declarada NO reintentable, y el mensaje iba directo a la DLT.
     */
    @Test
    void staleHeadIsReportedAsConcurrentNotIllegal() {
        SyncStateDoc head = SyncStateDoc.from("article", "A-1",
                new SyncStateTransition("A-1", "article", null, SyncState.RECEIVED, "cdc", "h-2",
                        Instant.now(), "cyc-B", null),
                SyncState.RECEIVED.code(), 3L);
        when(mongo.findFirstByDomainAndEntityIdOrderBySeqDescTimestampDesc("article", "A-1"))
                .thenReturn(Optional.of(head));

        // Esta instancia cree venir de FETCHING; otra ya abrio un ciclo nuevo en RECEIVED.
        SyncStateTransition mine = new SyncStateTransition("A-1", "article",
                SyncState.FETCHING, SyncState.VALIDATING, "cdc", "h-1", Instant.now(), "cyc-A", null);

        assertThatThrownBy(() -> repo.transition("article", "A-1", mine))
                .isInstanceOf(ConcurrentTransitionException.class)
                .isNotInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FETCHING")
                .hasMessageContaining("RECEIVED");
        verify(mongo, never()).save(any());
    }

    /**
     * AC-21: fencing por cycleId. Aunque el estado coincida, la cabecera puede ser
     * de OTRO ciclo: un proceso que perdio la carrera sin enterarse no escribe
     * sobre el trabajo del que la gano.
     */
    @Test
    void advancingOverAnotherCyclesHeadIsRejected() {
        SyncStateDoc head = SyncStateDoc.from("article", "A-1",
                new SyncStateTransition("A-1", "article", null, SyncState.FETCHING, "cdc", "h-2",
                        Instant.now(), "cyc-B", null),
                SyncState.FETCHING.code(), 5L);
        when(mongo.findFirstByDomainAndEntityIdOrderBySeqDescTimestampDesc("article", "A-1"))
                .thenReturn(Optional.of(head));

        SyncStateTransition mine = new SyncStateTransition("A-1", "article",
                SyncState.FETCHING, SyncState.VALIDATING, "cdc", "h-1", Instant.now(), "cyc-A", null);

        assertThatThrownBy(() -> repo.transition("article", "A-1", mine))
                .isInstanceOf(ConcurrentTransitionException.class)
                .hasMessageContaining("cyc-A")
                .hasMessageContaining("cyc-B");
        verify(mongo, never()).save(any());
    }

    /**
     * AC-20: {@code from == null} significa "no compruebes". Marcar ERROR tras un
     * fallo (SyncCustomerUseCase.markError) no puede fallar a su vez por una
     * colision, o el estado se quedaria sin cerrar.
     */
    @Test
    void nullDeclaredFromSkipsTheCheckSoMarkErrorStillWorks() {
        SyncStateDoc head = SyncStateDoc.from("article", "A-1",
                new SyncStateTransition("A-1", "article", null, SyncState.INDEXING, "cdc", "h-2",
                        Instant.now(), "cyc-B", null),
                SyncState.INDEXING.code(), 5L);
        when(mongo.findFirstByDomainAndEntityIdOrderBySeqDescTimestampDesc("article", "A-1"))
                .thenReturn(Optional.of(head));

        SyncStateTransition mark = new SyncStateTransition("A-1", "article",
                null, SyncState.ERROR, "cdc", "h-1", Instant.now(), "cyc-A", "Elasticsearch caido");

        assertThat(repo.transition("article", "A-1", mark)).isEqualTo(SyncState.ERROR);
        verify(mongo).save(any());
    }

    /**
     * AC-22: una transicion realmente imposible sigue siendo un error de
     * programacion. Solo la discrepancia con la cabecera es colision.
     */
    @Test
    void anIllegalTransitionIsStillAnIllegalStateException() {
        SyncStateDoc head = SyncStateDoc.from("article", "A-1",
                new SyncStateTransition("A-1", "article", null, SyncState.RECEIVED, "cdc", "h-1",
                        Instant.now(), "cyc-A", null),
                SyncState.RECEIVED.code(), 1L);
        when(mongo.findFirstByDomainAndEntityIdOrderBySeqDescTimestampDesc("article", "A-1"))
                .thenReturn(Optional.of(head));

        SyncStateTransition impossible = new SyncStateTransition("A-1", "article",
                SyncState.RECEIVED, SyncState.SENT_SAP, "cdc", "h-1", Instant.now(), "cyc-A", null);

        assertThatThrownBy(() -> repo.transition("article", "A-1", impossible))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(ConcurrentTransitionException.class);
    }
}

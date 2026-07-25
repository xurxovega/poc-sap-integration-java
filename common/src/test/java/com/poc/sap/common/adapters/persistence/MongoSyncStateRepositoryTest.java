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

/**
 * Test unit del {@link MongoSyncStateRepository} (SPEC.md §8; TECH.md §7).
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
        when(mongo.findFirstByDomainAndEntityIdOrderByTimestampDesc("article", "A-1"))
                .thenReturn(Optional.empty());

        Optional<SyncState> current = repo.currentState("article", "A-1");

        assertThat(current).isEmpty();
    }

    @Test
    void currentStateResolvesToLatestState() {
        SyncStateTransition t = transition(SyncState.INDEXED, Instant.parse("2026-01-01T00:00:00Z"));
        SyncStateDoc doc = SyncStateDoc.from("article", "A-1", t, SyncState.INDEXED.code());
        when(mongo.findFirstByDomainAndEntityIdOrderByTimestampDesc("article", "A-1"))
                .thenReturn(Optional.of(doc));

        Optional<SyncState> current = repo.currentState("article", "A-1");

        assertThat(current).contains(SyncState.INDEXED);
    }

    @Test
    void firstTransitionOfNewEntityIsAllowedToReceived() {
        when(mongo.findFirstByDomainAndEntityIdOrderByTimestampDesc("article", "A-1"))
                .thenReturn(Optional.empty());

        SyncStateTransition t = new SyncStateTransition(
                "A-1", "article", null, SyncState.RECEIVED,
                "cdc", "h-1", Instant.parse("2026-01-01T00:00:00Z"));

        SyncState result = repo.transition("article", "A-1", t);

        assertThat(result).isEqualTo(SyncState.RECEIVED);
        ArgumentCaptor<SyncStateDoc> save = ArgumentCaptor.forClass(SyncStateDoc.class);
        verify(mongo).save(save.capture());
        assertThat(save.getValue().stateCode()).isEqualTo(SyncState.RECEIVED.code());
    }

    @Test
    void transitionFromExistingStatePersistsAndReturns() {
        SyncStateTransition prev = transition(SyncState.INDEXED, Instant.parse("2026-01-01T00:00:00Z"));
        when(mongo.findFirstByDomainAndEntityIdOrderByTimestampDesc("article", "A-1"))
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
        when(mongo.findFirstByDomainAndEntityIdOrderByTimestampDesc("article", "A-1"))
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
        when(mongo.findByDomainAndEntityIdOrderByTimestampAsc("article", "A-1"))
                .thenReturn(List.of(d1, d2));

        List<SyncStateTransition> history = repo.history("article", "A-1");

        assertThat(history).extracting(SyncStateTransition::timestamp)
                .containsExactly(t1, t2);
    }

    @Test
    void alreadySentDelegatesToExistsQuery() {
        when(mongo.existsByDomainAndEntityIdAndPayloadHashAndStateCode(
                "article", "A-1", "h-1", SyncState.SENT_SAP.code())).thenReturn(true);

        assertThat(repo.alreadySent("article", "A-1", "h-1")).isTrue();
        assertThat(repo.alreadySent("article", "A-1", null)).isFalse();
        assertThat(repo.alreadySent("article", "A-1", "")).isFalse();
    }
}

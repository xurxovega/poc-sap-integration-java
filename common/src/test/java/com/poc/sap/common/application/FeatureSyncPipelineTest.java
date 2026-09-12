package com.poc.sap.common.application;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateMachine;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.ValidationResult;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.common.domain.port.SapOutboundPort;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec sdd/common/maquina-de-estados.md (linea de feature). El repositorio en
 * memoria aplica la SyncStateMachine REAL: un puerto mockeado esconderia justo
 * las transiciones que importan (incidencia 2026-09-09, D2/D6).
 */
class FeatureSyncPipelineTest {

    /** Fake minimo del puerto de estado con la maquina real. */
    static final class InMemoryStates implements SyncStateRepositoryPort {
        private final SyncStateMachine machine = new SyncStateMachine();
        final Map<String, List<SyncState>> states = new HashMap<>();
        final List<String> origins = new ArrayList<>();

        @Override public Optional<SyncState> currentState(String d, String e) {
            List<SyncState> s = states.get(e);
            return s == null || s.isEmpty() ? Optional.empty() : Optional.of(s.get(s.size() - 1));
        }
        @Override public SyncState beginCycle(String d, String e, SyncStateTransition t) {
            SyncState to = machine.beginCycle(currentState(d, e).orElse(null), t.to());
            states.computeIfAbsent(e, k -> new ArrayList<>()).add(to); origins.add(t.origin()); return to;
        }
        @Override public SyncState transition(String d, String e, SyncStateTransition t) {
            SyncState to = machine.advance(currentState(d, e).orElse(null), t.to());
            states.computeIfAbsent(e, k -> new ArrayList<>()).add(to); origins.add(t.origin()); return to;
        }
        @Override public List<SyncStateTransition> history(String d, String e) { return List.of(); }
        @Override public boolean alreadySent(String d, String e, String h) { return false; }
    }

    static final class CountingMetrics implements MetricsPort {
        final List<String> states = new ArrayList<>();
        @Override public void incrementState(String domain, String state) { states.add(domain + ":" + state); }
        @Override public void recordStageDuration(String domain, String stage, long ms) { }
    }

    private static SapOutboundPort<String> sap(int status) {
        return (id, hash, payload) -> new SapResponse(status, "", null);
    }

    private static ValidationResult verdict(String data) {
        return data.isBlank() ? ValidationResult.invalid("vacio") : ValidationResult.success();
    }

    @Test
    void syncWalksValidatingValidSendingSentOnTheFeatureLine() {
        InMemoryStates repo = new InMemoryStates(); CountingMetrics metrics = new CountingMetrics();
        var pipeline = new FeatureSyncPipeline<>("customer", "ADDRESS", FeatureSyncPipelineTest::verdict, sap(201), repo, metrics);

        SyncState result = pipeline.sync("C-1", "h-1", "Calle 1");

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        assertThat(repo.states.get("C-1:ADDRESS")).containsExactly(
                SyncState.VALIDATING, SyncState.VALID, SyncState.SENDING_SAP, SyncState.SENT_SAP);
        assertThat(repo.origins).allMatch("address"::equals);
        assertThat(metrics.states).containsExactly(
                "customer:VALIDATING", "customer:VALID", "customer:SENDING_SAP", "customer:SENT_SAP");
    }

    @Test
    void invalidDataStopsBeforeSap() {
        InMemoryStates repo = new InMemoryStates();
        var pipeline = new FeatureSyncPipeline<>("customer", "FISCAL", FeatureSyncPipelineTest::verdict,
                (id, hash, payload) -> { throw new AssertionError("no debe llamar a SAP"); }, repo, new CountingMetrics());

        assertThat(pipeline.sync("C-1", "h-1", "  ")).isEqualTo(SyncState.INVALID);
        assertThat(repo.states.get("C-1:FISCAL")).containsExactly(SyncState.VALIDATING, SyncState.INVALID);
    }

    @Test
    void sapRejectionEndsInSapErrorAndTheNextEventReopensTheLine() {
        InMemoryStates repo = new InMemoryStates();
        var pipeline = new FeatureSyncPipeline<>("customer", "CONTACT", FeatureSyncPipelineTest::verdict, sap(500), repo, new CountingMetrics());

        assertThat(pipeline.sync("C-1", "h-1", "x")).isEqualTo(SyncState.SAP_ERROR);
        assertThat(pipeline.sync("C-1", "h-2", "x")).isEqualTo(SyncState.SAP_ERROR);
        assertThat(repo.states.get("C-1:CONTACT")).hasSize(8).endsWith(SyncState.SENDING_SAP, SyncState.SAP_ERROR);
    }

    @Test
    void validateOnlyNeedsNoSapPortAndSyncWithoutPortFailsLoudly() {
        InMemoryStates repo = new InMemoryStates();
        var pipeline = new FeatureSyncPipeline<>("customer", "BANKING", FeatureSyncPipelineTest::verdict, null, repo, new CountingMetrics());

        assertThat(pipeline.validate("C-1", "h-1", "ES76")).isEqualTo(SyncState.VALID);
        assertThat(repo.states.get("C-1:BANKING")).containsExactly(SyncState.VALIDATING, SyncState.VALID);
        assertThatThrownBy(() -> pipeline.sync("C-1", "h-1", "ES76")).isInstanceOf(IllegalStateException.class);
        assertThat(FeatureSyncPipeline.featureEntityId("C-42", "BANKING")).isEqualTo("C-42:BANKING");
    }
}

package com.poc.sap.common.application;

import com.poc.sap.common.domain.ConcurrentTransitionException;
import com.poc.sap.common.domain.FeatureOutcome;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateMachine;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.ValidationResult;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.common.domain.port.SapOutboundPort;
import com.poc.sap.common.domain.port.SapOutboundPort.SapLookup;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.sap.SapCircuitOpenException;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.SapUpsertSettings;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    /** Fake minimo del puerto de estado con la maquina real. */
    static final class InMemoryStates implements SyncStateRepositoryPort {
        private final SyncStateMachine machine = new SyncStateMachine();
        final Map<String, List<SyncState>> states = new HashMap<>();
        final List<String> origins = new ArrayList<>();
        final List<SyncStateTransition> written = new ArrayList<>();
        /** Si no es null, la transicion a este estado choca con otra instancia. */
        SyncState failOn;

        @Override public Optional<SyncState> currentState(String d, String e) {
            List<SyncState> s = states.get(e);
            return s == null || s.isEmpty() ? Optional.empty() : Optional.of(s.get(s.size() - 1));
        }
        @Override public SyncState beginCycle(String d, String e, SyncStateTransition t) {
            SyncState to = machine.beginCycle(currentState(d, e).orElse(null), t.to());
            return record(e, t, to);
        }
        @Override public SyncState transition(String d, String e, SyncStateTransition t) {
            if (failOn != null && t.to() == failOn) {
                throw ConcurrentTransitionException.staleHead(d, e, t.from(), null, 1L);
            }
            SyncState to = machine.advance(currentState(d, e).orElse(null), t.to());
            return record(e, t, to);
        }
        private SyncState record(String e, SyncStateTransition t, SyncState to) {
            states.computeIfAbsent(e, k -> new ArrayList<>()).add(to);
            origins.add(t.origin());
            written.add(t);
            return to;
        }
        @Override public List<SyncStateTransition> history(String d, String e) { return List.of(); }
        @Override public List<SyncStateTransition> cycle(String d, String cycleId) {
            return written.stream().filter(t -> cycleId.equals(t.cycleId())).toList();
        }
        @Override public Optional<SyncStateTransition> lastTransition(String d, String e) {
            return written.stream().filter(t -> t.entityId().equals(e)).reduce((a, b) -> b);
        }
        @Override public boolean alreadySent(String d, String e, String h) { return false; }

        /** Motivo escrito en la ultima transicion a un estado de una linea. */
        String detailOf(String line, SyncState state) {
            return written.stream().filter(t -> t.entityId().equals(line) && t.to() == state)
                    .reduce((a, b) -> b).map(SyncStateTransition::detail).orElse(null);
        }
    }

    static final class CountingMetrics implements MetricsPort {
        final List<String> states = new ArrayList<>();
        @Override public void incrementState(String domain, String state) { states.add(domain + ":" + state); }
        @Override public void recordStageDuration(String domain, String stage, long ms) { }
    }

    /**
     * Puerto que registra el ORDEN de las llamadas y responde lo que se le indique.
     * No puede ser una lambda: el upsert usa los metodos {@code default}
     * {@code lookup} y {@code update}, que hay que sobreescribir.
     */
    static final class RecordingSap implements SapOutboundPort<String> {
        final List<String> calls = new ArrayList<>();
        final List<String> etagsSeen = new ArrayList<>();
        final Deque<SapLookup> lookups = new ArrayDeque<>();
        final Deque<SapResponse> updates = new ArrayDeque<>();
        SapLookup lastLookup = SapLookup.notSupported();
        SapResponse sendResponse = new SapResponse(201, "", null);
        SapResponse lastUpdate = new SapResponse(204, "", null);

        @Override public SapResponse send(String id, String hash, String payload) {
            calls.add("send");
            return sendResponse;
        }
        @Override public SapLookup lookup(String id, String payload) {
            calls.add("lookup");
            return lookups.isEmpty() ? lastLookup : lookups.poll();
        }
        @Override public SapResponse update(String id, String hash, String payload, SapLookup found) {
            calls.add("update");
            etagsSeen.add(found.etag());
            return updates.isEmpty() ? lastUpdate : updates.poll();
        }
    }

    private static SapOutboundPort<String> sap(int status) {
        return (id, hash, payload) -> new SapResponse(status, "", null);
    }

    private static SapOutboundPort<String> sap(int status, String body) {
        return (id, hash, payload) -> new SapResponse(status, body, null);
    }

    private static SapOutboundPort<String> throwing(RuntimeException e) {
        return (id, hash, payload) -> { throw e; };
    }

    private static ValidationResult verdict(String data) {
        return data.isBlank() ? ValidationResult.invalid("vacio") : ValidationResult.success();
    }

    private static FeatureSyncPipeline<String> pipeline(String feature, SapOutboundPort<String> sap,
                                                        InMemoryStates repo, CountingMetrics metrics) {
        return new FeatureSyncPipeline<>("customer", feature, FeatureSyncPipelineTest::verdict,
                sap, repo, metrics, FIXED);
    }

    private static FeatureSyncPipeline<String> pipeline(String feature, SapOutboundPort<String> sap,
                                                        InMemoryStates repo, CountingMetrics metrics,
                                                        SapUpsertSettings upsert) {
        return new FeatureSyncPipeline<>("customer", feature, FeatureSyncPipelineTest::verdict,
                sap, repo, metrics, FIXED, upsert);
    }

    @Test
    void syncWalksValidatingValidSendingSentOnTheFeatureLine() {
        InMemoryStates repo = new InMemoryStates(); CountingMetrics metrics = new CountingMetrics();
        var pipeline = pipeline("ADDRESS", sap(201), repo, metrics);

        FeatureOutcome result = pipeline.sync("C-1", "cyc-1", "h-1", "Calle 1");

        assertThat(result.state()).isEqualTo(SyncState.SENT_SAP);
        assertThat(result.ok()).isTrue();
        assertThat(result.feature()).isEqualTo("ADDRESS");
        assertThat(result.detail()).isNull();
        assertThat(result.at()).isEqualTo(NOW);
        assertThat(repo.states.get("C-1:ADDRESS")).containsExactly(
                SyncState.VALIDATING, SyncState.VALID, SyncState.SENDING_SAP, SyncState.SENT_SAP);
        assertThat(repo.origins).allMatch("address"::equals);
        assertThat(metrics.states).containsExactly(
                "customer:VALIDATING", "customer:VALID", "customer:SENDING_SAP", "customer:SENT_SAP");
    }

    @Test
    void invalidDataStopsBeforeSap() {
        InMemoryStates repo = new InMemoryStates();
        var pipeline = pipeline("FISCAL",
                throwing(new IllegalStateException("no debe llamar a SAP")), repo, new CountingMetrics());

        FeatureOutcome outcome = pipeline.sync("C-1", "cyc-1", "h-1", "  ");

        assertThat(outcome.state()).isEqualTo(SyncState.INVALID);
        assertThat(outcome.detail()).contains("vacio");
        assertThat(repo.states.get("C-1:FISCAL")).containsExactly(SyncState.VALIDATING, SyncState.INVALID);
    }

    @Test
    void sapRejectionEndsInSapErrorAndTheNextEventReopensTheLine() {
        InMemoryStates repo = new InMemoryStates();
        var pipeline = pipeline("CONTACT", sap(500), repo, new CountingMetrics());

        assertThat(pipeline.sync("C-1", "cyc-1", "h-1", "x").state()).isEqualTo(SyncState.SAP_ERROR);
        assertThat(pipeline.sync("C-1", "cyc-2", "h-2", "x").state()).isEqualTo(SyncState.SAP_ERROR);
        assertThat(repo.states.get("C-1:CONTACT")).hasSize(8).endsWith(SyncState.SENDING_SAP, SyncState.SAP_ERROR);
    }

    @Test
    void validateOnlyNeedsNoSapPortAndSyncWithoutPortFailsLoudly() {
        InMemoryStates repo = new InMemoryStates();
        var pipeline = pipeline("BANKING", null, repo, new CountingMetrics());

        assertThat(pipeline.validate("C-1", "h-1", "ES76")).isEqualTo(SyncState.VALID);
        assertThat(repo.states.get("C-1:BANKING")).containsExactly(SyncState.VALIDATING, SyncState.VALID);
        assertThatThrownBy(() -> pipeline.sync("C-1", "cyc-1", "h-1", "ES76"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(FeatureSyncPipeline.featureEntityId("C-42", "BANKING")).isEqualTo("C-42:BANKING");
    }

    /**
     * AC-15 (sdd/common/maquina-de-estados.md; auditoria 2026-09-18 N1): con el
     * circuito abierto el puerto LANZA. Antes la linea se quedaba en SENDING_SAP
     * para siempre. Ahora cierra en COMMUNICATION_ERROR: no se llamo a SAP, asi
     * que reenviar es seguro.
     */
    @Test
    void sapPortThrowingLeavesTheLineInCommunicationError() {
        InMemoryStates repo = new InMemoryStates();
        var pipeline = pipeline("BANKING",
                throwing(new SapCircuitOpenException(SapDestination.S4_NATIVE, null)), repo, new CountingMetrics());

        FeatureOutcome outcome = pipeline.sync("C-1", "cyc-1", "h-1", "ES76");

        assertThat(outcome.state()).isEqualTo(SyncState.COMMUNICATION_ERROR);
        assertThat(outcome.touchedSap()).isFalse();
        assertThat(outcome.detail()).contains("circuito");
        assertThat(repo.states.get("C-1:BANKING"))
                .endsWith(SyncState.SENDING_SAP, SyncState.COMMUNICATION_ERROR)
                .doesNotContain(SyncState.SENT_SAP);
    }

    /**
     * AC-15: si SAP RESPONDIO (aqui un 400), la linea es SAP_ERROR: pudo aplicar
     * algo, y reenviar sin verificacion previa duplicaria.
     */
    @Test
    void sapAnsweringAnErrorLeavesTheLineInSapError() {
        InMemoryStates repo = new InMemoryStates();
        var pipeline = pipeline("ADDRESS", sap(400, "falta ciudad"), repo, new CountingMetrics());

        FeatureOutcome outcome = pipeline.sync("C-1", "cyc-1", "h-1", "Calle 1");

        assertThat(outcome.state()).isEqualTo(SyncState.SAP_ERROR);
        assertThat(outcome.touchedSap()).isTrue();
    }

    /**
     * AC-16: {@code httpStatus = 0} es el transporte agotado, no una respuesta de
     * SAP. No sabemos si llego: COMMUNICATION_ERROR, no SAP_ERROR.
     */
    @Test
    void statusZeroIsCommunicationErrorNotSapError() {
        InMemoryStates repo = new InMemoryStates();
        var pipeline = pipeline("FISCAL", sap(0, "read timeout"), repo, new CountingMetrics());

        FeatureOutcome outcome = pipeline.sync("C-1", "cyc-1", "h-1", "B123");

        assertThat(outcome.state()).isEqualTo(SyncState.COMMUNICATION_ERROR);
        assertThat(outcome.detail()).contains("transporte");
    }

    /**
     * AC-17: la transicion de error persiste el motivo con el estado HTTP y el
     * cuerpo de SAP, y nunca el payload que enviamos.
     */
    @Test
    void errorDetailKeepsTheSapStatusAndBody() {
        InMemoryStates repo = new InMemoryStates();
        var pipeline = pipeline("CONTACT", sap(400, "Property 'BusinessPartnerPerson' is required"),
                repo, new CountingMetrics());

        FeatureOutcome outcome = pipeline.sync("C-1", "cyc-1", "h-1", "telefono-secreto");

        assertThat(outcome.detail()).contains("400").contains("BusinessPartnerPerson");
        assertThat(repo.detailOf("C-1:CONTACT", SyncState.SAP_ERROR))
                .isEqualTo(outcome.detail())
                .doesNotContain("telefono-secreto");
        assertThat(repo.detailOf("C-1:CONTACT", SyncState.SENDING_SAP)).isNull();
    }

    /**
     * AC-19: una colision de concurrencia no es un fallo de SAP. Si se tragara
     * como SAP_ERROR se perderia la senal de que este ciclo entero debe
     * reintentarse (auditoria 2026-09-18 N2).
     */
    @Test
    void concurrentTransitionIsNotSwallowedAsASapError() {
        InMemoryStates repo = new InMemoryStates();
        repo.failOn = SyncState.SENT_SAP;
        var pipeline = pipeline("ADDRESS", sap(201), repo, new CountingMetrics());

        assertThatThrownBy(() -> pipeline.sync("C-1", "cyc-1", "h-1", "Calle 1"))
                .isInstanceOf(ConcurrentTransitionException.class);
        assertThat(repo.states.get("C-1:ADDRESS")).doesNotContain(SyncState.SAP_ERROR);
    }

    /**
     * AC-18: todas las transiciones de la linea llevan el ciclo que le pasa el
     * orquestador; es lo que une la linea con el agregado en la traza de pasos.
     */
    @Test
    void everyTransitionOfTheLineCarriesTheCycleId() {
        InMemoryStates repo = new InMemoryStates();
        var pipeline = pipeline("ADDRESS", sap(201), repo, new CountingMetrics());

        pipeline.sync("C-1", "cyc-9", "h-1", "Calle 1");

        assertThat(repo.written).extracting(SyncStateTransition::cycleId).containsOnly("cyc-9");
        assertThat(repo.cycle("customer", "cyc-9")).hasSize(4);
    }

    /**
     * AC-1 (sdd/common/upsert-idempotente-sap.md): SAP no tiene la subentidad
     * (404 del lookup), asi que la operacion es un alta. Primero se pregunta, y
     * solo entonces se escribe.
     */
    @Test
    void lookupNotFoundCreates() {
        InMemoryStates repo = new InMemoryStates();
        RecordingSap sap = new RecordingSap();
        sap.lastLookup = SapLookup.notFound();

        FeatureOutcome outcome = pipeline("ADDRESS", sap, repo, new CountingMetrics())
                .sync("C-1", "cyc-1", "h-1", "Calle 1");

        assertThat(sap.calls).containsExactly("lookup", "send");
        assertThat(outcome.state()).isEqualTo(SyncState.SENT_SAP);
    }

    /**
     * AC-2: SAP ya la tiene, asi que se actualiza con el ETag del lookup como
     * precondicion y NUNCA se da de alta: un POST crearia el duplicado.
     */
    @Test
    void lookupFoundUpdatesWithIfMatch() {
        InMemoryStates repo = new InMemoryStates();
        RecordingSap sap = new RecordingSap();
        sap.lastLookup = SapLookup.found("A-0001", "W/\"datetime2026\"");

        FeatureOutcome outcome = pipeline("ADDRESS", sap, repo, new CountingMetrics())
                .sync("C-1", "cyc-1", "h-1", "Calle 1");

        assertThat(sap.calls).containsExactly("lookup", "update").doesNotContain("send");
        assertThat(sap.etagsSeen).containsExactly("W/\"datetime2026\"");
        assertThat(outcome.state()).isEqualTo(SyncState.SENT_SAP);
    }

    /**
     * AC-3: si el lookup no concluye no se escribe NADA y la linea queda en
     * COMMUNICATION_ERROR. Es el corazon de la decision (3) del propietario: si no
     * sabemos que tiene SAP, no escribimos; y como SAP no se toco, la reentrega es
     * segura.
     */
    @Test
    void lookupUnavailableStopsBeforeWritingAnything() {
        InMemoryStates repo = new InMemoryStates();
        RecordingSap sap = new RecordingSap();
        sap.lastLookup = SapLookup.unavailable("SAP respondio 503");

        FeatureOutcome outcome = pipeline("FISCAL", sap, repo, new CountingMetrics())
                .sync("C-1", "cyc-1", "h-1", "B123");

        assertThat(sap.calls).containsExactly("lookup");
        assertThat(outcome.state()).isEqualTo(SyncState.COMMUNICATION_ERROR);
        assertThat(outcome.touchedSap()).isFalse();
        assertThat(outcome.detail()).contains("lookup no concluyente").contains("503");
        assertThat(repo.states.get("C-1:FISCAL"))
                .endsWith(SyncState.SENDING_SAP, SyncState.COMMUNICATION_ERROR)
                .doesNotContain(SyncState.SENT_SAP);
    }

    /** AC-4: mas de un resultado es «no lo se», no «cojo el primero»: tampoco escribe. */
    @Test
    void ambiguousLookupNeverWrites() {
        InMemoryStates repo = new InMemoryStates();
        RecordingSap sap = new RecordingSap();
        sap.lastLookup = SapLookup.unavailable("lookup ambiguo: 2 resultados");

        FeatureOutcome outcome = pipeline("CONTACT", sap, repo, new CountingMetrics())
                .sync("C-1", "cyc-1", "h-1", "x");

        assertThat(sap.calls).containsExactly("lookup");
        assertThat(outcome.state()).isEqualTo(SyncState.COMMUNICATION_ERROR);
        assertThat(outcome.detail()).contains("ambiguo");
    }

    /** AC-5: un puerto que no sabe verificar (NOT_SUPPORTED) se comporta como antes: alta directa. */
    @Test
    void portWithoutLookupStillPostsAsBefore() {
        InMemoryStates repo = new InMemoryStates();
        RecordingSap sap = new RecordingSap();   // lookup por defecto: NOT_SUPPORTED

        FeatureOutcome outcome = pipeline("BANKING", sap, repo, new CountingMetrics())
                .sync("C-1", "cyc-1", "h-1", "ES76");

        assertThat(sap.calls).containsExactly("lookup", "send").doesNotContain("update");
        assertThat(outcome.state()).isEqualTo(SyncState.SENT_SAP);
    }

    /**
     * AC-7: un 412 (alguien toco el recurso entre nuestro lookup y nuestro PATCH)
     * permite UN re-lookup y UN PATCH mas. Un segundo 412 termina en SAP_ERROR con el
     * motivo: reintentar en bucle sobre un recurso que alguien esta editando no
     * converge.
     */
    @Test
    void preconditionFailedIsRefetchedOnce() {
        RecordingSap sap = new RecordingSap();
        sap.lookups.add(SapLookup.found("A-1", "etag-1"));
        sap.lastLookup = SapLookup.found("A-1", "etag-2");
        sap.updates.add(new SapResponse(412, "Precondition Failed", null));
        sap.lastUpdate = new SapResponse(204, "", null);

        FeatureOutcome ok = pipeline("ADDRESS", sap, new InMemoryStates(), new CountingMetrics())
                .sync("C-1", "cyc-1", "h-1", "Calle 1");

        assertThat(sap.calls).containsExactly("lookup", "update", "lookup", "update");
        assertThat(sap.etagsSeen).containsExactly("etag-1", "etag-2");
        assertThat(ok.state()).isEqualTo(SyncState.SENT_SAP);

        RecordingSap always412 = new RecordingSap();
        always412.lastLookup = SapLookup.found("A-1", "etag-1");
        always412.lastUpdate = new SapResponse(412, "Precondition Failed", null);

        FeatureOutcome ko = pipeline("ADDRESS", always412, new InMemoryStates(), new CountingMetrics())
                .sync("C-2", "cyc-2", "h-2", "Calle 2");

        assertThat(always412.calls).containsExactly("lookup", "update", "lookup", "update");
        assertThat(ko.state()).isEqualTo(SyncState.SAP_ERROR);
        assertThat(ko.detail()).contains("412");
    }

    /**
     * §6.1: {@code sap.client.lookup.enabled=false} devuelve el comportamiento
     * anterior (alta directa, sin preguntar). Solo para el SAP simulado local.
     */
    @Test
    void lookupDisabledPostsDirectly() {
        InMemoryStates repo = new InMemoryStates();
        RecordingSap sap = new RecordingSap();
        sap.lastLookup = SapLookup.found("A-1", "etag-1");

        FeatureOutcome outcome = pipeline("ADDRESS", sap, repo, new CountingMetrics(),
                new SapUpsertSettings(false, true, true)).sync("C-1", "cyc-1", "h-1", "Calle 1");

        assertThat(sap.calls).containsExactly("send");
        assertThat(outcome.state()).isEqualTo(SyncState.SENT_SAP);
    }
}

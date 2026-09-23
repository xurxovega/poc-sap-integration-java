package com.poc.sap.dashboard.customer.bootstrap.observability;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.dashboard.customer.bootstrap.observability.KpiJob.AlertRow;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT del {@link KpiJob} (UI-001 H-5 F-12): re-calcula contra Mongo los
 * gauges {@code business_kpi_mttr_seconds{window=7d}} (MTTR tecnico) y
 * {@code business_kpi_recovery_p95_seconds{cycle=last}} (recuperacion
 * p95) y los registra en el {@link MeterRegistry} que la app expone
 * luego en su {@code /actuator/prometheus}.
 *
 * <p>Se activa con {@code -Ddocker.available=true} (Testcontainers
 * Mongo). Sin Docker, la logica del job sigue siendo testable por el
 * {@link InMemoryKpiJobTest} (companion, no se entrega aqui porque el
 * plan no lo exige).
 */
@Testcontainers
@EnabledIfSystemProperty(named = "docker.available", matches = "true")
class KpiJobTest {

    @Container
    static final MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    private MongoClient client;

    @BeforeEach
    void setUp() {
        client = MongoClients.create(mongo.getReplicaSetUrl("customer"));
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    private static SyncStateTransition t(String entity, SyncState from, SyncState to,
                                        String cycleId, Instant at) {
        return new SyncStateTransition(entity, "customer", from, to,
                "it", "h", at, cycleId, null);
    }

    private static void seedCycle(MongoDatabase db, String entity, String cycleId,
                                  Instant tStart, Instant tError, Instant tFixed) {
        MongoCollection<Document> sync = db.getCollection("sync_state");
        sync.insertOne(doc(entity, 0, SyncState.RECEIVED.code(), cycleId, tStart));
        sync.insertOne(doc(entity, SyncState.RECEIVED.code(), SyncState.FETCHING.code(), cycleId, tStart.plusSeconds(10)));
        // El SAP_ERROR ocurre en tError y la ACK del operador cierra el ciclo un poco despues.
        sync.insertOne(doc(entity, SyncState.SENDING_SAP.code(), SyncState.SAP_ERROR.code(), cycleId, tError, "HTTP 500"));
        sync.insertOne(doc(entity, SyncState.SAP_ERROR.code(), SyncState.SENT_SAP.code(), cycleId, tFixed));
    }

    private static Document doc(String entityId, int fromCode, int toCode,
                                String cycleId, Instant ts) {
        return doc(entityId, fromCode, toCode, cycleId, ts, null);
    }

    private static Document doc(String entityId, int fromCode, int toCode,
                                String cycleId, Instant ts, String detail) {
        return new Document("domain", "customer").append("entityId", entityId)
                .append("fromStateCode", fromCode == 0 ? null : fromCode)
                .append("stateCode", toCode)
                .append("origin", "it").append("payloadHash", "h")
                .append("cycleId", cycleId).append("timestamp", Date.from(ts))
                .append("seq", ts.toEpochMilli()).append("detail", detail);
    }

    @Test
    void recomputeRegistersBothKpisAgainstMongo() {
        MongoDatabase db = client.getDatabase("customer");
        db.getCollection("sync_state").drop();
        Instant t0 = Instant.parse("2026-09-15T10:00:00Z");
        // Dos entidades terminan en SAP_ERROR y se recuperan a SENT_SAP 5 minutos despues.
        seedCycle(db, "C-1", "cyc-1", t0, t0.plusSeconds(60), t0.plusSeconds(360));
        seedCycle(db, "C-2", "cyc-2", t0.plusSeconds(30), t0.plusSeconds(120), t0.plusSeconds(420));

        MeterRegistry registry = new SimpleMeterRegistry();
        KpiJob job = new KpiJob(registry, () -> client.getDatabase("customer"), Duration.ofDays(7));

        // Llama explicitamente en lugar de esperar al scheduler.
        job.recompute(new AlertRow.Provider() {
            @Override public List<AlertRow> open() {
                // sin alertas: el mttr usa solo el sync_state.
                return List.of();
            }
        });

        Gauge mttr = registry.get("business_kpi_mttr_seconds").tag("window", "7d").gauge();
        Gauge p95 = registry.get("business_kpi_recovery_p95_seconds").tag("cycle", "last").gauge();
        assertThat(mttr.value()).isCloseTo(300.0, org.assertj.core.api.Assertions.within(1.0));
        assertThat(p95.value()).isPositive();
    }

    @Test
    void mttrIsZeroWhenNoErrorsInWindow() {
        MongoDatabase db = client.getDatabase("customer");
        db.getCollection("sync_state").drop();
        Instant t0 = Instant.parse("2026-09-15T10:00:00Z");
        // Solo SENT_SAP, sin errores en la ventana.
        db.getCollection("sync_state").insertOne(doc("C-1", 0, SyncState.RECEIVED.code(), "cyc-x", t0));
        db.getCollection("sync_state").insertOne(doc("C-1",
                SyncState.RECEIVED.code(), SyncState.SENT_SAP.code(), "cyc-x",
                t0.plusSeconds(30)));

        MeterRegistry registry = new SimpleMeterRegistry();
        KpiJob job = new KpiJob(registry, () -> client.getDatabase("customer"), Duration.ofDays(7));
        job.recompute(() -> List.of());

        Gauge mttr = registry.get("business_kpi_mttr_seconds").tag("window", "7d").gauge();
        assertThat(mttr.value()).isEqualTo(0.0);
    }
}

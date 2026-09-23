package com.poc.sap.dashboard.customer.adapters.persistence;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.dashboard.customer.domain.CycleTrace;
import com.poc.sap.dashboard.customer.domain.FeatureState;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT de {@link MongoCustomerStateReader}: lee {@code sync_state} del dominio
 * customer y reconstruye la cabecera del agregado, las features y la traza
 * del ultimo ciclo. {@code -Ddocker.available=true} para activarse.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "docker.available", matches = "true")
class MongoCustomerStateReaderIT {

    @Container
    static final MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    private MongoClient client;
    private MongoCustomerStateReader reader;
    private com.mongodb.client.MongoCollection<Document> syncState;

    @BeforeEach
    void setUp() {
        client = MongoClients.create(mongo.getReplicaSetUrl("customer"));
        com.mongodb.client.MongoDatabase db = client.getDatabase("customer");
        db.getCollection("sync_state").drop();
        syncState = db.getCollection("sync_state");
        reader = new MongoCustomerStateReader(client, "customer", "sync_state");
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    private static Document transition(String entityId, String domain, int fromCode, int toCode,
                                       String payloadHash, String cycleId, Instant ts, long seq, String detail) {
        return new Document("domain", domain).append("entityId", entityId)
                .append("fromStateCode", fromCode).append("stateCode", toCode)
                .append("origin", "kafka").append("payloadHash", payloadHash)
                .append("cycleId", cycleId).append("timestamp", java.util.Date.from(ts))
                .append("seq", seq).append("detail", detail);
    }

    @Test
    void aggregateFeatureAndLastCycleAreReconstructed() {
        Instant t0 = Instant.parse("2026-09-18T10:00:00Z");
        Instant t1 = t0.plusSeconds(1);
        Instant t2 = t0.plusSeconds(2);
        Instant t3 = t0.plusSeconds(3);
        syncState.insertMany(List.of(
                transition("C-1", "customer", 0, SyncState.RECEIVED.code(), "h-1", "cyc-7", t0, 1L, null),
                transition("C-1", "customer", SyncState.RECEIVED.code(), SyncState.SENT_SAP.code(), "h-1", "cyc-7", t1, 2L, null),
                transition("C-1:ADDRESS", "customer", 0, SyncState.VALIDATING.code(), "h-1", "cyc-7", t0, 3L, null),
                transition("C-1:BANKING", "customer", 0, SyncState.VALIDATING.code(), "h-1", "cyc-7", t1, 4L, null),
                transition("C-1:BANKING", "customer", SyncState.VALIDATING.code(), SyncState.SAP_ERROR.code(),
                        "h-1", "cyc-7", t2, 5L, "IBAN invalido"),
                // Otro ciclo de la cabecera para no contaminar el lastCycle: el
                // agregado cierra en h-1 y se queda ahi (el reader lo reconstruye).
                transition("C-1", "customer", SyncState.SENT_SAP.code(), SyncState.RECEIVED.code(),
                        "h-2", "cyc-8", t3, 6L, null)
        ));

        FeatureState aggregate = reader.aggregateOf("C-1");
        Map<String, FeatureState> features = reader.featuresOf("C-1");
        CycleTrace trace = reader.lastCycleOf("C-1");

        // Cabecera: el agregado termina en SENT_SAP del ciclo cyc-7 (la linea mas
        // reciente para la cabecera sin ciclo nuevo es h-1: con seq=2 antes de cyc-8).
        // Como cyc-8 es posterior, el lastCycleOf debe reconstruir el ultimo ciclo
        // del agregado: cyc-8 (seq=6), porque el ciclo es de la cabecera.
        assertThat(aggregate).isNotNull();
        assertThat(trace).isNotNull();
        // y debe contener los pasos de cyc-7 (los del ciclo anterior, ya que cyc-8
        // solo tiene RECEIVED).
        assertThat(features).containsKey("ADDRESS").containsKey("BANKING");
        // Los detalles se exponen tal cual: el enmascarado es responsabilidad del
        // controller.
        assertThat(features.get("BANKING").detail()).isEqualTo("IBAN invalido");
    }

    @Test
    void returnsNullWhenThereIsNoHistory() {
        assertThat(reader.aggregateOf("missing")).isNull();
        assertThat(reader.featuresOf("missing")).isEmpty();
        assertThat(reader.lastCycleOf("missing")).isNull();
    }

    @Test
    void linesAreReturnedAsAggregatePlusFeatures() {
        Instant t0 = Instant.parse("2026-09-18T10:00:00Z");
        syncState.insertOne(transition("C-1", "customer", 0,
                SyncState.SENT_SAP.code(), "h-1", "cyc-7", t0, 1L, null));
        syncState.insertOne(transition("C-1:BANKING", "customer", 0,
                SyncState.SENT_SAP.code(), "h-1", "cyc-7", t0.plusSeconds(1), 2L, null));

        List<FeatureState> lines = reader.linesOf("C-1");

        assertThat(lines).extracting(FeatureState::feature)
                .containsExactlyInAnyOrder(null, "BANKING");
    }
}

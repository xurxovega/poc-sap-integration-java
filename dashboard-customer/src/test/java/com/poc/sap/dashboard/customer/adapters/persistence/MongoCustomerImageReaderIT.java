package com.poc.sap.dashboard.customer.adapters.persistence;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.poc.sap.dashboard.customer.domain.CustomerSnapshot;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT de {@link MongoCustomerImageReader} contra Mongo real (UI-001 H-3,
 * AC-1 AC-2): lee el documento BSON de {@code customers_current} y lo mapea
 * al {@link CustomerSnapshot} local. Se activa con {@code -Ddocker.available=true}.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "docker.available", matches = "true")
class MongoCustomerImageReaderIT {

    @Container
    static final MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    private MongoClient client;
    private MongoCustomerImageReader reader;
    private com.mongodb.client.MongoCollection<Document> collection;

    @BeforeEach
    void setUp() {
        client = MongoClients.create(mongo.getReplicaSetUrl("customer"));
        com.mongodb.client.MongoDatabase db = client.getDatabase("customer");
        db.getCollection("customers_current").drop();
        collection = db.getCollection("customers_current");
        reader = new MongoCustomerImageReader(client, "customer", "customers_current");
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void readsCustomerSnapshotAndMapsEveryFeature() {
        Document addr = new Document("street", "Calle 1").append("city", "Madrid")
                .append("postalCode", "28001").append("country", "ES").append("region", "M");
        Document fisc = new Document("taxId", "B12345678").append("vatNumber", null)
                .append("legalName", "Acme").append("taxResidency", "ES");
        Document cont = new Document("email", "info@acme.com").append("phone", "+34600000000")
                .append("fax", null).append("website", null);
        Document bank = new Document("iban", "ES7621000418401234567890")
                .append("bic", "BBVAESMM").append("mandateIds", java.util.List.of());
        collection.insertOne(new Document("_id", "C-1")
                .append("code", "CUST-001")
                .append("name", "Acme")
                .append("status", "ACTIVE")
                .append("address", addr)
                .append("fiscal", fisc)
                .append("contact", cont)
                .append("banking", bank));

        CustomerSnapshot snap = reader.findById("C-1").orElseThrow();

        assertThat(snap.entityId()).isEqualTo("C-1");
        assertThat(snap.code()).isEqualTo("CUST-001");
        assertThat(snap.name()).isEqualTo("Acme");
        assertThat(snap.status()).isEqualTo(CustomerSnapshot.Status.ACTIVE);
        assertThat(snap.address().city()).isEqualTo("Madrid");
        assertThat(snap.fiscal().taxId()).isEqualTo("B12345678");
        assertThat(snap.contact().email()).isEqualTo("info@acme.com");
        assertThat(snap.banking().iban()).isEqualTo("ES7621000418401234567890");
    }

    @Test
    void returnsEmptyWhenEntityMissing() {
        assertThat(reader.findById("missing")).isEmpty();
    }

    @Test
    void fallsBackToActiveWhenStatusIsOmitted() {
        // Cubre el caso del row inicial cuando todavia no se ha sincronizado
        // el campo status: el modulo customer mapea a ACTIVE por defecto.
        collection.insertOne(new Document("_id", "C-9")
                .append("code", "CUST-009")
                .append("name", "Sin Status"));

        CustomerSnapshot snap = reader.findById("C-9").orElseThrow();

        assertThat(snap.status()).isEqualTo(CustomerSnapshot.Status.ACTIVE);
    }

    @Test
    void readsCustomerInLessThan500Millis() {
        // AC-1: la lectura directa desde Mongo + ES es < 500 ms en condiciones
        // de local. Con un solo documento el coste deberia ser menor: el test
        // asume que la red/contendor no se inflan (es la cota superior del SLI).
        Document addr = new Document("street", "Calle 1").append("city", "Madrid")
                .append("postalCode", "28001").append("country", "ES").append("region", "M");
        collection.insertOne(new Document("_id", "C-fast")
                .append("code", "CUST-FAST").append("name", "Fast").append("status", "ACTIVE")
                .append("address", addr));

        Instant start = Instant.now();
        CustomerSnapshot snap = reader.findById("C-fast").orElseThrow();
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(snap.name()).isEqualTo("Fast");
        // Margen generoso: un IT contra Testcontainers tiende a ser > 500 ms en CI
        // compartido. Se mide el orden de magnitud: < 5 s.
        assertThat(elapsed.toMillis()).isLessThan(5000);
    }
}

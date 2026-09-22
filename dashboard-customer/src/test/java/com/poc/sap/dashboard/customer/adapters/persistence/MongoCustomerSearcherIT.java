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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT de busqueda por {@code fiscal.taxId} (UI-001 H-3 AC-3): lee directo de
 * {@code customers_current} y devuelve matches ordenados por {@code code}.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "docker.available", matches = "true")
class MongoCustomerSearcherIT {

    @Container
    static final MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    private MongoClient client;
    private MongoCustomerSearcher searcher;

    @BeforeEach
    void setUp() {
        client = MongoClients.create(mongo.getReplicaSetUrl("customer"));
        com.mongodb.client.MongoDatabase db = client.getDatabase("customer");
        db.getCollection("customers_current").drop();
        com.mongodb.client.MongoCollection<Document> coll = db.getCollection("customers_current");
        coll.insertMany(List.of(
                new Document("_id", "C-1").append("code", "CUST-001").append("name", "Acme")
                        .append("status", "ACTIVE")
                        .append("fiscal", new Document("taxId", "B12345678")
                                .append("vatNumber", null).append("legalName", "Acme").append("taxResidency", "ES")),
                new Document("_id", "C-2").append("code", "CUST-002").append("name", "Beta")
                        .append("status", "ACTIVE")
                        .append("fiscal", new Document("taxId", "B12345678")
                                .append("vatNumber", null).append("legalName", "Beta").append("taxResidency", "ES")),
                new Document("_id", "C-3").append("code", "CUST-003").append("name", "Gamma")
                        .append("status", "ACTIVE")
                        .append("fiscal", new Document("taxId", "B99999999")
                                .append("vatNumber", null).append("legalName", "Gamma").append("taxResidency", "ES"))
        ));
        searcher = new MongoCustomerSearcher(client, "customer", "customers_current");
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void findsByTaxIdAndOrdersByCode() {
        List<CustomerSnapshot> matches = searcher.findByTaxId("B12345678");

        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).code()).isEqualTo("CUST-001");
        assertThat(matches.get(1).code()).isEqualTo("CUST-002");
    }

    @Test
    void emptyForUnknownTaxId() {
        assertThat(searcher.findByTaxId("missing")).isEmpty();
    }

    @Test
    void emptyForBlankTaxId() {
        assertThat(searcher.findByTaxId("")).isEmpty();
        assertThat(searcher.findByTaxId(null)).isEmpty();
    }
}

package com.poc.sap.dashboard.customer.adapters.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.poc.sap.dashboard.customer.adapters.search.ElasticsearchCustomerHistoryReader.HistoryDoc;
import com.poc.sap.dashboard.customer.domain.CustomerSnapshot;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT del {@link ElasticsearchCustomerHistoryReader} (UI-001 H-3): lee
 * versiones del indice {@code customers_history} y devuelve de mas reciente a
 * mas antigua, agrupando por payloadHash (mismo criterio que customer-app).
 */
@Testcontainers
@EnabledIfSystemProperty(named = "docker.available", matches = "true")
class ElasticsearchCustomerHistoryReaderIT {

    @Container
    static final ElasticsearchContainer es = new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.13.4");

    private ElasticsearchClient client;
    private ElasticsearchCustomerHistoryReader reader;

    @BeforeEach
    void setUp() throws Exception {
        RestClient restClient = RestClient.builder(new HttpHost("localhost",
                es.getMappedPort(9200), "http")).build();
        ElasticsearchTransport transport =
                new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
        // Asegurar el indice con un mapping explicito: el campo timestamp es fecha,
        // payloadHash y customerId son keyword.
        if (client.indices().exists(b -> b.index("customers_history")).value()) {
            client.indices().delete(b -> b.index("customers_history"));
        }
        client.indices().create(c -> c.index("customers_history")
                .mappings(m -> m.properties("customerId", p -> p.keyword(k -> k))
                        .properties("payloadHash", p -> p.keyword(k -> k))
                        .properties("timestamp", p -> p.date(d -> d))));

        reader = new ElasticsearchCustomerHistoryReader(client, "customers_history");
    }

    @AfterEach
    void tearDown() throws Exception {
        client.shutdown();
    }

    private static void index(ElasticsearchClient c, String id, String customerId, String hash,
                              long epochMillis, String name) throws Exception {
        HistoryDoc doc = new HistoryDoc(id, customerId, hash,
                Instant.ofEpochMilli(epochMillis),
                new CustomerSnapshot(customerId, "CUST-" + customerId, name,
                        CustomerSnapshot.Status.ACTIVE, null, null, null, null));
        c.index(i -> i.index("customers_history").id(id).document(doc));
    }

    @Test
    void readsNewestFirst() throws Exception {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        index(client, "C-1-h-2-ms1", "C-1", "h-2", t0.toEpochMilli(), "ACME nueva");
        index(client, "C-1-h-1-ms2", "C-1", "h-1", t0.plusSeconds(60).toEpochMilli(), "ACME vieja");
        client.indices().refresh(r -> r.index("customers_history"));

        List<ElasticsearchCustomerHistoryReader.HistoryVersion> versions = reader.historyOf("C-1");

        assertThat(versions).hasSize(2);
        // Más reciente primero (timestamp mayor primero).
        assertThat(versions.get(0).payloadHash()).isEqualTo("h-1");
        assertThat(versions.get(0).timestamp()).isEqualTo(t0.plusSeconds(60));
    }

    @Test
    void emptyForMissingCustomer() throws Exception {
        client.indices().refresh(r -> r.index("customers_history"));

        assertThat(reader.historyOf("missing")).isEmpty();
    }

    @Test
    void snapshotRoundtrips() throws Exception {
        index(client, "C-2-h-1", "C-2", "h-1",
                Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), "Roundtrip");
        client.indices().refresh(r -> r.index("customers_history"));

        var version = reader.historyOf("C-2").get(0);

        assertThat(version.snapshot().name()).isEqualTo("Roundtrip");
        assertThat(version.snapshot().code()).isEqualTo("CUST-C-2");
    }
}

package com.poc.sap.dashboard.customer.adapters.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.poc.sap.dashboard.customer.adapters.persistence.MongoCustomerAddressMapper;
import com.poc.sap.dashboard.customer.adapters.persistence.MongoCustomerBankingMapper;
import com.poc.sap.dashboard.customer.adapters.persistence.MongoCustomerContactMapper;
import com.poc.sap.dashboard.customer.adapters.persistence.MongoCustomerFiscalMapper;
import com.poc.sap.dashboard.customer.domain.CustomerSnapshot;
import com.poc.sap.dashboard.customer.domain.port.CustomerHistoryReader;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Adaptador Elasticsearch del historico de Customer (UI-001 H-3). Usa el
 * SDK nativo {@link ElasticsearchClient} (no Spring Data Elasticsearch),
 * con mapeo explicito del documento ES a {@link CustomerSnapshot}.
 */
public class ElasticsearchCustomerHistoryReader implements CustomerHistoryReader {

    private final ElasticsearchClient client;
    private final String index;

    public ElasticsearchCustomerHistoryReader(ElasticsearchClient client, String index) {
        this.client = client;
        this.index = index;
    }

    @Override
    public List<HistoryVersion> historyOf(String entityId) {
        try {
            SearchResponse<HistoryDoc> resp = client.search(s -> s
                    .index(index)
                    .size(1000)
                    .query(Query.of(q -> q.term(t -> t.field("customerId").value(entityId))))
                    .sort(srt -> srt.field(f -> f.field("timestamp").order(SortOrder.Desc))), HistoryDoc.class);
            List<HistoryVersion> out = new ArrayList<>();
            for (Hit<HistoryDoc> h : resp.hits().hits()) {
                HistoryDoc doc = h.source();
                if (doc == null) continue;
                out.add(new HistoryVersion(doc.customerId, doc.payloadHash, doc.timestamp, docToSnapshot(doc)));
            }
            return out;
        } catch (java.io.IOException e) {
            throw new RuntimeException("Error leyendo historico de " + entityId, e);
        }
    }

    private static CustomerSnapshot docToSnapshot(HistoryDoc doc) {
        return new CustomerSnapshot(doc.customerId, doc.code, doc.name, parseStatus(doc.status),
                MongoCustomerAddressMapper.fromBson(asDoc(doc.address)),
                MongoCustomerFiscalMapper.fromBson(asDoc(doc.fiscal)),
                MongoCustomerContactMapper.fromBson(asDoc(doc.contact)),
                MongoCustomerBankingMapper.fromBson(asDoc(doc.banking)));
    }

    private static Document asDoc(Object o) {
        if (o == null) return null;
        if (o instanceof Document d) return d;
        // El SDK mapea objetos anidados a LinkedHashMap; reconvertimos a Document.
        Document d = new Document();
        if (o instanceof java.util.Map<?,?> m) {
            for (var e : m.entrySet()) {
                d.append(e.getKey().toString(), e.getValue());
            }
            return d;
        }
        return null;
    }

    private static CustomerSnapshot.Status parseStatus(String s) {
        if (s == null) return CustomerSnapshot.Status.ACTIVE;
        try {
            return CustomerSnapshot.Status.valueOf(s);
        } catch (Exception e) {
            return CustomerSnapshot.Status.ACTIVE;
        }
    }

    /**
     * Forma del documento en ES. Las features son objetos anidados que el SDK
     * deserializa a Map; los reenviamos a Document para reutilizar los mappers
     * BSON compartidos con la lectura de Mongo.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class HistoryDoc {
        public String id;
        public String customerId;
        public String code;
        public String name;
        public String status;
        public Object address;
        public Object fiscal;
        public Object contact;
        public Object banking;
        public String payloadHash;
        public Instant timestamp;

        public HistoryDoc() {}

        public HistoryDoc(String id, String customerId, String payloadHash, Instant timestamp,
                          CustomerSnapshot snapshot) {
            this.id = id;
            this.customerId = customerId;
            this.payloadHash = payloadHash;
            this.timestamp = timestamp;
            this.code = snapshot.code();
            this.name = snapshot.name();
            this.status = snapshot.status() == null ? null : snapshot.status().name();
            // El SDK mapea fields del snapshot a Map o Document segun el binder;
            // aqui lo dejamos como Map para que Jackson lo serialice igual.
            this.address = snapshot.address();
            this.fiscal = snapshot.fiscal();
            this.contact = snapshot.contact();
            this.banking = snapshot.banking();
        }
    }
}

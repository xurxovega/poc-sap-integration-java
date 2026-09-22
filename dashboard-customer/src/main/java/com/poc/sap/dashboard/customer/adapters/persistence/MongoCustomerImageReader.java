package com.poc.sap.dashboard.customer.adapters.persistence;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.poc.sap.dashboard.customer.domain.CustomerSnapshot;
import com.poc.sap.dashboard.customer.domain.port.CustomerImageReader;
import org.bson.Document;

import java.util.Optional;

/**
 * Adaptador Mongo (driver sync nativo) del snapshot actual de Customer
 * (UI-001 H-3). Lee la coleccion {@code customers_current} del dominio
 * customer y mapea el documento BSON al {@link CustomerSnapshot} local del
 * dashboard. Driver nativo (no Spring Data) para mantener el aislamiento
 * con customer (DashboardIsolationTest).
 */
public class MongoCustomerImageReader implements CustomerImageReader {

    private final MongoClient client;
    private final String database;
    private final String collection;

    public MongoCustomerImageReader(MongoClient client, String database, String collection) {
        this.client = client;
        this.database = database;
        this.collection = collection;
    }

    @Override
    public Optional<CustomerSnapshot> findById(String entityId) {
        Document doc = client.getDatabase(database)
                .getCollection(collection)
                .find(Filters.eq("_id", entityId))
                .first();
        if (doc == null) {
            return Optional.empty();
        }
        return Optional.of(toSnapshot(doc));
    }

    static CustomerSnapshot toSnapshot(Document doc) {
        String id = doc.getString("_id");
        String code = doc.getString("code");
        String name = doc.getString("name");
        String statusStr = doc.getString("status");
        CustomerSnapshot.Status status;
        try {
            status = CustomerSnapshot.Status.valueOf(statusStr);
        } catch (Exception e) {
            status = CustomerSnapshot.Status.ACTIVE;
        }
        return new CustomerSnapshot(id, code, name, status,
                MongoCustomerAddressMapper.fromBson(doc.get("address", Document.class)),
                MongoCustomerFiscalMapper.fromBson(doc.get("fiscal", Document.class)),
                MongoCustomerContactMapper.fromBson(doc.get("contact", Document.class)),
                MongoCustomerBankingMapper.fromBson(doc.get("banking", Document.class)));
    }
}

package com.poc.sap.dashboard.customer.adapters.persistence;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.poc.sap.dashboard.customer.domain.CustomerSnapshot;
import com.poc.sap.dashboard.customer.domain.port.CustomerSearcher;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;

/**
 * Busqueda por {@code fiscal.taxId} (UI-001 H-3 AC-3). Implementacion
 * simple contra Mongo {@code customers_current}, ordenada por codigo cliente.
 */
public class MongoCustomerSearcher implements CustomerSearcher {

    private final MongoClient client;
    private final String database;
    private final String collection;

    public MongoCustomerSearcher(MongoClient client, String database, String collection) {
        this.client = client;
        this.database = database;
        this.collection = collection;
    }

    @Override
    public List<CustomerSnapshot> findByTaxId(String taxId) {
        if (taxId == null || taxId.isBlank()) {
            return List.of();
        }
        Bson filter = Filters.eq("fiscal.taxId", taxId);
        List<CustomerSnapshot> out = new ArrayList<>();
        client.getDatabase(database)
                .getCollection(collection)
                .find(filter)
                .sort(Sorts.ascending("code"))
                .forEach(d -> out.add(MongoCustomerImageReader.toSnapshot(d)));
        return out;
    }
}

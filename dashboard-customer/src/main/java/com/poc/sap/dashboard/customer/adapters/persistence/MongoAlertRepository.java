package com.poc.sap.dashboard.customer.adapters.persistence;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.poc.sap.dashboard.customer.domain.Alert;
import com.poc.sap.dashboard.customer.domain.port.AlertRepository;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Repositorio de alertas operativas (UI-001 H-3 F-9 AC-6). Coleccion
 * {@code alerts} con TTL 30 dias (indices declarados en
 * {@code external-services/mongodb/init.js}).
 */
public class MongoAlertRepository implements AlertRepository {

    private final MongoClient client;
    private final String database;
    private final String collection;

    public MongoAlertRepository(MongoClient client, String database, String collection) {
        this.client = client;
        this.database = database;
        this.collection = collection;
    }

    @Override
    public void save(Alert alert) {
        Document doc = toDoc(alert);
        client.getDatabase(database)
                .getCollection(collection)
                .replaceOne(Filters.eq("alertId", alert.alertId()), doc,
                        new com.mongodb.client.model.ReplaceOptions().upsert(true));
    }

    @Override
    public Optional<Alert> findById(String alertId) {
        Document d = client.getDatabase(database).getCollection(collection)
                .find(Filters.eq("alertId", alertId)).first();
        if (d == null) return Optional.empty();
        return Optional.of(fromDoc(d));
    }

    @Override
    public List<Alert> open() {
        return listAll(Filters.eq("ackedAt", null));
    }

    @Override
    public List<Alert> openByEntity(String entityId) {
        return listAll(Filters.and(
                Filters.eq("entityId", entityId),
                Filters.eq("ackedAt", null)));
    }

    private List<Alert> listAll(org.bson.conversions.Bson filter) {
        List<Alert> out = new ArrayList<>();
        client.getDatabase(database).getCollection(collection)
                .find(filter)
                .sort(Sorts.ascending("openedAt"))
                .forEach(d -> out.add(fromDoc(d)));
        return out;
    }

    static Document toDoc(Alert a) {
        Document d = new Document("alertId", a.alertId())
                .append("entityId", a.entityId())
                .append("severity", a.severity())
                .append("origin", a.origin())
                .append("detail", a.detail())
                .append("openedAt", a.openedAt() == null ? null : Date.from(a.openedAt()));
        d.append("ackedAt", a.ackedAt() == null ? null : Date.from(a.ackedAt()))
                .append("ackedBy", a.ackedBy());
        return d;
    }

    static Alert fromDoc(Document d) {
        Date opened = d.getDate("openedAt");
        Date acked = d.getDate("ackedAt");
        return new Alert(
                d.getString("alertId"),
                d.getString("entityId"),
                d.getString("severity"),
                d.getString("origin"),
                d.getString("detail"),
                opened == null ? null : opened.toInstant(),
                acked == null ? null : acked.toInstant(),
                d.getString("ackedBy"));
    }
}

package com.poc.sap.dashboard.customer.adapters.persistence;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.dashboard.customer.domain.CycleTrace;
import com.poc.sap.dashboard.customer.domain.FeatureState;
import com.poc.sap.dashboard.customer.domain.port.CustomerStateReader;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adaptador Mongo del estado de sincronizacion (UI-001 H-3). Lee
 * {@code sync_state} con el driver nativo (compatible con el shape del
 * modulo customer, pero el dominio del dashboard es local). Reconstruye:
 *
 * <ul>
 *   <li>La cabecera del agregado: ultima transicion de la linea {@code entityId}.</li>
 *   <li>El estado de cada feature por nombre: ultimas transiciones de las lineas
 *       que llevan el patron {@code entityId:FEATURE}.</li>
 *   <li>La traza del ultimo ciclo: todas las transiciones del mismo
 *       {@code cycleId} que la cabecera, en orden.</li>
 * </ul>
 */
public class MongoCustomerStateReader implements CustomerStateReader {

    private final MongoClient client;
    private final String database;
    private final String collection;

    public MongoCustomerStateReader(MongoClient client, String database, String collection) {
        this.client = client;
        this.database = database;
        this.collection = collection;
    }

    @Override
    public FeatureState aggregateOf(String entityId) {
        Document doc = head(entityId);
        return doc == null ? null : toFeatureState(null, doc);
    }

    @Override
    public Map<String, FeatureState> featuresOf(String entityId) {
        Map<String, FeatureState> out = new LinkedHashMap<>();
        for (String feature : new String[]{"ADDRESS", "FISCAL", "CONTACT", "BANKING"}) {
            Document doc = head(entityId + ":" + feature);
            if (doc != null) {
                out.put(feature, toFeatureState(feature, doc));
            }
        }
        return out;
    }

    @Override
    public CycleTrace lastCycleOf(String entityId) {
        Document agg = head(entityId);
        if (agg == null || agg.getString("cycleId") == null) {
            return null;
        }
        String cycleId = agg.getString("cycleId");
        List<Document> allSteps = new ArrayList<>();
        client.getDatabase(database)
                .getCollection(collection)
                .find(Filters.eq("cycleId", cycleId))
                .sort(Sorts.orderBy(Sorts.ascending("seq"), Sorts.ascending("timestamp")))
                .forEach(allSteps::add);
        if (allSteps.isEmpty()) {
            return null;
        }
        Instant start = toInstant(allSteps.get(0).getDate("timestamp"));
        Instant end = toInstant(allSteps.get(allSteps.size() - 1).getDate("timestamp"));
        String hash = agg.getString("payloadHash");
        List<CycleTrace.Step> steps = new ArrayList<>();
        for (Document d : allSteps) {
            steps.add(new CycleTrace.Step(
                    d.getString("entityId"),
                    SyncState.ofCode(d.getInteger("stateCode")),
                    d.getString("detail"),
                    toInstant(d.getDate("timestamp"))));
        }
        return new CycleTrace(cycleId, hash, start, end, steps);
    }

    /** Ultima transicion (cabecera) para una linea: el documento mas reciente por seq. */
    private Document head(String entityId) {
        return client.getDatabase(database)
                .getCollection(collection)
                .find(Filters.eq("entityId", entityId))
                .sort(Sorts.orderBy(Sorts.descending("seq"), Sorts.descending("timestamp")))
                .first();
    }

    private static FeatureState toFeatureState(String feature, Document doc) {
        return new FeatureState(feature,
                SyncState.ofCode(doc.getInteger("stateCode")),
                doc.getString("payloadHash"),
                doc.getString("cycleId"),
                doc.getString("detail"),
                toInstant(doc.getDate("timestamp")));
    }

    private static Instant toInstant(Date d) {
        return d == null ? null : d.toInstant();
    }
}

package com.poc.sap.dashboard.customer.domain.port;

import com.poc.sap.dashboard.customer.domain.CycleTrace;
import com.poc.sap.dashboard.customer.domain.FeatureState;

import java.util.List;
import java.util.Map;

/**
 * Puerto de lectura del estado de sincronizacion (UI-001 H-2): estado del
 * agregado, estado de cada feature y traza del ultimo ciclo, leidos del
 * {@code sync_state} de Mongo. Equivalente al {@code /customers/{id}/state}
 * del customer-app, pero el dashboard NO consume esa API para mantener el
 * aislamiento entre bounded contexts (DashboardIsolationTest).
 */
public interface CustomerStateReader {

    /** Ultima cabecera del agregado (feature == null en el agregado). */
    FeatureState aggregateOf(String entityId);

    /** Estado de cada feature por nombre (ADDRESS, FISCAL, CONTACT, BANKING). */
    Map<String, FeatureState> featuresOf(String entityId);

    /** Traza de pasos del ultimo ciclo del agregado, o null si no hay. */
    CycleTrace lastCycleOf(String entityId);

    /** Lineas del estado completo: agregado + features, en un unico map. */
    default List<FeatureState> linesOf(String entityId) {
        FeatureState agg = aggregateOf(entityId);
        Map<String, FeatureState> features = featuresOf(entityId);
        List<FeatureState> lines = new java.util.ArrayList<>();
        if (agg != null) lines.add(agg);
        features.values().forEach(lines::add);
        return lines;
    }
}

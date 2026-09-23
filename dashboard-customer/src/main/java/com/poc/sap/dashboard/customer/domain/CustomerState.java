package com.poc.sap.dashboard.customer.domain;

import java.util.Map;

/**
 * Lo que el dashboard pinta en la vista por entidad (UI-001 H-2 AC-1, AC-2):
 * cabecera del agregado (el {@link FeatureState} con {@code feature == null}),
 * mapa de features (cada una su ultima transicion), y la traza del ultimo
 * ciclo. Si la entidad no tiene historial, los dos ultimos son null.
 */
public record CustomerState(
        String entityId,
        FeatureState aggregate,
        Map<String, FeatureState> features,
        CycleTrace lastCycle
) {
}

package com.poc.sap.dashboard.customer.application;

import com.poc.sap.dashboard.customer.domain.CustomerSnapshot;
import com.poc.sap.dashboard.customer.domain.CustomerState;
import com.poc.sap.dashboard.customer.domain.port.CustomerImageReader;
import com.poc.sap.dashboard.customer.domain.port.CustomerStateReader;

import java.util.Optional;

/**
 * Vista por entidad (UI-001 H-2 AC-1): compone el snapshot (de Mongo) con el
 * estado de sincronizacion (de {@code sync_state}) y la traza del ultimo
 * ciclo. Devuelve {@link Optional#empty()} si la entidad no existe en Mongo.
 */
public class GetCustomerOverview {

    private final CustomerImageReader images;
    private final CustomerStateReader states;

    public GetCustomerOverview(CustomerImageReader images, CustomerStateReader states) {
        this.images = images;
        this.states = states;
    }

    public Optional<CustomerSnapshot> snapshot(String entityId) {
        return images.findById(entityId);
    }

    public Optional<CustomerState> stateOf(String entityId) {
        Optional<CustomerSnapshot> snap = images.findById(entityId);
        if (snap.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CustomerState(entityId,
                states.aggregateOf(entityId),
                states.featuresOf(entityId),
                states.lastCycleOf(entityId)));
    }

    /**
     * Serializa un {@link CustomerState} en JSON bonito para la pagina de
     * detalle /state (vista debug del operador). No usa Jackson para no
     * importar nada del framework en la capa de aplicacion.
     */
    public static String pretty(com.poc.sap.dashboard.customer.domain.CustomerState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("CustomerState{\n");
        sb.append("  entityId: ").append(state.entityId()).append('\n');
        if (state.aggregate() != null) {
            sb.append("  aggregate: ").append(state.aggregate().state())
                    .append(" (hash=").append(state.aggregate().payloadHash())
                    .append(", cycle=").append(state.aggregate().cycleId())
                    .append(", at=").append(state.aggregate().at()).append(")\n");
        }
        state.features().forEach((k, f) -> sb.append("  feature ").append(k)
                .append(": ").append(f.state()).append('\n'));
        if (state.lastCycle() != null) {
            sb.append("  lastCycle: ").append(state.lastCycle().cycleId())
                    .append(" (").append(state.lastCycle().steps().size()).append(" pasos)\n");
        }
        sb.append('}');
        return sb.toString();
    }
}

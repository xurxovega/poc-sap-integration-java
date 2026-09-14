package com.poc.sap.customer.application.general;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.customer.domain.CustomerFeature;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Donde esta cada parte de un cliente (sdd/customer/sincronizacion-cliente.md R-9;
 * ADR-0010): estado del agregado y de cada linea de feature, con el hash y el
 * instante de su ultima transicion. Es lo que operacion mira cuando llega una
 * alerta de sincronizacion parcial.
 */
public class CustomerStateUseCase {

    private static final String DOMAIN = "customer";

    private final SyncStateRepositoryPort stateRepo;

    public CustomerStateUseCase(SyncStateRepositoryPort stateRepo) {
        this.stateRepo = stateRepo;
    }

    /** Ultimo estado conocido de una linea. */
    public record LineState(SyncState state, String payloadHash, Instant at) {}

    /** Estado del agregado y de cada feature; una feature sin historial no aparece. */
    public record EntityState(String entityId, LineState aggregate, Map<String, LineState> features) {}

    public EntityState of(String customerId) {
        Map<String, LineState> features = new LinkedHashMap<>();
        for (CustomerFeature f : CustomerFeature.values()) {
            LineState line = last(customerId + ":" + f.name());
            if (line != null) {
                features.put(f.name(), line);
            }
        }
        return new EntityState(customerId, last(customerId), features);
    }

    private LineState last(String entityId) {
        List<SyncStateTransition> history = stateRepo.history(DOMAIN, entityId);
        if (history.isEmpty()) {
            return null;
        }
        SyncStateTransition t = history.get(history.size() - 1);
        return new LineState(t.to(), t.payloadHash(), t.timestamp());
    }
}

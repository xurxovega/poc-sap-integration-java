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
 * ADR-0010): estado del agregado y de cada linea de feature, con el hash, el
 * motivo y el instante de su ultima transicion, mas la traza de pasos del ultimo
 * ciclo. Es lo que operacion mira cuando llega una alerta de sincronizacion
 * parcial.
 *
 * <p>Cada linea se resuelve con UNA lectura de cabecera y el ciclo con UNA
 * consulta por indice. Antes se leia el historial completo cinco veces por
 * peticion (auditoria 2026-09-18 N8).
 */
public class CustomerStateUseCase {

    private static final String DOMAIN = "customer";

    private final SyncStateRepositoryPort stateRepo;

    public CustomerStateUseCase(SyncStateRepositoryPort stateRepo) {
        this.stateRepo = stateRepo;
    }

    /** Ultimo estado conocido de una linea, con el motivo si termino en error. */
    public record LineState(SyncState state, String payloadHash, String cycleId, String detail, Instant at) {}

    /** Un paso de la traza: que linea, en que estado quedo y por que. */
    public record Step(String line, SyncState state, String detail, Instant at) {}

    /** Traza de pasos de un envio: todas las transiciones del ciclo, en orden. */
    public record CycleTrace(String cycleId, String payloadHash, Instant startedAt, Instant endedAt,
                             List<Step> steps) {}

    /**
     * Estado del agregado y de cada feature; una feature sin historial no aparece.
     * {@code lastCycle} es {@code null} para ciclos anteriores a la traza: no la
     * hubo, y decirlo es mas honesto que inventarla.
     */
    public record EntityState(String entityId, LineState aggregate, Map<String, LineState> features,
                              CycleTrace lastCycle) {}

    public EntityState of(String customerId) {
        Map<String, LineState> features = new LinkedHashMap<>();
        for (CustomerFeature f : CustomerFeature.values()) {
            LineState line = last(customerId + ":" + f.name());
            if (line != null) {
                features.put(f.name(), line);
            }
        }
        LineState aggregate = last(customerId);
        return new EntityState(customerId, aggregate, features, traceOf(aggregate));
    }

    private LineState last(String entityId) {
        return stateRepo.lastTransition(DOMAIN, entityId)
                .map(t -> new LineState(t.to(), t.payloadHash(), t.cycleId(), t.detail(), t.timestamp()))
                .orElse(null);
    }

    private CycleTrace traceOf(LineState aggregate) {
        if (aggregate == null || aggregate.cycleId() == null) {
            return null;
        }
        List<SyncStateTransition> steps = stateRepo.cycle(DOMAIN, aggregate.cycleId());
        if (steps.isEmpty()) {
            return null;
        }
        return new CycleTrace(aggregate.cycleId(), aggregate.payloadHash(),
                steps.get(0).timestamp(), steps.get(steps.size() - 1).timestamp(),
                steps.stream()
                        .map(t -> new Step(t.entityId(), t.to(), t.detail(), t.timestamp()))
                        .toList());
    }
}

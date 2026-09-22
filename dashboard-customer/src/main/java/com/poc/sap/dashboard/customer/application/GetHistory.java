package com.poc.sap.dashboard.customer.application;

import com.poc.sap.dashboard.customer.domain.port.CustomerHistoryReader;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Historico de versiones de un cliente (UI-001 H-2). Lee de Elasticsearch.
 *
 * <p>{@code of(id)} falla con {@link NoSuchElementException} si no hay ni
 * una version en ES: para el dashboard una entidad sin historico es un error
 * del operador (la busqueda la ha encontrado por snapshot en Mongo, asi que
 * el lector espera al menos una entrada en ES para los snapshots que ya
 * enviamos).
 */
public class GetHistory {

    private final CustomerHistoryReader history;

    public GetHistory(CustomerHistoryReader history) {
        this.history = history;
    }

    public List<CustomerHistoryReader.HistoryVersion> of(String entityId) {
        List<CustomerHistoryReader.HistoryVersion> versions = history.historyOf(entityId);
        if (versions.isEmpty()) {
            throw new NoSuchElementException("Sin historico para customer " + entityId);
        }
        return versions;
    }
}

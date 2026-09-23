package com.poc.sap.dashboard.customer.application;

import com.poc.sap.dashboard.customer.domain.Alert;
import com.poc.sap.dashboard.customer.domain.port.AlertRepository;

import java.util.List;

/**
 * Lista de alertas operativas abiertas (UI-001 H-2 F-9). Lo que pinta la
 * pestana "Alerts" y los badges de la cabecera del dashboard.
 */
public class GetOpenAlerts {

    private final AlertRepository repo;

    public GetOpenAlerts(AlertRepository repo) {
        this.repo = repo;
    }

    public List<Alert> all() {
        return repo.open();
    }

    public List<Alert> byEntity(String entityId) {
        return repo.openByEntity(entityId);
    }
}

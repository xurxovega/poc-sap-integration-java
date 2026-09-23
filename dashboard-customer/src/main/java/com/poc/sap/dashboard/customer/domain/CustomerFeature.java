package com.poc.sap.dashboard.customer.domain;

/**
 * Subconjunto de features de Customer que pinta el dashboard (UI-001 H-2).
 * Es un duplicado deliberado de {@code customer.domain.CustomerFeature}:
 * los bounded contexts customer-app y dashboard-customer NO comparten tipos
 * de dominio (DashboardIsolationTest). Si el customer-app anade una quinta
 * feature, el dashboard sigue mostrando solo las 4 que sabe pintar hasta
 * que se actualice este enum y el use case correspondiente.
 */
public enum CustomerFeature {
    ADDRESS,
    FISCAL,
    CONTACT,
    BANKING;
}

package com.poc.sap.dashboard.customer.domain;

/**
 * Copia local del value object fiscal del cliente (ver
 * {@code customer.domain.feature.fiscal.FiscalData}). El dashboard NO importa
 * el bounded context del customer-app (DashboardIsolationTest), asi que
 * duplica lo que necesita.
 *
 * @param taxId        NIF/CIF
 * @param vatNumber    numero VAT para intracomunitario (opcional)
 * @param legalName    razon social fiscal
 * @param taxResidency pais de residencia fiscal ISO-3166 alpha-2
 */
public record FiscalData(
        String taxId,
        String vatNumber,
        String legalName,
        String taxResidency
) {
}

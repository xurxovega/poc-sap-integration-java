package com.poc.sap.customer.domain.feature.fiscal;

/**
 * Datos fiscales del Customer (feature FISCAL, OVERVIEW.md §2).
 *
 * @param taxId        NIF/CIF (obligatorio)
 * @param vatNumber    numero VAT para intracomunitario (opcional)
 * @param legalName    razon social fiscal (obligatorio)
 * @param taxResidency pais de residencia fiscal ISO-3166 alpha-2 (obligatorio)
 */
public record FiscalData(
        String taxId,
        String vatNumber,
        String legalName,
        String taxResidency
) {
}
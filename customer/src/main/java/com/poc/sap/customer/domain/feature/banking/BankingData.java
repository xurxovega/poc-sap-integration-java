package com.poc.sap.customer.domain.feature.banking;

/**
 * Datos bancarios del Customer (feature BANKING, OVERVIEW.md §2).
 * Engloba la cuenta principal y referencia a mandates asociados.
 *
 * @param iban       IBAN de la cuenta principal (opcional)
 * @param bic        BIC del banco (opcional)
 * @param mandateIds lista de identificadores de mandates asociados (puede ser vacia)
 */
public record BankingData(
        String iban,
        String bic,
        java.util.List<String> mandateIds
) {
    public BankingData {
        mandateIds = mandateIds == null ? java.util.List.of() : java.util.List.copyOf(mandateIds);
    }
}
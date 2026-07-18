package com.poc.sap.customer.domain;

/**
 * Entidad Mandate (mandato bancario) asociada a un Customer (SPEC.md §3).
 *
 * @param id            identificador del mandato
 * @param customerId    identificador del cliente al que pertenece
 * @param iban          IBAN de la cuenta
 * @param bic           BIC del banco (opcional)
 * @param signatureDate fecha de firma (ISO-8601)
 * @param status        estado (ACTIVE/REVOKED/EXPIRED)
 */
public record Mandate(
        String id,
        String customerId,
        String iban,
        String bic,
        String signatureDate,
        Status status
) {
    public enum Status { ACTIVE, REVOKED, EXPIRED }

    public Mandate {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id obligatorio");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId obligatorio");
        }
        if (iban == null || iban.isBlank()) {
            throw new IllegalArgumentException("iban obligatorio");
        }
    }
}

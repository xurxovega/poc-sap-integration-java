package com.poc.sap.dashboard.customer.domain;

import java.util.List;

/**
 * Copia local del value object bancario (ver
 * {@code customer.domain.feature.banking.BankingData}).
 */
public record BankingData(
        String iban,
        String bic,
        List<String> mandateIds
) {
    public BankingData {
        mandateIds = mandateIds == null ? List.of() : List.copyOf(mandateIds);
    }
}

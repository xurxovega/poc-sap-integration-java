package com.poc.sap.customer.adapters.sap.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.poc.sap.customer.domain.feature.banking.BankingData;

import java.util.List;

/**
 * DTO de serializacion para la API S/4 nativa de datos bancarios y mandates.
 */
public record S4BankingDto(
        @JsonProperty("CustomerID") String customerId,
        @JsonProperty("IBAN")       String iban,
        @JsonProperty("BIC")        String bic,
        @JsonProperty("Mandates")   String mandates
) {
    public static S4BankingDto from(BankingData b) {
        return new S4BankingDto(
                "",
                n(b.iban()),
                n(b.bic()),
                mandates(b.mandateIds()));
    }

    private static String n(String s) { return s == null ? "" : s; }

    private static String mandates(List<String> ids) {
        return ids == null || ids.isEmpty() ? "" : String.join(",", ids);
    }
}

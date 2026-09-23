package com.poc.sap.customer.adapters.sap.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.poc.sap.customer.domain.feature.banking.BankingData;

import java.util.List;

/**
 * DTO de serializacion para la API BTP de datos bancarios del Customer
 * (sdd/customer/sincronizacion-datos-bancarios.md §5, ruta BTP).
 * Sustituye al antiguo S4BankingDto, que apuntaba a una API S/4 inexistente
 * (auditoria B3) y serializaba los mandatos como texto separado por comas.
 */
public record BtpBankingDto(
        @JsonProperty("BusinessPartner") String businessPartner,
        @JsonProperty("IBAN")            String iban,
        @JsonProperty("BIC")             String bic,
        @JsonProperty("Mandates")        List<String> mandates
) {
    public static BtpBankingDto from(String entityId, BankingData b) {
        return new BtpBankingDto(n(entityId), n(b.iban()), n(b.bic()), b.mandateIds());
    }

    private static String n(String s) { return s == null ? "" : s; }
}

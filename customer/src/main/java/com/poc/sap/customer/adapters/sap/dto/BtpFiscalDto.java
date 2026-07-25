package com.poc.sap.customer.adapters.sap.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;

/**
 * DTO de serializacion para la API BTP de datos fiscales de Customer.
 */
public record BtpFiscalDto(
        @JsonProperty("BusinessPartner") String businessPartner,
        @JsonProperty("TaxNumber")       String taxNumber,
        @JsonProperty("VATNumber")       String vatNumber,
        @JsonProperty("LegalName")       String legalName,
        @JsonProperty("TaxResidency")    String taxResidency
) {
    public static BtpFiscalDto from(String entityId, FiscalData f) {
        return new BtpFiscalDto(
                n(entityId),
                n(f.taxId()),
                n(f.vatNumber()),
                n(f.legalName()),
                n(f.taxResidency()));
    }

    private static String n(String s) { return s == null ? "" : s; }
}

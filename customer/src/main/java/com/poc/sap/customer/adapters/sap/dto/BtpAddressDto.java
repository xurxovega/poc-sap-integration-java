package com.poc.sap.customer.adapters.sap.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.poc.sap.customer.domain.feature.address.AddressData;

/**
 * DTO de serializacion para la API BTP de direccion de Customer.
 * Mapea {@link AddressData} al contrato JSON esperado por SAP.
 */
public record BtpAddressDto(
        @JsonProperty("BusinessPartner") String businessPartner,
        @JsonProperty("Street")          String street,
        @JsonProperty("City")            String city,
        @JsonProperty("PostalCode")      String postalCode,
        @JsonProperty("Country")         String country,
        @JsonProperty("Region")          String region
) {
    public static BtpAddressDto from(String entityId, AddressData a) {
        return new BtpAddressDto(
                n(entityId),
                n(a.street()),
                n(a.city()),
                n(a.postalCode()),
                n(a.country()),
                n(a.region()));
    }

    private static String n(String s) { return s == null ? "" : s; }
}

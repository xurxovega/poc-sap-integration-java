package com.poc.sap.customer.adapters.sap.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.poc.sap.customer.domain.feature.contact.ContactData;

/**
 * DTO de serializacion para la API BTP de contacto de Customer.
 */
public record BtpContactDto(
        @JsonProperty("BusinessPartner") String businessPartner,
        @JsonProperty("Email")           String email,
        @JsonProperty("Phone")           String phone,
        @JsonProperty("Fax")             String fax,
        @JsonProperty("Website")         String website
) {
    public static BtpContactDto from(ContactData c) {
        return new BtpContactDto(
                "",
                n(c.email()),
                n(c.phone()),
                n(c.fax()),
                n(c.website()));
    }

    private static String n(String s) { return s == null ? "" : s; }
}

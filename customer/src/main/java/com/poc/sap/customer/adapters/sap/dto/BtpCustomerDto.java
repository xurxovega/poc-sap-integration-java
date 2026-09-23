package com.poc.sap.customer.adapters.sap.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.poc.sap.customer.domain.Customer;

/**
 * DTO de serializacion para la API BTP del aggregate Customer.
 */
public record BtpCustomerDto(
        @JsonProperty("BusinessPartner") String businessPartner,
        @JsonProperty("Name")            String name,
        @JsonProperty("Status")          String status
) {
    public static BtpCustomerDto from(Customer c) {
        return new BtpCustomerDto(
                n(c.code()),
                n(c.name()),
                c.status() != null ? c.status().name() : "");
    }

    private static String n(String s) { return s == null ? "" : s; }
}

package com.poc.sap.customer.domain.feature.contact;

/**
 * Datos de contacto del Customer (feature CONTACT, SPEC.md §3).
 *
 * @param email   email principal (opcional)
 * @param phone   telefono (opcional)
 * @param fax     fax (opcional)
 * @param website sitio web (opcional)
 */
public record ContactData(
        String email,
        String phone,
        String fax,
        String website
) {
}
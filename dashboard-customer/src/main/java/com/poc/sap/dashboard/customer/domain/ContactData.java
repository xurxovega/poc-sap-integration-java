package com.poc.sap.dashboard.customer.domain;

/**
 * Copia local del value object de contacto (ver
 * {@code customer.domain.feature.contact.ContactData}). El dashboard es
 * solo lectura: la mutacion/validacion vive en customer-app.
 */
public record ContactData(
        String email,
        String phone,
        String fax,
        String website
) {
}

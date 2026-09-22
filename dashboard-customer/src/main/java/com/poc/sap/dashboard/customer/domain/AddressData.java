package com.poc.sap.dashboard.customer.domain;

/**
 * Copia local del value object de direccion del cliente (TECH.md §7; ver
 * {@code customer.domain.feature.address.AddressData}). Solo lectura del
 * dashboard: la mutacion y validacion de negocio vive en customer-app.
 *
 * @param street     calle y numero
 * @param city       ciudad
 * @param postalCode codigo postal
 * @param country    pais ISO-3166 alpha-2
 * @param region     region/comunidad (puede ser null)
 */
public record AddressData(
        String street,
        String city,
        String postalCode,
        String country,
        String region
) {
}

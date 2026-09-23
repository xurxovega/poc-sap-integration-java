package com.poc.sap.customer.domain.feature.address;

/**
 * Datos de direccion del Customer (feature ADDRESS, OVERVIEW.md §2).
 * Value object inmutable.
 *
 * @param street     calle y numero
 * @param city       ciudad
 * @param postalCode codigo postal
 * @param country    pais ISO-3166 alpha-2
 * @param region     region/comunidad (opcional)
 */
public record AddressData(
        String street,
        String city,
        String postalCode,
        String country,
        String region
) {
}
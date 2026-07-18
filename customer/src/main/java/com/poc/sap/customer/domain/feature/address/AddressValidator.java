package com.poc.sap.customer.domain.feature.address;

import com.poc.sap.common.domain.ValidationResult;

import java.util.regex.Pattern;

/**
 * Validaciones de negocio para la feature ADDRESS (SPEC.md §3, §9).
 * Puro: sin Spring, sin IO.
 */
public final class AddressValidator {

    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern POSTAL = Pattern.compile("^[A-Za-z0-9 \\-]{3,12}$");

    private AddressValidator() {}

    public static ValidationResult validate(AddressData a) {
        if (a == null) {
            return ValidationResult.invalid("address nulo");
        }
        ValidationResult r = ValidationResult.success();
        r = r.and(v -> a.country() != null && COUNTRY.matcher(a.country()).matches(),
                "country debe ser ISO-3166 alpha-2");
        r = r.and(v -> a.city() != null && !a.city().isBlank(), "city obligatoria");
        r = r.and(v -> a.street() != null && !a.street().isBlank(), "street obligatoria");
        r = r.and(v -> a.postalCode() == null || POSTAL.matcher(a.postalCode()).matches(),
                "postalCode invalido");
        return r;
    }
}
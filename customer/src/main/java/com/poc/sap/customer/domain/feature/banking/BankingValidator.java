package com.poc.sap.customer.domain.feature.banking;

import com.poc.sap.common.domain.ValidationResult;

import java.util.regex.Pattern;

/**
 * Validaciones de negocio para la feature BANKING (OVERVIEW.md §2).
 */
public final class BankingValidator {

    private static final Pattern IBAN = Pattern.compile("^[A-Z]{2}[0-9]{2}[A-Z0-9]{10,30}$");
    private static final Pattern BIC = Pattern.compile("^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$");

    private BankingValidator() {}

    public static ValidationResult validate(BankingData b) {
        if (b == null) {
            return ValidationResult.invalid("banking nulo");
        }
        ValidationResult r = ValidationResult.success();
        r = r.and(v -> b.iban() == null || IBAN.matcher(b.iban()).matches(),
                "iban invalido");
        r = r.and(v -> b.bic() == null || BIC.matcher(b.bic()).matches(),
                "bic invalido");
        r = r.and(v -> b.iban() != null || !b.mandateIds().isEmpty(),
                "iban o al menos un mandate obligatorios");
        return r;
    }
}
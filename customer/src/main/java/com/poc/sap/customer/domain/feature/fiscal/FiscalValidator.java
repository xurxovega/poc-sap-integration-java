package com.poc.sap.customer.domain.feature.fiscal;

import com.poc.sap.common.domain.ValidationResult;

import java.util.regex.Pattern;

/**
 * Validaciones de negocio para la feature FISCAL (OVERVIEW.md §2).
 */
public final class FiscalValidator {

    private static final Pattern TAX_ID_ES = Pattern.compile("^[A-Z0-9]{9}$");
    private static final Pattern VAT = Pattern.compile("^[A-Z]{2}[A-Z0-9]{2,15}$");

    private FiscalValidator() {}

    public static ValidationResult validate(FiscalData f) {
        if (f == null) {
            return ValidationResult.invalid("fiscal nulo");
        }
        ValidationResult r = ValidationResult.success();
        r = r.and(v -> f.taxId() != null && !f.taxId().isBlank(), "taxId obligatorio");
        r = r.and(v -> f.legalName() != null && !f.legalName().isBlank(),
                "legalName obligatorio");
        r = r.and(v -> f.taxResidency() != null && !f.taxResidency().isBlank(),
                "taxResidency obligatoria");
        // Null-safe: con ValidationResult.and acumulando errores este predicado
        // se evalua aunque "taxId obligatorio" ya haya fallado.
        r = r.and(v -> f.taxId() == null || !"ES".equals(f.taxResidency())
                        || TAX_ID_ES.matcher(f.taxId()).matches(),
                "taxId ES invalido");
        r = r.and(v -> f.vatNumber() == null || VAT.matcher(f.vatNumber()).matches(),
                "vatNumber invalido");
        return r;
    }
}
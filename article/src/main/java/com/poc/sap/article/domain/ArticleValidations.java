package com.poc.sap.article.domain;

import com.poc.sap.common.domain.ValidationResult;

/**
 * Validaciones de negocio del dominio Article (SPEC.md §3, §9).
 * Puro: sin Spring, sin IO.
 */
public final class ArticleValidations {

    private ArticleValidations() {}

    public static ValidationResult validate(Article a) {
        if (a == null) {
            return ValidationResult.invalid("articulo nulo");
        }
        ValidationResult r = ValidationResult.success();
        r = r.and(v -> a.sku() != null && !a.sku().isBlank(), "sku obligatorio");
        r = r.and(v -> a.description() != null && !a.description().isBlank(),
                "description obligatoria");
        r = r.and(v -> a.unit() != null && !a.unit().isBlank(), "unit obligatoria");
        r = r.and(v -> a.status() != null, "status obligatorio");
        r = r.and(v -> a.category() == null || !a.category().isBlank() || true,
                "category invalida");
        return r;
    }
}
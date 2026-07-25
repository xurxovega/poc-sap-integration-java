package com.poc.sap.common.domain;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Resultado de una validacion de negocio.
 * Value object inmutable.
 *
 * @param valid   true si la validacion paso
 * @param errors  lista de mensajes de error (vacia si valid)
 */
public record ValidationResult(boolean valid, java.util.List<String> errors) {

    public ValidationResult {
        errors = errors == null ? java.util.List.of() : java.util.List.copyOf(errors);
    }

    public static ValidationResult success() {
        return new ValidationResult(true, java.util.List.of());
    }

    public static ValidationResult invalid(String... errors) {
        return new ValidationResult(false, java.util.List.of(errors));
    }

    public static ValidationResult invalid(java.util.List<String> errors) {
        return new ValidationResult(false, errors);
    }

    /**
     * Encadena una validacion adicional ACUMULANDO errores: se evalua
     * siempre, aunque este resultado ya sea invalido, y el error se anade
     * a la lista existente (no corta en el primer fallo).
     */
    public ValidationResult and(Predicate<Void> next, String onError) {
        if (next.test(null)) {
            return this;
        }
        java.util.List<String> merged = new java.util.ArrayList<>(errors);
        merged.add(onError);
        return new ValidationResult(false, merged);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ValidationResult r)) return false;
        return valid == r.valid && Objects.equals(errors, r.errors);
    }

    @Override
    public int hashCode() {
        return Objects.hash(valid, errors);
    }
}

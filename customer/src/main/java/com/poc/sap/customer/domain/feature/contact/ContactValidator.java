package com.poc.sap.customer.domain.feature.contact;

import com.poc.sap.common.domain.ValidationResult;

import java.util.regex.Pattern;

/**
 * Validaciones de negocio para la feature CONTACT (OVERVIEW.md §2).
 */
public final class ContactValidator {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9 \\-]{6,20}$");
    private static final Pattern URL = Pattern.compile("^https?://.+");

    private ContactValidator() {}

    public static ValidationResult validate(ContactData c) {
        if (c == null) {
            return ValidationResult.invalid("contact nulo");
        }
        ValidationResult r = ValidationResult.success();
        r = r.and(v -> c.email() == null || EMAIL.matcher(c.email()).matches(),
                "email mal formado");
        r = r.and(v -> c.phone() == null || PHONE.matcher(c.phone()).matches(),
                "phone mal formado");
        r = r.and(v -> c.fax() == null || PHONE.matcher(c.fax()).matches(),
                "fax mal formado");
        r = r.and(v -> c.website() == null || URL.matcher(c.website()).matches(),
                "website mal formado");
        r = r.and(v -> c.email() != null || c.phone() != null,
                "al menos un canal de contacto (email o phone)");
        return r;
    }
}
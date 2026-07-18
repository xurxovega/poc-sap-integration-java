package com.poc.sap.customer.domain;

import com.poc.sap.common.domain.ValidationResult;
import com.poc.sap.customer.domain.feature.address.AddressData;
import com.poc.sap.customer.domain.feature.address.AddressValidator;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import com.poc.sap.customer.domain.feature.banking.BankingValidator;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import com.poc.sap.customer.domain.feature.contact.ContactValidator;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;
import com.poc.sap.customer.domain.feature.fiscal.FiscalValidator;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Validaciones de negocio del aggregate Customer (SPEC.md §3, §9).
 * Orquesta los validadores de cada feature; se puede invocar sobre el aggregate
 * completo o sobre un subconjunto de features.
 */
public final class CustomerValidations {

    private CustomerValidations() {}

    /** Valida el aggregate Customer completo (todas las features). */
    public static ValidationResult validate(Customer c) {
        return validate(c, EnumSet.allOf(CustomerFeature.class));
    }

    /** Valida solo las features indicadas del Customer. */
    public static ValidationResult validate(Customer c, Set<CustomerFeature> features) {
        if (c == null) {
            return ValidationResult.invalid("customer nulo");
        }
        List<String> errors = new ArrayList<>();

        ValidationResult base = ValidationResult.success()
                .and(v -> c.name() != null && !c.name().isBlank(), "name obligatorio")
                .and(v -> c.status() != null, "status obligatorio");
        if (!base.valid()) {
            errors.addAll(base.errors());
        }

        if (features.contains(CustomerFeature.ADDRESS)) {
            collect(AddressValidator.validate(c.address()), errors, CustomerFeature.ADDRESS);
        }
        if (features.contains(CustomerFeature.FISCAL)) {
            collect(FiscalValidator.validate(c.fiscal()), errors, CustomerFeature.FISCAL);
        }
        if (features.contains(CustomerFeature.CONTACT)) {
            collect(ContactValidator.validate(c.contact()), errors, CustomerFeature.CONTACT);
        }
        if (features.contains(CustomerFeature.BANKING)) {
            collect(BankingValidator.validate(c.banking()), errors, CustomerFeature.BANKING);
        }
        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.invalid(errors);
    }

    /** Valida un Mandate (entidad bancaria asociada, feature BANKING). */
    public static ValidationResult validate(com.poc.sap.customer.domain.Mandate m) {
        return com.poc.sap.customer.domain.feature.banking.BankingValidator.validate(
                new BankingData(
                        m.iban(),
                        m.bic(),
                        List.of()));
    }

    private static void collect(ValidationResult r, List<String> errors, CustomerFeature f) {
        if (!r.valid()) {
            for (String e : r.errors()) {
                errors.add("[" + f.name() + "] " + e);
            }
        }
    }

    /** Conveniencia para validar una feature aislada (sobre su VO). */
    public static ValidationResult validateFeature(CustomerFeature f, Customer c) {
        return switch (f) {
            case ADDRESS -> AddressValidator.validate(c.address());
            case FISCAL -> FiscalValidator.validate(c.fiscal());
            case CONTACT -> ContactValidator.validate(c.contact());
            case BANKING -> BankingValidator.validate(c.banking());
        };
    }

    /** Conveniencia de tipado para validar un AddressData aislado. */
    public static ValidationResult validate(AddressData a) {
        return AddressValidator.validate(a);
    }
}
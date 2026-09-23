package com.poc.sap.customer.bootstrap.web;

import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;

/**
 * Fachada sobre {@code com.poc.sap.common.security.PiiMasker} para el dominio
 * customer: enmascara la PII de un snapshot antes de devolverlo a un cliente
 * externo (sdd/common/seguridad-api.md R-4).
 *
 * <p>La implementacion portable (sobre {@code String}) vive en el shared kernel
 * desde UI-001 H-1 para que el dashboard-customer la reuse sin importar
 * clases del modulo customer. Esta clase conserva la API tipada sobre
 * {@link Customer} por compatibilidad.
 *
 * @deprecated Usar {@code com.poc.sap.common.security.PiiMasker} para nuevo
 *             codigo; este wrapper se conserva una release para no romper a
 *             los controllers existentes (CustomerStateController,
 *             CustomerHistoryController).
 */
@Deprecated
public final class PiiMasker {

    /**
     * Enmascara la PII de un Customer: IBAN/NIF/vatNumber a ultimos 4, email a
     * inicial+dominio, telefono/fax a ultimos 3. BIC, mandateIds y resto sin
     * digitos mixtos pasan tal cual.
     */
    public static Customer mask(Customer c) {
        if (c == null) return null;
        FiscalData f = c.fiscal() == null ? null : new FiscalData(
                com.poc.sap.common.security.PiiMasker.mask(c.fiscal().taxId()),
                com.poc.sap.common.security.PiiMasker.mask(c.fiscal().vatNumber()),
                c.fiscal().legalName(),
                c.fiscal().taxResidency());
        ContactData k = c.contact() == null ? null : new ContactData(
                com.poc.sap.common.security.PiiMasker.maskEmail(c.contact().email()),
                com.poc.sap.common.security.PiiMasker.maskPhone(c.contact().phone()),
                com.poc.sap.common.security.PiiMasker.maskPhone(c.contact().fax()),
                c.contact().website());
        BankingData b = c.banking() == null ? null : new BankingData(
                com.poc.sap.common.security.PiiMasker.mask(c.banking().iban()),
                c.banking().bic(),
                c.banking().mandateIds().stream()
                        .map(com.poc.sap.common.security.PiiMasker::maskPhone).toList());
        return new Customer(c.id(), c.code(), c.name(), c.status(), c.address(), f, k, b);
    }

    /** Delegado al {@code com.poc.sap.common.security.PiiMasker.maskDetail}. */
    public static String maskDetail(String detail) {
        return com.poc.sap.common.security.PiiMasker.maskDetail(detail);
    }

    /** Conservado por compatibilidad con {@code PiiMaskerTest}. Implementacion local
     * con la semantica legacy: deja los ultimos {@code keep} caracteres visibles y
     * enmascara el resto; si la longitud efectiva es <= keep, devuelve todos
     * asteriscos. NO delega en common.security.PiiMasker porque ahi la regla
     * "sin digitos = no se enmascara" cambia el resultado para BIC. */
    static String last(String v, int keep) {
        if (v == null || v.isBlank()) return v;
        String t = v.replace(" ", "");
        return t.length() <= keep ? "*".repeat(t.length())
                                  : "*".repeat(t.length() - keep) + t.substring(t.length() - keep);
    }

    /** Conservado por compatibilidad con {@code PiiMaskerTest}. */
    static String email(String v) {
        return com.poc.sap.common.security.PiiMasker.maskEmail(v);
    }

    private PiiMasker() {}
}

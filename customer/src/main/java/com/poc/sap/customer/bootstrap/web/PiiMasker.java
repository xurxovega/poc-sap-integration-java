package com.poc.sap.customer.bootstrap.web;

import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;

/**
 * Enmascara la PII de un snapshot antes de devolverlo a un cliente externo
 * (sdd/common/seguridad-api.md R-4): IBAN y NIF/IVA solo con los ultimos 4,
 * email con la inicial y el dominio, telefono/fax con los ultimos 3. La
 * direccion y el nombre comercial se devuelven tal cual (no son datos de
 * persona en este dominio B2B).
 */
public final class PiiMasker {

    public static Customer mask(Customer c) {
        if (c == null) return null;
        FiscalData f = c.fiscal() == null ? null : new FiscalData(
                last(c.fiscal().taxId(), 4), last(c.fiscal().vatNumber(), 4), c.fiscal().legalName(), c.fiscal().taxResidency());
        ContactData k = c.contact() == null ? null : new ContactData(
                email(c.contact().email()), last(c.contact().phone(), 3), last(c.contact().fax(), 3), c.contact().website());
        BankingData b = c.banking() == null ? null : new BankingData(
                last(c.banking().iban(), 4), c.banking().bic(), c.banking().mandateIds().stream().map(m -> last(m, 2)).toList());
        return new Customer(c.id(), c.code(), c.name(), c.status(), c.address(), f, k, b);
    }

    static String last(String v, int keep) {
        if (v == null || v.isBlank()) return v;
        String t = v.replace(" ", "");
        return t.length() <= keep ? "*".repeat(t.length()) : "*".repeat(t.length() - keep) + t.substring(t.length() - keep);
    }

    static String email(String v) {
        if (v == null || v.isBlank() || !v.contains("@")) return v == null ? null : "***";
        int at = v.indexOf('@');
        return v.charAt(0) + "***" + v.substring(at);
    }

    private PiiMasker() {}
}

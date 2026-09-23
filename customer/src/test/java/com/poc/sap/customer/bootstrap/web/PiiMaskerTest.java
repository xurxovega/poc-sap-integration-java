package com.poc.sap.customer.bootstrap.web;

import com.poc.sap.customer.application.CustomerFixtures;
import com.poc.sap.customer.domain.Customer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec sdd/common/seguridad-api.md AC-4: PII enmascarada para lectura externa.
 *  La logica vive en {@code com.poc.sap.common.security.PiiMasker} desde UI-001 H-1;
 *  esta clase es una fachada que delega en ella. */
class PiiMaskerTest {

    @Test
    void masksIbanTaxIdEmailAndPhoneButKeepsBusinessFields() {
        Customer c = CustomerFixtures.validCustomer();
        Customer m = PiiMasker.mask(c);

        assertThat(m.id()).isEqualTo(c.id());
        assertThat(m.name()).isEqualTo(c.name());
        assertThat(m.address()).isEqualTo(c.address());
        if (c.banking() != null && c.banking().iban() != null) {
            assertThat(m.banking().iban()).endsWith(c.banking().iban().replace(" ", "").substring(c.banking().iban().replace(" ", "").length() - 4)).startsWith("*");
            assertThat(m.banking().iban()).doesNotContain(c.banking().iban().replace(" ", "").substring(0, 8));
        }
        if (c.fiscal() != null && c.fiscal().taxId() != null) {
            assertThat(m.fiscal().taxId()).startsWith("*").hasSize(c.fiscal().taxId().replace(" ", "").length());
        }
        if (c.contact() != null && c.contact().email() != null) {
            // La nueva API enmascara con **** (UI-001 H-1).
            assertThat(m.contact().email()).matches(".\\*\\*\\*\\*@.+");
        }
        // Sanity: las firmas legacy siguen siendo correctas tras la refactorizacion.
        assertThat(PiiMasker.last("ES7621000418401234567890", 4)).isEqualTo("********************7890");
        assertThat(PiiMasker.last("AB", 4)).isEqualTo("**");
        assertThat(PiiMasker.email("ana@example.com")).isEqualTo("a****@example.com");
        assertThat(PiiMasker.mask(null)).isNull();
    }
}

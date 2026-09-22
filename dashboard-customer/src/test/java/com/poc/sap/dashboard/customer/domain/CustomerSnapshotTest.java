package com.poc.sap.dashboard.customer.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Invariantes del record {@link CustomerSnapshot} (UI-001 H-2): agregado
 * raiz de la lectura del dashboard. Cada {@code null} en el constructor esta
 * prohibido en obligatorios; las features no obligatorias admiten null.
 */
class CustomerSnapshotTest {

    @Test
    void rejectsBlankEntityId() {
        assertThatThrownBy(() -> new CustomerSnapshot(null, "CUST-1", "Acme",
                CustomerSnapshot.Status.ACTIVE, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityId");
        assertThatThrownBy(() -> new CustomerSnapshot("  ", "CUST-1", "Acme",
                CustomerSnapshot.Status.ACTIVE, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankCode() {
        assertThatThrownBy(() -> new CustomerSnapshot("C-1", "", "Acme",
                CustomerSnapshot.Status.ACTIVE, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");
    }

    @Test
    void rejectsNullName() {
        assertThatThrownBy(() -> new CustomerSnapshot("C-1", "CUST-1", null,
                CustomerSnapshot.Status.ACTIVE, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsNullStatus() {
        assertThatThrownBy(() -> new CustomerSnapshot("C-1", "CUST-1", "Acme",
                null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
    }

    @Test
    void allowsAllFeatureBlanksForReadOnlySnapshot() {
        // El dashboard NO exige todas las features: un cliente sin fiscal
        // (porque nunca se ha sincronizado esa parte) debe poder leerse.
        CustomerSnapshot s = new CustomerSnapshot("C-1", "CUST-1", "Acme",
                CustomerSnapshot.Status.ACTIVE,
                new AddressData("Calle 1", "Madrid", "28001", "ES", "M"),
                null, null, null);
        assertThat(s.address()).isNotNull();
        assertThat(s.fiscal()).isNull();
        assertThat(s.contact()).isNull();
        assertThat(s.banking()).isNull();
    }
}

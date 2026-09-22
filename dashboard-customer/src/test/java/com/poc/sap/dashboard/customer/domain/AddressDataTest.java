package com.poc.sap.dashboard.customer.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests del record {@link AddressData}: solo invariantes; la validacion de
 * negocio ya vive en el modulo customer (el dashboard es solo lectura).
 */
class AddressDataTest {

    @Test
    void isPublicReadOnlyValueObject() {
        AddressData a = new AddressData("Calle 1", "Madrid", "28001", "ES", "M");
        assertThat(a.street()).isEqualTo("Calle 1");
        assertThat(a.city()).isEqualTo("Madrid");
        assertThat(a.postalCode()).isEqualTo("28001");
        assertThat(a.country()).isEqualTo("ES");
        assertThat(a.region()).isEqualTo("M");
    }

    @Test
    void regionMayBeNull() {
        AddressData a = new AddressData("Calle 1", "Madrid", "28001", "ES", null);
        assertThat(a.region()).isNull();
    }
}

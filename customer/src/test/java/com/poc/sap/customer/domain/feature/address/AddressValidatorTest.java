package com.poc.sap.customer.domain.feature.address;

import com.poc.sap.common.domain.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AddressValidatorTest {

    @Test
    void validAddressPasses() {
        AddressData a = new AddressData("Calle 1", "Madrid", "28001", "ES", "M");
        assertThat(AddressValidator.validate(a).valid()).isTrue();
    }

    @Test
    void missingCityFails() {
        AddressData a = new AddressData("Calle 1", "", "28001", "ES", null);
        assertThat(AddressValidator.validate(a).valid()).isFalse();
    }

    @Test
    void invalidCountryFails() {
        AddressData a = new AddressData("Calle 1", "Madrid", "28001", "Spain", null);
        assertThat(AddressValidator.validate(a).valid()).isFalse();
    }

    @Test
    void invalidPostalCodeFails() {
        AddressData a = new AddressData("Calle 1", "Madrid", "x".repeat(20), "ES", null);
        assertThat(AddressValidator.validate(a).valid()).isFalse();
    }

    @Test
    void nullAddressFails() {
        assertThat(AddressValidator.validate(null).valid()).isFalse();
    }
}
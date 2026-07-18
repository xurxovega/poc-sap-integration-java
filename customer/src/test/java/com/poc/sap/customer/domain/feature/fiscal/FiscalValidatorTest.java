package com.poc.sap.customer.domain.feature.fiscal;

import com.poc.sap.common.domain.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FiscalValidatorTest {

    @Test
    void validFiscalPasses() {
        FiscalData f = new FiscalData("A12345678", null, "Acme", "ES");
        assertThat(FiscalValidator.validate(f).valid()).isTrue();
    }

    @Test
    void missingTaxIdFails() {
        FiscalData f = new FiscalData("", null, "Acme", "ES");
        assertThat(FiscalValidator.validate(f).valid()).isFalse();
    }

    @Test
    void missingLegalNameFails() {
        FiscalData f = new FiscalData("A12345678", null, "", "ES");
        assertThat(FiscalValidator.validate(f).valid()).isFalse();
    }

    @Test
    void invalidEsTaxIdFails() {
        FiscalData f = new FiscalData("12", null, "Acme", "ES");
        assertThat(FiscalValidator.validate(f).valid()).isFalse();
    }

    @Test
    void invalidVatNumberFails() {
        FiscalData f = new FiscalData("A12345678", "invalidvat", "Acme", "ES");
        assertThat(FiscalValidator.validate(f).valid()).isFalse();
    }

    @Test
    void nullFiscalFails() {
        assertThat(FiscalValidator.validate(null).valid()).isFalse();
    }
}
package com.poc.sap.customer.domain.feature.banking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BankingValidatorTest {

    @Test
    void validIbanPasses() {
        BankingData b = new BankingData("ES7621000418401234567890", "BBVAESMM", List.of());
        assertThat(BankingValidator.validate(b).valid()).isTrue();
    }

    @Test
    void shortIbanFails() {
        BankingData b = new BankingData("ES12", null, List.of());
        assertThat(BankingValidator.validate(b).valid()).isFalse();
    }

    @Test
    void invalidBicFails() {
        BankingData b = new BankingData("ES7621000418401234567890", "badBIC", List.of());
        assertThat(BankingValidator.validate(b).valid()).isFalse();
    }

    @Test
    void mandateIdWithoutIbanPasses() {
        BankingData b = new BankingData(null, null, List.of("M-1"));
        assertThat(BankingValidator.validate(b).valid()).isTrue();
    }

    @Test
    void emptyBankingWithoutMandatesFails() {
        BankingData b = new BankingData(null, null, List.of());
        assertThat(BankingValidator.validate(b).valid()).isFalse();
    }

    @Test
    void nullBankingFails() {
        assertThat(BankingValidator.validate(null).valid()).isFalse();
    }
}
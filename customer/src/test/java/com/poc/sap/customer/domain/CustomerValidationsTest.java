package com.poc.sap.customer.domain;

import com.poc.sap.customer.domain.feature.address.AddressData;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerValidationsTest {

    private Customer valid() {
        return new Customer("C-1", "CUST-001", "Acme", Customer.Status.ACTIVE,
                new AddressData("Calle 1", "Madrid", "28001", "ES", "M"),
                new FiscalData("A12345678", null, "Acme", "ES"),
                new ContactData("info@acme.com", "+34 600 000 000", null, null),
                new BankingData("ES7621000418401234567890", "BBVAESMM", java.util.List.of()));
    }

    @Test
    void validCustomerPassesAllFeatures() {
        assertThat(CustomerValidations.validate(valid()).valid()).isTrue();
    }

    @Test
    void nullCustomerFails() {
        assertThat(CustomerValidations.validate((Customer) null).valid()).isFalse();
    }

    @Test
    void missingNameFails() {
        Customer c = new Customer("C-1", "CUST-001", "", Customer.Status.ACTIVE,
                valid().address(), valid().fiscal(), valid().contact(), valid().banking());
        assertThat(CustomerValidations.validate(c).valid()).isFalse();
    }

    @Test
    void nullStatusFails() {
        Customer c = new Customer("C-1", "CUST-001", "Acme", null,
                valid().address(), valid().fiscal(), valid().contact(), valid().banking());
        assertThat(CustomerValidations.validate(c).valid()).isFalse();
    }

    @Test
    void partialValidationSkipsInvalidFeatures() {
        Customer c = new Customer("C-1", "CUST-001", "Acme", Customer.Status.ACTIVE,
                new AddressData("", "", "", "Spain", null),
                new FiscalData("A12345678", null, "Acme", "ES"),
                new ContactData("info@acme.com", "+34 600 000 000", null, null),
                new BankingData(null, null, java.util.List.of("M-1")));
        Set<CustomerFeature> only = EnumSet.of(CustomerFeature.FISCAL, CustomerFeature.BANKING);
        assertThat(CustomerValidations.validate(c, only).valid()).isTrue();
    }

    @Test
    void partialValidationOnlyAddressCatchAddressErrors() {
        Customer c = new Customer("C-1", "CUST-001", "Acme", Customer.Status.ACTIVE,
                new AddressData("", "", "", "Spain", null),
                new FiscalData("A12345678", null, "Acme", "ES"),
                new ContactData("info@acme.com", null, null, null),
                new BankingData(null, null, java.util.List.of("M-1")));
        var r = CustomerValidations.validate(c, EnumSet.of(CustomerFeature.ADDRESS));
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anyMatch(e -> e.contains("[ADDRESS]"));
    }
}
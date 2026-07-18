package com.poc.sap.customer.domain.feature.contact;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContactValidatorTest {

    @Test
    void validContactWithOnlyEmailPasses() {
        ContactData c = new ContactData("info@acme.com", null, null, null);
        assertThat(ContactValidator.validate(c).valid()).isTrue();
    }

    @Test
    void invalidEmailFails() {
        ContactData c = new ContactData("not-an-email", null, null, null);
        assertThat(ContactValidator.validate(c).valid()).isFalse();
    }

    @Test
    void invalidPhoneFails() {
        ContactData c = new ContactData("info@acme.com", "abc", null, null);
        assertThat(ContactValidator.validate(c).valid()).isFalse();
    }

    @Test
    void noChannelFails() {
        ContactData c = new ContactData(null, null, null, null);
        assertThat(ContactValidator.validate(c).valid()).isFalse();
    }

    @Test
    void invalidWebsiteFails() {
        ContactData c = new ContactData("info@acme.com", null, null, "not-a-url");
        assertThat(ContactValidator.validate(c).valid()).isFalse();
    }
}
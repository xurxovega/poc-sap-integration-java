package com.poc.sap.customer.adapters.persistence;

import com.poc.sap.customer.domain.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test unit del {@link SqlServerCustomerRepository} (TECH.md §7). Mockea
 * {@link CustomerJpaRepository} y verifica el mapeo {@code CustomerEntity ->
 * Customer} (reparto entre las 4 features).
 */
@ExtendWith(MockitoExtension.class)
class SqlServerCustomerRepositoryTest {

    @Mock CustomerJpaRepository jpa;
    private SqlServerCustomerRepository repo;

    @BeforeEach
    void setUp() {
        repo = new SqlServerCustomerRepository(jpa);
    }

    private CustomerEntity entity() {
        CustomerEntity e = new CustomerEntity();
        e.setId("C-1");
        e.setCode("CUST-001");
        e.setName("Acme");
        e.setStatus("ACTIVE");
        e.setStreet("Calle 1");
        e.setCity("Madrid");
        e.setPostalCode("28001");
        e.setCountry("ES");
        e.setRegion("M");
        e.setTaxId("A12345678");
        e.setLegalName("Acme SL");
        e.setTaxResidency("ES");
        e.setEmail("info@acme.com");
        e.setPhone("+34 600000000");
        e.setIban("ES7621000418401234567890");
        e.setBic("BBVAESMM");
        return e;
    }

    @Test
    void fetchEmptyReturnsOptional() {
        when(jpa.findById("C-missing")).thenReturn(Optional.empty());

        assertThat(repo.fetch("C-missing")).isEmpty();
    }

    @Test
    void fetchMapsAllFeatures() {
        when(jpa.findById("C-1")).thenReturn(Optional.of(entity()));

        Customer c = repo.fetch("C-1").orElseThrow();

        assertThat(c.id()).isEqualTo("C-1");
        assertThat(c.code()).isEqualTo("CUST-001");
        assertThat(c.status()).isEqualTo(Customer.Status.ACTIVE);
        assertThat(c.address().street()).isEqualTo("Calle 1");
        assertThat(c.fiscal().taxId()).isEqualTo("A12345678");
        assertThat(c.fiscal().legalName()).isEqualTo("Acme SL");
        assertThat(c.fiscal().taxResidency()).isEqualTo("ES");
        assertThat(c.contact().email()).isEqualTo("info@acme.com");
        assertThat(c.banking().iban()).isEqualTo("ES7621000418401234567890");
        assertThat(c.banking().bic()).isEqualTo("BBVAESMM");
    }

    @Test
    void nullStatusDefaultsToActive() {
        CustomerEntity e = entity();
        e.setStatus(null);
        when(jpa.findById("C-1")).thenReturn(Optional.of(e));

        Customer c = repo.fetch("C-1").orElseThrow();

        assertThat(c.status()).isEqualTo(Customer.Status.ACTIVE);
    }

    @Test
    void missingLegalNameFallsBackToName() {
        CustomerEntity e = entity();
        e.setLegalName(null);
        when(jpa.findById("C-1")).thenReturn(Optional.of(e));

        Customer c = repo.fetch("C-1").orElseThrow();

        assertThat(c.fiscal().legalName()).isEqualTo("Acme");
    }

    @Test
    void missingTaxResidencyFallsBackToCountry() {
        CustomerEntity e = entity();
        e.setTaxResidency(null);
        when(jpa.findById("C-1")).thenReturn(Optional.of(e));

        Customer c = repo.fetch("C-1").orElseThrow();

        assertThat(c.fiscal().taxResidency()).isEqualTo("ES");
    }
}
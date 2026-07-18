package com.poc.sap.customer.adapters.persistence;

import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.feature.address.AddressData;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;
import com.poc.sap.customer.domain.port.CustomerLegacyRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Adaptador JPA del repositorio legacy de Customer (SQL Server).
 * Reparte las columnas de la tabla legacy entre las 4 features del aggregate.
 */
@Repository
public class SqlServerCustomerRepository implements CustomerLegacyRepositoryPort {

    private final CustomerJpaRepository jpa;

    public SqlServerCustomerRepository(CustomerJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Customer> fetch(String entityId) {
        return jpa.findById(entityId).map(this::toDomain);
    }

    private Customer toDomain(CustomerEntity e) {
        AddressData address = new AddressData(
                e.getStreet(), e.getCity(), e.getPostalCode(), e.getCountry(), e.getRegion());
        FiscalData fiscal = new FiscalData(
                e.getTaxId(), e.getVatNumber(),
                e.getLegalName() != null ? e.getLegalName() : e.getName(),
                e.getTaxResidency() != null ? e.getTaxResidency() : e.getCountry());
        ContactData contact = new ContactData(
                e.getEmail(), e.getPhone(), e.getFax(), e.getWebsite());
        BankingData banking = new BankingData(e.getIban(), e.getBic(), java.util.List.of());
        return new Customer(
                e.getId(), e.getCode(), e.getName(),
                e.getStatus() != null ? Customer.Status.valueOf(e.getStatus()) : Customer.Status.ACTIVE,
                address, fiscal, contact, banking);
    }
}
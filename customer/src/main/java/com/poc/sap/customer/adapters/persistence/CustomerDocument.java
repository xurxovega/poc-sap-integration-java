package com.poc.sap.customer.adapters.persistence;

import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.feature.address.AddressData;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Document(collection = "customers_current")
public class CustomerDocument {

    @Id
    private String id;
    private String code;
    private String name;
    private String status;

    private AddressData address;
    private FiscalData fiscal;
    private ContactData contact;
    private BankingData banking;

    public static CustomerDocument fromDomain(Customer c) {
        CustomerDocument d = new CustomerDocument();
        d.id = c.id();
        d.code = c.code();
        d.name = c.name();
        d.status = c.status() != null ? c.status().name() : null;
        d.address = c.address();
        d.fiscal = c.fiscal();
        d.contact = c.contact();
        d.banking = c.banking() != null
                ? new BankingData(c.banking().iban(), c.banking().bic(),
                        c.banking().mandateIds() != null ? c.banking().mandateIds() : List.of())
                : new BankingData(null, null, List.of());
        return d;
    }

    public Customer toDomain() {
        return new Customer(
                id, code, name,
                status != null ? Customer.Status.valueOf(status) : Customer.Status.ACTIVE,
                address, fiscal, contact,
                banking != null ? banking : new BankingData(null, null, List.of()));
    }

    public String getId() { return id; }
}
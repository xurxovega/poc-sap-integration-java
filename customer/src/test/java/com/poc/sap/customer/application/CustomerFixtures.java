package com.poc.sap.customer.application;

import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.IngestionOrigin;
import com.poc.sap.common.domain.OperationType;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.feature.address.AddressData;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;

import java.util.List;

/**
 * Fabrica de fixtures para tests del dominio Customer.
 * Construye instancias validas/invalidas reutilizables.
 */
public final class CustomerFixtures {

    private CustomerFixtures() {}

    public static Customer validCustomer() {
        return new Customer(
                "C-1", "CUST-001", "Acme", Customer.Status.ACTIVE,
                new AddressData("Calle 1", "Madrid", "28001", "ES", "M"),
                new FiscalData("A12345678", null, "Acme", "ES"),
                new ContactData("info@acme.com", "+34 600 000 000", null, null),
                new BankingData("ES7621000418401234567890", "BBVAESMM", List.of()));
    }

    public static Customer invalidAddressCustomer() {
        Customer base = validCustomer();
        return new Customer(
                base.id(), base.code(), base.name(), base.status(),
                new AddressData("", "", "", "Spain", null),
                base.fiscal(), base.contact(), base.banking());
    }

    public static IngestionMessage ingestionMessage(String entityId, OperationType op, String hash) {
        return new IngestionMessage(
                entityId,
                "customer",
                op != null ? op : OperationType.UPDATE,
                IngestionOrigin.CDC,
                hash,
                "{}");
    }

    public static IngestionMessage ingestionMessage() {
        return ingestionMessage("C-1", OperationType.UPDATE, "hash-001");
    }
}
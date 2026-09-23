package com.poc.sap.customer.domain;

import com.poc.sap.customer.domain.feature.address.AddressData;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;

/**
 * Entidad Customer del dominio (OVERVIEW.md §2), agregado raiz.
 * Se compone de 4 features de datos independientes:
 * {@link AddressData}, {@link FiscalData}, {@link ContactData}, {@link BankingData}.
 *
 * <p>Cada feature puede validarse, enviarse a SAP y reintentarse de forma
 * independiente; el orchestrador general decide cuales ejecutar.
 *
 * @param id       identificador legacy (SQL Server)
 * @param code     codigo de cliente (business key)
 * @param name     razon social (display)
 * @param status   estado (ACTIVE/INACTIVE/BLOCKED)
 * @param address  datos de direccion (feature ADDRESS)
 * @param fiscal   datos fiscales (feature FISCAL)
 * @param contact  datos de contacto (feature CONTACT)
 * @param banking  datos bancarios (feature BANKING)
 */
public record Customer(
        String id,
        String code,
        String name,
        Status status,
        AddressData address,
        FiscalData fiscal,
        ContactData contact,
        BankingData banking
) {
    public enum Status { ACTIVE, INACTIVE, BLOCKED }

    public Customer {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id obligatorio");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code obligatorio");
        }
    }
}
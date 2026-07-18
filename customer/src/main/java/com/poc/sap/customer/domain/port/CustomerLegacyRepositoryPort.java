package com.poc.sap.customer.domain.port;

import com.poc.sap.common.domain.port.LegacyRepositoryPort;
import com.poc.sap.customer.domain.Customer;

/**
 * Puerto de lectura del repositorio legacy de Customer (SQL Server).
 * Especializa {@link LegacyRepositoryPort} para tipar a {@link Customer}.
 */
public interface CustomerLegacyRepositoryPort extends LegacyRepositoryPort<Customer> {
}

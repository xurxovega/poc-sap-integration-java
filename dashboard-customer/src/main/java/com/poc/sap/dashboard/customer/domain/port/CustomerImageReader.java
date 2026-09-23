package com.poc.sap.dashboard.customer.domain.port;

import com.poc.sap.dashboard.customer.domain.CustomerSnapshot;

import java.util.Optional;

/**
 * Puerto de lectura del snapshot actual del cliente (UI-001 H-2). La
 * implementacion vive en adapters/persistence/MongoCustomerImageReader
 * (driver nativo MongoSync). El dashboard NO comparte tipo de dominio con
 * customer (DashboardIsolationTest): traduce el documento Mongo al value
 * object local.
 */
public interface CustomerImageReader {

    Optional<CustomerSnapshot> findById(String entityId);
}

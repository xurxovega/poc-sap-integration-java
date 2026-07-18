package com.poc.sap.customer.domain.port;

import com.poc.sap.common.domain.port.HistoryIndexerPort;
import com.poc.sap.customer.domain.Customer;

/**
 * Puerto de indexacion del historico de Customer (Elasticsearch).
 */
public interface CustomerHistoryIndexerPort extends HistoryIndexerPort<Customer> {
}

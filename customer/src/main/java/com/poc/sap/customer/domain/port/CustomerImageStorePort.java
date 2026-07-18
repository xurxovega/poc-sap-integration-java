package com.poc.sap.customer.domain.port;

import com.poc.sap.common.domain.port.ImageStorePort;
import com.poc.sap.customer.domain.Customer;

/**
 * Puerto de almacenamiento de la imagen actual de Customer (MongoDB).
 */
public interface CustomerImageStorePort extends ImageStorePort<Customer> {
}

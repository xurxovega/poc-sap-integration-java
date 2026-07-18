package com.poc.sap.customer.domain.port;

import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.OperationType;
import com.poc.sap.customer.domain.Customer;

/**
 * Puerto de parseo del payload de ingestion a entidad Customer.
 * Especifico del dominio: cada dominio sabe como interpretar su payload.
 */
public interface CustomerPayloadParserPort {

    /**
     * Parsea el payload crudo del mensaje de ingestion a la entidad Customer.
     */
    Customer parse(IngestionMessage message);

    /**
     * Parsea un mensaje de delete (solo necesita entityId; payload puede ir vacio).
     */
    default IngestionMessage normalize(IngestionMessage message) {
        if (message.operation() == OperationType.DELETE) {
            return message;
        }
        return message;
    }
}

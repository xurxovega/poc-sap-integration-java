package com.poc.sap.customer.application;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.customer.domain.Customer;

/**
 * Una feature del cliente que se valida y envia a SAP sobre su propia linea de
 * estado ({@code customerId:FEATURE}). El orquestador solo conoce esta
 * abstraccion; en tests se sustituye por un fake, no por un mock de la clase
 * concreta (plan Fase 7, auditoria A20).
 */
@FunctionalInterface
public interface CustomerFeatureSync {

    SyncState execute(Customer customer, String payloadHash);
}

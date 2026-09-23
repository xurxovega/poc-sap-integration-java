package com.poc.sap.customer.application;

import com.poc.sap.common.domain.FeatureOutcome;
import com.poc.sap.customer.domain.Customer;

/**
 * Una feature del cliente que se valida y envia a SAP sobre su propia linea de
 * estado ({@code customerId:FEATURE}). El orquestador solo conoce esta
 * abstraccion; en tests se sustituye por un fake, no por un mock de la clase
 * concreta (plan Fase 7, auditoria A20).
 *
 * <p>Recibe el {@code cycleId} del ciclo abierto por el agregado —asi todas las
 * lineas del envio comparten identificador— y devuelve el resultado con su
 * motivo, no solo el estado: quien orquesta necesita saber POR QUE fallo una
 * parte para avisar (ADR-0010).
 */
@FunctionalInterface
public interface CustomerFeatureSync {

    FeatureOutcome execute(Customer customer, String cycleId, String payloadHash);
}

package com.poc.sap.common.domain.port;

import com.poc.sap.common.domain.FeatureOutcome;
import com.poc.sap.common.domain.SyncState;

import java.time.Instant;
import java.util.List;

/**
 * Aviso de que un ciclo termino con alguna parte de la entidad SIN llegar a SAP
 * (sdd/customer/sincronizacion-cliente.md R-9; ADR-0010, D-2). No se compensa:
 * se marca el ciclo como error, se deja escrito que parte fallo y POR QUE, y se
 * avisa para que alguien (o el siguiente evento) lo resuelva.
 */
public interface SyncNotificationPort {

    /** Se emite SIEMPRE que el ciclo no termina con todas las partes en SAP. */
    void partialFailure(SyncPartialFailure event);

    /**
     * La traza de pasos de un envio fallido.
     *
     * @param domain         dominio (customer, article)
     * @param entityId       entidad del agregado
     * @param cycleId        ciclo al que pertenecen todas las lineas del envio
     * @param payloadHash    evento que provoco el ciclo
     * @param aggregateState estado en el que queda el agregado (cero confianza:
     *                       SAP_ERROR salvo que ninguna parte llegara a SAP)
     * @param attempts       resultado de CADA parte intentada, con su motivo
     * @param at             instante del aviso
     */
    record SyncPartialFailure(String domain, String entityId, String cycleId, String payloadHash,
                              SyncState aggregateState, List<FeatureOutcome> attempts, Instant at) {}
}

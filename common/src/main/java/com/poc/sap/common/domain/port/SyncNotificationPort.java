package com.poc.sap.common.domain.port;

import com.poc.sap.common.domain.SyncState;

import java.util.Map;

/**
 * Aviso de que un ciclo termino con alguna parte de la entidad SIN llegar a SAP
 * (sdd/customer/sincronizacion-cliente.md R-9; ADR-0010, D-2). No se compensa:
 * se marca el ciclo como error, se deja escrito que parte fallo y se avisa para
 * que alguien (o el siguiente evento) lo resuelva.
 */
public interface SyncNotificationPort {

    /**
     * @param domain         dominio (customer, article)
     * @param entityId       entidad del agregado
     * @param payloadHash    evento que provoco el ciclo
     * @param featureResults estado final de cada parte enviada por separado
     *                       (p. ej. ADDRESS=SENT_SAP, BANKING=SAP_ERROR)
     */
    void partialFailure(String domain, String entityId, String payloadHash, Map<String, SyncState> featureResults);
}

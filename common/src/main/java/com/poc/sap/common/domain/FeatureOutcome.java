package com.poc.sap.common.domain;

import java.time.Instant;

/**
 * Resultado de una parte de un envio: su estado final, por que y cuando
 * (sdd/customer/sincronizacion-cliente.md R-9; ADR-0010). Es lo que el
 * orquestador agrega para decidir el estado del agregado y lo que viaja, paso a
 * paso, en el aviso de fallo parcial y en {@code GET /customers/{id}/state}.
 *
 * <p>Vive en {@code domain} y no en el pipeline que lo produce porque lo consumen
 * tanto {@code application} como el puerto de notificacion: el dominio no puede
 * depender de la capa de aplicacion (AGENTS.md §2.2).
 *
 * @param feature nombre de la parte (ADDRESS, FISCAL, CONTACT, BANKING)
 * @param state   estado final de su linea
 * @param detail  motivo, si fallo; {@code null} si fue bien
 * @param at      instante en que se cerro la parte
 */
public record FeatureOutcome(String feature, SyncState state, String detail, Instant at) {

    /** La parte esta en SAP. */
    public boolean ok() {
        return state == SyncState.SENT_SAP;
    }

    /**
     * SAP pudo quedarse con algo de esta parte: o respondio con exito, o respondio
     * un error tras haberla recibido. {@code COMMUNICATION_ERROR} (circuito abierto
     * o transporte agotado) e {@code INVALID} significan que no llego a SAP.
     */
    public boolean touchedSap() {
        return state == SyncState.SAP_ERROR || ok();
    }
}

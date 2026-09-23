package com.poc.sap.customer.domain.port;

import com.poc.sap.common.domain.port.SapOutboundPort;
import com.poc.sap.customer.domain.Mandate;

/**
 * Puerto de envio a SAP del mandato SEPA (sdd/customer/sincronizacion-datos-bancarios.md
 * y sdd/customer/baja-mandato-sepa.md).
 */
public interface MandateSapOutboundPort extends SapOutboundPort<Mandate> {

    /**
     * Revoca (cancela) un mandato en SAP. Un mandato SEPA no se borra: cambia de
     * estado, porque el histórico de cobros lo referencia.
     *
     * @param mandateId   referencia unica del mandato
     * @param payloadHash hash de idempotencia
     */
    SapResponse revoke(String mandateId, String payloadHash);
}

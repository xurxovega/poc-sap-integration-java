package com.poc.sap.common.sap;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;

/**
 * Cliente HTTP SAP de bajo nivel (SPEC.md §5, TECH.md §8).
 * Abstrae transporte, autenticacion, reintentos y circuit breaker.
 * Cada adaptador {@code SapOutboundPort} de dominio delega aqui.
 *
 * <p>Centralizado en {@code common/sap} para reutilizar auth BTP (xsuaa),
 * auth S/4 nativa, Resilience4j (retry + circuit breaker) y observabilidad.
 */
public interface SapClient {

    /**
     * POST/PUT a SAP.
     *
     * @param destination   destino BTP o S/4 nativo
     * @param path          ruta relativa del recurso SAP
     * @param entityId      identificador de la entidad (para idempotencia/cabeceras)
     * @param payloadHash   hash de idempotencia (cabecera Idempotency-Key)
     * @param body          cuerpo JSON ya mapeado al contrato SAP
     * @return respuesta normalizada
     */
    SapResponse send(SapDestination destination,
                     String path,
                     String entityId,
                     String payloadHash,
                     String body);
}

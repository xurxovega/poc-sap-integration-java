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
     * @param path          ruta relativa del recurso SAP (query params incluidos en el path)
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

    /**
     * GET a SAP (lectura de entidades maestras OData/REST).
     *
     * @param destination destino BTP o S/4 nativo
     * @param path        ruta relativa + query params OData ({@code ?$top=10&$filter=...})
     * @return respuesta normalizada
     */
    SapResponse get(SapDestination destination, String path);

    /**
     * PATCH a SAP (actualizacion parcial OData).
     *
     * @param destination destino BTP o S/4 nativo
     * @param path        ruta relativa del recurso SAP
     * @param entityId    identificador de la entidad
     * @param payloadHash hash de idempotencia
     * @param body        cuerpo JSON con los campos a actualizar
     * @return respuesta normalizada
     */
    SapResponse patch(SapDestination destination,
                      String path,
                      String entityId,
                      String payloadHash,
                      String body);

    /**
     * DELETE a SAP (borrado logico/fisico OData).
     *
     * @param destination destino BTP o S/4 nativo
     * @param path        ruta relativa del recurso SAP ({@code /A_BusinessPartner('123')})
     * @return respuesta normalizada
     */
    SapResponse delete(SapDestination destination, String path);
}

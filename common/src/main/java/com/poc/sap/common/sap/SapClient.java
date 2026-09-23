package com.poc.sap.common.sap;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;

/**
 * Cliente HTTP SAP de bajo nivel (TECH.md §8).
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
     * @param ifMatch     ETag de la precondicion ({@code If-Match}), o {@code null}
     * @return respuesta normalizada
     */
    SapResponse patch(SapDestination destination,
                      String path,
                      String entityId,
                      String payloadHash,
                      String body,
                      String ifMatch);

    /**
     * DELETE a SAP (borrado logico/fisico OData).
     *
     * @param destination destino BTP o S/4 nativo
     * @param path        ruta relativa del recurso SAP ({@code /A_BusinessPartner('123')})
     * @param ifMatch     ETag de la precondicion, o {@code null} para no enviarla
     * @return respuesta normalizada
     */
    SapResponse delete(SapDestination destination, String path, String ifMatch);

    /**
     * PATCH sin precondicion. Se conserva porque hay decenas de llamadas con esta
     * firma, pero <b>sin {@code If-Match} el PATCH no es idempotente</b> y solo se
     * reintenta ante un fallo anterior al envio (spec resiliencia-cliente-sap R-1).
     */
    default SapResponse patch(SapDestination destination, String path, String entityId,
                              String payloadHash, String body) {
        return patch(destination, path, entityId, payloadHash, body, null);
    }

    /** DELETE sin precondicion. */
    default SapResponse delete(SapDestination destination, String path) {
        return delete(destination, path, null);
    }
}

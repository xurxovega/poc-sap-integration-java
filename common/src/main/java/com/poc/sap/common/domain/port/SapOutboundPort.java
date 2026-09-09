package com.poc.sap.common.domain.port;

/**
 * Puerto de envio a SAP (TECH.md §8).
 * Dos familias de adaptadores: APIs BTP (xsuaa + Destination Service) y
 * APIs nativas S/4 Public Cloud (OData/REST propio).
 *
 * <p>Cada dominio define su propio payload tipado y mapeo a SAP; este puerto
 * abstrae el transporte y la autenticacion, centralizados en {@code common/sap}.
 *
 * @param <P> tipo del payload ya mapeado al contrato SAP
 */
public interface SapOutboundPort<P> {

    /**
     * Envia un payload a SAP. Idempotente: mismo id + hash no duplica envios.
     *
     * @param entityId    identificador de la entidad del dominio
     * @param payloadHash hash de idempotencia
     * @param payload     payload mapeado al contrato SAP
     * @return respuesta SAP ya decodificada
     */
    SapResponse send(String entityId, String payloadHash, P payload);

    /**
     * Respuesta normalizada de una llamada SAP.
     *
     * @param httpStatus codigo HTTP
     * @param body       cuerpo de respuesta (puede ser vacio)
     * @param location   header Location si la respuesta es 201/202
     */
    record SapResponse(int httpStatus, String body, String location) {
        public boolean isSuccess() {
            return httpStatus >= 200 && httpStatus < 300;
        }
    }
}

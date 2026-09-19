package com.poc.sap.common.domain.port;

/**
 * Puerto de envio a SAP (TECH.md §8).
 * Dos familias de adaptadores: APIs BTP (xsuaa + Destination Service) y
 * APIs nativas S/4 Public Cloud (OData/REST propio).
 *
 * <p>Cada dominio define su propio payload tipado y mapeo a SAP; este puerto
 * abstrae el transporte y la autenticacion, centralizados en {@code common/sap}.
 *
 * <p><b>Upsert</b> (spec {@code docs/sdd/common/upsert-idempotente-sap.md}):
 * antes de escribir se pregunta a SAP que tiene ({@link #lookup}). Si lo tiene,
 * la operacion es {@link #update} ({@code PATCH} con {@code If-Match}); si no,
 * {@link #send} ({@code POST}); y si el lookup no responde, no se escribe nada.
 * Los dos metodos son {@code default} a proposito: un adaptador que no sepa
 * verificar (las familias {@code Btp*}) se comporta como hasta ahora sin tocarlo.
 *
 * @param <P> tipo del payload ya mapeado al contrato SAP
 */
public interface SapOutboundPort<P> {

    /**
     * ALTA en SAP ({@code POST}). Idempotente: mismo id + hash no duplica envios.
     *
     * @param entityId    identificador de la entidad del dominio
     * @param payloadHash hash de idempotencia
     * @param payload     payload mapeado al contrato SAP
     * @return respuesta SAP ya decodificada
     */
    SapResponse send(String entityId, String payloadHash, P payload);

    /**
     * Verificacion previa: que tiene SAP ahora mismo de la subentidad que este
     * puerto escribe. Es una lectura ({@code GET}) y nunca modifica nada.
     *
     * <p>Por defecto {@link SapLookup.Outcome#NOT_SUPPORTED}, que deja el
     * comportamiento anterior: alta directa.
     *
     * @param entityId identificador de la entidad del dominio
     * @param payload  payload, por si la clave de busqueda depende de sus datos
     * @return lo que sabemos de SAP, nunca {@code null}
     */
    default SapLookup lookup(String entityId, P payload) {
        return SapLookup.notSupported();
    }

    /**
     * ACTUALIZACION de lo que SAP ya tiene ({@code PATCH} con {@code If-Match}).
     *
     * @param entityId    identificador de la entidad del dominio
     * @param payloadHash hash de idempotencia
     * @param payload     payload mapeado al contrato SAP
     * @param found       resultado del {@link #lookup} inmediatamente anterior, con
     *                    la clave de la subentidad y el ETag de la precondicion
     * @return respuesta SAP ya decodificada
     */
    default SapResponse update(String entityId, String payloadHash, P payload, SapLookup found) {
        throw new UnsupportedOperationException(
                "update no implementado para " + getClass().getSimpleName());
    }

    /**
     * Resultado de la verificacion previa.
     *
     * @param outcome que sabemos
     * @param key     clave de la subentidad en SAP (AddressID, RelationshipNumber,
     *                BankIdentification...); {@code null} si no aplica
     * @param etag    ETag devuelto por el GET, para el {@code If-Match} del PATCH.
     *                <b>No se persiste</b>: envejece y produce 412
     * @param detail  motivo legible cuando no se pudo concluir
     */
    record SapLookup(Outcome outcome, String key, String etag, String detail) {

        /** Que sabemos de SAP tras preguntar. */
        public enum Outcome {
            /** SAP ya la tiene: toca actualizar. */
            FOUND,
            /** SAP no la tiene: toca dar de alta. */
            NOT_FOUND,
            /** No se pudo saber (transporte, 5xx, circuito abierto, respuesta ambigua): NO se escribe. */
            UNAVAILABLE,
            /** El adaptador no sabe verificar: comportamiento anterior, alta directa. */
            NOT_SUPPORTED
        }

        public static SapLookup notFound() {
            return new SapLookup(Outcome.NOT_FOUND, null, null, null);
        }

        public static SapLookup notSupported() {
            return new SapLookup(Outcome.NOT_SUPPORTED, null, null, null);
        }

        public static SapLookup unavailable(String detail) {
            return new SapLookup(Outcome.UNAVAILABLE, null, null, detail);
        }

        public static SapLookup found(String key, String etag) {
            return new SapLookup(Outcome.FOUND, key, etag, null);
        }

        public boolean isFound() {
            return outcome == Outcome.FOUND;
        }

        public boolean isUnavailable() {
            return outcome == Outcome.UNAVAILABLE;
        }
    }

    /**
     * Respuesta normalizada de una llamada SAP.
     *
     * @param httpStatus codigo HTTP (0 = fallo de transporte)
     * @param body       cuerpo de respuesta (puede ser vacio)
     * @param location   header Location si la respuesta es 201/202
     * @param etag       header ETag, si SAP lo devuelve
     */
    record SapResponse(int httpStatus, String body, String location, String etag) {

        /** Constructor historico de 3 argumentos: se conserva para no tocar ~30 llamadas. */
        public SapResponse(int httpStatus, String body, String location) {
            this(httpStatus, body, location, null);
        }

        public boolean isSuccess() {
            return httpStatus >= 200 && httpStatus < 300;
        }

        /** 412: el recurso cambio desde nuestra lectura; hay que releer antes de reintentar. */
        public boolean isPreconditionFailed() {
            return httpStatus == 412;
        }
    }
}

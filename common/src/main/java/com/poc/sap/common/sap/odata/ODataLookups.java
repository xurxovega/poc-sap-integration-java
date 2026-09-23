package com.poc.sap.common.sap.odata;

import com.fasterxml.jackson.databind.JsonNode;
import com.poc.sap.common.domain.port.SapOutboundPort.SapLookup;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.json.SapJsonMapper;

/**
 * Traduccion de una respuesta OData V2 a {@link SapLookup} (spec
 * {@code docs/sdd/common/upsert-idempotente-sap.md} R-1 a R-3).
 *
 * <p>La regla que centraliza: <b>solo un 404 significa «SAP no lo tiene»</b>.
 * Un 5xx agotado, un fallo de transporte ({@code status 0}) o un resultado
 * ambiguo son «no lo se», y con «no lo se» no se escribe.
 */
public final class ODataLookups {

    private ODataLookups() {
    }

    /** Escapa la comilla simple de un literal OData: {@code O'Neill} -> {@code O''Neill}. */
    public static String esc(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    /**
     * Respuesta de un {@code GET} por clave.
     *
     * @param r   respuesta del cliente SAP
     * @param key clave de la subentidad, que se devuelve si existe
     */
    public static SapLookup fromSingle(SapResponse r, String key) {
        if (r.isSuccess()) {
            return SapLookup.found(key, etagOf(r));
        }
        if (r.httpStatus() == 404) {
            return SapLookup.notFound();
        }
        return SapLookup.unavailable(describe(r));
    }

    /**
     * Respuesta de una navegacion o un {@code $filter} que devuelve una coleccion
     * ({@code {"d":{"results":[...]}}}).
     *
     * @param keyField      campo del que se toma la clave ({@code AddressID}...)
     * @param ambiguousFails con {@code true}, mas de un resultado es no concluyente
     */
    public static SapLookup fromCollection(SapResponse r, String keyField, boolean ambiguousFails) {
        if (!r.isSuccess()) {
            return r.httpStatus() == 404 ? SapLookup.notFound() : SapLookup.unavailable(describe(r));
        }
        JsonNode results;
        try {
            JsonNode root = SapJsonMapper.mapper().readTree(r.body() == null ? "{}" : r.body());
            results = (root.has("d") ? root.get("d") : root).path("results");
        } catch (Exception e) {
            return SapLookup.unavailable("respuesta OData ilegible: " + e.getMessage());
        }
        if (!results.isArray() || results.isEmpty()) {
            return SapLookup.notFound();
        }
        if (results.size() > 1 && ambiguousFails) {
            return SapLookup.unavailable("lookup ambiguo: " + results.size() + " resultados");
        }
        JsonNode first = results.get(0);
        String key = first.path(keyField).asText(null);
        if (key == null || key.isBlank()) {
            return SapLookup.unavailable("la respuesta no trae " + keyField);
        }
        return SapLookup.found(key, first.path("__metadata").path("etag").asText(null));
    }

    /**
     * ETag de la cabecera; si SAP no la envia, el {@code __metadata.etag} del
     * cuerpo, que es donde OData V2 lo pone.
     */
    public static String etagOf(SapResponse r) {
        if (r.etag() != null && !r.etag().isBlank()) {
            return r.etag();
        }
        try {
            JsonNode root = SapJsonMapper.mapper().readTree(r.body() == null ? "{}" : r.body());
            JsonNode d = root.has("d") ? root.get("d") : root;
            String etag = d.path("__metadata").path("etag").asText(null);
            return etag == null || etag.isBlank() ? null : etag;
        } catch (Exception e) {
            return null;
        }
    }

    private static String describe(SapResponse r) {
        return r.httpStatus() == 0
                ? "fallo de transporte: " + r.body()
                : "SAP respondio " + r.httpStatus();
    }
}

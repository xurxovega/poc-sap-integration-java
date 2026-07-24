package com.poc.sap.common.sap.odata;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wrapper OData v2 para payloads POST/PATCH en SAP S/4HANA.
 * SAP espera el cuerpo JSON envuelto en {@code {"d": {...}}}.
 *
 * <p>Uso:
 * <pre>{@code
 * String json = SapJsonMapper.write(ODataPayload.wrap(addressDto));
 * }</pre>
 */
public record ODataPayload(
        @JsonProperty("d") Object d
) {
    public static ODataPayload wrap(Object payload) {
        return new ODataPayload(payload);
    }
}

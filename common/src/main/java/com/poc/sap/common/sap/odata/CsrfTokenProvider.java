package com.poc.sap.common.sap.odata;

/**
 * Proveedor de tokens CSRF para SAP S/4HANA OData.
 *
 * <p>SAP NetWeaver requiere un token CSRF valido en todas las operaciones
 * de escritura (POST/PATCH/PUT/DELETE). El token se obtiene con un GET
 * previo que incluye el header {@code x-csrf-token: Fetch}.
 *
 * <p>Implementaciones:
 * <ul>
 *   <li>{@link S4CsrfTokenProvider} — on-premise / private cloud</li>
 *   <li>No-op (sin CSRF) — BTP / public cloud con OAuth2</li>
 * </ul>
 */
public interface CsrfTokenProvider {

    /**
     * Obtiene un token CSRF valido. Puede devolver uno cacheado si no ha expirado.
     */
    String fetchToken(String baseUrl, String username, String password);

    /**
     * Invalida el token cacheado. Se llama cuando SAP responde 403 con
     * {@code x-csrf-token: Required}.
     */
    void invalidate();

    /**
     * Indica si este destino requiere gestion de CSRF.
     */
    boolean requiresCsrf();
}

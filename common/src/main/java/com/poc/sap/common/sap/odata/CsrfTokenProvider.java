package com.poc.sap.common.sap.odata;

/**
 * Proveedor de tokens CSRF para SAP S/4HANA OData V2.
 *
 * <p>SAP NetWeaver exige un token CSRF en toda escritura (POST/PATCH/PUT/DELETE).
 * Se obtiene con un GET previo con la cabecera {@code x-csrf-token: Fetch} y
 * viaja junto a las cookies de sesion que devolvio ese GET.
 *
 * <p>Spec: {@code docs/sdd/common/resiliencia-cliente-sap.md} (R-4, AC-5..AC-7).
 *
 * <p>Implementaciones:
 * <ul>
 *   <li>{@link S4CsrfTokenProvider}: on-premise / private cloud / public cloud con CSRF</li>
 *   <li>Ninguna (bean ausente): BTP o destinos que no lo exigen</li>
 * </ul>
 */
public interface CsrfTokenProvider {

    /** Token y cookies de sesion que deben viajar juntos en cada escritura. */
    record CsrfToken(String token, String cookies) {}

    /**
     * Obtiene un token CSRF valido, cacheado hasta que se invalide.
     *
     * @param fetchUrl            URL completa del GET de fetch
     * @param authorizationHeader la MISMA cabecera {@code Authorization} que usara la
     *                            escritura (OAuth2 o basic); el fetch no decide la auth
     * @return token + cookies, o {@code null} si SAP no devolvio token
     */
    CsrfToken fetchToken(String fetchUrl, String authorizationHeader);

    /**
     * Invalida el token cacheado. Se llama cuando SAP responde 403 con
     * {@code x-csrf-token: Required}.
     */
    void invalidate();

    /** Indica si este destino requiere gestion de CSRF. */
    boolean requiresCsrf();
}

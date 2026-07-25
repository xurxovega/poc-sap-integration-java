package com.poc.sap.common.sap.auth;

import com.poc.sap.common.sap.SapDestination;

/**
 * Proveedor de tokens de acceso SAP (SPEC.md §5, TECH.md §8).
 * Una implementacion para BTP (xsuaa OAuth2 cliente) y otra para S/4 nativo
 * (basic u OAuth2 propio).
 */
public interface SapAuthProvider {

    /**
     * Destino para el que este provider emite tokens.
     */
    SapDestination supports();

    /**
     * Obtiene un token de acceso valido (con cacheo interno).
     */
    String accessToken();

    /**
     * Valor completo de la cabecera {@code Authorization}. Por defecto
     * Bearer; las implementaciones basic-auth lo sobreescriben.
     */
    default String authorizationHeader() {
        return "Bearer " + accessToken();
    }
}

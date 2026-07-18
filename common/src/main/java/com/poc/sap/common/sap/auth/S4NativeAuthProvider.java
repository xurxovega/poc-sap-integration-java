package com.poc.sap.common.sap.auth;

import com.poc.sap.common.sap.SapDestination;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Provider S/4 nativo (TECH.md §8). Stub: en produccion OAuth2 password/client
 * u basic segun configuracion.
 */
@Component
public class S4NativeAuthProvider implements SapAuthProvider {

    @Value("${sap.s4.auth.type:oauth2}")
    private String authType;
    @Value("${sap.s4.auth.username:}")
    private String username;
    @Value("${sap.s4.auth.password:}")
    private String password;

    @Override
    public SapDestination supports() {
        return SapDestination.S4_NATIVE;
    }

    @Override
    public String accessToken() {
        if (username.isBlank()) {
            return "stub-s4-token";
        }
        return "cached-s4-token";
    }
}
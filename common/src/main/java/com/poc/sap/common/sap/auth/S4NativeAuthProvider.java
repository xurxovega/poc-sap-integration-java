package com.poc.sap.common.sap.auth;

import com.poc.sap.common.sap.SapDestination;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * Provider S/4 nativo (TECH.md §8). Segun {@code sap.s4.auth.type}:
 * <ul>
 *   <li>{@code oauth2}: client-credentials contra el token endpoint del
 *       communication arrangement, con cacheo por expiracion.</li>
 *   <li>{@code basic}: usuario/password del communication user
 *       (cabecera Authorization Basic).</li>
 * </ul>
 * Sin configuracion devuelve un token stub para desarrollo local.
 */
@Component
public class S4NativeAuthProvider implements SapAuthProvider {

    private final OAuth2TokenClient tokenClient = new OAuth2TokenClient();

    @Value("${sap.s4.auth.type:oauth2}")
    private String authType;
    @Value("${sap.s4.auth.token-url:}")
    private String tokenUrl;
    @Value("${sap.s4.auth.client-id:}")
    private String clientId;
    @Value("${sap.s4.auth.client-secret:}")
    private String clientSecret;
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
        if ("basic".equalsIgnoreCase(authType)) {
            return "";
        }
        if (clientId.isBlank() || clientSecret.isBlank() || tokenUrl.isBlank()) {
            return "stub-s4-token";
        }
        return tokenClient.accessToken(tokenUrl, clientId, clientSecret);
    }

    @Override
    public String authorizationHeader() {
        if ("basic".equalsIgnoreCase(authType)) {
            if (username.isBlank()) {
                return "Bearer stub-s4-token";
            }
            String basic = Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes());
            return "Basic " + basic;
        }
        return "Bearer " + accessToken();
    }
}

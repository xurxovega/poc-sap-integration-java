package com.poc.sap.common.sap.auth;

import com.poc.sap.common.sap.SapDestination;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Provider BTP xsuaa (TECH.md §8): OAuth2 client-credentials contra el token
 * endpoint de xsuaa, con cacheo por expiracion.
 *
 * <p>Sin configuracion completa (client-id/secret/token-url) devuelve un token
 * stub para desarrollo local contra mocks.
 */
@Component
public class BtpAuthProvider implements SapAuthProvider {

    private final OAuth2TokenClient tokenClient = new OAuth2TokenClient();

    @Value("${sap.btp.xsuaa.client-id:}")
    private String clientId;
    @Value("${sap.btp.xsuaa.client-secret:}")
    private String clientSecret;
    @Value("${sap.btp.xsuaa.token-url:}")
    private String tokenUrl;

    @Override
    public SapDestination supports() {
        return SapDestination.BTP;
    }

    @Override
    public String accessToken() {
        if (clientId.isBlank() || clientSecret.isBlank() || tokenUrl.isBlank()) {
            return "stub-btp-token";
        }
        return tokenClient.accessToken(tokenUrl, clientId, clientSecret);
    }
}

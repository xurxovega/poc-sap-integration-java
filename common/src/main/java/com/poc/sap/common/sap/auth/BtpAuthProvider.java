package com.poc.sap.common.sap.auth;

import com.poc.sap.common.sap.SapDestination;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Provider BTP xsuaa (TECH.md §8). Stub: en produccion usa OAuth2 client credentials
 * contra xsuaa con cacheo y refresh. Aqui skeleton.
 */
@Component
public class BtpAuthProvider implements SapAuthProvider {

    @Value("${sap.btp.xsuaa.client-id:}")
    private String clientId;
    @Value("${sap.btp.xsuaa.client-secret:}")
    private String clientSecret;

    @Override
    public SapDestination supports() {
        return SapDestination.BTP;
    }

    @Override
    public String accessToken() {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            return "stub-btp-token";
        }
        return "cached-btp-token";
    }
}
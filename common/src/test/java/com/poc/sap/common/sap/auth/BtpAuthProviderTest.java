package com.poc.sap.common.sap.auth;

import com.poc.sap.common.sap.SapDestination;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test unit del {@link BtpAuthProvider} (TECH.md §8). Como los credenciales
 * solo se inyectan via {@code @Value}, se escriben con reflection sin levantar
 * contexto Spring. El flujo OAuth2 real (token endpoint) se ejercita en
 * integracion; aqui se valida el contrato de fallback stub.
 */
class BtpAuthProviderTest {

    private BtpAuthProvider provider;

    @BeforeEach
    void setUp() {
        provider = new BtpAuthProvider();
        // los campos @Value llegan como "" en produccion; con new() son null.
        // Seteamos defaults para reproducir el contrato sin Spring.
        ReflectionTestUtils.setField(provider, "clientId", "");
        ReflectionTestUtils.setField(provider, "clientSecret", "");
        ReflectionTestUtils.setField(provider, "tokenUrl", "");
    }

    @Test
    void supportsBtpDestination() {
        assertThat(provider.supports()).isEqualTo(SapDestination.BTP);
    }

    @Test
    void blankCredentialsReturnStubToken() {
        assertThat(provider.accessToken()).isEqualTo("stub-btp-token");
    }

    @Test
    void missingTokenUrlStillReturnsStubToken() {
        ReflectionTestUtils.setField(provider, "clientId", "client-123");
        ReflectionTestUtils.setField(provider, "clientSecret", "secret-456");
        // sin token-url no hay endpoint contra el que autenticar → stub

        assertThat(provider.accessToken()).isEqualTo("stub-btp-token");
    }

    @Test
    void authorizationHeaderIsBearer() {
        assertThat(provider.authorizationHeader()).isEqualTo("Bearer stub-btp-token");
    }
}

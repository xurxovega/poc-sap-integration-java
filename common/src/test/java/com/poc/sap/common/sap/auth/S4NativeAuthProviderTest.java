package com.poc.sap.common.sap.auth;

import com.poc.sap.common.sap.SapDestination;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test unit del {@link S4NativeAuthProvider} (TECH.md §8).
 * El flujo OAuth2 real se ejercita en integracion; aqui el contrato
 * stub/basic sin Spring.
 */
class S4NativeAuthProviderTest {

    private S4NativeAuthProvider provider;

    @BeforeEach
    void setUp() {
        provider = new S4NativeAuthProvider();
        // los campos @Value llegan como "" en produccion; con new() son null.
        ReflectionTestUtils.setField(provider, "authType", "oauth2");
        ReflectionTestUtils.setField(provider, "tokenUrl", "");
        ReflectionTestUtils.setField(provider, "clientId", "");
        ReflectionTestUtils.setField(provider, "clientSecret", "");
        ReflectionTestUtils.setField(provider, "username", "");
        ReflectionTestUtils.setField(provider, "password", "");
    }

    @Test
    void supportsS4NativeDestination() {
        assertThat(provider.supports()).isEqualTo(SapDestination.S4_NATIVE);
    }

    @Test
    void oauth2WithoutConfigReturnsStubToken() {
        assertThat(provider.accessToken()).isEqualTo("stub-s4-token");
        assertThat(provider.authorizationHeader()).isEqualTo("Bearer stub-s4-token");
    }

    @Test
    void basicAuthBuildsBasicHeader() {
        ReflectionTestUtils.setField(provider, "authType", "basic");
        ReflectionTestUtils.setField(provider, "username", "COMM_USER");
        ReflectionTestUtils.setField(provider, "password", "secret");

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("COMM_USER:secret".getBytes());
        assertThat(provider.authorizationHeader()).isEqualTo(expected);
    }

    @Test
    void basicAuthWithoutUserFallsBackToStub() {
        ReflectionTestUtils.setField(provider, "authType", "basic");

        assertThat(provider.authorizationHeader()).isEqualTo("Bearer stub-s4-token");
    }
}

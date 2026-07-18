package com.poc.sap.common.sap.auth;

import com.poc.sap.common.sap.SapDestination;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test unit del {@link S4NativeAuthProvider} (TECH.md §8).
 */
class S4NativeAuthProviderTest {

    private S4NativeAuthProvider provider;

    @BeforeEach
    void setUp() {
        provider = new S4NativeAuthProvider();
        // los campos @Value llegan como "" en produccion; con new() son null.
        ReflectionTestUtils.setField(provider, "authType", "oauth2");
        ReflectionTestUtils.setField(provider, "username", "");
        ReflectionTestUtils.setField(provider, "password", "");
    }

    @Test
    void supportsS4NativeDestination() {
        assertThat(provider.supports()).isEqualTo(SapDestination.S4_NATIVE);
    }

    @Test
    void blankUsernameReturnStubToken() {
        assertThat(provider.accessToken()).isEqualTo("stub-s4-token");
    }

    @Test
    void suppliedUsernameReturnCachedToken() {
        ReflectionTestUtils.setField(provider, "username", "admin");

        assertThat(provider.accessToken()).isEqualTo("cached-s4-token");
    }
}
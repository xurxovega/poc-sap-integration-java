package com.poc.sap.common.sap.auth;

import com.poc.sap.common.sap.SapDestination;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec sdd/common/autenticacion-sap.md (S/4 nativo). El flujo OAuth2 real se
 * ejercita en integracion; aqui el contrato de arranque, basic y stub.
 */
class S4NativeAuthProviderTest {

    private static S4NativeAuthProvider oauth2(String url, String id, String secret, boolean allowStub) {
        return new S4NativeAuthProvider("oauth2", url, id, secret, "", "", allowStub);
    }

    private static S4NativeAuthProvider basic(String user, String pass, boolean allowStub) {
        return new S4NativeAuthProvider("basic", "", "", "", user, pass, allowStub);
    }

    @Test
    void supportsS4NativeDestination() {
        assertThat(oauth2("", "", "", true).supports()).isEqualTo(SapDestination.S4_NATIVE);
    }

    /** AC-1: oauth2 sin credenciales y sin allow-stub no arranca. */
    @Test
    void oauth2WithoutConfigAndWithoutAllowStubFailsAtStartup() {
        S4NativeAuthProvider p = oauth2("", "", "", false);

        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sap.s4.auth.type=oauth2")
                .hasMessageContaining("SAP_AUTH_ALLOW_STUB=true");
        assertThatThrownBy(p::authorizationHeader).isInstanceOf(IllegalStateException.class);
    }

    /** AC-2: oauth2 sin credenciales con allow-stub devuelve el stub. */
    @Test
    void oauth2WithoutConfigReturnsStubTokenWhenAllowed() {
        S4NativeAuthProvider p = oauth2("", "", "", true);

        assertThatCode(p::validate).doesNotThrowAnyException();
        assertThat(p.accessToken()).isEqualTo("stub-s4-token");
        assertThat(p.authorizationHeader()).isEqualTo("Bearer stub-s4-token");
    }

    /** AC-4: basic construye la cabecera con el communication user. */
    @Test
    void basicAuthBuildsBasicHeader() {
        S4NativeAuthProvider p = basic("COMM_USER", "secret", false);

        String expected = "Basic " + Base64.getEncoder().encodeToString("COMM_USER:secret".getBytes());
        assertThatCode(p::validate).doesNotThrowAnyException();
        assertThat(p.authorizationHeader()).isEqualTo(expected);
    }

    /** AC-1: basic sin usuario es configuracion incompleta: no arranca sin allow-stub. */
    @Test
    void basicAuthWithoutUserFailsAtStartupUnlessStubAllowed() {
        assertThatThrownBy(basic("", "", false)::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sap.s4.auth.type=basic");
        assertThat(basic("", "", true).authorizationHeader()).isEqualTo("Bearer stub-s4-token");
    }

    /** AC-3: con credenciales completas el arranque no depende de allow-stub. */
    @Test
    void completeOauth2CredentialsPassStartupWithoutStub() {
        S4NativeAuthProvider p = oauth2("https://s4/oauth/token", "id", "secret", false);

        assertThat(p.configured()).isTrue();
        assertThatCode(p::validate).doesNotThrowAnyException();
    }
}

package com.poc.sap.common.sap.auth;

import com.poc.sap.common.sap.SapDestination;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec sdd/common/autenticacion-sap.md (BTP). El flujo OAuth2 real (token
 * endpoint) se ejercita en integracion; aqui el contrato de arranque y stub.
 */
class BtpAuthProviderTest {

    private static BtpAuthProvider provider(String id, String secret, String url, boolean allowStub) {
        return new BtpAuthProvider(id, secret, url, allowStub);
    }

    @Test
    void supportsBtpDestination() {
        assertThat(provider("", "", "", true).supports()).isEqualTo(SapDestination.BTP);
    }

    /** AC-1: sin credenciales y sin permiso explicito de stub, la app no arranca. */
    @Test
    void missingCredentialsWithoutAllowStubFailAtStartup() {
        BtpAuthProvider p = provider("", "", "", false);

        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sap.btp.xsuaa.client-id")
                .hasMessageContaining("SAP_AUTH_ALLOW_STUB=true");
        assertThatThrownBy(p::accessToken).isInstanceOf(IllegalStateException.class);
    }

    /** AC-2: con allow-stub el arranque pasa y el token es el stub (solo para mocks). */
    @Test
    void allowStubReturnsStubTokenAndPassesStartup() {
        BtpAuthProvider p = provider("", "", "", true);

        assertThatCode(p::validate).doesNotThrowAnyException();
        assertThat(p.accessToken()).isEqualTo("stub-btp-token");
        assertThat(p.authorizationHeader()).isEqualTo("Bearer stub-btp-token");
    }

    /** AC-1: la configuracion es incompleta si falta cualquiera de las tres propiedades. */
    @Test
    void missingTokenUrlCountsAsUnconfigured() {
        BtpAuthProvider p = provider("client-123", "secret-456", "", false);

        assertThat(p.configured()).isFalse();
        assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class);
    }

    /** AC-3: con credenciales completas el arranque no depende de allow-stub. */
    @Test
    void completeCredentialsPassStartupWithoutStub() {
        BtpAuthProvider p = provider("client-123", "secret-456", "https://xsuaa/oauth/token", false);

        assertThat(p.configured()).isTrue();
        assertThatCode(p::validate).doesNotThrowAnyException();
    }
}

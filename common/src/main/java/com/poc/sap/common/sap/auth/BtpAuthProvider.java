package com.poc.sap.common.sap.auth;

import com.poc.sap.common.sap.SapDestination;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Provider BTP xsuaa: OAuth2 client-credentials contra el token endpoint de
 * xsuaa, con cacheo por expiracion (sdd/common/autenticacion-sap.md).
 *
 * <p>Sin configuracion completa (client-id/secret/token-url) la app <b>no
 * arranca</b>, salvo que {@code sap.auth.allow-stub=true} declare de forma
 * explicita que se trabaja contra un mock: entonces devuelve un token stub y lo
 * avisa en el log. Hasta la Fase 4 del plan el fallback era silencioso y en
 * produccion se traducia en un 401 en la primera llamada (auditoria A8).
 */
@Component
public class BtpAuthProvider implements SapAuthProvider {

    private static final Logger log = LoggerFactory.getLogger(BtpAuthProvider.class);
    static final String STUB_TOKEN = "stub-btp-token";

    private final OAuth2TokenClient tokenClient = new OAuth2TokenClient();
    private final String clientId;
    private final String clientSecret;
    private final String tokenUrl;
    private final boolean allowStub;

    public BtpAuthProvider(@Value("${sap.btp.xsuaa.client-id:}") String clientId,
                           @Value("${sap.btp.xsuaa.client-secret:}") String clientSecret,
                           @Value("${sap.btp.xsuaa.token-url:}") String tokenUrl,
                           @Value("${sap.auth.allow-stub:false}") boolean allowStub) {
        this.clientId = n(clientId);
        this.clientSecret = n(clientSecret);
        this.tokenUrl = n(tokenUrl);
        this.allowStub = allowStub;
    }

    @PostConstruct
    void validate() {
        if (!configured()) {
            if (!allowStub) {
                throw new IllegalStateException("Credenciales BTP incompletas (sap.btp.xsuaa.client-id, "
                        + "client-secret, token-url) y sap.auth.allow-stub=false: la app no arranca sin "
                        + "autenticacion real. Contra el mock local exporta SAP_AUTH_ALLOW_STUB=true");
            }
            log.warn("BTP sin credenciales OAuth2: se usara un token STUB (sap.auth.allow-stub=true). "
                    + "Solo valido contra un SAP simulado");
        }
    }

    @Override
    public SapDestination supports() {
        return SapDestination.BTP;
    }

    @Override
    public String accessToken() {
        if (!configured()) {
            if (!allowStub) {
                throw new IllegalStateException("BTP sin credenciales y sin sap.auth.allow-stub");
            }
            return STUB_TOKEN;
        }
        return tokenClient.accessToken(tokenUrl, clientId, clientSecret);
    }

    boolean configured() {
        return !clientId.isBlank() && !clientSecret.isBlank() && !tokenUrl.isBlank();
    }

    private static String n(String s) { return s == null ? "" : s; }
}

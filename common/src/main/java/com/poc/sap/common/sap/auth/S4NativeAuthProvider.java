package com.poc.sap.common.sap.auth;

import com.poc.sap.common.sap.SapDestination;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Provider S/4 nativo (sdd/common/autenticacion-sap.md). Segun {@code sap.s4.auth.type}:
 * <ul>
 *   <li>{@code oauth2}: client-credentials contra el token endpoint del
 *       communication arrangement, con cacheo por expiracion.</li>
 *   <li>{@code basic}: usuario/password del communication user
 *       (cabecera Authorization Basic).</li>
 * </ul>
 * Sin configuracion completa la app <b>no arranca</b>, salvo
 * {@code sap.auth.allow-stub=true} (mock local): entonces token stub con aviso
 * en el log. Antes el fallback era silencioso (auditoria A8).
 */
@Component
public class S4NativeAuthProvider implements SapAuthProvider {

    private static final Logger log = LoggerFactory.getLogger(S4NativeAuthProvider.class);
    static final String STUB_TOKEN = "stub-s4-token";

    private final OAuth2TokenClient tokenClient = new OAuth2TokenClient();
    private final String authType;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final String username;
    private final String password;
    private final boolean allowStub;

    public S4NativeAuthProvider(@Value("${sap.s4.auth.type:oauth2}") String authType,
                                @Value("${sap.s4.auth.token-url:}") String tokenUrl,
                                @Value("${sap.s4.auth.client-id:}") String clientId,
                                @Value("${sap.s4.auth.client-secret:}") String clientSecret,
                                @Value("${sap.s4.auth.username:}") String username,
                                @Value("${sap.s4.auth.password:}") String password,
                                @Value("${sap.auth.allow-stub:false}") boolean allowStub) {
        this.authType = n(authType);
        this.tokenUrl = n(tokenUrl);
        this.clientId = n(clientId);
        this.clientSecret = n(clientSecret);
        this.username = n(username);
        this.password = n(password);
        this.allowStub = allowStub;
    }

    @PostConstruct
    void validate() {
        if (!configured()) {
            if (!allowStub) {
                throw new IllegalStateException("Credenciales S/4 incompletas para sap.s4.auth.type=" + authType
                        + " (oauth2: token-url, client-id, client-secret; basic: username, password) y "
                        + "sap.auth.allow-stub=false: la app no arranca sin autenticacion real. "
                        + "Contra el mock local exporta SAP_AUTH_ALLOW_STUB=true");
            }
            log.warn("S/4 sin credenciales ({}): se usara un token STUB (sap.auth.allow-stub=true). "
                    + "Solo valido contra un SAP simulado", authType);
        }
    }

    @Override
    public SapDestination supports() {
        return SapDestination.S4_NATIVE;
    }

    @Override
    public String accessToken() {
        if (isBasic()) {
            return "";
        }
        if (!configured()) {
            return stubOrFail();
        }
        return tokenClient.accessToken(tokenUrl, clientId, clientSecret);
    }

    @Override
    public String authorizationHeader() {
        if (isBasic()) {
            if (!configured()) {
                return "Bearer " + stubOrFail();
            }
            String basic = Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            return "Basic " + basic;
        }
        return "Bearer " + accessToken();
    }

    boolean configured() {
        if (isBasic()) {
            return !username.isBlank() && !password.isBlank();
        }
        return !clientId.isBlank() && !clientSecret.isBlank() && !tokenUrl.isBlank();
    }

    private boolean isBasic() {
        return "basic".equalsIgnoreCase(authType);
    }

    private String stubOrFail() {
        if (!allowStub) {
            throw new IllegalStateException("S/4 sin credenciales y sin sap.auth.allow-stub");
        }
        return STUB_TOKEN;
    }

    private static String n(String s) { return s == null ? "" : s; }
}

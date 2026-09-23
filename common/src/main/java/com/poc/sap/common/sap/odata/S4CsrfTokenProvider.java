package com.poc.sap.common.sap.odata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Flujo CSRF de SAP S/4HANA (OData V2):
 * <ol>
 *   <li>GET a la URL de fetch con {@code x-csrf-token: Fetch} y la misma
 *       cabecera {@code Authorization} que la escritura (auditoria A6: antes iba
 *       siempre en Basic con usuario/password aunque el destino fuera OAuth2)</li>
 *   <li>Token de la cabecera {@code x-csrf-token} de la respuesta</li>
 *   <li>Cookies de {@code Set-Cookie} (SAP_SESSIONID*, sap-usercontext...): solo
 *       el par nombre=valor, sin atributos</li>
 *   <li>Token y cookies se cachean y se devuelven <b>juntos</b> (un solo
 *       {@link CsrfToken} inmutable) hasta que {@link #invalidate()} los borra.
 *       Antes el token y las cookies se leian por separado y sin sincronizar
 *       (auditoria C4)</li>
 * </ol>
 */
public class S4CsrfTokenProvider implements CsrfTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(S4CsrfTokenProvider.class);

    private static final String CSRF_HEADER = "x-csrf-token";
    private static final String FETCH_VALUE = "Fetch";

    private final HttpClient httpClient;
    private final Duration timeout;
    private volatile CsrfToken cached;

    public S4CsrfTokenProvider() {
        this(Duration.ofSeconds(30));
    }

    public S4CsrfTokenProvider(Duration timeout) {
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public boolean requiresCsrf() {
        return true;
    }

    @Override
    public synchronized CsrfToken fetchToken(String fetchUrl, String authorizationHeader) {
        CsrfToken current = cached;
        if (current != null) {
            return current;
        }
        log.debug("Fetching CSRF token from {}", fetchUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fetchUrl + (fetchUrl.contains("?") ? "&" : "?") + "$top=1"))
                .header(CSRF_HEADER, FETCH_VALUE)
                .header("Authorization", authorizationHeader)
                .header("Accept", "application/json")
                .timeout(timeout)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("Fetch CSRF fallo con status={}: no se cachea token", response.statusCode());
                return null;
            }
            String token = response.headers().firstValue(CSRF_HEADER).orElse(null);
            if (token == null) {
                log.warn("SAP no devolvio x-csrf-token (status={})", response.statusCode());
                return null;
            }
            List<String> setCookies = response.headers().allValues("Set-Cookie");
            String cookies = setCookies.isEmpty() ? null : setCookies.stream()
                    .map(c -> c.split(";", 2)[0])
                    .reduce((a, b) -> a + "; " + b)
                    .orElse(null);
            cached = new CsrfToken(token, cookies);
            log.info("CSRF token obtenido correctamente");
            return cached;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Error obteniendo CSRF token de {}", fetchUrl, e);
            throw new IllegalStateException("Fallo al obtener CSRF token de SAP: " + e.getMessage(), e);
        }
    }

    @Override
    public void invalidate() {
        log.debug("Invalidating CSRF token");
        cached = null;
    }
}

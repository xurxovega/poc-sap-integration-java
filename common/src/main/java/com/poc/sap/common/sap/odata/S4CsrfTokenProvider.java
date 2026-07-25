package com.poc.sap.common.sap.odata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Implementacion del flujo CSRF para SAP S/4HANA on-premise y private cloud.
 *
 * <p>Flujo:
 * <ol>
 *   <li>GET al recurso base con header {@code x-csrf-token: Fetch}</li>
 *   <li>Extrae token de la cabecera {@code x-csrf-token} de la respuesta</li>
 *   <li>Extrae cookies de {@code Set-Cookie} (mantienen sesion)</li>
 *   <li>Cachea token hasta que se invalida (SAP rechaza con 403)</li>
 * </ol>
 *
 * <p>Uso tipico en un adaptador S/4:
 * <pre>{@code
 * String csrf = csrfProvider.fetchToken(baseUrl, user, pass);
 * // Añadir header x-csrf-token + cookies en POST/PATCH/DELETE
 * }</pre>
 */
public class S4CsrfTokenProvider implements CsrfTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(S4CsrfTokenProvider.class);

    private static final String CSRF_HEADER = "x-csrf-token";
    private static final String FETCH_VALUE = "Fetch";
    private static final String REQUIRED_VALUE = "Required";

    private final HttpClient httpClient;
    private String cachedToken;
    private String cachedCookies;

    public S4CsrfTokenProvider() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public boolean requiresCsrf() {
        return true;
    }

    @Override
    public synchronized String fetchToken(String baseUrl, String username, String password) {
        if (cachedToken != null) {
            return cachedToken;
        }

        log.debug("Fetching CSRF token from {}", baseUrl);

        String auth = java.util.Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "?$top=1"))
                .header(CSRF_HEADER, FETCH_VALUE)
                .header("Authorization", "Basic " + auth)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                log.warn("Fetch CSRF fallo con status={} — no se cachea token", response.statusCode());
                return null;
            }

            String token = response.headers().firstValue(CSRF_HEADER).orElse(null);
            if (token == null) {
                log.warn("SAP no devolvio x-csrf-token (status={})", response.statusCode());
                return null;
            }

            // SAP devuelve varias Set-Cookie (SAP_SESSIONID*, sap-usercontext...):
            // se conservan todas (solo el par nombre=valor, sin atributos).
            java.util.List<String> setCookies = response.headers().allValues("Set-Cookie");
            cachedCookies = setCookies.isEmpty() ? null : setCookies.stream()
                    .map(c -> c.split(";", 2)[0])
                    .reduce((a, b) -> a + "; " + b)
                    .orElse(null);

            cachedToken = token;
            log.info("CSRF token obtenido correctamente");
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Error obteniendo CSRF token de {}", baseUrl, e);
            throw new RuntimeException("Fallo al obtener CSRF token de SAP: " + e.getMessage(), e);
        }

        return cachedToken;
    }

    @Override
    public synchronized void invalidate() {
        log.debug("Invalidating CSRF token");
        cachedToken = null;
        cachedCookies = null;
    }

    /**
     * Devuelve las cookies de sesion asociadas al CSRF token actual.
     * Deben añadirse como header {@code Cookie} en las peticiones de escritura.
     */
    public String cookies() {
        return cachedCookies;
    }
}

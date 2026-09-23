package com.poc.sap.common.sap.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Cliente OAuth2 client-credentials con cacheo de token por expiracion.
 * Reutilizado por {@link BtpAuthProvider} (xsuaa) y {@link S4NativeAuthProvider}.
 *
 * <p>Renueva el token 60 segundos antes de su expiracion declarada
 * ({@code expires_in}); si el servidor no la informa, cachea 5 minutos.
 */
final class OAuth2TokenClient {

    private static final Logger log = LoggerFactory.getLogger(OAuth2TokenClient.class);
    private static final Duration SAFETY_MARGIN = Duration.ofSeconds(60);
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private String cachedToken;
    private Instant expiresAt = Instant.EPOCH;

    synchronized String accessToken(String tokenUrl, String clientId, String clientSecret) {
        if (cachedToken != null && Instant.now().isBefore(expiresAt)) {
            return cachedToken;
        }
        String basic = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Token endpoint respondio " + response.statusCode() + ": " + response.body());
            }
            JsonNode json = mapper.readTree(response.body());
            String token = json.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("Respuesta del token endpoint sin access_token");
            }
            long expiresIn = json.path("expires_in").asLong(0);
            Duration ttl = expiresIn > 0 ? Duration.ofSeconds(expiresIn).minus(SAFETY_MARGIN) : DEFAULT_TTL;
            cachedToken = token;
            expiresAt = Instant.now().plus(ttl.isNegative() ? Duration.ZERO : ttl);
            log.debug("Token OAuth2 renovado, expira {}", expiresAt);
            return cachedToken;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Fallo obteniendo token OAuth2 de " + tokenUrl, e);
        }
    }

    synchronized void invalidate() {
        cachedToken = null;
        expiresAt = Instant.EPOCH;
    }
}

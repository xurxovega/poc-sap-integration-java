package com.poc.sap.common.sap;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.auth.SapAuthProvider;
import com.poc.sap.common.sap.odata.CsrfTokenProvider;
import com.poc.sap.common.sap.odata.S4CsrfTokenProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Implementacion base de {@link SapClient} con WebClient, auth, retry y
 * circuit breaker (TECH.md §8). Cada destino SAP tiene su base URL y provider.
 *
 * <p>Semantica de errores:
 * <ul>
 *   <li>5xx y errores de transporte (timeout, conexion) lanzan excepcion
 *       interna → Resilience4j reintenta y el circuit breaker cuenta el fallo.
 *       Agotados los reintentos, se devuelve {@code SapResponse} con el status
 *       original (o 0 si fue transporte).</li>
 *   <li>4xx NO se reintenta: se devuelve tal cual (error de contrato/datos).</li>
 *   <li>403 en escrituras S/4 con CSRF activo: se invalida el token, se
 *       refresca y se reintenta una unica vez.</li>
 * </ul>
 */
public class WebClientSapClient implements SapClient {

    private static final Logger log = LoggerFactory.getLogger(WebClientSapClient.class);
    private static final String CSRF_HEADER = "x-csrf-token";

    private final Map<SapDestination, WebClient> clients = new ConcurrentHashMap<>();
    private final Map<SapDestination, SapAuthProvider> authProviders;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final CsrfTokenProvider csrfProvider;
    private final String s4BaseUrl;
    private final String csrfFetchPath;
    private final String s4Username;
    private final String s4Password;

    public WebClientSapClient(Map<SapDestination, SapAuthProvider> authProviders,
                              String btpBaseUrl,
                              String s4BaseUrl,
                              RetryRegistry retryRegistry,
                              CircuitBreakerRegistry cbRegistry,
                              CsrfTokenProvider csrfProvider,
                              SapClientTimeouts timeouts,
                              String csrfFetchPath,
                              String s4Username,
                              String s4Password) {
        this.authProviders = authProviders;
        this.retry = retryRegistry.retry("sap");
        this.circuitBreaker = cbRegistry.circuitBreaker("sap");
        this.csrfProvider = csrfProvider;
        this.s4BaseUrl = s4BaseUrl;
        this.csrfFetchPath = csrfFetchPath;
        this.s4Username = s4Username;
        this.s4Password = s4Password;

        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) timeouts.connect().toMillis())
                .responseTimeout(timeouts.response());
        ReactorClientHttpConnector connector = new ReactorClientHttpConnector(http);
        this.clients.put(SapDestination.BTP,
                WebClient.builder().baseUrl(btpBaseUrl).clientConnector(connector).build());
        this.clients.put(SapDestination.S4_NATIVE,
                WebClient.builder().baseUrl(s4BaseUrl).clientConnector(connector).build());
    }

    /** Timeouts de conexion y respuesta del cliente HTTP. */
    public record SapClientTimeouts(Duration connect, Duration response) {}

    @Override
    public SapResponse send(SapDestination destination,
                            String path,
                            String entityId,
                            String payloadHash,
                            String body) {
        return exchange(destination, path, entityId, payloadHash, body, "POST");
    }

    @Override
    public SapResponse get(SapDestination destination, String path) {
        return exchange(destination, path, null, null, null, "GET");
    }

    @Override
    public SapResponse patch(SapDestination destination,
                             String path,
                             String entityId,
                             String payloadHash,
                             String body) {
        return exchange(destination, path, entityId, payloadHash, body, "PATCH");
    }

    @Override
    public SapResponse delete(SapDestination destination, String path) {
        return exchange(destination, path, null, null, null, "DELETE");
    }

    private SapResponse exchange(SapDestination destination,
                                  String path,
                                  String entityId,
                                  String payloadHash,
                                  String body,
                                  String method) {
        Supplier<SapResponse> attempt = () -> doExchange(destination, path, entityId, payloadHash, body, method);
        Supplier<SapResponse> resilient = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, attempt));
        try {
            SapResponse response = resilient.get();
            if (isCsrfRejection(destination, method, response)) {
                log.warn("CSRF rechazado por SAP ({} {}), refrescando token y reintentando", method, path);
                csrfProvider.invalidate();
                response = resilient.get();
            }
            return response;
        } catch (SapServerException e) {
            log.error("Error SAP {} {} entityId={} status={} tras reintentos", method, destination, entityId, e.status());
            return new SapResponse(e.status(), e.getMessage(), null);
        } catch (Exception e) {
            log.error("Error de transporte SAP {} {} entityId={}", method, destination, entityId, e);
            return new SapResponse(0, e.getMessage(), null);
        }
    }

    private SapResponse doExchange(SapDestination destination,
                                    String path,
                                    String entityId,
                                    String payloadHash,
                                    String body,
                                    String method) {
        WebClient client = clients.get(destination);
        SapAuthProvider auth = authProviders.get(destination);
        if (client == null || auth == null) {
            throw new IllegalArgumentException("Destino SAP no configurado: " + destination);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        headers.set(HttpHeaders.AUTHORIZATION, auth.authorizationHeader());
        if (payloadHash != null) {
            headers.set("Idempotency-Key", payloadHash);
        }
        applyCsrf(destination, method, headers);

        Mono<SapResponse> call = switch (method) {
            case "GET"    -> client.get().uri(path).headers(h -> h.addAll(headers))
                    .exchangeToMono(WebClientSapClient::toResponse);
            case "DELETE" -> client.delete().uri(path).headers(h -> h.addAll(headers))
                    .exchangeToMono(WebClientSapClient::toResponse);
            case "PATCH"  -> client.patch().uri(path).headers(h -> h.addAll(headers))
                    .bodyValue(body != null ? body : "{}")
                    .exchangeToMono(WebClientSapClient::toResponse);
            default       -> client.post().uri(path).headers(h -> h.addAll(headers))
                    .bodyValue(body != null ? body : "{}")
                    .exchangeToMono(WebClientSapClient::toResponse);
        };

        SapResponse response = call.block();
        if (response != null && response.httpStatus() >= 500) {
            throw new SapServerException(response.httpStatus(), response.body());
        }
        return response;
    }

    private boolean isWrite(String method) {
        return !"GET".equals(method);
    }

    private void applyCsrf(SapDestination destination, String method, HttpHeaders headers) {
        if (csrfProvider == null || !csrfProvider.requiresCsrf()
                || destination != SapDestination.S4_NATIVE || !isWrite(method)) {
            return;
        }
        String token = csrfProvider.fetchToken(s4BaseUrl + csrfFetchPath, s4Username, s4Password);
        if (token != null) {
            headers.set(CSRF_HEADER, token);
            if (csrfProvider instanceof S4CsrfTokenProvider s4 && s4.cookies() != null) {
                headers.set(HttpHeaders.COOKIE, s4.cookies());
            }
        }
    }

    private boolean isCsrfRejection(SapDestination destination, String method, SapResponse response) {
        return csrfProvider != null && csrfProvider.requiresCsrf()
                && destination == SapDestination.S4_NATIVE && isWrite(method)
                && response != null && response.httpStatus() == 403;
    }

    private static Mono<SapResponse> toResponse(org.springframework.web.reactive.function.client.ClientResponse resp) {
        return resp.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(b -> new SapResponse(
                        resp.statusCode().value(),
                        b,
                        resp.headers().asHttpHeaders().getFirst("Location")));
    }

    /** 5xx de SAP: visible para Retry/CircuitBreaker antes de normalizar a SapResponse. */
    public static final class SapServerException extends RuntimeException {
        private final int status;

        SapServerException(int status, String body) {
            super("SAP respondio " + status + (body == null || body.isBlank() ? "" : ": " + body));
            this.status = status;
        }

        int status() {
            return status;
        }
    }
}

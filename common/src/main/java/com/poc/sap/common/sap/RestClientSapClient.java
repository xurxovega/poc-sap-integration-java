package com.poc.sap.common.sap;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.auth.SapAuthProvider;
import com.poc.sap.common.sap.odata.CsrfTokenProvider;
import com.poc.sap.common.sap.odata.CsrfTokenProvider.CsrfToken;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Implementacion de {@link SapClient} sobre {@link RestClient} de Spring
 * (sincrono, sobre el {@code HttpClient} del JDK). Sustituye al antiguo
 * {@code WebClientSapClient} (WebClient reactivo + {@code .block()}) por la
 * decision D-1 del plan de accion: ADR-0001
 * ({@code docs/architecture/adr/0001-transporte-http-sap-restclient.md}).
 * Spec: {@code docs/sdd/common/resiliencia-cliente-sap.md}.
 *
 * <p>Semantica de errores (AC-1..AC-4 del spec):
 * <ul>
 *   <li>5xx y errores de transporte (timeout, conexion) lanzan excepcion
 *       interna: Resilience4j reintenta y el circuit breaker cuenta el fallo.
 *       Agotados los reintentos, se devuelve {@code SapResponse} con el status
 *       original (o 0 si fue transporte).</li>
 *   <li>4xx NO se reintenta: se devuelve tal cual (error de contrato/datos).</li>
 *   <li>Circuito abierto: {@link SapCircuitOpenException}, nunca una respuesta
 *       falsa (auditoria B13).</li>
 *   <li>403 con {@code x-csrf-token: Required} en escrituras S/4: se invalida
 *       el token, se refresca y se reintenta una unica vez. Un 403 sin esa
 *       cabecera es un rechazo de autorizacion y se devuelve tal cual
 *       (auditoria A6/C4).</li>
 * </ul>
 */
public class RestClientSapClient implements SapClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientSapClient.class);
    private static final String CSRF_HEADER = "x-csrf-token";
    private static final String CSRF_REQUIRED = "Required";

    private final Map<SapDestination, RestClient> clients = new EnumMap<>(SapDestination.class);
    private final Map<SapDestination, SapAuthProvider> authProviders;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final CsrfTokenProvider csrfProvider;
    private final String s4BaseUrl;
    private final String csrfFetchPath;

    public RestClientSapClient(Map<SapDestination, SapAuthProvider> authProviders,
                               String btpBaseUrl,
                               String s4BaseUrl,
                               RetryRegistry retryRegistry,
                               CircuitBreakerRegistry cbRegistry,
                               CsrfTokenProvider csrfProvider,
                               SapClientTimeouts timeouts,
                               String csrfFetchPath) {
        this.authProviders = authProviders;
        this.retry = retryRegistry.retry("sap");
        this.circuitBreaker = cbRegistry.circuitBreaker("sap");
        this.csrfProvider = csrfProvider;
        this.s4BaseUrl = s4BaseUrl;
        this.csrfFetchPath = csrfFetchPath;

        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(timeouts.connect())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(timeouts.response());

        this.clients.put(SapDestination.BTP,
                RestClient.builder().baseUrl(btpBaseUrl).requestFactory(factory).build());
        this.clients.put(SapDestination.S4_NATIVE,
                RestClient.builder().baseUrl(s4BaseUrl).requestFactory(factory).build());
    }

    /** Timeouts de conexion y respuesta del cliente HTTP. */
    public record SapClientTimeouts(Duration connect, Duration response) {}

    @Override
    public SapResponse send(SapDestination destination, String path, String entityId,
                            String payloadHash, String body) {
        return exchange(destination, path, entityId, payloadHash, body, HttpMethod.POST);
    }

    @Override
    public SapResponse get(SapDestination destination, String path) {
        return exchange(destination, path, null, null, null, HttpMethod.GET);
    }

    @Override
    public SapResponse patch(SapDestination destination, String path, String entityId,
                             String payloadHash, String body) {
        return exchange(destination, path, entityId, payloadHash, body, HttpMethod.PATCH);
    }

    @Override
    public SapResponse delete(SapDestination destination, String path) {
        return exchange(destination, path, null, null, null, HttpMethod.DELETE);
    }

    private SapResponse exchange(SapDestination destination, String path, String entityId,
                                 String payloadHash, String body, HttpMethod method) {
        Supplier<RawResponse> attempt = () -> doExchange(destination, path, payloadHash, body, method);
        // Circuit breaker POR FUERA del retry: con el circuito abierto no hay nada
        // que reintentar (auditoria B13).
        Supplier<RawResponse> resilient = CircuitBreaker.decorateSupplier(circuitBreaker,
                Retry.decorateSupplier(retry, attempt));
        try {
            RawResponse response = resilient.get();
            if (isCsrfRejection(destination, method, response)) {
                log.warn("CSRF rechazado por SAP ({} {}), refrescando token y reintentando", method, path);
                csrfProvider.invalidate();
                response = resilient.get();
            }
            return response.toSapResponse();
        } catch (CallNotPermittedException e) {
            log.warn("Circuito SAP abierto: {} {} entityId={} no se intenta", method, destination, entityId);
            throw new SapCircuitOpenException(destination, e);
        } catch (SapServerException e) {
            log.error("Error SAP {} {} entityId={} status={} tras reintentos", method, destination, entityId, e.status());
            return new SapResponse(e.status(), e.getMessage(), null);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error de transporte SAP {} {} entityId={}", method, destination, entityId, e);
            return new SapResponse(0, e.getMessage(), null);
        }
    }

    private RawResponse doExchange(SapDestination destination, String path, String payloadHash,
                                   String body, HttpMethod method) {
        RestClient client = clients.get(destination);
        SapAuthProvider auth = authProviders.get(destination);
        if (client == null || auth == null) {
            throw new IllegalArgumentException("Destino SAP no configurado: " + destination);
        }

        RestClient.RequestBodySpec request = client.method(method)
                .uri(path)
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, auth.authorizationHeader());
        if (payloadHash != null) {
            request.header("Idempotency-Key", payloadHash);
        }
        applyCsrf(destination, method, auth, request);
        if (method == HttpMethod.POST || method == HttpMethod.PATCH || method == HttpMethod.PUT) {
            request.contentType(MediaType.APPLICATION_JSON).body(body != null ? body : "{}");
        }

        RawResponse response = request.exchange((req, res) -> new RawResponse(
                res.getStatusCode().value(), readBody(res.getBody()), res.getHeaders()));
        if (response.status() >= 500) {
            throw new SapServerException(response.status(), response.body());
        }
        return response;
    }

    private static String readBody(InputStream in) {
        try (in) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isWrite(HttpMethod method) {
        return method != HttpMethod.GET;
    }

    private boolean csrfApplies(SapDestination destination, HttpMethod method) {
        return csrfProvider != null && csrfProvider.requiresCsrf()
                && destination == SapDestination.S4_NATIVE && isWrite(method);
    }

    /**
     * El fetch CSRF se autentica con la MISMA cabecera Authorization que la
     * escritura (OAuth2 o basic segun {@code sap.s4.auth.type}); antes iba
     * siempre con Basic user/pass aunque el destino fuera OAuth2 (auditoria A6).
     */
    private void applyCsrf(SapDestination destination, HttpMethod method, SapAuthProvider auth,
                           RestClient.RequestBodySpec request) {
        if (!csrfApplies(destination, method)) {
            return;
        }
        CsrfToken token = csrfProvider.fetchToken(s4BaseUrl + csrfFetchPath, auth.authorizationHeader());
        if (token != null) {
            request.header(CSRF_HEADER, token.token());
            if (token.cookies() != null) {
                request.header(HttpHeaders.COOKIE, token.cookies());
            }
        }
    }

    /** Solo es rechazo CSRF un 403 que ademas pide token ({@code x-csrf-token: Required}). */
    private boolean isCsrfRejection(SapDestination destination, HttpMethod method, RawResponse response) {
        return csrfApplies(destination, method)
                && response.status() == 403
                && CSRF_REQUIRED.equalsIgnoreCase(response.headers().getFirst(CSRF_HEADER));
    }

    /** Respuesta cruda, con cabeceras, antes de normalizar a {@link SapResponse}. */
    private record RawResponse(int status, String body, HttpHeaders headers) {
        SapResponse toSapResponse() {
            return new SapResponse(status, body, headers.getFirst(HttpHeaders.LOCATION));
        }
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

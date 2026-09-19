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
import io.micrometer.core.instrument.MeterRegistry;
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
    /** Nombre de la configuracion y de la instancia del retry de escrituras no idempotentes. */
    static final String WRITE_RETRY = "sap-write";

    private final Map<SapDestination, RestClient> clients = new EnumMap<>(SapDestination.class);
    private final Map<SapDestination, SapAuthProvider> authProviders;
    private final Retry retry;
    private final Retry retryWrite;
    private final CircuitBreaker circuitBreaker;
    private final CsrfTokenProvider csrfProvider;
    private final String s4BaseUrl;
    private final String csrfFetchPath;
    private final MeterRegistry meterRegistry;

    public RestClientSapClient(Map<SapDestination, SapAuthProvider> authProviders,
                               String btpBaseUrl,
                               String s4BaseUrl,
                               RetryRegistry retryRegistry,
                               CircuitBreakerRegistry cbRegistry,
                               CsrfTokenProvider csrfProvider,
                               SapClientTimeouts timeouts,
                               String csrfFetchPath) {
        this(authProviders, btpBaseUrl, s4BaseUrl, retryRegistry, cbRegistry, csrfProvider, timeouts,
                csrfFetchPath, null);
    }

    /**
     * @param meterRegistry registro Micrometer para {@code sap_client_request_duration}
     *                      (una muestra por intento HTTP, con destino, metodo y
     *                      resultado); {@code null} desactiva la metrica.
     */
    public RestClientSapClient(Map<SapDestination, SapAuthProvider> authProviders,
                               String btpBaseUrl,
                               String s4BaseUrl,
                               RetryRegistry retryRegistry,
                               CircuitBreakerRegistry cbRegistry,
                               CsrfTokenProvider csrfProvider,
                               SapClientTimeouts timeouts,
                               String csrfFetchPath,
                               MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.authProviders = authProviders;
        this.retry = retryRegistry.retry("sap");
        this.retryWrite = writeRetry(retryRegistry);
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
        return exchange(destination, path, entityId, payloadHash, body, HttpMethod.POST, null);
    }

    @Override
    public SapResponse get(SapDestination destination, String path) {
        return exchange(destination, path, null, null, null, HttpMethod.GET, null);
    }

    @Override
    public SapResponse patch(SapDestination destination, String path, String entityId,
                             String payloadHash, String body, String ifMatch) {
        return exchange(destination, path, entityId, payloadHash, body, HttpMethod.PATCH, ifMatch);
    }

    @Override
    public SapResponse delete(SapDestination destination, String path, String ifMatch) {
        return exchange(destination, path, null, null, null, HttpMethod.DELETE, ifMatch);
    }

    private SapResponse exchange(SapDestination destination, String path, String entityId,
                                 String payloadHash, String body, HttpMethod method, String ifMatch) {
        Supplier<RawResponse> attempt = () -> doExchange(destination, path, payloadHash, body, method, ifMatch);
        // Circuit breaker POR FUERA del retry: con el circuito abierto no hay nada
        // que reintentar (auditoria B13).
        Supplier<RawResponse> resilient = CircuitBreaker.decorateSupplier(circuitBreaker,
                Retry.decorateSupplier(retryFor(method, ifMatch), attempt));
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
            // UnresolvedAddressException TAMBIEN es IllegalArgumentException: un fallo
            // de DNS no es un error de programacion y no puede acabar en la DLT sin
            // un solo reintento (spec R-8).
            if (TransportFailures.isConfigurationError(e)) {
                throw e;
            }
            log.error("Error de transporte SAP {} {} entityId={}", method, destination, entityId, e);
            return new SapResponse(0, e.getMessage(), null);
        } catch (Exception e) {
            log.error("Error de transporte SAP {} {} entityId={}", method, destination, entityId, e);
            return new SapResponse(0, e.getMessage(), null);
        }
    }

    private RawResponse doExchange(SapDestination destination, String path, String payloadHash,
                                   String body, HttpMethod method, String ifMatch) {
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
        if (ifMatch != null && !ifMatch.isBlank()) {
            request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        applyCsrf(destination, method, auth, request);
        if (method == HttpMethod.POST || method == HttpMethod.PATCH || method == HttpMethod.PUT) {
            request.contentType(MediaType.APPLICATION_JSON).body(body != null ? body : "{}");
        }

        long start = System.nanoTime();
        String outcome = "transport_error";
        try {
            RawResponse response = request.exchange((req, res) -> new RawResponse(
                    res.getStatusCode().value(), readBody(res.getBody()), res.getHeaders()));
            outcome = outcomeOf(response.status());
            if (response.status() >= 500) {
                throw new SapServerException(response.status(), response.body());
            }
            return response;
        } finally {
            recordRequest(destination, method, outcome, System.nanoTime() - start);
        }
    }

    /**
     * Politica de reintento por METODO y por FASE del fallo (spec R-1 reescrita, R-8).
     * GET y DELETE son idempotentes por definicion, y un PATCH con {@code If-Match}
     * lo es porque un segundo intento con el ETag ya consumido da 412 en vez de una
     * doble escritura. Todo lo demas solo se reintenta si el fallo ocurrio ANTES de
     * que la peticion saliera.
     */
    private Retry retryFor(HttpMethod method, String ifMatch) {
        boolean idempotent = method == HttpMethod.GET
                || method == HttpMethod.DELETE
                || (method == HttpMethod.PATCH && ifMatch != null && !ifMatch.isBlank());
        return idempotent ? retry : retryWrite;
    }

    /**
     * Retry de las escrituras no idempotentes: hereda intervalo y numero de intentos
     * de la configuracion {@code sap-write} del registry (o de la general si no se
     * declaro) y le impone el predicado de fase, que es lo que no se puede delegar
     * en configuracion.
     */
    private static Retry writeRetry(RetryRegistry registry) {
        io.github.resilience4j.retry.RetryConfig base = registry.getConfiguration(WRITE_RETRY)
                .orElseGet(registry::getDefaultConfig);
        io.github.resilience4j.retry.RetryConfig write =
                io.github.resilience4j.retry.RetryConfig.from(base)
                        .retryOnException(TransportFailures::isBeforeSend)
                        .build();
        return registry.retry(WRITE_RETRY, write);
    }

    /** Una muestra por intento HTTP real (los reintentos cuentan cada uno), spec observabilidad R-3. */
    private void recordRequest(SapDestination destination, HttpMethod method, String outcome, long nanos) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.timer("sap_client_request_duration",
                        "destination", destination.name(),
                        "method", method.name(),
                        "outcome", outcome)
                .record(Duration.ofNanos(nanos));
    }

    private static String outcomeOf(int status) {
        return status >= 500 ? "5xx" : status >= 400 ? "4xx" : status >= 200 ? "2xx" : "other";
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
            return new SapResponse(status, body,
                    headers.getFirst(HttpHeaders.LOCATION),
                    headers.getFirst(HttpHeaders.ETAG));
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

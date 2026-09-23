package com.poc.sap.common.sap;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.auth.SapAuthProvider;
import com.poc.sap.common.sap.odata.S4CsrfTokenProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec docs/sdd/common/resiliencia-cliente-sap.md: semantica de errores,
 * resiliencia y CSRF del {@link RestClientSapClient} contra WireMock.
 * Portado del antiguo WebClientSapClientTest al cambiar el transporte (ADR-0001):
 * el comportamiento observable no cambia, y este test es la prueba.
 */
class RestClientSapClientTest {

    private WireMockServer wiremock;

    private static final SapAuthProvider STUB_AUTH = new SapAuthProvider() {
        @Override public SapDestination supports() { return SapDestination.BTP; }
        @Override public String accessToken() { return "test-token"; }
    };

    /** Destino S/4 con basic auth: el fetch CSRF debe usar ESTA cabecera, no otra. */
    private static final SapAuthProvider BASIC_S4_AUTH = new SapAuthProvider() {
        @Override public SapDestination supports() { return SapDestination.S4_NATIVE; }
        @Override public String accessToken() { return ""; }
        @Override public String authorizationHeader() { return "Basic dXNlcjpwYXNz"; }
    };

    @BeforeEach
    void setUp() {
        wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wiremock.start();
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    private RetryRegistry lastRetries;

    /**
     * Dos politicas, como en produccion: {@code default} para las llamadas
     * idempotentes y {@code sap-write} para las escrituras no idempotentes
     * (spec R-1/R-8; el predicado de fase lo pone el cliente).
     */
    private static RetryRegistry retries() {
        RetryConfig idempotent = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .build();
        RetryConfig write = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(10))
                .build();
        return RetryRegistry.of(Map.of("default", idempotent, "sap-write", write));
    }

    private RestClientSapClient client(S4CsrfTokenProvider csrf) {
        return client(csrf, CircuitBreakerRegistry.ofDefaults(), STUB_AUTH);
    }

    private RestClientSapClient client(S4CsrfTokenProvider csrf, CircuitBreakerRegistry cbs, SapAuthProvider s4Auth) {
        return client(csrf, cbs, s4Auth, wiremock.baseUrl(), Duration.ofSeconds(5));
    }

    private RestClientSapClient client(S4CsrfTokenProvider csrf, CircuitBreakerRegistry cbs,
                                       SapAuthProvider s4Auth, String baseUrl, Duration responseTimeout) {
        Map<SapDestination, SapAuthProvider> auth = Map.of(
                SapDestination.BTP, STUB_AUTH,
                SapDestination.S4_NATIVE, s4Auth);
        lastRetries = retries();
        return new RestClientSapClient(
                auth,
                baseUrl,
                baseUrl,
                lastRetries,
                cbs,
                csrf,
                new RestClientSapClient.SapClientTimeouts(Duration.ofSeconds(2), responseTimeout),
                "/csrf-fetch");
    }

    /** Cuenta los reintentos reales de la politica de escritura del ultimo cliente construido. */
    private AtomicInteger countWriteRetries() {
        AtomicInteger retriesSeen = new AtomicInteger();
        lastRetries.retry("sap-write").getEventPublisher().onRetry(e -> retriesSeen.incrementAndGet());
        return retriesSeen;
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * AC-1: 5xx se reintenta hasta agotar intentos o tener exito, <b>en una llamada
     * idempotente</b>. Antes este test usaba un POST: con R-1 reescrita (AC-11) una
     * escritura ya no se reintenta ante un 5xx porque SAP pudo haberla aplicado.
     */
    @Test
    void retriesOn5xxUntilSuccess() {
        wiremock.stubFor(get(urlEqualTo("/api"))
                .inScenario("retry")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second"));
        wiremock.stubFor(get(urlEqualTo("/api"))
                .inScenario("retry")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("third"));
        wiremock.stubFor(get(urlEqualTo("/api"))
                .inScenario("retry")
                .whenScenarioStateIs("third")
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        SapResponse response = client(null).get(SapDestination.BTP, "/api");

        assertThat(response.httpStatus()).isEqualTo(200);
        wiremock.verify(3, getRequestedFor(urlEqualTo("/api")));
    }

    /** AC-1: agotados los reintentos se devuelve el ultimo status 5xx, no una excepcion. */
    @Test
    void exhaustedRetriesReturnLastServerError() {
        wiremock.stubFor(get(urlEqualTo("/api"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        SapResponse response = client(null).get(SapDestination.BTP, "/api");

        assertThat(response.httpStatus()).isEqualTo(500);
        wiremock.verify(3, getRequestedFor(urlEqualTo("/api")));
    }

    /** AC-2: 4xx no se reintenta y llega tal cual con su cuerpo. */
    @Test
    void clientErrorIsNotRetried() {
        wiremock.stubFor(post(urlEqualTo("/api"))
                .willReturn(aResponse().withStatus(400).withBody("bad payload")));

        SapResponse response = client(null).send(SapDestination.BTP, "/api", "E-1", "h-1", "{}");

        assertThat(response.httpStatus()).isEqualTo(400);
        assertThat(response.body()).contains("bad payload");
        wiremock.verify(1, postRequestedFor(urlEqualTo("/api")));
    }

    /** AC-3: toda escritura lleva Authorization, Accept, Content-Type e Idempotency-Key. */
    @Test
    void sendsAuthAcceptAndIdempotencyHeaders() {
        wiremock.stubFor(post(urlEqualTo("/api")).willReturn(aResponse().withStatus(200)));

        client(null).send(SapDestination.BTP, "/api", "E-1", "hash-99", "{\"a\":1}");

        wiremock.verify(postRequestedFor(urlEqualTo("/api"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("Idempotency-Key", equalTo("hash-99"))
                .withRequestBody(equalToJson("{\"a\":1}")));
    }

    /**
     * AC-3: PATCH y DELETE son metodos de primera clase del transporte (el
     * HttpClient del JDK los soporta; HttpURLConnection no soportaba PATCH). El
     * Location de la respuesta se conserva.
     */
    @Test
    void patchAndDeleteGoThroughTheSameTransport() {
        wiremock.stubFor(patch(urlEqualTo("/bp('1')")).willReturn(aResponse().withStatus(204)));
        wiremock.stubFor(delete(urlEqualTo("/bp('1')"))
                .willReturn(aResponse().withStatus(204).withHeader("Location", "/bp('1')")));

        SapResponse patched = client(null).patch(SapDestination.BTP, "/bp('1')", "1", "h-p", "{\"Name\":\"x\"}");
        SapResponse deleted = client(null).delete(SapDestination.BTP, "/bp('1')");

        assertThat(patched.httpStatus()).isEqualTo(204);
        assertThat(deleted.httpStatus()).isEqualTo(204);
        assertThat(deleted.location()).isEqualTo("/bp('1')");
        wiremock.verify(patchRequestedFor(urlEqualTo("/bp('1')"))
                .withHeader("Idempotency-Key", equalTo("h-p"))
                .withRequestBody(equalToJson("{\"Name\":\"x\"}")));
        wiremock.verify(deleteRequestedFor(urlEqualTo("/bp('1')"))
                .withHeader("Authorization", equalTo("Bearer test-token")));
    }

    /** AC-5: las escrituras a S/4 llevan x-csrf-token y las cookies del fetch. */
    @Test
    void writesToS4CarryCsrfTokenAndCookies() {
        wiremock.stubFor(get(urlPathEqualTo("/csrf-fetch"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("x-csrf-token", "tok-123")
                        .withHeader("Set-Cookie", "SAP_SESSIONID=abc; Path=/")
                        .withHeader("Set-Cookie", "sap-usercontext=xyz; Path=/")));
        wiremock.stubFor(post(urlEqualTo("/api"))
                .willReturn(aResponse().withStatus(201)));

        SapResponse response = client(new S4CsrfTokenProvider())
                .send(SapDestination.S4_NATIVE, "/api", "E-1", "h-1", "{}");

        assertThat(response.httpStatus()).isEqualTo(201);
        wiremock.verify(postRequestedFor(urlEqualTo("/api"))
                .withHeader("x-csrf-token", equalTo("tok-123"))
                .withHeader("Cookie", equalTo("SAP_SESSIONID=abc; sap-usercontext=xyz")));
    }

    /**
     * AC-6 (auditoria A6): el fetch CSRF se autentica con la MISMA cabecera que la
     * escritura. Antes iba siempre con Basic usuario/password fijos aunque el
     * destino usara OAuth2 o un basic distinto.
     */
    @Test
    void csrfFetchUsesTheDestinationAuthorizationHeader() {
        wiremock.stubFor(get(urlPathEqualTo("/csrf-fetch"))
                .willReturn(aResponse().withStatus(200).withHeader("x-csrf-token", "tok-1")));
        wiremock.stubFor(post(urlEqualTo("/api")).willReturn(aResponse().withStatus(201)));

        client(new S4CsrfTokenProvider(), CircuitBreakerRegistry.ofDefaults(), BASIC_S4_AUTH)
                .send(SapDestination.S4_NATIVE, "/api", "E-1", "h-1", "{}");

        wiremock.verify(getRequestedFor(urlPathEqualTo("/csrf-fetch"))
                .withHeader("x-csrf-token", equalTo("Fetch"))
                .withHeader("Authorization", equalTo("Basic dXNlcjpwYXNz")));
        wiremock.verify(postRequestedFor(urlEqualTo("/api"))
                .withHeader("Authorization", equalTo("Basic dXNlcjpwYXNz"))
                .withHeader("x-csrf-token", equalTo("tok-1")));
    }

    /** AC-7: 403 con x-csrf-token: Required refresca el token y reintenta UNA vez. */
    @Test
    void csrfRejectionRefreshesTokenAndRetriesOnce() {
        wiremock.stubFor(get(urlPathEqualTo("/csrf-fetch"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("x-csrf-token", "tok-fresh")
                        .withHeader("Set-Cookie", "SAP_SESSIONID=abc; Path=/")));
        wiremock.stubFor(post(urlEqualTo("/api"))
                .inScenario("csrf")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(403)
                        .withHeader("x-csrf-token", "Required"))
                .willSetStateTo("refreshed"));
        wiremock.stubFor(post(urlEqualTo("/api"))
                .inScenario("csrf")
                .whenScenarioStateIs("refreshed")
                .willReturn(aResponse().withStatus(201)));

        SapResponse response = client(new S4CsrfTokenProvider())
                .send(SapDestination.S4_NATIVE, "/api", "E-1", "h-1", "{}");

        assertThat(response.httpStatus()).isEqualTo(201);
        wiremock.verify(2, postRequestedFor(urlEqualTo("/api")));
        wiremock.verify(2, getRequestedFor(urlPathEqualTo("/csrf-fetch")));
    }

    /**
     * AC-7 (auditoria C4): un 403 SIN x-csrf-token: Required es un rechazo de
     * autorizacion, no de CSRF. No se refresca ni se reintenta: se devuelve tal cual.
     */
    @Test
    void plainForbiddenIsNotTreatedAsCsrfRejection() {
        wiremock.stubFor(get(urlPathEqualTo("/csrf-fetch"))
                .willReturn(aResponse().withStatus(200).withHeader("x-csrf-token", "tok-1")));
        wiremock.stubFor(post(urlEqualTo("/api"))
                .willReturn(aResponse().withStatus(403).withBody("No authorization")));

        SapResponse response = client(new S4CsrfTokenProvider())
                .send(SapDestination.S4_NATIVE, "/api", "E-1", "h-1", "{}");

        assertThat(response.httpStatus()).isEqualTo(403);
        assertThat(response.body()).contains("No authorization");
        wiremock.verify(1, postRequestedFor(urlEqualTo("/api")));
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/csrf-fetch")));
    }

    /**
     * observabilidad AC-3 (auditoria A9): cada intento HTTP deja una muestra en
     * sap_client_request_duration con destino, metodo y resultado. Antes el
     * WebClient.builder() estatico no registraba ninguna metrica HTTP.
     */
    @Test
    void recordsOneTimerSamplePerHttpAttemptWithDestinationMethodAndOutcome() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Map<SapDestination, SapAuthProvider> auth = Map.of(
                SapDestination.BTP, STUB_AUTH, SapDestination.S4_NATIVE, STUB_AUTH);
        RestClientSapClient c = new RestClientSapClient(auth, wiremock.baseUrl(), wiremock.baseUrl(),
                retries(), CircuitBreakerRegistry.ofDefaults(), null,
                new RestClientSapClient.SapClientTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                "/csrf-fetch", meters);
        wiremock.stubFor(post(urlEqualTo("/ok")).willReturn(aResponse().withStatus(201)));
        wiremock.stubFor(get(urlEqualTo("/down")).willReturn(aResponse().withStatus(503)));

        c.send(SapDestination.BTP, "/ok", "E-1", "h", "{}");
        c.get(SapDestination.S4_NATIVE, "/down");   // 3 intentos (lectura: politica completa)

        assertThat(meters.get("sap_client_request_duration")
                .tags("destination", "BTP", "method", "POST", "outcome", "2xx").timer().count()).isEqualTo(1);
        assertThat(meters.get("sap_client_request_duration")
                .tags("destination", "S4_NATIVE", "method", "GET", "outcome", "5xx").timer().count()).isEqualTo(3);
    }

    /**
     * AC-4 (auditoria B13): con el circuito abierto, el cliente devolvia
     * SapResponse(0) y el use case marcaba SAP_ERROR "con normalidad". Debe
     * propagarse como fallo transitorio para que la ingesta reintente con backoff.
     */
    @Test
    void openCircuitRaisesCircuitOpenExceptionInsteadOfFakeResponse() {
        CircuitBreakerRegistry cbs = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(1)
                .minimumNumberOfCalls(1)
                .failureRateThreshold(1f)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build());
        RestClientSapClient c = client(null, cbs, STUB_AUTH);
        wiremock.stubFor(post(urlEqualTo("/bp")).willReturn(aResponse().withStatus(500)));

        c.send(SapDestination.BTP, "/bp", "E-1", "h", "{}");   // abre el circuito

        assertThatThrownBy(() -> c.send(SapDestination.BTP, "/bp", "E-1", "h", "{}"))
                .isInstanceOf(SapCircuitOpenException.class);
        // UN solo intento: la escritura ya no reintenta el 5xx (AC-11), pero el
        // circuit breaker lo cuenta igual porque envuelve por fuera del retry.
        wiremock.verify(1, postRequestedFor(anyUrl()));
    }

    // ---------------------------------------------------------------------
    // Reintento por fase del fallo (spec R-1 reescrita, R-8): AC-9 .. AC-15
    // ---------------------------------------------------------------------

    /**
     * AC-9: un POST cuya respuesta se pierde por timeout de RESPUESTA no se
     * reintenta: SAP pudo haberlo aplicado y el segundo intento crearia un
     * duplicado. SAP recibe exactamente una peticion y se devuelve status 0.
     */
    @Test
    void postIsNotRetriedAfterAResponseTimeout() {
        wiremock.stubFor(post(urlEqualTo("/api"))
                .willReturn(aResponse().withStatus(201).withFixedDelay(2000)));

        SapResponse response = client(null, CircuitBreakerRegistry.ofDefaults(), STUB_AUTH,
                wiremock.baseUrl(), Duration.ofMillis(200))
                .send(SapDestination.BTP, "/api", "E-1", "h-1", "{}");

        assertThat(response.httpStatus()).isZero();
        wiremock.verify(1, postRequestedFor(urlEqualTo("/api")));
    }

    /**
     * AC-10: una escritura que falla ANTES de salir (conexion rechazada) si se
     * reintenta, hasta {@code sap.client.retry.write.max-attempts}: la peticion
     * no llego a SAP, no puede haber creado nada.
     */
    @Test
    void postIsRetriedWhenTheConnectionWasRefusedBeforeSending() throws IOException {
        String deadUrl = "http://127.0.0.1:" + closedPort();
        RestClientSapClient c = client(null, CircuitBreakerRegistry.ofDefaults(), STUB_AUTH,
                deadUrl, Duration.ofSeconds(2));
        AtomicInteger retriesSeen = countWriteRetries();

        SapResponse response = c.send(SapDestination.BTP, "/api", "E-1", "h-1", "{}");

        assertThat(response.httpStatus()).isZero();
        assertThat(retriesSeen).hasValue(1);   // maxAttempts 2 = 1 reintento
    }

    /**
     * AC-10: un host que no resuelve es un fallo de transporte anterior al envio,
     * no un error de programacion. Llega envuelto como
     * {@code ConnectException <- UnresolvedAddressException}, y
     * {@code UnresolvedAddressException} hereda de {@code IllegalArgumentException}:
     * si se ignorara por ese parentesco, un DNS con hipo mandaria el mensaje a la DLT.
     */
    @Test
    void unresolvedHostIsRetriedNotTreatedAsAProgrammingError() {
        RestClientSapClient c = client(null, CircuitBreakerRegistry.ofDefaults(), STUB_AUTH,
                "http://sap-no-existe.invalid", Duration.ofSeconds(2));
        AtomicInteger retriesSeen = countWriteRetries();

        SapResponse response = c.send(SapDestination.BTP, "/api", "E-1", "h-1", "{}");

        org.junit.jupiter.api.Assumptions.assumeTrue(response.httpStatus() == 0,
                "el resolver DNS respondio a un dominio .invalid: no se puede probar aqui");
        assertThat(retriesSeen).hasValue(1);
    }

    /** AC-11: un 5xx en una escritura NO se reintenta: SAP pudo haberla aplicado. */
    @Test
    void postIsNotRetriedOnServerError() {
        wiremock.stubFor(post(urlEqualTo("/api")).willReturn(aResponse().withStatus(500).withBody("boom")));

        SapResponse response = client(null).send(SapDestination.BTP, "/api", "E-1", "h-1", "{}");

        assertThat(response.httpStatus()).isEqualTo(500);
        wiremock.verify(1, postRequestedFor(urlEqualTo("/api")));
    }

    /**
     * AC-11: dejar de reintentar no es dejar de proteger. El circuit breaker
     * envuelve POR FUERA del retry y sigue contando el 5xx de la escritura.
     */
    @Test
    void serverErrorOnAWriteStillFeedsTheCircuitBreaker() {
        CircuitBreakerRegistry cbs = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(1)
                .minimumNumberOfCalls(1)
                .failureRateThreshold(1f)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build());
        RestClientSapClient c = client(null, cbs, STUB_AUTH);
        wiremock.stubFor(post(urlEqualTo("/api")).willReturn(aResponse().withStatus(500)));

        c.send(SapDestination.BTP, "/api", "E-1", "h-1", "{}");

        assertThatThrownBy(() -> c.send(SapDestination.BTP, "/api", "E-2", "h-2", "{}"))
                .isInstanceOf(SapCircuitOpenException.class);
        wiremock.verify(1, postRequestedFor(urlEqualTo("/api")));
    }

    /** AC-12: la politica del GET no cambia: 5xx y transporte se reintentan como siempre. */
    @Test
    void getIsStillRetriedOnServerError() {
        wiremock.stubFor(get(urlEqualTo("/bp('1')")).willReturn(aResponse().withStatus(503)));

        SapResponse response = client(null).get(SapDestination.BTP, "/bp('1')");

        assertThat(response.httpStatus()).isEqualTo(503);
        wiremock.verify(3, getRequestedFor(urlEqualTo("/bp('1')")));
    }

    /**
     * AC-13: el ETag hace idempotente al PATCH (un segundo intento con el ETag ya
     * consumido da 412, no una doble escritura), asi que se reintenta como una
     * lectura. Sin {@code If-Match} se comporta como un POST.
     */
    @Test
    void patchWithIfMatchIsRetriedLikeAnIdempotentCall() {
        wiremock.stubFor(patch(urlEqualTo("/bp('1')")).willReturn(aResponse().withStatus(500)));

        client(null).patch(SapDestination.BTP, "/bp('1')", "1", "h", "{}", "W/\"v1\"");
        wiremock.verify(3, patchRequestedFor(urlEqualTo("/bp('1')")));

        wiremock.resetRequests();
        client(null).patch(SapDestination.BTP, "/bp('1')", "1", "h", "{}");
        wiremock.verify(1, patchRequestedFor(urlEqualTo("/bp('1')")));
    }

    /** AC-14: el PATCH con precondicion envia la cabecera If-Match con el ETag indicado. */
    @Test
    void patchWithIfMatchSendsThePreconditionHeader() {
        wiremock.stubFor(patch(urlEqualTo("/bp('1')")).willReturn(aResponse().withStatus(204)));
        wiremock.stubFor(delete(urlEqualTo("/bp('1')")).willReturn(aResponse().withStatus(204)));

        client(null).patch(SapDestination.BTP, "/bp('1')", "1", "h", "{\"Name\":\"x\"}", "W/\"datetime'1'\"");
        client(null).delete(SapDestination.BTP, "/bp('1')", "W/\"datetime'1'\"");

        wiremock.verify(patchRequestedFor(urlEqualTo("/bp('1')"))
                .withHeader("If-Match", equalTo("W/\"datetime'1'\"")));
        wiremock.verify(deleteRequestedFor(urlEqualTo("/bp('1')"))
                .withHeader("If-Match", equalTo("W/\"datetime'1'\"")));
    }

    /** AC-14: la respuesta conserva el ETag, que es lo que alimenta el If-Match del PATCH siguiente. */
    @Test
    void responseKeepsTheEtag() {
        wiremock.stubFor(get(urlEqualTo("/bp('1')"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "W/\"datetime'2026-09-18'\"")));

        SapResponse response = client(null).get(SapDestination.BTP, "/bp('1')");

        // WireMock anade "--gzip" dentro de las comillas del ETag al servirlo; lo que
        // se comprueba es que la cabecera viaja hasta SapResponse, no su texto exacto.
        assertThat(response.etag()).startsWith("W/\"datetime'2026-09-18'");
    }

    /** AC-14: sin precondicion no se inventa la cabecera (un If-Match vacio es un 400 en S/4). */
    @Test
    void withoutPreconditionNoIfMatchHeaderIsSent() {
        wiremock.stubFor(patch(urlEqualTo("/bp('1')")).willReturn(aResponse().withStatus(204)));

        client(null).patch(SapDestination.BTP, "/bp('1')", "1", "h", "{}");

        wiremock.verify(patchRequestedFor(urlEqualTo("/bp('1')")).withoutHeader("If-Match"));
    }
}

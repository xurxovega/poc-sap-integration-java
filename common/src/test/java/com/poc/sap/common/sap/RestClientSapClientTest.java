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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

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

    private static RetryRegistry retries() {
        return RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .ignoreExceptions(IllegalArgumentException.class)
                .build());
    }

    private RestClientSapClient client(S4CsrfTokenProvider csrf) {
        return client(csrf, CircuitBreakerRegistry.ofDefaults(), STUB_AUTH);
    }

    private RestClientSapClient client(S4CsrfTokenProvider csrf, CircuitBreakerRegistry cbs, SapAuthProvider s4Auth) {
        Map<SapDestination, SapAuthProvider> auth = Map.of(
                SapDestination.BTP, STUB_AUTH,
                SapDestination.S4_NATIVE, s4Auth);
        return new RestClientSapClient(
                auth,
                wiremock.baseUrl(),
                wiremock.baseUrl(),
                retries(),
                cbs,
                csrf,
                new RestClientSapClient.SapClientTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                "/csrf-fetch");
    }

    /** AC-1: 5xx se reintenta hasta agotar intentos o tener exito. */
    @Test
    void retriesOn5xxUntilSuccess() {
        wiremock.stubFor(post(urlEqualTo("/api"))
                .inScenario("retry")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second"));
        wiremock.stubFor(post(urlEqualTo("/api"))
                .inScenario("retry")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("third"));
        wiremock.stubFor(post(urlEqualTo("/api"))
                .inScenario("retry")
                .whenScenarioStateIs("third")
                .willReturn(aResponse().withStatus(201).withBody("{\"ok\":true}")));

        SapResponse response = client(null).send(SapDestination.BTP, "/api", "E-1", "h-1", "{}");

        assertThat(response.httpStatus()).isEqualTo(201);
        wiremock.verify(3, postRequestedFor(urlEqualTo("/api")));
    }

    /** AC-1: agotados los reintentos se devuelve el ultimo status 5xx, no una excepcion. */
    @Test
    void exhaustedRetriesReturnLastServerError() {
        wiremock.stubFor(post(urlEqualTo("/api"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        SapResponse response = client(null).send(SapDestination.BTP, "/api", "E-1", "h-1", "{}");

        assertThat(response.httpStatus()).isEqualTo(500);
        wiremock.verify(3, postRequestedFor(urlEqualTo("/api")));
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
        wiremock.verify(3, postRequestedFor(anyUrl()));
    }
}

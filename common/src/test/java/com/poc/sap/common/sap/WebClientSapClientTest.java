package com.poc.sap.common.sap;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.auth.SapAuthProvider;
import com.poc.sap.common.sap.odata.S4CsrfTokenProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * Test de la semantica de errores y resiliencia del {@link WebClientSapClient}
 * contra WireMock: 5xx reintenta, 4xx no, CSRF se aplica en escrituras S/4.
 */
class WebClientSapClientTest {

    private WireMockServer wiremock;

    private static final SapAuthProvider STUB_AUTH = new SapAuthProvider() {
        @Override public SapDestination supports() { return SapDestination.BTP; }
        @Override public String accessToken() { return "test-token"; }
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

    private WebClientSapClient client(S4CsrfTokenProvider csrf) {
        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .ignoreExceptions(IllegalArgumentException.class)
                .build());
        Map<SapDestination, SapAuthProvider> auth = Map.of(
                SapDestination.BTP, STUB_AUTH,
                SapDestination.S4_NATIVE, STUB_AUTH);
        return new WebClientSapClient(
                auth,
                wiremock.baseUrl(),
                wiremock.baseUrl(),
                retries,
                CircuitBreakerRegistry.ofDefaults(),
                csrf,
                new WebClientSapClient.SapClientTimeouts(
                        Duration.ofSeconds(2), Duration.ofSeconds(5)),
                "/csrf-fetch",
                "user", "pass");
    }

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

    @Test
    void exhaustedRetriesReturnLastServerError() {
        wiremock.stubFor(post(urlEqualTo("/api"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        SapResponse response = client(null).send(SapDestination.BTP, "/api", "E-1", "h-1", "{}");

        assertThat(response.httpStatus()).isEqualTo(500);
        wiremock.verify(3, postRequestedFor(urlEqualTo("/api")));
    }

    @Test
    void clientErrorIsNotRetried() {
        wiremock.stubFor(post(urlEqualTo("/api"))
                .willReturn(aResponse().withStatus(400).withBody("bad payload")));

        SapResponse response = client(null).send(SapDestination.BTP, "/api", "E-1", "h-1", "{}");

        assertThat(response.httpStatus()).isEqualTo(400);
        assertThat(response.body()).contains("bad payload");
        wiremock.verify(1, postRequestedFor(urlEqualTo("/api")));
    }

    @Test
    void sendsAuthAcceptAndIdempotencyHeaders() {
        wiremock.stubFor(post(urlEqualTo("/api")).willReturn(aResponse().withStatus(200)));

        client(null).send(SapDestination.BTP, "/api", "E-1", "hash-99", "{}");

        wiremock.verify(postRequestedFor(urlEqualTo("/api"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("Idempotency-Key", equalTo("hash-99")));
    }

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

    private WebClientSapClient clientWithCircuit(CircuitBreakerRegistry cbs) {
        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .ignoreExceptions(IllegalArgumentException.class)
                .build());
        Map<SapDestination, SapAuthProvider> auth = Map.of(
                SapDestination.BTP, STUB_AUTH,
                SapDestination.S4_NATIVE, STUB_AUTH);
        return new WebClientSapClient(auth, wiremock.baseUrl(), wiremock.baseUrl(),
                retries, cbs, null,
                new WebClientSapClient.SapClientTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                "/csrf-fetch", "user", "pass");
    }

    /**
     * B13 (auditoria): con el circuito abierto, el cliente devolvia SapResponse(0)
     * y el use case marcaba SAP_ERROR "con normalidad": el listener no reintentaba
     * y nadie veia que el circuito estaba abierto. Debe propagarse como fallo
     * transitorio para que la ingesta reintente con backoff.
     */
    @Test
    void openCircuitRaisesCircuitOpenExceptionInsteadOfFakeResponse() {
        CircuitBreakerRegistry cbs = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(1)
                .minimumNumberOfCalls(1)
                .failureRateThreshold(1f)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build());
        WebClientSapClient c = clientWithCircuit(cbs);
        wiremock.stubFor(post(urlEqualTo("/bp")).willReturn(aResponse().withStatus(500)));

        c.send(SapDestination.BTP, "/bp", "E-1", "h", "{}");   // abre el circuito

        assertThatThrownBy(() -> c.send(SapDestination.BTP, "/bp", "E-1", "h", "{}"))
                .isInstanceOf(SapCircuitOpenException.class);
    }
}

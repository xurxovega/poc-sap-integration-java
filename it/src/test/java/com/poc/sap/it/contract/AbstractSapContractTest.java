package com.poc.sap.it.contract;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.WebClientSapClient;
import com.poc.sap.common.sap.auth.SapAuthProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Base de los contract tests hacia SAP (TECH.md §10; auditoria B6).
 *
 * <p>Hasta la Fase 2 estos tests stubbeaban WireMock y lo llamaban con el
 * HttpClient del JDK: verificaban el propio stub y no tocaban una sola linea de
 * produccion. Ahora construyen el {@link WebClientSapClient} REAL apuntando a
 * WireMock y cada test ejercita el adaptador real: si el adaptador cambia el
 * path, el metodo, una cabecera o el cuerpo, aqui se rompe.
 */
abstract class AbstractSapContractTest {

    @RegisterExtension
    static final WireMockExtension sap = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    protected static final String TOKEN = "contract-token";
    protected SapClient sapClient;

    @BeforeEach
    void realSapClient() {
        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .ignoreExceptions(IllegalArgumentException.class)
                .build());
        Map<SapDestination, SapAuthProvider> auth = Map.of(
                SapDestination.BTP, stubAuth(SapDestination.BTP),
                SapDestination.S4_NATIVE, stubAuth(SapDestination.S4_NATIVE));
        sapClient = new WebClientSapClient(
                auth,
                sap.baseUrl(),          // BTP y S/4 apuntan al mismo WireMock
                sap.baseUrl(),
                retries,
                CircuitBreakerRegistry.ofDefaults(),
                null,                   // sin CSRF: se prueba aparte en WebClientSapClientTest
                new WebClientSapClient.SapClientTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                "/csrf-fetch", "user", "pass");
    }

    private static SapAuthProvider stubAuth(SapDestination destination) {
        return new SapAuthProvider() {
            @Override public SapDestination supports() { return destination; }
            @Override public String accessToken() { return TOKEN; }
        };
    }
}

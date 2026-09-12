package com.poc.sap.common.sap;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec sdd/common/observabilidad.md AC-3: retry y circuit breaker "sap" exponen metricas. */
class SapResilienceMetricsTest {

    @Test
    void retryAndCircuitBreakerMetricsAreBoundForTheSapInstances() {
        RetryRegistry retries = RetryRegistry.ofDefaults();
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        new SapIntegrationConfig().sapResilienceMetrics(retries, breakers).bindTo(meters);
        retries.retry("sap");
        breakers.circuitBreaker("sap");

        assertThat(meters.find("resilience4j.retry.calls").tag("name", "sap").meters()).isNotEmpty();
        assertThat(meters.find("resilience4j.circuitbreaker.state").tag("name", "sap").meters()).isNotEmpty();
    }
}

package com.poc.sap.common.sap;

import com.poc.sap.common.sap.auth.SapAuthProvider;
import com.poc.sap.common.sap.odata.CsrfTokenProvider;
import com.poc.sap.common.sap.odata.S4CsrfTokenProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Wiring central del cliente SAP para todos los dominios (customer, article,
 * supplier). Las apps solo necesitan escanear {@code com.poc.sap.common}.
 *
 * <p>Configurable via {@code sap.client.*} en application-common.yml.
 */
@Configuration
public class SapIntegrationConfig {

    @Bean
    public Map<SapDestination, SapAuthProvider> sapAuthProviders(List<SapAuthProvider> providers) {
        Map<SapDestination, SapAuthProvider> byDestination = new EnumMap<>(SapDestination.class);
        providers.forEach(p -> byDestination.put(p.supports(), p));
        return byDestination;
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryRegistry sapRetryRegistry(
            @Value("${sap.client.retry.max-attempts:3}") int maxAttempts,
            @Value("${sap.client.retry.initial-backoff-ms:500}") long initialBackoffMs) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(initialBackoffMs), 2.0))
                .ignoreExceptions(IllegalArgumentException.class)
                .build();
        return RetryRegistry.of(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerRegistry sapCircuitBreakerRegistry(
            @Value("${sap.client.circuit-breaker.failure-rate-threshold:50}") float failureRate,
            @Value("${sap.client.circuit-breaker.sliding-window-size:10}") int windowSize,
            @Value("${sap.client.circuit-breaker.wait-duration-open-ms:30000}") long waitOpenMs) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRate)
                .slidingWindowSize(windowSize)
                .waitDurationInOpenState(Duration.ofMillis(waitOpenMs))
                .ignoreExceptions(IllegalArgumentException.class)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    @ConditionalOnProperty(name = "sap.s4.csrf.enabled", havingValue = "true")
    public CsrfTokenProvider s4CsrfTokenProvider() {
        return new S4CsrfTokenProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public SapClient sapClient(Map<SapDestination, SapAuthProvider> sapAuthProviders,
                               RetryRegistry retryRegistry,
                               CircuitBreakerRegistry circuitBreakerRegistry,
                               @Value("${sap.btp.base-url:}") String btpBaseUrl,
                               @Value("${sap.s4.base-url:}") String s4BaseUrl,
                               @Value("${sap.client.connect-timeout-ms:3000}") long connectTimeoutMs,
                               @Value("${sap.client.response-timeout-ms:20000}") long responseTimeoutMs,
                               @Value("${sap.s4.csrf.fetch-path:/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner}") String csrfFetchPath,
                               @Value("${sap.s4.auth.username:}") String s4Username,
                               @Value("${sap.s4.auth.password:}") String s4Password,
                               org.springframework.beans.factory.ObjectProvider<CsrfTokenProvider> csrfProvider) {
        return new WebClientSapClient(
                sapAuthProviders,
                btpBaseUrl,
                s4BaseUrl,
                retryRegistry,
                circuitBreakerRegistry,
                csrfProvider.getIfAvailable(),
                new WebClientSapClient.SapClientTimeouts(
                        Duration.ofMillis(connectTimeoutMs), Duration.ofMillis(responseTimeoutMs)),
                csrfFetchPath,
                s4Username,
                s4Password);
    }
}

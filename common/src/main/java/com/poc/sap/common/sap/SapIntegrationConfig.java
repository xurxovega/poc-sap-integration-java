package com.poc.sap.common.sap;

import com.poc.sap.common.sap.auth.SapAuthProvider;
import com.poc.sap.common.sap.odata.CsrfTokenProvider;
import com.poc.sap.common.sap.odata.S4CsrfTokenProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.ObjectProvider;
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

    /**
     * Dos politicas de reintento (spec resiliencia-cliente-sap R-1 reescrita, R-8):
     * <ul>
     *   <li>{@code default}: llamadas idempotentes (GET, DELETE, PATCH con
     *       {@code If-Match}). 5xx y transporte se reintentan, como siempre.</li>
     *   <li>{@code sap-write}: escrituras NO idempotentes. El predicado de fase lo
     *       impone {@code RestClientSapClient}; aqui solo van intentos y backoff.
     *       {@code connect-only=false} restaura el comportamiento anterior y es
     *       <b>solo para diagnostico</b>: reintentar un POST duplica el alta.</li>
     * </ul>
     * Nota: ya no se ignora {@code IllegalArgumentException} por su tipo, porque
     * {@code UnresolvedAddressException} (fallo de DNS) hereda de ella y quedaba
     * sin un solo reintento.
     */
    @Bean
    @ConditionalOnMissingBean
    public RetryRegistry sapRetryRegistry(
            @Value("${sap.client.retry.max-attempts:3}") int maxAttempts,
            @Value("${sap.client.retry.initial-backoff-ms:500}") long initialBackoffMs,
            @Value("${sap.client.retry.write.max-attempts:2}") int writeMaxAttempts,
            @Value("${sap.client.retry.write.connect-only:true}") boolean writeConnectOnly) {
        IntervalFunction backoff =
                IntervalFunction.ofExponentialBackoff(Duration.ofMillis(initialBackoffMs), 2.0);
        RetryConfig idempotent = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(backoff)
                .retryOnException(t -> !TransportFailures.isConfigurationError(t))
                .build();
        RetryConfig write = RetryConfig.custom()
                .maxAttempts(writeConnectOnly ? writeMaxAttempts : maxAttempts)
                .intervalFunction(backoff)
                .build();
        return RetryRegistry.of(Map.of("default", idempotent,
                RestClientSapClient.WRITE_RETRY, writeConnectOnly ? write : idempotent));
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
                // Por predicado, no por tipo: UnresolvedAddressException es un
                // IllegalArgumentException y SI es un fallo de SAP que debe contar.
                .ignoreException(TransportFailures::isConfigurationError)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    /**
     * Interruptores de la verificacion previa y el upsert (spec
     * {@code docs/sdd/common/upsert-idempotente-sap.md} §6). Los consumen los
     * adaptadores OData de cada feature.
     */
    @Bean
    @ConditionalOnMissingBean
    public SapUpsertSettings sapUpsertSettings(
            @Value("${sap.client.lookup.enabled:true}") boolean lookupEnabled,
            @Value("${sap.client.lookup.ambiguous-fails:true}") boolean ambiguousFails,
            @Value("${sap.client.upsert.refetch-on-precondition-failed:true}") boolean refetch) {
        return new SapUpsertSettings(lookupEnabled, ambiguousFails, refetch);
    }

    @Bean
    @ConditionalOnProperty(name = "sap.s4.csrf.enabled", havingValue = "true")
    public CsrfTokenProvider s4CsrfTokenProvider() {
        return new S4CsrfTokenProvider();
    }

    /**
     * Transporte HTTP: {@link RestClientSapClient} (ADR-0001). El fetch CSRF se
     * autentica con el {@code SapAuthProvider} del destino S/4, por eso ya no
     * recibe usuario/password aqui.
     */
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
                               ObjectProvider<CsrfTokenProvider> csrfProvider,
                               ObjectProvider<MeterRegistry> meterRegistry) {
        return new RestClientSapClient(
                sapAuthProviders,
                btpBaseUrl,
                s4BaseUrl,
                retryRegistry,
                circuitBreakerRegistry,
                csrfProvider.getIfAvailable(),
                new RestClientSapClient.SapClientTimeouts(
                        Duration.ofMillis(connectTimeoutMs), Duration.ofMillis(responseTimeoutMs)),
                csrfFetchPath,
                meterRegistry.getIfAvailable());
    }

    /**
     * Metricas de Resilience4j (resilience4j.retry.calls, resilience4j.circuitbreaker.state,
     * ...) para el retry y el circuit breaker "sap" (sdd/common/observabilidad.md R-3;
     * auditoria A9: sin binder, el estado del circuito era invisible).
     */
    @Bean
    public MeterBinder sapResilienceMetrics(RetryRegistry retryRegistry,
                                            CircuitBreakerRegistry circuitBreakerRegistry) {
        return registry -> {
            TaggedRetryMetrics.ofRetryRegistry(retryRegistry).bindTo(registry);
            TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry).bindTo(registry);
        };
    }
}

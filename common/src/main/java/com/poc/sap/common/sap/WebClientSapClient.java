package com.poc.sap.common.sap;

import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.sap.auth.SapAuthProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementacion base de {@link SapClient} con WebClient, auth, retry y
 * circuit breaker (TECH.md §8). Cada destino SAP tiene su base URL y provider.
 */
public class WebClientSapClient implements SapClient {

    private static final Logger log = LoggerFactory.getLogger(WebClientSapClient.class);

    private final Map<SapDestination, WebClient> clients = new ConcurrentHashMap<>();
    private final Map<SapDestination, SapAuthProvider> authProviders;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public WebClientSapClient(Map<SapDestination, SapAuthProvider> authProviders,
                              @Value("${sap.btp.base-url:}") String btpBaseUrl,
                              @Value("${sap.s4.base-url:}") String s4BaseUrl,
                              RetryRegistry retryRegistry,
                              CircuitBreakerRegistry cbRegistry) {
        this.authProviders = authProviders;
        this.retry = retryRegistry.retry("sap");
        this.circuitBreaker = cbRegistry.circuitBreaker("sap");
        this.clients.put(SapDestination.BTP, WebClient.builder().baseUrl(btpBaseUrl).build());
        this.clients.put(SapDestination.S4_NATIVE, WebClient.builder().baseUrl(s4BaseUrl).build());
    }

    @Override
    public SapResponse send(SapDestination destination,
                            String path,
                            String entityId,
                            String payloadHash,
                            String body) {
        WebClient client = clients.get(destination);
        SapAuthProvider auth = authProviders.get(destination);
        if (client == null || auth == null) {
            throw new IllegalArgumentException("Destino SAP no configurado: " + destination);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(auth.accessToken());
        headers.set("Idempotency-Key", payloadHash);

        Mono<SapResponse> call = client.post()
                .uri(path)
                .headers(h -> h.addAll(headers))
                .bodyValue(body)
                .exchangeToMono(resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(b -> new SapResponse(
                                resp.statusCode().value(),
                                b,
                                resp.headers().asHttpHeaders().getFirst("Location"))))
                .onErrorResume(e -> {
                    log.error("Error enviando a SAP {} entityId={} path={}", destination, entityId, path, e);
                    return Mono.just(new SapResponse(0, e.getMessage(), null));
                });

        return Retry.decorateSupplier(retry,
                () -> CircuitBreaker.decorateSupplier(circuitBreaker,
                        call::block).get()).get();
    }
}
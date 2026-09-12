package com.poc.sap.common.sap;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Presupuesto de reintentos por mensaje Kafka (sdd/common/observabilidad.md R-4;
 * auditoria A10). Un mensaje puede provocar hasta {@code calls-per-message}
 * llamadas a SAP, cada una con {@code max-attempts} intentos de hasta
 * {@code response-timeout} mas el backoff. Si ese peor caso alcanza
 * {@code max.poll.interval.ms}, Kafka expulsa al consumidor del grupo mientras
 * aun esta procesando y provoca rebalanceos en cascada justo cuando SAP esta
 * degradado. Con los defaults anteriores (5 x (3 x 20 s + 1,5 s) = 5,1 min contra
 * 5 min) pasaba. Este guard lo comprueba al arrancar y falla si no cuadra.
 */
@Component
public class RetryBudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(RetryBudgetGuard.class);

    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long responseTimeoutMs;
    private final int callsPerMessage;
    private final long maxPollIntervalMs;

    public RetryBudgetGuard(@Value("${sap.client.retry.max-attempts:3}") int maxAttempts,
                            @Value("${sap.client.retry.initial-backoff-ms:500}") long initialBackoffMs,
                            @Value("${sap.client.response-timeout-ms:20000}") long responseTimeoutMs,
                            @Value("${sap.client.calls-per-message:5}") int callsPerMessage,
                            @Value("${spring.kafka.consumer.properties.max.poll.interval.ms:300000}") long maxPollIntervalMs) {
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.responseTimeoutMs = responseTimeoutMs;
        this.callsPerMessage = callsPerMessage;
        this.maxPollIntervalMs = maxPollIntervalMs;
    }

    @PostConstruct
    void validate() {
        long worst = worstCaseMillis();
        if (worst >= maxPollIntervalMs) {
            throw new IllegalStateException(String.format(
                    "Presupuesto de reintentos por mensaje (%d ms = %d llamadas x (%d intentos x %d ms + backoff)) "
                    + "alcanza max.poll.interval.ms (%d ms): Kafka expulsaria al consumidor en pleno proceso. "
                    + "Sube KAFKA_MAX_POLL_INTERVAL_MS o baja SAP_CLIENT_RESPONSE_TIMEOUT_MS / SAP_CLIENT_RETRY_MAX_ATTEMPTS",
                    worst, callsPerMessage, maxAttempts, responseTimeoutMs, maxPollIntervalMs));
        }
        log.info("Presupuesto de reintentos por mensaje: {} ms (peor caso) < max.poll.interval.ms {} ms",
                worst, maxPollIntervalMs);
    }

    /** Peor caso por mensaje: todas las llamadas agotan intentos con timeout y backoff exponencial x2. */
    long worstCaseMillis() {
        return worstCaseMillis(maxAttempts, initialBackoffMs, responseTimeoutMs, callsPerMessage);
    }

    static long worstCaseMillis(int maxAttempts, long initialBackoffMs, long responseTimeoutMs, int callsPerMessage) {
        long backoff = 0;
        for (int i = 0; i < maxAttempts - 1; i++) {
            backoff += initialBackoffMs * (1L << i);
        }
        long perCall = maxAttempts * responseTimeoutMs + backoff;
        return perCall * callsPerMessage;
    }
}

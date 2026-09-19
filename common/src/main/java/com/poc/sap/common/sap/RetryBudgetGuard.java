package com.poc.sap.common.sap;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Presupuesto de reintentos por mensaje Kafka (sdd/common/observabilidad.md R-4;
 * sdd/common/upsert-idempotente-sap.md R-6; auditoria A10, hallazgo 2A-12).
 *
 * <p>Si el peor caso de un mensaje alcanza {@code max.poll.interval.ms}, Kafka
 * expulsa al consumidor del grupo mientras aun esta procesando y provoca
 * rebalanceos en cascada justo cuando SAP esta degradado. Este guard lo
 * comprueba al arrancar y falla si no cuadra.
 *
 * <p>La formula ya <b>no</b> es una sola multiplicacion: con la verificacion
 * previa cada feature hace dos llamadas y no cuestan lo mismo. Los lookups son
 * lecturas (timeout corto, politica de reintento completa) y las escrituras
 * tienen timeout largo pero casi no reintentan (spec resiliencia R-1):
 *
 * <pre>
 * peor caso = lecturas    x (intentos_lectura   x timeout_lookup   + backoff)
 *           + escrituras  x (intentos_escritura x timeout_respuesta + backoff)
 *           + (csrf ? 2 x timeout_respuesta : 0)
 *           + intentos_kafka x backoff_circuito_abierto
 * </pre>
 *
 * El fetch CSRF va FUERA del retry y se paga dos veces: el fetch inicial y el
 * refresco tras un 403 {@code x-csrf-token: Required}.
 *
 * <p>El ultimo sumando es el que faltaba: con el circuito abierto, el
 * {@code DefaultErrorHandler} duerme el hilo del consumidor con su backoff largo
 * ({@code app.kafka.retry.circuit-open-backoff-ms}) una vez por intento
 * ({@code app.kafka.retry.max-attempts}), y ese tiempo cuenta igual para
 * {@code max.poll.interval.ms}. Sin el, el presupuesto declaraba 90 s de menos.
 */
@Component
public class RetryBudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(RetryBudgetGuard.class);

    private final int maxAttempts;
    private final int writeMaxAttempts;
    private final long initialBackoffMs;
    private final long responseTimeoutMs;
    private final long lookupTimeoutMs;
    private final int callsPerMessage;
    private final int lookupCallsPerMessage;
    private final boolean csrfEnabled;
    private final int kafkaMaxAttempts;
    private final long kafkaCircuitOpenBackoffMs;
    private final long maxPollIntervalMs;

    public RetryBudgetGuard(@Value("${sap.client.retry.max-attempts:3}") int maxAttempts,
                            @Value("${sap.client.retry.write.max-attempts:2}") int writeMaxAttempts,
                            @Value("${sap.client.retry.initial-backoff-ms:500}") long initialBackoffMs,
                            @Value("${sap.client.response-timeout-ms:20000}") long responseTimeoutMs,
                            @Value("${sap.client.lookup.timeout-ms:5000}") long lookupTimeoutMs,
                            @Value("${sap.client.calls-per-message:5}") int callsPerMessage,
                            @Value("${sap.client.lookup.calls-per-message:6}") int lookupCallsPerMessage,
                            @Value("${sap.s4.csrf.enabled:false}") boolean csrfEnabled,
                            @Value("${app.kafka.retry.max-attempts:3}") int kafkaMaxAttempts,
                            @Value("${app.kafka.retry.circuit-open-backoff-ms:30000}") long kafkaCircuitOpenBackoffMs,
                            @Value("${spring.kafka.consumer.properties.max.poll.interval.ms:300000}") long maxPollIntervalMs) {
        this.maxAttempts = maxAttempts;
        this.writeMaxAttempts = writeMaxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.responseTimeoutMs = responseTimeoutMs;
        this.lookupTimeoutMs = lookupTimeoutMs;
        this.callsPerMessage = callsPerMessage;
        this.lookupCallsPerMessage = lookupCallsPerMessage;
        this.csrfEnabled = csrfEnabled;
        this.kafkaMaxAttempts = kafkaMaxAttempts;
        this.kafkaCircuitOpenBackoffMs = kafkaCircuitOpenBackoffMs;
        this.maxPollIntervalMs = maxPollIntervalMs;
    }

    @PostConstruct
    void validate() {
        long worst = worstCaseMillis();
        if (worst >= maxPollIntervalMs) {
            throw new IllegalStateException(String.format(
                    "Presupuesto de reintentos por mensaje (%d ms = %d lookups x (%d intentos x %d ms + backoff) "
                    + "+ %d escrituras x (%d intentos x %d ms + backoff)%s + backoff Kafka del circuito abierto) "
                    + "alcanza max.poll.interval.ms (%d ms): "
                    + "Kafka expulsaria al consumidor en pleno proceso. Sube KAFKA_MAX_POLL_INTERVAL_MS o baja "
                    + "SAP_CLIENT_RESPONSE_TIMEOUT_MS / SAP_CLIENT_RETRY_MAX_ATTEMPTS",
                    worst, lookupCallsPerMessage, maxAttempts, lookupTimeoutMs,
                    callsPerMessage, writeMaxAttempts, responseTimeoutMs,
                    csrfEnabled ? " + 2 fetch CSRF" : "", maxPollIntervalMs));
        }
        log.info("Presupuesto de reintentos por mensaje: {} ms (peor caso: {} lookups + {} escrituras{} "
                        + "+ {} reintentos Kafka x {} ms de circuito abierto) < max.poll.interval.ms {} ms",
                worst, lookupCallsPerMessage, callsPerMessage, csrfEnabled ? " + CSRF" : "",
                kafkaMaxAttempts, kafkaCircuitOpenBackoffMs, maxPollIntervalMs);
    }

    /** Peor caso por mensaje con la configuracion activa. */
    long worstCaseMillis() {
        return worstCaseMillis(maxAttempts, writeMaxAttempts, initialBackoffMs, lookupTimeoutMs,
                responseTimeoutMs, lookupCallsPerMessage, callsPerMessage, csrfEnabled,
                kafkaMaxAttempts, kafkaCircuitOpenBackoffMs);
    }

    /**
     * @param readAttempts   intentos de una llamada idempotente (lookup incluido)
     * @param writeAttempts  intentos de una escritura no idempotente
     * @param initialBackoffMs backoff inicial, exponencial x2
     * @param lookupTimeoutMs  timeout de respuesta de un lookup
     * @param responseTimeoutMs timeout de respuesta de una escritura
     * @param lookupCalls    lookups que puede provocar UN mensaje
     * @param writeCalls     escrituras que puede provocar UN mensaje
     * @param csrfEnabled    si el fetch de token CSRF esta activo
     * @param kafkaAttempts  entregas del mensaje que hace el {@code DefaultErrorHandler}
     *                       ({@code app.kafka.retry.max-attempts})
     * @param kafkaCircuitOpenBackoffMs backoff con el que duerme el hilo del consumidor
     *                       entre entregas cuando el circuito esta abierto
     *                       ({@code app.kafka.retry.circuit-open-backoff-ms})
     * @return milisegundos del peor caso
     */
    static long worstCaseMillis(int readAttempts, int writeAttempts, long initialBackoffMs,
                                long lookupTimeoutMs, long responseTimeoutMs,
                                int lookupCalls, int writeCalls, boolean csrfEnabled,
                                int kafkaAttempts, long kafkaCircuitOpenBackoffMs) {
        long reads = lookupCalls * (readAttempts * lookupTimeoutMs + backoff(readAttempts, initialBackoffMs));
        long writes = writeCalls * (writeAttempts * responseTimeoutMs + backoff(writeAttempts, initialBackoffMs));
        long csrf = csrfEnabled ? 2 * responseTimeoutMs : 0;
        long kafka = kafkaAttempts * kafkaCircuitOpenBackoffMs;
        return reads + writes + csrf + kafka;
    }

    /** Solo el coste de las llamadas a SAP, sin el backoff de reentrega de Kafka. */
    static long worstCaseMillis(int readAttempts, int writeAttempts, long initialBackoffMs,
                                long lookupTimeoutMs, long responseTimeoutMs,
                                int lookupCalls, int writeCalls, boolean csrfEnabled) {
        return worstCaseMillis(readAttempts, writeAttempts, initialBackoffMs, lookupTimeoutMs,
                responseTimeoutMs, lookupCalls, writeCalls, csrfEnabled, 0, 0);
    }

    /** Suma del backoff exponencial x2 entre intentos. */
    private static long backoff(int attempts, long initialBackoffMs) {
        long total = 0;
        for (int i = 0; i < attempts - 1; i++) {
            total += initialBackoffMs * (1L << i);
        }
        return total;
    }
}

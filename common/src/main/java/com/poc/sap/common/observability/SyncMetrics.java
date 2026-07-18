package com.poc.sap.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Facade de metricas por dominio y estado de la maquina de estados (SPEC.md §7,
 * TECH.md §9). Centraliza contadores y timers para que los dominios no dependan
 * directamente de Micrometer.
 */
@Component
public class SyncMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> stateCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> stageTimers = new ConcurrentHashMap<>();

    public SyncMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Incrementa el contador de registros que alcanzan un estado.
     */
    public void incrementState(String domain, String state) {
        stateCounters.computeIfAbsent(
                key(domain, state),
                k -> Counter.builder("sap_sync_state_total")
                        .tag("domain", domain)
                        .tag("state", state)
                        .register(registry))
                .increment();
    }

    /**
     * Registra la duracion de una etapa del pipeline (fetch, validate, index, send).
     */
    public void recordStageDuration(String domain, String stage, long durationMillis) {
        stageTimers.computeIfAbsent(
                key(domain, stage),
                k -> Timer.builder("sap_sync_stage_duration")
                        .tag("domain", domain)
                        .tag("stage", stage)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(registry))
                .record(java.time.Duration.ofMillis(durationMillis));
    }

    private static String key(String a, String b) {
        return a + ":" + b;
    }
}

package com.poc.sap.common.domain.port;

/**
 * Puerto de metricas del pipeline (sdd/common/observabilidad.md R-1, R-2).
 * Permite que {@code application} registre estados y duraciones sin depender
 * de Micrometer (auditoria A4: 14 use cases importaban la implementacion).
 * Implementacion: {@code common/observability/SyncMetrics}.
 */
public interface MetricsPort {

    /** Incrementa el contador de registros que alcanzan un estado. */
    void incrementState(String domain, String state);

    /** Registra la duracion de una etapa del pipeline (fetch, validate, index, send). */
    void recordStageDuration(String domain, String stage, long durationMillis);

    /** Resultado de cada parte (feature) enviada por separado: sap_sync_feature_result_total (ADR-0010). */
    default void incrementFeatureResult(String domain, String feature, String result) {
    }
}

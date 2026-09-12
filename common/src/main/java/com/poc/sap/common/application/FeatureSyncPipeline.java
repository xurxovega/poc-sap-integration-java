package com.poc.sap.common.application;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.ValidationResult;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.common.domain.port.SapOutboundPort;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;

import java.util.Locale;
import java.util.function.Function;

/**
 * Pipeline por feature (sdd/common/maquina-de-estados.md §6, linea de feature):
 * {@code VALIDATING -> VALID | INVALID -> SENDING_SAP -> SENT_SAP | SAP_ERROR}
 * sobre la linea de estado {@code <entityId>:<FEATURE>}. Cada feature de un
 * dominio aporta su validador y su puerto SAP; el recorrido es el mismo
 * (plan Fase 7, auditoria A5: cuatro copias identicas en customer).
 *
 * @param <D> datos de la feature (AddressData, FiscalData, ...)
 */
public final class FeatureSyncPipeline<D> {

    private final String feature;
    private final String origin;
    private final Function<D, ValidationResult> validator;
    private final SapOutboundPort<D> sapPort;
    private final SyncCycleRecorder cycle;

    /**
     * @param sapPort puerto SAP de la feature; {@code null} si solo se va a validar
     */
    public FeatureSyncPipeline(String domain, String feature, Function<D, ValidationResult> validator,
                               SapOutboundPort<D> sapPort, SyncStateRepositoryPort stateRepo, MetricsPort metrics) {
        this.feature = feature;
        this.origin = feature.toLowerCase(Locale.ROOT);
        this.validator = validator;
        this.sapPort = sapPort;
        this.cycle = new SyncCycleRecorder(domain, stateRepo, metrics);
    }

    /** Identificador de la linea de feature en el repositorio de estado: {@code entityId:FEATURE}. */
    public static String featureEntityId(String entityId, String feature) {
        return entityId + ":" + feature;
    }

    public String featureEntityId(String entityId) {
        return featureEntityId(entityId, feature);
    }

    /** Valida y envia. Un evento nuevo siempre abre ciclo por VALIDATING (R-3). */
    public SyncState sync(String entityId, String payloadHash, D data) {
        if (sapPort == null) {
            throw new IllegalStateException("FeatureSyncPipeline de " + feature + " sin puerto SAP: solo puede validar");
        }
        String line = featureEntityId(entityId);
        SyncState verdict = validateLine(line, payloadHash, data);
        if (verdict != SyncState.VALID) {
            return verdict;
        }
        cycle.advance(line, origin, payloadHash, SyncState.VALID, SyncState.SENDING_SAP);
        var response = sapPort.send(entityId, payloadHash, data);
        SyncState target = response.isSuccess() ? SyncState.SENT_SAP : SyncState.SAP_ERROR;
        cycle.advance(line, origin, payloadHash, SyncState.SENDING_SAP, target);
        return target;
    }

    /** Solo valida: abre ciclo por VALIDATING y avanza a VALID o INVALID. */
    public SyncState validate(String entityId, String payloadHash, D data) {
        return validateLine(featureEntityId(entityId), payloadHash, data);
    }

    private SyncState validateLine(String line, String payloadHash, D data) {
        cycle.beginCycle(line, origin, payloadHash, SyncState.VALIDATING);
        ValidationResult v = validator.apply(data);
        SyncState target = v.valid() ? SyncState.VALID : SyncState.INVALID;
        cycle.advance(line, origin, payloadHash, SyncState.VALIDATING, target);
        return target;
    }
}

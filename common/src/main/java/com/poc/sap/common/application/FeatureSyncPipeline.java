package com.poc.sap.common.application;

import com.poc.sap.common.application.SyncCycleRecorder.Cycle;
import com.poc.sap.common.domain.ConcurrentTransitionException;
import com.poc.sap.common.domain.FeatureOutcome;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.ValidationResult;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.common.domain.port.SapOutboundPort;
import com.poc.sap.common.domain.port.SapOutboundPort.SapLookup;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.sap.SapCircuitOpenException;
import com.poc.sap.common.sap.SapLookupUnavailableException;
import com.poc.sap.common.sap.SapUpsertSettings;

import java.time.Clock;
import java.util.Locale;
import java.util.function.Function;

/**
 * Pipeline por feature (sdd/common/maquina-de-estados.md §6, linea de feature):
 * {@code VALIDATING -> VALID | INVALID -> SENDING_SAP -> SENT_SAP | SAP_ERROR |
 * COMMUNICATION_ERROR} sobre la linea de estado {@code <entityId>:<FEATURE>}.
 * Cada feature de un dominio aporta su validador y su puerto SAP; el recorrido es
 * el mismo (plan Fase 7, auditoria A5: cuatro copias identicas en customer).
 *
 * <p>La llamada a SAP va envuelta: el puerto puede LANZAR (circuito abierto, CSRF,
 * mapeo) y antes eso dejaba la linea colgada en {@code SENDING_SAP} para siempre
 * y sin aviso (auditoria 2026-09-18 N1). Ahora la linea cierra siempre, con el
 * motivo escrito, y aqui se decide UNA sola vez el estado de la parte.
 *
 * @param <D> datos de la feature (AddressData, FiscalData, ...)
 */
public final class FeatureSyncPipeline<D> {

    private final String feature;
    private final String origin;
    private final Function<D, ValidationResult> validator;
    private final SapOutboundPort<D> sapPort;
    private final SyncCycleRecorder cycle;
    private final Clock clock;
    private final SapUpsertSettings upsert;

    /**
     * Con los interruptores de upsert por defecto: verificar siempre y no adivinar
     * nunca ({@link SapUpsertSettings#defaults()}).
     *
     * @param sapPort puerto SAP de la feature; {@code null} si solo se va a validar
     */
    public FeatureSyncPipeline(String domain, String feature, Function<D, ValidationResult> validator,
                               SapOutboundPort<D> sapPort, SyncStateRepositoryPort stateRepo,
                               MetricsPort metrics, Clock clock) {
        this(domain, feature, validator, sapPort, stateRepo, metrics, clock, SapUpsertSettings.defaults());
    }

    /**
     * @param sapPort puerto SAP de la feature; {@code null} si solo se va a validar
     * @param upsert  interruptores de la verificacion previa
     *                (spec {@code docs/sdd/common/upsert-idempotente-sap.md} §6.1)
     */
    public FeatureSyncPipeline(String domain, String feature, Function<D, ValidationResult> validator,
                               SapOutboundPort<D> sapPort, SyncStateRepositoryPort stateRepo,
                               MetricsPort metrics, Clock clock, SapUpsertSettings upsert) {
        this.upsert = upsert;
        this.feature = feature;
        this.origin = feature.toLowerCase(Locale.ROOT);
        this.validator = validator;
        this.sapPort = sapPort;
        this.clock = clock;
        this.cycle = new SyncCycleRecorder(domain, stateRepo, metrics, clock);
    }

    /** Identificador de la linea de feature en el repositorio de estado: {@code entityId:FEATURE}. */
    public static String featureEntityId(String entityId, String feature) {
        return entityId + ":" + feature;
    }

    public String featureEntityId(String entityId) {
        return featureEntityId(entityId, feature);
    }

    /**
     * Valida y envia. Un evento nuevo siempre abre ciclo por VALIDATING (R-3), y la
     * linea hereda el {@code cycleId} del agregado para que la traza de pasos del
     * envio se pueda reconstruir de una sola consulta.
     *
     * <p>No relanza el fallo de SAP: lo convierte en el estado de la parte, para que
     * el bucle del orquestador complete y el aviso salga siempre (ADR-0010). La
     * unica excepcion es {@link ConcurrentTransitionException}, que no es un fallo
     * de SAP sino la senal de que este ciclo perdio la carrera.
     */
    public FeatureOutcome sync(String entityId, String cycleId, String payloadHash, D data) {
        if (sapPort == null) {
            throw new IllegalStateException("FeatureSyncPipeline de " + feature + " sin puerto SAP: solo puede validar");
        }
        String line = featureEntityId(entityId);
        Cycle c = cycle.beginCycle(line, cycleId, origin, payloadHash, SyncState.VALIDATING);

        ValidationResult v = validator.apply(data);
        if (!v.valid()) {
            String why = String.join("; ", v.errors());
            cycle.advance(c, SyncState.VALIDATING, SyncState.INVALID, why);
            return new FeatureOutcome(feature, SyncState.INVALID, why, clock.instant());
        }
        cycle.advance(c, SyncState.VALIDATING, SyncState.VALID);
        cycle.advance(c, SyncState.VALID, SyncState.SENDING_SAP);

        SyncState target;
        String detail = null;
        try {
            var response = write(entityId, payloadHash, data);
            if (response.isSuccess()) {
                target = SyncState.SENT_SAP;
            } else if (response.httpStatus() == 0) {
                // Transporte agotado: no sabemos si llego (RestClientSapClient).
                target = SyncState.COMMUNICATION_ERROR;
                detail = "transporte: " + trim(response.body());
            } else {
                target = SyncState.SAP_ERROR;
                detail = "HTTP " + response.httpStatus() + ": " + trim(response.body());
            }
        } catch (SapCircuitOpenException e) {
            // No se llamo a SAP: reenviar es seguro.
            target = SyncState.COMMUNICATION_ERROR;
            detail = "circuito SAP abierto";
        } catch (SapLookupUnavailableException e) {
            // No sabemos que tiene SAP, asi que NO se escribio nada (R-3): reenviar es seguro.
            target = SyncState.COMMUNICATION_ERROR;
            detail = "lookup no concluyente: " + trim(e.getMessage());
        } catch (ConcurrentTransitionException e) {
            throw e;   // este ciclo perdio la carrera: no es un fallo de SAP
        } catch (RuntimeException e) {
            target = SyncState.SAP_ERROR;
            detail = e.getClass().getSimpleName() + ": " + trim(e.getMessage());
        }
        cycle.advance(c, SyncState.SENDING_SAP, target, detail);
        return new FeatureOutcome(feature, target, detail, clock.instant());
    }

    /**
     * Escribe en SAP con verificacion previa (spec
     * {@code docs/sdd/common/upsert-idempotente-sap.md} R-1 a R-3): se pregunta
     * primero que tiene SAP y con la respuesta en la mano se decide alta o
     * actualizacion. Si el lookup no concluye <b>no se escribe nada</b>: preferir
     * un alta «por si acaso» es justo lo que duplica el dato maestro.
     */
    private SapResponse write(String entityId, String payloadHash, D data) {
        if (!upsert.lookupEnabled()) {
            return sapPort.send(entityId, payloadHash, data);   // comportamiento anterior
        }
        SapLookup found = sapPort.lookup(entityId, data);
        return switch (found.outcome()) {
            case UNAVAILABLE -> throw new SapLookupUnavailableException(feature, found.detail());
            case FOUND -> updateWithOneRefetch(entityId, payloadHash, data, found);
            case NOT_FOUND, NOT_SUPPORTED -> sapPort.send(entityId, payloadHash, data);
        };
    }

    /**
     * R-7: un {@code 412} significa que alguien modifico el recurso entre nuestro
     * lookup y nuestra escritura. Se repite el lookup y la escritura <b>una</b>
     * vez; un segundo {@code 412} se deja pasar como respuesta de SAP y la parte
     * termina en {@code SAP_ERROR} con el motivo.
     */
    private SapResponse updateWithOneRefetch(String entityId, String payloadHash, D data, SapLookup found) {
        SapResponse response = sapPort.update(entityId, payloadHash, data, found);
        if (!response.isPreconditionFailed() || !upsert.refetchOnPreconditionFailed()) {
            return response;
        }
        SapLookup again = sapPort.lookup(entityId, data);
        return switch (again.outcome()) {
            case UNAVAILABLE -> throw new SapLookupUnavailableException(feature, again.detail());
            case FOUND -> sapPort.update(entityId, payloadHash, data, again);
            case NOT_FOUND, NOT_SUPPORTED -> sapPort.send(entityId, payloadHash, data);
        };
    }

    /** Solo valida: abre ciclo por VALIDATING y avanza a VALID o INVALID. */
    public SyncState validate(String entityId, String payloadHash, D data) {
        String line = featureEntityId(entityId);
        Cycle c = cycle.beginCycle(line, origin, payloadHash, SyncState.VALIDATING);
        ValidationResult v = validator.apply(data);
        if (!v.valid()) {
            cycle.advance(c, SyncState.VALIDATING, SyncState.INVALID, String.join("; ", v.errors()));
            return SyncState.INVALID;
        }
        cycle.advance(c, SyncState.VALIDATING, SyncState.VALID);
        return SyncState.VALID;
    }

    /** El motivo nunca crece sin limite: la respuesta de SAP puede ser enorme. */
    private static String trim(String body) {
        if (body == null || body.isBlank()) {
            return "sin cuerpo";
        }
        return body.length() > SyncStateTransition.MAX_DETAIL
                ? body.substring(0, SyncStateTransition.MAX_DETAIL)
                : body;
    }
}

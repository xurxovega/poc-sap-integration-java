package com.poc.sap.customer.application.general;

import com.poc.sap.common.application.SyncCycleRecorder;
import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncNotificationPort;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.customer.application.CustomerFeatureSync;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.CustomerFeature;
import com.poc.sap.customer.domain.CustomerValidations;
import com.poc.sap.customer.domain.port.CustomerImageStorePort;
import com.poc.sap.customer.domain.port.CustomerHistoryIndexerPort;
import com.poc.sap.customer.domain.port.CustomerLegacyRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Orchestrador general del dominio Customer (OVERVIEW.md §2, §5; TECH.md §6).
 *
 * <p>Ejecuta el pipeline completo (fetch → validar aggregate → indexar →
 * ejecutar features SAP) y puede invocar **todas o solo varias features** del
 * aggregate, segun la funcionalidad de negocio. Estado maestro a nivel de
 * aggregate (entityId.original), las features gestionan su propio estado por
 * entidad compuesta {@code customerId:FEATURE}.
 */
public class SyncCustomerUseCase {

    private static final Logger log = LoggerFactory.getLogger(SyncCustomerUseCase.class);
    private static final String DOMAIN = "customer";

    private final CustomerLegacyRepositoryPort legacyRepo;
    private final CustomerImageStorePort imageStore;
    private final CustomerHistoryIndexerPort historyIndexer;
    private final SyncStateRepositoryPort stateRepo;
    private final MetricsPort metrics;
    private final SyncNotificationPort notifications;

    private final SyncCycleRecorder cycle;

    private final CustomerFeatureSync address;
    private final CustomerFeatureSync fiscal;
    private final CustomerFeatureSync contact;
    private final CustomerFeatureSync banking;

    public SyncCustomerUseCase(CustomerLegacyRepositoryPort legacyRepo,
                              CustomerImageStorePort imageStore,
                              CustomerHistoryIndexerPort historyIndexer,
                              SyncStateRepositoryPort stateRepo,
                              MetricsPort metrics,
                              SyncNotificationPort notifications,
                              CustomerFeatureSync address,
                              CustomerFeatureSync fiscal,
                              CustomerFeatureSync contact,
                              CustomerFeatureSync banking) {
        this.legacyRepo = legacyRepo;
        this.imageStore = imageStore;
        this.historyIndexer = historyIndexer;
        this.stateRepo = stateRepo;
        this.metrics = metrics;
        this.notifications = notifications;
        this.cycle = new SyncCycleRecorder(DOMAIN, stateRepo, metrics);
        this.address = address;
        this.fiscal = fiscal;
        this.contact = contact;
        this.banking = banking;
    }

    /** Ejecuta todas las features (comportamiento por defecto). */
    public SyncState execute(IngestionMessage message) {
        return execute(message, EnumSet.allOf(CustomerFeature.class));
    }

    /**
     * Ejecuta solo las features indicadas. Permite que una funcionalidad de
     * negocio (p.ej. un cambio solo en direccion) invoque ADDRESS sin tocar
     * FISCAL/BANKING.
     */
    public SyncState execute(IngestionMessage message, Set<CustomerFeature> features) {
        if (features == null || features.isEmpty()) {
            throw new IllegalArgumentException("features no puede ser vacio");
        }
        if (stateRepo.alreadySent(DOMAIN, message.entityId(), message.payloadHash())) {
            log.info("SyncCustomer dedupe entityId={} payloadHash={} ya enviado a SAP, se omite",
                    message.entityId(), message.payloadHash());
            return SyncState.SENT_SAP;
        }
        log.info("SyncCustomer inicio entityId={} origin={} features={}",
                message.entityId(), message.origin(), features);

        boolean lastCycleSent = stateRepo.currentState(DOMAIN, message.entityId())
                .filter(s -> s == SyncState.SENT_SAP)
                .isPresent();

        // R-5 (sdd/customer/sincronizacion-cliente.md): un evento nuevo siempre
        // abre ciclo, venga de SENT_SAP, SAP_ERROR, ERROR o de un ciclo en vuelo.
        beginCycle(message, SyncState.RECEIVED);
        transition(message, SyncState.RECEIVED, SyncState.FETCHING);

        Optional<Customer> fetched = timed("fetch", () -> legacyRepo.fetch(message.entityId()));
        if (fetched.isEmpty()) {
            transition(message, SyncState.FETCHING, SyncState.ERROR);
            return SyncState.ERROR;
        }
        Customer customer = fetched.get();

        transition(message, SyncState.FETCHING, SyncState.VALIDATING);
        var validation = timed("validate", () -> CustomerValidations.validate(customer, features));
        if (!validation.valid()) {
            log.warn("Customer invalido entityId={} errors={}", message.entityId(), validation.errors());
            transition(message, SyncState.VALIDATING, SyncState.INVALID);
            return SyncState.INVALID;
        }
        transition(message, SyncState.VALIDATING, SyncState.VALID);

        // R-6: cualquier fallo de infraestructura tras VALID (Mongo, ES, HTTP)
        // deja la entidad en ERROR y se propaga. Antes quedaba colgada en
        // INDEXING/SENDING_SAP para siempre (auditoria B12/C2).
        try {
            // "Sin cambios reales" (idempotencia-y-dedupe R-3): si el ciclo anterior
            // termino en SENT_SAP y el snapshot re-leido del legacy es identico a la
            // imagen (= lo que SAP tiene, R-4), no hay nada que enviar. SENT_SAP con
            // este hash significa "SAP esta en sincronia con este payload".
            if (lastCycleSent && imageStore.find(customer.id()).filter(customer::equals).isPresent()) {
                log.info("SyncCustomer sin cambios reales entityId={} (snapshot == imagen staging), no se reenvia",
                        message.entityId());
                transition(message, SyncState.VALID, SyncState.SENT_SAP);
                return SyncState.SENT_SAP;
            }

            // El historico registra lo que SE VA A ENVIAR, con un documento por
            // intento (idempotencia-y-dedupe R-5): un reenvio no pisa la version anterior.
            transition(message, SyncState.VALID, SyncState.INDEXING);
            timed("index", () -> {
                historyIndexer.index(customer.id(), customer, message.payloadHash());
                return null;
            });
            transition(message, SyncState.INDEXING, SyncState.INDEXED);

            transition(message, SyncState.INDEXED, SyncState.SENDING_SAP);
            SyncState finalState = timed("send", () -> sendFeatures(message, customer, features));
            if (finalState == SyncState.SENT_SAP) {
                // La imagen es "lo que SAP tiene": se persiste solo tras el ACK de todas
                // las features (idempotencia-y-dedupe R-4). Antes se guardaba antes de
                // enviar y un SAP_ERROR dejaba una imagen que SAP nunca recibio.
                imageStore.save(customer.id(), customer);
            }
            transition(message, SyncState.SENDING_SAP, finalState);

            log.info("SyncCustomer fin entityId={} state={}", message.entityId(), finalState);
            return finalState;
        } catch (RuntimeException e) {
            markError(message, e);
            throw e;
        }
    }

    /**
     * Envia cada parte por su propia linea de estado y decide el estado del agregado
     * (R-7). No hay compensacion (ADR-0010, D-2): si una parte falla, las que
     * entraron se quedan en SAP, el agregado termina en SAP_ERROR o INVALID, cada
     * linea de feature dice que paso, y se AVISA (R-9). El siguiente evento reenvia.
     */
    private SyncState sendFeatures(IngestionMessage message, Customer customer, Set<CustomerFeature> features) {
        Map<CustomerFeature, CustomerFeatureSync> dispatch = Map.of(
                CustomerFeature.ADDRESS, address,
                CustomerFeature.FISCAL,  fiscal,
                CustomerFeature.CONTACT, contact,
                CustomerFeature.BANKING, banking);

        Map<String, SyncState> results = new java.util.LinkedHashMap<>();
        for (CustomerFeature f : features) {
            CustomerFeatureSync uc = dispatch.get(f);
            if (uc == null) {
                continue;
            }
            SyncState s = uc.execute(customer, message.payloadHash());
            results.put(f.name(), s);
            metrics.incrementFeatureResult(DOMAIN, f.name(), s.name());
        }
        boolean anyInvalid = results.containsValue(SyncState.INVALID);
        boolean allOk = results.values().stream().allMatch(s -> s == SyncState.SENT_SAP);
        SyncState finalState = anyInvalid ? SyncState.INVALID : (allOk ? SyncState.SENT_SAP : SyncState.SAP_ERROR);
        if (!allOk) {
            notifications.partialFailure(DOMAIN, message.entityId(), message.payloadHash(), results);
        }
        return finalState;
    }

    /** Duracion de cada etapa en sap_sync_stage_duration (sdd/common/observabilidad.md R-2). */
    private <T> T timed(String stage, Supplier<T> body) {
        long start = System.nanoTime();
        try {
            return body.get();
        } finally {
            metrics.recordStageDuration(DOMAIN, stage, (System.nanoTime() - start) / 1_000_000);
        }
    }

    private void beginCycle(IngestionMessage msg, SyncState entry) {
        cycle.beginCycle(msg.entityId(), msg.origin().name().toLowerCase(), msg.payloadHash(), entry);
    }

    /** R-6: registra ERROR sin enmascarar la excepcion original si el propio registro falla. */
    private void markError(IngestionMessage msg, RuntimeException cause) {
        try {
            transition(msg, null, SyncState.ERROR);
        } catch (RuntimeException e) {
            log.error("No se pudo registrar ERROR entityId={} tras fallo '{}'", msg.entityId(), cause.toString(), e);
        }
    }

    private void transition(IngestionMessage msg, SyncState from, SyncState to) {
        cycle.advance(msg.entityId(), msg.origin().name().toLowerCase(), msg.payloadHash(), from, to);
    }
}
package com.poc.sap.customer.application.general;

import com.poc.sap.common.application.SyncCycleRecorder;
import com.poc.sap.common.application.SyncCycleRecorder.Cycle;
import com.poc.sap.common.domain.ConcurrentTransitionException;
import com.poc.sap.common.domain.FeatureOutcome;
import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.NothingReachedSapException;
import com.poc.sap.common.domain.PayloadHasher;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.port.SyncNotificationPort;
import com.poc.sap.common.domain.port.SyncNotificationPort.SyncPartialFailure;
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

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
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
 * entidad compuesta {@code customerId:FEATURE}, todas dentro del mismo ciclo.
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
    private final Clock clock;
    /** R-10 (D-15): propagar cuando sea demostrable que el ciclo no escribio en SAP. */
    private final boolean rethrowWhenNothingReachedSap;

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
                              CustomerFeatureSync banking,
                              Clock clock,
                              boolean rethrowWhenNothingReachedSap) {
        this.legacyRepo = legacyRepo;
        this.imageStore = imageStore;
        this.historyIndexer = historyIndexer;
        this.stateRepo = stateRepo;
        this.metrics = metrics;
        this.notifications = notifications;
        this.clock = clock;
        this.rethrowWhenNothingReachedSap = rethrowWhenNothingReachedSap;
        this.cycle = new SyncCycleRecorder(DOMAIN, stateRepo, metrics, clock);
        this.address = address;
        this.fiscal = fiscal;
        this.contact = contact;
        this.banking = banking;
    }

    /** Resultado del envio de las partes: estado del agregado y traza de pasos. */
    private record AggregateOutcome(SyncState state, List<FeatureOutcome> attempts) {}

    /** Ejecuta todas las features (comportamiento por defecto). */
    public SyncState execute(IngestionMessage message) {
        return execute(message, EnumSet.allOf(CustomerFeature.class));
    }

    /**
     * Ejecuta solo las features indicadas. Permite que una funcionalidad de
     * negocio (p.ej. un cambio solo en direccion) invoque ADDRESS sin tocar
     * FISCAL/BANKING.
     *
     * <p>Fuente del {@code Customer}: el legacy (SQL Server / PostgreSQL). Es
     * el camino CDC y REST cuando el llamador solo aporta la identidad del
     * cambio (mensaje fino, ADR-0013). Para fuente alternativa (payload REST),
     * usar {@link #executeFromPayload(IngestionMessage, Customer, Set)}.
     */
    public SyncState execute(IngestionMessage message, Set<CustomerFeature> features) {
        if (features == null || features.isEmpty()) {
            throw new IllegalArgumentException("features no puede ser vacio");
        }
        log.info("SyncCustomer inicio entityId={} origin={} features={}",
                message.entityId(), message.origin(), features);

        // ADR-0013 (mensaje fino): el aviso solo dice QUE entidad cambio. La fuente
        // de datos es el legacy y el hash de idempotencia se calcula sobre lo que se
        // acaba de leer, nunca sobre lo que traiga el mensaje.
        Optional<Customer> fetched = timed("fetch", () -> legacyRepo.fetch(message.entityId()));
        if (fetched.isEmpty()) {
            Cycle failed = cycle.beginCycle(message.entityId(), origin(message),
                    message.payloadHash(), SyncState.RECEIVED);
            cycle.advance(failed, SyncState.RECEIVED, SyncState.FETCHING);
            cycle.advance(failed, SyncState.FETCHING, SyncState.ERROR, "no existe en el legacy");
            return SyncState.ERROR;
        }
        Customer customer = fetched.get();

        return runPipeline(message, customer, features);
    }

    /**
     * Ejecuta el mismo pipeline que {@link #execute(IngestionMessage, Set)} pero
     * <b>sin releer el legacy</b>: el {@code Customer} ya viene del llamador
     * (p. ej. body de un PUT/PATCH manual de Business Partner, PRD-10). El
     * resto es identico: hash sobre el snapshot, dedupe, maquina de estados,
     * lookup + upsert en SAP.
     *
     * <p>Si el legacy no tiene la entidad, en CDC se devuelve
     * {@link SyncState#ERROR}; aqui eso no aplica porque el caller ya ha
     * decidido que la entidad existe (la esta creando o actualizando). Si el
     * caller quiere borrar que use el flujo de baja, no este.
     *
     * <p>Difiere de {@link #execute(IngestionMessage, Set)} en un punto
     * importante: {@code features} puede ser vacio, lo que significa
     * "solo el agregado, sin subentidades". Es lo que hace el upsert manual
     * de Business Partner: escribe el BP y nada mas; las features siguen
     * sincronizandose por su propio flujo ({@code POST /customers/sync}).
     *
     * @param message  identificador del cambio; debe llevar {@code origin=REST}
     *                 en este flujo, pero el orquestador no lo exige.
     * @param sourced  snapshot construido por el caller; su hash es el del
     *                 dedupe y el que viaja a SAP.
     * @param features features a ejecutar (ADDRESS, FISCAL, CONTACT, BANKING).
     *                 Vacio o {@code null} significa "solo el agregado".
     */
    public SyncState executeFromPayload(IngestionMessage message, Customer sourced,
                                        Set<CustomerFeature> features) {
        if (sourced == null) {
            throw new IllegalArgumentException("sourced obligatorio");
        }
        if (features == null) {
            features = EnumSet.noneOf(CustomerFeature.class);
        }
        log.info("SyncCustomer (payload) inicio entityId={} origin={} features={}",
                message.entityId(), message.origin(), features);
        return runPipeline(message, sourced, features);
    }

    /**
     * Esqueleto comun: dado un {@code Customer} ya construido, ejecuta
     * hash -> dedupe -> maquina de estados -> indexar -> enviar SAP ->
     * imagen tras ACK -> aviso parcial. Es el mismo camino desde CDC y desde
     * REST (PRD-10); la unica diferencia entre los dos puntos de entrada
     * esta en como se obtiene el {@code Customer}.
     */
    private SyncState runPipeline(IngestionMessage message, Customer customer,
                                  Set<CustomerFeature> features) {
        String payloadHash = PayloadHasher.hash(customer);
        if (message.payloadHash() != null && !message.payloadHash().equals(payloadHash)) {
            // Solo pista de diagnostico: el hash del mensaje puede venir de una
            // version anterior de la fila. El que manda es el calculado.
            log.debug("SyncCustomer hash del mensaje {} != hash del snapshot {} entityId={}",
                    message.payloadHash(), payloadHash, message.entityId());
        }

        if (stateRepo.alreadySent(DOMAIN, message.entityId(), payloadHash)) {
            log.info("SyncCustomer dedupe entityId={} payloadHash={} (del snapshot) ya enviado a SAP, se omite",
                    message.entityId(), payloadHash);
            return SyncState.SENT_SAP;
        }

        boolean lastCycleSent = stateRepo.currentState(DOMAIN, message.entityId())
                .filter(s -> s == SyncState.SENT_SAP)
                .isPresent();

        // R-5 (sdd/customer/sincronizacion-cliente.md): un evento nuevo siempre
        // abre ciclo, venga de SENT_SAP, SAP_ERROR, ERROR o de un ciclo en vuelo.
        Cycle c = cycle.beginCycle(message.entityId(), origin(message), payloadHash, SyncState.RECEIVED);
        cycle.advance(c, SyncState.RECEIVED, SyncState.FETCHING);
        cycle.advance(c, SyncState.FETCHING, SyncState.VALIDATING);

        var validation = timed("validate", () -> CustomerValidations.validate(customer, features));
        if (!validation.valid()) {
            log.warn("Customer invalido entityId={} errors={}", message.entityId(), validation.errors());
            cycle.advance(c, SyncState.VALIDATING, SyncState.INVALID, String.join("; ", validation.errors()));
            return SyncState.INVALID;
        }
        cycle.advance(c, SyncState.VALIDATING, SyncState.VALID);

        AggregateOutcome outcome;
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
                cycle.advance(c, SyncState.VALID, SyncState.SENT_SAP);
                return SyncState.SENT_SAP;
            }

            // El historico registra lo que SE VA A ENVIAR, con un documento por
            // intento (idempotencia-y-dedupe R-5): un reenvio no pisa la version anterior.
            cycle.advance(c, SyncState.VALID, SyncState.INDEXING);
            timed("index", () -> {
                historyIndexer.index(customer.id(), customer, payloadHash);
                return null;
            });
            cycle.advance(c, SyncState.INDEXING, SyncState.INDEXED);

            cycle.advance(c, SyncState.INDEXED, SyncState.SENDING_SAP);
            outcome = timed("send", () -> sendFeatures(c, message, customer, payloadHash, features));
            if (outcome.state() == SyncState.SENT_SAP) {
                // La imagen es "lo que SAP tiene": se persiste solo tras el ACK de todas
                // las features (idempotencia-y-dedupe R-4). Antes se guardaba antes de
                // enviar y un SAP_ERROR dejaba una imagen que SAP nunca recibio.
                imageStore.save(customer.id(), customer);
            }
            cycle.advance(c, SyncState.SENDING_SAP, outcome.state());

            log.info("SyncCustomer fin entityId={} state={}", message.entityId(), outcome.state());
        } catch (RuntimeException e) {
            markError(c, message, e);
            throw e;
        }

        // R-10 (D-15): el estado y el aviso ya estan escritos; solo entonces se
        // propaga, y solo si es demostrable que nada entro en SAP.
        if (rethrowWhenNothingReachedSap && nothingReachedSap(outcome.attempts())) {
            throw new NothingReachedSapException(DOMAIN, message.entityId(), c.cycleId(),
                    "todas las partes fallaron por comunicacion");
        }
        return outcome.state();
    }

    /**
     * Envia cada parte por su propia linea de estado y decide el estado del agregado
     * (R-7). No hay compensacion (ADR-0010, D-2): si una parte falla, las que
     * entraron se quedan en SAP, el agregado termina en SAP_ERROR o INVALID, cada
     * linea de feature dice que paso y POR QUE, y se AVISA (R-9).
     *
     * <p>El bucle NUNCA aborta: cada parte va en su propio {@code try}. Antes, una
     * feature que lanzaba dejaba sin intentar a las tres siguientes y el aviso no
     * se emitia jamas (auditoria 2026-09-18 N1).
     */
    private AggregateOutcome sendFeatures(Cycle c, IngestionMessage message, Customer customer,
                                          String payloadHash, Set<CustomerFeature> features) {
        Map<CustomerFeature, CustomerFeatureSync> dispatch = Map.of(
                CustomerFeature.ADDRESS, address,
                CustomerFeature.FISCAL,  fiscal,
                CustomerFeature.CONTACT, contact,
                CustomerFeature.BANKING, banking);

        List<FeatureOutcome> attempts = new ArrayList<>();
        for (CustomerFeature f : features) {
            CustomerFeatureSync uc = dispatch.get(f);
            if (uc == null) {
                continue;
            }
            FeatureOutcome o;
            try {
                o = uc.execute(customer, c.cycleId(), payloadHash);
            } catch (ConcurrentTransitionException e) {
                throw e;   // el ciclo entero se reintenta (sdd/common/maquina-de-estados.md R-7)
            } catch (RuntimeException e) {
                // Red de seguridad: una feature que lanza fuera del pipeline no puede
                // llevarse por delante a las demas ni el aviso.
                o = new FeatureOutcome(f.name(), SyncState.SAP_ERROR,
                        e.getClass().getSimpleName() + ": " + e.getMessage(), clock.instant());
                log.error("Feature {} lanzo fuera del pipeline entityId={}", f, message.entityId(), e);
            }
            attempts.add(o);
            metrics.incrementFeatureResult(DOMAIN, f.name(), o.state().name());
        }

        SyncState finalState = aggregateStateOf(attempts);
        if (finalState != SyncState.SENT_SAP) {
            notifications.partialFailure(new SyncPartialFailure(
                    DOMAIN, message.entityId(), c.cycleId(), payloadHash,
                    finalState, List.copyOf(attempts), clock.instant()));
        }
        return new AggregateOutcome(finalState, attempts);
    }

    /**
     * R-7 (cero confianza, D-14): el agregado es INVALID solo si NINGUNA parte
     * llego a llamar a SAP. En cuanto una lo hizo, SAP puede tener algo y el
     * agregado es SAP_ERROR: "este ciclo no dejo a SAP como se pidio, revisalo".
     */
    static SyncState aggregateStateOf(List<FeatureOutcome> attempts) {
        if (attempts.stream().allMatch(FeatureOutcome::ok)) {
            return SyncState.SENT_SAP;
        }
        if (attempts.stream().allMatch(o -> o.state() == SyncState.INVALID)) {
            return SyncState.INVALID;
        }
        return SyncState.SAP_ERROR;
    }

    /** R-10: ninguna parte llego a SAP y todas las fallidas son de comunicacion. */
    private static boolean nothingReachedSap(List<FeatureOutcome> attempts) {
        return !attempts.isEmpty()
                && attempts.stream().noneMatch(FeatureOutcome::touchedSap)
                && attempts.stream().allMatch(o -> o.state() == SyncState.COMMUNICATION_ERROR);
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

    private static String origin(IngestionMessage msg) {
        return msg.origin().name().toLowerCase();
    }

    /** R-6: registra ERROR sin enmascarar la excepcion original si el propio registro falla. */
    private void markError(Cycle c, IngestionMessage msg, RuntimeException cause) {
        try {
            // from = null a proposito: cerrar en ERROR no puede fallar por una colision.
            cycle.advance(c, null, SyncState.ERROR, trim(cause.getClass().getSimpleName() + ": " + cause.getMessage()));
        } catch (RuntimeException e) {
            log.error("No se pudo registrar ERROR entityId={} tras fallo '{}'", msg.entityId(), cause.toString(), e);
        }
    }

    private static String trim(String detail) {
        return detail.length() > SyncStateTransition.MAX_DETAIL
                ? detail.substring(0, SyncStateTransition.MAX_DETAIL)
                : detail;
    }
}

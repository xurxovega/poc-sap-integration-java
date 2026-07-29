package com.poc.sap.customer.application.general;

import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.customer.application.address.SyncAddressUseCase;
import com.poc.sap.customer.application.banking.SyncBankingUseCase;
import com.poc.sap.customer.application.contact.SyncContactUseCase;
import com.poc.sap.customer.application.fiscal.SyncFiscalUseCase;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.CustomerFeature;
import com.poc.sap.customer.domain.CustomerValidations;
import com.poc.sap.customer.domain.port.CustomerImageStorePort;
import com.poc.sap.customer.domain.port.CustomerHistoryIndexerPort;
import com.poc.sap.customer.domain.port.CustomerLegacyRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Orchestrador general del dominio Customer (SPEC.md §3, §4, §8).
 *
 * <p>Ejecuta el pipeline completo (fetch → validar aggregate → indexar →
 * ejecutar features SAP) y puede invocar **todas o solo varias features** del
 * aggregate, segun la funcionalidad de negocio. Estado maestro a nivel de
 * aggregate (entityId.original), las features gestionan su propio estado por
 * entidad compuesta {@code customerId:FEATURE}.
 */
@Service
public class SyncCustomerUseCase {

    private static final Logger log = LoggerFactory.getLogger(SyncCustomerUseCase.class);
    private static final String DOMAIN = "customer";

    private final CustomerLegacyRepositoryPort legacyRepo;
    private final CustomerImageStorePort imageStore;
    private final CustomerHistoryIndexerPort historyIndexer;
    private final SyncStateRepositoryPort stateRepo;
    private final SyncMetrics metrics;

    private final SyncAddressUseCase  address;
    private final SyncFiscalUseCase  fiscal;
    private final SyncContactUseCase  contact;
    private final SyncBankingUseCase  banking;

    public SyncCustomerUseCase(CustomerLegacyRepositoryPort legacyRepo,
                              CustomerImageStorePort imageStore,
                              CustomerHistoryIndexerPort historyIndexer,
                              SyncStateRepositoryPort stateRepo,
                              SyncMetrics metrics,
                              SyncAddressUseCase address,
                              SyncFiscalUseCase fiscal,
                              SyncContactUseCase contact,
                              SyncBankingUseCase banking) {
        this.legacyRepo = legacyRepo;
        this.imageStore = imageStore;
        this.historyIndexer = historyIndexer;
        this.stateRepo = stateRepo;
        this.metrics = metrics;
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

        transition(message, null, SyncState.RECEIVED);
        transition(message, SyncState.RECEIVED, SyncState.FETCHING);

        Optional<Customer> fetched = legacyRepo.fetch(message.entityId());
        if (fetched.isEmpty()) {
            transition(message, SyncState.FETCHING, SyncState.ERROR);
            return SyncState.ERROR;
        }
        Customer customer = fetched.get();

        transition(message, SyncState.FETCHING, SyncState.VALIDATING);
        var validation = CustomerValidations.validate(customer, features);
        if (!validation.valid()) {
            log.warn("Customer invalido entityId={} errors={}", message.entityId(), validation.errors());
            transition(message, SyncState.VALIDATING, SyncState.INVALID);
            return SyncState.INVALID;
        }
        transition(message, SyncState.VALIDATING, SyncState.VALID);

        // Deteccion de "sin cambios reales": si el ciclo anterior termino en
        // SENT_SAP y el snapshot re-leido del legacy es identico a la imagen
        // actual (Mongo, staging), la modificacion no afecta a datos
        // sincronizados y no se reenvia a SAP. El historico ELK conserva el
        // snapshot de cada envio real para auditar la comparacion.
        if (lastCycleSent && imageStore.find(customer.id()).filter(customer::equals).isPresent()) {
            log.info("SyncCustomer sin cambios reales entityId={} (snapshot == imagen staging), no se reenvia",
                    message.entityId());
            transition(message, SyncState.VALID, SyncState.SENT_SAP);
            return SyncState.SENT_SAP;
        }

        transition(message, SyncState.VALID, SyncState.INDEXING);
        imageStore.save(customer.id(), customer);
        historyIndexer.index(customer.id(), customer, message.payloadHash());
        transition(message, SyncState.INDEXING, SyncState.INDEXED);

        transition(message, SyncState.INDEXED, SyncState.SENDING_SAP);
        Map<CustomerFeature, BiFunction<Customer, String, SyncState>> dispatch = Map.of(
                CustomerFeature.ADDRESS, address::execute,
                CustomerFeature.FISCAL,  fiscal::execute,
                CustomerFeature.CONTACT, contact::execute,
                CustomerFeature.BANKING, banking::execute);

        boolean allOk = true;
        boolean anyInvalid = false;
        for (CustomerFeature f : features) {
            BiFunction<Customer, String, SyncState> uc = dispatch.get(f);
            if (uc == null) {
                continue;
            }
            SyncState s = uc.apply(customer, message.payloadHash());
            if (s == SyncState.INVALID) {
                anyInvalid = true;
            } else if (s != SyncState.SENT_SAP) {
                allOk = false;
            }
        }
        SyncState finalState = anyInvalid ? SyncState.INVALID
                : (allOk ? SyncState.SENT_SAP : SyncState.SAP_ERROR);
        transition(message, SyncState.SENDING_SAP, finalState);

        log.info("SyncCustomer fin entityId={} state={}", message.entityId(), finalState);
        return finalState;
    }

    private void transition(IngestionMessage msg, SyncState from, SyncState to) {
        stateRepo.transition(DOMAIN, msg.entityId(), new SyncStateTransition(
                msg.entityId(), DOMAIN, from, to,
                msg.origin().name().toLowerCase(), msg.payloadHash(), Instant.now()));
        metrics.incrementState(DOMAIN, to.name());
    }
}
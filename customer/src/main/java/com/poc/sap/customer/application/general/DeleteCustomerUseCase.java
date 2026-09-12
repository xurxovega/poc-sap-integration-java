package com.poc.sap.customer.application.general;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.port.CustomerImageStorePort;
import com.poc.sap.customer.domain.port.CustomerSapOutboundPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Baja de un cliente (sdd/customer/baja-cliente.md; OVERVIEW.md §2).
 *
 * <p>Pipeline corto que entra por {@code SENDING_SAP}: no hay nada que leer del
 * legacy (el cliente ya no esta) ni que validar. Hacia SAP emite una baja real
 * ({@code DELETE}); en local aplica el <b>modelo de bloqueo</b>: la imagen pasa a
 * {@link Customer.Status#BLOCKED} en vez de eliminarse, y el historico y el
 * estado se conservan como rastro de auditoria.
 *
 * <p>Hasta la auditoria del 2026-09-10 esta baja nunca se ejecutaba: abria por
 * un estado que la maquina no admitia y, de haberlo hecho, mandaba {@code POST {}}.
 */
@Service
public class DeleteCustomerUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeleteCustomerUseCase.class);
    private static final String DOMAIN = "customer";
    private static final String ORIGIN = "cdc";

    private final CustomerImageStorePort imageStore;
    private final CustomerSapOutboundPort sapOutbound;
    private final SyncStateRepositoryPort stateRepo;
    private final SyncMetrics metrics;

    public DeleteCustomerUseCase(CustomerImageStorePort imageStore,
                                 CustomerSapOutboundPort sapOutbound,
                                 SyncStateRepositoryPort stateRepo,
                                 SyncMetrics metrics) {
        this.imageStore = imageStore;
        this.sapOutbound = sapOutbound;
        this.stateRepo = stateRepo;
        this.metrics = metrics;
    }

    public SyncState execute(String customerId, String payloadHash) {
        // R-1: la baja abre ciclo desde cualquier estado previo.
        record(customerId, payloadHash, SyncState.SENDING_SAP, true);

        var response = sapOutbound.delete(customerId, payloadHash);
        if (!response.isSuccess()) {
            // R-4: SAP rechaza → la imagen no se toca.
            record(customerId, payloadHash, SyncState.SAP_ERROR, false);
            return SyncState.SAP_ERROR;
        }

        // R-3: modelo de bloqueo. R-5: sin imagen local no hay nada que bloquear.
        imageStore.find(customerId).ifPresentOrElse(
                current -> imageStore.save(customerId, blocked(current)),
                () -> log.info("Baja de customer sin imagen local entityId={}: solo se notifica a SAP", customerId));

        record(customerId, payloadHash, SyncState.SENT_SAP, false);
        return SyncState.SENT_SAP;
    }

    private static Customer blocked(Customer c) {
        return new Customer(c.id(), c.code(), c.name(), Customer.Status.BLOCKED,
                c.address(), c.fiscal(), c.contact(), c.banking());
    }

    private void record(String entityId, String payloadHash, SyncState to, boolean opensCycle) {
        SyncStateTransition t = new SyncStateTransition(
                entityId, DOMAIN, null, to, ORIGIN, payloadHash, Instant.now());
        if (opensCycle) {
            stateRepo.beginCycle(DOMAIN, entityId, t);
        } else {
            stateRepo.transition(DOMAIN, entityId, t);
        }
        metrics.incrementState(DOMAIN, to.name());
    }
}

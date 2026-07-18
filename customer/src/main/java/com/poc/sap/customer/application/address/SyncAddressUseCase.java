package com.poc.sap.customer.application.address;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.ValidationResult;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.CustomerFeature;
import com.poc.sap.customer.domain.feature.address.AddressData;
import com.poc.sap.customer.domain.feature.address.AddressValidator;
import com.poc.sap.customer.domain.port.AddressSapPort;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Use case de la feature ADDRESS: valida y envia a SAP los datos de direccion
 * del Customer (SPEC.md §3, §5, §8). Idempotente por payloadHash.
 */
@Service
public class SyncAddressUseCase {

    private static final String DOMAIN = "customer";
    private static final String STAGE = "address";

    private final AddressSapPort sapPort;
    private final SyncStateRepositoryPort stateRepo;
    private final SyncMetrics metrics;

    public SyncAddressUseCase(AddressSapPort sapPort,
                               SyncStateRepositoryPort stateRepo,
                               SyncMetrics metrics) {
        this.sapPort = sapPort;
        this.stateRepo = stateRepo;
        this.metrics = metrics;
    }

    public SyncState execute(Customer customer, String payloadHash) {
        AddressData address = customer.address();
        String featureEntityId = featureEntityId(customer.id());

        ValidationResult v = AddressValidator.validate(address);
        transition(featureEntityId, payloadHash, SyncState.VALIDATING,
                v.valid() ? SyncState.VALID : SyncState.INVALID);
        if (!v.valid()) {
            return SyncState.INVALID;
        }

        transition(featureEntityId, payloadHash, SyncState.VALID, SyncState.SENDING_SAP);
        var response = sapPort.send(customer.id(), payloadHash, address);
        SyncState target = response.isSuccess() ? SyncState.SENT_SAP : SyncState.SAP_ERROR;
        transition(featureEntityId, payloadHash, SyncState.SENDING_SAP, target);
        return target;
    }

    /** Identificador de la feature en el state repo: customerId:ADDRESS. */
    public static String featureEntityId(String customerId) {
        return customerId + ":" + CustomerFeature.ADDRESS.name();
    }

    private void transition(String entityId, String payloadHash,
                            SyncState from, SyncState to) {
        stateRepo.transition(DOMAIN, entityId, new SyncStateTransition(
                entityId, DOMAIN, from, to, STAGE, payloadHash, Instant.now()));
        metrics.incrementState(DOMAIN, to.name());
    }
}
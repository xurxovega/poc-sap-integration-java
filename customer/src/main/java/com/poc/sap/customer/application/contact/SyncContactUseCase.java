package com.poc.sap.customer.application.contact;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.ValidationResult;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.CustomerFeature;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import com.poc.sap.customer.domain.feature.contact.ContactValidator;
import com.poc.sap.customer.domain.port.ContactSapPort;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Use case de la feature CONTACT: valida y envia a SAP los datos de contacto. */
@Service
public class SyncContactUseCase {

    private static final String DOMAIN = "customer";
    private static final String STAGE = "contact";

    private final ContactSapPort sapPort;
    private final SyncStateRepositoryPort stateRepo;
    private final SyncMetrics metrics;

    public SyncContactUseCase(ContactSapPort sapPort,
                              SyncStateRepositoryPort stateRepo,
                              SyncMetrics metrics) {
        this.sapPort = sapPort;
        this.stateRepo = stateRepo;
        this.metrics = metrics;
    }

    public SyncState execute(Customer customer, String payloadHash) {
        ContactData contact = customer.contact();
        String featureEntityId = featureEntityId(customer.id());

        ValidationResult v = ContactValidator.validate(contact);
        transition(featureEntityId, payloadHash, SyncState.VALIDATING,
                v.valid() ? SyncState.VALID : SyncState.INVALID);
        if (!v.valid()) {
            return SyncState.INVALID;
        }

        transition(featureEntityId, payloadHash, SyncState.VALID, SyncState.SENDING_SAP);
        var response = sapPort.send(customer.id(), payloadHash, contact);
        SyncState target = response.isSuccess() ? SyncState.SENT_SAP : SyncState.SAP_ERROR;
        transition(featureEntityId, payloadHash, SyncState.SENDING_SAP, target);
        return target;
    }

    public static String featureEntityId(String customerId) {
        return customerId + ":" + CustomerFeature.CONTACT.name();
    }

    private void transition(String entityId, String payloadHash,
                            SyncState from, SyncState to) {
        stateRepo.transition(DOMAIN, entityId, new SyncStateTransition(
                entityId, DOMAIN, from, to, STAGE, payloadHash, Instant.now()));
        metrics.incrementState(DOMAIN, to.name());
    }
}
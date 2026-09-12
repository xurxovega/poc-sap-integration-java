package com.poc.sap.customer.application.contact;

import com.poc.sap.common.application.FeatureSyncPipeline;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.CustomerFeature;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import com.poc.sap.customer.domain.feature.contact.ContactValidator;

/** Validacion aislada de la feature CONTACT sobre su linea de estado (sin envio a SAP). */
public class ValidateContactUseCase {

    private final FeatureSyncPipeline<ContactData> pipeline;

    public ValidateContactUseCase(SyncStateRepositoryPort stateRepo, MetricsPort metrics) {
        this.pipeline = new FeatureSyncPipeline<>("customer", CustomerFeature.CONTACT.name(),
                ContactValidator::validate, null, stateRepo, metrics);
    }

    public SyncState execute(Customer c, String payloadHash) {
        return pipeline.validate(c.id(), payloadHash, c.contact());
    }
}

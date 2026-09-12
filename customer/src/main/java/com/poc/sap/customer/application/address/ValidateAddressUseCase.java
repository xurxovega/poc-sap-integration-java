package com.poc.sap.customer.application.address;

import com.poc.sap.common.application.FeatureSyncPipeline;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.CustomerFeature;
import com.poc.sap.customer.domain.feature.address.AddressData;
import com.poc.sap.customer.domain.feature.address.AddressValidator;

/** Validacion aislada de la feature ADDRESS sobre su linea de estado (sin envio a SAP). */
public class ValidateAddressUseCase {

    private final FeatureSyncPipeline<AddressData> pipeline;

    public ValidateAddressUseCase(SyncStateRepositoryPort stateRepo, MetricsPort metrics) {
        this.pipeline = new FeatureSyncPipeline<>("customer", CustomerFeature.ADDRESS.name(),
                AddressValidator::validate, null, stateRepo, metrics);
    }

    public SyncState execute(Customer c, String payloadHash) {
        return pipeline.validate(c.id(), payloadHash, c.address());
    }
}

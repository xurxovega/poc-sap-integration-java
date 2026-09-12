package com.poc.sap.customer.application.address;

import com.poc.sap.common.application.FeatureSyncPipeline;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.customer.application.CustomerFeatureSync;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.CustomerFeature;
import com.poc.sap.customer.domain.feature.address.AddressData;
import com.poc.sap.customer.domain.feature.address.AddressValidator;
import com.poc.sap.customer.domain.port.AddressSapPort;

/**
 * Feature ADDRESS del cliente: valida y envia a SAP los direccion sobre la linea de
 * estado {@code customerId:ADDRESS} (sdd/customer/sincronizacion-direccion.md). El recorrido lo
 * implementa {@link FeatureSyncPipeline}, comun a las cuatro features (Fase 7).
 */
public class SyncAddressUseCase implements CustomerFeatureSync {

    private final FeatureSyncPipeline<AddressData> pipeline;

    public SyncAddressUseCase(AddressSapPort sapPort,
                           SyncStateRepositoryPort stateRepo,
                           MetricsPort metrics) {
        this.pipeline = new FeatureSyncPipeline<>("customer", CustomerFeature.ADDRESS.name(),
                AddressValidator::validate, sapPort, stateRepo, metrics);
    }

    @Override
    public SyncState execute(Customer customer, String payloadHash) {
        return pipeline.sync(customer.id(), payloadHash, customer.address());
    }

    /** Identificador de la feature en el state repo: customerId:ADDRESS. */
    public static String featureEntityId(String customerId) {
        return FeatureSyncPipeline.featureEntityId(customerId, CustomerFeature.ADDRESS.name());
    }
}

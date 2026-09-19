package com.poc.sap.customer.application.address;

import com.poc.sap.common.application.FeatureSyncPipeline;
import com.poc.sap.common.domain.FeatureOutcome;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.common.sap.SapUpsertSettings;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.customer.application.CustomerFeatureSync;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.CustomerFeature;
import com.poc.sap.customer.domain.feature.address.AddressData;
import com.poc.sap.customer.domain.feature.address.AddressValidator;
import com.poc.sap.customer.domain.port.AddressSapPort;

import java.time.Clock;

/**
 * Feature ADDRESS del cliente: valida y envia a SAP los direccion sobre la linea de
 * estado {@code customerId:ADDRESS} (sdd/customer/sincronizacion-direccion.md). El recorrido lo
 * implementa {@link FeatureSyncPipeline}, comun a las cuatro features (Fase 7).
 */
public class SyncAddressUseCase implements CustomerFeatureSync {

    private final FeatureSyncPipeline<AddressData> pipeline;

    /** Con los interruptores de upsert por defecto (verificacion previa activa). */
    public SyncAddressUseCase(AddressSapPort sapPort,
                           SyncStateRepositoryPort stateRepo,
                           MetricsPort metrics,
                           Clock clock) {
        this(sapPort, stateRepo, metrics, clock, SapUpsertSettings.defaults());
    }

    public SyncAddressUseCase(AddressSapPort sapPort,
                           SyncStateRepositoryPort stateRepo,
                           MetricsPort metrics,
                           Clock clock,
                           SapUpsertSettings upsert) {
        this.pipeline = new FeatureSyncPipeline<>("customer", CustomerFeature.ADDRESS.name(),
                AddressValidator::validate, sapPort, stateRepo, metrics, clock, upsert);
    }

    @Override
    public FeatureOutcome execute(Customer customer, String cycleId, String payloadHash) {
        return pipeline.sync(customer.id(), cycleId, payloadHash, customer.address());
    }

    /** Identificador de la feature en el state repo: customerId:ADDRESS. */
    public static String featureEntityId(String customerId) {
        return FeatureSyncPipeline.featureEntityId(customerId, CustomerFeature.ADDRESS.name());
    }
}

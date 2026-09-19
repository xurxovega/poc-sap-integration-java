package com.poc.sap.customer.application.contact;

import com.poc.sap.common.application.FeatureSyncPipeline;
import com.poc.sap.common.domain.FeatureOutcome;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.common.sap.SapUpsertSettings;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.customer.application.CustomerFeatureSync;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.CustomerFeature;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import com.poc.sap.customer.domain.feature.contact.ContactValidator;
import com.poc.sap.customer.domain.port.ContactSapPort;

import java.time.Clock;

/**
 * Feature CONTACT del cliente: valida y envia a SAP los datos de contacto sobre la linea de
 * estado {@code customerId:CONTACT} (sdd/customer/sincronizacion-contacto.md). El recorrido lo
 * implementa {@link FeatureSyncPipeline}, comun a las cuatro features (Fase 7).
 */
public class SyncContactUseCase implements CustomerFeatureSync {

    private final FeatureSyncPipeline<ContactData> pipeline;

    /** Con los interruptores de upsert por defecto (verificacion previa activa). */
    public SyncContactUseCase(ContactSapPort sapPort,
                           SyncStateRepositoryPort stateRepo,
                           MetricsPort metrics,
                           Clock clock) {
        this(sapPort, stateRepo, metrics, clock, SapUpsertSettings.defaults());
    }

    public SyncContactUseCase(ContactSapPort sapPort,
                           SyncStateRepositoryPort stateRepo,
                           MetricsPort metrics,
                           Clock clock,
                           SapUpsertSettings upsert) {
        this.pipeline = new FeatureSyncPipeline<>("customer", CustomerFeature.CONTACT.name(),
                ContactValidator::validate, sapPort, stateRepo, metrics, clock, upsert);
    }

    @Override
    public FeatureOutcome execute(Customer customer, String cycleId, String payloadHash) {
        return pipeline.sync(customer.id(), cycleId, payloadHash, customer.contact());
    }

    /** Identificador de la feature en el state repo: customerId:CONTACT. */
    public static String featureEntityId(String customerId) {
        return FeatureSyncPipeline.featureEntityId(customerId, CustomerFeature.CONTACT.name());
    }
}

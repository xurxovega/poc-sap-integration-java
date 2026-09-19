package com.poc.sap.customer.application.fiscal;

import com.poc.sap.common.application.FeatureSyncPipeline;
import com.poc.sap.common.domain.FeatureOutcome;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.common.sap.SapUpsertSettings;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.customer.application.CustomerFeatureSync;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.CustomerFeature;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;
import com.poc.sap.customer.domain.feature.fiscal.FiscalValidator;
import com.poc.sap.customer.domain.port.FiscalSapPort;

import java.time.Clock;

/**
 * Feature FISCAL del cliente: valida y envia a SAP los datos fiscales sobre la linea de
 * estado {@code customerId:FISCAL} (sdd/customer/sincronizacion-datos-fiscales.md). El recorrido lo
 * implementa {@link FeatureSyncPipeline}, comun a las cuatro features (Fase 7).
 */
public class SyncFiscalUseCase implements CustomerFeatureSync {

    private final FeatureSyncPipeline<FiscalData> pipeline;

    /** Con los interruptores de upsert por defecto (verificacion previa activa). */
    public SyncFiscalUseCase(FiscalSapPort sapPort,
                           SyncStateRepositoryPort stateRepo,
                           MetricsPort metrics,
                           Clock clock) {
        this(sapPort, stateRepo, metrics, clock, SapUpsertSettings.defaults());
    }

    public SyncFiscalUseCase(FiscalSapPort sapPort,
                           SyncStateRepositoryPort stateRepo,
                           MetricsPort metrics,
                           Clock clock,
                           SapUpsertSettings upsert) {
        this.pipeline = new FeatureSyncPipeline<>("customer", CustomerFeature.FISCAL.name(),
                FiscalValidator::validate, sapPort, stateRepo, metrics, clock, upsert);
    }

    @Override
    public FeatureOutcome execute(Customer customer, String cycleId, String payloadHash) {
        return pipeline.sync(customer.id(), cycleId, payloadHash, customer.fiscal());
    }

    /** Identificador de la feature en el state repo: customerId:FISCAL. */
    public static String featureEntityId(String customerId) {
        return FeatureSyncPipeline.featureEntityId(customerId, CustomerFeature.FISCAL.name());
    }
}

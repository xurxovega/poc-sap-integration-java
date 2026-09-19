package com.poc.sap.customer.application.banking;

import com.poc.sap.common.application.FeatureSyncPipeline;
import com.poc.sap.common.domain.FeatureOutcome;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.common.sap.SapUpsertSettings;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.customer.application.CustomerFeatureSync;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.CustomerFeature;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import com.poc.sap.customer.domain.feature.banking.BankingValidator;
import com.poc.sap.customer.domain.port.BankingSapPort;

import java.time.Clock;

/**
 * Feature BANKING del cliente: valida y envia a SAP los datos bancarios sobre la linea de
 * estado {@code customerId:BANKING} (sdd/customer/sincronizacion-datos-bancarios.md). El recorrido lo
 * implementa {@link FeatureSyncPipeline}, comun a las cuatro features (Fase 7).
 */
public class SyncBankingUseCase implements CustomerFeatureSync {

    private final FeatureSyncPipeline<BankingData> pipeline;

    /** Con los interruptores de upsert por defecto (verificacion previa activa). */
    public SyncBankingUseCase(BankingSapPort sapPort,
                           SyncStateRepositoryPort stateRepo,
                           MetricsPort metrics,
                           Clock clock) {
        this(sapPort, stateRepo, metrics, clock, SapUpsertSettings.defaults());
    }

    public SyncBankingUseCase(BankingSapPort sapPort,
                           SyncStateRepositoryPort stateRepo,
                           MetricsPort metrics,
                           Clock clock,
                           SapUpsertSettings upsert) {
        this.pipeline = new FeatureSyncPipeline<>("customer", CustomerFeature.BANKING.name(),
                BankingValidator::validate, sapPort, stateRepo, metrics, clock, upsert);
    }

    @Override
    public FeatureOutcome execute(Customer customer, String cycleId, String payloadHash) {
        return pipeline.sync(customer.id(), cycleId, payloadHash, customer.banking());
    }

    /** Identificador de la feature en el state repo: customerId:BANKING. */
    public static String featureEntityId(String customerId) {
        return FeatureSyncPipeline.featureEntityId(customerId, CustomerFeature.BANKING.name());
    }
}

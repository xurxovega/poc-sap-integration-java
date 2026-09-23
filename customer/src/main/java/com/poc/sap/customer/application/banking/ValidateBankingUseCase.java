package com.poc.sap.customer.application.banking;

import com.poc.sap.common.application.FeatureSyncPipeline;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.CustomerFeature;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import com.poc.sap.customer.domain.feature.banking.BankingValidator;

import java.time.Clock;

/** Validacion aislada de la feature BANKING sobre su linea de estado (sin envio a SAP). */
public class ValidateBankingUseCase {

    private final FeatureSyncPipeline<BankingData> pipeline;

    public ValidateBankingUseCase(SyncStateRepositoryPort stateRepo, MetricsPort metrics, Clock clock) {
        this.pipeline = new FeatureSyncPipeline<>("customer", CustomerFeature.BANKING.name(),
                BankingValidator::validate, null, stateRepo, metrics, clock);
    }

    public SyncState execute(Customer c, String payloadHash) {
        return pipeline.validate(c.id(), payloadHash, c.banking());
    }
}

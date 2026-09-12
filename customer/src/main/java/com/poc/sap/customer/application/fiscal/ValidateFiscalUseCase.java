package com.poc.sap.customer.application.fiscal;

import com.poc.sap.common.application.FeatureSyncPipeline;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.CustomerFeature;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;
import com.poc.sap.customer.domain.feature.fiscal.FiscalValidator;

/** Validacion aislada de la feature FISCAL sobre su linea de estado (sin envio a SAP). */
public class ValidateFiscalUseCase {

    private final FeatureSyncPipeline<FiscalData> pipeline;

    public ValidateFiscalUseCase(SyncStateRepositoryPort stateRepo, MetricsPort metrics) {
        this.pipeline = new FeatureSyncPipeline<>("customer", CustomerFeature.FISCAL.name(),
                FiscalValidator::validate, null, stateRepo, metrics);
    }

    public SyncState execute(Customer c, String payloadHash) {
        return pipeline.validate(c.id(), payloadHash, c.fiscal());
    }
}

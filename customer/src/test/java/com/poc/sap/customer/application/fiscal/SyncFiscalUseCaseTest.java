package com.poc.sap.customer.application.fiscal;

import java.time.Clock;
import com.poc.sap.common.domain.FeatureOutcome;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.port.SapOutboundPort.SapLookup;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.customer.application.CustomerFixtures;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;
import com.poc.sap.customer.domain.port.FiscalSapPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncFiscalUseCaseTest {

    @Mock FiscalSapPort sapPort;
    @Mock SyncStateRepositoryPort stateRepo;
    @Mock MetricsPort metrics;

    private SyncFiscalUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SyncFiscalUseCase(sapPort, stateRepo, metrics, Clock.systemUTC());
        // El puerto mockeado no sabe verificar: estos tests cubren el ALTA. La
        // verificacion previa (upsert-idempotente-sap.md) tiene sus propios tests en
        // FeatureSyncPipelineTest y en los adaptadores OData.
        lenient().when(sapPort.lookup(any(), any())).thenReturn(SapLookup.notSupported());
    }

    @Test
    void happyPath() {
        Customer c = CustomerFixtures.validCustomer();
        when(sapPort.send(eq("C-1"), anyString(), any(FiscalData.class)))
                .thenReturn(new SapResponse(201, "", null));

        FeatureOutcome result = useCase.execute(c, "cyc-1", "hash-f");

        assertThat(result.state()).isEqualTo(SyncState.SENT_SAP);
    }

    @Test
    void invalidFiscalReturnsInvalid() {
        Customer invalid = new Customer(
                "C-1", "CUST-001", "Acme", Customer.Status.ACTIVE,
                CustomerFixtures.validCustomer().address(),
                new FiscalData("", null, "", ""),
                CustomerFixtures.validCustomer().contact(),
                CustomerFixtures.validCustomer().banking());

        FeatureOutcome result = useCase.execute(invalid, "cyc-1", "hash-f");

        assertThat(result.state()).isEqualTo(SyncState.INVALID);
        verify(sapPort, never()).send(any(), any(), any());
    }

    @Test
    void sapErrorReturnsSapError() {
        Customer c = CustomerFixtures.validCustomer();
        when(sapPort.send(any(), any(), any()))
                .thenReturn(new SapResponse(502, "bad gateway", null));

        FeatureOutcome result = useCase.execute(c, "cyc-1", "hash-f");

        assertThat(result.state()).isEqualTo(SyncState.SAP_ERROR);
    }

    @Test
    void featureEntityIdContainsFeatureSuffix() {
        assertThat(SyncFiscalUseCase.featureEntityId("X-1")).isEqualTo("X-1:FISCAL");
    }
}
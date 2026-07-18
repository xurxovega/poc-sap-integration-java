package com.poc.sap.customer.application.fiscal;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.observability.SyncMetrics;
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
    @Mock SyncMetrics metrics;

    private SyncFiscalUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SyncFiscalUseCase(sapPort, stateRepo, metrics);
    }

    @Test
    void happyPath() {
        Customer c = CustomerFixtures.validCustomer();
        when(sapPort.send(eq("C-1"), anyString(), any(FiscalData.class)))
                .thenReturn(new SapResponse(201, "", null));

        SyncState result = useCase.execute(c, "hash-f");

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
    }

    @Test
    void invalidFiscalReturnsInvalid() {
        Customer invalid = new Customer(
                "C-1", "CUST-001", "Acme", Customer.Status.ACTIVE,
                CustomerFixtures.validCustomer().address(),
                new FiscalData("", null, "", ""),
                CustomerFixtures.validCustomer().contact(),
                CustomerFixtures.validCustomer().banking());

        SyncState result = useCase.execute(invalid, "hash-f");

        assertThat(result).isEqualTo(SyncState.INVALID);
        verify(sapPort, never()).send(any(), any(), any());
    }

    @Test
    void sapErrorReturnsSapError() {
        Customer c = CustomerFixtures.validCustomer();
        when(sapPort.send(any(), any(), any()))
                .thenReturn(new SapResponse(502, "bad gateway", null));

        SyncState result = useCase.execute(c, "hash-f");

        assertThat(result).isEqualTo(SyncState.SAP_ERROR);
    }

    @Test
    void featureEntityIdContainsFeatureSuffix() {
        assertThat(SyncFiscalUseCase.featureEntityId("X-1")).isEqualTo("X-1:FISCAL");
    }
}
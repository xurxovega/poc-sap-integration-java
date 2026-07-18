package com.poc.sap.customer.application.banking;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.customer.application.CustomerFixtures;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import com.poc.sap.customer.domain.port.BankingSapPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncBankingUseCaseTest {

    @Mock BankingSapPort sapPort;
    @Mock SyncStateRepositoryPort stateRepo;
    @Mock SyncMetrics metrics;

    private SyncBankingUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SyncBankingUseCase(sapPort, stateRepo, metrics);
    }

    @Test
    void happyPath() {
        Customer c = CustomerFixtures.validCustomer();
        when(sapPort.send(eq("C-1"), anyString(), any(BankingData.class)))
                .thenReturn(new SapResponse(201, "", null));

        SyncState result = useCase.execute(c, "hash-b");

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
    }

    @Test
    void emptyBankingReturnsInvalid() {
        Customer c = new Customer(
                "C-1", "CUST-001", "Acme", Customer.Status.ACTIVE,
                CustomerFixtures.validCustomer().address(),
                CustomerFixtures.validCustomer().fiscal(),
                CustomerFixtures.validCustomer().contact(),
                new BankingData(null, null, java.util.List.of()));

        SyncState result = useCase.execute(c, "hash-b");

        assertThat(result).isEqualTo(SyncState.INVALID);
        verify(sapPort, never()).send(any(), any(), any());
    }

    @Test
    void sapErrorReturnsSapError() {
        Customer c = CustomerFixtures.validCustomer();
        when(sapPort.send(any(), any(), any()))
                .thenReturn(new SapResponse(503, "unavailable", null));

        SyncState result = useCase.execute(c, "hash-b");

        assertThat(result).isEqualTo(SyncState.SAP_ERROR);
    }

    @Test
    void featureEntityIdContainsFeatureSuffix() {
        assertThat(SyncBankingUseCase.featureEntityId("X-1")).isEqualTo("X-1:BANKING");
    }
}
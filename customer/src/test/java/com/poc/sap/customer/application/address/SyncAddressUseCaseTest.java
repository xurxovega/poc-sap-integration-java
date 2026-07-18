package com.poc.sap.customer.application.address;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.customer.application.CustomerFixtures;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.feature.address.AddressData;
import com.poc.sap.customer.domain.port.AddressSapPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncAddressUseCaseTest {

    @Mock AddressSapPort sapPort;
    @Mock SyncStateRepositoryPort stateRepo;
    @Mock SyncMetrics metrics;

    private SyncAddressUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SyncAddressUseCase(sapPort, stateRepo, metrics);
    }

    @Test
    void happyPath() {
        Customer c = CustomerFixtures.validCustomer();
        when(sapPort.send(eq("C-1"), anyString(), any(AddressData.class)))
                .thenReturn(new SapResponse(201, "", "loc"));

        SyncState result = useCase.execute(c, "hash-a");

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        verify(sapPort).send(eq("C-1"), anyString(), any(AddressData.class));
    }

    @Test
    void invalidAddressReturnsInvalid() {
        Customer invalid = CustomerFixtures.invalidAddressCustomer();
        SyncState result = useCase.execute(invalid, "hash-a");

        assertThat(result).isEqualTo(SyncState.INVALID);
        verify(sapPort, never()).send(any(), any(), any());
    }

    @Test
    void sapErrorReturnsSapError() {
        Customer c = CustomerFixtures.validCustomer();
        when(sapPort.send(any(), any(), any()))
                .thenReturn(new SapResponse(500, "fail", null));

        SyncState result = useCase.execute(c, "hash-a");

        assertThat(result).isEqualTo(SyncState.SAP_ERROR);
    }

    @Test
    void featureEntityIdContainsFeatureSuffix() {
        String id = SyncAddressUseCase.featureEntityId("C-42");
        assertThat(id).isEqualTo("C-42:ADDRESS");
    }
}
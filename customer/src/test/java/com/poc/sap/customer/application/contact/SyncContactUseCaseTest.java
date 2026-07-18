package com.poc.sap.customer.application.contact;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.customer.application.CustomerFixtures;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.feature.contact.ContactData;
import com.poc.sap.customer.domain.port.ContactSapPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncContactUseCaseTest {

    @Mock ContactSapPort sapPort;
    @Mock SyncStateRepositoryPort stateRepo;
    @Mock SyncMetrics metrics;

    private SyncContactUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SyncContactUseCase(sapPort, stateRepo, metrics);
    }

    @Test
    void happyPath() {
        Customer c = CustomerFixtures.validCustomer();
        when(sapPort.send(eq("C-1"), anyString(), any(ContactData.class)))
                .thenReturn(new SapResponse(200, "", null));

        SyncState result = useCase.execute(c, "hash-c");

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
    }

    @Test
    void noChannelReturnsInvalid() {
        Customer c = new Customer(
                "C-1", "CUST-001", "Acme", Customer.Status.ACTIVE,
                CustomerFixtures.validCustomer().address(),
                CustomerFixtures.validCustomer().fiscal(),
                new ContactData(null, null, null, null),
                CustomerFixtures.validCustomer().banking());

        SyncState result = useCase.execute(c, "hash-c");

        assertThat(result).isEqualTo(SyncState.INVALID);
        verify(sapPort, never()).send(any(), any(), any());
    }

    @Test
    void sapErrorReturnsSapError() {
        Customer c = CustomerFixtures.validCustomer();
        when(sapPort.send(any(), any(), any()))
                .thenReturn(new SapResponse(500, "err", null));

        SyncState result = useCase.execute(c, "hash-c");

        assertThat(result).isEqualTo(SyncState.SAP_ERROR);
    }

    @Test
    void featureEntityIdContainsFeatureSuffix() {
        assertThat(SyncContactUseCase.featureEntityId("X-1")).isEqualTo("X-1:CONTACT");
    }
}
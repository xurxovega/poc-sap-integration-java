package com.poc.sap.customer.application.contact;

import java.time.Clock;
import com.poc.sap.common.domain.FeatureOutcome;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.port.SapOutboundPort.SapLookup;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.domain.port.MetricsPort;
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
    @Mock MetricsPort metrics;

    private SyncContactUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new SyncContactUseCase(sapPort, stateRepo, metrics, Clock.systemUTC());
        // El puerto mockeado no sabe verificar: estos tests cubren el ALTA. La
        // verificacion previa (upsert-idempotente-sap.md) tiene sus propios tests en
        // FeatureSyncPipelineTest y en los adaptadores OData.
        lenient().when(sapPort.lookup(any(), any())).thenReturn(SapLookup.notSupported());
    }

    @Test
    void happyPath() {
        Customer c = CustomerFixtures.validCustomer();
        when(sapPort.send(eq("C-1"), anyString(), any(ContactData.class)))
                .thenReturn(new SapResponse(200, "", null));

        FeatureOutcome result = useCase.execute(c, "cyc-1", "hash-c");

        assertThat(result.state()).isEqualTo(SyncState.SENT_SAP);
    }

    @Test
    void noChannelReturnsInvalid() {
        Customer c = new Customer(
                "C-1", "CUST-001", "Acme", Customer.Status.ACTIVE,
                CustomerFixtures.validCustomer().address(),
                CustomerFixtures.validCustomer().fiscal(),
                new ContactData(null, null, null, null),
                CustomerFixtures.validCustomer().banking());

        FeatureOutcome result = useCase.execute(c, "cyc-1", "hash-c");

        assertThat(result.state()).isEqualTo(SyncState.INVALID);
        verify(sapPort, never()).send(any(), any(), any());
    }

    @Test
    void sapErrorReturnsSapError() {
        Customer c = CustomerFixtures.validCustomer();
        when(sapPort.send(any(), any(), any()))
                .thenReturn(new SapResponse(500, "err", null));

        FeatureOutcome result = useCase.execute(c, "cyc-1", "hash-c");

        assertThat(result.state()).isEqualTo(SyncState.SAP_ERROR);
    }

    @Test
    void featureEntityIdContainsFeatureSuffix() {
        assertThat(SyncContactUseCase.featureEntityId("X-1")).isEqualTo("X-1:CONTACT");
    }
}
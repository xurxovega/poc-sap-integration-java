package com.poc.sap.customer.application.general;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.customer.domain.port.CustomerImageStorePort;
import com.poc.sap.customer.domain.port.CustomerSapOutboundPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeleteCustomerUseCaseTest {

    @Mock CustomerImageStorePort imageStore;
    @Mock CustomerSapOutboundPort sapOutbound;
    @Mock SyncStateRepositoryPort stateRepo;
    @Mock SyncMetrics metrics;

    private DeleteCustomerUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new DeleteCustomerUseCase(imageStore, sapOutbound, stateRepo, metrics);
    }

    @Test
    void deletesWhenSapSucceeds() {
        when(sapOutbound.send(eq("C-1"), anyString(), isNull()))
                .thenReturn(new SapResponse(204, "", null));

        SyncState result = useCase.execute("C-1", "hash-del");

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        verify(imageStore).delete("C-1");
    }

    @Test
    void keepsImageWhenSapFails() {
        when(sapOutbound.send(eq("C-1"), anyString(), isNull()))
                .thenReturn(new SapResponse(500, "boom", null));

        SyncState result = useCase.execute("C-1", "hash-del");

        assertThat(result).isEqualTo(SyncState.SAP_ERROR);
        verify(imageStore, never()).delete(anyString());
    }
}
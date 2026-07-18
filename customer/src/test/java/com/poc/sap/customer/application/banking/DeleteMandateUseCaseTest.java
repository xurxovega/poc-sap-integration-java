package com.poc.sap.customer.application.banking;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.customer.domain.port.BankingSapPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeleteMandateUseCaseTest {

    @Mock BankingSapPort sapPort;
    @Mock SyncStateRepositoryPort stateRepo;
    @Mock SyncMetrics metrics;

    private DeleteMandateUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new DeleteMandateUseCase(sapPort, stateRepo, metrics);
    }

    @Test
    void happyPath() {
        when(sapPort.send(eq("M-1"), anyString(), any()))
                .thenReturn(new SapResponse(204, "", null));

        SyncState result = useCase.execute("M-1", "C-1", "hash-dm");

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        verify(sapPort).send(eq("M-1"), eq("hash-dm"), any());
    }

    @Test
    void sapErrorReturnsSapError() {
        when(sapPort.send(any(), any(), any()))
                .thenReturn(new SapResponse(500, "err", null));

        SyncState result = useCase.execute("M-1", "C-1", "hash-dm");

        assertThat(result).isEqualTo(SyncState.SAP_ERROR);
    }
}
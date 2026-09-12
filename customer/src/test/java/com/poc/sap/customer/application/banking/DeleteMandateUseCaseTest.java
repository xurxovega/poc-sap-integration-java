package com.poc.sap.customer.application.banking;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.customer.domain.port.MandateSapOutboundPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Spec sdd/customer/baja-mandato-sepa.md AC-1..AC-3. */
@ExtendWith(MockitoExtension.class)
class DeleteMandateUseCaseTest {

    @Mock MandateSapOutboundPort sapPort;
    @Mock SyncStateRepositoryPort stateRepo;
    @Mock MetricsPort metrics;

    private DeleteMandateUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new DeleteMandateUseCase(sapPort, stateRepo, metrics);
    }

    /** AC-1: la baja revoca el mandato (no envia un payload bancario ni borra nada). */
    @Test
    void happyPathRevokesTheMandate() {
        when(sapPort.revoke(eq("M-1"), anyString()))
                .thenReturn(new SapResponse(204, "", null));

        SyncState result = useCase.execute("M-1", "C-1", "hash-dm");

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        verify(sapPort).revoke("M-1", "hash-dm");
        verify(sapPort, never()).send(any(), any(), any());
    }

    /** AC-2: si SAP rechaza, el ciclo termina en SAP_ERROR. */
    @Test
    void sapErrorReturnsSapError() {
        when(sapPort.revoke(any(), any()))
                .thenReturn(new SapResponse(500, "err", null));

        SyncState result = useCase.execute("M-1", "C-1", "hash-dm");

        assertThat(result).isEqualTo(SyncState.SAP_ERROR);
    }
}
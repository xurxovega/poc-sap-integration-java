package com.poc.sap.customer.application.general;

import java.time.Clock;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SapOutboundPort.SapResponse;
import com.poc.sap.common.domain.port.MetricsPort;
import com.poc.sap.customer.application.CustomerFixtures;
import com.poc.sap.customer.application.InMemoryStateRepo;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.port.CustomerImageStorePort;
import com.poc.sap.customer.domain.port.CustomerSapOutboundPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test del {@link DeleteCustomerUseCase} (sdd/customer/baja-cliente.md). Usa el
 * repositorio de estado en memoria con la maquina REAL: la baja nunca se habia
 * ejecutado porque entraba por un estado que la maquina no admitia, y el mock del
 * puerto lo ocultaba (auditoria B2).
 */
@ExtendWith(MockitoExtension.class)
class DeleteCustomerUseCaseTest {

    @Mock CustomerImageStorePort imageStore;
    @Mock CustomerSapOutboundPort sapOutbound;
    @Mock MetricsPort metrics;

    private InMemoryStateRepo stateRepo;
    private DeleteCustomerUseCase useCase;

    @BeforeEach
    void setUp() {
        stateRepo = new InMemoryStateRepo();
        useCase = new DeleteCustomerUseCase(imageStore, sapOutbound, stateRepo, metrics, Clock.systemUTC());
    }

    /**
     * AC-1 (sdd/customer/baja-cliente.md): la baja abre ciclo desde cualquier
     * estado previo, incluido ninguno, y termina en SENT_SAP.
     */
    @Test
    void deleteOpensCycleFromAnyPriorState() {
        SyncState[] priors = {null, SyncState.SENT_SAP, SyncState.SAP_ERROR, SyncState.SENDING_SAP};
        for (SyncState prior : priors) {
            InMemoryStateRepo repo = new InMemoryStateRepo();
            if (prior != null) {
                repo.seed("C-1", prior);
            }
            DeleteCustomerUseCase uc = new DeleteCustomerUseCase(imageStore, sapOutbound, repo, metrics, Clock.systemUTC());
            when(sapOutbound.delete("C-1", "hash-del")).thenReturn(new SapResponse(204, "", null));

            SyncState result = uc.execute("C-1", "hash-del");

            assertThat(result).as("estado previo %s", prior).isEqualTo(SyncState.SENT_SAP);
        }
    }

    /** AC-2: hacia SAP va una baja, nunca un alta vacia. */
    @Test
    void deleteIssuesDeleteNotSend() {
        when(sapOutbound.delete("C-1", "hash-del")).thenReturn(new SapResponse(204, "", null));

        useCase.execute("C-1", "hash-del");

        verify(sapOutbound).delete("C-1", "hash-del");
        verify(sapOutbound, never()).send(anyString(), anyString(), any());
    }

    /**
     * AC-3: modelo de bloqueo. Si SAP acepta, la imagen local pasa a BLOCKED; no
     * se elimina, porque el rastro tiene que conservarse.
     */
    @Test
    void blocksImageInsteadOfDeleting() {
        when(sapOutbound.delete("C-1", "hash-del")).thenReturn(new SapResponse(204, "", null));
        when(imageStore.find("C-1")).thenReturn(Optional.of(CustomerFixtures.validCustomer()));

        SyncState result = useCase.execute("C-1", "hash-del");

        assertThat(result).isEqualTo(SyncState.SENT_SAP);
        ArgumentCaptor<Customer> saved = ArgumentCaptor.forClass(Customer.class);
        verify(imageStore).save(eq("C-1"), saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(Customer.Status.BLOCKED);
        verify(imageStore, never()).delete(anyString());
    }

    /** AC-4: si SAP rechaza, la imagen no se toca y el ciclo termina en SAP_ERROR. */
    @Test
    void keepsImageWhenSapFails() {
        when(sapOutbound.delete("C-1", "hash-del")).thenReturn(new SapResponse(500, "boom", null));

        SyncState result = useCase.execute("C-1", "hash-del");

        assertThat(result).isEqualTo(SyncState.SAP_ERROR);
        verify(imageStore, never()).save(anyString(), any());
        verify(imageStore, never()).delete(anyString());
    }

    /** R-5: una entidad que nunca se sincronizo puede darse de baja igualmente. */
    @Test
    void deleteWithoutLocalImageStillReachesSap() {
        when(sapOutbound.delete("C-1", "hash-del")).thenReturn(new SapResponse(204, "", null));
        when(imageStore.find("C-1")).thenReturn(Optional.empty());

        assertThat(useCase.execute("C-1", "hash-del")).isEqualTo(SyncState.SENT_SAP);
        verify(imageStore, never()).save(anyString(), any());
    }
}

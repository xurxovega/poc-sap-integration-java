package com.poc.sap.customer.application.address;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateMachine;
import com.poc.sap.common.domain.SyncStateTransition;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.argThat;
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

    /**
     * AC-4 (sdd/customer/sincronizacion-direccion.md): la linea de estado de la feature no
     * existe antes del primer sync, asi que el use case debe registrar la
     * entrada en VALIDATING. Sin ella la maquina evalua null -> VALID, que no es
     * un estado inicial permitido, y el sync entero revienta.
     */
    @Test
    void firstSyncEntersThroughValidating() {
        Customer c = CustomerFixtures.validCustomer();
        when(sapPort.send(any(), any(), any())).thenReturn(new SapResponse(201, "", "loc"));

        useCase.execute(c, "hash-a");

        verify(stateRepo).transition(eq("customer"), eq("C-1:ADDRESS"),
                argThat(t -> t.from() == null && t.to() == SyncState.VALIDATING));
    }

    /**
     * AC-5 (sdd/customer/sincronizacion-direccion.md): mockear el puerto de estado esconde
     * las reglas de la maquina. Este test usa un repositorio en memoria que
     * aplica la SyncStateMachine real, como hace MongoSyncStateRepository:
     * calcula el 'from' a partir del estado almacenado, no del declarado.
     */
    @Test
    void completesAgainstRealStateMachine() {
        InMemoryStateRepo repo = new InMemoryStateRepo();
        SyncAddressUseCase realUseCase = new SyncAddressUseCase(sapPort, repo, metrics);
        Customer c = CustomerFixtures.validCustomer();
        when(sapPort.send(any(), any(), any())).thenReturn(new SapResponse(201, "", "loc"));

        assertThatCode(() -> realUseCase.execute(c, "hash-a")).doesNotThrowAnyException();

        assertThat(repo.states("C-1:ADDRESS")).containsExactly(
                SyncState.VALIDATING, SyncState.VALID,
                SyncState.SENDING_SAP, SyncState.SENT_SAP);
    }

    /**
     * AC-6 (sdd/customer/sincronizacion-direccion.md): un segundo evento con
     * cambios reales debe poder re-sincronizar una direccion ya enviada. La
     * linea de feature queda en SENT_SAP tras el primer ciclo y tiene que
     * re-entrar por VALIDATING.
     */
    @Test
    void reSyncOfAnAlreadySentAddress() {
        InMemoryStateRepo repo = new InMemoryStateRepo();
        SyncAddressUseCase realUseCase = new SyncAddressUseCase(sapPort, repo, metrics);
        Customer c = CustomerFixtures.validCustomer();
        when(sapPort.send(any(), any(), any())).thenReturn(new SapResponse(201, "", "loc"));

        realUseCase.execute(c, "hash-1");
        assertThatCode(() -> realUseCase.execute(c, "hash-2")).doesNotThrowAnyException();

        assertThat(repo.states("C-1:ADDRESS")).containsExactly(
                SyncState.VALIDATING, SyncState.VALID, SyncState.SENDING_SAP, SyncState.SENT_SAP,
                SyncState.VALIDATING, SyncState.VALID, SyncState.SENDING_SAP, SyncState.SENT_SAP);
        verify(sapPort, times(2)).send(any(), any(), any());
    }

    /** Repositorio de estado en memoria con las mismas reglas que el de Mongo. */
    private static final class InMemoryStateRepo implements SyncStateRepositoryPort {
        private final SyncStateMachine machine = new SyncStateMachine();
        private final Map<String, List<SyncState>> byEntity = new HashMap<>();

        List<SyncState> states(String entityId) {
            return byEntity.getOrDefault(entityId, List.of());
        }

        @Override
        public Optional<SyncState> currentState(String domain, String entityId) {
            List<SyncState> s = byEntity.get(entityId);
            return s == null || s.isEmpty() ? Optional.empty() : Optional.of(s.get(s.size() - 1));
        }

        @Override
        public SyncState transition(String domain, String entityId, SyncStateTransition t) {
            SyncState to = machine.transition(currentState(domain, entityId).orElse(null), t.to());
            byEntity.computeIfAbsent(entityId, k -> new ArrayList<>()).add(to);
            return to;
        }

        @Override
        public List<SyncStateTransition> history(String domain, String entityId) {
            return List.of();
        }

        @Override
        public boolean alreadySent(String domain, String entityId, String payloadHash) {
            return false;
        }
    }

    @Test
    void featureEntityIdContainsFeatureSuffix() {
        String id = SyncAddressUseCase.featureEntityId("C-42");
        assertThat(id).isEqualTo("C-42:ADDRESS");
    }
}
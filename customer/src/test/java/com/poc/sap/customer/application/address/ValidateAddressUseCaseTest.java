package com.poc.sap.customer.application.address;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.customer.application.CustomerFixtures;
import com.poc.sap.customer.domain.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test unit del {@link ValidateAddressUseCase} (TECH.md §4, §8).
 * Verifica la validacion aislada de la feature ADDRESS y la persistencia
 * de las transiciones de estado.
 */
@ExtendWith(MockitoExtension.class)
class ValidateAddressUseCaseTest {

    @Mock SyncStateRepositoryPort stateRepo;
    @Mock SyncMetrics metrics;

    private ValidateAddressUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ValidateAddressUseCase(stateRepo, metrics);
    }

    @Test
    void validAddressReturnsValid() {
        Customer c = CustomerFixtures.validCustomer();

        SyncState result = useCase.execute(c, "hash-v");

        assertThat(result).isEqualTo(SyncState.VALID);
    }

    @Test
    void invalidAddressReturnsInvalid() {
        Customer c = CustomerFixtures.invalidAddressCustomer();

        SyncState result = useCase.execute(c, "hash-v");

        assertThat(result).isEqualTo(SyncState.INVALID);
    }

    @Test
    void recordsValidatingThenTargetTransition() {
        Customer c = CustomerFixtures.validCustomer();

        useCase.execute(c, "hash-v");

        verify(stateRepo, times(2)).transition(
                eq("customer"),
                eq("C-1:ADDRESS"),
                any(SyncStateTransition.class));
        verify(metrics).incrementState("customer", "VALIDATING");
        verify(metrics).incrementState("customer", "VALID");
    }
}
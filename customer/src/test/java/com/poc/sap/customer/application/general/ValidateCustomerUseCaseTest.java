package com.poc.sap.customer.application.general;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.customer.application.CustomerFixtures;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.port.CustomerLegacyRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ValidateCustomerUseCaseTest {

    @Mock CustomerLegacyRepositoryPort legacyRepo;
    @Mock SyncStateRepositoryPort stateRepo;
    @Mock SyncMetrics metrics;

    private ValidateCustomerUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ValidateCustomerUseCase(legacyRepo, stateRepo, metrics);
    }

    @Test
    void validReturnsValid() {
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(CustomerFixtures.validCustomer()));

        SyncState result = useCase.execute("C-1", "hash-v");

        assertThat(result).isEqualTo(SyncState.VALID);
    }

    @Test
    void invalidReturnsInvalid() {
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(CustomerFixtures.invalidAddressCustomer()));

        SyncState result = useCase.execute("C-1", "hash-v");

        assertThat(result).isEqualTo(SyncState.INVALID);
    }

    @Test
    void missingReturnsError() {
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.empty());

        SyncState result = useCase.execute("C-1", "hash-v");

        assertThat(result).isEqualTo(SyncState.ERROR);
    }

    @Test
    void partialFeaturesOnlyAddress() {
        Customer valid = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(valid));

        SyncState result = useCase.execute("C-1", "hash-v", EnumSet.of(com.poc.sap.customer.domain.CustomerFeature.ADDRESS));

        assertThat(result).isEqualTo(SyncState.VALID);
    }
}
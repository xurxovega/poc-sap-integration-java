package com.poc.sap.customer.application.banking;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.customer.application.CustomerFixtures;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.feature.banking.BankingData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test unit del {@link ValidateBankingUseCase} (TECH.md §4, §8).
 */
@ExtendWith(MockitoExtension.class)
class ValidateBankingUseCaseTest {

    @Mock SyncStateRepositoryPort stateRepo;
    @Mock SyncMetrics metrics;

    private ValidateBankingUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ValidateBankingUseCase(stateRepo, metrics);
    }

    private Customer invalidBanking() {
        Customer base = CustomerFixtures.validCustomer();
        return new Customer(
                base.id(), base.code(), base.name(), base.status(),
                base.address(), base.fiscal(), base.contact(),
                new BankingData(null, null, java.util.List.of()));
    }

    @Test
    void validBankingReturnsValid() {
        Customer c = CustomerFixtures.validCustomer();

        SyncState result = useCase.execute(c, "hash-v");

        assertThat(result).isEqualTo(SyncState.VALID);
    }

    @Test
    void invalidBankingReturnsInvalid() {
        Customer c = invalidBanking();

        SyncState result = useCase.execute(c, "hash-v");

        assertThat(result).isEqualTo(SyncState.INVALID);
    }

    @Test
    void recordsValidatingThenTargetTransition() {
        Customer c = invalidBanking();

        useCase.execute(c, "hash-v");

        verify(stateRepo, times(2)).transition(
                eq("customer"),
                eq("C-1:BANKING"),
                any(SyncStateTransition.class));
        verify(metrics).incrementState("customer", "VALIDATING");
        verify(metrics).incrementState("customer", "INVALID");
    }
}
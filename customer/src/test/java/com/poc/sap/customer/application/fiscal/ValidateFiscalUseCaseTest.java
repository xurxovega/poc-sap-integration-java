package com.poc.sap.customer.application.fiscal;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.SyncStateTransition;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.customer.application.CustomerFixtures;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.feature.fiscal.FiscalData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test unit del {@link ValidateFiscalUseCase} (TECH.md §4, §8).
 */
@ExtendWith(MockitoExtension.class)
class ValidateFiscalUseCaseTest {

    @Mock SyncStateRepositoryPort stateRepo;
    @Mock SyncMetrics metrics;

    private ValidateFiscalUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ValidateFiscalUseCase(stateRepo, metrics);
    }

    private Customer invalidFiscal() {
        Customer base = CustomerFixtures.validCustomer();
        return new Customer(
                base.id(), base.code(), base.name(), base.status(),
                base.address(),
                new FiscalData("", null, "", ""),
                base.contact(), base.banking());
    }

    @Test
    void validFiscalReturnsValid() {
        Customer c = CustomerFixtures.validCustomer();

        SyncState result = useCase.execute(c, "hash-v");

        assertThat(result).isEqualTo(SyncState.VALID);
    }

    @Test
    void invalidFiscalReturnsInvalid() {
        Customer c = invalidFiscal();

        SyncState result = useCase.execute(c, "hash-v");

        assertThat(result).isEqualTo(SyncState.INVALID);
    }

    @Test
    void recordsValidatingThenTargetTransition() {
        Customer c = invalidFiscal();

        useCase.execute(c, "hash-v");

        verify(stateRepo, times(2)).transition(
                eq("customer"),
                eq("C-1:FISCAL"),
                any(SyncStateTransition.class));
        verify(metrics).incrementState("customer", "VALIDATING");
        verify(metrics).incrementState("customer", "INVALID");
    }
}
package com.poc.sap.customer.application.general;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.SyncStateRepositoryPort;
import com.poc.sap.common.observability.SyncMetrics;
import com.poc.sap.customer.application.CustomerFixtures;
import com.poc.sap.customer.domain.Customer;
import com.poc.sap.customer.domain.port.CustomerHistoryIndexerPort;
import com.poc.sap.customer.domain.port.CustomerImageStorePort;
import com.poc.sap.customer.domain.port.CustomerLegacyRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IndexCustomerUseCaseTest {

    @Mock CustomerLegacyRepositoryPort legacyRepo;
    @Mock CustomerImageStorePort imageStore;
    @Mock CustomerHistoryIndexerPort historyIndexer;
    @Mock SyncStateRepositoryPort stateRepo;
    @Mock SyncMetrics metrics;

    private IndexCustomerUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new IndexCustomerUseCase(legacyRepo, imageStore, historyIndexer,
                stateRepo, metrics);
    }

    @Test
    void indexesValidCustomer() {
        Customer c = CustomerFixtures.validCustomer();
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.of(c));

        SyncState result = useCase.execute("C-1", "hash-002");

        assertThat(result).isEqualTo(SyncState.INDEXED);
        verify(imageStore).save("C-1", c);
        verify(historyIndexer).index("C-1", c, "hash-002");
    }

    @Test
    void returnsErrorWhenLegacyEmpty() {
        when(legacyRepo.fetch("C-1")).thenReturn(Optional.empty());

        SyncState result = useCase.execute("C-1", "hash-002");

        assertThat(result).isEqualTo(SyncState.ERROR);
        verify(imageStore, never()).save(any(), any());
    }
}
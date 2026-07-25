package com.poc.sap.customer.adapters.index;

import com.poc.sap.customer.application.CustomerFixtures;
import com.poc.sap.customer.domain.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test unit del {@link ElasticsearchCustomerIndexer} (TECH.md §7).
 */
@ExtendWith(MockitoExtension.class)
class ElasticsearchCustomerIndexerTest {

    @Mock CustomerHistoryRepository repo;
    private ElasticsearchCustomerIndexer indexer;

    @BeforeEach
    void setUp() {
        indexer = new ElasticsearchCustomerIndexer(repo);
    }

    @Test
    void indexDelegatesToRepoWithHashedId() {
        Customer c = CustomerFixtures.validCustomer();

        indexer.index("C-1", c, "hash-1");

        ArgumentCaptor<CustomerHistoryDoc> captor =
                ArgumentCaptor.forClass(CustomerHistoryDoc.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("C-1-hash-1");
        assertThat(captor.getValue().getCustomerId()).isEqualTo("C-1");
        assertThat(captor.getValue().getPayloadHash()).isEqualTo("hash-1");
        assertThat(captor.getValue().getTimestamp()).isNotNull();
    }

    @Test
    void historyKeepsTimestampDescOrderFromRepo() {
        Customer c1 = CustomerFixtures.validCustomer();
        Customer c2 = new Customer("C-2", "CUST-002", "Beta", Customer.Status.ACTIVE,
                c1.address(), c1.fiscal(), c1.contact(), c1.banking());
        CustomerHistoryDoc d2 = CustomerHistoryDoc.from(c2, "h2", Instant.parse("2026-01-02T00:00:00Z"));
        CustomerHistoryDoc d1 = CustomerHistoryDoc.from(c1, "h1", Instant.parse("2026-01-01T00:00:00Z"));
        when(repo.findByCustomerIdOrderByTimestampDesc("C-1"))
                .thenReturn(List.of(d2, d1));

        List<Customer> history = indexer.history("C-1");

        // el repo ya devuelve orden timestamp desc: el mas reciente primero
        assertThat(history).extracting(Customer::id).containsExactly("C-2", "C-1");
    }

    @Test
    void historyEmptyReturnsEmptyList() {
        when(repo.findByCustomerIdOrderByTimestampDesc("C-1")).thenReturn(List.of());

        assertThat(indexer.history("C-1")).isEmpty();
    }
}
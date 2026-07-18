package com.poc.sap.customer.adapters.persistence;

import com.poc.sap.customer.application.CustomerFixtures;
import com.poc.sap.customer.domain.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test unit del {@link MongoCustomerImageStore} (TECH.md §7). Mockea el Spring
 * Data Mongo repo y verifica el mapeo ida/vuelta con {@link CustomerDocument}.
 */
@ExtendWith(MockitoExtension.class)
class MongoCustomerImageStoreTest {

    @Mock CustomerMongoRepository mongo;
    private MongoCustomerImageStore store;

    @BeforeEach
    void setUp() {
        store = new MongoCustomerImageStore(mongo);
    }

    @Test
    void saveDelegatesToMongo() {
        Customer c = CustomerFixtures.validCustomer();
        when(mongo.save(any(CustomerDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        store.save("C-1", c);

        ArgumentCaptor<CustomerDocument> captor =
                ArgumentCaptor.forClass(CustomerDocument.class);
        verify(mongo).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("C-1");
    }

    @Test
    void findEmptyReturnsOptional() {
        when(mongo.findById("C-x")).thenReturn(Optional.empty());

        assertThat(store.find("C-x")).isEmpty();
    }

    @Test
    void findRoundtripsCustomer() {
        Customer original = CustomerFixtures.validCustomer();
        when(mongo.findById("C-1"))
                .thenReturn(Optional.of(CustomerDocument.fromDomain(original)));

        Customer c = store.find("C-1").orElseThrow();

        assertThat(c.id()).isEqualTo("C-1");
        assertThat(c.code()).isEqualTo("CUST-001");
        assertThat(c.address().street()).isEqualTo("Calle 1");
        assertThat(c.banking().iban()).isEqualTo("ES7621000418401234567890");
    }

    @Test
    void deleteDelegatesToMongo() {
        store.delete("C-1");

        verify(mongo).deleteById("C-1");
    }
}
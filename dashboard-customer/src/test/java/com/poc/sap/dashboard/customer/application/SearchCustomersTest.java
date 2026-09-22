package com.poc.sap.dashboard.customer.application;

import com.poc.sap.dashboard.customer.domain.CustomerSnapshot;
import com.poc.sap.dashboard.customer.domain.port.CustomerSearcher;
import com.poc.sap.dashboard.customer.application.SearchCustomers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Busqueda por clave fiscal (UI-001 H-2 AC-3).
 */
@ExtendWith(MockitoExtension.class)
class SearchCustomersTest {

    @Mock CustomerSearcher searcher;
    @InjectMocks SearchCustomers useCase;

    @Test
    void nullQueryReturnsEmpty() {
        assertThat(useCase.byTaxId(null)).isEmpty();
        assertThat(useCase.byTaxId("")).isEmpty();
        assertThat(useCase.byTaxId("   ")).isEmpty();
    }

    @Test
    void delegatesToSearcher() {
        CustomerSnapshot s = new CustomerSnapshot("C-1", "CUST-001", "Acme",
                CustomerSnapshot.Status.ACTIVE, null, null, null, null);
        when(searcher.findByTaxId(anyString())).thenReturn(List.of(s));

        List<CustomerSnapshot> matches = useCase.byTaxId("B12345678");

        assertThat(matches).containsExactly(s);
    }
}

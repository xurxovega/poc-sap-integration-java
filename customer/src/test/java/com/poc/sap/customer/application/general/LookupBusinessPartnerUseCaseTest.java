package com.poc.sap.customer.application.general;

import com.poc.sap.customer.domain.port.BusinessPartnerReadPort;
import com.poc.sap.customer.domain.port.BusinessPartnerReadPort.BusinessPartnerSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit del {@link LookupBusinessPartnerUseCase} (spec
 * {@code docs/sdd/customer/consulta-business-partner-sap.md} AC-1..AC-5).
 * Cubre el contrato observable del use case sin tocar el puerto real: lo que
 * importa es como traduce parametros y mapea errores del puerto al dominio.
 */
@ExtendWith(MockitoExtension.class)
class LookupBusinessPartnerUseCaseTest {

    @Mock BusinessPartnerReadPort port;

    private LookupBusinessPartnerUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new LookupBusinessPartnerUseCase(port);
    }

    // --- AC-1 / AC-2 ----------------------------------------------------------

    @Test
    void findByIdDelegatesToPort() {
        BusinessPartnerSummary expected = new BusinessPartnerSummary("C001", "ACME S.L.", "2");
        when(port.findById("C001")).thenReturn(java.util.Optional.of(expected));

        assertThat(useCase.findById("C001")).isSameAs(expected);
        verify(port).findById("C001");
        verifyNoMoreInteractions(port);
    }

    @Test
    void findByIdThrowsWhenPortReturnsEmpty() {
        when(port.findById("C404")).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> useCase.findById("C404"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("C404");
    }

    // --- AC-3 -----------------------------------------------------------------

    @Test
    void searchReturnsPortResults() {
        List<BusinessPartnerSummary> expected = List.of(
                new BusinessPartnerSummary("C001", "ACME", "2"),
                new BusinessPartnerSummary("C002", "Beta", "2"));
        when(port.searchByCategory("2", 10)).thenReturn(expected);

        assertThat(useCase.search("2", 10)).isEqualTo(expected);
    }

    @Test
    void searchReturnsEmptyListWhenPortEmpty() {
        when(port.searchByCategory("99", 5)).thenReturn(List.of());

        assertThat(useCase.search("99", 5)).isEmpty();
    }

    // --- AC-4 -----------------------------------------------------------------

    @Test
    void findCustomersAndSuppliersUseFixedCategories() {
        when(port.findCustomers(20)).thenReturn(List.of(
                new BusinessPartnerSummary("C001", "ACME", "2")));
        when(port.findSuppliers(20)).thenReturn(List.of(
                new BusinessPartnerSummary("S001", "Acero", "1")));

        assertThat(useCase.findCustomers(20)).extracting(BusinessPartnerSummary::code)
                .containsExactly("C001");
        assertThat(useCase.findSuppliers(20)).extracting(BusinessPartnerSummary::code)
                .containsExactly("S001");

        verify(port).findCustomers(20);
        verify(port).findSuppliers(20);
    }

    // --- AC-5 (validacion de top) ---------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 201, 999, Integer.MIN_VALUE})
    void topOutOfRangeFailsOnSearch(int top) {
        assertThatThrownBy(() -> useCase.search("2", top))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("top")
                .hasMessageContaining("[" + top + "]");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 201, 999})
    void topOutOfRangeFailsOnFindCustomers(int top) {
        assertThatThrownBy(() -> useCase.findCustomers(top))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("top");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 201, 999})
    void topOutOfRangeFailsOnFindSuppliers(int top) {
        assertThatThrownBy(() -> useCase.findSuppliers(top))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("top");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 100, 200})
    void topAtBoundaryIsAccepted(int top) {
        when(port.searchByCategory("2", top)).thenReturn(List.of());
        when(port.findCustomers(top)).thenReturn(List.of());
        when(port.findSuppliers(top)).thenReturn(List.of());

        useCase.search("2", top);
        useCase.findCustomers(top);
        useCase.findSuppliers(top);

        verify(port).searchByCategory("2", top);
        verify(port).findCustomers(top);
        verify(port).findSuppliers(top);
    }
}

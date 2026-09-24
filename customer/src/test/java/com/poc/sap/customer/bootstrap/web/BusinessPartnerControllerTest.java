package com.poc.sap.customer.bootstrap.web;

import com.poc.sap.common.security.AccessScope;
import com.poc.sap.customer.application.general.LookupBusinessPartnerUseCase;
import com.poc.sap.customer.domain.port.BusinessPartnerReadPort.BusinessPartnerSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test del {@code BusinessPartnerController}
 * (spec {@code docs/sdd/customer/consulta-business-partner-sap.md} AC-6..AC-10).
 * MockMvc standalone, use case mockado. El {@link AccessScope} tambien se
 * mockea para forzar el camino "veo PII" y el camino "no veo PII" sin
 * depender de la jerarquia de roles real.
 */
class BusinessPartnerControllerTest {

    private final LookupBusinessPartnerUseCase useCase = mock(LookupBusinessPartnerUseCase.class);

    private MockMvc mvcForRead() {
        return mvc(true);
    }

    private MockMvc mvcForExternalRead() {
        return mvc(false);
    }

    private MockMvc mvc(boolean canSeeSensitiveData) {
        AccessScope scope = mock(AccessScope.class);
        when(scope.canSeeSensitiveData()).thenReturn(canSeeSensitiveData);
        return MockMvcBuilders.standaloneSetup(new BusinessPartnerController(useCase, scope)).build();
    }

    @BeforeEach
    void clearMocks() {
        // No setup comun; cada test configura lo que necesita.
    }

    // --- AC-6 / AC-7 ---------------------------------------------------------

    @Test
    void findByIdReturnsFullNameForReadRole() throws Exception {
        when(useCase.findById("C001")).thenReturn(new BusinessPartnerSummary("C001", "Cliente de ejemplo S.L.", "2"));

        mvcForRead().perform(get("/business-partners/{code}", "C001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("C001"))
                .andExpect(jsonPath("$.name").value("Cliente de ejemplo S.L."))
                .andExpect(jsonPath("$.category").value("2"))
                .andExpect(jsonPath("$.masked").value(false));
    }

    @Test
    void findByIdMasksNameForExternalRead() throws Exception {
        when(useCase.findById("C001")).thenReturn(new BusinessPartnerSummary("C001", "Cliente de ejemplo S.L.", "2"));

        mvcForExternalRead().perform(get("/business-partners/{code}", "C001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("C001"))
                // El nombre queda enmascarado por PiiMasker.maskName: solo los ultimos 4 visibles.
                // "Cliente de ejemplo S.L." sin espacios = 20 chars; |asteriscos| = 16 + "S.L." (4) = 20.
                .andExpect(jsonPath("$.name").value("****************S.L."))
                .andExpect(jsonPath("$.category").value("2"))
                .andExpect(jsonPath("$.masked").value(true));
    }

    // --- AC-8 ----------------------------------------------------------------

    @Test
    void searchReturnsListAndMasksWhenExternalRead() throws Exception {
        when(useCase.search("2", 10)).thenReturn(List.of(
                new BusinessPartnerSummary("C001", "Cliente de ejemplo S.L.", "2"),
                new BusinessPartnerSummary("C002", "Otro cliente S.A.", "2")));

        mvcForExternalRead().perform(get("/business-partners")
                        .param("category", "2").param("top", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.results[0].code").value("C001"))
                .andExpect(jsonPath("$.results[0].name").value("****************S.L."))
                // "Otro cliente S.A." sin espacios = 16 chars; 12 asteriscos + "S.A." = 16.
                .andExpect(jsonPath("$.results[1].name").value("***********S.A."))
                .andExpect(jsonPath("$.masked").value(true));
    }

    @Test
    void searchReturnsFullNamesForReadRole() throws Exception {
        when(useCase.search("2", 10)).thenReturn(List.of(
                new BusinessPartnerSummary("C001", "Cliente de ejemplo S.L.", "2")));

        mvcForRead().perform(get("/business-partners")
                        .param("category", "2").param("top", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].name").value("Cliente de ejemplo S.L."))
                .andExpect(jsonPath("$.masked").value(false));
    }

    // --- AC-9 ----------------------------------------------------------------

    @Test
    void customersAndSuppliersDelegateToUseCase() throws Exception {
        when(useCase.findCustomers(5)).thenReturn(List.of(
                new BusinessPartnerSummary("C001", "Cliente", "2")));
        when(useCase.findSuppliers(5)).thenReturn(List.of(
                new BusinessPartnerSummary("S001", "Acero", "1")));

        mvcForRead().perform(get("/business-partners/customers").param("top", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].code").value("C001"));

        mvcForRead().perform(get("/business-partners/suppliers").param("top", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].code").value("S001"));

        verify(useCase).findCustomers(5);
        verify(useCase).findSuppliers(5);
    }

    // --- AC-10 ---------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 201, 999})
    void topOutOfRangeReturns400(int top) throws Exception {
        // El use case mock devuelve IllegalArgumentException al recibir el top fuera de rango.
        // El @ExceptionHandler del controller lo mapea a 400 (R-1).
        doThrow(new IllegalArgumentException("top fuera de rango [1..200]: [" + top + "]"))
                .when(useCase).search("2", top);

        mvcForRead().perform(get("/business-partners")
                        .param("category", "2").param("top", String.valueOf(top)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("top")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("[" + top + "]")));
    }

    // --- 404 -----------------------------------------------------------------

    @Test
    void findByIdReturns404WhenAbsent() throws Exception {
        when(useCase.findById("C404")).thenThrow(
                new NoSuchElementException("BP C404 no existe en SAP"));

        mvcForRead().perform(get("/business-partners/{code}", "C404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("BP C404 no existe en SAP"));
    }
}

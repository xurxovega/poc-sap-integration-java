package com.poc.sap.customer.bootstrap.web;

import com.poc.sap.common.domain.ConcurrentTransitionException;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.security.AccessScope;
import com.poc.sap.customer.application.general.UpsertBusinessPartnerUseCase;
import com.poc.sap.customer.domain.BusinessPartnerUpsertException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test del {@code BusinessPartnerWriteController}
 * (spec {@code docs/sdd/customer/upsert-business-partner-manual.md} AC-1..AC-7).
 * MockMvc standalone, use case mockado. Verifica:
 *
 * <ul>
 *   <li>PUT/PATCH delegan en el use case con los argumentos correctos</li>
 *   <li>PATCH sin campos, PUT sin name, PATCH con category -> 400</li>
 *   <li>ConcurrentTransitionException -> 409 ProblemDetail</li>
 *   <li>El estado del orquestador se devuelve tal cual en el body</li>
 * </ul>
 */
class BusinessPartnerWriteControllerTest {

    private final UpsertBusinessPartnerUseCase useCase = mock(UpsertBusinessPartnerUseCase.class);

    private MockMvc mvc() {
        AccessScope scope = mock(AccessScope.class);
        return MockMvcBuilders.standaloneSetup(new BusinessPartnerWriteController(useCase, scope)).build();
    }

    // --- AC-1/AC-2: PUT exitoso ---------------------------------------------

    @Test
    void putWithNameAndCategoryDelegatesAndReturnsState() throws Exception {
        when(useCase.put(eq("C001"), any(), any())).thenReturn(SyncState.SENT_SAP);

        mvc().perform(put("/business-partners/{id}", "C001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Foo SL\",\"category\":\"2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value("C001"))
                .andExpect(jsonPath("$.state").value("SENT_SAP"));

        verify(useCase).put(eq("C001"), any(), any());
    }

    @Test
    void putWithDefaultCategoryDelegates() throws Exception {
        when(useCase.put(eq("C001"), any(), any())).thenReturn(SyncState.SENT_SAP);

        mvc().perform(put("/business-partners/{id}", "C001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Foo SL\"}"))
                .andExpect(status().isOk());

        verify(useCase).put(eq("C001"), any(), any());
    }

    // --- AC-3: PATCH parcial ------------------------------------------------

    @Test
    void patchWithOnlyNameDelegates() throws Exception {
        when(useCase.patch(eq("C001"), any(), any())).thenReturn(SyncState.SENT_SAP);

        mvc().perform(patch("/business-partners/{id}", "C001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Foo SL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SENT_SAP"));

        verify(useCase).patch(eq("C001"), any(), any());
    }

    // --- AC-4: PATCH sin campos -> 400 --------------------------------------

    @Test
    void patchWithoutAnyFieldReturns400() throws Exception {
        doThrow(new BusinessPartnerUpsertException(
                BusinessPartnerUpsertException.Kind.MandatoryFieldMissing,
                "al menos un campo: name, category"))
                .when(useCase).patch(eq("C001"), any(), any());

        mvc().perform(patch("/business-partners/{id}", "C001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("campo")));
    }

    // --- R-1: PATCH con category -> 400 -------------------------------------

    @Test
    void patchWithCategoryReturns400() throws Exception {
        doThrow(new BusinessPartnerUpsertException(
                BusinessPartnerUpsertException.Kind.InvalidPayload,
                "category no se puede modificar por PATCH en esta version"))
                .when(useCase).patch(eq("C001"), any(), any());

        mvc().perform(patch("/business-partners/{id}", "C001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"2\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("category")));
    }

    // --- R-2: PUT sin name -> 400 -------------------------------------------

    @Test
    void putWithoutNameReturns400() throws Exception {
        doThrow(new BusinessPartnerUpsertException(
                BusinessPartnerUpsertException.Kind.InvalidPayload,
                "name obligatorio"))
                .when(useCase).put(eq("C001"), any(), any());

        mvc().perform(put("/business-partners/{id}", "C001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"2\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("name")));
    }

    // --- AC-6: ConcurrentTransitionException -> 409 --------------------------

    @Test
    void concurrentTransitionReturns409() throws Exception {
        doThrow(new ConcurrentTransitionException("customer", "C001", 1L, null))
                .when(useCase).put(eq("C001"), any(), any());

        mvc().perform(put("/business-partners/{id}", "C001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Foo SL\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Sincronizacion concurrente"))
                .andExpect(jsonPath("$.status").value(HttpStatus.CONFLICT.value()));
    }

    // --- detalles del body de respuesta -------------------------------------

    @Test
    void putPropagatesSapErrorState() throws Exception {
        when(useCase.put(eq("C001"), any(), any())).thenReturn(SyncState.SAP_ERROR);

        mvc().perform(put("/business-partners/{id}", "C001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Foo SL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SAP_ERROR"));
    }

    @Test
    void putPropagatesInvalidState() throws Exception {
        when(useCase.put(eq("C001"), any(), any())).thenReturn(SyncState.INVALID);

        mvc().perform(put("/business-partners/{id}", "C001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Foo SL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("INVALID"));
    }

    @Test
    void patchPropagatesInvalidPayloadAs400() throws Exception {
        doThrow(new BusinessPartnerUpsertException(
                BusinessPartnerUpsertException.Kind.InvalidPayload,
                "category no se puede modificar por PATCH en esta version"))
                .when(useCase).patch(eq("C001"), any(), any());

        mvc().perform(patch("/business-partners/{id}", "C001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"1\"}"))
                .andExpect(status().isBadRequest());
    }
}

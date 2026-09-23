package com.poc.sap.customer.bootstrap.web;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.security.AccessScope;
import com.poc.sap.customer.application.general.CustomerStateUseCase;
import com.poc.sap.customer.application.general.CustomerStateUseCase.CycleTrace;
import com.poc.sap.customer.application.general.CustomerStateUseCase.EntityState;
import com.poc.sap.customer.application.general.CustomerStateUseCase.LineState;
import com.poc.sap.customer.application.general.CustomerStateUseCase.Step;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test de {@code GET /customers/{id}/state} (TECH.md §6, §10). MockMvc
 * standalone porque Spring Boot 4 elimino {@code @WebMvcTest}.
 */
class CustomerStateControllerTest {

    private static final Instant AT = Instant.parse("2026-09-18T10:00:00Z");
    private static final String PII_DETAIL = "HTTP 400: IBAN ES7620770024003102575766 de juan.perez@example.com invalido";

    private final CustomerStateUseCase useCase = mock(CustomerStateUseCase.class);

    private static EntityState state() {
        return new EntityState("C-1",
                new LineState(SyncState.SAP_ERROR, "h-2", "cyc-7", null, AT),
                Map.of("BANKING", new LineState(SyncState.SAP_ERROR, "h-2", "cyc-7", PII_DETAIL, AT)),
                new CycleTrace("cyc-7", "h-2", AT, AT, List.of(
                        new Step("C-1:BANKING", SyncState.SAP_ERROR, PII_DETAIL, AT))));
    }

    private MockMvc mvc(boolean fullRead) {
        AccessScope scope = mock(AccessScope.class);
        when(scope.canSeeSensitiveData()).thenReturn(fullRead);
        when(useCase.of("C-1")).thenReturn(state());
        return MockMvcBuilders.standaloneSetup(new CustomerStateController(useCase, scope)).build();
    }

    /**
     * AC-15 (sdd/customer/sincronizacion-cliente.md): el GET expone la traza de
     * pasos del ultimo ciclo, con el motivo de cada parte.
     */
    @Test
    void stateExposesTheCycleTrace() throws Exception {
        mvc(true).perform(get("/customers/C-1/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value("C-1"))
                .andExpect(jsonPath("$.aggregate.state").value("SAP_ERROR"))
                .andExpect(jsonPath("$.aggregate.cycleId").value("cyc-7"))
                .andExpect(jsonPath("$.lastCycle.cycleId").value("cyc-7"))
                .andExpect(jsonPath("$.lastCycle.steps[0].line").value("C-1:BANKING"))
                .andExpect(jsonPath("$.lastCycle.steps[0].detail").value(PII_DETAIL))
                .andExpect(jsonPath("$.features.BANKING.detail").value(PII_DETAIL));
    }

    /**
     * AC-16 (sdd/common/seguridad-api.md R-4): el motivo puede arrastrar el cuerpo
     * de error de SAP, que en un 400 repite el valor rechazado (IBAN, NIF, email).
     * Para {@code sap-external-read} va enmascarado.
     */
    @Test
    void externalReadNeverSeesUnmaskedErrorDetail() throws Exception {
        mvc(false).perform(get("/customers/C-1/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features.BANKING.detail").value(org.hamcrest.Matchers.not(PII_DETAIL)))
                .andExpect(jsonPath("$.features.BANKING.detail")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ES7620770024003102575766"))))
                .andExpect(jsonPath("$.features.BANKING.detail")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("juan.perez@example.com"))))
                .andExpect(jsonPath("$.features.BANKING.detail")
                        .value(org.hamcrest.Matchers.containsString("400")))
                .andExpect(jsonPath("$.lastCycle.steps[0].detail")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ES7620770024003102575766"))));
    }
}

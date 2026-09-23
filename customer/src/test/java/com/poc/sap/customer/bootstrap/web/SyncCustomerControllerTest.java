package com.poc.sap.customer.bootstrap.web;

import com.poc.sap.common.domain.ConcurrentTransitionException;
import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.SyncState;
import com.poc.sap.customer.application.general.SyncCustomerUseCase;
import com.poc.sap.customer.application.general.ValidateCustomerUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test del REST controller de Customer (TECH.md §6, §10).
 * Usa MockMvc standalone (sin contexto Spring Boot) porque Spring Boot 4
* elimino {@code @WebMvcTest} en favor de la API de Spring Framework 7.
 * Los use cases van mockados con Mockito.
 */
class SyncCustomerControllerTest {

    private MockMvc mvc;
    private final SyncCustomerUseCase syncUseCase = mock(SyncCustomerUseCase.class);
    private final ValidateCustomerUseCase validateUseCase = mock(ValidateCustomerUseCase.class);

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new SyncCustomerController(syncUseCase, validateUseCase)).build();
    }

    @Test
    void syncReturnsOkWithState() throws Exception {
        when(syncUseCase.execute(any(IngestionMessage.class))).thenReturn(SyncState.SENT_SAP);

        mvc.perform(post("/customers/sync")
                        .contentType("application/json")
                        .content("""
                                {"entityId":"C-1","operation":"UPDATE","payloadHash":"h-1","payload":"{}"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value("C-1"))
                .andExpect(jsonPath("$.state").value("SENT_SAP"));
    }

    @Test
    void syncDefaultsOperationToUpdate() throws Exception {
        when(syncUseCase.execute(any(IngestionMessage.class))).thenReturn(SyncState.SENT_SAP);

        mvc.perform(post("/customers/sync")
                        .contentType("application/json")
                        .content("""
                                {"entityId":"CC-2","payloadHash":"h-1","payload":"{}"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value("CC-2"))
                .andExpect(jsonPath("$.state").value("SENT_SAP"));
    }

    /**
     * AC-9 (contrato-openapi-rest / ADR-0013): el cuerpo sin payloadHash ni
     * payload es valido; el servicio relee el legacy y calcula el hash.
     */
    @Test
    void syncAcceptsBodyWithoutPayloadHashNorPayload() throws Exception {
        when(syncUseCase.execute(any(IngestionMessage.class))).thenReturn(SyncState.SENT_SAP);

        mvc.perform(post("/customers/sync")
                        .contentType("application/json")
                        .content("""
                                {"entityId":"C-3","operation":"UPDATE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value("C-3"))
                .andExpect(jsonPath("$.state").value("SENT_SAP"));
    }

    @Test
    void validateReturnsOkWithStateValid() throws Exception {
        when(validateUseCase.execute(eq("C-1"), any())).thenReturn(SyncState.VALID);

        mvc.perform(post("/customers/validate")
                        .contentType("application/json")
                        .content("""
                                {"entityId":"C-1","payloadHash":"h-v"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value("C-1"))
                .andExpect(jsonPath("$.state").value("VALID"));
    }

    @Test
    void validateReturnsInvalidStateWhenUseCaseFails() throws Exception {
        when(validateUseCase.execute(eq("C-1"), any())).thenReturn(SyncState.INVALID);

        mvc.perform(post("/customers/validate")
                        .contentType("application/json")
                        .content("""
                                {"entityId":"C-1","payloadHash":"h-x"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("INVALID"));
    }

    /**
     * ADR-0011: el REST sincrono es el unico camino que puede concurrir con Kafka
     * sobre la misma entidad. Una colision no es un fallo del servidor: es
     * "lo esta procesando otro, reintentalo" -> 409 Conflict con ProblemDetail.
     */
    @Test
    void concurrentTransitionAnswers409NotAServerError() throws Exception {
        when(syncUseCase.execute(any(IngestionMessage.class)))
                .thenThrow(new ConcurrentTransitionException("customer", "C-9", 4L, null));

        mvc.perform(post("/customers/sync")
                        .contentType("application/json")
                        .content("""
                                {"entityId":"C-9","operation":"UPDATE","payloadHash":"h-9","payload":"{}"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").exists());
    }
}

package com.poc.sap.article.bootstrap.web;

import com.poc.sap.article.application.SyncArticleUseCase;
import com.poc.sap.common.domain.IngestionMessage;
import com.poc.sap.common.domain.SyncState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test del REST controller de Article (TECH.md §6, §10). Usa MockMvc
 * standalone (Spring Boot 4 elimino {@code @WebMvcTest}). El use case va
 * mockado con Mockito. Nombrado {@code *Test} (no {@code *IT}) para que se
 * ejecute con surefire sin configurar failsafe en el modulo article.
 */
class SyncArticleControllerTest {

    private MockMvc mvc;
    private final SyncArticleUseCase syncUseCase = mock(SyncArticleUseCase.class);

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SyncArticleController(syncUseCase)).build();
    }

    @Test
    void syncReturnsOkWithState() throws Exception {
        when(syncUseCase.execute(any(IngestionMessage.class))).thenReturn(SyncState.SENT_SAP);

        mvc.perform(post("/articles/sync")
                        .contentType("application/json")
                        .content("""
                                {"entityId":"A-1","operation":"UPDATE","payloadHash":"h-1","payload":"{}"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value("A-1"))
                .andExpect(jsonPath("$.state").value("SENT_SAP"));
    }

    @Test
    void syncDefaultsOperationToUpdate() throws Exception {
        when(syncUseCase.execute(any(IngestionMessage.class))).thenReturn(SyncState.SENT_SAP);

        mvc.perform(post("/articles/sync")
                        .contentType("application/json")
                        .content("""
                                {"entityId":"A-1","payloadHash":"h-1","payload":"{}"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value("A-1"))
                .andExpect(jsonPath("$.state").value("SENT_SAP"));
    }

    @Test
    void syncPropagatesSapErrorState() throws Exception {
        when(syncUseCase.execute(any(IngestionMessage.class))).thenReturn(SyncState.SAP_ERROR);

        mvc.perform(post("/articles/sync")
                        .contentType("application/json")
                        .content("""
                                {"entityId":"A-1","payloadHash":"h-1","payload":"{}"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SAP_ERROR"));
    }
}
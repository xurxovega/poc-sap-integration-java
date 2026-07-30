package com.poc.sap.customer.bootstrap.web;

import com.poc.sap.common.diff.JsonDiff;
import com.poc.sap.common.domain.port.HistoryIndexerPort.Snapshot;
import com.poc.sap.customer.application.CustomerFixtures;
import com.poc.sap.customer.application.general.CustomerHistoryUseCase;
import com.poc.sap.customer.domain.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test del controller de historico (MockMvc standalone, use case mockado,
 * mismo patron que {@code SyncArticleControllerTest}).
 */
class CustomerHistoryControllerTest {

    private MockMvc mvc;
    private final CustomerHistoryUseCase useCase = mock(CustomerHistoryUseCase.class);

    private final Instant t1 = Instant.parse("2026-07-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new CustomerHistoryController(useCase)).build();
    }

    @Test
    void historyReturnsVersionsWithoutSnapshotByDefault() throws Exception {
        Customer c = CustomerFixtures.validCustomer();
        when(useCase.history("C-1")).thenReturn(List.of(new Snapshot<>("h-1", t1, c)));

        mvc.perform(get("/customers/C-1/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value("C-1"))
                .andExpect(jsonPath("$.versions[0].payloadHash").value("h-1"))
                .andExpect(jsonPath("$.versions[0].snapshot").doesNotExist());
    }

    @Test
    void historyFullIncludesSnapshot() throws Exception {
        Customer c = CustomerFixtures.validCustomer();
        when(useCase.history("C-1")).thenReturn(List.of(new Snapshot<>("h-1", t1, c)));

        mvc.perform(get("/customers/C-1/history").param("full", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].snapshot.id").value(c.id()));
    }

    @Test
    void diffReturnsChanges() throws Exception {
        var diff = new CustomerHistoryUseCase.HistoryDiff("C-1",
                new CustomerHistoryUseCase.VersionRef("h-1", t1),
                new CustomerHistoryUseCase.VersionRef("h-2", t1),
                Map.of("name", new JsonDiff.Change("ACME", "ACME NUEVA")));
        when(useCase.diff("C-1", null, null)).thenReturn(diff);

        mvc.perform(get("/customers/C-1/history/diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from.payloadHash").value("h-1"))
                .andExpect(jsonPath("$.to.payloadHash").value("h-2"))
                .andExpect(jsonPath("$.changes.name.before").value("ACME"))
                .andExpect(jsonPath("$.changes.name.after").value("ACME NUEVA"));
    }

    @Test
    void diffWithoutHistoryReturns404() throws Exception {
        when(useCase.diff("C-404", null, null))
                .thenThrow(new NoSuchElementException("Sin historico para customer C-404"));

        mvc.perform(get("/customers/C-404/history/diff"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Sin historico para customer C-404"));
    }
}

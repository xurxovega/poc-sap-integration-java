package com.poc.sap.customer.bootstrap.web;

import com.poc.sap.common.domain.SyncState;
import com.poc.sap.common.domain.port.HistoryIndexerPort.Snapshot;
import com.poc.sap.customer.application.CustomerFixtures;
import com.poc.sap.customer.application.general.CustomerHistoryUseCase;
import com.poc.sap.customer.application.general.SyncCustomerUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec sdd/common/seguridad-api.md AC-11: con app.security.enabled=false (solo
 * local con el SAP simulado) TODO responde sin token, incluidos los endpoints
 * con @PreAuthorize. Visto en vivo el 14-09-2026: la cadena permitia todo pero
 * el metodo devolvia 403 porque el anonimo no tenia roles.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
        "spring.jpa.database-platform=org.hibernate.dialect.SQLServerDialect",
        "spring.sql.init.mode=never",
        "spring.data.mongodb.auto-index-creation=false",
        "spring.kafka.listener.auto-startup=false",
        "SQLSERVER_USER=test", "SQLSERVER_PASSWORD=test",
        "sap.auth.allow-stub=true",
        "app.security.enabled=false",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9/realms/test"
})
class ApiSecurityDisabledTest {

    @Autowired WebApplicationContext context;
    @MockitoBean SyncCustomerUseCase syncUseCase;
    @MockitoBean CustomerHistoryUseCase historyUseCase;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(historyUseCase.history("C-1")).thenReturn(List.of(
                new Snapshot<>("h-1", Instant.parse("2026-01-01T00:00:00Z"), CustomerFixtures.validCustomer())));
        when(syncUseCase.execute(any())).thenReturn(SyncState.SENT_SAP);
    }

    private static final String SYNC_BODY = "{\"entityId\":\"C-1\",\"operation\":\"UPDATE\",\"payloadHash\":\"h\",\"payload\":\"{}\"}";

    @Test
    void everythingIsOpenWithoutTokenWhenSecurityIsDisabled() throws Exception {
        mvc.perform(post("/customers/sync").contentType("application/json").content(SYNC_BODY))
                .andExpect(status().isOk());
        mvc.perform(get("/customers/C-1/history?full=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.masked").value(false));
        mvc.perform(get("/customers/C-1/history/diff")).andExpect(r -> org.assertj.core.api.Assertions.assertThat(r.getResponse().getStatus()).isNotIn(401, 403));
        mvc.perform(get("/actuator/metrics")).andExpect(status().isOk());
    }
}

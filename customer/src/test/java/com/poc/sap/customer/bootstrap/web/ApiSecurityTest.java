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
 * Spec sdd/common/seguridad-api.md AC-3..AC-6 sobre el contexto REAL de
 * customer-app con la cadena de seguridad activa. El JWT se inyecta con
 * spring-security-test (el decoder es perezoso y no consulta al issuer).
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
        "app.security.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9/realms/test"
})
class ApiSecurityTest {

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

    /** AC-3: sin token, 401; health sin token, 200. */
    @Test
    void anonymousIsRejectedExceptHealth() throws Exception {
        mvc.perform(get("/customers/C-1/history")).andExpect(status().isUnauthorized());
        mvc.perform(post("/customers/sync").contentType("application/json").content(SYNC_BODY)).andExpect(status().isUnauthorized());
        // sin token no es 401/403; puede ser 503 si la infra local no esta levantada (health DOWN)
        mvc.perform(get("/actuator/health"))
                .andExpect(r -> org.assertj.core.api.Assertions.assertThat(r.getResponse().getStatus()).isNotIn(401, 403));
    }

    /** AC-5: escribir exige WRITE; READ no basta. */
    @Test
    void writeRequiresWriteRole() throws Exception {
        mvc.perform(post("/customers/sync").contentType("application/json").content(SYNC_BODY)
                        .with(jwt().authorities(() -> "ROLE_SAP_READ")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/customers/sync").contentType("application/json").content(SYNC_BODY)
                        .with(jwt().authorities(() -> "ROLE_SAP_WRITE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SENT_SAP"));
    }

    /** AC-4: READ ve el snapshot completo; EXTERNAL_READ lo ve enmascarado y sin diff. */
    @Test
    void externalReadGetsMaskedSnapshotAndNoDiff() throws Exception {
        String iban = CustomerFixtures.validCustomer().banking().iban().replace(" ", "");
        mvc.perform(get("/customers/C-1/history?full=true").with(jwt().authorities(() -> "ROLE_SAP_READ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.masked").value(false))
                .andExpect(content().string(containsString(iban)));
        mvc.perform(get("/customers/C-1/history?full=true").with(jwt().authorities(() -> "ROLE_SAP_EXTERNAL_READ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.masked").value(true))
                .andExpect(content().string(not(containsString(iban))));
        mvc.perform(get("/customers/C-1/history/diff").with(jwt().authorities(() -> "ROLE_SAP_EXTERNAL_READ")))
                .andExpect(status().isForbidden());
    }

    /** AC-2/AC-6: la jerarquia hace que ADMIN pueda leer y escribir y ver actuator; WRITE no ve actuator. */
    @Test
    void adminInheritsWriteAndReadAndSeesActuator() throws Exception {
        mvc.perform(post("/customers/sync").contentType("application/json").content(SYNC_BODY)
                        .with(jwt().authorities(() -> "ROLE_SAP_ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/customers/C-1/history").with(jwt().authorities(() -> "ROLE_SAP_SUPERADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics").with(jwt().authorities(() -> "ROLE_SAP_WRITE"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").with(jwt().authorities(() -> "ROLE_SAP_ADMIN"))).andExpect(status().isOk());
    }
}

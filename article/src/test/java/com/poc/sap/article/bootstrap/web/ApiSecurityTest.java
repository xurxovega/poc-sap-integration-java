package com.poc.sap.article.bootstrap.web;

import com.poc.sap.article.application.ArticleHistoryUseCase;
import com.poc.sap.article.application.SyncArticleUseCase;
import com.poc.sap.common.domain.SyncState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec sdd/common/seguridad-api.md AC-3/AC-5 en article-app (sin PII: external-read lee todo). */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never",
        "spring.data.mongodb.auto-index-creation=false",
        "spring.kafka.listener.auto-startup=false",
        "POSTGRES_USER=test", "POSTGRES_PASSWORD=test",
        "sap.auth.allow-stub=true",
        "app.security.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9/realms/test"
})
class ApiSecurityTest {

    @Autowired WebApplicationContext context;
    @MockitoBean SyncArticleUseCase syncUseCase;
    @MockitoBean ArticleHistoryUseCase historyUseCase;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(historyUseCase.history("A-1")).thenReturn(List.of());
        when(syncUseCase.execute(any())).thenReturn(SyncState.SENT_SAP);
    }

    @Test
    void anonymousIsRejectedWriteNeedsWriteAndExternalCanReadProducts() throws Exception {
        String body = "{\"entityId\":\"A-1\",\"payloadHash\":\"h\",\"payload\":\"{}\"}";
        mvc.perform(get("/articles/A-1/history")).andExpect(status().isUnauthorized());
        mvc.perform(post("/articles/sync").contentType("application/json").content(body)
                .with(jwt().authorities(() -> "ROLE_SAP_EXTERNAL_READ"))).andExpect(status().isForbidden());
        mvc.perform(post("/articles/sync").contentType("application/json").content(body)
                .with(jwt().authorities(() -> "ROLE_SAP_WRITE"))).andExpect(status().isOk());
        mvc.perform(get("/articles/A-1/history").with(jwt().authorities(() -> "ROLE_SAP_EXTERNAL_READ"))).andExpect(status().isOk());
    }
}

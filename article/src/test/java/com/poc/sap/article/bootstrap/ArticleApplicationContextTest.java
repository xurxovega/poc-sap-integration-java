package com.poc.sap.article.bootstrap;

import com.poc.sap.common.sap.SapClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test de arranque: el contexto Spring completo de article-app debe
 * levantar con la configuracion por defecto (sin infraestructura externa).
 * Detecta beans que faltan, YAML invalido y conflictos de wiring que los
 * tests unitarios con mocks no ven.
 */
@SpringBootTest
@TestPropertySource(properties = {
        // sin BD real: Hibernate no debe abrir conexion para metadata
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.sql.init.mode=never",
        // sin Mongo real: no crear indices en el arranque
        "spring.data.mongodb.auto-index-creation=false",
        // sin broker real: los listeners no arrancan
        "spring.kafka.listener.auto-startup=false"
})
class ArticleApplicationContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsWithSapClientWired() {
        assertThat(context.getBean(SapClient.class)).isNotNull();
    }
}

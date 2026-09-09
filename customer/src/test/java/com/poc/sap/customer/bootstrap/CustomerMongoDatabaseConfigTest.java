package com.poc.sap.customer.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La imagen actual y el estado de sincronizacion deben ir a la base de datos
 * configurada para el dominio, no a la que el driver usa por defecto.
 *
 * <p>Spring Boot 4 movio las propiedades de <em>conexion</em> de
 * {@code spring.data.mongodb.*} a {@code spring.mongodb.*} (las de Spring Data,
 * como {@code auto-index-creation}, siguen donde estaban). Con la propiedad
 * antigua la app arranca sin quejarse y escribe silenciosamente en
 * {@code mongodb://localhost/test}, el default de Boot 4.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
        "spring.jpa.database-platform=org.hibernate.dialect.SQLServerDialect",
        "spring.sql.init.mode=never",
        "spring.data.mongodb.auto-index-creation=false",
        "spring.kafka.listener.auto-startup=false"
})
class CustomerMongoDatabaseConfigTest {

    @Autowired
    private MongoDatabaseFactory mongoDatabaseFactory;

    @Test
    void usesTheDomainDatabaseAndNotTheDriverDefault() {
        assertThat(mongoDatabaseFactory.getMongoDatabase().getName())
                .as("base de datos Mongo resuelta desde la configuracion de la app")
                .isEqualTo("customer");
    }
}

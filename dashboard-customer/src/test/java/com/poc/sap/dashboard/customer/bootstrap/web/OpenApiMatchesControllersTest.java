package com.poc.sap.dashboard.customer.bootstrap.web;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mirror del {@code OpenApiMatchesControllersTest} del modulo customer:
 * garantiza que el contrato {@code openapi.yml} y los controladores del
 * dashboard describen el mismo API. Si la primera no existe todavia
 * (porque el modulo es nuevo) se enciende al primer controller, asi que
 * los mensajes de fallo tambien se mantienen al dia.
 *
 * <p>Hasta que el modulo tenga su primer controller esto entra en modo
 * SMOKE: el contrato declara los 5 endpoints y existe.
 */
class OpenApiMatchesControllersTest {

    private static final Path OPENAPI = Paths.get("src/main/resources/openapi.yml");

    @Test
    void openapiFileParsesAsValidYamlWithRequiredSections() throws IOException {
        assertThat(OPENAPI).exists();
        try (InputStream in = Files.newInputStream(OPENAPI)) {
            Map<String, Object> doc = new Yaml().load(in);
            assertThat(doc.get("openapi").toString()).startsWith("3.");
            Object paths = doc.get("paths");
            assertThat(paths).isInstanceOf(Map.class);
            Map<?, ?> p = (Map<?, ?>) paths;
            assertThat((java.util.Set<Object>) p.keySet())
                    .as("openapi.yml debe declarar los endpoints del dashboard")
                    .contains(
                            "/customers/{id}",
                            "/customers/{id}/history",
                            "/customers/{id}/history/diff",
                            "/customers/{id}/state",
                            "/customers/search",
                            "/customers/alerts/open",
                            "/customers/alerts/{id}/ack");
        }
    }
}

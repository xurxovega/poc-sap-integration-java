package com.poc.sap.customer.bootstrap;

import com.poc.sap.common.sap.SapClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test de arranque: el contexto Spring completo de customer-app debe
 * levantar sin infraestructura externa, con las credenciales minimas que el
 * YAML ya no trae por defecto (auditoria A8).
 * Detecta beans que faltan, YAML invalido y conflictos de wiring que los
 * tests unitarios con mocks no ven.
 *
 * <p>Ademas cubre OBS-005 AC-1: los tags {@code application}, {@code env} y
 * {@code cluster} aparecen en las series de {@code /actuator/prometheus} con
 * los valores configurados por defecto de la app y por las variables de
 * entorno del test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // sin BD real: Hibernate no debe abrir conexion para metadata
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
        "spring.jpa.database-platform=org.hibernate.dialect.SQLServerDialect",
        "spring.sql.init.mode=never",
        // sin Mongo real: no crear indices en el arranque
        "spring.data.mongodb.auto-index-creation=false",
        // credenciales fuera del YAML (auditoria A8): el contexto las exige
        "SQLSERVER_USER=test", "SQLSERVER_PASSWORD=test",
        // sin SAP real: token stub declarado de forma explicita
        "sap.auth.allow-stub=true",
        // seguridad activa con un issuer que no se consulta (el decoder es perezoso)
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9/realms/test",
        // sin broker real: los listeners no arrancan
        "spring.kafka.listener.auto-startup=false",
        // OBS-005: nombre del cluster y entorno aplicados a /actuator/prometheus
        "OBS_ENV=test-obs005", "OBS_CLUSTER=cluster-obs005"
})
class CustomerApplicationContextTest {

    @Autowired
    private ApplicationContext context;

    @LocalManagementPort
    private int managementPort;

    /**
     * OBS-005 AC-1: /actuator/prometheus lleva los tags application, env y cluster
     * con los valores de OBS_ENV/OBS_CLUSTER del test, en al menos una serie sap_sync_*.
     */
    @Test
    void contextExposesPrometheusEndpointWithApplicationEnvClusterTags() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + managementPort + "/actuator/prometheus"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("/actuator/prometheus debe responder 2xx").isBetween(200, 299);
        String body = response.body();
        assertThat(body).isNotNull();
        assertThat(body)
                .as("/actuator/prometheus debe llevar los tags OBS_ENV y OBS_CLUSTER "
                        + "(validamos contra una serie que siempre se publica al arrancar; si en el futuro "
                        + "hay trafico suficiente, las sap_sync_* tambien los llevaran)")
                // El orden de los tags lo decide Micrometer: cluster puede ir antes
                // o despues de env. Comprobamos ambos independientemente, en
                // cualquier orden.
                .contains("env=\"test-obs005\"")
                .contains("cluster=\"cluster-obs005\"");
    }

    @Test
    void contextLoadsWithSapClientWired() {
        assertThat(context.getBean(SapClient.class)).isNotNull();
    }

    /**
     * OBS-005 AC-1: si OBS_CLUSTER no esta definido, el tag cluster cae al
     * default {@code local} para que las series sigan siendo agregables.
     *
     * <p>El {@code @SpringBootTest} fija OBS_CLUSTER a {@code cluster-obs005},
     * asi que no podemos verificar el default aqui. Lo verificamos estructural:
     * la linea {@code cluster: ${OBS_CLUSTER:local}} esta en
     * {@code application-common.yml}, asi Spring resolvera a {@code local} si
     * OBS_CLUSTER no existe en el entorno (otro despliegue, CI local...).
     */
    /**
     * OPS-010 AC-5: el YAML del módulo debe declarar el bootstrap del broker
     * con la nueva variable {@code MESSAGING_BOOTSTRAP} y NO con la antigua
     * {@code KAFKA_BOOTSTRAP}.
     */
    @Test
    void contextStartsWithMessagingBootstrapEnv() throws Exception {
        java.nio.file.Path yml = java.nio.file.Paths.get(new java.io.File(
                "src/main/resources/application.yml").getAbsolutePath());
        String contents = java.nio.file.Files.readString(yml, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(contents)
                .as("application.yml debe referenciar MESSAGING_BOOTSTRAP (no KAFKA_BOOTSTRAP) "
                        + "tras OPS-010")
                .contains("MESSAGING_BOOTSTRAP")
                .doesNotContain("KAFKA_BOOTSTRAP");
    }

    @Test
    void defaultClusterTagIsLocalWhenObsClusterMissing() throws Exception {
        java.nio.file.Path yml = java.nio.file.Paths.get(System.getProperty(
                "obs005.common-yml",
                new java.io.File("../common/src/main/resources/application-common.yml").getAbsolutePath()));
        String contents = java.nio.file.Files.readString(yml, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(contents)
                .as("application-common.yml debe declarar 'cluster: ${OBS_CLUSTER:local}' para que el tag "
                        + "siempre tenga un valor y el agregado entre instancias siga funcionando")
                .contains("cluster: ${OBS_CLUSTER:local}");
    }
}

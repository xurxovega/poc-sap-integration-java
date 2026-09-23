package com.poc.sap.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica que el renombrado de la variable de entorno del broker y la sustitución
 * de los servicios Kafka por Redpanda se han propagado a todos los ficheros de
 * configuración del repo (compose, scripts, manifiestos K8s, YAML de apps).
 *
 * <p>Cobertura:
 * <ul>
 *   <li>{@code KAFKA_BOOTSTRAP} → {@code MESSAGING_BOOTSTRAP}</li>
 *   <li>Servicios {@code kafka-broker} / {@code zookeeper} / {@code kafka-init-topics}
 *       → {@code redpanda} / {@code redpanda-init-topics}</li>
 * </ul>
 *
 * <p>Alcance: solo ficheros donde aparecería un <em>servicio</em>, una
 * <em>variable de entorno</em> o un <em>nombre de host</em> operativo:
 * compose, scripts, YAML de apps, JSON de conectores, ConfigMaps de K8s y
 * manifiestos Kustomize. Excluye docs (markdown), auditorías históricas y el
 * propio test (para que las menciones descriptivas no disparen falsos
 * positivos).
 *
 * <p>Vinculado a OPS-010 AC-5 y a la regla del proyecto de que el ancla
 * spec↔código es bidireccional.
 */
class TopologyTest {

    private static final Path REPO_ROOT = locateRepoRoot();

    @Test
    @DisplayName("No quedan restos de KAFKA_BOOTSTRAP en ficheros operativos")
    void renombradoDelBrokerCompletado() throws IOException {
        List<String> offenders = scan("KAFKA_BOOTSTRAP");
        assertThat(offenders)
                .as("Tras el renombrado de KAFKA_BOOTSTRAP → MESSAGING_BOOTSTRAP no debe "
                        + "quedar ninguna referencia operativa al nombre antiguo. Restos:\n%s",
                        String.join("\n", offenders))
                .isEmpty();
    }

    @Test
    @DisplayName("No quedan restos del servicio kafka-broker en manifiestos/compose")
    void serviciosKafkaSustituidosPorRedpanda() throws IOException {
        List<String> offenders = scan("kafka-broker");
        assertThat(offenders)
                .as("Tras la sustitución de los servicios Kafka por Redpanda no debe "
                        + "quedar ninguna referencia operativa a 'kafka-broker'. Restos:\n%s",
                        String.join("\n", offenders))
                .isEmpty();
    }

    @Test
    @DisplayName("No quedan restos de zookeeper (Redpanda no usa ZooKeeper)")
    void zookeeperEliminado() throws IOException {
        List<String> offenders = scan("zookeeper");
        assertThat(offenders)
                .as("Redpanda no usa ZooKeeper; tras la migración no debe quedar el "
                        + "servicio. Restos:\n%s",
                        String.join("\n", offenders))
                .isEmpty();
    }

    private static Path locateRepoRoot() {
        // it/ es el módulo de tests; el repo está tres niveles arriba.
        Path cwd = Path.of("").toAbsolutePath();
        Path candidate = cwd;
        for (int i = 0; i < 4 && candidate != null; i++) {
            if (Files.exists(candidate.resolve("pom.xml")) && Files.exists(candidate.resolve("external-services"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return cwd.toAbsolutePath();
    }

    /**
     * Recorre los ficheros operativos del repo y devuelve las líneas que
     * contienen el patrón. Excluye documentación, histórico, target y .git.
     */
    private static List<String> scan(String pattern) throws IOException {
        List<String> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(REPO_ROOT)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(TopologyTest::isScannable)
                    .toList();
            for (Path file : files) {
                String rel = REPO_ROOT.relativize(file).toString();
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.contains(pattern)) {
                        matches.add(rel + ":" + (i + 1) + ": " + line.trim());
                    }
                }
            }
        }
        return matches;
    }

    private static boolean isScannable(Path path) {
        String name = path.getFileName().toString();
        String rel = REPO_ROOT.relativize(path).toString();
        if (rel.contains("target/") || rel.contains(".git/") || rel.contains("node_modules/")) {
            return false;
        }
        // Excluir documentacion y auditorias: las menciones historicas no son
        // restos operativos.
        if (rel.startsWith("docs/") || rel.contains("/docs/")) {
            return false;
        }
        // Excluir el propio test para evitar falsos positivos por sus literales.
        if (rel.endsWith("TopologyTest.java")) {
            return false;
        }
        // Excluir tests de contexto cuyo literal cita el nombre antiguo en el
        // Javadoc/mensaje de error: la asercion NEGATIVA necesita el nombre.
        if (rel.endsWith("CustomerApplicationContextTest.java")
                || rel.endsWith("ArticleApplicationContextTest.java")) {
            return false;
        }
        // Solo ficheros donde apareceria un servicio, env var o host operativo.
        return name.endsWith(".yml") || name.endsWith(".yaml")
                || name.endsWith(".env") || name.endsWith(".example")
                || name.endsWith(".sh") || name.endsWith(".json")
                || name.endsWith(".java") || name.endsWith(".properties");
    }
}

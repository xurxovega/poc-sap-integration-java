package com.poc.sap.common.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests estructurales que vigilan que los artefactos de observabilidad
 * versionados en {@code deploy/observability/} y las variables de entorno de
 * {@code scripts/env/} se mantienen coherentes con lo que la app publica
 * (OBS-005 AC-2, AC-3 y AC-6; spec {@code docs/sdd/common/observabilidad.md}).
 *
 * <p>Son tests filesystem-puros: no levantan Spring ni HTTP. La ruta a la
 * raiz del repo se obtiene de {@code obs005.repo-root} (configurado por el
 * {@code pom.xml} de common como {@code ${project.basedir}/..}) y, si no
 * esta definido, cae a {@code ${user.dir}}.
 *
 * <p>El conteo de tests declarados en este fichero lo vigila
 * {@code TestCountMatchesDocsTest} (modulo {@code it}); cualquier {@code @Test}
 * anadido o quitado obliga a actualizar {@code docs/testing/TESTING.md} y
 * {@code docs/QUICK_START.md}.
 */
class DeployObservabilityStructureTest {

    private static final Set<String> RESERVED_PROMQL = Set.of(
            // Operadores y agregadores que NO son nombres de metrica.
            "on", "by", "without", "ignoring", "group_left", "group_right",
            "sum", "min", "max", "avg", "count", "topk", "bottomk",
            "rate", "irate", "increase", "delta", "idelta",
            "histogram_quantile", "quantile",
            "for", "le", "gt", "lt", "ne", "eq",
            "abs", "absent", "absent_over_time",
            "ceil", "floor", "exp", "ln", "log2", "log10", "sqrt",
            "round", "clamp", "clamp_min", "clamp_max",
            "and", "or", "unless",
            "label_replace", "label_join",
            "scalar", "time", "timestamp",
            "bool"
    );

    /**
     * Whitelist de series que la app publica hoy. Si se aniade una metricas
     * nueva o se renombra una existente, se actualiza esta lista y
     * {@code alerts.yml} en el mismo PR (es la regla de coherencia OBS-005
     * AC-3 / fase B1.4 del plan).
     */
    private static final List<Pattern> KNOWN_METRIC_PATTERNS = List.of(
            Pattern.compile("sap_sync_state_total"),
            Pattern.compile("sap_sync_stage_duration_seconds(_bucket|_count|_sum)?"),
            Pattern.compile("sap_sync_feature_result_total"),
            Pattern.compile("sap_client_request_duration_seconds(_bucket|_count|_sum)?"),
            Pattern.compile("resilience4j_circuitbreaker_state"),
            Pattern.compile("resilience4j_retry_calls"),
            Pattern.compile("kafka_consumergroup_records_lag")
    );

    private static Path repoRoot;

    @BeforeAll
    static void resolveRepoRoot() {
        // Por defecto el working dir de surefire es el modulo (common/), asi
        // que subir dos niveles lleva a la raiz del repo.
        Path fromProperty = Paths.get(System.getProperty("obs005.repo-root", "../.."));
        repoRoot = fromProperty.isAbsolute()
                ? fromProperty
                : Paths.get(System.getProperty("user.dir")).resolve(fromProperty).normalize();
    }

    /** OBS-005 AC-2: cada dashboard versionado es JSON valido. */
    @Test
    void allDashboardsAreValidJson() throws IOException {
        List<Path> dashboards = listDashboards();
        assertThat(dashboards)
                .as("Esperaba al menos un dashboard versionado; el directorio "
                        + "deploy/observability/grafana/dashboards/*.json debe existir")
                .isNotEmpty();

        ObjectMapper mapper = new ObjectMapper();
        for (Path p : dashboards) {
            JsonNode tree;
            try {
                tree = mapper.readTree(p.toFile());
            } catch (IOException e) {
                throw new AssertionError("Dashboard no parseable: " + p + " -> " + e.getMessage(), e);
            }
            assertThat(tree.isObject())
                    .as("Dashboard raiz debe ser un objeto JSON: " + p)
                    .isTrue();
            assertThat(tree.path("uid").asText())
                    .as("Dashboard sin uid: " + p)
                    .isNotBlank();
        }
    }

    /** OBS-005 AC-2: cada dashboard declara una variable DS_PROMETHEUS o DS_LOKI. */
    @Test
    void everyDashboardDeclaresDsPrometheusOrDsLokiVariable() throws IOException {
        List<Path> dashboards = listDashboards();
        assertThat(dashboards)
                .as("Sin dashboards versionados no se puede vigilar el contrato; crea "
                        + "deploy/observability/grafana/dashboards/*.json primero")
                .isNotEmpty();
        for (Path p : dashboards) {
            JsonNode vars = mapper().readTree(p.toFile()).path("templating").path("list");
            Set<String> names = new HashSet<>();
            for (JsonNode v : vars) {
                names.add(v.path("name").asText());
            }
            assertThat(names)
                    .as("Dashboard " + p.getFileName() + " debe declarar una variable DS_PROMETHEUS o DS_LOKI; tiene " + names)
                    .anyMatch(n -> n.equals("DS_PROMETHEUS") || n.equals("DS_LOKI"));
        }
    }

    /** OBS-005 AC-2: las targets de cada panel apuntan a DS_PROMETHEUS o DS_LOKI. */
    @Test
    void everyPanelTargetsEitherDsPrometheusOrDsLoki() throws IOException {
        List<Path> dashboards = listDashboards();
        assertThat(dashboards)
                .as("Sin dashboards versionados no hay panels que vigilar")
                .isNotEmpty();
        for (Path p : dashboards) {
            JsonNode panels = mapper().readTree(p.toFile()).path("panels");
            List<String> bad = new ArrayList<>();
            walkPanels(panels, bad, p);
            assertThat(bad)
                    .as("Paneles en " + p.getFileName() + " deben apuntar a ${DS_PROMETHEUS} o ${DS_LOKI}, "
                            + "no a una instancia Prometheus/Loki concreta: " + bad)
                    .isEmpty();
        }
    }

    /** OBS-005 AC-3: las alertas referencian series que la app publica hoy. */
    @Test
    void everyAlertExpressionReferencesASeriesFromTheApp() throws IOException {
        Object yaml = new Yaml().load(Files.readString(alertsFile(), StandardCharsets.UTF_8));
        assertThat(yaml).as("alerts.yml debe ser un mapa").isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) ((Map<String, Object>) yaml).get("groups");
        assertThat(groups)
                .as("alerts.yml debe declarar al menos un grupo PrometheusRule")
                .isNotEmpty();

        List<String> badAlerts = new ArrayList<>();
        for (Map<String, Object> group : groups) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rules = (List<Map<String, Object>>) group.get("rules");
            if (rules == null) {
                continue;
            }
            for (Map<String, Object> rule : rules) {
                String alert = String.valueOf(rule.get("alert"));
                Object exprObj = rule.get("expr");
                if (!(exprObj instanceof String expr)) {
                    badAlerts.add(alert + ": expr no es String");
                    continue;
                }
                if (!exprReferencesKnownMetric(expr)) {
                    badAlerts.add(alert + " -> " + expr);
                }
            }
        }
        assertThat(badAlerts)
                .as("Toda alerta en alerts.yml debe mencionar una serie de la whitelist. "
                        + "Si es intencionado, actualiza KNOWN_METRIC_PATTERNS en este test y la documentacion. "
                        + "Mal: " + badAlerts)
                .isEmpty();
    }

    /** OBS-005 AC-2: el YAML de alertas parsea sin errores. */
    @Test
    void alertsYmlIsValidYaml() throws IOException {
        Object parsed = new Yaml().load(Files.readString(alertsFile(), StandardCharsets.UTF_8));
        assertThat(parsed)
                .as("alerts.yml debe parsear y no ser null/vacio")
                .isNotNull();
    }

    /** OBS-005 AC-6: cada ejemplo de entorno declara OBS_ENV y OBS_CLUSTER. */
    @Test
    void everyEnvExampleDeclaresObsEnvAndObsCluster() throws IOException {
        List<Path> files = List.of(
                repoRoot.resolve("scripts/env/local.env"),
                repoRoot.resolve("scripts/env/test.env.example"),
                repoRoot.resolve("scripts/env/prod.env.example"));
        for (Path p : files) {
            assertThat(Files.exists(p))
                    .as("Fichero de entorno esperado por OBS-005 AC-6: " + p)
                    .isTrue();
            String contents = Files.readString(p, StandardCharsets.UTF_8);
            assertThat(contents)
                    .as(p + " debe declarar OBS_ENV para que /actuator/prometheus lleve el tag env")
                    .containsPattern("(?m)^\\s*OBS_ENV\\s*[:=]");
            assertThat(contents)
                    .as(p + " debe declarar OBS_CLUSTER (aunque sea vacio) para que el tag cluster nunca falte")
                    .containsPattern("(?m)^\\s*OBS_CLUSTER\\s*[:=]");
        }
    }

    // ---- helpers -------------------------------------------------------------

    private static ObjectMapper mapper() {
        return new ObjectMapper();
    }

    private static List<Path> listDashboards() throws IOException {
        Path dir = repoRoot.resolve("deploy/observability/grafana/dashboards");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
    }

    private static Path alertsFile() {
        return repoRoot.resolve("deploy/observability/prometheus/rules/alerts.yml");
    }

    /** Recorre el arbol de paneles (panels anidados, collapsados). */
    private static void walkPanels(JsonNode panels, List<String> bad, Path p) {
        if (panels == null || !panels.isArray()) {
            return;
        }
        for (JsonNode panel : panels) {
            // El datasource del panel cubre todos sus targets: si el target no
            // declara uno propio, hereda este. Esto es lo que usa Grafana
            // moderno (schemaVersion >= 38).
            String panelDs = panelDsText(panel.path("datasource"));
            JsonNode targets = panel.path("targets");
            if (targets.isArray()) {
                for (JsonNode t : targets) {
                    JsonNode ds = t.path("datasource");
                    // Si el target NO declara datasource propio, usamos el del
                    // panel. Si tampoco tiene, queda vacio y falla.
                    String text = (ds.isMissingNode() || ds.isNull())
                            ? panelDs
                            : ds.toString();
                    if (!text.contains("DS_PROMETHEUS") && !text.contains("DS_LOKI")) {
                        bad.add(panel.path("title").asText("?") + " -> datasource=" + text);
                    }
                }
            }
            JsonNode collapsed = panel.path("panels");
            if (collapsed.isArray()) {
                walkPanels(collapsed, bad, p);
            }
        }
    }

    /** Representa el datasource de un panel como string comparable. */
    private static String panelDsText(JsonNode ds) {
        if (ds == null || ds.isMissingNode() || ds.isNull()) {
            return "";
        }
        // Grafana lo guarda como objeto {type, uid}; comparamos contra el uid
        // textual, no contra toString() (que incluye saltos de linea).
        return ds.toString();
    }

    /**
     * Heuristica: la expr menciona al menos una serie de la whitelist. Esto
     * evita falsos positivos con labels (p. ej. {@code by (domain) (rate(...))})
     * que el primer identificador no reservado seria {@code domain}.
     */
    private static boolean exprReferencesKnownMetric(String expr) {
        Matcher m = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(expr);
        while (m.find()) {
            String ident = m.group();
            if (RESERVED_PROMQL.contains(ident)) {
                continue;
            }
            // Si es el prefijo de una metrica multi-segmento (p.ej. "rate(... *_bucket)"),
            // hay que reconstruir el nombre entero a mano para no quedarnos en
            // "sap" cuando la serie es "sap_sync_feature_result_total".
            int end = m.end();
            int next = end;
            while (next < expr.length()
                    && (Character.isLetterOrDigit(expr.charAt(next)) || expr.charAt(next) == '_')) {
                next++;
            }
            String candidate = expr.substring(m.start(), next);
            for (Pattern known : KNOWN_METRIC_PATTERNS) {
                if (known.matcher(candidate).matches() || known.matcher(ident).matches()) {
                    return true;
                }
            }
        }
        return false;
    }
}

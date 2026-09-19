package com.poc.sap.customer.bootstrap.web;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sdd/common/contrato-openapi-rest.md AC-1..AC-4: el contrato REST publicado en
 * {@code customer/src/main/resources/openapi.yml} y los controladores del modulo
 * son el mismo hecho contado dos veces. Si alguien anade, borra o cambia el rol
 * de un endpoint sin tocar el YAML, este test rompe el build y dice cual.
 *
 * <p>Lee el YAML con SnakeYAML (ya en el classpath por Spring Boot) y descubre
 * los endpoints por reflexion sobre las clases compiladas del paquete
 * {@code bootstrap.web}, el mismo criterio que usa {@code EndpointsDeclareAccessTest}.
 */
class OpenApiMatchesControllersTest {

    private static final String WEB_PACKAGE = "com.poc.sap.customer.bootstrap.web";
    private static final Path CLASSES = Paths.get("target/classes");
    private static final Path OPENAPI = Paths.get("src/main/resources/openapi.yml");

    /** AC-1: todo endpoint del controlador esta declarado en el contrato. */
    @Test
    void everyControllerEndpointIsDeclaredInTheContract() throws Exception {
        Map<String, Endpoint> endpoints = controllerEndpoints();
        Map<String, Object> paths = contractPaths();

        List<String> missing = new ArrayList<>();
        endpoints.forEach((key, e) -> {
            Object path = paths.get(e.path());
            if (!(path instanceof Map<?, ?> operations) || !operations.containsKey(e.httpMethod())) {
                missing.add(key + "  (" + e.declaredIn() + ")");
            }
        });

        assertThat(missing)
                .as("Endpoints que existen en el codigo pero no en %s: dalos de alta en el contrato %s",
                        OPENAPI, missing)
                .isEmpty();
    }

    /** AC-2: el contrato no inventa endpoints que no existan en el codigo. */
    @Test
    void everyContractOperationExistsInAController() throws Exception {
        Set<String> endpoints = controllerEndpoints().keySet();
        List<String> ghosts = new ArrayList<>();
        contractPaths().forEach((path, item) -> ((Map<?, ?>) item).keySet().forEach(op -> {
            String key = op.toString().toUpperCase(java.util.Locale.ROOT) + " " + path;
            if (!endpoints.contains(key)) {
                ghosts.add(key);
            }
        }));

        assertThat(ghosts)
                .as("Operaciones declaradas en %s sin controlador que las atienda: %s", OPENAPI, ghosts)
                .isEmpty();
    }

    /** AC-3: cada operacion declara x-required-role y coincide con su @PreAuthorize. */
    @Test
    void everyOperationDeclaresTheRoleItsPreAuthorizeDemands() throws Exception {
        Map<String, Object> paths = contractPaths();
        List<String> wrong = new ArrayList<>();

        controllerEndpoints().forEach((key, e) -> {
            Map<?, ?> operations = (Map<?, ?>) paths.get(e.path());
            if (operations == null) {
                return;   // lo reporta everyControllerEndpointIsDeclaredInTheContract
            }
            Object operation = operations.get(e.httpMethod());
            if (!(operation instanceof Map<?, ?> op)) {
                return;
            }
            Set<String> declared = asRoleSet(op.get("x-required-role"));
            if (declared.isEmpty()) {
                wrong.add(key + ": sin x-required-role (el codigo exige " + e.roles() + ")");
            } else if (!declared.equals(e.roles())) {
                wrong.add(key + ": el contrato dice " + declared + " y el @PreAuthorize de "
                        + e.declaredIn() + " exige " + e.roles());
            }
        });

        assertThat(wrong).as("Roles que no cuadran entre %s y los @PreAuthorize: %s", OPENAPI, wrong).isEmpty();
    }

    /** AC-4: el contrato es importable: version 3.1, servidores y esquema de seguridad. */
    @Test
    void theContractDeclaresVersionServersAndSecurityScheme() throws Exception {
        Map<String, Object> contract = contract();

        assertThat(String.valueOf(contract.get("openapi")))
                .as("%s debe ser OpenAPI 3.1", OPENAPI).startsWith("3.1");
        assertThat(contract.get("info")).as("%s debe llevar info", OPENAPI).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> servers = (List<Map<String, Object>>) contract.get("servers");
        assertThat(servers).as("%s debe declarar los servidores (local y test)", OPENAPI).hasSizeGreaterThanOrEqualTo(2);
        assertThat(servers).allSatisfy(s -> assertThat(s).containsKey("url"));

        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) contract.get("components");
        assertThat(components).as("%s debe llevar components", OPENAPI).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> schemes = (Map<String, Object>) components.get("securitySchemes");
        assertThat(schemes).as("%s debe declarar securitySchemes.keycloak", OPENAPI).containsKey("keycloak");
        assertThat(contract.get("security")).as("%s debe aplicar seguridad global", OPENAPI).isNotNull();
    }

    // --- lectura del contrato -------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> contract() throws IOException {
        assertThat(OPENAPI).as("el contrato REST del modulo debe existir").exists();
        try (InputStream in = Files.newInputStream(OPENAPI)) {
            return (Map<String, Object>) new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> contractPaths() throws IOException {
        Map<String, Object> paths = (Map<String, Object>) contract().get("paths");
        assertThat(paths).as("%s debe declarar paths", OPENAPI).isNotNull();
        return paths;
    }

    private static Set<String> asRoleSet(Object value) {
        Set<String> roles = new LinkedHashSet<>();
        if (value instanceof List<?> list) {
            list.forEach(r -> roles.add(String.valueOf(r)));
        } else if (value != null) {
            roles.add(String.valueOf(value));
        }
        return roles;
    }

    // --- descubrimiento de los endpoints del codigo ---------------------------

    /** Un endpoint tal y como lo declara el controlador. */
    private record Endpoint(String httpMethod, String path, Set<String> roles, String declaredIn) {}

    private static final Pattern ROLE = Pattern.compile("'([A-Z_]+)'");

    private static Map<String, Endpoint> controllerEndpoints() throws Exception {
        Map<String, Endpoint> endpoints = new TreeMap<>();
        for (Class<?> controller : webClasses()) {
            if (!controller.isAnnotationPresent(RestController.class)) {
                continue;
            }
            String base = controller.isAnnotationPresent(RequestMapping.class)
                    ? first(controller.getAnnotation(RequestMapping.class).value())
                    : "";
            for (Method m : controller.getDeclaredMethods()) {
                mapping(m).ifPresent(mapped -> {
                    String path = join(base, mapped.path());
                    PreAuthorize pre = m.getAnnotation(PreAuthorize.class);
                    Set<String> roles = new LinkedHashSet<>();
                    if (pre != null) {
                        Matcher matcher = ROLE.matcher(pre.value());
                        while (matcher.find()) {
                            roles.add(matcher.group(1));
                        }
                    }
                    endpoints.put(mapped.httpMethod().toUpperCase(java.util.Locale.ROOT) + " " + path,
                            new Endpoint(mapped.httpMethod(), path, roles,
                                    controller.getSimpleName() + "#" + m.getName()));
                });
            }
        }
        assertThat(endpoints).as("no se ha descubierto ningun endpoint en %s; "
                + "compila el modulo antes (target/classes)", WEB_PACKAGE).isNotEmpty();
        return endpoints;
    }

    private record Mapped(String httpMethod, String path) {}

    private static java.util.Optional<Mapped> mapping(Method m) {
        Map<Class<? extends Annotation>, String> verbs = new LinkedHashMap<>();
        verbs.put(GetMapping.class, "get");
        verbs.put(PostMapping.class, "post");
        verbs.put(PutMapping.class, "put");
        verbs.put(PatchMapping.class, "patch");
        verbs.put(DeleteMapping.class, "delete");
        for (Map.Entry<Class<? extends Annotation>, String> e : verbs.entrySet()) {
            Annotation a = m.getAnnotation(e.getKey());
            if (a != null) {
                return java.util.Optional.of(new Mapped(e.getValue(), first(value(a))));
            }
        }
        return java.util.Optional.empty();
    }

    private static String[] value(Annotation a) {
        try {
            return (String[]) a.annotationType().getMethod("value").invoke(a);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String first(String[] values) {
        return values.length == 0 ? "" : values[0];
    }

    private static String join(String base, String path) {
        String joined = (base + path).replace("//", "/");
        return joined.isEmpty() ? "/" : joined;
    }

    private static List<Class<?>> webClasses() throws Exception {
        Path dir = CLASSES.resolve(WEB_PACKAGE.replace('.', '/'));
        assertThat(dir).as("clases compiladas del paquete web").exists();
        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = f.getFileName().toString().replace(".class", "");
                if (name.contains("$")) {
                    continue;
                }
                classes.add(Class.forName(WEB_PACKAGE + "." + name));
            }
        }
        return classes;
    }
}

package com.poc.sap.common.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * Comprueba, ANTES de crear ningun bean, que las credenciales del legacy
 * llegaron de verdad (sdd/common/autenticacion-sap.md R-5, AC-6). Spring Boot
 * deja sin resolver los placeholders que no encuentra ({@code ${POSTGRES_USER}}
 * viaja literal hasta la base de datos) y el sintoma era un "password
 * authentication failed for user ${POSTGRES_USER}" al inicializar Hibernate,
 * que arranca antes que cualquier {@code @PostConstruct}. Por eso es un
 * {@link EnvironmentPostProcessor} (registrado en META-INF/spring.factories) y
 * no un componente. Visto en vivo el 12-09-2026 al arrancar article-app sin
 * cargar scripts/env/local.env.
 */
public class LegacyCredentialsGuard implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.containsProperty("spring.datasource.url")) {
            return;   // la app no tiene legacy JPA: nada que comprobar
        }
        check(raw(environment, "spring.datasource.username"), raw(environment, "spring.datasource.password"));
    }

    /** Valor con los placeholders resueltos si se puede; si no, el literal con `${...}`. */
    private static String raw(ConfigurableEnvironment env, String key) {
        try {
            String v = env.getProperty(key);
            return v == null ? "" : v;
        } catch (RuntimeException unresolved) {
            // Spring 7 lanza PlaceholderResolutionException (no es IllegalArgumentException):
            // se lee el valor crudo de la fuente para mostrar el placeholder que falta.
            for (PropertySource<?> source : env.getPropertySources()) {
                Object literal = source.getProperty(key);
                if (literal != null) {
                    return literal.toString();
                }
            }
            return "${?}";
        }
    }

    /** Falla si el usuario o la clave vienen vacios o con un placeholder sin resolver. */
    public static void check(String username, String password) {
        String user = username == null ? "" : username;
        String pass = password == null ? "" : password;
        if (missing(user) || missing(pass)) {
            throw new IllegalStateException("Credenciales del legacy sin resolver: spring.datasource.username="
                    + describe(user) + ", password=" + describe(pass)
                    + ". El YAML no trae defaults a proposito: exporta SQLSERVER_USER/SQLSERVER_PASSWORD "
                    + "(customer) o POSTGRES_USER/POSTGRES_PASSWORD (article), p. ej. "
                    + "`set -a; source scripts/env/local.env; set +a`");
        }
    }

    static boolean missing(String value) {
        return value == null || value.isBlank() || value.contains("${");
    }

    private static String describe(String value) {
        return value.isBlank() ? "<vacio>" : value.contains("${") ? "'" + value + "' (placeholder sin resolver)" : "<ok>";
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 10;   // tras cargar application*.yml
    }
}

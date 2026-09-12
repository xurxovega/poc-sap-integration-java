package com.poc.sap.common.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Comprueba al arrancar que las credenciales del legacy llegaron de verdad
 * (sdd/common/autenticacion-sap.md R-5). Spring Boot deja sin resolver los
 * placeholders que no encuentra ({@code ${POSTGRES_USER}} viaja literal hasta
 * la base de datos) y el sintoma es un "password authentication failed for
 * user ${POSTGRES_USER}" varias capas mas abajo. Visto en vivo el 12-09-2026 al
 * arrancar article-app sin cargar scripts/env/local.env.
 */
@Component
public class LegacyCredentialsGuard {

    private final String username;
    private final String password;

    public LegacyCredentialsGuard(@Value("${spring.datasource.username:}") String username,
                                  @Value("${spring.datasource.password:}") String password) {
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
    }

    @PostConstruct
    void validate() {
        if (missing(username) || missing(password)) {
            throw new IllegalStateException("Credenciales del legacy sin resolver: spring.datasource.username="
                    + describe(username) + ", password=" + (password.isBlank() ? "<vacia>" : describe(password))
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
}

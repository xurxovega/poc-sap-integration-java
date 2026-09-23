package com.poc.sap.common.security;

import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;

/**
 * Roles de las APIs REST (sdd/common/seguridad-api.md §4). Son roles de
 * Keycloak (realm o cliente) que {@link KeycloakRoleConverter} convierte en
 * {@code ROLE_<NOMBRE_EN_MAYUSCULAS>}. Jerarquia: superadmin > admin > write >
 * read; external-read va aparte: solo lectura y sin datos sensibles.
 */
public final class ApiRoles {

    /** Lectura completa (incluye PII): usuarios internos y servicios de lectura. */
    public static final String READ = "SAP_READ";
    /** Escritura: disparar sincronizaciones/validaciones. Incluye READ. */
    public static final String WRITE = "SAP_WRITE";
    /** Operacion: actuator completo. Incluye WRITE. */
    public static final String ADMIN = "SAP_ADMIN";
    /** Todo. Incluye ADMIN. */
    public static final String SUPERADMIN = "SAP_SUPERADMIN";
    /** Clientes externos: lectura con PII enmascarada, sin diff ni escritura. */
    public static final String EXTERNAL_READ = "SAP_EXTERNAL_READ";

    /** Nombre del rol tal como se crea en Keycloak (en minusculas y con guion). */
    public static String keycloakName(String role) {
        return role.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    public static RoleHierarchy hierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role(SUPERADMIN).implies(ADMIN)
                .role(ADMIN).implies(WRITE)
                .role(WRITE).implies(READ)
                .build();
    }

    private ApiRoles() {}
}

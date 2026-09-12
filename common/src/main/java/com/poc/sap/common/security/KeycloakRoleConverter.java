package com.poc.sap.common.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Roles de Keycloak -> authorities de Spring (sdd/common/seguridad-api.md R-2).
 * Lee {@code realm_access.roles} y {@code resource_access.<client>.roles} del
 * token y los convierte a {@code ROLE_<MAYUSCULAS_CON_GUION_BAJO>}:
 * {@code sap-external-read} -> {@code ROLE_SAP_EXTERNAL_READ}.
 */
public final class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private final String clientId;

    public KeycloakRoleConverter(String clientId) {
        this.clientId = clientId == null ? "" : clientId;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<GrantedAuthority> out = new LinkedHashSet<>();
        Map<String, Object> realm = jwt.getClaim("realm_access");
        addRoles(out, realm);
        Map<String, Object> resources = jwt.getClaim("resource_access");
        if (resources != null && !clientId.isBlank() && resources.get(clientId) instanceof Map<?, ?> client) {
            addRoles(out, (Map<String, Object>) client);
        }
        return out;
    }

    private static void addRoles(Set<GrantedAuthority> out, Map<String, Object> access) {
        if (access == null || !(access.get("roles") instanceof List<?> roles)) {
            return;
        }
        for (Object r : roles) {
            out.add(new SimpleGrantedAuthority(authority(String.valueOf(r))));
        }
    }

    static String authority(String keycloakRole) {
        return "ROLE_" + keycloakRole.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }
}

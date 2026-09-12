package com.poc.sap.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec sdd/common/seguridad-api.md AC-1: roles de Keycloak -> ROLE_*. */
class KeycloakRoleConverterTest {

    private static Jwt jwt(Map<String, Object> claims) {
        return new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "RS256"), claims);
    }

    @Test
    void realmAndClientRolesBecomeUpperCaseAuthorities() {
        Jwt token = jwt(Map.of(
                "sub", "u1",
                "realm_access", Map.of("roles", List.of("sap-read", "offline_access")),
                "resource_access", Map.of(
                        "sap-integration", Map.of("roles", List.of("sap-write")),
                        "otro-cliente", Map.of("roles", List.of("sap-superadmin")))));

        var authorities = new KeycloakRoleConverter("sap-integration").convert(token).stream()
                .map(GrantedAuthority::getAuthority).toList();

        assertThat(authorities).containsExactlyInAnyOrder("ROLE_SAP_READ", "ROLE_OFFLINE_ACCESS", "ROLE_SAP_WRITE");
    }

    @Test
    void tokenWithoutRolesYieldsNoAuthoritiesAndNamesMatchKeycloak() {
        assertThat(new KeycloakRoleConverter("sap-integration").convert(jwt(Map.of("sub", "u1")))).isEmpty();
        assertThat(ApiRoles.keycloakName(ApiRoles.EXTERNAL_READ)).isEqualTo("sap-external-read");
        assertThat(KeycloakRoleConverter.authority(" sap-admin ")).isEqualTo("ROLE_SAP_ADMIN");
    }

    /** AC-2: superadmin > admin > write > read; external-read no implica read. */
    @Test
    void hierarchyImpliesLowerRolesButNotExternal() {
        var h = ApiRoles.hierarchy();
        var fromSuper = h.getReachableGrantedAuthorities(List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SAP_SUPERADMIN")))
                .stream().map(GrantedAuthority::getAuthority).toList();
        assertThat(fromSuper).contains("ROLE_SAP_ADMIN", "ROLE_SAP_WRITE", "ROLE_SAP_READ").doesNotContain("ROLE_SAP_EXTERNAL_READ");
        var scope = new AccessScope(h);
        assertThat(scope.canSeeSensitiveData(new org.springframework.security.authentication.TestingAuthenticationToken("x", "y", "ROLE_SAP_EXTERNAL_READ"))).isFalse();
        assertThat(scope.canSeeSensitiveData(new org.springframework.security.authentication.TestingAuthenticationToken("x", "y", "ROLE_SAP_WRITE"))).isTrue();
        assertThat(scope.canSeeSensitiveData(null)).isTrue();
    }
}

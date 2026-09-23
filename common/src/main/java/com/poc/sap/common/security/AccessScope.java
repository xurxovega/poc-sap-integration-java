package com.poc.sap.common.security;

import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Que puede ver quien llama (sdd/common/seguridad-api.md R-4). Los controllers
 * lo consultan para enmascarar PII antes de responder a un cliente externo.
 */
@Component
public class AccessScope {

    private final RoleHierarchy hierarchy;

    public AccessScope(RoleHierarchy hierarchy) {
        this.hierarchy = hierarchy;
    }

    /** True si el llamador tiene lectura completa (READ o superior). Sin autenticacion (seguridad desactivada o test standalone): true. */
    public boolean canSeeSensitiveData() {
        return canSeeSensitiveData(SecurityContextHolder.getContext().getAuthentication());
    }

    public boolean canSeeSensitiveData(Authentication auth) {
        if (auth == null) {
            return true;
        }
        return hierarchy.getReachableGrantedAuthorities(auth.getAuthorities()).stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(("ROLE_" + ApiRoles.READ)::equals);
    }
}

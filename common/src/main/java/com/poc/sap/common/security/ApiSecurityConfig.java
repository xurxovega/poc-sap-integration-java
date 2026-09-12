package com.poc.sap.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Seguridad de las APIs REST y de actuator (sdd/common/seguridad-api.md;
 * auditoria B4; ADR-0007): resource server OAuth2 con los JWT de Keycloak.
 *
 * <ul>
 *   <li>Quien puede llamar a cada endpoint se declara EN el endpoint con
 *       {@code @PreAuthorize} (equivalente a los atributos de .NET); esta clase
 *       solo exige token y protege actuator.</li>
 *   <li>{@code app.security.enabled=false} (solo el entorno local con el SAP
 *       simulado): todo abierto y un WARN al arrancar.</li>
 *   <li>Health, info y prometheus quedan sin token para las sondas de k8s y el
 *       scraping; el resto de actuator exige ADMIN. El ingress no debe exponer
 *       /actuator.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class ApiSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(ApiSecurityConfig.class);

    @Bean
    public RoleHierarchy roleHierarchy() {
        return ApiRoles.hierarchy();
    }

    @Bean
    @ConditionalOnProperty(name = "app.security.enabled", havingValue = "true", matchIfMissing = true)
    public SecurityFilterChain apiSecurity(HttpSecurity http,
                                           ObjectProvider<JwtDecoder> jwtDecoder,
                                           @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuer,
                                           @Value("${app.security.keycloak.client-id:sap-integration}") String clientId) throws Exception {
        if (issuer == null || issuer.isBlank() || jwtDecoder.getIfAvailable() == null) {
            throw new IllegalStateException("Seguridad de API activada (app.security.enabled=true) sin "
                    + "KEYCLOAK_ISSUER_URI: la app no arranca sin saber quien firma los tokens. "
                    + "Contra el mock local exporta APP_SECURITY_ENABLED=false");
        }
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRoleConverter(clientId));
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    // Por ruta (management.endpoints.web.base-path por defecto): sondas de k8s y
                    // scraping sin token; el resto de actuator, solo ADMIN.
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                    .requestMatchers("/actuator/**").hasRole(ApiRoles.ADMIN)
                    .anyRequest().authenticated())
            .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        log.info("Seguridad de API activa: JWT de {} (client-id {} para roles de cliente)", issuer, clientId);
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.security.enabled", havingValue = "false")
    public SecurityFilterChain openSecurity(HttpSecurity http) throws Exception {
        log.warn("Seguridad de API DESACTIVADA (app.security.enabled=false): todos los endpoints abiertos. "
                + "Solo valido para el entorno local con el SAP simulado");
        http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}

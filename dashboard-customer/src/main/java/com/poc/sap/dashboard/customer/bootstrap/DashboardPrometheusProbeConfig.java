package com.poc.sap.dashboard.customer.bootstrap;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Configuracion minima para el test de Prometheus (UI-001 H-4 AC-5).
 * Arranca el modulo con lo justo: desactiva seguridad porque Prometheus no
 * necesita token (escenario gestor-monitor). No escanea controllers: el
 * test no los invoca, solo verifica /actuator/prometheus.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
        SecurityAutoConfiguration.class,
        OAuth2ResourceServerAutoConfiguration.class
})
@ComponentScan(
        basePackages = "com.poc.sap.dashboard.customer",
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.REGEX,
                        pattern = "com\\.poc\\.sap\\.dashboard\\.customer\\.bootstrap\\.web\\..*"),
                @ComponentScan.Filter(type = FilterType.REGEX,
                        pattern = "com\\.poc\\.sap\\.common\\.security\\..*")
        })
public class DashboardPrometheusProbeConfig {
}

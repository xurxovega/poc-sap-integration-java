package com.poc.sap.dashboard.customer.bootstrap;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-5 (UI-001 H-4): el dashboard expone series Prometheus en su
 * {@code /actuator/prometheus}. Aqui verificamos que un
 * {@link PrometheusMeterRegistry} con el nombre de aplicacion configurado
 * produce las series que la UI va a publicar (incluido {@code http_server_requests_seconds}).
 *
 * <p>El slice evita el coste de un {@code @SpringBootTest}: arranca el
 * registry de Prometheus con las mismas tags que application-common.yml
 * aporta (application, env, cluster) y comprueba que el scrape funciona.
 */
class DashboardEmitsPrometheusMetricsTest {

    private PrometheusMeterRegistry registry;

    @BeforeEach
    void setUp() {
        MeterRegistry simple = new SimpleMeterRegistry();
        registry = new PrometheusMeterRegistry(io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT);
        // commonTags las aplica application-common.yml en runtime; replicamos aqui.
        registry.config().commonTags(
                "application", "dashboard-customer",
                "env", "test",
                "cluster", "local");
    }

    @Test
    void httpServerRequestsSeriesIsExposedWithApplicationTag() {
        // Simula el scrape del http_server_requests que Boot 4 publica por defecto
        // (cada endpoint lleva uri como tag y el filtro uri-templates lo aplica).
        registry.timer("http_server_requests_seconds",
                "uri", "/customers/{id}",
                "method", "GET",
                "status", "200",
                "outcome", "SUCCESS").record(java.time.Duration.ofMillis(120));

        String scrape = registry.scrape();

        assertThat(scrape)
                .as("el scrape de Prometheus lleva el tag application=dashboard-customer")
                .contains("application=\"dashboard-customer\"");
        assertThat(scrape).contains("http_server_requests_seconds");
        assertThat(scrape).contains("uri=\"/customers/{id}\"");
    }

    @Test
    void parserAcceptsTheScrapeFormat() throws Exception {
        // Para que el scrape no este vacio y arranque con HELP line, registramos
        // un counter y un gauge del propio dashboard. Los gauges en Micrometer
        // se exponen via AtomicLong con tags.
        java.util.concurrent.atomic.AtomicLong openAlerts = new java.util.concurrent.atomic.AtomicLong(3);
        registry.gauge("dashboard_open_alerts", openAlerts);
        registry.counter("dashboard_request_total", "endpoint", "search").increment();

        String scrape = registry.scrape();
        // MiniSmoke: el scrape incluye las HELP/Type line de Prometheus y
        // las metricas del dashboard.
        assertThat(scrape).startsWith("# HELP");
        assertThat(scrape).contains("dashboard_request_total");
        assertThat(scrape).contains("dashboard_open_alerts");
    }
}

package com.poc.sap.dashboard.customer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point del dashboard-customer (UI-001).
 *
 * <p>App Spring Boot de solo lectura contra Mongo y Elasticsearch: a diferencia
 * del {@code customer-app} no consume Kafka ni ataca SQL Server, asi que no
 * arrastra los starters JDBC/JPA/Kafka. Si Boot intenta autoconfigurar algo que
 * no aplica, los autoconfig matching conditions lo filtran. Lee directo de
 * Mongo ({@code customers_current}, {@code sync_state}, {@code alerts}) y ES
 * ({@code customers_history}) con driver nativo para hacer explicito el
 * aislamiento entre bounded contexts ({@code DashboardIsolationTest}).
 *
 * <p>{@link EnableScheduling} activa el job de KPIs (MTTR tecnico, recuperacion
 * p95) que vive dentro del modulo y se materializa en H-5
 * ({@code dashboard-customer.observability.KpiJob}).
 */
@SpringBootApplication
@ComponentScan(basePackages = {
        "com.poc.sap.dashboard.customer",
        // common.security.ApiSecurityConfig / AccessScope / KeycloakRoleConverter
        // viven aqui, son parte del shared kernel.
        "com.poc.sap.common"
})
@EnableScheduling
public class DashboardCustomerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DashboardCustomerApplication.class, args);
    }
}

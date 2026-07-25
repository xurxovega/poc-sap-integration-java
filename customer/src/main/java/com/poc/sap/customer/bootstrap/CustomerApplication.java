package com.poc.sap.customer.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.web.server.servlet.context.ServletComponentScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Entry point del dominio CUSTOMER (TECH.md §3).
 * App Spring Boot independiente: se despliega sin tocar article/supplier.
 *
 * <p>Los repositorios Spring Data se declaran explicitamente: la deteccion
 * automatica solo escanea el paquete de esta clase (bootstrap), no los
 * adapters ni el shared kernel common.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.sap.cloud.sdk", "com.poc.sap.customer", "com.poc.sap.common"})
@ServletComponentScan(basePackages = {"com.sap.cloud.sdk", "com.poc.sap.customer", "com.poc.sap.common"})
@EnableJpaRepositories(basePackages = "com.poc.sap.customer.adapters.persistence")
@EntityScan(basePackages = "com.poc.sap.customer.adapters.persistence")
@EnableMongoRepositories(basePackages = {
        "com.poc.sap.customer.adapters.persistence",
        "com.poc.sap.common.adapters.persistence"})
@EnableElasticsearchRepositories(basePackages = "com.poc.sap.customer.adapters.index")
public class CustomerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerApplication.class, args);
    }
}

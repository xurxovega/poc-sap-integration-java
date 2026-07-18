package com.poc.sap.customer.bootstrap;

import com.poc.sap.common.sap.SapClient;
import com.poc.sap.common.sap.SapDestination;
import com.poc.sap.common.sap.WebClientSapClient;
import com.poc.sap.common.sap.auth.SapAuthProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.servlet.context.ServletComponentScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;

import java.util.Map;

/**
 * Entry point del dominio CUSTOMER (TECH.md §3).
 * App Spring Boot independiente: se despliega sin tocar article/supplier.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.sap.cloud.sdk", "com.poc.sap.customer", "com.poc.sap.common"})
@ServletComponentScan(basePackages = {"com.sap.cloud.sdk", "com.poc.sap.customer", "com.poc.sap.common"})
public class CustomerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerApplication.class, args);
    }

    @Bean
    public SapClient sapClient(Map<SapDestination, SapAuthProvider> authProviders,
                               @Value("${sap.btp.base-url:}") String btpBaseUrl,
                               @Value("${sap.s4.base-url:}") String s4BaseUrl,
                               CircuitBreakerRegistry cbRegistry,
                               RetryRegistry retryRegistry) {
        return new WebClientSapClient(
                authProviders, btpBaseUrl, s4BaseUrl, retryRegistry, cbRegistry);
    }
}
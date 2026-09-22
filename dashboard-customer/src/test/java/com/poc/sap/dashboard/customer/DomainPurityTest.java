package com.poc.sap.dashboard.customer;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Regla de capas vigilada por el build (AGENTS.md §2.2): el dominio del
 * dashboard-customer no depende de ningun framework. Cada modulo ejecuta
 * su propio mirror porque no comparten paquete raiz.
 */
@AnalyzeClasses(packages = "com.poc.sap.dashboard.customer", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainPurityTest {

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "com.fasterxml..", "tools.jackson..",
                    "org.bson..", "com.mongodb..",
                    "org.apache.kafka..",
                    "io.micrometer..",
                    "jakarta.persistence..",
                    "co.elastic.clients..",
                    "org.thymeleaf..")
            .because("domain es puro: sin Spring, Jackson, Mongo, Kafka, Micrometer, OData, ES ni Thymeleaf (AGENTS.md §2.2)")
            .allowEmptyShould(true);
}

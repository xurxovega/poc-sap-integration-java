package com.poc.sap.common;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Regla de capas vigilada por el build (AGENTS.md §2.2; auditoria A4/T29): el
 * dominio del shared kernel no depende de ningun framework. Es la regla que
 * AGENTS.md declara y que hasta hoy nadie comprobaba.
 */
@AnalyzeClasses(packages = "com.poc.sap.common", importOptions = ImportOption.DoNotIncludeTests.class)
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
                    "jakarta.persistence..")
            .because("domain es puro: sin Spring, Jackson, Mongo, Kafka, Micrometer ni JPA (AGENTS.md §2.2)");
}

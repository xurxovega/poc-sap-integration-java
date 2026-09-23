package com.poc.sap.article;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Plan Fase 7 (auditoria A4): {@code application} orquesta puertos y no conoce
 * el framework. Antes 16/16 use cases llevaban {@code @Service} y 14 importaban
 * Micrometer; el wiring vive ahora en {@code bootstrap/*UseCaseConfig}.
 */
@AnalyzeClasses(packages = "com.poc.sap.article", importOptions = com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests.class)
class ApplicationPurityTest {

    @ArchTest
    static final ArchRule application_no_depende_de_spring_micrometer_ni_jackson = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "io.micrometer..", "com.fasterxml..", "tools.jackson..");
}

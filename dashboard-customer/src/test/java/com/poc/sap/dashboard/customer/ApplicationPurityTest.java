package com.poc.sap.dashboard.customer;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Mirror del ApplicationPurityTest del modulo customer para
 * dashboard-customer: application orquesta puertos y no conoce el
 * framework. El wiring vive en bootstrap/DashboardUseCaseConfig.
 */
@AnalyzeClasses(packages = "com.poc.sap.dashboard.customer", importOptions = ImportOption.DoNotIncludeTests.class)
class ApplicationPurityTest {

    @ArchTest
    static final ArchRule application_no_depende_de_spring_micrometer_ni_jackson = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "io.micrometer..",
                    "com.fasterxml..", "tools.jackson..",
                    "org.thymeleaf..")
            .allowEmptyShould(true);
}

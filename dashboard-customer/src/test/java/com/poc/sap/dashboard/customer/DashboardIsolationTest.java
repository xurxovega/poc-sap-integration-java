package com.poc.sap.dashboard.customer;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Aislamiento entre bounded contexts (AGENTS.md §2.2, plan UI-001 AC-4):
 * el dashboard lee los datos compartidos via Mongo y ES (driver nativo)
 * pero NO importa los bounded contexts del customer-app.
 *
 * <p>{@link com.poc.sap.customer.application} (use cases, validaciones,
 * adaptadores SAP) y {@link com.poc.sap.customer.bootstrap} (controllers
 * REST) pertenecen al bounded context de sincronizacion. Si dashboard-customer
 * los necesitase, los reimplementa/duplica en su propio paquete
 * {@code com.poc.sap.dashboard.customer.*}. Esta regla rompe el build si
 * alguien cruza la frontera.
 */
@AnalyzeClasses(packages = "com.poc.sap.dashboard.customer", importOptions = ImportOption.DoNotIncludeTests.class)
class DashboardIsolationTest {

    @ArchTest
    static final ArchRule dashboard_does_not_depend_on_customer_application = noClasses()
            .that().resideInAPackage("com.poc.sap.dashboard.customer..")
            .should().dependOnClassesThat().resideInAPackage("com.poc.sap.customer.application..")
            .because("dashboard-customer es un bounded context aparte: la lectura se hace via Mongo+ES, no reusando use cases de customer-application");

    @ArchTest
    static final ArchRule dashboard_does_not_depend_on_customer_bootstrap = noClasses()
            .that().resideInAPackage("com.poc.sap.dashboard.customer..")
            .should().dependOnClassesThat().resideInAPackage("com.poc.sap.customer.bootstrap..")
            .because("dashboard-customer es un bounded context aparte: no importa los controllers REST del customer-app");

    @ArchTest
    static final ArchRule dashboard_does_not_depend_on_customer_domain = noClasses()
            .that().resideInAPackage("com.poc.sap.dashboard.customer..")
            .should().dependOnClassesThat().resideInAPackage("com.poc.sap.customer.domain..")
            .because("dashboard-customer duplica lo que necesita en su propio domain: no comparte tipos con customer");
}

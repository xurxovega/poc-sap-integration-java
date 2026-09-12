package com.poc.sap.article;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * sdd/common/seguridad-api.md R-3: cada endpoint declara quien puede llamarlo.
 * Un endpoint nuevo sin @PreAuthorize rompe el build (auditoria B4).
 */
@AnalyzeClasses(packages = "com.poc.sap.article", importOptions = ImportOption.DoNotIncludeTests.class)
class EndpointsDeclareAccessTest {

    @ArchTest
    static final ArchRule todo_endpoint_declara_su_acceso = methods()
            .that().areAnnotatedWith(GetMapping.class).or().areAnnotatedWith(PostMapping.class)
            .or().areAnnotatedWith(PutMapping.class).or().areAnnotatedWith(PatchMapping.class)
            .or().areAnnotatedWith(DeleteMapping.class).or().areAnnotatedWith(RequestMapping.class)
            .should().beAnnotatedWith(PreAuthorize.class)
            .because("quien puede llamar a cada endpoint se declara en el endpoint (seguridad-api R-3)");
}

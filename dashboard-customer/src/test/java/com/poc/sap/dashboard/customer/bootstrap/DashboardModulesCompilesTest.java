package com.poc.sap.dashboard.customer.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garantiza que el modulo compila y empaqueta antes de pasar al resto de
 * ArchUnit. Si el modulo no produce target/classes/bootstrap/web, las
 * clases referenciadas por los demas tests no existen y Maven los ejecutara
 * con una excepcion rara en lugar del fallo claro de "no compila".
 */
class DashboardModulesCompilesTest {

    @Test
    void dashboardCustomerModuleIsCompiled() throws IOException {
        Path classes = Paths.get("target/classes/com/poc/sap/dashboard/customer");
        assertThat(classes)
                .as("dashboard-customer debe compilar (target/classes/com/poc/sap/dashboard/customer). "
                        + "Ejecuta mvn compile antes de los tests")
                .exists();
        try (Stream<Path> files = Files.walk(classes)) {
            long count = files.filter(p -> p.toString().endsWith(".class")).count();
            assertThat(count).as("clases compiladas del modulo").isGreaterThan(0);
        }
    }

    @Test
    void bannerIsPresentAndAscii() throws IOException {
        Path banner = Paths.get("src/main/resources/banner.txt");
        assertThat(banner).exists();
        String text = Files.readString(banner, StandardCharsets.UTF_8);
        assertThat(text).isNotBlank();
        // Solo ASCII: la consola de Windows no renderiza bloques Unicode
        // (AGENTS.md Parte 3, banner por dominio).
        for (int i = 0; i < text.length(); i++) {
            assertThat((int) text.charAt(i))
                    .as("banner caracter %d (codepoint %d) no ASCII", i, (int) text.charAt(i))
                    .isLessThan(128);
        }
    }
}

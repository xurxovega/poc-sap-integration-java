package com.poc.sap.it;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La documentacion decia 211, 240, 245 y 250 tests segun el fichero (auditoria
 * A15). Este test hace que la cifra de TESTING.md sea la que declara el codigo:
 * si anades un @Test sin actualizarla, el build lo dice.
 *
 * <p>Cuenta anotaciones {@code @Test} declaradas en {@code src/test/java} de todos
 * los modulos del reactor. Es "declarados", no "ejecutados": los IT gateados por
 * Docker y los contract tests de failsafe tambien cuentan.
 */
class TestCountMatchesDocsTest {

    private static final Pattern TEST_ANNOTATION = Pattern.compile("(?m)^\\s*@Test\\b");
    private static final Pattern DOCUMENTED = Pattern.compile("\\*\\*(\\d+) tests\\*\\*");

    @Test
    void documentedTestCountMatchesDeclaredTests() throws IOException {
        Path root = Paths.get("").toAbsolutePath().resolve("..").normalize();   // it/ -> raiz del reactor
        long declared = countDeclaredTests(root);
        String testing = Files.readString(root.resolve("docs/testing/TESTING.md"), StandardCharsets.UTF_8);
        Matcher m = DOCUMENTED.matcher(testing);
        assertThat(m.find()).as("TESTING.md debe declarar la cifra como **N tests**").isTrue();
        long documented = Long.parseLong(m.group(1));

        assertThat(declared)
                .as("docs/testing/TESTING.md dice **%d tests** pero el codigo declara %d @Test. "
                    + "Actualiza la cifra en TESTING.md, QUICK_START.md y GUIA-PRUEBAS.md", documented, declared)
                .isEqualTo(documented);
    }

    static long countDeclaredTests(Path root) throws IOException {
        long total = 0;
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path tests = module.resolve("src/test/java");
                if (!Files.isDirectory(tests) || module.getFileName().toString().equals("sap-sdk-client")) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(tests)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        Matcher m = TEST_ANNOTATION.matcher(Files.readString(f, StandardCharsets.UTF_8));
                        while (m.find()) {
                            total++;
                        }
                    }
                }
            }
        }
        return total;
    }
}

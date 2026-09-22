package com.poc.sap.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests en rojo del {@link PiiMasker} que vive en {@code common.security}
 * (UI-001 H-1, sdd/common/seguridad-api.md R-4): antes solo enmascaraba
 * un {@code Customer} en el modulo customer; aqui se queda la version
 * portable sobre {@code String}, que es lo que el dashboard-customer reusa.
 *
 * <p>El modulo customer conserva una fachada {@code PiiMasker.mask(Customer)}
 * que delega en estos metodos para no romper a quien lo use.
 */
class PiiMaskerCommonTest {

    @Test
    void maskIbanKeepsLast4() {
        assertThat(PiiMasker.mask("ES7621000418401234560123"))
                .isEqualTo("********************0123");
    }

    @Test
    void maskTaxIdKeepsLast4() {
        // B12345678 (9 chars): quedan visibles los ultimos 4 (5678) y los otros
        // 5 se enmascaran. La regla es: |asteriscos| = longitud - 4.
        assertThat(PiiMasker.mask("B12345678"))
                .isEqualTo("*****5678");
    }

    @Test
    void maskVatNumberKeepsLast4() {
        // ESB12345678 (11 chars): visibles los ultimos 4, 7 asteriscos.
        assertThat(PiiMasker.mask("ESB12345678"))
                .isEqualTo("*******5678");
    }

    @Test
    void maskEmailKeepsInitialAndDomain() {
        assertThat(PiiMasker.maskEmail("carlos@ejemplo.es"))
                .isEqualTo("c****@ejemplo.es");
    }

    @Test
    void maskPhoneKeepsLast3() {
        // +34600000000 (12 chars) con keep=3: 9 asteriscos + 3 ultimos.
        assertThat(PiiMasker.maskPhone("+34600000000"))
                .isEqualTo("*********000");
    }

    @Test
    void maskFaxKeepsLast3() {
        // +34911111111 (12 chars) con keep=3: 9 asteriscos + 3 ultimos.
        assertThat(PiiMasker.maskPhone("+34911111111"))
                .isEqualTo("*********111");
    }

    @Test
    void maskBicPassthrough() {
        // BIC no se enmascara por convencion.
        assertThat(PiiMasker.mask("BBVAESMM")).isEqualTo("BBVAESMM");
    }

    @Test
    void maskNullReturnsNull() {
        assertThat(PiiMasker.mask((String) null)).isNull();
        assertThat(PiiMasker.maskEmail(null)).isNull();
        assertThat(PiiMasker.maskDetail(null)).isNull();
    }

    @Test
    void maskEmptyReturnsEmpty() {
        assertThat(PiiMasker.mask("")).isEqualTo("");
        assertThat(PiiMasker.maskEmail("")).isEqualTo("");
        assertThat(PiiMasker.maskDetail("")).isEqualTo("");
    }

    @Test
    void maskDetailMasksLongIdentifiersAndEmailsInFreeText() {
        String input = "IBAN rejected: ES7621000418401234560123, email carlos@ejemplo.es";
        String masked = PiiMasker.maskDetail(input);

        // Ambos quedan enmascarados
        assertThat(masked).doesNotContain("ES7621000418401234560123");
        assertThat(masked).doesNotContain("carlos@ejemplo.es");
        // El resto del texto (clave "rejected:") sigue legible
        assertThat(masked).contains("rejected:");
    }

    @Test
    void maskIbanEdgeCaseLessThan4KeepsAll() {
        // IBAN con menos de 4 caracteres visibles: no enmascara para no perder
        // la pista diagnostica (sdd/common/seguridad-api.md R-4).
        assertThat(PiiMasker.mask("AB")).isEqualTo("AB");
        assertThat(PiiMasker.mask("ES12")).isEqualTo("ES12");
    }
}

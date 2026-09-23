package com.poc.sap.common.security;

import java.util.regex.Pattern;

/**
 * Enmascara la informacion personal (PII) en cadenas sueltas (sdd/common/
 * seguridad-api.md R-4):
 *
 * <ul>
 *   <li>Identificadores largos (IBAN, NIF, vatNumber): 4 ultimos caracteres
 *       visibles.</li>
 *   <li>Telefonos y fax: 3 ultimos caracteres visibles.</li>
 *   <li>Email: inicial y dominio visibles.</li>
 *   <li>BIC y resto: pasa tal cual (no son dato personal en este dominio B2B).</li>
 * </ul>
 *
 * <p>Vive en {@code common.security} como utilidad portable, sin tocar un
 * modelo concreto: la version tipada sobre {@code Customer} la conserva el
 * modulo customer como fachada que delega aqui (UI-001 H-1).
 */
public final class PiiMasker {

    private PiiMasker() {}

    private static final int KEEP_LONG = 4;
    private static final int KEEP_PHONE = 3;

    /**
     * Enmascara un identificador largo (IBAN, NIF, vatNumber) dejando visibles
     * los ultimos 4 caracteres. Si la cadena tiene 4 o menos visibles, no se
     * enmascara (perderia la pista diagnostica). Si la cadena NO tiene digitos
     * (caso del BIC, todo letras), no se enmascara: BIC no es PII en este
     * dominio B2B y enmascararlo confunde al operador.
     */
    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (!hasDigit(value)) {
            return value;
        }
        String t = value.replace(" ", "");
        // Si la longitud efectiva (sin espacios) es < KEEP_LONG, no enmascara:
        // perderia la pista diagnostica (el caso del IBAN corto o un NIF que
        // llega truncado). BIC y todo-letra pasan por hasDigit() arriba.
        if (t.length() < KEEP_LONG) {
            return value;
        }
        return "*".repeat(t.length() - KEEP_LONG) + t.substring(t.length() - KEEP_LONG);
    }

    /** Variante con {@code keep} configurable: cuantos caracteres finales quedan visibles. */
    public static String mask(String value, int keep) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (keep <= 0) {
            return value;
        }
        if (!hasDigit(value)) {
            return value;
        }
        return last(value, keep);
    }

    /** Enmascara un telefono/fax dejando visibles los ultimos 3 caracteres. */
    public static String maskPhone(String value) {
        return last(value, KEEP_PHONE);
    }

    /** Enmascara un email dejando la inicial y el dominio (sdd/common/seguridad-api.md R-4). */
    public static String maskEmail(String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            return value;
        }
        int at = value.indexOf('@');
        if (at <= 0) {
            return value;
        }
        return value.charAt(0) + "****" + value.substring(at);
    }

    private static final Pattern EMAIL =
            Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}");
    /** Cadena larga con letras y digitos: IBAN, NIF, numero de BP. */
    private static final Pattern IDENTIFIER =
            Pattern.compile("\\b(?=[A-Za-z0-9]*[A-Za-z])(?=[A-Za-z0-9]*\\d)[A-Za-z0-9]{6,}\\b");

    /**
     * Enmascara la PII incrustada en un texto libre (motivo de error, detalle
     * de un ciclo). Deja intacto lo que no identifica a nadie: el codigo HTTP,
     * el nombre de la propiedad, el verbo.
     */
    public static String maskDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return detail;
        }
        String masked = EMAIL.matcher(detail).replaceAll(m -> maskEmail(m.group()));
        return IDENTIFIER.matcher(masked).replaceAll(m -> mask(m.group()));
    }

    /** Mascara los ultimos N caracteres de una cadena (sin espacios). */
    private static String last(String value, int keep) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String t = value.replace(" ", "");
        if (t.length() <= keep) {
            return "*".repeat(t.length());
        }
        return "*".repeat(t.length() - keep) + t.substring(t.length() - keep);
    }

    /** True si {@code value} contiene al menos un digito (ignora espacios). */
    private static boolean hasDigit(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                return true;
            }
        }
        return false;
    }
}

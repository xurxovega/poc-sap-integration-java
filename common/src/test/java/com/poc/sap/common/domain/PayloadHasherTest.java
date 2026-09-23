package com.poc.sap.common.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hash canonico del snapshot leido del legacy (ADR-0013;
 * sdd/common/contrato-mensaje-de-cambio.md AC-1, AC-2).
 *
 * <p>Con el mensaje fino el hash ya no viaja en el aviso de cambio: lo calcula
 * el consumidor sobre lo que acaba de leer. Tiene que ser determinista entre
 * instancias, entre arranques y entre versiones de la JVM.
 */
class PayloadHasherTest {

    record Inner(String city, String zip) {}
    record Agg(String id, int n, Status status, Inner inner, List<String> tags, Map<String, String> extra) {}
    enum Status { ACTIVE, INACTIVE }

    private static Agg sample() {
        return new Agg("C-1", 7, Status.ACTIVE, new Inner("Madrid", "28001"),
                List.of("a", "b"), Map.of("k1", "v1", "k2", "v2"));
    }

    /** AC-1: el mismo snapshot da siempre el mismo hash. */
    @Test
    void isDeterministicForTheSameSnapshot() {
        assertThat(PayloadHasher.hash(sample())).isEqualTo(PayloadHasher.hash(sample()));
    }

    /** AC-1: SHA-256 en hexadecimal minusculas, 64 caracteres. */
    @Test
    void isSha256HexLowercase() {
        assertThat(PayloadHasher.hash(sample())).matches("[0-9a-f]{64}");
    }

    /** AC-1: cambiar un solo campo de negocio cambia el hash. */
    @Test
    void changesWhenAnyFieldChanges() {
        String base = PayloadHasher.hash(sample());
        Agg other = new Agg("C-1", 7, Status.ACTIVE, new Inner("Madrid", "28002"),
                List.of("a", "b"), Map.of("k1", "v1", "k2", "v2"));
        assertThat(PayloadHasher.hash(other)).isNotEqualTo(base);
    }

    /** AC-2: el orden de iteracion de un mapa no altera el hash. */
    @Test
    void isStableAgainstMapIterationOrder() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("k1", "v1");
        a.put("k2", "v2");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("k2", "v2");
        b.put("k1", "v1");
        assertThat(PayloadHasher.hash(withExtra(a))).isEqualTo(PayloadHasher.hash(withExtra(b)));
    }

    /** AC-2: el orden de una lista SI es significativo (es dato de negocio). */
    @Test
    void listOrderIsSignificant() {
        Agg reversed = new Agg("C-1", 7, Status.ACTIVE, new Inner("Madrid", "28001"),
                List.of("b", "a"), Map.of("k1", "v1", "k2", "v2"));
        assertThat(PayloadHasher.hash(reversed)).isNotEqualTo(PayloadHasher.hash(sample()));
    }

    /** AC-2: un nulo es explicito y no se confunde con la cadena "null". */
    @Test
    void nullIsNotTheStringNull() {
        Agg withNull = new Agg("C-1", 7, null, null, List.of(), Map.of());
        Agg withText = new Agg("C-1", 7, null, new Inner("null", "null"), List.of(), Map.of());
        assertThat(PayloadHasher.hash(withNull)).isNotEqualTo(PayloadHasher.hash(withText));
    }

    /** AC-2: dos agregados con los mismos valores pero distinto campo no colisionan. */
    @Test
    void doesNotCollideWhenValuesMoveBetweenFields() {
        assertThat(PayloadHasher.hash(new Inner("a", "b")))
                .isNotEqualTo(PayloadHasher.hash(new Inner("b", "a")));
    }

    /** AC-1: el hash de null es estable y no explota. */
    @Test
    void hashesNullSnapshot() {
        assertThat(PayloadHasher.hash(null)).matches("[0-9a-f]{64}");
    }

    /** AC-3: hash de identidad para la baja, donde ya no hay snapshot que leer. */
    @Test
    void identityHashIsDeterministic() {
        assertThat(PayloadHasher.ofIdentity("customer", "C-1", "DELETE"))
                .isEqualTo(PayloadHasher.ofIdentity("customer", "C-1", "DELETE"))
                .isNotEqualTo(PayloadHasher.ofIdentity("customer", "C-2", "DELETE"));
    }

    private static Agg withExtra(Map<String, String> extra) {
        return new Agg("C-1", 7, Status.ACTIVE, new Inner("Madrid", "28001"), List.of("a", "b"), extra);
    }
}

package com.poc.sap.common.domain;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Hash canonico del snapshot leido del legacy (ADR-0013;
 * sdd/common/contrato-mensaje-de-cambio.md).
 *
 * <p>Con el <b>mensaje fino</b> el aviso de cambio ya no lleva datos: solo dice
 * QUE entidad cambio. El hash de idempotencia deja de venir en el mensaje y lo
 * calcula el consumidor sobre lo que acaba de leer del legacy. Para que dos
 * instancias lleguen al mismo numero, la forma canonica es fija:
 *
 * <ul>
 *   <li><b>record</b> → {@code Nombre(componente=valor,...)} en el orden de
 *       declaracion (estable, lo fija el compilador);</li>
 *   <li><b>Map</b> → claves ordenadas por su representacion textual, para que el
 *       orden de iteracion no altere el hash;</li>
 *   <li><b>Set</b> → elementos ordenados; <b>List</b>/array → orden preservado,
 *       porque en una lista el orden es dato de negocio;</li>
 *   <li><b>enum</b> → su {@code name()}; <b>Optional</b> → su contenido o nulo;</li>
 *   <li><b>null</b> → marca propia {@code ~} que ningun texto puede producir
 *       (los textos van entre comillas y escapados);</li>
 *   <li>el resto → {@code String.valueOf}.</li>
 * </ul>
 *
 * <p>Sobre esa cadena UTF-8 se aplica SHA-256 y se devuelve en hexadecimal
 * minusculas. Funcion pura: sin Spring, sin Jackson y sin estado.
 */
public final class PayloadHasher {

    private static final String NULL_MARK = "~";
    private static final int MAX_DEPTH = 32;

    private PayloadHasher() {
    }

    /** Hash de idempotencia del snapshot: SHA-256 hex sobre su forma canonica. */
    public static String hash(Object snapshot) {
        return sha256Hex(canonicalForm(snapshot));
    }

    /**
     * Hash cuando no hay snapshot que leer: la baja, donde la fila ya no esta en
     * el legacy y la identidad del cambio es todo lo que hay (ADR-0013).
     */
    public static String ofIdentity(String domain, String entityId, String operation) {
        return sha256Hex(canonicalForm(List.of(
                String.valueOf(domain), String.valueOf(entityId), String.valueOf(operation))));
    }

    /** Forma canonica legible; expuesta para poder diagnosticar una diferencia de hash. */
    public static String canonicalForm(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value, 0);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Snapshot demasiado anidado para el hash canonico");
        }
        switch (value) {
            case null -> sb.append(NULL_MARK);
            case Optional<?> o -> append(sb, o.orElse(null), depth);
            case CharSequence s -> quote(sb, s.toString());
            case Enum<?> e -> sb.append("E(").append(e.name()).append(')');
            case Number n -> sb.append("N(").append(n).append(')');
            case Boolean b -> sb.append("B(").append(b).append(')');
            case Map<?, ?> m -> appendMap(sb, m, depth);
            case Set<?> s -> appendSet(sb, s, depth);
            case Collection<?> c -> appendList(sb, c, depth);
            case Object[] a -> appendList(sb, List.of(a), depth);
            default -> appendObject(sb, value, depth);
        }
    }

    private static void appendObject(StringBuilder sb, Object value, int depth) {
        Class<?> type = value.getClass();
        if (!type.isRecord()) {
            sb.append("V(").append(value).append(')');
            return;
        }
        sb.append(type.getSimpleName()).append('(');
        RecordComponent[] components = type.getRecordComponents();
        for (int i = 0; i < components.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(components[i].getName()).append('=');
            append(sb, read(value, components[i]), depth + 1);
        }
        sb.append(')');
    }

    private static Object read(Object owner, RecordComponent component) {
        try {
            return component.getAccessor().invoke(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "No se pudo leer el componente " + component.getName() + " para el hash canonico", e);
        }
    }

    private static void appendMap(StringBuilder sb, Map<?, ?> map, int depth) {
        Map<String, Object> sorted = new TreeMap<>();
        map.forEach((k, v) -> sorted.put(canonicalForm(k), v));
        sb.append("M{");
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=');
            append(sb, e.getValue(), depth + 1);
        }
        sb.append('}');
    }

    private static void appendSet(StringBuilder sb, Set<?> set, int depth) {
        List<String> items = new ArrayList<>(set.size());
        for (Object item : set) {
            items.add(canonicalForm(item));
        }
        items.sort(String::compareTo);
        sb.append("S[").append(String.join(",", items)).append(']');
    }

    private static void appendList(StringBuilder sb, Collection<?> items, int depth) {
        sb.append("L[");
        boolean first = true;
        for (Object item : items) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            append(sb, item, depth + 1);
        }
        sb.append(']');
    }

    private static void quote(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"' || ch == '\\') {
                sb.append('\\');
            }
            sb.append(ch);
        }
        sb.append('"');
    }

    private static String sha256Hex(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
        }
    }
}

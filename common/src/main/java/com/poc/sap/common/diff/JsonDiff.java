package com.poc.sap.common.diff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Diff estructural entre dos objetos serializables a JSON.
 *
 * <p>Aplana ambos objetos a rutas con notacion de puntos
 * ({@code address.city}, {@code banking.mandateIds[0]}) y devuelve solo las
 * rutas cuyo valor cambia: {@code before=null} indica campo añadido,
 * {@code after=null} campo eliminado.
 *
 * <p>Uso: comparar dos versiones del historico (Elasticsearch) de una entidad
 * para auditar que cambio realmente entre dos envios a SAP.
 */
public final class JsonDiff {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonDiff() {}

    /** Cambio de valor de una ruta: {@code before} → {@code after}. */
    public record Change(String before, String after) {}

    public static Map<String, Change> diff(Object before, Object after) {
        Map<String, String> flatBefore = flatten(MAPPER.valueToTree(before));
        Map<String, String> flatAfter = flatten(MAPPER.valueToTree(after));

        Map<String, Change> changes = new TreeMap<>();
        for (String path : flatBefore.keySet()) {
            String b = flatBefore.get(path);
            String a = flatAfter.get(path);
            if (!Objects.equals(b, a)) {
                changes.put(path, new Change(b, a));
            }
        }
        for (String path : flatAfter.keySet()) {
            if (!flatBefore.containsKey(path)) {
                changes.put(path, new Change(null, flatAfter.get(path)));
            }
        }
        return changes;
    }

    private static Map<String, String> flatten(JsonNode root) {
        Map<String, String> out = new LinkedHashMap<>();
        flattenInto("", root, out);
        return out;
    }

    private static void flattenInto(String prefix, JsonNode node, Map<String, String> out) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String path = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
                flattenInto(path, field.getValue(), out);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flattenInto(prefix + "[" + i + "]", node.get(i), out);
            }
        } else {
            out.put(prefix, node.isNull() ? null : node.asText());
        }
    }
}

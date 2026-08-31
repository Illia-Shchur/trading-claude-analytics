package com.tradinganalytics.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** JavaScript-value compatibility helpers shared by the reporting ports. */
final class ReportingJson {
    static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final ObjectMapper JSON = new ObjectMapper();

    private ReportingJson() {}

    static JsonNode get(JsonNode node, String... path) {
        JsonNode cursor = node;
        for (String segment : path) {
            if (cursor == null || !cursor.isObject()) return MissingNode.getInstance();
            cursor = cursor.get(segment);
        }
        return cursor == null ? MissingNode.getInstance() : cursor;
    }

    static ObjectNode object(JsonNode node, String... path) {
        JsonNode value = get(node, path);
        return value.isObject() ? (ObjectNode) value : NODES.objectNode();
    }

    static ArrayNode array(JsonNode node, String... path) {
        JsonNode value = get(node, path);
        return value.isArray() ? (ArrayNode) value : NODES.arrayNode();
    }

    static boolean present(JsonNode value) {
        return value != null && !value.isMissingNode() && !value.isNull();
    }

    static boolean hasValue(JsonNode value) {
        return present(value) && !(value.isTextual() && value.textValue().isEmpty());
    }

    static boolean truthy(JsonNode value) {
        if (!present(value)) return false;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.doubleValue() != 0 && !Double.isNaN(value.doubleValue());
        if (value.isTextual()) return !value.textValue().isEmpty();
        return true; // arrays and objects are truthy in JavaScript, including empty ones
    }

    static JsonNode or(JsonNode value, JsonNode fallback) {
        return truthy(value) ? value : fallback;
    }

    static JsonNode nullish(JsonNode value, JsonNode fallback) {
        return present(value) ? value : fallback;
    }

    static String string(JsonNode value) {
        if (value == null || value.isMissingNode()) return "undefined";
        if (value.isNull()) return "null";
        if (value.isTextual()) return value.textValue();
        if (value.isBoolean()) return value.booleanValue() ? "true" : "false";
        if (value.isNumber()) return CanonicalJson.canonicalize(value);
        if (value.isArray()) {
            List<String> parts = new ArrayList<>();
            value.forEach(item -> parts.add(item.isNull() || item.isMissingNode() ? "" : string(item)));
            return String.join(",", parts);
        }
        return "[object Object]";
    }

    static String stringOr(JsonNode value, String fallback) {
        return truthy(value) ? string(value) : fallback;
    }

    static String nullishString(JsonNode value, String fallback) {
        return present(value) ? string(value) : fallback;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = get(node, field);
        return value.isTextual() ? value.textValue() : null;
    }

    static Double numberOrNull(JsonNode value) {
        return value != null && value.isNumber() && Double.isFinite(value.doubleValue())
                ? value.doubleValue() : null;
    }

    static List<Map.Entry<String, JsonNode>> entries(JsonNode value) {
        List<Map.Entry<String, JsonNode>> integerKeys = new ArrayList<>();
        List<Map.Entry<String, JsonNode>> otherKeys = new ArrayList<>();
        if (value != null && value.isObject()) value.fields().forEachRemaining(entry -> {
            if (arrayIndex(entry.getKey()) != null) integerKeys.add(entry); else otherKeys.add(entry);
        });
        integerKeys.sort(java.util.Comparator.comparingLong(entry -> arrayIndex(entry.getKey())));
        integerKeys.addAll(otherKeys);
        return integerKeys;
    }

    private static Long arrayIndex(String key) {
        if (key == null || !key.matches("(?:0|[1-9][0-9]*)")) return null;
        try {
            long value = Long.parseLong(key);
            return value <= 4_294_967_294L ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static List<JsonNode> elements(JsonNode value) {
        List<JsonNode> result = new ArrayList<>();
        if (value != null && value.isArray()) value.forEach(result::add);
        return result;
    }

    static boolean includesInt(JsonNode array, int expected) {
        if (array == null || !array.isArray()) return false;
        for (JsonNode value : array) if (value.isInt() && value.intValue() == expected) return true;
        return false;
    }

    static boolean own(JsonNode object, String key) {
        return object != null && object.isObject() && object.has(key);
    }

    static String jsonStringify(JsonNode value) {
        if (value == null || value.isMissingNode()) return "undefined";
        try {
            return JSON.writeValueAsString(value);
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    static ObjectNode objectOf(Object... keysAndValues) {
        ObjectNode result = NODES.objectNode();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            String key = (String) keysAndValues[index];
            Object raw = keysAndValues[index + 1];
            if (raw instanceof JsonNode node) result.set(key, node.deepCopy());
            else result.set(key, JSON.valueToTree(raw));
        }
        return result;
    }

    static Iterator<String> fieldNames(JsonNode object) {
        return object != null && object.isObject() ? object.fieldNames() : List.<String>of().iterator();
    }
}

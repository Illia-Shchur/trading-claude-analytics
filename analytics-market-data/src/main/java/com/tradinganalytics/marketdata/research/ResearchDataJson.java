package com.tradinganalytics.marketdata.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Small JSON operations shared by the research-lake collaborators. */
final class ResearchDataJson {
    private ResearchDataJson() {
    }

    static void putNullable(ObjectNode target, String key, Object value) {
        if (value == null) {
            target.putNull(key);
        } else if (value instanceof JsonNode node) {
            target.set(key, node.deepCopy());
        } else {
            target.put(key, String.valueOf(value));
        }
    }

    static Object nodeOrText(JsonNode node, String fallback) {
        return node != null && !node.isNull() ? node : fallback;
    }

    static String textOr(JsonNode node, String fallback) {
        return node != null && !node.isNull() ? node.asText() : fallback;
    }

    static JsonNode firstPresent(JsonNode row, String... names) {
        if (row == null) {
            return null;
        }
        for (String name : names) {
            if (row.has(name) && !row.get(name).isNull()) {
                return row.get(name);
            }
        }
        return null;
    }

    static void copyIfPresent(JsonNode source, ObjectNode target, String... names) {
        for (String name : names) {
            if (source.has(name)) {
                target.set(name, source.get(name).deepCopy());
            }
        }
    }

    static ArrayNode array(JsonNode value) {
        return value instanceof ArrayNode array ? array : JsonHashes.mapper().createArrayNode();
    }

    static List<JsonNode> concat(JsonNode left, JsonNode right) {
        List<JsonNode> output = new ArrayList<>();
        if (left != null && left.isArray()) {
            left.forEach(output::add);
        }
        if (right != null && right.isArray()) {
            right.forEach(output::add);
        }
        return output;
    }

    static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    static boolean isHash(String value) {
        return value != null && Pattern.matches("^[a-f0-9]{64}$", value);
    }

    static List<ObjectNode> immutableNodes(List<ObjectNode> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(ObjectNode::deepCopy).toList();
    }

    static String valueOr(String value, String fallback) {
        return value == null ? fallback : value;
    }

    static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}

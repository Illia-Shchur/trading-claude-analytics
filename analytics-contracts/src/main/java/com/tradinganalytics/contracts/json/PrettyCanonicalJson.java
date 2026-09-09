package com.tradinganalytics.contracts.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Recursively sorted, two-space JSON used by committed human-readable files.
 * This is the Java equivalent of {@code tools/lib.mjs canonicalJSON} and is
 * deliberately distinct from minified RFC 8785/JCS payloads.
 */
public final class PrettyCanonicalJson {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PrettyCanonicalJson() {
    }

    public static String write(Object value) {
        JsonNode tree = value instanceof JsonNode node ? node : JSON.valueToTree(value);
        var output = new StringBuilder();
        append(tree, 0, output);
        return output.append('\n').toString();
    }

    private static void append(JsonNode value, int depth, StringBuilder output) {
        if (value.isObject()) {
            appendObject(value, depth, output);
        } else if (value.isArray()) {
            appendArray(value, depth, output);
        } else {
            output.append(CanonicalJson.canonicalize(value));
        }
    }

    private static void appendObject(JsonNode value, int depth, StringBuilder output) {
        List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
        value.fields().forEachRemaining(fields::add);
        fields.sort(Map.Entry.comparingByKey(Comparator.naturalOrder()));
        if (fields.isEmpty()) {
            output.append("{}");
            return;
        }
        output.append("{\n");
        for (int index = 0; index < fields.size(); index++) {
            Map.Entry<String, JsonNode> field = fields.get(index);
            indent(depth + 1, output);
            output.append(CanonicalJson.canonicalize(TextNode.valueOf(field.getKey()))).append(": ");
            append(field.getValue(), depth + 1, output);
            output.append(index + 1 == fields.size() ? '\n' : ",\n");
        }
        indent(depth, output);
        output.append('}');
    }

    private static void appendArray(JsonNode value, int depth, StringBuilder output) {
        if (value.isEmpty()) {
            output.append("[]");
            return;
        }
        output.append("[\n");
        for (int index = 0; index < value.size(); index++) {
            indent(depth + 1, output);
            append(value.get(index), depth + 1, output);
            output.append(index + 1 == value.size() ? '\n' : ",\n");
        }
        indent(depth, output);
        output.append(']');
    }

    private static void indent(int depth, StringBuilder output) {
        output.append("  ".repeat(depth));
    }
}

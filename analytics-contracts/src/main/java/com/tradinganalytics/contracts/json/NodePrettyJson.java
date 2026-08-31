package com.tradinganalytics.contracts.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Byte-compatible two-space {@code JSON.stringify(value, null, 2) + "\n"} for JSON trees. */
public final class NodePrettyJson {
    private NodePrettyJson() {
    }

    public static String write(JsonNode value) {
        StringBuilder output = new StringBuilder();
        append(value, 0, output);
        return output.append('\n').toString();
    }

    private static void append(JsonNode value, int depth, StringBuilder output) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            output.append("null");
            return;
        }
        if (value.isObject()) {
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            value.fields().forEachRemaining(fields::add);
            if (fields.isEmpty()) {
                output.append("{}");
                return;
            }
            output.append("{\n");
            for (int index = 0; index < fields.size(); index++) {
                Map.Entry<String, JsonNode> field = fields.get(index);
                indent(depth + 1, output);
                output.append(quote(field.getKey())).append(": ");
                append(field.getValue(), depth + 1, output);
                output.append(index + 1 == fields.size() ? '\n' : ",\n");
            }
            indent(depth, output);
            output.append('}');
            return;
        }
        if (value.isArray()) {
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
            return;
        }
        // RFC 8785 deliberately adopts ECMAScript's JSON number and string
        // serialization.  Reuse it for scalar leaves so values such as 300000
        // never leak Jackson's BigDecimal scientific notation ("3E+5").
        output.append(CanonicalJson.canonicalize(value));
    }

    private static String quote(String value) {
        return CanonicalJson.canonicalize(TextNode.valueOf(value));
    }

    private static void indent(int depth, StringBuilder output) {
        output.append("  ".repeat(depth));
    }
}

package com.tradinganalytics.contracts.json;

import com.fasterxml.jackson.databind.JsonNode;
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
        if (value.isTextual()) {
            output.append(quote(value.textValue()));
            return;
        }
        // RFC 8785 deliberately adopts ECMAScript's JSON number serialization.
        // Reuse it for non-string scalar leaves so values such as 300000 never
        // leak Jackson's BigDecimal scientific notation ("3E+5"). Strings use
        // the serializer below because JSON.stringify escapes lone surrogates,
        // while the stricter I-JSON canonicalizer correctly rejects them.
        output.append(CanonicalJson.canonicalize(value));
    }

    private static String quote(String value) {
        StringBuilder output = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> appendUnescapedOrUnicodeEscape(value, index, current, output);
            }
            if (Character.isHighSurrogate(current)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                output.append(value.charAt(++index));
            }
        }
        return output.append('"').toString();
    }

    private static void appendUnescapedOrUnicodeEscape(
            String value,
            int index,
            char current,
            StringBuilder output) {
        boolean loneHighSurrogate = Character.isHighSurrogate(current)
                && (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1)));
        if (current <= 0x1f || loneHighSurrogate || Character.isLowSurrogate(current)) {
            appendUnicodeEscape(current, output);
        } else {
            output.append(current);
        }
    }

    private static void appendUnicodeEscape(char value, StringBuilder output) {
        output.append("\\u");
        for (int shift = 12; shift >= 0; shift -= 4) {
            output.append(Character.forDigit((value >> shift) & 0xf, 16));
        }
    }

    private static void indent(int depth, StringBuilder output) {
        output.append("  ".repeat(depth));
    }
}

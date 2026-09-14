package com.tradinganalytics.contracts.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.erdtman.jcs.JsonCanonicalizer;

/** RFC 8785 JSON Canonicalization Scheme helpers compatible with npm canonicalize. */
public final class CanonicalJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] LINE_FEED = {'\n'};
    private static final byte[] NULL_BYTES = {'n', 'u', 'l', 'l'};
    private static final byte[] TRUE_BYTES = {'t', 'r', 'u', 'e'};
    private static final byte[] FALSE_BYTES = {'f', 'a', 'l', 's', 'e'};
    private static final byte[] HEX = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private CanonicalJson() {
    }

    /** Returns the canonical payload without a trailing newline. */
    public static String canonicalize(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String string) {
            return new String(quotedStringBytes(string), StandardCharsets.UTF_8);
        }
        if (value instanceof Boolean bool) {
            return bool ? "true" : "false";
        }

        JsonNode tree = toTree(value);
        if (tree.isTextual()) {
            return new String(quotedStringBytes(tree.textValue()), StandardCharsets.UTF_8);
        }
        verifyIJsonValue(tree);
        if (tree.isNull()) {
            return "null";
        }
        if (tree.isBoolean()) {
            return tree.booleanValue() ? "true" : "false";
        }
        return canonicalizeStructured(tree);
    }

    private static String canonicalizeStructured(JsonNode tree) {
        try {
            // The reference Java JCS decoder accepts object/array roots only, while npm
            // canonicalize (and JSON itself) also accepts primitives. A one-element wrapper
            // exercises the identical serializer for every JSON value; remove only that
            // synthetic pair of brackets afterward.
            String wrapped = '[' + MAPPER.writeValueAsString(tree) + ']';
            String encoded = new JsonCanonicalizer(wrapped).getEncodedString();
            return encoded.substring(1, encoded.length() - 1);
        } catch (IOException exception) {
            throw new IllegalArgumentException("JSON value is not canonicalizable", exception);
        }
    }

    /** Parses strict JSON and returns the equivalent canonical payload. */
    public static String canonicalizeJson(String strictJson) {
        return canonicalize(StrictJson.parse(strictJson));
    }

    /** Returns canonical UTF-8 bytes without a trailing newline. */
    public static byte[] canonicalBytes(Object value) {
        if (value == null) {
            return NULL_BYTES.clone();
        }
        if (value instanceof String string) {
            return quotedStringBytes(string);
        }
        if (value instanceof Boolean bool) {
            return (bool ? TRUE_BYTES : FALSE_BYTES).clone();
        }

        JsonNode tree = toTree(value);
        if (tree.isTextual()) {
            return quotedStringBytes(tree.textValue());
        }
        verifyIJsonValue(tree);
        if (tree.isNull()) {
            return NULL_BYTES.clone();
        }
        if (tree.isBoolean()) {
            return (tree.booleanValue() ? TRUE_BYTES : FALSE_BYTES).clone();
        }
        return canonicalizeStructured(tree).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] quotedStringBytes(String value) {
        int byteLength = 2;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (needsShortEscape(current)) {
                byteLength += 2;
            } else if (current < 0x20) {
                byteLength += 6;
            } else if (current <= 0x7f) {
                byteLength++;
            } else if (current <= 0x7ff) {
                byteLength += 2;
            } else if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("Lone surrogate is not canonicalizable");
                }
                byteLength += 4;
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("Lone surrogate is not canonicalizable");
            } else {
                byteLength += 3;
            }
        }

        byte[] output = new byte[byteLength];
        int offset = 0;
        output[offset++] = '"';
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> {
                    output[offset++] = '\\';
                    output[offset++] = '"';
                }
                case '\\' -> {
                    output[offset++] = '\\';
                    output[offset++] = '\\';
                }
                case '\b' -> {
                    output[offset++] = '\\';
                    output[offset++] = 'b';
                }
                case '\t' -> {
                    output[offset++] = '\\';
                    output[offset++] = 't';
                }
                case '\n' -> {
                    output[offset++] = '\\';
                    output[offset++] = 'n';
                }
                case '\f' -> {
                    output[offset++] = '\\';
                    output[offset++] = 'f';
                }
                case '\r' -> {
                    output[offset++] = '\\';
                    output[offset++] = 'r';
                }
                default -> {
                    if (current < 0x20) {
                        output[offset++] = '\\';
                        output[offset++] = 'u';
                        output[offset++] = '0';
                        output[offset++] = '0';
                        output[offset++] = HEX[(current >>> 4) & 0x0f];
                        output[offset++] = HEX[current & 0x0f];
                    } else if (current <= 0x7f) {
                        output[offset++] = (byte) current;
                    } else if (current <= 0x7ff) {
                        output[offset++] = (byte) (0xc0 | (current >>> 6));
                        output[offset++] = (byte) (0x80 | (current & 0x3f));
                    } else if (Character.isHighSurrogate(current)) {
                        int codePoint = Character.toCodePoint(current, value.charAt(index + 1));
                        output[offset++] = (byte) (0xf0 | (codePoint >>> 18));
                        output[offset++] = (byte) (0x80 | ((codePoint >>> 12) & 0x3f));
                        output[offset++] = (byte) (0x80 | ((codePoint >>> 6) & 0x3f));
                        output[offset++] = (byte) (0x80 | (codePoint & 0x3f));
                        index++;
                    } else {
                        output[offset++] = (byte) (0xe0 | (current >>> 12));
                        output[offset++] = (byte) (0x80 | ((current >>> 6) & 0x3f));
                        output[offset++] = (byte) (0x80 | (current & 0x3f));
                    }
                }
            }
        }
        output[offset] = '"';
        return output;
    }

    private static boolean needsShortEscape(char value) {
        return value == '"' || value == '\\' || value == '\b' || value == '\t'
                || value == '\n' || value == '\f' || value == '\r';
    }

    /** Returns the canonical payload followed by exactly one LF. */
    public static String canonicalJson(Object value) {
        return canonicalize(value) + '\n';
    }

    /** Returns canonical UTF-8 bytes followed by exactly one LF byte. */
    public static byte[] canonicalJsonBytes(Object value) {
        byte[] payload = canonicalBytes(value);
        byte[] output = new byte[payload.length + 1];
        System.arraycopy(payload, 0, output, 0, payload.length);
        output[payload.length] = LINE_FEED[0];
        return output;
    }

    /** Node-compatible alias used by report-machine callers. */
    public static String canonicalReportPayload(Object value) {
        return canonicalize(value);
    }

    /** Node-compatible alias used by report-machine file writers. */
    public static String canonicalReportJson(Object value) {
        return canonicalJson(value);
    }

    /** Exact-name compatibility alias for the JavaScript canonicalReportJSON export. */
    public static String canonicalReportJSON(Object value) {
        return canonicalJson(value);
    }

    private static JsonNode toTree(Object value) {
        if (value instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        try {
            return MAPPER.valueToTree(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("JSON value is not canonicalizable", exception);
        }
    }

    private static void verifyIJsonValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isPojo() || node.isBinary()) {
            throw new IllegalArgumentException("JSON value is not canonicalizable");
        }
        if (node.isNumber() && !Double.isFinite(node.doubleValue())) {
            throw new IllegalArgumentException("NaN and Infinity are not canonicalizable");
        }
        if (node.isTextual()) {
            verifyNoLoneSurrogate(node.textValue());
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                verifyNoLoneSurrogate(field.getKey());
                verifyIJsonValue(field.getValue());
            }
            return;
        }
        if (node.isArray()) {
            node.forEach(CanonicalJson::verifyIJsonValue);
        }
    }

    private static void verifyNoLoneSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("Lone surrogate is not canonicalizable");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("Lone surrogate is not canonicalizable");
            }
        }
    }
}

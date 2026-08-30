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

    private CanonicalJson() {
    }

    /** Returns the canonical payload without a trailing newline. */
    public static String canonicalize(Object value) {
        JsonNode tree = toTree(value);
        verifyIJsonValue(tree);
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
        return canonicalize(value).getBytes(StandardCharsets.UTF_8);
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

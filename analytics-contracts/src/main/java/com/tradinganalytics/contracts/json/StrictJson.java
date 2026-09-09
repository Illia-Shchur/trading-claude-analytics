package com.tradinganalytics.contracts.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Strict JSON parsing at trust and contract boundaries. */
public final class StrictJson {
    private static final String DEFAULT_LABEL = "JSON";
    private static final ObjectMapper MAPPER = new ObjectMapper(strictFactory())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private StrictJson() {
    }

    public static JsonNode parse(String text) {
        return parse(text, DEFAULT_LABEL);
    }

    /** Exact-name compatibility alias for the JavaScript parseStrictJSON export. */
    public static JsonNode parseStrictJSON(String text) {
        return parse(text, DEFAULT_LABEL);
    }

    /** Exact-name compatibility alias for the JavaScript parseStrictJSON export. */
    public static JsonNode parseStrictJSON(String text, String label) {
        return parse(text, label);
    }

    public static JsonNode parse(String text, String label) {
        String effectiveLabel = normalizeLabel(label);
        if (text == null) {
            throw new StrictJsonException(effectiveLabel, "input must be UTF-8 text");
        }
        try {
            JsonNode value = MAPPER.readTree(text);
            if (value.isMissingNode()) {
                throw new StrictJsonException(effectiveLabel, text.length(), "parser error unexpected end of input", null);
            }
            return value;
        } catch (StrictJsonException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw invalid(effectiveLabel, exception);
        }
    }

    public static JsonNode parse(byte[] utf8) {
        return parse(utf8, DEFAULT_LABEL);
    }

    public static JsonNode parse(byte[] utf8, String label) {
        String effectiveLabel = normalizeLabel(label);
        if (utf8 == null) {
            throw new StrictJsonException(effectiveLabel, "input must be UTF-8 text");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8))
                    .toString();
            return parse(text, effectiveLabel);
        } catch (CharacterCodingException exception) {
            throw new StrictJsonException(effectiveLabel, "input must be UTF-8 text");
        }
    }

    private static StrictJsonException invalid(String label, JsonProcessingException exception) {
        String original = exception.getOriginalMessage();
        if (original != null && original.startsWith("Duplicate field '")) {
            int start = "Duplicate field '".length();
            int end = original.lastIndexOf('\'');
            String key = original.substring(start, end);
            // The Node oracle intentionally reports duplicate-key failures at offset zero.
            return new StrictJsonException(label, 0, "duplicate key " + key, exception);
        }
        JsonLocation location = exception.getLocation();
        long offset = location == null || location.getCharOffset() < 0 ? 0 : location.getCharOffset();
        String detail = original == null || original.isBlank() ? "parser error" : "parser error " + original;
        return new StrictJsonException(label, offset, detail, exception);
    }

    private static String normalizeLabel(String label) {
        return label == null || label.isBlank() ? DEFAULT_LABEL : label;
    }

    private static JsonFactory strictFactory() {
        return JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                .disable(JsonReadFeature.ALLOW_YAML_COMMENTS)
                .disable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                .disable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
                .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                .disable(JsonReadFeature.ALLOW_MISSING_VALUES)
                .disable(JsonReadFeature.ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS)
                .disable(JsonReadFeature.ALLOW_TRAILING_DECIMAL_POINT_FOR_NUMBERS)
                .disable(JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS)
                .disable(JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS)
                .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                .disable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
                .disable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .build();
    }
}

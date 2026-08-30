package com.tradinganalytics.infrastructure.security;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Deterministic SHA-256 and canonical JSON helpers shared by custody records. */
public final class JsonHashes {
    private static final Pattern SHA_256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final ObjectMapper MAPPER = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private JsonHashes() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(Path path) {
        try {
            return sha256(Files.readAllBytes(path));
        } catch (IOException error) {
            throw new CustodyException("cannot hash file: " + path, error);
        }
    }

    public static byte[] canonicalBytes(Object value) {
        JsonNode node = value instanceof JsonNode jsonNode ? jsonNode : MAPPER.valueToTree(value);
        return CanonicalJson.canonicalBytes(node);
    }

    public static String canonicalString(Object value) {
        return new String(canonicalBytes(value), StandardCharsets.UTF_8);
    }

    public static String canonicalSha256(Object value) {
        return sha256(canonicalBytes(value));
    }

    public static String ownHash(JsonNode value) {
        return ownHash(value, "content_sha256");
    }

    public static String ownHash(JsonNode value, String field) {
        JsonNode copy = value.deepCopy();
        if (copy instanceof ObjectNode object) {
            object.remove(field);
        }
        return canonicalSha256(copy);
    }

    public static JsonNode parse(byte[] bytes, String label) {
        try {
            return MAPPER.readTree(bytes);
        } catch (IOException error) {
            throw new CustodyException(label + " is not valid JSON: " + error.getMessage(), error);
        }
    }

    public static String requireSha256(Object value, String label) {
        String text = value == null ? "" : String.valueOf(value);
        if (!SHA_256.matcher(text).matches()) {
            throw new CustodyException(label + " must be a SHA-256 hash");
        }
        return text;
    }

    public static boolean isSha256(Object value) {
        return value != null && SHA_256.matcher(String.valueOf(value)).matches();
    }

}

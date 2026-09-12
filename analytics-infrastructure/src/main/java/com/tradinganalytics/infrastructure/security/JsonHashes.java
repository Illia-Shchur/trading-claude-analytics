package com.tradinganalytics.infrastructure.security;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestOutputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Deterministic SHA-256 and canonical JSON helpers shared by custody records. */
public final class JsonHashes {
    private static final Pattern SHA_256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final int FILE_HASH_BUFFER_SIZE = 16 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private JsonHashes() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = sha256Digest();
            byte[] buffer = new byte[FILE_HASH_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
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
        JsonNode node = value instanceof JsonNode jsonNode ? jsonNode : MAPPER.valueToTree(value);
        return canonicalSha256Streaming(node == null ? NullNode.instance : node);
    }

    /**
     * Computes a canonical JSON SHA-256 without materializing the complete
     * canonical payload. Scalar encoding is delegated to the pinned JCS helper
     * so this has the same RFC 8785 bytes as {@link #canonicalSha256(Object)}.
     */
    public static String canonicalSha256Streaming(JsonNode value) {
        Objects.requireNonNull(value);
        MessageDigest digest = sha256Digest();
        writeCanonical(value, digest, true, java.util.Set.of());
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Streaming equivalent of {@link #ownHash(JsonNode)}. */
    public static String ownHashStreaming(JsonNode value) {
        Objects.requireNonNull(value);
        if (!(value instanceof ObjectNode)) return canonicalSha256Streaming(value);
        MessageDigest digest = sha256Digest();
        writeCanonical(value, digest, true, java.util.Set.of("content_sha256"));
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Streaming SHA-256 of Jackson's compact JSON serialization. */
    public static String serializedSha256Streaming(JsonNode value) {
        Objects.requireNonNull(value);
        MessageDigest digest = sha256Digest();
        try (OutputStream output = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
            MAPPER.writeValue(output, value);
        } catch (IOException error) {
            throw new CustodyException("cannot serialize JSON value", error);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String ownHash(JsonNode value) {
        return ownHash(value, "content_sha256");
    }

    public static String ownHash(JsonNode value, String field) {
        Objects.requireNonNull(value);
        if (!(value instanceof ObjectNode object)) {
            return canonicalSha256(value);
        }
        ObjectNode copy = MAPPER.createObjectNode();
        object.fields().forEachRemaining(entry -> {
            if (!Objects.equals(field, entry.getKey())) {
                copy.set(entry.getKey(), entry.getValue());
            }
        });
        return canonicalSha256(copy);
    }

    private static void writeCanonical(JsonNode node, MessageDigest digest, boolean root,
            java.util.Set<String> rootExcludedFields) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.removeIf(name -> root && rootExcludedFields.contains(name));
            names.sort(String::compareTo);
            digest.update((byte) '{');
            for (int index = 0; index < names.size(); index++) {
                if (index != 0) digest.update((byte) ',');
                String name = names.get(index);
                digest.update(CanonicalJson.canonicalBytes(name));
                digest.update((byte) ':');
                writeCanonical(node.get(name), digest, false, rootExcludedFields);
            }
            digest.update((byte) '}');
        } else if (node.isArray()) {
            digest.update((byte) '[');
            for (int index = 0; index < node.size(); index++) {
                if (index != 0) digest.update((byte) ',');
                writeCanonical(node.get(index), digest, false, rootExcludedFields);
            }
            digest.update((byte) ']');
        } else {
            digest.update(CanonicalJson.canonicalBytes(node));
        }
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

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

}

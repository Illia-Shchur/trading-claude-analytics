package com.tradinganalytics.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.research.v5.StrategyProspectiveV5;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Small, side-effect-aware JSON helpers shared by workflow mode handlers. */
final class StrategyV5WorkflowJson {
    static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");

    private StrategyV5WorkflowJson() {}

    static ObjectNode object() {
        return JsonHashes.mapper().createObjectNode();
    }

    static ObjectNode object(JsonNode value, String label) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        return (ObjectNode) value;
    }

    static ObjectNode readObject(Path path) {
        return object(readJson(path), path.toString());
    }

    static JsonNode readJson(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("file is missing: " + path);
        }
        try {
            return JsonHashes.parse(
                    com.tradinganalytics.infrastructure.security.PathConfinement
                            .readSinglyLinkedFile(path, "JSON artifact"), path.toString());
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("JSON artifact is invalid: " + path, error);
        }
    }

    static ObjectNode optionalObject(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            return readObject(path);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static ObjectNode optionalObject(String value, Path work) {
        if (value == null || value.isBlank()) return null;
        return optionalObject(StrategyV5WorkflowPaths.confinedPath(value, work, "JSON artifact path"));
    }

    static JsonNode optionalJson(String value, Path work) {
        if (value == null || value.isBlank()) return null;
        Path path = StrategyV5WorkflowPaths.confinedPath(value, work, "JSON artifact path");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            return readJson(path);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static boolean validOwnHash(ObjectNode value) {
        return value != null && HASH.matcher(text(value.get("content_sha256"))).matches()
                && text(value.get("content_sha256")).equals(StrategyProspectiveV5.ownHash(value));
    }

    static boolean validSchemaHash(ObjectNode value, String schema) {
        return validOwnHash(value) && schema.equals(text(value.get("schema")));
    }

    static void requireSchemaAndHash(ObjectNode value, String schema, String label) {
        if (!validSchemaHash(value, schema)) {
            throw new IllegalArgumentException(label + " is missing or tampered");
        }
        ResearchSchemaRegistry.defaultRegistry().validateContractSchema(value);
    }

    static String text(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode() ? "" : value.asText();
    }

    static List<JsonNode> rows(JsonNode value) {
        if (value == null || value.isNull() || !value.isArray()) return List.of();
        List<JsonNode> result = new ArrayList<>();
        value.forEach(result::add);
        return result;
    }

    static List<String> stringRows(JsonNode value) {
        return rows(value).stream().map(StrategyV5WorkflowJson::text).toList();
    }

    static ArrayNode strings(List<String> values) {
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        values.forEach(result::add);
        return result;
    }

    static String pretty(JsonNode value) {
        return NodePrettyJson.write(value);
    }

    static String hashFile(Path path) {
        try {
            return StrategyProspectiveV5.hash(Files.readAllBytes(path));
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("cannot hash " + path, error);
        }
    }

    static String hashIfPresent(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? hashFile(path) : "";
    }

    static Instant parseInstant(JsonNode value) {
        if (value == null || value.isNull()) return null;
        try {
            return Instant.parse(text(value));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    static JsonNode firstNode(ObjectNode value, String... names) {
        if (value == null) return NullNode.instance;
        for (String name : names) {
            if (value.hasNonNull(name)) return value.get(name);
        }
        return NullNode.instance;
    }

    static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    static long numeric(JsonNode value) {
        if (value == null || value.isNull()) return Long.MIN_VALUE;
        if (value.isIntegralNumber()) return value.asLong(Long.MIN_VALUE);
        try {
            return Long.parseLong(value.asText());
        } catch (RuntimeException ignored) {
            return Long.MIN_VALUE;
        }
    }

    static boolean sameCanonical(JsonNode left, JsonNode right) {
        return left != null && right != null
                && JsonHashes.canonicalSha256(left).equals(JsonHashes.canonicalSha256(right));
    }
}

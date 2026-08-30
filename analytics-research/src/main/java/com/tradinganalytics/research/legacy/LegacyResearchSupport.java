package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.hash.Sha256;
import com.tradinganalytics.contracts.json.CanonicalJson;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.infrastructure.security.PathConfinement;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared dynamic-JSON and fail-closed file helpers for the legacy research ports. */
final class LegacyResearchSupport {
    static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");

    private LegacyResearchSupport() {}

    static String stable(Object value) {
        return CanonicalJson.canonicalize(value);
    }

    static String hash(Object value) {
        if (value instanceof byte[] bytes) return Sha256.hex(bytes);
        if (value instanceof String text) return Sha256.hex(text);
        return Sha256.hex(CanonicalJson.canonicalBytes(value));
    }

    static JsonNode cloneNode(JsonNode value) {
        return value == null ? NullNode.instance : value.deepCopy();
    }

    static String ownHash(JsonNode value, String field) {
        ObjectNode copy = objectCopy(value, "value");
        copy.remove(field);
        return hash(copy);
    }

    static ObjectNode withHash(JsonNode value, String field) {
        ObjectNode copy = objectCopy(value, "value");
        copy.put(field, ownHash(copy, field));
        return copy;
    }

    static ObjectNode object(JsonNode value, String name) {
        if (value == null || !value.isObject()) throw new IllegalArgumentException(name + " must be an object");
        return (ObjectNode) value;
    }

    static ObjectNode objectCopy(JsonNode value, String name) {
        return object(value, name).deepCopy();
    }

    static void required(JsonNode value, List<String> keys, String name) {
        ObjectNode object = object(value, name);
        for (String key : keys) {
            JsonNode item = object.get(key);
            if (item == null || item.isNull() || (item.isTextual() && item.textValue().isEmpty())) {
                throw new IllegalArgumentException(name + "." + key + " is required");
            }
        }
    }

    static void requiredDefined(JsonNode value, List<String> keys, String name) {
        ObjectNode object = object(value, name);
        for (String key : keys) if (!object.has(key)) throw new IllegalArgumentException(name + "." + key + " is required");
    }

    static double finite(JsonNode value, String name) {
        double result = jsNumber(value);
        if (!Double.isFinite(result)) throw new IllegalArgumentException(name + " must be numeric");
        return result;
    }

    static boolean range(JsonNode value, String name, double minimum, double maximum) {
        ObjectNode range = object(value, name);
        required(range, List.of("min", "max"), name);
        double low = finite(range.get("min"), name + ".min");
        double high = finite(range.get("max"), name + ".max");
        if (low > high || low < minimum || high > maximum) throw new IllegalArgumentException(name + " has invalid range");
        return true;
    }

    static void oneOf(String value, List<String> values, String name) {
        if (!values.contains(value)) throw new IllegalArgumentException(name + " must be one of " + String.join(", ", values));
    }

    static String safeId(JsonNode value, String name) {
        String text = text(value);
        if (!SAFE_ID.matcher(text).matches()) throw new IllegalArgumentException(name + " is not a safe id");
        return text;
    }

    static String safeId(String value, String name) {
        String text = value == null ? "" : value;
        if (!SAFE_ID.matcher(text).matches()) throw new IllegalArgumentException(name + " is not a safe id");
        return text;
    }

    static List<JsonNode> rows(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return List.of();
        List<JsonNode> result = new ArrayList<>();
        if (value.isArray()) value.forEach(result::add);
        else if (value.isObject()) value.elements().forEachRemaining(result::add);
        return result;
    }

    static ArrayNode array(JsonNode value) {
        if (value != null && value.isArray()) return (ArrayNode) value;
        return JSON.arrayNode();
    }

    static ArrayNode arrayOf(Iterable<? extends JsonNode> values) {
        ArrayNode result = JSON.arrayNode();
        values.forEach(value -> result.add(cloneNode(value)));
        return result;
    }

    static JsonNode first(JsonNode value, String... paths) {
        for (String raw : paths) {
            JsonNode current = value;
            for (String part : raw.split("\\.")) {
                if (current == null || current.isNull() || !current.isObject() || !current.has(part)) {
                    current = null;
                    break;
                }
                current = current.get(part);
            }
            if (current != null) return current;
        }
        return null;
    }

    static String text(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return "";
        if (value.isTextual()) return value.textValue();
        if (value.isBoolean()) return value.booleanValue() ? "true" : "false";
        if (value.isNumber()) return value.asText();
        return value.toString();
    }

    static String nullableText(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode() ? null : text(value);
    }

    static boolean bool(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return false;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.doubleValue() != 0 && !Double.isNaN(value.doubleValue());
        if (value.isTextual()) return !value.textValue().isEmpty();
        return true;
    }

    /** JavaScript Number(value), for the JSON values accepted by these tools. */
    static double jsNumber(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return 0;
        if (value.isBoolean()) return value.booleanValue() ? 1 : 0;
        if (value.isNumber()) return value.doubleValue();
        if (value.isTextual()) {
            String text = value.textValue().trim();
            if (text.isEmpty()) return 0;
            try { return Double.parseDouble(text); }
            catch (NumberFormatException ignored) { return Double.NaN; }
        }
        return Double.NaN;
    }

    static long jsTime(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return 0;
        if (value.isNumber()) return value.longValue();
        String raw = text(value);
        if (raw.isEmpty()) return 0;
        try { return Instant.parse(raw).toEpochMilli(); }
        catch (DateTimeParseException ignored) {
            try { return (long) Double.parseDouble(raw); }
            catch (NumberFormatException error) { return Long.MIN_VALUE; }
        }
    }

    static void setPath(ObjectNode object, String path, JsonNode value) {
        String[] parts = path.split("\\.");
        ObjectNode current = object;
        for (int index = 0; index < parts.length - 1; index++) {
            JsonNode child = current.get(parts[index]);
            if (child == null || !child.isObject()) {
                ObjectNode created = JSON.objectNode();
                current.set(parts[index], created);
                current = created;
            } else current = (ObjectNode) child;
        }
        current.set(parts[parts.length - 1], cloneNode(value));
    }

    static ObjectNode pick(JsonNode value, String... keys) {
        ObjectNode result = JSON.objectNode();
        if (value == null || !value.isObject()) return result;
        for (String key : keys) if (value.has(key)) result.set(key, cloneNode(value.get(key)));
        return result;
    }

    static ArrayNode canonicalRows(Iterable<? extends JsonNode> values) {
        List<JsonNode> rows = new ArrayList<>();
        values.forEach(value -> rows.add(cloneNode(value)));
        rows.sort(Comparator.comparing(LegacyResearchSupport::stable));
        return arrayOf(rows);
    }

    static double quantile(List<Double> values, double probability) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        double index = (sorted.size() - 1) * probability;
        int low = (int) Math.floor(index);
        int high = (int) Math.ceil(index);
        if (low == high) return sorted.get(low);
        // Keep the source's exact operation order. Algebraically equivalent
        // interpolation produces observably different IEEE-754 tails.
        return sorted.get(low) + (sorted.get(high) - sorted.get(low)) * (index - low);
    }

    static double jsMean(List<Double> values) {
        double sum = 0;
        for (double value : values) sum += value;
        return sum / values.size();
    }

    static JsonNode readJson(Path path) {
        try {
            Path absolute = path.toAbsolutePath().normalize();
            PathConfinement.validateSinglyLinkedFile(absolute, "JSON input");
            return MAPPER.readTree(PathConfinement.readSinglyLinkedFile(absolute, "JSON input"));
        } catch (IOException error) {
            throw new IllegalArgumentException("JSON input cannot be parsed: " + path, error);
        }
    }

    static ArrayNode readJsonl(Path path) {
        ArrayNode result = JSON.arrayNode();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return result;
        byte[] bytes = PathConfinement.readSinglyLinkedFile(path.toAbsolutePath().normalize(), "JSONL input");
        String text = new String(bytes, StandardCharsets.UTF_8);
        for (String line : text.split("\\r?\\n")) if (!line.isEmpty()) {
            try { result.add(MAPPER.readTree(line)); }
            catch (JsonProcessingException error) { throw new IllegalArgumentException("JSONL input cannot be parsed: " + path, error); }
        }
        return result;
    }

    static byte[] jsonBytes(JsonNode value) {
        return NodePrettyJson.write(value).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] compactJsonBytes(JsonNode value) {
        try { return (MAPPER.writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("JSON cannot be encoded", error); }
    }

    static byte[] jsonlBytes(JsonNode values) {
        StringBuilder body = new StringBuilder();
        for (JsonNode value : rows(values)) {
            try { body.append(MAPPER.writeValueAsString(value)).append('\n'); }
            catch (JsonProcessingException error) { throw new IllegalArgumentException("JSONL cannot be encoded", error); }
        }
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    static void writeExclusive(Path path, byte[] bytes) {
        Path absolute = path.toAbsolutePath().normalize();
        secureParents(absolute.getParent());
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("overwrite refused: " + path);
        try {
            Files.write(absolute, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException collision) {
            throw new IllegalArgumentException("overwrite refused: " + path, collision);
        } catch (IOException error) {
            throw new IllegalArgumentException("immutable output cannot be written: " + path, error);
        }
        PathConfinement.validateSinglyLinkedFile(absolute, "immutable output");
    }

    static void secureParents(Path directory) {
        if (directory == null) return;
        Path absolute = directory.toAbsolutePath().normalize();
        List<Path> missing = new ArrayList<>();
        Path cursor = absolute;
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(cursor);
            cursor = cursor.getParent();
        }
        if (cursor != null) assertRealDirectory(cursor, "output parent");
        Collections.reverse(missing);
        for (Path path : missing) {
            try { Files.createDirectory(path); }
            catch (FileAlreadyExistsException ignored) { /* inspected below */ }
            catch (IOException error) { throw new IllegalArgumentException("output parent cannot be created: " + path, error); }
            assertRealDirectory(path, "output parent");
        }
        assertRealDirectory(absolute, "output parent");
    }

    static void assertRealDirectory(Path path, String label) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || !attrs.isDirectory()) throw new IllegalArgumentException(label + " contains a symlink or non-directory component");
        } catch (IOException error) { throw new IllegalArgumentException(label + " cannot be inspected", error); }
    }

    static List<Path> walk(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        assertRealDirectory(root, "tree root");
        List<Path> result = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.skip(1).forEach(path -> {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attrs.isSymbolicLink()) throw new IllegalArgumentException("tree contains a symlink: " + path);
                    if (attrs.isRegularFile()) {
                        PathConfinement.requireSingleLink(path, "tree file");
                        result.add(path);
                    } else if (!attrs.isDirectory()) throw new IllegalArgumentException("tree contains a special file: " + path);
                } catch (IOException error) { throw new IllegalArgumentException("tree cannot be inspected: " + path, error); }
            });
        } catch (IOException error) { throw new IllegalArgumentException("tree cannot be walked: " + root, error); }
        return result;
    }

    static String lower(JsonNode value) { return text(value).toLowerCase(Locale.ROOT); }

    static ArrayNode strings(List<String> values) {
        ArrayNode result = JSON.arrayNode();
        values.forEach(result::add);
        return result;
    }

    static Set<String> stringSet(JsonNode value) {
        Set<String> result = new LinkedHashSet<>();
        rows(value).forEach(item -> result.add(text(item)));
        return result;
    }

    static ObjectNode merge(ObjectNode target, JsonNode source) {
        if (source != null && source.isObject()) source.fields().forEachRemaining(field -> target.set(field.getKey(), cloneNode(field.getValue())));
        return target;
    }

    static ObjectNode objectOf(Map<String, ? extends JsonNode> fields) {
        ObjectNode out = JSON.objectNode();
        fields.forEach((key, value) -> out.set(key, cloneNode(value)));
        return out;
    }
}

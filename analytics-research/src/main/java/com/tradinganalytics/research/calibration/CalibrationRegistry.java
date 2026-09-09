package com.tradinganalytics.research.calibration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Structured append-only tuning history from {@code tools/calib-registry.mjs}. */
public final class CalibrationRegistry {
    public static final String SCHEMA = "calibration-registry/1";
    public static final List<String> VERDICTS = List.of(
            "adopted", "adopted_with_modification", "rejected", "withheld", "unadjudicated");

    private static final List<String> REQUIRED_FIELDS = List.of(
            "date", "run_id", "framework", "surface", "name", "verdict", "why");
    private static final Set<String> STOPWORDS = Set.of(
            "with", "that", "this", "from", "into", "than", "were", "been", "have",
            "gate", "gates", "leg", "legs", "tune", "the", "and", "for");
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]{4,}");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[a-z]?$");

    private final ObjectMapper json;

    public CalibrationRegistry(ObjectMapper json) {
        this.json = json;
    }

    public ObjectNode load(Path path) throws IOException {
        if (!Files.exists(path)) {
            ObjectNode empty = json.createObjectNode();
            empty.put("schema", SCHEMA);
            empty.put("note", "");
            empty.set("entries", json.createArrayNode());
            return empty;
        }
        JsonNode value = json.readTree(Files.readString(path));
        if (!(value instanceof ObjectNode object)) {
            throw new IOException("calibration registry root must be an object");
        }
        return object;
    }

    public ValidationResult validate(JsonNode registry) {
        var errors = new ArrayList<String>();
        String schema = textOrNull(registry == null ? null : registry.get("schema"));
        if (!SCHEMA.equals(schema)) {
            errors.add("schema must be \"" + SCHEMA + "\", got \"" + jsString(schema) + "\"");
        }
        JsonNode entries = registry == null ? null : registry.get("entries");
        if (entries == null || !entries.isArray()) {
            errors.add("entries must be an array");
            return new ValidationResult(false, List.copyOf(errors));
        }

        for (int index = 0; index < entries.size(); index++) {
            JsonNode entry = entries.get(index);
            for (String key : REQUIRED_FIELDS) {
                JsonNode value = entry == null ? null : entry.get(key);
                if (missingLikeJavaScript(value)) {
                    errors.add("entries[" + index + "] missing \"" + key + "\"");
                }
            }
            JsonNode verdict = entry == null ? null : entry.get("verdict");
            if (truthy(verdict) && !VERDICTS.contains(verdict.asText())) {
                errors.add("entries[" + index + "] verdict \"" + verdict.asText()
                        + "\" not one of " + String.join("|", VERDICTS));
            }
            JsonNode date = entry == null ? null : entry.get("date");
            if (truthy(date) && !DATE.matcher(date.asText()).matches()) {
                errors.add("entries[" + index + "] date \"" + date.asText()
                        + "\" not YYYY-MM-DD (or YYYY-MM-DDb for a same-day second run)");
            }
            JsonNode revalidations = entry == null ? null : entry.get("revalidations");
            if (truthy(revalidations) && !revalidations.isArray()) {
                errors.add("entries[" + index + "] revalidations must be an array");
            }
            if (revalidations != null && revalidations.isArray()) {
                for (JsonNode revalidation : revalidations) {
                    if (!truthy(revalidation.get("date")) || !truthy(revalidation.get("verdict"))) {
                        errors.add("entries[" + index + "] revalidation missing date/verdict: "
                                + compact(revalidation));
                    }
                }
            }
        }
        return new ValidationResult(errors.isEmpty(), List.copyOf(errors));
    }

    public List<Match> matchRejections(String query, JsonNode registry, String framework) {
        LinkedHashSet<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        JsonNode entries = registry.get("entries");
        var matches = new ArrayList<Match>();
        for (JsonNode entry : entries) {
            String verdict = entry.path("verdict").asText();
            if (!(verdict.equals("rejected") || verdict.equals("withheld"))) {
                continue;
            }
            String entryFramework = entry.path("framework").asText();
            if (framework != null && !framework.isEmpty()
                    && !(entryFramework.equals(framework) || entryFramework.equals("both"))) {
                continue;
            }
            LinkedHashSet<String> entryTokens = tokenize(
                    entry.path("name").asText("") + " " + entry.path("surface").asText(""));
            List<String> overlap = queryTokens.stream().filter(entryTokens::contains).toList();
            if (overlap.size() >= 2) {
                matches.add(new Match(entry, overlap, overlap.size()));
            }
        }
        matches.sort(Comparator.comparingInt(Match::score).reversed());
        return List.copyOf(matches);
    }

    public ObjectNode append(ObjectNode registry, JsonNode incoming) {
        ArrayNode entries = (ArrayNode) registry.withArray("entries");
        if (incoming.isArray()) {
            incoming.forEach(entries::add);
        } else {
            entries.add(incoming);
        }
        return registry;
    }

    private LinkedHashSet<String> tokenize(String value) {
        var output = new LinkedHashSet<String>();
        var matcher = TOKEN.matcher(value == null ? "" : value.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (!STOPWORDS.contains(token)) {
                output.add(token);
            }
        }
        return output;
    }

    private String compact(JsonNode value) {
        try {
            return json.writeValueAsString(value);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean missingLikeJavaScript(JsonNode value) {
        return value == null || value.isNull() || (value.isTextual() && value.textValue().isEmpty());
    }

    private static boolean truthy(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            return value.doubleValue() != 0 && !Double.isNaN(value.doubleValue());
        }
        return !value.isTextual() || !value.textValue().isEmpty();
    }

    private static String textOrNull(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String jsString(String value) {
        return value == null ? "undefined" : value;
    }

    public record ValidationResult(boolean ok, List<String> errors) {
    }

    public record Match(JsonNode entry, List<String> overlap, int score) {
    }
}

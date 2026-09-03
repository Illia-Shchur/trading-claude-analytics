package com.tradinganalytics.marketdata.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Parses, validates, and normalizes research rows before publication. */
final class ResearchDataRows {
    private static final Set<String> LABEL_FIELDS = Set.of(
            "outcome", "outcomes", "forward_return", "future_return", "forward_pnl",
            "future_pnl", "resolved_at", "resolution_bars", "label", "target");
    private static final Pattern EVENT_NATIVE = Pattern.compile(
            "(?:trade|funding|liquidation|exchange[_-]?event|settlement)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIMEFRAME = Pattern.compile("^(\\d+)(m|h|d)$", Pattern.CASE_INSENSITIVE);

    private ResearchDataRows() {
    }

    static List<ObjectNode> readRows(java.nio.file.Path path) {
        try {
            return parseRows(path, Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw failure(error.getMessage(), error);
        }
    }

    static long rowTime(JsonNode row) {
        return rowTime(row, "event_time");
    }

    static long rowTime(JsonNode row, String name) {
        JsonNode raw = ResearchDataJson.firstPresent(row, name, "time", "timestamp", "open_time");
        if (raw == null || raw.isNull()) {
            throw failure("row " + name + " must be a valid timestamp");
        }
        if (raw.isNumber()) {
            double value = raw.asDouble();
            if (!Double.isFinite(value)) {
                throw failure("row " + name + " must be a valid timestamp");
            }
            return (long) value;
        }
        Long parsed = parseTime(raw.asText());
        if (parsed == null) {
            throw failure("row " + name + " must be a valid timestamp");
        }
        return parsed;
    }

    static String findFutureLabel(JsonNode value) {
        return findFutureLabel(value, "");
    }

    static String findFutureLabel(JsonNode value, String path) {
        if (value == null || !value.isContainerNode()) {
            return null;
        }
        if (value.isArray()) {
            for (int index = 0; index < value.size(); index++) {
                String nested = findFutureLabel(value.get(index), path.isEmpty()
                        ? String.valueOf(index) : path + "." + index);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        var fields = value.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String childPath = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
            if (LABEL_FIELDS.contains(field.getKey().toLowerCase(Locale.ROOT))) {
                return childPath;
            }
            String nested = findFutureLabel(field.getValue(), childPath);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    static List<ObjectNode> normalizeRows(List<? extends JsonNode> rows, ResearchData.NormalizeOptions options) {
        Objects.requireNonNull(rows, "rows");
        options = options == null ? ResearchData.NormalizeOptions.defaults() : options;
        if (!ResearchData.PIT_TIERS.contains(options.pitTier())) {
            throw failure("unknown PIT tier " + options.pitTier());
        }
        List<ObjectNode> output = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            JsonNode raw = rows.get(index);
            if (raw == null || !raw.isObject()) {
                throw failure("row " + index + " must be an object");
            }
            if ("FEATURE".equals(options.role())) {
                String leaked = findFutureLabel(raw);
                if (leaked != null) {
                    throw failure("feature row contains future-label field " + leaked);
                }
            }
            long event = rowTime(raw);
            JsonNode availabilityRaw = "LABEL".equals(options.role())
                    ? ResearchDataJson.firstPresent(raw, "resolved_at", "resolution_time", "label_available_at")
                    : ResearchDataJson.firstPresent(raw, "availability_time", "available_at", "as_of",
                            "close_time", "end_time");
            boolean eventNative = EVENT_NATIVE.matcher(options.source()).find();
            if (availabilityRaw == null && !eventNative) {
                throw failure("row " + index + " availability_time is required for non-event-native source "
                        + options.source());
            }
            long availability = availabilityRaw == null ? event
                    : rowTime(JsonHashes.mapper().createObjectNode().set("time", availabilityRaw));
            if (availability < event && !"event_time".equals(options.availabilityPolicy())) {
                throw failure("row " + index + " availability_time precedes event_time");
            }
            String asset = ResearchDataJson.textOr(raw.get("asset"), options.asset());
            asset = asset == null ? "" : asset.toLowerCase(Locale.ROOT);
            String assetClass = ResearchDataJson.textOr(raw.get("asset_class"),
                    "CONTEXT".equals(options.role()) ? "context" : "crypto");
            assetClass = assetClass.toLowerCase(Locale.ROOT);
            if (asset.isEmpty()) {
                throw failure("row " + index + " asset is required");
            }
            boolean context = "CONTEXT".equals(options.role()) || "context".equals(assetClass);
            if (!context && !"crypto".equals(assetClass)) {
                throw failure("row " + index + " non-crypto data cannot enter " + options.role());
            }
            ObjectNode normalized = ((ObjectNode) raw).deepCopy();
            normalized.put("asset", asset);
            normalized.put("asset_class", assetClass);
            ResearchDataJson.putNullable(normalized, "venue",
                    ResearchDataJson.nodeOrText(raw.get("venue"), options.venue()));
            ResearchDataJson.putNullable(normalized, "instrument",
                    ResearchDataJson.nodeOrText(raw.get("instrument"), options.instrument()));
            ResearchDataJson.putNullable(normalized, "timeframe",
                    ResearchDataJson.nodeOrText(raw.get("timeframe"), options.timeframe()));
            normalized.put("event_time", event);
            normalized.put("availability_time", availability);
            normalized.put("dataset_id", options.datasetId());
            normalized.set("dataset_version", raw.hasNonNull("dataset_version")
                    ? raw.get("dataset_version").deepCopy()
                    : JsonHashes.mapper().getNodeFactory().textNode(options.datasetId()));
            normalized.put("source", options.source());
            normalized.put("pit_tier", options.pitTier());
            normalized.put("revision_status", raw.hasNonNull("revision_status")
                    ? raw.get("revision_status").asText()
                    : "T3_REVISED_OR_PROXY".equals(options.pitTier())
                            ? "REVISED_OR_PROXY" : "ORIGINAL");
            normalized.put("role", options.role());
            output.add(normalized);
        }
        output.sort(Comparator.comparingLong((ObjectNode row) -> row.path("event_time").asLong())
                .thenComparing(row -> row.path("asset").asText()));
        return output;
    }

    static ResearchData.SplitRows splitFeatureLabels(List<? extends JsonNode> rows) {
        List<ObjectNode> features = new ArrayList<>();
        List<ObjectNode> labels = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.isObject()) {
                throw failure("row must be an object");
            }
            ObjectNode feature = JsonHashes.mapper().createObjectNode();
            ObjectNode label = JsonHashes.mapper().createObjectNode();
            row.fields().forEachRemaining(field ->
                    (LABEL_FIELDS.contains(field.getKey().toLowerCase(Locale.ROOT)) ? label : feature)
                            .set(field.getKey(), field.getValue().deepCopy()));
            if (!label.isEmpty()) {
                ResearchDataJson.copyIfPresent(row, label, "event_time", "asset", "dataset_id");
                labels.add(label);
            }
            features.add(feature);
        }
        return new ResearchData.SplitRows(features, labels);
    }

    static ResearchData.SplitRows splitForSnapshot(List<ObjectNode> rows, ObjectNode horizon, String timeframe) {
        List<ObjectNode> features = new ArrayList<>();
        List<ObjectNode> labels = new ArrayList<>();
        for (ObjectNode row : rows) {
            ObjectNode feature = JsonHashes.mapper().createObjectNode();
            ObjectNode label = JsonHashes.mapper().createObjectNode();
            row.fields().forEachRemaining(field ->
                    (LABEL_FIELDS.contains(field.getKey().toLowerCase(Locale.ROOT)) ? label : feature)
                            .set(field.getKey(), field.getValue().deepCopy()));
            if (!label.isEmpty()) {
                ResearchDataJson.copyIfPresent(row, label, "event_time", "time", "timestamp", "open_time", "asset",
                        "asset_class", "venue", "instrument", "timeframe", "dataset_version",
                        "resolution_bars", "horizon_bars", "resolved_at", "resolution_time",
                        "label_available_at");
                label.put("resolved_at", labelAvailability(label, horizon, timeframe));
                labels.add(label);
            }
            features.add(feature);
        }
        return new ResearchData.SplitRows(features, labels);
    }

    static ResearchData.SplitRows labelOnly(List<ObjectNode> rows, ObjectNode horizon, String timeframe) {
        List<ObjectNode> labels = new ArrayList<>();
        for (ObjectNode row : rows) {
            ObjectNode label = row.deepCopy();
            label.put("resolved_at", labelAvailability(label, horizon, timeframe));
            labels.add(label);
        }
        return new ResearchData.SplitRows(List.of(), labels);
    }

    static long labelAvailability(JsonNode row, ObjectNode horizon, String timeframe) {
        JsonNode explicit = ResearchDataJson.firstPresent(row, "resolved_at", "resolution_time", "label_available_at");
        if (explicit != null) {
            return rowTime(JsonHashes.mapper().createObjectNode().set("time", explicit));
        }
        JsonNode rawBars = ResearchDataJson.firstPresent(row, "resolution_bars", "horizon_bars");
        int bars = rawBars == null ? (horizon == null ? 0 : horizon.path("bars").asInt()) : rawBars.asInt();
        if (bars <= 0) {
            throw failure("label row requires resolved_at or a positive frozen label horizon");
        }
        return rowTime(row) + bars * timeframeMs(row.path("timeframe").asText(timeframe));
    }

    static List<ObjectNode> parseRows(java.nio.file.Path path, String source) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".csv")) {
                return readDelimited(source);
            }
            if (name.endsWith(".jsonl") || name.endsWith(".ndjson")) {
                List<ObjectNode> rows = new ArrayList<>();
                for (String line : source.split("\\R")) {
                    if (!line.isBlank()) {
                        rows.add(parseObject(line.getBytes(StandardCharsets.UTF_8)));
                    }
                }
                return rows;
            }
            JsonNode value = JsonHashes.mapper().readTree(source);
            JsonNode rows = value.isArray() ? value
                    : value.path("rows").isArray() ? value.path("rows") : value.path("data");
            List<ObjectNode> output = new ArrayList<>();
            if (rows.isArray()) {
                for (JsonNode row : rows) {
                    if (!row.isObject()) {
                        throw failure("row must be an object");
                    }
                    output.add(((ObjectNode) row).deepCopy());
                }
            }
            return output;
        } catch (JsonProcessingException error) {
            throw failure(error.getOriginalMessage(), error);
        }
    }

    private static List<ObjectNode> readDelimited(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (quoted) {
                if (character == '"' && index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                } else if (character == '"') {
                    quoted = false;
                } else {
                    field.append(character);
                }
            } else if (character == '"' && field.isEmpty()) {
                quoted = true;
            } else if (character == ',') {
                record.add(field.toString().trim());
                field.setLength(0);
            } else if (character == '\n' || character == '\r') {
                if (character == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
                record.add(field.toString().trim());
                field.setLength(0);
                if (record.stream().anyMatch(value -> !value.isEmpty())) {
                    records.add(record);
                }
                record = new ArrayList<>();
            } else {
                field.append(character);
            }
        }
        if (quoted) {
            throw failure("CSV contains an unterminated quoted field");
        }
        if (!field.isEmpty() || !record.isEmpty()) {
            record.add(field.toString().trim());
            if (record.stream().anyMatch(value -> !value.isEmpty())) {
                records.add(record);
            }
        }
        if (records.isEmpty()) {
            return List.of();
        }
        List<String> headers = records.remove(0).stream().map(String::trim).toList();
        if (headers.stream().anyMatch(String::isEmpty)) {
            throw failure("CSV header contains an empty field");
        }
        List<ObjectNode> rows = new ArrayList<>();
        for (List<String> values : records) {
            ObjectNode row = JsonHashes.mapper().createObjectNode();
            for (int index = 0; index < headers.size(); index++) {
                row.put(headers.get(index), index < values.size() ? values.get(index) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    static long timeframeMs(String value) {
        var match = TIMEFRAME.matcher(ResearchDataJson.valueOr(value, "4h"));
        if (!match.matches()) {
            throw failure("unsupported timeframe " + value);
        }
        long count = Long.parseLong(match.group(1));
        return count * switch (match.group(2).toLowerCase(Locale.ROOT)) {
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            default -> 86_400_000L;
        };
    }

    static Long parseTime(String value) {
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant().toEpochMilli();
            } catch (DateTimeParseException alsoIgnored) {
                try {
                    return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                } catch (DateTimeParseException invalid) {
                    return null;
                }
            }
        }
    }

    private static ObjectNode parseObject(byte[] bytes) {
        JsonNode value = JsonHashes.parse(bytes, "research data JSON");
        if (!value.isObject()) {
            throw failure("research data JSON must be an object");
        }
        return ((ObjectNode) value).deepCopy();
    }

    private static IllegalArgumentException failure(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException failure(String message, Throwable cause) {
        return new IllegalArgumentException(message, cause);
    }
}

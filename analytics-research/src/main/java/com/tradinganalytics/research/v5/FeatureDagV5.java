package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Causal, point-in-time feature graph ported from {@code strategy-v5-feature-dag.mjs}. */
public final class FeatureDagV5 {
    public static final String FEATURE_DAG_SCHEMA = "strategy-v5-feature-dag/1";
    /** SHA-256 of the frozen Node oracle source at the migration baseline. */
    public static final String FEATURE_DAG_CODE_SHA256 =
            "f14c3dc00b7fd8b2fc46b3938127e90b231c8e1ffa123c4ec6714936ed53b428";

    private static final DateTimeFormatter JS_ISO = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern ID = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_.-]{0,127}$");
    private static final Pattern LABEL_PATTERN = Pattern.compile("(^|_)(future|forward|realized|resolved|outcome|label|target|settled)(_|$)");
    private static final Pattern TRADE_LABEL_PATTERN = Pattern.compile("(^|_)(trade_pnl|exit_price|exit_time)(_|$)");
    private static final Set<String> LABEL_KEYS = Set.of(
            "label", "target", "outcome", "forward_return", "future_return", "future_pnl", "forward_pnl",
            "net_r", "gross_r", "exit_price", "exit_time", "resolved_at", "resolution_time", "resolution_bars",
            "future_high", "future_low", "future_close", "realized_return", "realized_pnl", "trade_pnl", "pnl",
            "profit_loss", "trade_result", "settled_pnl");
    private static final Set<String> NUMERIC = Set.of("number", "integer");
    private static final Set<String> OPS = Set.of(
            "FIELD", "LAG", "DIFF", "PCT_RETURN", "LOG_RETURN", "ADD", "SUB", "MUL", "DIV", "ABS", "LOG", "CLAMP",
            "SMA", "EMA", "SUM", "MIN", "MAX", "MEDIAN", "QUANTILE", "PERCENTILE_RANK", "STDDEV", "VOL", "ZSCORE",
            "ROBUST_ZSCORE", "WINSORIZE", "TRUE_RANGE", "ATR", "SLOPE", "COVARIANCE", "CORRELATION", "BETA",
            "RATIO", "SPREAD", "RELATIVE_RETURN", "BASIS", "AND", "OR", "NOT", "EQ", "NE", "GT", "GTE", "LT", "LTE",
            "IS_NULL", "IF", "CROSS_ABOVE", "CROSS_BELOW", "RSI");
    private static final Set<String> ROLLING = Set.of(
            "SMA", "EMA", "SUM", "MIN", "MAX", "MEDIAN", "QUANTILE", "PERCENTILE_RANK", "STDDEV", "VOL", "ZSCORE",
            "ROBUST_ZSCORE", "WINSORIZE", "ATR", "SLOPE", "COVARIANCE", "CORRELATION", "BETA");

    private FeatureDagV5() {}

    public static String hash(JsonNode value) { return JsonHashes.canonicalSha256(value); }
    public static String hash(String value) { return JsonHashes.sha256(value); }

    public static boolean validateFeatureGraphV5(JsonNode graph) {
        if (!isObject(graph) || !FEATURE_DAG_SCHEMA.equals(text(field(graph, "schema")))
                || field(graph, "version").asInt() != 1) {
            throw failure("feature graph schema/version is invalid");
        }
        if (!text(field(graph, "content_sha256")).equals(ownHash(graph))) throw failure("feature graph hash is invalid");
        if (!field(graph, "fixture_only").isBoolean() || !field(graph, "provenance").isTextual()
                || field(graph, "provenance").textValue().isEmpty()) {
            throw failure("feature graph fixture/provenance marker is required");
        }
        if (!FEATURE_DAG_CODE_SHA256.equals(text(field(graph, "code_sha256")))) {
            throw failure("feature graph code binding is stale");
        }
        if (!field(graph, "fixture_only").asBoolean()) {
            for (String name : List.of("precommit_sha256", "predictor_registry_sha256", "config_sha256")) {
                if (!HASH.matcher(text(field(graph, name))).matches()) {
                    throw failure("production feature graph requires bound " + name);
                }
            }
        }
        JsonNode rawNodes = field(graph, "nodes");
        if (!rawNodes.isArray() || rawNodes.isEmpty()) throw failure("feature graph requires nodes");
        List<ObjectNode> normalized = new ArrayList<>();
        int index = 0;
        for (JsonNode node : rawNodes) normalized.add(normalizeNode(node, index++));
        Set<String> ids = new HashSet<>();
        for (ObjectNode node : normalized) {
            String id = node.path("id").asText();
            if (!ids.add(id)) throw failure("duplicate feature node " + id);
        }
        List<ObjectNode> ordered = topological(normalized);
        Map<String, ObjectNode> byId = byId(ordered);
        Map<String, Lineage> memo = new HashMap<>();
        for (ObjectNode node : ordered) {
            TypeUnit inferred = inferTypeUnit(node, byId);
            if (!inferred.type.equals(node.path("scalar_type").asText())) {
                throw failure(node.path("id").asText() + " declares " + node.path("scalar_type").asText()
                        + " but its operation produces " + inferred.type);
            }
            String unit = node.path("unit").asText();
            if (!unit.isEmpty() && inferred.unit != null && !unit.equals(inferred.unit)
                    && !Set.of("DIV", "MUL").contains(node.path("op").asText())) {
                throw failure(node.path("id").asText() + " declares unit " + unit
                        + " but its operation produces " + inferred.unit);
            }
        }
        ArrayNode outputs = outputIds(graph, ordered);
        Set<String> outputIds = new HashSet<>();
        for (JsonNode output : outputs) {
            String id = output.asText();
            if (!outputIds.add(id) || !byId.containsKey(id)) throw failure("feature graph outputs are invalid");
        }
        Set<String> outputPhysical = new HashSet<>();
        for (JsonNode output : outputs) {
            String id = output.asText();
            Lineage lineage = lineage(byId, id, memo);
            for (String value : lineage.physical) {
                if (outputPhysical.contains(value) && byId.get(id).path("voting_output").asBoolean(false)) {
                    throw failure("independent feature outputs share physical evidence " + value);
                }
                outputPhysical.add(value);
            }
        }
        Map<String, String> fieldPhysical = new HashMap<>();
        for (ObjectNode node : ordered) {
            if (!"FIELD".equals(node.path("op").asText())) continue;
            String physical = nullableText(field(node, "physical_evidence_id"));
            if (physical != null) {
                String prior = fieldPhysical.get(physical);
                if (prior != null && !prior.equals(node.path("id").asText())
                        && node.path("voting_output").asBoolean(false)
                        && !present(node, "independent_vote", false)) {
                    throw failure("duplicate/derived physical evidence " + physical + " cannot receive independent votes");
                }
                fieldPhysical.put(physical, node.path("id").asText());
            }
            String sourceField = node.path("source_field").asText();
            if (isLabelKey(sourceField)) {
                throw failure(node.path("id").asText() + " cannot read label/outcome field " + sourceField);
            }
        }
        Map<String, String> sourceKeys = new HashMap<>();
        Map<String, Identity> physicalIdentities = new HashMap<>();
        for (ObjectNode node : ordered) {
            if (!"FIELD".equals(node.path("op").asText())) continue;
            String physical = nullableText(field(node, "physical_evidence_id"));
            String key = node.path("source_series").asText() + '|' + node.path("source_field").asText() + '|'
                    + (physical == null ? "" : physical);
            if (sourceKeys.containsKey(key) && !present(node, "independent_vote", false)) {
                throw failure("duplicate physical feature source " + key);
            }
            sourceKeys.put(key, node.path("id").asText());
            if (physical != null) {
                String identityKey = node.path("source_series").asText() + '|' + physical;
                Identity identity = physicalIdentities.get(identityKey);
                Identity current = new Identity(node.path("source_field").asText(), node.path("scalar_type").asText(),
                        node.path("unit").asText(), node.path("trade_scope").asText());
                if (identity != null && identity.sourceField.equals(current.sourceField)
                        && (!identity.scalarType.equals(current.scalarType) || !identity.unit.equals(current.unit)
                        || !identity.tradeScope.equals(current.tradeScope))) {
                    throw failure("conflicting duplicate feature identity " + identityKey);
                }
                physicalIdentities.put(identityKey, current);
            }
        }
        ObjectNode expected = ((ObjectNode) graph).deepCopy();
        expected.set("nodes", arrayOf(ordered));
        expected.put("content_sha256", ownHash(expected));
        if (!stable(expected).equals(stable(graph))) throw failure("feature graph is not in deterministic topological order");
        return true;
    }

    public static ObjectNode makeFeatureGraphV5(ObjectNode options) {
        ObjectNode source = options == null ? object() : options;
        String precommit = bindArtifact(field(source, "precommit"), field(source, "precommit_sha256"), "precommit");
        String registry = bindArtifact(field(source, "predictorRegistry"), field(source, "predictor_registry_sha256"), "predictor registry");
        String config = bindArtifact(field(source, "config"), field(source, "config_sha256"), "config");
        List<ObjectNode> normalized = new ArrayList<>();
        JsonNode nodes = field(source, "nodes");
        if (nodes.isArray()) {
            int index = 0;
            for (JsonNode node : nodes) normalized.add(normalizeNode(node, index++));
        }
        List<ObjectNode> ordered = topological(normalized);
        boolean fixture = field(source, "fixtureOnly").asBoolean(false);
        ObjectNode value = object().put("schema", FEATURE_DAG_SCHEMA).put("version", 1).put("status", "FROZEN")
                .put("fixture_only", fixture).put("provenance", fixture ? "FIXTURE/LEGACY_EXPOSED" : "AUTHORITATIVE");
        String graphId = truthy(field(source, "graph_id")) ? text(field(source, "graph_id"))
                : "feature-graph-" + hash(arrayOf(ordered)).substring(0, 16);
        value.put("graph_id", graphId);
        putNullable(value, "precommit_sha256", precommit);
        putNullable(value, "predictor_registry_sha256", registry);
        putNullable(value, "config_sha256", config);
        value.put("code_sha256", FEATURE_DAG_CODE_SHA256);
        value.set("nodes", arrayOf(ordered));
        if (truthy(field(source, "outputs"))) value.set("outputs", field(source, "outputs").deepCopy());
        else {
            ArrayNode outputs = value.putArray("outputs");
            if (ordered.isEmpty()) outputs.addNull(); else outputs.add(ordered.get(ordered.size() - 1).path("id").asText());
        }
        value.putNull("content_sha256");
        value.put("content_sha256", ownHash(value));
        validateFeatureGraphV5(value);
        return value;
    }

    public static ObjectNode resumeWilderRsiV5(ArrayNode values, ObjectNode options) {
        ObjectNode source = options == null ? object() : options;
        int period = Math.max(1, truncate(number(field(source, "period"), "RSI period")));
        ObjectNode prior = isObject(field(source, "state")) ? ((ObjectNode) field(source, "state")).deepCopy() : object();
        if (present(prior, "period") && truncate(numberJs(field(prior, "period"))) != period) {
            throw failure("Wilder RSI checkpoint period mismatch");
        }
        if (present(prior, "min_history") && truncate(numberJs(field(prior, "min_history"))) != period) {
            throw failure("Wilder RSI checkpoint min-history mismatch");
        }
        Double previous = finite(field(prior, "previous")) ? numberJs(field(prior, "previous")) : null;
        double gainSum = finite(field(prior, "gain_sum")) ? numberJs(field(prior, "gain_sum")) : 0;
        double lossSum = finite(field(prior, "loss_sum")) ? numberJs(field(prior, "loss_sum")) : 0;
        int count = Math.max(0, truncate(numberJs(or(field(prior, "count"), numberNode(0)))));
        Double avgGain = finite(field(prior, "avg_gain")) ? numberJs(field(prior, "avg_gain")) : null;
        Double avgLoss = finite(field(prior, "avg_loss")) ? numberJs(field(prior, "avg_loss")) : null;
        ArrayNode output = array();
        for (JsonNode raw : values == null ? array() : values) {
            if (!finite(raw)) {
                output.addNull(); previous = null; gainSum = 0; lossSum = 0; count = 0; avgGain = null; avgLoss = null;
                continue;
            }
            double value = numberJs(raw);
            if (previous == null) { previous = value; output.addNull(); continue; }
            double gain = Math.max(0, value - previous), loss = Math.max(0, previous - value);
            previous = value;
            if (avgGain == null) {
                gainSum += gain; lossSum += loss; count++;
                if (count < period) { output.addNull(); continue; }
                avgGain = gainSum / period; avgLoss = lossSum / period;
            } else {
                avgGain = (avgGain * (period - 1) + gain) / period;
                avgLoss = (avgLoss * (period - 1) + loss) / period;
            }
            output.add(avgLoss == 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss));
        }
        ObjectNode state = object().put("period", period).put("min_history", period);
        putNullableNumber(state, "previous", previous);
        state.put("gain_sum", gainSum).put("loss_sum", lossSum).put("count", count);
        putNullableNumber(state, "avg_gain", avgGain); putNullableNumber(state, "avg_loss", avgLoss);
        ObjectNode result = object(); result.set("values", output); result.set("state", state); return result;
    }

    public static ObjectNode resumeRecursiveEmaV5(ArrayNode values, ObjectNode options) {
        ObjectNode source = options == null ? object() : options;
        int period = Math.max(1, truncate(number(field(source, "period"), "EMA period")));
        int minimum = Math.max(1, truncate(number(nullish(field(source, "minHistory"), field(source, "period")), "EMA min_history")));
        ObjectNode prior = isObject(field(source, "state")) ? ((ObjectNode) field(source, "state")).deepCopy() : object();
        if (present(prior, "period") && truncate(numberJs(field(prior, "period"))) != period) {
            throw failure("EMA checkpoint period mismatch");
        }
        if (present(prior, "min_history") && truncate(numberJs(field(prior, "min_history"))) != minimum) {
            throw failure("EMA checkpoint min-history mismatch");
        }
        double alpha = 2d / (period + 1);
        List<Double> seed = new ArrayList<>();
        JsonNode seedValues = field(prior, "seed_values");
        if (seedValues.isArray()) for (JsonNode value : seedValues) if (finite(value)) seed.add(numberJs(value));
        Double ema = finite(field(prior, "ema")) ? numberJs(field(prior, "ema")) : null;
        ArrayNode output = array();
        for (JsonNode raw : values == null ? array() : values) {
            if (!finite(raw)) { seed.clear(); ema = null; output.addNull(); continue; }
            double value = numberJs(raw);
            if (ema == null) {
                seed.add(value);
                if (seed.size() < minimum) { output.addNull(); continue; }
                double total = 0; for (double item : seed) total += item;
                ema = total / seed.size(); output.add(ema);
            } else {
                ema = alpha * value + (1 - alpha) * ema; output.add(ema);
            }
        }
        ObjectNode state = object().put("period", period).put("min_history", minimum);
        ArrayNode finalSeed = state.putArray("seed_values");
        if (ema == null) seed.forEach(finalSeed::add);
        putNullableNumber(state, "ema", ema);
        ObjectNode result = object(); result.set("values", output); result.set("state", state); return result;
    }

    public static ArrayNode pointInTimeJoinV5(ObjectNode options) {
        ObjectNode source = options == null ? object() : options;
        List<SeriesRow> rows = normalizeSeries(field(source, "series"), "join");
        List<Long> times = new ArrayList<>();
        JsonNode decisions = field(source, "decisions");
        if (decisions.isArray()) for (JsonNode decision : decisions) {
            if (decision.isObject()) {
                String key = truthy(field(source, "decisionKey")) ? text(field(source, "decisionKey")) : "decision_time";
                times.add(time(nullish(field(decision, key), field(decision, "event_time"), field(decision, "time"))));
            } else times.add(time(decision));
        }
        times.sort(Long::compareTo);
        Long maxStaleness = defined(field(source, "maxStalenessMs")) ? (long) numberJs(field(source, "maxStalenessMs")) : null;
        String gapPolicy = truthy(field(source, "gapPolicy")) ? text(field(source, "gapPolicy")) : "NULL";
        boolean includeCurrent = !present(source, "includeCurrent") || field(source, "includeCurrent").asBoolean();
        ArrayNode output = array();
        for (long decision : times) {
            SeriesRow row = latestAsOf(rows, decision, includeCurrent, maxStaleness, gapPolicy);
            ObjectNode joined = object().put("decision_time", iso(decision));
            if (row == null) {
                joined.putNull("event_time").putNull("availability_time").putNull("stale_ms")
                        .putNull("event_age_ms").putNull("value");
            } else {
                joined.put("event_time", iso(row.event)).put("availability_time", iso(row.available))
                        .put("stale_ms", decision - row.available).put("event_age_ms", decision - row.event);
                joined.set("value", cleanInternal(row.value));
            }
            output.add(joined);
        }
        return output;
    }

    public static ArrayNode joinPointInTimeV5(ObjectNode options) { return pointInTimeJoinV5(options); }

    public static ObjectNode evaluateFeatureGraphV5(JsonNode graph, ObjectNode options) {
        validateFeatureGraphV5(graph);
        ObjectNode source = options == null ? object() : options;
        List<ObjectNode> ordered = objectList(field(graph, "nodes"));
        Map<String, ObjectNode> byId = byId(ordered);
        JsonNode sourceMap = or(field(source, "series"), field(source, "rows"), object());
        Map<String, List<SeriesRow>> sources = seriesMap(sourceMap);
        List<SeriesRow> primary = sources.get("primary");
        if (primary == null) primary = sources.values().stream().findFirst().orElseGet(ArrayList::new);

        JsonNode requestedInput = truthy(field(source, "decisionTimes")) ? field(source, "decisionTimes")
                : truthy(field(source, "decisions")) ? field(source, "decisions") : seriesRowsNode(primary);
        List<Long> requestedTimes = new ArrayList<>();
        if (requestedInput.isArray()) for (JsonNode row : requestedInput) {
            long at = row.isObject() ? time(nullish(field(row, "decision_time"), field(row, "event_time"),
                    field(row, "time"), field(row, "open_time"))) : time(row);
            requestedTimes.add(at);
        }
        requestedTimes.sort(Long::compareTo);
        requestedTimes = distinct(requestedTimes);
        Set<Long> requestedSet = new HashSet<>(requestedTimes);
        List<Long> times = new ArrayList<>();
        for (SeriesRow row : primary) times.add(row.event);
        times.addAll(requestedTimes); times.sort(Long::compareTo); times = distinct(times);

        Map<String, List<JsonNode>> nodeValues = new LinkedHashMap<>();
        List<ObjectNode> outputRows = new ArrayList<>();
        for (long at : times) outputRows.add(object().put("decision_time", iso(at)).put("event_time", iso(at)).put("availability_time", iso(at)));
        String gapPolicy = truthy(field(source, "gapPolicy")) ? text(field(source, "gapPolicy")) : "NULL";
        for (ObjectNode node : ordered) {
            String op = node.path("op").asText();
            List<JsonNode> values = new ArrayList<>();
            if ("FIELD".equals(op)) {
                String seriesName = truthy(field(node, "source_series")) ? text(field(node, "source_series"))
                        : truthy(field(node, "source")) ? text(field(node, "source")) : "primary";
                List<SeriesRow> seriesRows = sources.getOrDefault(seriesName, List.of());
                boolean include = "INCLUDE_CURRENT_COMPLETED".equals(node.path("current_observation_policy").asText());
                Long stale = present(node, "max_staleness_ms") ? (long) numberJs(field(node, "max_staleness_ms")) : null;
                for (long decision : times) {
                    SeriesRow row = latestAsOf(seriesRows, decision, include, stale, gapPolicy);
                    JsonNode value = row == null ? NullNode.getInstance() : field(row.value, node.path("source_field").asText());
                    values.add(value.deepCopy());
                }
            } else if ("TRUE_RANGE".equals(op)) {
                List<List<JsonNode>> refs = new ArrayList<>();
                for (JsonNode ref : field(node, "inputs")) refs.add(nodeValues.get(ref.asText()));
                for (int index = 0; index < times.size(); index++) {
                    double high = number(valueAt(refs.get(0), index), node.path("id").asText() + ".high");
                    double low = number(valueAt(refs.get(1), index), node.path("id").asText() + ".low");
                    JsonNode close = valueAt(refs.get(2), index);
                    JsonNode prior = index > 0 ? valueAt(refs.get(2), index - 1) : NullNode.getInstance();
                    double range = finite(close) && finite(prior)
                            ? Math.max(high - low, Math.max(Math.abs(high - numberJs(prior)), Math.abs(low - numberJs(prior))))
                            : high - low;
                    values.add(numberNode(range));
                }
            } else if ("EMA".equals(op)) {
                List<JsonNode> full = recursiveEma(nodeValues.get(text(field(node, "inputs").get(0))), node);
                for (int index = 0; index < times.size(); index++) {
                    values.add("INCLUDE_CURRENT_COMPLETED".equals(node.path("current_observation_policy").asText())
                            ? valueAt(full, index) : index > 0 ? valueAt(full, index - 1) : NullNode.getInstance());
                }
            } else if ("RSI".equals(op)) {
                List<JsonNode> full = wilderRsi(nodeValues.get(text(field(node, "inputs").get(0))), node.path("lookback_bars").asInt());
                for (int index = 0; index < times.size(); index++) {
                    values.add("INCLUDE_CURRENT_COMPLETED".equals(node.path("current_observation_policy").asText())
                            ? valueAt(full, index) : index > 0 ? valueAt(full, index - 1) : NullNode.getInstance());
                }
            } else {
                for (int index = 0; index < times.size(); index++) values.add(evaluateNode(node, index, nodeValues));
            }
            nodeValues.put(node.path("id").asText(), values);
            for (int index = 0; index < outputRows.size(); index++) {
                JsonNode value = valueAt(values, index);
                if (!value.isMissingNode()) outputRows.get(index).set(node.path("id").asText(), value);
            }
        }
        ArrayNode outputs = outputIds(graph, ordered);
        ArrayNode resultRows = array();
        Map<String, Lineage> memo = new HashMap<>();
        for (int index = 0; index < outputRows.size(); index++) {
            ObjectNode row = outputRows.get(index);
            if (!requestedSet.contains(time(field(row, "decision_time")))) continue;
            ObjectNode resultRow = row.deepCopy();
            ObjectNode features = object(), featureLineage = object();
            for (JsonNode output : outputs) {
                String id = output.asText();
                JsonNode featureValue = field(row, id);
                if (!featureValue.isMissingNode()) features.set(id, featureValue.deepCopy());
                Lineage line = lineage(byId, id, memo);
                String scope = line.scopes.size() == 1 && line.scopes.contains("CONTEXT_ONLY")
                        ? "CONTEXT_ONLY" : "TRADEABLE_CRYPTO";
                featureLineage.set(id, object().put("evidence_family", byId.get(id).path("evidence_family").asText())
                        .put("trade_scope", scope));
            }
            resultRow.set("features", features); resultRow.set("feature_lineage", featureLineage); resultRows.add(resultRow);
        }
        ObjectNode result = object().put("graph_sha256", text(field(graph, "content_sha256")));
        result.set("rows", resultRows); result.set("outputs", outputs.deepCopy());
        boolean tradeable = true;
        for (JsonNode output : outputs) {
            Lineage line = lineage(byId, output.asText(), memo);
            if (line.scopes.size() == 1 && line.scopes.contains("CONTEXT_ONLY")) { tradeable = false; break; }
        }
        result.put("tradeable", tradeable);
        return result;
    }

    public static ObjectNode evaluateFeatureDagV5(JsonNode graph, ObjectNode options) {
        return evaluateFeatureGraphV5(graph, options);
    }

    public static ObjectNode planFeatureGraphV5(ObjectNode options) {
        ObjectNode source = options == null ? object() : options;
        JsonNode graph = field(source, "graph"); validateFeatureGraphV5(graph);
        Map<String, ObjectNode> byId = byId(objectList(field(graph, "nodes")));
        ObjectNode sourceRegistry = isObject(field(source, "sourceRegistry")) ? (ObjectNode) field(source, "sourceRegistry") : object();
        Map<String, Requirement> requirements = new LinkedHashMap<>(); Set<String> seen = new HashSet<>();
        ArrayNode outputs = (ArrayNode) field(graph, "outputs");
        for (JsonNode output : outputs) visitRequirement(byId.get(output.asText()), 0, 0, new LinkedHashSet<>(),
                byId, sourceRegistry, requirements, seen);
        List<ObjectNode> declarations = new ArrayList<>();
        for (Requirement requirement : requirements.values()) {
            ObjectNode row = object().put("source_series", requirement.sourceSeries).put("timeframe", requirement.timeframe)
                    .put("lookback_bars", requirement.lookbackBars).put("warmup_bars", requirement.warmupBars);
            ArrayNode nodes = row.putArray("nodes"); requirement.nodes.stream().sorted().distinct().forEach(nodes::add);
            ArrayNode stateful = row.putArray("stateful_nodes"); requirement.stateful.stream().sorted().distinct().forEach(stateful::add);
            row.put("checkpoint_state_required", !requirement.stateful.isEmpty()); declarations.add(row);
        }
        declarations.sort(Comparator.comparing(row -> row.path("source_series").asText() + '|' + row.path("timeframe").asText()));
        boolean fixture = field(graph, "fixture_only").asBoolean(false);
        ObjectNode result = object().put("schema", "strategy-v5-feature-plan/1").put("version", 1).put("status", "FROZEN")
                .put("fixture_only", fixture).put("provenance", fixture ? "FIXTURE/LEGACY_EXPOSED" : "AUTHORITATIVE")
                .put("graph_sha256", text(field(graph, "content_sha256")));
        copyNullable(result, "precommit_sha256", field(source, "precommit_sha256"));
        copyNullable(result, "config_sha256", field(source, "config_sha256"));
        result.put("code_sha256", FEATURE_DAG_CODE_SHA256).set("requirements", arrayOf(declarations));
        result.put("source_registry_sha256", hash(sourceRegistry)).putNull("content_sha256");
        result.put("content_sha256", ownHash(result)); return result;
    }

    public static ObjectNode deriveFeatureRequirementsV5(ObjectNode options) { return planFeatureGraphV5(options); }

    public static boolean assertTradeableFeatureGraphV5(JsonNode graph) {
        return assertTradeableFeatureGraphV5(graph, field(graph, "outputs"));
    }

    public static boolean assertTradeableFeatureGraphV5(JsonNode graph, JsonNode outputs) {
        validateFeatureGraphV5(graph); Map<String, ObjectNode> byId = byId(objectList(field(graph, "nodes")));
        if (outputs.isArray()) for (JsonNode output : outputs) {
            Lineage line = lineage(byId, output.asText(), new HashMap<>());
            if (line.scopes.size() == 1 && line.scopes.contains("CONTEXT_ONLY")) {
                throw failure("CONTEXT_ONLY feature " + output.asText() + " cannot produce a trade");
            }
        }
        return true;
    }

    public static boolean validateFeatureLineageV5(JsonNode graph) { return validateFeatureGraphV5(graph); }

    public static ObjectNode dedupeEvidenceVotesV5(ObjectNode options) {
        ObjectNode source = options == null ? object() : options; JsonNode graph = field(source, "graph");
        validateFeatureGraphV5(graph); Map<String, ObjectNode> byId = byId(objectList(field(graph, "nodes")));
        JsonNode outputs = truthy(field(source, "outputs")) ? field(source, "outputs") : field(graph, "outputs");
        JsonNode scores = isObject(field(source, "scores")) ? field(source, "scores") : object();
        Set<String> seen = new HashSet<>(); ArrayNode kept = array(), suppressed = array();
        if (outputs.isArray()) for (JsonNode output : outputs) {
            String id = output.asText(); Lineage line = lineage(byId, id, new HashMap<>());
            Set<String> evidence = line.physical.isEmpty() ? line.families : line.physical;
            List<String> orderedEvidence = evidence.stream().sorted().toList();
            List<String> overlap = orderedEvidence.stream().filter(seen::contains).sorted().toList();
            String identity = orderedEvidence.isEmpty() ? id : String.join("|", orderedEvidence);
            if (!overlap.isEmpty()) {
                ObjectNode row = object().put("id", id).put("physical_evidence_id", identity);
                ArrayNode shared = row.putArray("shared_with"); overlap.forEach(shared::add);
                row.put("reason", "SHARED_PHYSICAL_LINEAGE"); suppressed.add(row);
            } else {
                seen.addAll(evidence); ObjectNode row = object().put("id", id);
                if (present(scores, id)) row.set("score", field(scores, id).deepCopy());
                row.put("physical_evidence_id", identity); kept.add(row);
            }
        }
        ObjectNode result = object(); result.set("kept", kept); result.set("suppressed", suppressed);
        result.put("independent_vote_count", kept.size()); return result;
    }

    private static ObjectNode normalizeNode(JsonNode raw, int index) {
        if (!isObject(raw)) throw failure("feature node " + index + " is invalid");
        ObjectNode node = ((ObjectNode) raw).deepCopy();
        String op = text(or(field(node, "op"), field(node, "kind"), textNode(""))).toUpperCase(Locale.ROOT);
        if (!OPS.contains(op)) throw failure("unsupported feature operation " + (op.isEmpty() ? "?" : op));
        String id = text(or(field(node, "id"), field(node, "name"), textNode("feature_" + (index + 1))));
        if (!ID.matcher(id).matches()) throw failure("invalid feature node id " + id);
        JsonNode refsSource;
        if (truthy(field(node, "inputs"))) refsSource = field(node, "inputs");
        else if (truthy(field(node, "args"))) refsSource = field(node, "args");
        else if ("FIELD".equals(op)) refsSource = array();
        else if (present(node, "input")) { ArrayNode one = array(); one.add(field(node, "input")); refsSource = one; }
        else refsSource = array();
        if (!refsSource.isArray()) throw failure(id + " expects " + operands(op) + " operands");
        int count = refsSource.size(), expected = operands(op);
        if (count != expected && !(ROLLING.contains(op) && count >= 1) && !("FIELD".equals(op) && count == 0)) {
            throw failure(id + " expects " + expected + " operands");
        }
        ArrayNode refs = array();
        for (JsonNode ref : refsSource) refs.add(ref.deepCopy());
        node.put("id", id).put("op", op).set("inputs", refs);
        String scalarDefault = Set.of("AND", "OR", "NOT", "EQ", "NE", "GT", "GTE", "LT", "LTE", "IS_NULL",
                "CROSS_ABOVE", "CROSS_BELOW").contains(op) ? "boolean" : "number";
        String scalar = text(or(field(node, "scalar_type"), textNode(scalarDefault))).toLowerCase(Locale.ROOT);
        if (!Set.of("number", "integer", "boolean").contains(scalar)) {
            throw failure(id + " has unsupported scalar type " + scalar);
        }
        node.put("scalar_type", scalar);
        node.put("unit", text(or(field(node, "unit"), textNode("boolean".equals(scalar) ? "boolean" : "dimensionless"))));
        String current = text(or(field(node, "current_observation_policy"), field(node, "current_policy"),
                textNode("INCLUDE_CURRENT_COMPLETED")));
        if (!Set.of("INCLUDE_CURRENT_COMPLETED", "EXCLUDE_CURRENT_COMPLETED").contains(current)) {
            throw failure(id + " has invalid current observation policy");
        }
        node.put("current_observation_policy", current);
        String scope = truthy(field(node, "trade_scope")) ? text(field(node, "trade_scope"))
                : field(node, "context_only").asBoolean(false) ? "CONTEXT_ONLY" : "TRADEABLE_CRYPTO";
        if (!Set.of("TRADEABLE_CRYPTO", "CONTEXT_ONLY").contains(scope)) throw failure(id + " has invalid trade scope");
        node.put("trade_scope", scope);
        node.put("evidence_family", text(or(field(node, "evidence_family"), field(node, "source_family"),
                textNode("DERIVED:" + id))));
        if (!truthy(field(node, "physical_evidence_id"))) node.putNull("physical_evidence_id");
        if (present(node, "source_field")) node.put("source_field", text(field(node, "source_field")));
        if ("FIELD".equals(op) && !truthy(field(node, "source_field"))) throw failure(id + " FIELD requires source_field");
        if ("FIELD".equals(op) && !present(node, "source_series")) {
            node.put("source_series", text(or(field(node, "source"), textNode("primary"))));
        }
        if ("FIELD".equals(op) && text(field(node, "source_series")).isEmpty()) throw failure(id + " FIELD source_series is empty");
        if (ROLLING.contains(op)) {
            JsonNode requested = nullish(field(node, "lookback_bars"), field(node, "period"), field(node, "window"), numberNode(1));
            int lookback = Math.max(1, truncate(number(requested, id + ".lookback_bars")));
            int minimum = Math.max(1, truncate(number(nullish(field(node, "min_history"), numberNode(lookback)), id + ".min_history")));
            node.put("lookback_bars", lookback).put("min_history", minimum);
            if (minimum > lookback && !Set.of("EMA", "RSI", "ATR").contains(op)) {
                throw failure(id + ".min_history exceeds lookback_bars");
            }
        }
        if (Set.of("ZSCORE", "ROBUST_ZSCORE", "WINSORIZE", "PERCENTILE_RANK").contains(op)) {
            String policy = text(or(field(node, "fit_policy"), textNode("PRIOR_ONLY"))).toUpperCase(Locale.ROOT);
            if (!Set.of("PRIOR_ONLY", "SELF_INCLUSIVE").contains(policy)) throw failure(id + " has invalid fit_policy");
            node.put("fit_policy", policy);
        }
        if (Set.of("QUANTILE", "WINSORIZE").contains(op)) {
            node.put("quantile", number(nullish(field(node, "quantile"), numberNode(.5)), id + ".quantile"));
        }
        if ("WINSORIZE".equals(op)) {
            node.put("lower", number(nullish(field(node, "lower"), numberNode(.05)), id + ".lower"));
            node.put("upper", number(nullish(field(node, "upper"), numberNode(.95)), id + ".upper"));
        }
        if ("CLAMP".equals(op)) {
            double min = number(field(node, "min"), id + ".min"), max = number(field(node, "max"), id + ".max");
            if (max < min) throw failure(id + ".max below min"); node.put("min", min).put("max", max);
        }
        if ("RSI".equals(op)) {
            String method = text(or(field(node, "rsi_method"), textNode("WILDER_RSI"))).toUpperCase(Locale.ROOT);
            node.put("rsi_method", method);
            if (present(node, "lookback_bars")) node.set("rsi_period", field(node, "lookback_bars").deepCopy());
            if (!"WILDER_RSI".equals(method)) throw failure(id + " RSI method must be explicitly supported as WILDER_RSI");
        }
        if ("EMA".equals(op)) {
            String method = text(or(field(node, "ema_method"), textNode("RECURSIVE_EMA"))).toUpperCase(Locale.ROOT);
            node.put("ema_method", method);
            if (!"RECURSIVE_EMA".equals(method)) throw failure(id + " EMA method must be explicitly supported as RECURSIVE_EMA");
        }
        if ("TRUE_RANGE".equals(op) && refs.size() != 3) throw failure(id + " TRUE_RANGE requires high, low and prior-close inputs");
        if (Set.of("COVARIANCE", "CORRELATION", "BETA").contains(op) && refs.size() != 2) {
            throw failure(id + ' ' + op + " requires two aligned inputs");
        }
        if (Set.of("CROSS_ABOVE", "CROSS_BELOW").contains(op)) {
            for (JsonNode ref : refs) if (!ref.isTextual()) {
                throw failure(id + ' ' + op + " requires two feature-series operands; literal crossing levels are not supported");
            }
        }
        if ("FIELD".equals(op) && present(node, "asof_policy")
                && !"LATEST_AVAILABLE_NOT_AFTER_DECISION".equals(text(field(node, "asof_policy")))) {
            throw failure(id + " has invalid as-of policy");
        }
        if (present(node, "max_staleness_ms") && !(number(field(node, "max_staleness_ms"), id + ".max_staleness_ms") > 0)) {
            throw failure(id + ".max_staleness_ms must be positive");
        }
        return node;
    }

    private static int operands(String op) {
        if ("FIELD".equals(op)) return 0;
        if ("TRUE_RANGE".equals(op) || "IF".equals(op) || "CLAMP".equals(op)) return 3;
        if (Set.of("LAG", "ABS", "LOG", "IS_NULL", "RSI", "NOT").contains(op) || ROLLING.contains(op)) return 1;
        return 2;
    }

    private static List<String> refsOf(JsonNode node) {
        List<String> refs = new ArrayList<>(); JsonNode inputs = field(node, "inputs");
        if (inputs.isArray()) for (JsonNode input : inputs) if (input.isTextual()) refs.add(input.textValue());
        return refs;
    }

    private static List<ObjectNode> topological(List<ObjectNode> nodes) {
        Map<String, ObjectNode> byId = byId(nodes); Set<String> visiting = new HashSet<>(), visited = new HashSet<>();
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode node : nodes) visitTopological(node.path("id").asText(), byId, visiting, visited, result);
        return result;
    }

    private static void visitTopological(String id, Map<String, ObjectNode> byId, Set<String> visiting,
            Set<String> visited, List<ObjectNode> result) {
        if (visited.contains(id)) return;
        if (visiting.contains(id)) throw failure("feature graph cycle at " + id);
        ObjectNode node = byId.get(id); if (node == null) throw failure("feature graph references unknown node " + id);
        visiting.add(id); for (String ref : refsOf(node)) visitTopological(ref, byId, visiting, visited, result);
        visiting.remove(id); visited.add(id); result.add(node);
    }

    private static Lineage lineage(Map<String, ObjectNode> nodes, String id, Map<String, Lineage> memo) {
        if (memo.containsKey(id)) return memo.get(id);
        ObjectNode node = nodes.get(id); if (node == null) throw failure("feature graph references unknown node " + id);
        LinkedHashSet<String> families = new LinkedHashSet<>(); families.add(node.path("evidence_family").asText());
        LinkedHashSet<String> physical = new LinkedHashSet<>();
        if (truthy(field(node, "physical_evidence_id"))) physical.add(text(field(node, "physical_evidence_id")));
        LinkedHashSet<String> scopes = new LinkedHashSet<>(); scopes.add(node.path("trade_scope").asText());
        for (String ref : refsOf(node)) {
            Lineage child = lineage(nodes, ref, memo); families.addAll(child.families); physical.addAll(child.physical); scopes.addAll(child.scopes);
        }
        Lineage result = new Lineage(families, physical, scopes); memo.put(id, result); return result;
    }

    private static TypeUnit inferTypeUnit(ObjectNode node, Map<String, ObjectNode> byId) {
        if ("FIELD".equals(node.path("op").asText())) return new TypeUnit(node.path("scalar_type").asText(), node.path("unit").asText());
        List<TypeUnit> refs = new ArrayList<>(); for (String ref : refsOf(node)) refs.add(inferTypeUnit(byId.get(ref), byId));
        boolean numeric = refs.stream().allMatch(row -> NUMERIC.contains(row.type));
        boolean bool = refs.stream().allMatch(row -> "boolean".equals(row.type));
        String op = node.path("op").asText(), id = node.path("id").asText();
        if (Set.of("AND", "OR", "NOT").contains(op) && !bool) throw failure(id + " boolean operation requires boolean operands");
        if (Set.of("GT", "GTE", "LT", "LTE", "EQ", "NE").contains(op) && !(numeric || bool)) {
            throw failure(id + " comparison operands have incompatible types");
        }
        if (Set.of("ADD", "SUB", "MUL", "DIV", "MIN", "MAX", "MEDIAN", "SMA", "EMA", "SUM", "STDDEV", "VOL",
                "ZSCORE", "ROBUST_ZSCORE", "WINSORIZE", "TRUE_RANGE", "ATR", "SLOPE", "COVARIANCE", "CORRELATION",
                "BETA", "RATIO", "SPREAD", "RELATIVE_RETURN", "BASIS", "PCT_RETURN", "LOG_RETURN", "ABS", "LOG", "CLAMP")
                .contains(op) && !numeric) throw failure(id + " requires numeric operands");
        if (Set.of("ADD", "SUB").contains(op) && refs.size() == 2 && !refs.get(0).unit.equals(refs.get(1).unit)) {
            throw failure(id + " cannot combine units " + refs.get(0).unit + " and " + refs.get(1).unit);
        }
        if (Set.of("GT", "GTE", "LT", "LTE").contains(op) && refs.size() == 2 && !refs.get(0).unit.equals(refs.get(1).unit)) {
            throw failure(id + " compares incompatible units " + refs.get(0).unit + " and " + refs.get(1).unit);
        }
        String expected = "boolean".equals(node.path("scalar_type").asText()) ? "boolean" : node.path("unit").asText();
        return new TypeUnit(node.path("scalar_type").asText(), !expected.isEmpty() ? expected
                : refs.isEmpty() ? "dimensionless" : refs.get(0).unit);
    }

    private static List<SeriesRow> normalizeSeries(JsonNode series, String name) {
        List<JsonNode> values = asArray(series); List<SeriesRow> rows = new ArrayList<>();
        for (JsonNode raw : values) {
            rejectLabelKeys(raw, "series[" + name + ']');
            Long event = rowTime(raw, null); if (event == null) throw failure("series " + name + " row lacks event time");
            Long available = availability(raw); if (available == null) available = event;
            if (available < event) throw failure("series " + name + " availability precedes event");
            ObjectNode value = isObject(raw) ? ((ObjectNode) raw).deepCopy() : object();
            value.put("__event", event).put("__available", available); rows.add(new SeriesRow(value, event, available));
        }
        rows.sort(Comparator.comparingLong(row -> row.event));
        for (int index = 1; index < rows.size(); index++) if (rows.get(index).event == rows.get(index - 1).event) {
            throw failure("series " + name + " has duplicate event times");
        }
        return rows;
    }

    private static Map<String, List<SeriesRow>> seriesMap(JsonNode input) {
        Map<String, List<SeriesRow>> result = new LinkedHashMap<>();
        if (input.isArray()) { result.put("primary", normalizeSeries(input, "primary")); return result; }
        JsonNode source = isObject(input) ? or(field(input, "series"), field(input, "sources"), field(input, "rows"), input) : MissingNode.getInstance();
        if (!isObject(source)) { result.put("primary", new ArrayList<>()); return result; }
        source.fields().forEachRemaining(entry -> result.put(entry.getKey(), normalizeSeries(entry.getValue(), entry.getKey())));
        return result;
    }

    private static SeriesRow latestAsOf(List<SeriesRow> rows, long decision, boolean includeCurrent,
            Long maxStalenessMs, String gapPolicy) {
        SeriesRow selected = null;
        for (int index = rows.size() - 1; index >= 0; index--) {
            SeriesRow row = rows.get(index);
            if (row.event <= decision && (includeCurrent || row.event < decision) && row.available <= decision) { selected = row; break; }
        }
        if (selected == null || maxStalenessMs != null && decision - selected.available > maxStalenessMs) {
            if ("FAIL".equals(gapPolicy)) throw failure("PIT series observation is missing/stale at " + iso(decision));
            return null;
        }
        return selected;
    }

    private static List<JsonNode> recursiveEma(List<JsonNode> values, JsonNode node) {
        ArrayNode input = array(); values.forEach(input::add);
        ObjectNode options = object().put("period", node.path("lookback_bars").asInt())
                .put("minHistory", node.path("min_history").asInt());
        return nodeList(resumeRecursiveEmaV5(input, options).path("values"));
    }

    private static List<JsonNode> wilderRsi(List<JsonNode> values, int period) {
        ArrayNode input = array(); values.forEach(input::add);
        return nodeList(resumeWilderRsiV5(input, object().put("period", period)).path("values"));
    }

    private static JsonNode evalRolling(String op, List<JsonNode> values, int index, ObjectNode node,
            List<List<JsonNode>> allValues) {
        boolean fitPriorOnly = Set.of("ZSCORE", "ROBUST_ZSCORE", "WINSORIZE", "PERCENTILE_RANK").contains(op)
                && !"SELF_INCLUSIVE".equals(node.path("fit_policy").asText());
        boolean include = !fitPriorOnly && "INCLUDE_CURRENT_COMPLETED".equals(node.path("current_observation_policy").asText());
        List<Double> window = rolling(values, index, node.path("lookback_bars").asInt(), include);
        if (window.size() < node.path("min_history").asInt()) return NullNode.getInstance();
        if ("SMA".equals(op)) return nullableNumber(mean(window));
        if ("SUM".equals(op)) { double total = 0; for (double value : window) total += value; return numberNode(total); }
        if ("MIN".equals(op)) return numberNode(window.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN));
        if ("MAX".equals(op)) return numberNode(window.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN));
        if ("MEDIAN".equals(op)) return nullableNumber(quantile(window, .5));
        if ("QUANTILE".equals(op)) return nullableNumber(quantile(window, node.path("quantile").asDouble()));
        if ("PERCENTILE_RANK".equals(op)) {
            JsonNode current = valueAt(values, index); if (!finite(current)) return NullNode.getInstance();
            long count = window.stream().filter(value -> value <= numberJs(current)).count(); return numberNode((double) count / window.size());
        }
        if (Set.of("STDDEV", "VOL").contains(op)) return nullableNumber(std(window));
        if ("ZSCORE".equals(op)) {
            Double mean = mean(window), std = std(window); JsonNode current = valueAt(values, index);
            return mean == null || std == null || std == 0 || !finite(current) ? NullNode.getInstance()
                    : numberNode((numberJs(current) - mean) / std);
        }
        if ("ROBUST_ZSCORE".equals(op)) {
            Double median = quantile(window, .5); List<Double> deviations = new ArrayList<>();
            if (median != null) for (double value : window) deviations.add(Math.abs(value - median));
            Double mad = quantile(deviations, .5);
            return median == null || mad == null || mad == 0 ? NullNode.getInstance()
                    : numberNode((numberJs(valueAt(values, index)) - median) / (1.4826 * mad));
        }
        if ("WINSORIZE".equals(op)) {
            Double low = quantile(window, node.path("lower").asDouble()), high = quantile(window, node.path("upper").asDouble());
            JsonNode current = valueAt(values, index);
            return low == null || high == null || !finite(current) ? NullNode.getInstance()
                    : numberNode(Math.min(high, Math.max(low, numberJs(current))));
        }
        if ("EMA".equals(op)) {
            List<JsonNode> full = recursiveEma(values, node);
            return "INCLUDE_CURRENT_COMPLETED".equals(node.path("current_observation_policy").asText())
                    ? valueAt(full, index) : index > 0 ? valueAt(full, index - 1) : NullNode.getInstance();
        }
        if ("SLOPE".equals(op)) {
            double xbar = (window.size() - 1) / 2d; Double ybar = mean(window); double denominator = 0;
            for (int cursor = 0; cursor < window.size(); cursor++) denominator += Math.pow(cursor - xbar, 2);
            if (denominator == 0 || ybar == null) return NullNode.getInstance();
            double numerator = 0; for (int cursor = 0; cursor < window.size(); cursor++) {
                numerator += (cursor - xbar) * (window.get(cursor) - ybar);
            }
            return numberNode(numerator / denominator);
        }
        if (Set.of("COVARIANCE", "CORRELATION", "BETA").contains(op)) {
            List<JsonNode> other = allValues.isEmpty() ? List.of() : allValues.get(0);
            int end = include ? index : index - 1, start = Math.max(0, end - node.path("lookback_bars").asInt() + 1);
            List<Pair> pairs = pairwise(slice(values, start, end + 1), slice(other, start, end + 1));
            if (pairs.size() < node.path("min_history").asInt()) return NullNode.getInstance();
            List<Double> first = pairs.stream().map(Pair::first).toList(), second = pairs.stream().map(Pair::second).toList();
            double firstMean = mean(first), secondMean = mean(second), covariance = 0, variance = 0, firstVariance = 0;
            for (Pair pair : pairs) {
                covariance += (pair.first - firstMean) * (pair.second - secondMean);
                variance += Math.pow(pair.second - secondMean, 2); firstVariance += Math.pow(pair.first - firstMean, 2);
            }
            covariance /= pairs.size(); variance /= pairs.size(); firstVariance /= pairs.size();
            if ("COVARIANCE".equals(op)) return numberNode(covariance);
            if ("BETA".equals(op)) return variance != 0 ? numberNode(covariance / variance) : NullNode.getInstance();
            double firstStd = Math.sqrt(firstVariance), secondStd = Math.sqrt(variance);
            return firstStd != 0 && secondStd != 0 ? numberNode(covariance / (firstStd * secondStd)) : NullNode.getInstance();
        }
        if ("ATR".equals(op)) return nullableNumber(mean(window));
        return NullNode.getInstance();
    }

    private static JsonNode evaluateNode(ObjectNode node, int index, Map<String, List<JsonNode>> nodeValues) {
        List<JsonNode> inputs = new ArrayList<>();
        for (JsonNode input : field(node, "inputs")) {
            inputs.add(input.isTextual() ? valueAt(nodeValues.get(input.asText()), index) : input);
        }
        JsonNode a = inputs.isEmpty() ? MissingNode.getInstance() : inputs.get(0);
        JsonNode b = inputs.size() < 2 ? MissingNode.getInstance() : inputs.get(1);
        String op = node.path("op").asText(), id = node.path("id").asText();
        switch (op) {
            case "LAG" -> {
                int lag = Math.max(1, truncate(number(nullish(field(node, "lag_bars"), field(node, "period"), numberNode(1)), id + ".lag_bars")));
                return index >= lag ? valueAt(nodeValues.get(field(node, "inputs").get(0).asText()), index - lag) : NullNode.getInstance();
            }
            case "DIFF" -> { return binaryNumber(a, b, (left, right) -> left - right); }
            case "PCT_RETURN" -> {
                if (!cleanFinite(a) || !cleanFinite(b) || numberJs(b) == 0) return NullNode.getInstance();
                return numberNode(numberJs(a) / numberJs(b) - 1);
            }
            case "LOG_RETURN" -> {
                if (!cleanFinite(a) || !cleanFinite(b) || numberJs(a) <= 0 || numberJs(b) <= 0) return NullNode.getInstance();
                return numberNode(Math.log(numberJs(a) / numberJs(b)));
            }
            case "ADD" -> { return binaryNumber(a, b, Double::sum); }
            case "SUB" -> { return binaryNumber(a, b, (left, right) -> left - right); }
            case "MUL" -> { return binaryNumber(a, b, (left, right) -> left * right); }
            case "DIV" -> {
                if (!cleanFinite(a) || !cleanFinite(b) || numberJs(b) == 0) return NullNode.getInstance();
                return numberNode(numberJs(a) / numberJs(b));
            }
            case "ABS" -> { return cleanFinite(a) ? numberNode(Math.abs(numberJs(a))) : NullNode.getInstance(); }
            case "LOG" -> { return cleanFinite(a) && numberJs(a) > 0 ? numberNode(Math.log(numberJs(a))) : NullNode.getInstance(); }
            case "CLAMP" -> { return cleanFinite(a) ? numberNode(Math.min(node.path("max").asDouble(), Math.max(node.path("min").asDouble(), numberJs(a)))) : NullNode.getInstance(); }
            case "AND" -> { return BooleanNode.valueOf(inputs.stream().allMatch(value -> value.isBoolean() && value.booleanValue())); }
            case "OR" -> { return BooleanNode.valueOf(inputs.stream().anyMatch(value -> value.isBoolean() && value.booleanValue())); }
            case "NOT" -> { return a.isNull() || a.isMissingNode() ? NullNode.getInstance() : BooleanNode.valueOf(!truthy(a)); }
            case "IS_NULL" -> { return BooleanNode.valueOf(a.isNull() || a.isMissingNode()); }
            case "EQ" -> { return BooleanNode.valueOf(stable(a).equals(stable(b))); }
            case "NE" -> { return BooleanNode.valueOf(!stable(a).equals(stable(b))); }
            case "GT" -> { return BooleanNode.valueOf(cleanFinite(a) && cleanFinite(b) && numberJs(a) > numberJs(b)); }
            case "GTE" -> { return BooleanNode.valueOf(cleanFinite(a) && cleanFinite(b) && numberJs(a) >= numberJs(b)); }
            case "LT" -> { return BooleanNode.valueOf(cleanFinite(a) && cleanFinite(b) && numberJs(a) < numberJs(b)); }
            case "LTE" -> { return BooleanNode.valueOf(cleanFinite(a) && cleanFinite(b) && numberJs(a) <= numberJs(b)); }
            case "IF" -> { return truthy(inputs.get(0)) ? inputs.get(1) : inputs.get(2); }
            case "CROSS_ABOVE", "CROSS_BELOW" -> {
                if (index <= 0 || !cleanFinite(a) || !cleanFinite(b)) return BooleanNode.FALSE;
                String firstId = field(node, "inputs").get(0).asText(), secondId = field(node, "inputs").get(1).asText();
                JsonNode priorA = valueAt(nodeValues.get(firstId), index - 1), priorB = valueAt(nodeValues.get(secondId), index - 1);
                if (!cleanFinite(priorA) || !cleanFinite(priorB)) return BooleanNode.FALSE;
                boolean crossed = "CROSS_ABOVE".equals(op)
                        ? numberJs(priorA) <= numberJs(priorB) && numberJs(a) > numberJs(b)
                        : numberJs(priorA) >= numberJs(priorB) && numberJs(a) < numberJs(b);
                return BooleanNode.valueOf(crossed);
            }
            case "TRUE_RANGE" -> {
                JsonNode close = inputs.size() > 2 ? inputs.get(2) : MissingNode.getInstance();
                JsonNode prior = index > 0 ? valueAt(nodeValues.get(field(node, "inputs").get(2).asText()), index - 1) : NullNode.getInstance();
                if (!cleanFinite(a) || !cleanFinite(b)) return NullNode.getInstance();
                return !cleanFinite(prior) ? numberNode(numberJs(a) - numberJs(b))
                        : numberNode(Math.max(numberJs(a) - numberJs(b), Math.max(Math.abs(numberJs(a) - numberJs(prior)), Math.abs(numberJs(b) - numberJs(prior)))));
            }
            case "RSI" -> {
                List<JsonNode> values = wilderRsi(nodeValues.get(field(node, "inputs").get(0).asText()), node.path("lookback_bars").asInt());
                int offset = "INCLUDE_CURRENT_COMPLETED".equals(node.path("current_observation_policy").asText()) ? 0 : 1;
                return index >= offset ? valueAt(values, index - offset) : NullNode.getInstance();
            }
            default -> {
                if (ROLLING.contains(op)) {
                    List<List<JsonNode>> rest = new ArrayList<>();
                    for (int cursor = 1; cursor < field(node, "inputs").size(); cursor++) {
                        rest.add(nodeValues.get(field(node, "inputs").get(cursor).asText()));
                    }
                    return evalRolling(op, nodeValues.get(field(node, "inputs").get(0).asText()), index, node, rest);
                }
                if (Set.of("RATIO", "BASIS", "SPREAD").contains(op)) {
                    if (!cleanFinite(a) || !cleanFinite(b) || "RATIO".equals(op) && numberJs(b) == 0) return NullNode.getInstance();
                    return numberNode("RATIO".equals(op) ? numberJs(a) / numberJs(b) : numberJs(a) - numberJs(b));
                }
                if ("RELATIVE_RETURN".equals(op)) return binaryNumber(a, b, (left, right) -> left - right);
                return NullNode.getInstance();
            }
        }
    }

    private static void visitRequirement(ObjectNode node, int priorLookback, int priorWarmup,
            LinkedHashSet<String> priorStateful, Map<String, ObjectNode> byId, ObjectNode sourceRegistry,
            Map<String, Requirement> requirements, Set<String> seen) {
        if (node == null) return; String op = node.path("op").asText();
        int lag = truncate(numberJs(or(field(node, "lag_bars"), numberNode("LAG".equals(op) ? 1 : 0))));
        int ownLookback = truncate(numberJs(or(field(node, "lookback_bars"), numberNode(0)))) + lag;
        int ownWarmup = truncate(numberJs(or(field(node, "min_history"),
                Set.of("EMA", "RSI", "ATR").contains(op) ? or(field(node, "lookback_bars"), numberNode(1)) : numberNode(0))));
        int accumulatedLookback = priorLookback + ownLookback, accumulatedWarmup = priorWarmup + ownWarmup;
        LinkedHashSet<String> stateful = new LinkedHashSet<>(priorStateful);
        if (Set.of("EMA", "RSI").contains(op)) stateful.add(node.path("id").asText());
        String visitKey = node.path("id").asText() + '|' + accumulatedLookback + '|' + accumulatedWarmup + '|'
                + String.join(",", stateful);
        if (!seen.add(visitKey)) return;
        if ("FIELD".equals(op)) {
            String series = truthy(field(node, "source_series")) ? text(field(node, "source_series"))
                    : truthy(field(node, "source")) ? text(field(node, "source")) : "primary";
            JsonNode registered = field(sourceRegistry, series);
            String timeframe = truthy(field(node, "source_timeframe")) ? text(field(node, "source_timeframe"))
                    : truthy(field(node, "timeframe")) ? text(field(node, "timeframe"))
                    : truthy(field(registered, "timeframe")) ? text(field(registered, "timeframe")) : "unknown";
            String key = series + '|' + timeframe;
            Requirement requirement = requirements.computeIfAbsent(key, ignored -> new Requirement(series, timeframe));
            requirement.lookbackBars = Math.max(requirement.lookbackBars, accumulatedLookback);
            requirement.warmupBars = Math.max(requirement.warmupBars, accumulatedWarmup);
            requirement.nodes.add(node.path("id").asText()); requirement.stateful.addAll(stateful);
        }
        for (String ref : refsOf(node)) visitRequirement(byId.get(ref), accumulatedLookback, accumulatedWarmup,
                stateful, byId, sourceRegistry, requirements, seen);
    }

    private static String bindArtifact(JsonNode artifact, JsonNode supplied, String label) {
        if (!isObject(artifact)) return defined(supplied) ? text(supplied) : null;
        String actual = truthy(field(artifact, "content_sha256")) ? text(field(artifact, "content_sha256")) : ownHash(artifact);
        if (truthy(field(artifact, "content_sha256")) && !text(field(artifact, "content_sha256")).equals(ownHash(artifact))) {
            throw failure("feature graph binding artifact hash is invalid");
        }
        if (truthy(supplied) && !text(supplied).equals(actual)) throw failure(label + " binding does not match artifact content");
        return actual;
    }

    private static ArrayNode outputIds(JsonNode graph, List<ObjectNode> ordered) {
        JsonNode raw = field(graph, "outputs");
        if (raw.isArray() && !raw.isEmpty()) return (ArrayNode) raw;
        ArrayNode output = array();
        if (!ordered.isEmpty()) output.add(ordered.get(ordered.size() - 1).path("id").asText());
        return output;
    }

    private static Map<String, ObjectNode> byId(List<ObjectNode> nodes) {
        Map<String, ObjectNode> result = new LinkedHashMap<>();
        for (ObjectNode node : nodes) result.put(node.path("id").asText(), node);
        return result;
    }

    private static List<ObjectNode> objectList(JsonNode value) {
        List<ObjectNode> result = new ArrayList<>();
        if (value != null && value.isArray()) for (JsonNode row : value) if (row.isObject()) result.add((ObjectNode) row);
        return result;
    }

    private static List<JsonNode> nodeList(JsonNode value) {
        List<JsonNode> result = new ArrayList<>();
        if (value != null && value.isArray()) value.forEach(row -> result.add(row.deepCopy()));
        return result;
    }

    private static List<JsonNode> asArray(JsonNode value) {
        List<JsonNode> output = new ArrayList<>();
        if (value == null) return output;
        if (value.isArray()) value.forEach(output::add);
        else if (value.isObject()) value.elements().forEachRemaining(output::add);
        return output;
    }

    private static ArrayNode arrayOf(List<? extends JsonNode> nodes) {
        ArrayNode output = array(); nodes.forEach(output::add); return output;
    }

    private static JsonNode seriesRowsNode(List<SeriesRow> rows) {
        ArrayNode output = array(); rows.forEach(row -> output.add(row.value)); return output;
    }

    private static List<Long> distinct(List<Long> values) {
        List<Long> result = new ArrayList<>(); Long prior = null;
        for (Long value : values) if (prior == null || !prior.equals(value)) { result.add(value); prior = value; }
        return result;
    }

    private static Long rowTime(JsonNode row, Long fallback) {
        JsonNode value = nullish(field(row, "event_time"), field(row, "time"), field(row, "open_time"),
                field(row, "decision_time"), fallback == null ? MissingNode.getInstance() : numberNode(fallback));
        return defined(value) ? time(value) : null;
    }

    private static Long availability(JsonNode row) {
        JsonNode value = nullish(field(row, "availability_time"), field(row, "available_at"), field(row, "close_time"),
                field(row, "event_time"), field(row, "time"));
        return defined(value) ? time(value) : null;
    }

    private static ObjectNode cleanInternal(ObjectNode value) {
        ObjectNode output = object();
        value.fields().forEachRemaining(entry -> { if (!entry.getKey().startsWith("__")) output.set(entry.getKey(), entry.getValue().deepCopy()); });
        return output;
    }

    private static List<Double> rolling(List<JsonNode> values, int index, int count, boolean includeCurrent) {
        int end = includeCurrent ? index : index - 1, start = Math.max(0, end - count + 1);
        List<Double> output = new ArrayList<>();
        for (int cursor = start; cursor <= end && cursor < values.size(); cursor++) {
            JsonNode value = values.get(cursor);
            if (!value.isNull() && !value.isMissingNode() && finite(value)) output.add(numberJs(value));
        }
        return output;
    }

    private static Double quantile(List<Double> values, double q) {
        List<Double> sorted = values.stream().filter(Double::isFinite).sorted().toList();
        if (sorted.isEmpty()) return null;
        double position = Math.max(0, Math.min(sorted.size() - 1, (sorted.size() - 1) * q));
        int low = (int) Math.floor(position), high = (int) Math.ceil(position);
        return sorted.get(low) + (sorted.get(high) - sorted.get(low)) * (position - low);
    }

    private static Double mean(List<Double> values) {
        List<Double> rows = values.stream().filter(Double::isFinite).toList(); if (rows.isEmpty()) return null;
        double total = 0; for (double value : rows) total += value; return total / rows.size();
    }

    private static Double std(List<Double> values) {
        Double mean = mean(values); List<Double> rows = values.stream().filter(Double::isFinite).toList();
        if (mean == null || rows.size() < 2) return null;
        double total = 0; for (double value : rows) total += Math.pow(value - mean, 2); return Math.sqrt(total / rows.size());
    }

    private static List<Pair> pairwise(List<JsonNode> first, List<JsonNode> second) {
        List<Pair> result = new ArrayList<>();
        for (int index = 0; index < first.size(); index++) {
            JsonNode a = first.get(index), b = index < second.size() ? second.get(index) : MissingNode.getInstance();
            if (finite(a) && finite(b)) result.add(new Pair(numberJs(a), numberJs(b)));
        }
        return result;
    }

    private static List<JsonNode> slice(List<JsonNode> values, int start, int end) {
        if (values == null || end <= 0 || start >= values.size()) return List.of();
        return values.subList(Math.max(0, start), Math.max(Math.max(0, start), Math.min(values.size(), end)));
    }

    private static JsonNode binaryNumber(JsonNode first, JsonNode second, BinaryNumber operation) {
        return !cleanFinite(first) || !cleanFinite(second) ? NullNode.getInstance()
                : numberNode(operation.apply(numberJs(first), numberJs(second)));
    }

    private static boolean cleanFinite(JsonNode value) { return finite(value); }

    private static JsonNode valueAt(List<JsonNode> values, int index) {
        return values == null || index < 0 || index >= values.size() ? MissingNode.getInstance() : values.get(index);
    }

    private static JsonNode nullableNumber(Double value) { return value == null ? NullNode.getInstance() : numberNode(value); }

    private static void rejectLabelKeys(JsonNode value, String path) {
        if (value == null || !value.isContainerNode()) return;
        if (value.isArray()) {
            int index = 0; for (JsonNode child : value) rejectLabelKeys(child, path + '.' + index++); return;
        }
        value.fields().forEachRemaining(entry -> {
            String lower = entry.getKey().toLowerCase(Locale.ROOT);
            if (isLabelKey(lower)) throw failure("feature input contains inaccessible label/outcome column " + path + '.' + entry.getKey());
            if (entry.getValue().isContainerNode()) rejectLabelKeys(entry.getValue(), path + '.' + entry.getKey());
        });
    }

    private static boolean isLabelKey(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return LABEL_KEYS.contains(lower) || LABEL_PATTERN.matcher(lower).find() || TRADE_LABEL_PATTERN.matcher(lower).find();
    }

    private static String ownHash(JsonNode value) {
        JsonNode copy = value.deepCopy(); if (copy.isObject()) ((ObjectNode) copy).remove("content_sha256");
        return hash(copy);
    }

    private static String stable(JsonNode value) {
        return value == null || value.isMissingNode() ? "__JS_UNDEFINED__" : CanonicalJson.canonicalize(value);
    }

    private static boolean finite(JsonNode value) { return Double.isFinite(numberJs(value)); }

    private static double number(JsonNode value, String label) {
        double result = numberJs(value); if (!Double.isFinite(result)) throw failure(label + " must be finite"); return result;
    }

    /** JavaScript Number conversion for the scalar forms accepted by the frozen graph. */
    private static double numberJs(JsonNode value) {
        if (value == null || value.isMissingNode()) return Double.NaN;
        if (value.isNull()) return 0;
        if (value.isNumber()) return value.doubleValue();
        if (value.isBoolean()) return value.booleanValue() ? 1 : 0;
        if (value.isTextual()) {
            String text = value.textValue().trim(); if (text.isEmpty()) return 0;
            try { return Double.parseDouble(text); } catch (NumberFormatException ignored) { return Double.NaN; }
        }
        if (value.isArray() && value.isEmpty()) return 0;
        return Double.NaN;
    }

    private static int truncate(double value) { return (int) (value < 0 ? Math.ceil(value) : Math.floor(value)); }

    private static long time(JsonNode value) {
        if (value != null && value.isNumber()) {
            double number = value.doubleValue(); if (!Double.isFinite(number)) throw failure("invalid timestamp " + text(value));
            return (long) number;
        }
        String text = text(value);
        try { return Instant.parse(text).toEpochMilli(); }
        catch (DateTimeParseException first) {
            try { return OffsetDateTime.parse(text).toInstant().toEpochMilli(); }
            catch (DateTimeParseException second) {
                try { return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli(); }
                catch (DateTimeParseException third) { throw failure("invalid timestamp " + text); }
            }
        }
    }

    private static String iso(long value) { return JS_ISO.format(Instant.ofEpochMilli(value)); }

    private static boolean isObject(JsonNode value) { return value != null && value.isObject(); }
    private static boolean defined(JsonNode value) { return value != null && !value.isMissingNode() && !value.isNull(); }
    private static boolean present(JsonNode value, String key) { return isObject(value) && value.has(key); }
    private static boolean present(JsonNode value, String key, boolean expected) {
        return present(value, key) && field(value, key).isBoolean() && field(value, key).booleanValue() == expected;
    }
    private static boolean truthy(JsonNode value) {
        if (!defined(value)) return false;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.doubleValue() != 0 && !Double.isNaN(value.doubleValue());
        if (value.isTextual()) return !value.textValue().isEmpty();
        return true;
    }
    private static JsonNode field(JsonNode value, String key) {
        return isObject(value) && value.has(key) ? value.get(key) : MissingNode.getInstance();
    }
    private static JsonNode nullish(JsonNode... values) {
        for (JsonNode value : values) if (defined(value)) return value; return MissingNode.getInstance();
    }
    private static JsonNode or(JsonNode... values) {
        for (JsonNode value : values) if (truthy(value)) return value; return MissingNode.getInstance();
    }
    private static String text(JsonNode value) {
        if (value == null || value.isMissingNode()) return "undefined";
        if (value.isNull()) return "null";
        if (value.isTextual()) return value.textValue();
        if (value.isNumber() || value.isBoolean()) return value.asText();
        return value.isArray() && value.isEmpty() ? "" : "[object Object]";
    }
    private static String nullableText(JsonNode value) { return defined(value) ? text(value) : null; }
    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }
    private static ArrayNode array() { return JsonHashes.mapper().createArrayNode(); }
    private static JsonNode numberNode(double value) { return JsonHashes.mapper().getNodeFactory().numberNode(value); }
    private static JsonNode numberNode(long value) { return JsonHashes.mapper().getNodeFactory().numberNode(value); }
    private static JsonNode textNode(String value) { return JsonHashes.mapper().getNodeFactory().textNode(value); }
    private static void putNullable(ObjectNode target, String key, String value) {
        if (value == null) target.putNull(key); else target.put(key, value);
    }
    private static void putNullableNumber(ObjectNode target, String key, Double value) {
        if (value == null) target.putNull(key); else target.put(key, value);
    }
    private static void copyNullable(ObjectNode target, String key, JsonNode value) {
        if (!defined(value)) target.putNull(key); else target.set(key, value.deepCopy());
    }
    private static IllegalArgumentException failure(String message) { return new IllegalArgumentException(message); }

    private record TypeUnit(String type, String unit) {}
    private record Lineage(LinkedHashSet<String> families, LinkedHashSet<String> physical, LinkedHashSet<String> scopes) {}
    private record Identity(String sourceField, String scalarType, String unit, String tradeScope) {}
    private record SeriesRow(ObjectNode value, long event, long available) {}
    private record Pair(double first, double second) {}
    @FunctionalInterface private interface BinaryNumber { double apply(double first, double second); }
    private static final class Requirement {
        private final String sourceSeries, timeframe;
        private int lookbackBars, warmupBars;
        private final List<String> nodes = new ArrayList<>(), stateful = new ArrayList<>();
        private Requirement(String sourceSeries, String timeframe) { this.sourceSeries = sourceSeries; this.timeframe = timeframe; }
    }
}

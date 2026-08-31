package com.tradinganalytics.research.swing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.tradinganalytics.contracts.hash.Sha256;
import com.tradinganalytics.contracts.json.CanonicalJson;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.core.swing.SwingScore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Deterministic, network-free Java port of {@code tools/swing-engine.mjs}.
 *
 * <p>Contract objects intentionally remain JSON trees: the source accepts
 * extensible feature metadata and emits a stable JSON artifact rather than a
 * closed domain schema. Every public Java method is pure except the explicitly
 * named feature-store file methods.</p>
 */
public final class SwingEngine {
    public static final String ENGINE_VERSION = "swing-engine/1";
    public static final String FEATURE_STORE_SCHEMA = "swing-feature-store/1";
    public static final String RUN_SCHEMA = "swing-backtest/1";
    public static final long BAR_MS = 4L * 60 * 60 * 1000;
    public static final int MAX_HOLD_BARS = 180;
    public static final double DEFAULT_INITIAL_EQUITY = 100_000;

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double EPS = 1e-12;
    private static final double PORTFOLIO_RISK_PCT = 1.5;
    private static final double ASSET_RISK_PCT = 3;
    private static final Pattern TIMEFRAME = Pattern.compile("^(\\d+)(m|h|d)$");
    private static final Pattern FACTOR_PATH = Pattern.compile("^factors\\.[a-zA-Z0-9_.]+$");
    private static final List<String> LEG_NAMES = List.of("flow", "technical", "macro", "sentiment", "valuation", "structure");
    private static final Map<String, Integer> LEG_MAXES = Map.of(
            "flow", 5, "technical", 4, "macro", 3, "sentiment", 3, "valuation", 3, "structure", 2);
    private static final List<String> BASE_COLUMNS = List.of("time", "open", "high", "low", "close", "volume",
            "funding_rate", "funding_event_time", "equity_usd", "stop_distance_pct");
    private static final Set<String> FACTOR_OPS = Set.of("gt", "gte", "lt", "lte", "eq", "neq", "between", "in");
    private static final Set<String> FUTURE_LABEL_KEYS = Set.of("outcome", "outcomes", "resolution_bars", "resolved_at",
            "forward_return", "future_return", "forward_pnl", "future_pnl", "long_early_capture", "short_early_capture");

    private SwingEngine() {}

    /** Hashes strings as raw UTF-8 and every other value as canonical JSON. */
    public static String sha256(Object value) {
        return value instanceof String text ? Sha256.hex(text) : Sha256.hex(CanonicalJson.canonicalBytes(value));
    }

    public static ObjectNode buildFeatureStore(JsonNode input) {
        return buildFeatureStore(input, JSON.objectNode(), Clock.systemUTC());
    }

    public static ObjectNode buildFeatureStore(JsonNode input, JsonNode options) {
        return buildFeatureStore(input, options, Clock.systemUTC());
    }

    public static ObjectNode buildFeatureStore(JsonNode input, JsonNode options, Clock clock) {
        LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (ObjectNode dataset : extractDatasets(input)) {
            JsonNode rowsNode = present(dataset, "features") ? dataset.get("features") : dataset.get("rows");
            if (rowsNode == null || !rowsNode.isArray()) continue;
            for (JsonNode rawNode : rowsNode) {
                if (!rawNode.isObject()) continue;
                String leakedPath = futureLabelPath(rawNode, "");
                if (leakedPath != null) throw new IllegalArgumentException("feature row contains future-label field " + leakedPath);
                ObjectNode row = normalizeRow((ObjectNode) rawNode, dataset);
                String key = textOr(row.get("asset"), "UNKNOWN") + '|' + textOr(row.get("timeframe"), "4h") + '|'
                        + textOr(row.get("framework"), "UNSCOPED") + '|' + textOr(row.get("channel"), "A");
                String identity = key + '|' + row.path("time").asText();
                if (!seen.add(identity)) continue;
                Group group = groups.computeIfAbsent(key, ignored -> new Group(nullableText(row.get("asset")),
                        textOr(row.get("timeframe"), "4h"), firstText(dataset.get("framework"), row.get("framework")),
                        firstText(dataset.get("channel"), row.get("channel"))));
                group.rows.add(row);
            }
        }
        ArrayNode datasets = JSON.arrayNode();
        long rowCount = 0;
        for (Group group : groups.values()) {
            group.rows.sort(Comparator.comparingDouble(row -> number(row.get("time"))));
            ObjectNode columns = JSON.objectNode();
            for (String field : BASE_COLUMNS) {
                ArrayNode values = JSON.arrayNode();
                for (ObjectNode row : group.rows) values.add(copyOrNull(row.get(field)));
                columns.set(field, values);
            }
            ArrayNode metadata = JSON.arrayNode();
            for (ObjectNode row : group.rows) {
                ObjectNode copy = row.deepCopy();
                BASE_COLUMNS.forEach(copy::remove);
                metadata.add(copy);
            }
            ObjectNode dataset = JSON.objectNode();
            dataset.set("asset", textOrNull(group.asset));
            dataset.put("timeframe", group.timeframe);
            dataset.set("framework", textOrNull(group.framework));
            dataset.set("channel", textOrNull(group.channel));
            dataset.set("columns", columns);
            dataset.set("metadata", metadata);
            datasets.add(dataset);
            rowCount += metadata.size();
        }
        boolean pit = option(options, "pointInTimeSafe", "point_in_time_safe") != null
                ? bool(option(options, "pointInTimeSafe", "point_in_time_safe"), false)
                : bool(input == null ? null : input.get("point_in_time_safe"), false);
        JsonNode source = option(options, "source");
        ObjectNode store = JSON.objectNode().put("schema", FEATURE_STORE_SCHEMA).put("engine", ENGINE_VERSION)
                .put("interval", "4h").put("point_in_time_safe", pit);
        store.set("source", source == null ? NullNode.instance : source.deepCopy());
        store.put("created_at", jsIso(clock.instant()));
        store.set("datasets", datasets);
        store.put("row_count", rowCount);
        store.put("features_sha256", featureHash(store));
        return store;
    }

    public static ArrayNode decodeFeatureStore(JsonNode store) {
        if (store == null || !FEATURE_STORE_SCHEMA.equals(nullableText(store.get("schema")))) {
            throw new IllegalArgumentException("unsupported feature store: " + textOr(store == null ? null : store.get("schema"), "missing"));
        }
        ArrayNode out = JSON.arrayNode();
        for (JsonNode datasetNode : array(store.get("datasets"))) {
            ObjectNode dataset = (ObjectNode) datasetNode;
            ArrayNode metadata = array(dataset.get("metadata"));
            JsonNode timeColumn = dataset.path("columns").get("time");
            int count = !metadata.isEmpty() ? metadata.size() : timeColumn != null && timeColumn.isArray() ? timeColumn.size() : 0;
            for (int index = 0; index < count; index++) {
                ObjectNode row = metadata.has(index) && metadata.get(index).isObject()
                        ? ((ObjectNode) metadata.get(index)).deepCopy() : JSON.objectNode();
                for (String field : BASE_COLUMNS) {
                    JsonNode column = dataset.path("columns").get(field);
                    row.set(field, column != null && column.isArray() && column.has(index) ? column.get(index).deepCopy() : NullNode.instance);
                }
                row.set("asset", copyOrNull(dataset.get("asset")));
                row.set("timeframe", copyOrNull(dataset.get("timeframe")));
                row.set("framework", copyOrNull(dataset.get("framework")));
                row.set("channel", copyOrNull(dataset.get("channel")));
                out.add(row);
            }
        }
        sortArray(out, Comparator.comparingDouble(row -> number(row.get("time"))));
        return out;
    }

    public static boolean verifyFeatureStoreHash(JsonNode store) {
        return store != null && truthy(store.get("features_sha256"))
                && Objects.equals(store.path("features_sha256").asText(), featureHash(store));
    }

    public record FeatureStoreWrite(Path path, String sha256, long bytes) {
        public ObjectNode toJson() {
            return JSON.objectNode().put("path", path.toString()).put("sha256", sha256).put("bytes", bytes);
        }
    }

    public static FeatureStoreWrite writeFeatureStore(Path path, JsonNode store) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        byte[] body = (MAPPER.writeValueAsString(store) + '\n').getBytes(StandardCharsets.UTF_8);
        byte[] encoded = absolute.toString().endsWith(".gz") ? gzip(body) : body;
        Files.write(absolute, encoded, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return new FeatureStoreWrite(absolute, sha256(store), encoded.length);
    }

    public static ArrayNode readFeatureStore(Path path) throws IOException {
        return decodeFeatureStore(readFeatureStoreArtifact(path));
    }

    public static ObjectNode readFeatureStoreArtifact(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path.toAbsolutePath().normalize());
        if (path.toString().endsWith(".gz")) bytes = gunzip(bytes);
        JsonNode parsed = MAPPER.readTree(bytes);
        if (!parsed.isObject()) throw new IllegalArgumentException("unsupported feature store: missing");
        ObjectNode store = (ObjectNode) parsed;
        if (!verifyFeatureStoreHash(store)) throw new IllegalArgumentException("feature-store hash mismatch; refuse tampered cache");
        return store;
    }

    /** Normalizes and validates the declarative candidate contract. */
    public static ObjectNode normalizeCandidate(JsonNode inputNode) {
        ObjectNode input = inputNode != null && inputNode.isObject() ? (ObjectNode) inputNode : JSON.objectNode();
        JsonNode sourceRaw = input.path("raw").isObject() ? input.get("raw") : input;
        String requestedFramework = nullableText(input.get("framework"));
        String framework = "fallen_knives".equals(requestedFramework) ? "fallen_knives"
                : "flying_rocket".equals(requestedFramework) ? "flying_rocket" : null;
        if (framework == null) throw new IllegalArgumentException("candidate.framework must be fallen_knives or flying_rocket");
        String expectedDirection = framework.equals("fallen_knives") ? "long" : "short";
        String direction = truthy(input.get("direction")) ? input.get("direction").asText() : expectedDirection;
        if (!expectedDirection.equals(direction)) throw new IllegalArgumentException("candidate direction does not match framework");
        String channel = framework.equals("flying_rocket") && "B".equals(nullableText(input.get("channel"))) ? "B"
                : framework.equals("flying_rocket") ? "A" : null;
        String phase = truthy(input.get("phase")) ? input.get("phase").asText() : "1A";
        Double thresholdBase = defaultThreshold(framework, channel, phase);
        if (thresholdBase == null) throw new IllegalArgumentException("unsupported phase " + phase + " for " + framework + '/' + (channel == null ? "" : channel));
        if (framework.equals("flying_rocket") && "B".equals(channel) && "3".equals(phase))
            throw new IllegalArgumentException("Flying Rocket B has no Phase 3");
        int triggerWindow = trunc(numberOr(option(input, "trigger_window_bars", "trigger_freshness_bars"), 2));
        if (triggerWindow < 1 || triggerWindow > 2) throw new IllegalArgumentException("trigger freshness must be 1 or 2 completed bars");
        int maxHold = trunc(numberOr(option(input, "time_stop_bars", "max_hold_bars"), 180));
        if (maxHold < 1 || maxHold > MAX_HOLD_BARS) throw new IllegalArgumentException("time stop must be between 1 and 180 bars");
        int maxConcurrent = trunc(numberOr(input.get("max_concurrent"), 1));
        if (maxConcurrent < 1) throw new IllegalArgumentException("max_concurrent must be at least 1");
        if (maxConcurrent != 1) throw new IllegalArgumentException("max_concurrent > 1 is unsupported until the capital-aware scheduler is authoritative");
        Double stopPct = finite(option(input, "stop_pct", "stop_distance_pct"));
        double ceiling = stopCeiling(framework, channel, phase);
        if (stopPct != null && (stopPct <= 0 || stopPct > ceiling))
            throw new IllegalArgumentException("stop_pct must be >0 and <=" + jsNumber(ceiling) + "% for this phase/channel");
        Double stopAtr = finite(input.get("stop_atr_multiple"));
        double stopMin = numberOr(input.get("stop_min_pct"), 1);
        double stopMax = numberOr(input.get("stop_max_pct"), ceiling);
        if (stopAtr != null && stopAtr <= 0) throw new IllegalArgumentException("stop_atr_multiple must be positive");
        if (stopPct != null && stopAtr != null) throw new IllegalArgumentException("declare either stop_pct or stop_atr_multiple, not both");
        if (!(stopMin > 0 && stopMax >= stopMin && stopMax <= ceiling))
            throw new IllegalArgumentException("dynamic stop bounds must satisfy 0 < min <= max <=" + jsNumber(ceiling) + "%");
        Double activeFrom = timeBound(input.get("active_from"), "active_from");
        Double activeTo = timeBound(input.get("active_to"), "active_to");
        if (activeFrom != null && activeTo != null && activeTo <= activeFrom)
            throw new IllegalArgumentException("active_to must be later than active_from");
        Double targetR = finite(option(input, "target_r", "take_profit_r"));
        if (targetR != null && targetR <= 0) throw new IllegalArgumentException("target_r must be positive");
        double phaseCap = SwingScore.phaseCaps(framework).get(phase);
        double capPct = numberOr(input.get("cap_pct"), phaseCap);
        if (!Double.isFinite(capPct) || capPct <= 0 || capPct > phaseCap)
            throw new IllegalArgumentException("cap_pct cannot exceed " + jsNumber(phaseCap) + "%");

        ArrayNode excluded = distinctStrings(arrayish(input.get("excluded_score_legs")), true);
        for (JsonNode leg : excluded) if (!LEG_NAMES.contains(leg.asText()))
            throw new IllegalArgumentException("excluded_score_legs contains an unsupported leg");
        String normalization = truthy(input.get("score_normalization")) ? input.get("score_normalization").asText()
                : excluded.isEmpty() ? "none" : "included_max_to_20";
        if (!Set.of("none", "included_max_to_20").contains(normalization))
            throw new IllegalArgumentException("score_normalization is unsupported");
        ObjectNode minState = minimumMap(option(input, "min_state", "state_leg_minimums"));
        ObjectNode minImpulse = minimumMap(option(input, "min_impulse", "impulse_leg_minimums"));
        Double explicitThreshold = finite(option(input, "score_threshold", "threshold"));
        double offset = numberOr(input.get("threshold_offset"), 0);

        ObjectNode out = JSON.objectNode();
        String id = truthy(input.get("id")) ? input.get("id").asText()
                : framework + ':' + (channel == null ? "A" : channel) + ':' + phase + ':'
                    + (truthy(input.get("setup_family")) ? input.get("setup_family").asText() : "ALL") + ':' + direction;
        out.put("id", id).put("framework", framework).put("direction", direction).set("channel", textOrNull(channel));
        out.put("phase", phase);
        putNullableNumber(out, "score_threshold", explicitThreshold);
        putNumber(out, "threshold_offset", offset);
        putNumber(out, "threshold", explicitThreshold == null ? thresholdBase + offset : explicitThreshold);
        out.set("excluded_score_legs", excluded); out.put("score_normalization", normalization);
        out.set("min_state", minState); out.set("min_impulse", minImpulse);
        out.set("factor_filters", normalizeFactorFilters(option(input, "factor_filters", "filters")));
        out.put("min_flow_aligned", Math.max(0, trunc(numberOr(option(input, "min_flow_aligned", "aligned_rows_min"), 0))));
        out.set("setup_families", upperStrings(arrayish(option(input, "setup_families", "setup_family"))));
        out.put("trigger_window_bars", triggerWindow).put("max_hold_bars", maxHold);
        putNullableNumber(out, "stop_pct", stopPct); putNullableNumber(out, "stop_atr_multiple", stopAtr);
        putNumber(out, "stop_min_pct", stopMin); putNumber(out, "stop_max_pct", stopMax); putNumber(out, "stop_ceiling_pct", ceiling);
        putNullableNumber(out, "target_r", targetR); putNumber(out, "cap_pct", capPct);
        putNumber(out, "partial_exit_pct", clamp(numberOr(input.get("partial_exit_pct"), 0), 0, 1));
        putNullableNumber(out, "partial_target_r", finite(input.get("partial_target_r")));
        out.put("ratchet_to_entry", bool(input.get("ratchet_to_entry"), false) || "entry".equals(nullableText(input.get("ratchet"))));
        out.set("regime", strings(arrayish(option(input, "regime", "regimes")), false));
        out.set("assets", strings(arrayish(option(input, "assets", "asset")), true));
        out.set("timeframes", strings(arrayish(option(input, "timeframes", "timeframe")), true));
        putNumber(out, "fee_pct", numberOr(option(input, "fee_pct", "fee_pct_one_way"), 0.1));
        putNumber(out, "slippage_pct", numberOr(option(input, "slippage_pct", "slippage_pct_one_way"), 0.05));
        out.put("funding_debit", !present(input, "funding_debit") || !input.get("funding_debit").isBoolean() || input.get("funding_debit").booleanValue());
        putNumber(out, "initial_equity", numberOr(input.get("initial_equity"), DEFAULT_INITIAL_EQUITY));
        out.put("max_concurrent", maxConcurrent).put("concurrency_policy", "SINGLE_EPISODE");
        putNullableNumber(out, "active_from", activeFrom); putNullableNumber(out, "active_to", activeTo);
        out.put("require_protective_controls", framework.equals("flying_rocket") || bool(input.get("require_protective_controls"), false));
        out.set("_state", minState.isEmpty() ? NullNode.instance : minState.deepCopy());
        out.set("_impulse", minImpulse.isEmpty() ? NullNode.instance : minImpulse.deepCopy());
        out.set("raw", sourceRaw.deepCopy());
        return out;
    }

    public static boolean candidateMatches(JsonNode rowInput, JsonNode candidateInput) {
        ObjectNode candidate = isNormalizedCandidate(candidateInput) ? ((ObjectNode) candidateInput).deepCopy() : normalizeCandidate(candidateInput);
        ObjectNode row = rowInput != null && rowInput.isObject() ? ((ObjectNode) rowInput).deepCopy() : JSON.objectNode();
        if (finite(row.get("mechanical_score")) == null) row = normalizeRow(row, row);
        return candidateMatchesNormalized(row, candidate);
    }

    private static boolean candidateMatchesNormalized(ObjectNode row, ObjectNode candidate) {
        if (isFalse(row.get("timestamp_safe")) || isFalse(row.get("no_lookahead"))
                && isFalse(row.path("source_coverage").get("point_in_time_safe"))) return false;
        if (!matchesListed(candidate.get("assets"), lower(nullableText(row.get("asset"))))) return false;
        double time = number(row.get("time"));
        Double activeFrom = finite(candidate.get("active_from")), activeTo = finite(candidate.get("active_to"));
        if (activeFrom != null && time < activeFrom || activeTo != null && time >= activeTo) return false;
        if (!matchesListed(candidate.get("timeframes"), lower(nullableText(row.get("timeframe"))))) return false;
        if (truthy(row.get("framework")) && !Objects.equals(nullableText(row.get("framework")), nullableText(candidate.get("framework")))) return false;
        if ("flying_rocket".equals(nullableText(candidate.get("framework"))) && truthy(row.get("channel"))
                && !Objects.equals(nullableText(row.get("channel")), nullableText(candidate.get("channel")))) return false;
        for (String field : List.of("open", "high", "low", "close")) if (finite(row.get(field)) == null) return false;
        Double score = candidateScore(row, candidate);
        if (score == null || score < number(candidate.get("threshold"))) return false;
        if (!legPass(row, "state", candidate.path("min_state")) || !legPass(row, "impulse", candidate.path("min_impulse"))) return false;
        if (!factorFiltersPass(row, candidate.path("factor_filters"))) return false;
        if (finite(row.get("flow_aligned_rows")) == null) return false;
        if (truthy(row.get("flow_coverage")) && !"COMPLETE".equals(nullableText(row.get("flow_coverage")).toUpperCase(Locale.ROOT))) return false;
        if (numberOr(row.get("flow_aligned_rows"), 0) < number(candidate.get("min_flow_aligned"))) return false;
        if (!rowSetupMatches(row, candidate) || !triggerPass(row, candidate)) return false;
        if (!matchesListed(candidate.get("regime"), textOr(row.get("regime"), "UNKNOWN"))) return false;
        JsonNode controls = truthy(row.get("protective_controls")) ? row.get("protective_controls") : row.path("risk_controls");
        if (bool(candidate.get("require_protective_controls"), false)
                && (!bool(controls.get("stop_valid"), false) || !bool(controls.get("time_stop_valid"), false)
                    || !bool(controls.get("ratchet_valid"), false) || bool(controls.get("carry_veto"), false))) return false;
        return true;
    }

    /** Simulates one completed-bar signal with next-bar entry and OHLC lifecycle. */
    public static ObjectNode simulateTrade(ArrayNode rows, int signalIndex, JsonNode candidateInput) {
        return simulateTrade(rows, signalIndex, candidateInput, JSON.objectNode());
    }

    public static ObjectNode simulateTrade(ArrayNode rows, int signalIndex, JsonNode candidateInput, JsonNode options) {
        ObjectNode candidate = isNormalizedCandidate(candidateInput) ? ((ObjectNode) candidateInput).deepCopy() : normalizeCandidate(candidateInput);
        ObjectNode signal = option(options, "signal") != null && option(options, "signal").isObject()
                ? (ObjectNode) option(options, "signal") : (ObjectNode) rows.get(signalIndex);
        long expected = longNumber(signal.get("time")) + timeframeMs(textOr(signal.get("timeframe"), "4h"));
        int entryIndex = signalIndex + 1;
        if (!rows.has(entryIndex) || longNumber(rows.get(entryIndex).get("time")) != expected)
            return status("NO_NEXT_BAR", signal);
        ObjectNode entryBar = (ObjectNode) rows.get(entryIndex);
        String direction = candidate.path("direction").asText();
        Double entryRaw = finite(entryBar.get("open"));
        if (entryRaw == null || entryRaw <= 0) return status("NO_FILL", signal);
        double equity = numberOr(option(options, "equity"), number(candidate.get("initial_equity")));
        Double close = finite(signal.get("close")), atr = finite(signal.get("atr_20d"));
        Double signalAtrPct = close != null && close > 0 && atr != null && atr > 0 ? 100 * atr / close : null;
        Double stopAtrMultiple = finite(candidate.get("stop_atr_multiple"));
        Double dynamicStop = stopAtrMultiple != null && signalAtrPct != null
                ? clamp(signalAtrPct * stopAtrMultiple, number(candidate.get("stop_min_pct")), number(candidate.get("stop_max_pct"))) : null;
        Double stopPctNode = finite(candidate.get("stop_pct"));
        double stopPct = stopPctNode != null ? stopPctNode : dynamicStop != null ? dynamicStop : numberOr(signal.get("stop_distance_pct"), 0);
        if (!(stopPct > 0 && stopPct <= number(candidate.get("stop_ceiling_pct"))))
            return status("RISK_BLOCKED", signal).put("reason", "missing_or_invalid_stop_or_ceiling");
        double capPct = Math.min(number(candidate.get("cap_pct")), numberOr(signal.get("cap_pct"), number(candidate.get("cap_pct"))));
        double stopFraction = stopPct / 100;
        double portfolioRiskNotional = equity * (PORTFOLIO_RISK_PCT / 100) / stopFraction;
        double assetRiskNotional = equity * (ASSET_RISK_PCT / 100) / stopFraction;
        double phaseNotional = equity * capPct / 100;
        double notional = Math.max(0, Math.min(phaseNotional, Math.min(portfolioRiskNotional, assetRiskNotional)));
        if (!(notional > 0)) return status("RISK_BLOCKED", signal).put("reason", "no_equity");
        double entry = fillPrice(entryRaw, direction, number(candidate.get("slippage_pct")), true);
        double riskPerUnit = entry * stopPct / 100;
        double targetR = numberOr(candidate.get("target_r"), 1.5);
        double stop = direction.equals("long") ? entry - riskPerUnit : entry + riskPerUnit;
        double target = direction.equals("long") ? entry + riskPerUnit * targetR : entry - riskPerUnit * targetR;
        Double partialTargetR = finite(candidate.get("partial_target_r"));
        double partialPct = number(candidate.get("partial_exit_pct")) > 0 && partialTargetR != null
                ? number(candidate.get("partial_exit_pct")) : 0;
        Double partialTarget = partialPct > 0 ? direction.equals("long")
                ? entry + riskPerUnit * partialTargetR : entry - riskPerUnit * partialTargetR : null;
        double units = notional / entry;
        double entryFee = notional * number(candidate.get("fee_pct")) / 100;
        double remaining = 1, gross = -entryFee, fees = entryFee, funding = 0, stopLevel = stop;
        boolean partial = false;
        ArrayNode fundingSettlements = JSON.arrayNode();
        Set<Long> fundingEvents = new HashSet<>();
        Integer exitIndex = null;
        Double exitRaw = null;
        String exitType = "TIME_STOP";
        double exitedFraction = 0, maxFavorable = 0, maxAdverse = 0;
        String collision = textOr(option(options, "same_bar_collision"),
                textOr(candidate.path("raw").get("same_bar_collision"), "stop-first"));
        int maxIndex = Math.min(rows.size() - 1, entryIndex + candidate.path("max_hold_bars").asInt() - 1);
        long expectedBarMs = timeframeMs(textOr(signal.get("timeframe"), "4h"));
        for (int index = entryIndex; index <= maxIndex; index++) {
            ObjectNode bar = (ObjectNode) rows.get(index);
            if (index > entryIndex && longNumber(bar.get("time")) != longNumber(rows.get(index - 1).get("time")) + expectedBarMs) {
                return status("DATA_GAP", signal).put("opened", true)
                        .put("gap_from", longNumber(rows.get(index - 1).get("time")) + expectedBarMs)
                        .put("gap_to", longNumber(bar.get("time")));
            }
            Double high = finite(bar.get("high")), low = finite(bar.get("low")), barClose = finite(bar.get("close"));
            if (high == null || low == null || barClose == null) continue;
            double favorable = direction.equals("long") ? high / entry - 1 : 1 - low / entry;
            double adverse = direction.equals("long") ? 1 - low / entry : high / entry - 1;
            maxFavorable = Math.max(maxFavorable, favorable); maxAdverse = Math.max(maxAdverse, adverse);
            Double fundingTimeDouble = finite(first(bar.get("funding_event_time"), bar.path("funding").get("event_time")));
            if (fundingTimeDouble != null) {
                long fundingTime = fundingTimeDouble.longValue();
                if (fundingTime >= longNumber(entryBar.get("time")) && fundingTime <= longNumber(bar.get("time")) && fundingEvents.add(fundingTime)) {
                    double settlementNotional = notional * remaining;
                    double settlement = fundingForBar(bar, direction, settlementNotional, bool(candidate.get("funding_debit"), true));
                    JsonNode fundingNode = bar.path("funding");
                    String venue = firstText(bar.get("funding_venue"), fundingNode.get("venue"), bar.get("venue"));
                    String instrument = firstText(bar.get("funding_instrument"), fundingNode.get("instrument"), bar.get("instrument"));
                    String source = firstText(bar.get("funding_source"), fundingNode.get("source"));
                    if (source == null) source = "unknown";
                    boolean authoritative = truthy(bar.get("funding_event_id")) && !source.equals("unknown") && venue != null && instrument != null;
                    ObjectNode settlementNode = JSON.objectNode().put("time", fundingTime)
                            .put("event_id", truthy(bar.get("funding_event_id")) ? bar.get("funding_event_id").asText()
                                    : (venue == null ? "unknown" : venue) + '|' + (instrument == null ? "unknown" : instrument) + '|' + fundingTime);
                    putNumber(settlementNode, "rate", numberOr(first(bar.get("funding_rate"), fundingNode.get("rate")), 0));
                    putNumber(settlementNode, "notional", settlementNotional); putNumber(settlementNode, "amount", settlement);
                    settlementNode.put("source", source).set("venue", textOrNull(venue)); settlementNode.set("instrument", textOrNull(instrument));
                    settlementNode.put("identity_status", authoritative ? "AUTHORITATIVE" : "INFERRED");
                    fundingSettlements.add(settlementNode); funding += settlement;
                }
            }
            ExitEvent event = exitReasonForBar(bar, direction, stopLevel, target, partial || partialPct == 0 ? null : partialTarget, collision);
            if (event != null && event.type.equals("PARTIAL")) {
                double price = fillPrice(partialTarget, direction, number(candidate.get("slippage_pct")), false);
                double fraction = partialPct;
                gross += direction.equals("long") ? (price - entry) * units * fraction : (entry - price) * units * fraction;
                fees += Math.abs(price * units * fraction) * number(candidate.get("fee_pct")) / 100;
                remaining -= fraction; partial = true; exitedFraction += fraction;
                if (bool(candidate.get("ratchet_to_entry"), false)) stopLevel = entry;
                if (event.fullTargetSameBar) {
                    double full = fillPrice(target, direction, number(candidate.get("slippage_pct")), false);
                    double fractionFull = remaining;
                    gross += direction.equals("long") ? (full - entry) * units * fractionFull : (entry - full) * units * fractionFull;
                    fees += Math.abs(full * units * fractionFull) * number(candidate.get("fee_pct")) / 100;
                    remaining = 0; exitedFraction += fractionFull; exitIndex = index; exitRaw = target; exitType = "TARGET"; break;
                }
                continue;
            }
            if (event != null) {
                double raw = event.type.equals("STOP") ? stopLevel : target;
                double price = fillPrice(raw, direction, number(candidate.get("slippage_pct")), false);
                double fraction = remaining;
                gross += direction.equals("long") ? (price - entry) * units * fraction : (entry - price) * units * fraction;
                fees += Math.abs(price * units * fraction) * number(candidate.get("fee_pct")) / 100;
                remaining = 0; exitedFraction += fraction; exitIndex = index; exitRaw = raw; exitType = event.type; break;
            }
            if (index == maxIndex) {
                double price = fillPrice(barClose, direction, number(candidate.get("slippage_pct")), false);
                gross += direction.equals("long") ? (price - entry) * units * remaining : (entry - price) * units * remaining;
                fees += Math.abs(price * units * remaining) * number(candidate.get("fee_pct")) / 100;
                remaining = 0; exitedFraction += 1 - exitedFraction; exitIndex = index; exitRaw = barClose; exitType = "TIME_STOP";
            }
        }
        if (exitIndex == null) return status("NO_EXIT", signal);
        double netPnl = gross + funding - (fees - entryFee);
        double riskDollars = notional * stopPct / 100;
        Double netR = riskDollars > 0 ? netPnl / riskDollars : null;
        int holdBars = exitIndex - entryIndex + 1;
        ObjectNode result = JSON.objectNode().put("status", "COMPLETED")
                .put("trade_id", textOr(signal.get("signal_id"), "null") + ':' + longNumber(entryBar.get("time")));
        result.set("signal_id", copyOrNull(signal.get("signal_id"))); result.set("setup_family_id", copyOrNull(signal.get("setup_family_id")));
        result.set("setup_family", copyOrNull(signal.get("setup_family"))); result.put("regime", textOr(signal.get("regime"), "UNKNOWN"));
        result.set("asset", copyOrNull(signal.get("asset"))); result.set("timeframe", copyOrNull(signal.get("timeframe")));
        result.put("framework", candidate.path("framework").asText()); result.set("channel", copyOrNull(candidate.get("channel")));
        result.put("direction", direction).put("phase", candidate.path("phase").asText());
        result.put("signal_time", longNumber(signal.get("time"))).put("entry_time", longNumber(entryBar.get("time")))
                .put("exit_time", longNumber(rows.get(exitIndex).get("time"))).put("entry_index", entryIndex).put("exit_index", exitIndex);
        putNumber(result, "entry_price", entry); putNumber(result, "exit_price", fillPrice(exitRaw, direction, number(candidate.get("slippage_pct")), false));
        putNumber(result, "stop_price", stop); putNumber(result, "target_price", target);
        result.put("exit_type", exitType).put("partial_exit", partial); putNumber(result, "partial_exit_pct", partial ? partialPct : 0);
        result.put("hold_bars", holdBars); putNumber(result, "notional", notional); putNumber(result, "risk_dollars", riskDollars);
        ObjectNode riskBudget = JSON.objectNode();
        putNumber(riskBudget, "phase_cap_pct", capPct); putNumber(riskBudget, "portfolio_risk_pct", PORTFOLIO_RISK_PCT);
        putNumber(riskBudget, "asset_risk_pct", ASSET_RISK_PCT); putNumber(riskBudget, "phase_notional", phaseNotional);
        putNumber(riskBudget, "portfolio_risk_notional", portfolioRiskNotional); putNumber(riskBudget, "asset_risk_notional", assetRiskNotional);
        result.set("risk_budget", riskBudget); putNumber(result, "gross_pnl", gross); putNumber(result, "net_pnl", netPnl);
        putNullableNumber(result, "net_r", netR); putNumber(result, "fees", fees);
        double finalExit = fillPrice(exitRaw, direction, number(candidate.get("slippage_pct")), false);
        putNumber(result, "slippage_debit", notional * number(candidate.get("slippage_pct")) / 100
                + Math.abs(finalExit * units) * number(candidate.get("slippage_pct")) / 100);
        putNumber(result, "funding_pnl", funding); result.set("funding_settlements", fundingSettlements);
        putNumber(result, "mae_pct", -maxAdverse * 100); putNumber(result, "mfe_pct", maxFavorable * 100);
        result.put("early_capture", exitType.equals("TARGET") && holdBars <= Math.floor(candidate.path("max_hold_bars").asInt() * .25));
        result.set("stop_out_then_target", NullNode.instance); result.put("stop_out_then_target_status", "UNAVAILABLE_COUNTERFACTUAL")
                .put("collision_policy", collision);
        return result;
    }

    public static ObjectNode tradeMetrics(ArrayNode trades) {
        return tradeMetrics(trades, JSON.objectNode());
    }

    public static ObjectNode tradeMetrics(ArrayNode trades, JsonNode options) {
        List<ObjectNode> completed = new ArrayList<>();
        for (JsonNode trade : trades) if ("COMPLETED".equals(nullableText(trade.get("status"))) && finite(trade.get("net_r")) != null)
            completed.add((ObjectNode) trade);
        completed.sort(Comparator.comparingDouble((ObjectNode t) -> number(t.get("exit_time")))
                .thenComparingDouble(t -> number(t.get("entry_time"))));
        List<ObjectNode> wins = completed.stream().filter(t -> number(t.get("net_r")) > EPS).toList();
        List<ObjectNode> losses = completed.stream().filter(t -> number(t.get("net_r")) < -EPS).toList();
        int breakeven = completed.size() - wins.size() - losses.size();
        double grossWin = sum(wins, "net_pnl"), grossLoss = Math.abs(sum(losses, "net_pnl"));
        double grossWinR = 0, grossLossRSigned = 0;
        for (ObjectNode trade : completed) { grossWinR += Math.max(0, numberOr(trade.get("net_r"), 0)); grossLossRSigned += Math.min(0, numberOr(trade.get("net_r"), 0)); }
        double grossLossR = Math.abs(grossLossRSigned);
        double[] rs = completed.stream().mapToDouble(t -> number(t.get("net_r"))).toArray();
        Double mean = rs.length == 0 ? null : mean(rs);
        Double variance = rs.length > 1 ? sampleVariance(rs, mean) : null;
        double[] downside = java.util.Arrays.stream(rs).filter(value -> value < 0).toArray();
        double downsideSquares = 0; for (double value : downside) downsideSquares += value * value;
        Double downsideDev = downside.length > 1 ? Math.sqrt(downsideSquares / (downside.length - 1)) : null;
        Double periodMsOption = finite(option(options, "periodMs", "period_ms"));
        Double duration = periodMsOption != null ? periodMsOption : completed.isEmpty() ? null
                : number(completed.get(completed.size() - 1).get("exit_time")) - number(completed.get(0).get("entry_time"));
        double initialEquity = numberOr(option(options, "initialEquity", "initial_equity"), DEFAULT_INITIAL_EQUITY);
        double totalPnl = sum(completed, "net_pnl");
        // The frozen Node oracle expects the fdlibm-compatible result. StrictMath
        // keeps this calculation stable across JVM architectures; Math.pow may
        // be intrinsified and produce a different final ULP on Linux x64.
        Double annualized = duration != null && duration > 0
                ? StrictMath.pow(Math.max(EPS, 1 + totalPnl / initialEquity), 365 * 86_400_000d / duration) - 1 : null;
        double exposureBars = sum(completed, "hold_bars");
        Double totalBars = duration == null ? null : duration / BAR_MS;
        int rawSetupBars = integerOption(options, 0, "rawSetupBars", "raw_setup_bars");
        int uniqueSignals = integerOption(options, 0, "uniqueSignals", "unique_signals");
        int attemptedSignals = integerOption(options, uniqueSignals, "attemptedSignals", "attempted_signals");
        JsonNode openedOption = option(options, "openedTrades", "opened_trades");
        int openedTrades = openedOption == null || openedOption.isNull() ? completed.size() : trunc(number(openedOption));
        int candidateCount = integerOption(options, 1, "candidateCount", "candidate_count");
        int bootstrapRounds = integerOption(options, 1000, "bootstrapRounds", "bootstrap_rounds");
        Bootstrap bootstrap = bootstrap(rs, bootstrapRounds, completed.size() + rawSetupBars);
        ObjectNode out = JSON.objectNode().put("raw_setup_bars", rawSetupBars).put("unique_signals", uniqueSignals)
                .put("attempted_signals", attemptedSignals).put("opened_trades", openedTrades).put("completed_trades", completed.size())
                .put("wins", wins.size()).put("losses", losses.size()).put("breakeven", breakeven);
        putNullableNumber(out, "win_rate", completed.isEmpty() ? null : (double) wins.size() / completed.size());
        out.set("win_rate_wilson_95", wilson(wins.size(), completed.size())); putNullableNumber(out, "expectancy_r", mean);
        putNullableNumber(out, "expectancy_bootstrap_20", bootstrap.p20());
        ObjectNode interval = JSON.objectNode(); putNullableNumber(interval, "low", bootstrap.low()); putNullableNumber(interval, "high", bootstrap.high());
        out.set("expectancy_bootstrap_95", interval);
        putNullableNumber(out, "profit_factor", grossLoss != 0 ? grossWin / grossLoss : null);
        out.put("profit_factor_unbounded", grossWin > 0 && grossLoss == 0);
        putNullableNumber(out, "profit_factor_r", grossLossR != 0 ? grossWinR / grossLossR : null);
        out.put("profit_factor_r_unbounded", grossWinR > 0 && grossLossR == 0);
        putNumber(out, "total_return", totalPnl / initialEquity); putNullableNumber(out, "annualized_return", annualized);
        putNullableNumber(out, "evaluation_period_ms", duration); putNumber(out, "max_drawdown", maxDrawdown(completed, initialEquity));
        putNullableNumber(out, "sharpe_r", variance != null && variance > 0 ? mean / Math.sqrt(variance) : null);
        putNullableNumber(out, "sortino_r", downsideDev != null && downsideDev > 0 ? mean / downsideDev : null);
        putNullableNumber(out, "mae_pct", completed.isEmpty() ? null : sum(completed, "mae_pct") / completed.size());
        putNullableNumber(out, "mfe_pct", completed.isEmpty() ? null : sum(completed, "mfe_pct") / completed.size());
        if (completed.isEmpty()) out.set("median_hold_bars", NullNode.instance); else {
            double[] holds = completed.stream().mapToDouble(t -> number(t.get("hold_bars"))).sorted().toArray();
            putNumber(out, "median_hold_bars", holds[holds.length / 2]);
        }
        putNullableNumber(out, "exposure", totalBars != null && totalBars != 0 ? exposureBars / totalBars : null);
        putNumber(out, "turnover", sum(completed, "notional") * 2); putNumber(out, "fees", sum(completed, "fees"));
        putNumber(out, "slippage", sum(completed, "slippage_debit"));
        double fundingDebit = 0, fundingCredit = 0;
        for (ObjectNode trade : completed) { fundingDebit += Math.max(0, -number(trade.get("funding_pnl"))); fundingCredit += Math.max(0, number(trade.get("funding_pnl"))); }
        putNumber(out, "funding_debit", fundingDebit); putNumber(out, "funding_credit", fundingCredit);
        putNullableNumber(out, "early_capture_rate", completed.isEmpty() ? null
                : (double) completed.stream().filter(t -> bool(t.get("early_capture"), false)).count() / completed.size());
        out.set("stop_out_then_target_rate", NullNode.instance); out.put("stop_out_then_target_status", "UNAVAILABLE_COUNTERFACTUAL")
                .put("candidate_count", candidateCount);
        Double penalty = completed.isEmpty() ? null : Math.sqrt(2 * Math.log(Math.max(1, candidateCount)) / completed.size());
        putNullableNumber(out, "conservative_search_penalty_r", penalty);
        putNullableNumber(out, "search_adjusted_expectancy_r", mean == null ? null : mean - (penalty == null ? 0 : penalty));
        return out;
    }

    /** Runs one candidate, excluding overlapping signal episodes by actual exit time. */
    public static ObjectNode evaluateCandidate(ArrayNode rows, JsonNode candidateInput) {
        return evaluateCandidate(rows, candidateInput, JSON.objectNode());
    }

    public static ObjectNode evaluateCandidate(ArrayNode rows, JsonNode candidateInput, JsonNode options) {
        ObjectNode candidate = normalizeCandidate(candidateInput);
        List<Signal> signals = candidateSignalRows(rows, candidate);
        ArrayNode trades = JSON.arrayNode(), attempts = JSON.arrayNode();
        double nextAvailable = Double.NEGATIVE_INFINITY;
        double equity = number(candidate.get("initial_equity"));
        for (Signal signal : signals) {
            ObjectNode signalRow = ((ObjectNode) rows.get(signal.index())).deepCopy();
            signalRow.put("signal_id", signal.id()).put("setup_family_id", signal.setupId()).put("setup_family", signal.family());
            if (number(signalRow.get("time")) < nextAvailable) continue;
            ObjectNode simulationOptions = options != null && options.isObject() ? ((ObjectNode) options).deepCopy() : JSON.objectNode();
            putNumber(simulationOptions, "equity", equity); simulationOptions.set("signal", signalRow);
            ObjectNode lifecycle = simulateTrade(rows, signal.index(), candidate, simulationOptions);
            ObjectNode attempt = lifecycle.deepCopy().put("signal_id", signal.id()).put("attempted", true);
            attempts.add(attempt);
            if (!"COMPLETED".equals(lifecycle.path("status").asText())) continue;
            ObjectNode result = lifecycle.deepCopy().put("candidate_id", candidate.path("id").asText());
            trades.add(result); equity += number(result.get("net_pnl")); nextAvailable = number(result.get("exit_time"));
        }
        int openedTrades = 0;
        for (JsonNode attempt : attempts) if (Set.of("COMPLETED", "NO_EXIT").contains(attempt.path("status").asText()) || bool(attempt.get("opened"), false)) openedTrades++;
        ObjectNode metricsOptions = JSON.objectNode().put("rawSetupBars", signals.size()).put("uniqueSignals", signals.size())
                .put("attemptedSignals", signals.size()).put("openedTrades", openedTrades)
                .put("candidateCount", integerOption(options, 1, "candidate_count", "candidateCount"));
        putNumber(metricsOptions, "initialEquity", number(candidate.get("initial_equity")));
        Double optionPeriod = finite(option(options, "periodMs", "period_ms"));
        putNullableNumber(metricsOptions, "periodMs", optionPeriod != null ? optionPeriod : evaluationWindowMs(rows));
        metricsOptions.put("bootstrapRounds", integerOption(options, 1000, "bootstrap_rounds", "bootstrapRounds"));
        ObjectNode metrics = tradeMetrics(trades, metricsOptions);
        ArrayNode blocked = JSON.arrayNode();
        for (JsonNode attempt : attempts) if (!"COMPLETED".equals(attempt.path("status").asText())) {
            ObjectNode summary = JSON.objectNode().set("signal_id", copyOrNull(attempt.get("signal_id")));
            summary.set("status", copyOrNull(attempt.get("status"))); summary.set("reason", copyOrNull(attempt.get("reason")));
            // JSON.stringify omits undefined `reason`; Jackson's source inputs
            // use absence, so remove the synthetic null to retain that shape.
            if (!attempt.has("reason")) summary.remove("reason");
            blocked.add(summary);
        }
        ObjectNode out = JSON.objectNode().set("candidate", candidate);
        out.put("raw_setup_bars", signals.size()).put("unique_signals", signals.size()).put("attempted_signals", signals.size())
                .put("opened_trades", openedTrades).put("completed_trades", trades.size()).set("blocked_attempts", blocked);
        out.set("trades", trades); out.set("metrics", metrics); out.set("regime_breakdown", breakdown(trades, "regime"));
        out.set("setup_breakdown", breakdown(trades, "setup_family"));
        return out;
    }

    /** Outcome-free signal and lifecycle intent path used by strategy research. */
    public static ArrayNode candidateSignalIntent(ArrayNode rows, JsonNode candidateInput) {
        ObjectNode candidate = normalizeCandidate(candidateInput);
        ArrayNode intents = JSON.arrayNode();
        for (Signal signal : candidateSignalRows(rows, candidate)) {
            ObjectNode row = (ObjectNode) rows.get(signal.index());
            ObjectNode lifecycle = JSON.objectNode().put("phase", candidate.path("phase").asText());
            lifecycle.set("stop_pct", copyOrNull(candidate.get("stop_pct"))); lifecycle.set("stop_atr_multiple", copyOrNull(candidate.get("stop_atr_multiple")));
            lifecycle.set("target_r", copyOrNull(candidate.get("target_r"))); lifecycle.set("partial_exit_pct", copyOrNull(candidate.get("partial_exit_pct")));
            lifecycle.set("partial_target_r", copyOrNull(candidate.get("partial_target_r"))); lifecycle.set("ratchet_to_entry", copyOrNull(candidate.get("ratchet_to_entry")));
            lifecycle.set("max_hold_bars", copyOrNull(candidate.get("max_hold_bars")));
            lifecycle.put("same_bar_collision", textOr(candidate.path("raw").get("same_bar_collision"), "stop-first"));
            ObjectNode intent = JSON.objectNode().set("asset", copyOrNull(row.get("asset")));
            intent.set("decision_time", copyOrNull(row.get("time"))); intent.put("direction", candidate.path("direction").asText())
                    .put("setup_identity", signal.setupId()).put("setup_family", signal.family()); intent.set("lifecycle_intent", lifecycle);
            intent.set("instrument", first(candidate.path("raw").get("instrument"), candidate.path("raw").get("instrument_contract"),
                    candidate.path("raw").get("tradable_instrument")) == null ? NullNode.instance
                    : first(candidate.path("raw").get("instrument"), candidate.path("raw").get("instrument_contract"), candidate.path("raw").get("tradable_instrument")).deepCopy());
            intents.add(intent);
        }
        return intents;
    }

    /** Evaluates ordered strategy components with a single active episode per asset. */
    public static ObjectNode evaluateStrategy(ArrayNode rows, ArrayNode componentInputs) {
        return evaluateStrategy(rows, componentInputs, JSON.objectNode());
    }

    public static ObjectNode evaluateStrategy(ArrayNode rows, ArrayNode componentInputs, JsonNode options) {
        ArrayNode components = JSON.arrayNode();
        Set<String> ids = new HashSet<>();
        for (JsonNode input : componentInputs) {
            ObjectNode component = normalizeCandidate(input);
            if (!ids.add(component.path("id").asText())) throw new IllegalArgumentException("strategy component ids must be unique");
            components.add(component);
        }
        if (components.isEmpty()) throw new IllegalArgumentException("strategy requires at least one component");
        LinkedHashSet<String> assets = new LinkedHashSet<>();
        for (JsonNode row : rows) if (truthy(row.get("asset"))) assets.add(row.get("asset").asText());
        if (assets.size() > 1) throw new IllegalArgumentException("evaluateStrategy accepts one asset at a time");
        List<StrategyEvent> events = new ArrayList<>();
        for (int priority = 0; priority < components.size(); priority++) {
            ObjectNode candidate = (ObjectNode) components.get(priority);
            ArrayNode series = JSON.arrayNode();
            for (JsonNode row : rows) if (Objects.equals(nullableText(row.get("framework")), nullableText(candidate.get("framework")))
                    && (!"flying_rocket".equals(nullableText(candidate.get("framework")))
                        || Objects.equals(nullableText(row.get("channel")), nullableText(candidate.get("channel"))))) series.add(row.deepCopy());
            sortArray(series, Comparator.comparingDouble(row -> number(row.get("time"))));
            for (Signal signal : candidateSignalRows(series, candidate))
                events.add(new StrategyEvent(number(series.get(signal.index()).get("time")), priority, candidate, series, signal));
        }
        events.sort(Comparator.comparingDouble(StrategyEvent::time).thenComparingInt(StrategyEvent::priority)
                .thenComparing(event -> event.candidate().path("id").asText()));
        ArrayNode trades = JSON.arrayNode(), attempts = JSON.arrayNode();
        double nextAvailable = Double.NEGATIVE_INFINITY, equity = number(components.get(0).get("initial_equity"));
        for (StrategyEvent event : events) {
            ObjectNode signalRow = ((ObjectNode) event.rows().get(event.signal().index())).deepCopy()
                    .put("signal_id", event.signal().id()).put("setup_family_id", event.signal().setupId()).put("setup_family", event.signal().family());
            if (number(signalRow.get("time")) < nextAvailable) {
                attempts.add(JSON.objectNode().put("status", "OVERLAP_BLOCKED").put("signal_id", event.signal().id())
                        .put("component_id", event.candidate().path("id").asText()));
                continue;
            }
            ObjectNode simOptions = options != null && options.isObject() ? ((ObjectNode) options).deepCopy() : JSON.objectNode();
            putNumber(simOptions, "equity", equity); simOptions.set("signal", signalRow);
            ObjectNode lifecycle = simulateTrade(event.rows(), event.signal().index(), event.candidate(), simOptions);
            ObjectNode attempt = lifecycle.deepCopy().put("signal_id", event.signal().id())
                    .put("component_id", event.candidate().path("id").asText());
            attempts.add(attempt);
            if (!"COMPLETED".equals(lifecycle.path("status").asText())) continue;
            ObjectNode result = lifecycle.deepCopy().put("candidate_id", event.candidate().path("id").asText())
                    .put("component_id", event.candidate().path("id").asText());
            trades.add(result); equity += number(result.get("net_pnl")); nextAvailable = number(result.get("exit_time"));
        }
        int opened = 0;
        for (JsonNode attempt : attempts) if (Set.of("COMPLETED", "NO_EXIT").contains(attempt.path("status").asText()) || bool(attempt.get("opened"), false)) opened++;
        ObjectNode metricOptions = JSON.objectNode().put("rawSetupBars", events.size()).put("uniqueSignals", events.size())
                .put("attemptedSignals", events.size()).put("openedTrades", opened)
                .put("candidateCount", integerOption(options, components.size(), "candidate_count", "candidateCount"));
        putNumber(metricOptions, "initialEquity", number(components.get(0).get("initial_equity")));
        Double period = finite(option(options, "periodMs", "period_ms")); putNullableNumber(metricOptions, "periodMs", period != null ? period : evaluationWindowMs(rows));
        metricOptions.put("bootstrapRounds", integerOption(options, 1000, "bootstrap_rounds", "bootstrapRounds"));
        ArrayNode blocked = JSON.arrayNode();
        for (JsonNode attempt : attempts) if (!"COMPLETED".equals(attempt.path("status").asText())) blocked.add(attempt.deepCopy());
        ObjectNode out = JSON.objectNode().put("schema", "swing-strategy-evaluation/1").set("asset", assets.isEmpty() ? NullNode.instance : TextNode.valueOf(assets.iterator().next()));
        out.set("components", components); out.put("priority_rule", "component array order for same completed-bar timestamp; one active episode per asset")
                .put("raw_setup_bars", events.size()).put("unique_signals", events.size()).put("attempted_signals", events.size())
                .put("opened_trades", opened).put("completed_trades", trades.size()); out.set("blocked_attempts", blocked);
        out.set("trades", trades); out.set("metrics", tradeMetrics(trades, metricOptions)); out.set("component_breakdown", breakdown(trades, "component_id"));
        out.set("direction_breakdown", breakdown(trades, "direction")); out.set("regime_breakdown", breakdown(trades, "regime"));
        return out;
    }

    public static ArrayNode rankCandidates(ArrayNode reports) {
        return rankCandidates(reports, JSON.objectNode());
    }

    public static ArrayNode rankCandidates(ArrayNode reports, JsonNode options) {
        int minTrades = integerOption(options, 10, "minTrades", "min_trades");
        double minExpectancy = numberOr(option(options, "minExpectancyR", "min_expectancy_r"), 0);
        double maxDrawdown = numberOr(option(options, "maxDrawdown", "max_drawdown"), .35);
        int minRegimes = integerOption(options, 2, "minRegimes", "min_regimes");
        ObjectNode criteria = JSON.objectNode().put("min_trades", minTrades);
        putNumber(criteria, "min_expectancy_r", minExpectancy); putNumber(criteria, "min_profit_factor", 1);
        putNumber(criteria, "max_drawdown", maxDrawdown); criteria.put("min_regimes", minRegimes);
        List<ObjectNode> ranked = new ArrayList<>();
        for (JsonNode reportNode : reports) {
            ObjectNode report = ((ObjectNode) reportNode).deepCopy();
            ObjectNode metrics = (ObjectNode) report.path("metrics");
            int regimes = report.path("regime_breakdown").size();
            Double expectancy = finite(metrics.get("expectancy_r"));
            Double adjusted = finite(metrics.get("search_adjusted_expectancy_r"));
            Double profitFactor = finite(metrics.get("profit_factor"));
            boolean admissible = metrics.path("completed_trades").asInt() >= minTrades && expectancy != null && expectancy > minExpectancy
                    && adjusted != null && adjusted > minExpectancy
                    && (bool(metrics.get("profit_factor_unbounded"), false) || profitFactor != null && profitFactor > 1)
                    && number(metrics.get("max_drawdown")) <= maxDrawdown && regimes >= minRegimes;
            ObjectNode selection = JSON.objectNode().put("admissible", admissible).set("criteria", criteria.deepCopy());
            selection.set("downside_score_r", copyOrNull(metrics.get("expectancy_bootstrap_20")));
            selection.put("ranking_rule", "hard positive search-adjusted expectancy gate; then deterministic 20th-percentile bootstrap mean R");
            report.set("selection", selection); ranked.add(report);
        }
        ranked.sort((left, right) -> {
            int compared = Boolean.compare(bool(right.path("selection").get("admissible"), false), bool(left.path("selection").get("admissible"), false));
            if (compared != 0) return compared;
            compared = Double.compare(numberOr(right.path("selection").get("downside_score_r"), Double.NEGATIVE_INFINITY),
                    numberOr(left.path("selection").get("downside_score_r"), Double.NEGATIVE_INFINITY));
            if (compared != 0) return compared;
            compared = Integer.compare(right.path("metrics").path("completed_trades").asInt(), left.path("metrics").path("completed_trades").asInt());
            return compared != 0 ? compared : left.path("candidate").path("id").asText().compareTo(right.path("candidate").path("id").asText());
        });
        ArrayNode out = JSON.arrayNode(); ranked.forEach(out::add); return out;
    }

    /** Anchored expanding walk-forward with the source's 30-day purge/embargo. */
    public static ObjectNode walkForward(ArrayNode rows, ArrayNode candidateInputs) {
        return walkForward(rows, candidateInputs, JSON.objectNode());
    }

    public static ObjectNode walkForward(ArrayNode rows, ArrayNode candidateInputs, JsonNode options) {
        ArrayNode normalized = JSON.arrayNode();
        for (JsonNode candidate : candidateInputs) normalized.add(normalizeCandidate(candidate));
        ArrayNode candidates = uniqueCandidateModels(normalized);
        List<Integer> months = splitMonths(rows);
        int minMonths = integerOption(options, 12, "minMonths", "min_months");
        int holdoutMonths = Math.max(1, integerOption(options, 6, "holdoutMonths", "holdout_months"));
        int foldMonths = Math.max(1, integerOption(options, 3, "foldMonths", "fold_months"));
        int developmentMonths = Math.max(1, integerOption(options, Math.max(6, months.size() - holdoutMonths - 12), "developmentMonths", "development_months"));
        if (months.size() < minMonths || months.size() <= holdoutMonths + foldMonths) {
            ObjectNode insufficient = JSON.objectNode().put("status", "INSUFFICIENT_REGIMES_OR_MONTHS");
            insufficient.set("months", integers(months)); insufficient.set("folds", JSON.arrayNode()); insufficient.set("holdout", NullNode.instance); insufficient.set("selected", NullNode.instance);
            return insufficient;
        }
        List<Integer> holdout = new ArrayList<>(months.subList(months.size() - holdoutMonths, months.size()));
        List<Integer> preHoldout = new ArrayList<>(months.subList(0, months.size() - holdoutMonths));
        long holdoutStart = monthStart(holdout.get(0));
        ArrayNode trainingForHoldout = purgeRows(filterRows(rows, row -> !holdout.contains(rowMonth(row))), holdoutStart, MAX_HOLD_BARS);
        ArrayNode trainReports = JSON.arrayNode();
        for (JsonNode candidate : candidates) trainReports.add(evaluateCandidate(trainingForHoldout, candidate,
                with(options, "candidate_count", JSON.numberNode(candidates.size()))));
        ArrayNode ranked = rankCandidates(trainReports, options);
        ObjectNode selectedReport = firstAdmissible(ranked);
        ArrayNode folds = JSON.arrayNode();
        for (int start = developmentMonths; start < preHoldout.size(); start += foldMonths) {
            List<Integer> testMonths = new ArrayList<>(preHoldout.subList(start, Math.min(start + foldMonths, preHoldout.size())));
            if (testMonths.isEmpty()) continue;
            long boundary = monthStart(testMonths.get(0));
            ArrayNode trainRows = purgeRows(filterRows(rows, row -> rowMonth(row) < testMonths.get(0)), boundary, MAX_HOLD_BARS);
            ArrayNode testRows = rowsForMonths(rows, testMonths);
            ArrayNode reports = JSON.arrayNode();
            for (JsonNode candidate : candidates) reports.add(evaluateCandidate(trainRows, candidate,
                    with(options, "candidate_count", JSON.numberNode(candidates.size()))));
            ArrayNode train = rankCandidates(reports, options);
            ObjectNode foldSelected = firstAdmissible(train);
            ObjectNode fold = JSON.objectNode().set("train_months", integers(splitMonths(trainRows)));
            fold.set("test_months", integers(testMonths)); fold.put("purge_bars", MAX_HOLD_BARS);
            fold.set("selected", foldSelected == null ? NullNode.instance : foldSelected.get("candidate").deepCopy());
            fold.put("selection_blocked", foldSelected == null);
            ArrayNode trainLeaderboard = JSON.arrayNode();
            int max = Math.min(train.size(), integerOption(options, 100, "max_leaderboard"));
            for (int index = 0; index < max; index++) trainLeaderboard.add(reportSummary(train.get(index)));
            fold.set("train_leaderboard", trainLeaderboard);
            fold.set("oos", foldSelected == null ? NullNode.instance : evaluateCandidate(testRows, foldSelected.get("candidate"),
                    with(options, "candidate_count", JSON.numberNode(candidates.size()))));
            folds.add(fold);
        }
        List<String> counterKeys = List.of("raw_setup_bars", "unique_signals", "attempted_signals", "opened_trades", "completed_trades");
        ObjectNode foldCounters = JSON.objectNode();
        for (String key : counterKeys) {
            int total = 0;
            for (JsonNode fold : folds) if (fold.path("oos").isObject()) total += trunc(numberOr(fold.path("oos").path("metrics").get(key), 0));
            foldCounters.put(key, total);
        }
        ArrayNode rawOosTrades = JSON.arrayNode();
        LinkedHashSet<Integer> oosMonthSet = new LinkedHashSet<>();
        for (JsonNode fold : folds) {
            if (fold.path("oos").isObject()) for (JsonNode trade : array(fold.path("oos").get("trades"))) rawOosTrades.add(trade.deepCopy());
            for (JsonNode month : array(fold.get("test_months"))) oosMonthSet.add(month.asInt());
        }
        ArrayNode oosTrades = chronologicalTrades(nonOverlappingTrades(rawOosTrades));
        List<Integer> oosMonths = oosMonthSet.stream().sorted().toList();
        Double oosPeriod = evaluationWindowMs(rowsForMonths(rows, oosMonths));
        ObjectNode deoverlap = JSON.objectNode().put("rule", "actual entry/exit timestamps")
                .put("completed_before", foldCounters.path("completed_trades").asInt()).put("completed_after", oosTrades.size())
                .put("dropped_completed", Math.max(0, foldCounters.path("completed_trades").asInt() - oosTrades.size()));
        ObjectNode oosMetricOptions = JSON.objectNode();
        for (String key : counterKeys) if (!key.equals("completed_trades")) oosMetricOptions.set(key, foldCounters.get(key));
        oosMetricOptions.put("candidateCount", candidates.size()); putNullableNumber(oosMetricOptions, "periodMs", oosPeriod);
        ObjectNode oosMetrics = tradeMetrics(oosTrades, oosMetricOptions);
        int positiveFolds = 0;
        for (JsonNode fold : folds) if (numberOr(fold.path("oos").path("metrics").get("expectancy_r"), Double.NEGATIVE_INFINITY) > 0) positiveFolds++;
        int minOosTrades = Math.max(1, integerOption(options, 20, "holdoutMinOosTrades", "holdout_min_oos_trades"));
        int minPositiveFolds = Math.max(1, integerOption(options, 3, "holdoutMinPositiveFolds", "holdout_min_positive_folds"));
        boolean positiveExpectancy = numberOr(oosMetrics.get("expectancy_r"), Double.NEGATIVE_INFINITY) > 0;
        boolean positiveProfitFactor = bool(oosMetrics.get("profit_factor_unbounded"), false)
                || numberOr(oosMetrics.get("profit_factor"), Double.NEGATIVE_INFINITY) > 1;
        boolean selectedAvailable = selectedReport != null;
        boolean eligible = oosMetrics.path("completed_trades").asInt() >= minOosTrades && positiveExpectancy && positiveProfitFactor
                && positiveFolds >= minPositiveFolds && selectedAvailable;
        ObjectNode gate = JSON.objectNode().put("min_oos_trades", minOosTrades).put("min_positive_folds", minPositiveFolds)
                .put("completed_oos_trades", oosMetrics.path("completed_trades").asInt()).put("positive_oos_folds", positiveFolds)
                .put("positive_expectancy", positiveExpectancy).put("positive_profit_factor", positiveProfitFactor)
                .put("final_training_candidate_available", selectedAvailable).put("eligible", eligible);
        ArrayNode reasons = JSON.arrayNode();
        if (oosMetrics.path("completed_trades").asInt() < minOosTrades) reasons.add("INSUFFICIENT_OOS_TRADES");
        if (!positiveExpectancy) reasons.add("NONPOSITIVE_OOS_EXPECTANCY");
        if (!positiveProfitFactor) reasons.add("NONPOSITIVE_OOS_PROFIT_FACTOR");
        if (positiveFolds < minPositiveFolds) reasons.add("INSUFFICIENT_POSITIVE_OOS_FOLDS");
        if (!selectedAvailable) reasons.add("NO_FINAL_TRAINING_CANDIDATE");
        gate.set("reasons", reasons);
        ArrayNode holdoutRows = rowsForMonths(rows, holdout);
        ObjectNode holdoutHashPayload = JSON.objectNode().set("months", integers(holdout)); holdoutHashPayload.set("rows", holdoutRows);
        String holdoutDataHash;
        try { holdoutDataHash = sha256(MAPPER.writeValueAsString(holdoutHashPayload)); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException(exception); }
        String sealToken = nullableText(option(options, "sealedHoldoutToken", "sealed_holdout_token"));
        String sealHash = nullableText(option(options, "sealedHoldoutHash", "sealed_holdout_hash"));
        boolean sealVerified = sealToken != null && !sealToken.isEmpty() && Objects.equals(sealHash, holdoutDataHash);
        ObjectNode holdoutReport = eligible ? evaluateCandidate(holdoutRows, selectedReport.get("candidate"),
                with(options, "candidate_count", JSON.numberNode(candidates.size()))) : null;
        ObjectNode out = JSON.objectNode().put("status", "OK").set("months", integers(months));
        out.put("purge_bars", MAX_HOLD_BARS).put("development_months", developmentMonths).put("fold_months", foldMonths)
                .put("holdout_months", holdoutMonths); out.set("folds", folds);
        out.set("selected", selectedReport == null ? NullNode.instance : selectedReport.get("candidate").deepCopy());
        ArrayNode trainingLeaderboard = JSON.arrayNode();
        int trainMax = Math.min(ranked.size(), integerOption(options, 100, "max_leaderboard"));
        for (int index = 0; index < trainMax; index++) trainingLeaderboard.add(reportSummary(ranked.get(index)));
        out.set("training_leaderboard", trainingLeaderboard);
        ObjectNode oos = JSON.objectNode().put("measurement_status", "MEASURED_WITHOUT_SIGNIFICANCE_PRECONDITION");
        oos.set("trades", oosTrades); oos.set("fold_counters", foldCounters); oos.set("deoverlap", deoverlap); oos.set("metrics", oosMetrics);
        out.set("walk_forward_oos", oos);
        ObjectNode seal = JSON.objectNode().put("verified", sealVerified).put("token_supplied", sealToken != null)
                .put("hash_supplied", sealHash != null).put("data_sha256", holdoutDataHash);
        seal.set("error", sealHash != null && !sealHash.equals(holdoutDataHash) ? TextNode.valueOf("HASH_MISMATCH") : NullNode.instance);
        ObjectNode holdoutNode = JSON.objectNode().put("label", sealVerified ? "SEALED_CONFIRMATION" : "EXPOSED_CONFIRMATION")
                .put("untouched", sealVerified); holdoutNode.set("seal", seal);
        holdoutNode.set("train_end_month", preHoldout.isEmpty() ? NullNode.instance : JSON.numberNode(preHoldout.get(preHoldout.size() - 1)));
        holdoutNode.set("months", integers(holdout)); holdoutNode.set("selected", eligible ? selectedReport.get("candidate").deepCopy() : NullNode.instance);
        holdoutNode.put("selection_blocked", !eligible); holdoutNode.set("gate", gate); holdoutNode.set("report", holdoutReport == null ? NullNode.instance : holdoutReport);
        out.set("holdout", holdoutNode);
        return out;
    }

    public static String renderSummary(JsonNode result) {
        ArrayNode validation = JSON.arrayNode();
        for (JsonNode series : array(result.get("series"))) {
            ObjectNode item = JSON.objectNode().set("series", copyOrNull(series.get("series")));
            JsonNode oos = series.path("validation").path("walk_forward_oos").get("metrics");
            item.set("walk_forward_oos", oos == null || oos.isMissingNode() ? NullNode.instance : oos.deepCopy());
            JsonNode holdout = series.path("validation").get("holdout");
            if (holdout != null && !holdout.isNull()) {
                ObjectNode h = JSON.objectNode().set("label", copyOrNull(holdout.get("label")));
                h.set("selected", copyOrNull(holdout.get("selected")));
                h.set("metrics", holdout.path("report").isObject() ? copyOrNull(holdout.path("report").get("metrics")) : NullNode.instance);
                h.set("selection_blocked", copyOrNull(holdout.get("selection_blocked"))); h.set("gate", copyOrNull(holdout.get("gate")));
                item.set("holdout", h);
            } else item.set("holdout", NullNode.instance);
            validation.add(item);
        }
        List<String> lines = new ArrayList<>(List.of("# Swing research backtest", "",
                "- Engine: " + result.path("engine").asText(),
                "- Feature store: " + textOr(result.get("feature_store_sha256"), "n/a"),
                "- Activation: " + result.path("activation").asText() + " (research only; live gates unchanged)", "",
                "## Leaderboard", "", "| Candidate | Trades | Win rate | Expectancy R | Downside score R | Max DD | Measurement |",
                "|---|---:|---:|---:|---:|---:|---|"));
        int leaderboardCount = 0;
        for (JsonNode report : array(result.get("leaderboard"))) {
            if (leaderboardCount++ >= 20) break;
            JsonNode metrics = report.path("metrics"), selection = report.path("selection");
            lines.add("| " + report.path("candidate").path("id").asText() + " | " + metrics.path("completed_trades").asText()
                    + " | " + percent1(metrics.get("win_rate")) + " | " + fixed3(metrics.get("expectancy_r")) + " | "
                    + fixed3(selection.get("downside_score_r")) + " | " + String.format(Locale.ROOT, "%.1f%%", number(metrics.get("max_drawdown")) * 100)
                    + " | " + (bool(selection.get("admissible"), false) ? "OOS ELIGIBLE" : "BLOCK") + " |");
        }
        lines.addAll(List.of("", "## In-sample / development", "",
                "Feasible training candidates require minimum sample and regime breadth, positive after-cost expectancy, profit factor above 1, and the drawdown bound. The deterministic 20th-percentile bootstrap mean ranks them; statistical significance is not required before OOS measurement.", "",
                prettyWithoutTerminalNewline(slice(array(result.get("aggregate")), 20)), "", "## Walk-forward OOS", ""));
        lines.addAll(metricsTable(validation, false));
        lines.addAll(List.of("", "## Holdout confirmation", "",
                "This output makes no Untouched holdout claim. Unsealed local results are labelled EXPOSED_CONFIRMATION. SEALED_CONFIRMATION requires a caller-supplied token and a matching precommitted holdout-data hash.", ""));
        lines.addAll(metricsTable(validation, true));
        ArrayNode holdouts = JSON.arrayNode();
        for (JsonNode item : validation) { ObjectNode holdout = JSON.objectNode().set("series", copyOrNull(item.get("series"))); holdout.set("holdout", copyOrNull(item.get("holdout"))); holdouts.add(holdout); }
        lines.addAll(List.of("", prettyWithoutTerminalNewline(holdouts), "",
                "Raw setup bars, unique signals, opened trades and completed trades are reported separately. This artifact is SHADOW and cannot activate live FK/FR gates."));
        return String.join("\n", lines) + '\n';
    }

    public static boolean verifyRunHash(JsonNode result) {
        return result != null && truthy(result.get("run_sha256"))
                && Objects.equals(result.path("run_sha256").asText(), sha256(runHashPayload(result)));
    }

    public static ObjectNode runResearch(ArrayNode storeRows, ArrayNode candidatesInput) {
        return runResearch(storeRows, candidatesInput, JSON.objectNode(), Clock.systemUTC());
    }

    public static ObjectNode runResearch(ArrayNode storeRows, ArrayNode candidatesInput, JsonNode options) {
        return runResearch(storeRows, candidatesInput, options, Clock.systemUTC());
    }

    /** Runs the full per-series development, walk-forward, aggregate, and artifact pipeline. */
    public static ObjectNode runResearch(ArrayNode storeRows, ArrayNode candidatesInput, JsonNode options, Clock clock) {
        ArrayNode sourceCandidates = candidatesInput == null ? defaultCandidates() : candidatesInput;
        ArrayNode declared = JSON.arrayNode();
        for (JsonNode candidate : sourceCandidates) declared.add(normalizeCandidate(candidate));
        ArrayNode candidates = uniqueCandidateModels(declared);
        ArrayNode rows = JSON.arrayNode();
        for (JsonNode row : storeRows) if (!isFalse(row.get("timestamp_safe"))) rows.add(row.deepCopy());
        sortArray(rows, Comparator.comparingDouble(row -> number(row.get("time"))));
        LinkedHashMap<String, ArrayNode> bySeries = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String key = textOr(row.get("asset"), "UNKNOWN") + '|' + textOr(row.get("timeframe"), "4h") + '|'
                    + textOr(row.get("framework"), "UNSCOPED") + '|' + textOr(row.get("channel"), "A");
            bySeries.computeIfAbsent(key, ignored -> JSON.arrayNode()).add(row.deepCopy());
        }
        ArrayNode leaderboardWithTrades = JSON.arrayNode(), seriesOutput = JSON.arrayNode();
        LinkedHashMap<String, AggregateState> aggregateByCandidate = new LinkedHashMap<>();
        for (Map.Entry<String, ArrayNode> entry : bySeries.entrySet()) {
            ArrayNode seriesRows = entry.getValue();
            Development development = developmentRows(seriesRows, integerOption(options, 6, "holdoutMonths", "holdout_months"));
            ArrayNode seriesCandidates = JSON.arrayNode();
            for (JsonNode candidate : candidates) if (candidateEligibleForSeries(candidate, seriesRows)) seriesCandidates.add(candidate.deepCopy());
            ArrayNode reports = JSON.arrayNode();
            for (JsonNode candidate : seriesCandidates) reports.add(evaluateCandidate(development.rows(), candidate,
                    with(options, "candidate_count", JSON.numberNode(seriesCandidates.size()))));
            ArrayNode ranked = rankCandidates(reports, options);
            for (JsonNode report : ranked) {
                ObjectNode copy = ((ObjectNode) report).deepCopy(); copy.put("series", entry.getKey()); leaderboardWithTrades.add(copy);
            }
            for (JsonNode report : reports) addAggregate(aggregateByCandidate, report, development.rows());
            JsonNode validation = bool(option(options, "skip_validation"), false)
                    ? JSON.objectNode().put("status", "SKIPPED_FOR_BENCHMARK") : walkForward(seriesRows, seriesCandidates, options);
            ObjectNode series = JSON.objectNode().put("series", entry.getKey()).put("rows", seriesRows.size());
            series.set("development_months", integers(development.months())); series.set("holdout_months", integers(development.holdout()));
            int rawSetupBars = 0; for (JsonNode report : reports) rawSetupBars += report.path("raw_setup_bars").asInt();
            series.put("raw_setup_bars", rawSetupBars);
            ArrayNode top = JSON.arrayNode(); for (int index = 0; index < Math.min(20, ranked.size()); index++) top.add(reportSummary(ranked.get(index)));
            series.set("leaderboard", top); series.set("validation", validation); seriesOutput.add(series);
        }
        List<JsonNode> leaderboardSorted = toList(leaderboardWithTrades);
        leaderboardSorted.sort((left, right) -> {
            int result = Boolean.compare(bool(right.path("selection").get("admissible"), false), bool(left.path("selection").get("admissible"), false));
            if (result != 0) return result;
            result = Double.compare(numberOr(right.path("selection").get("downside_score_r"), Double.NEGATIVE_INFINITY),
                    numberOr(left.path("selection").get("downside_score_r"), Double.NEGATIVE_INFINITY));
            return result != 0 ? result : left.path("candidate").path("id").asText().compareTo(right.path("candidate").path("id").asText());
        });
        leaderboardWithTrades.removeAll(); leaderboardSorted.forEach(leaderboardWithTrades::add);

        ArrayNode aggregateReports = JSON.arrayNode();
        for (AggregateState state : aggregateByCandidate.values()) {
            int capitalSeries = Math.max(1, state.eligibleSeriesCount);
            ArrayNode trades = chronologicalTrades(state.trades);
            Double periodMs = state.periodStart == null || state.periodEnd == null ? null
                    : state.periodEnd - state.periodStart + timeframeMs(state.periodTimeframe);
            ObjectNode metricOptions = JSON.objectNode().put("rawSetupBars", state.rawSetupBars).put("uniqueSignals", state.uniqueSignals)
                    .put("attemptedSignals", state.attemptedSignals).put("openedTrades", state.openedTrades)
                    .put("candidateCount", candidates.size()).put("bootstrapRounds", integerOption(options, 1000, "bootstrap_rounds", "bootstrapRounds"));
            putNumber(metricOptions, "initialEquity", number(state.candidate.get("initial_equity")) * capitalSeries);
            putNullableNumber(metricOptions, "periodMs", periodMs);
            ObjectNode report = JSON.objectNode().set("candidate", state.candidate.deepCopy());
            report.put("eligible_series_count", state.eligibleSeriesCount).put("raw_setup_bars", state.rawSetupBars)
                    .put("unique_signals", state.uniqueSignals); report.set("trades", trades); report.set("metrics", tradeMetrics(trades, metricOptions));
            report.set("regime_breakdown", breakdown(trades, "regime")); report.set("setup_breakdown", breakdown(trades, "setup_family"));
            aggregateReports.add(report);
        }
        ArrayNode aggregateLeaderboard = rankCandidates(aggregateReports, options);
        LinkedHashSet<String> retainedIds = new LinkedHashSet<>();
        for (JsonNode id : array(option(options, "retain_candidate_ids"))) retainedIds.add(id.asText());
        if (retainedIds.isEmpty()) for (JsonNode report : aggregateLeaderboard) {
            if (bool(report.path("selection").get("admissible"), false)) retainedIds.add(report.path("candidate").path("id").asText());
            if (retainedIds.size() >= 3) break;
        }
        LinkedHashMap<String, JsonNode> retainedTradeMap = new LinkedHashMap<>();
        for (ArrayNode reports : List.of(leaderboardWithTrades, aggregateLeaderboard)) for (JsonNode report : reports) {
            if (!retainedIds.contains(report.path("candidate").path("id").asText())) continue;
            for (JsonNode trade : array(report.get("trades"))) {
                String key = truthy(trade.get("trade_id")) ? trade.get("trade_id").asText()
                        : report.path("candidate").path("id").asText() + '|' + nullableText(trade.get("asset")) + '|'
                            + nullableText(trade.get("entry_time")) + '|' + nullableText(trade.get("exit_time"));
                retainedTradeMap.putIfAbsent(key, trade.deepCopy());
            }
        }
        ObjectNode result = JSON.objectNode().put("schema", RUN_SCHEMA).put("engine", ENGINE_VERSION)
                .put("generated_at", jsIso(clock.instant())).put("activation", "SHADOW");
        result.set("feature_store_sha256", copyOrNull(option(options, "feature_store_sha256")));
        result.put("candidates_declared", declared.size()).put("candidates_evaluated", candidates.size()).put("candidate_hash", sha256(declared));
        ObjectNode validation = JSON.objectNode().put("design", "anchored expanding walk-forward; 30-day purge/embargo; feasible training candidates are measured OOS without a significance precondition; holdout opens only after rolling-OOS evidence")
                .put("multiple_testing", "training feasibility uses completed trades, raw costed expectancy, profit factor, drawdown and regime breadth; ranking uses the deterministic 20th-percentile bootstrap mean R. sqrt(2 log K/n) remains descriptive and never gates OOS measurement");
        validation.set("series", seriesOutput.deepCopy()); result.set("validation", validation);
        ArrayNode leaderboard = JSON.arrayNode(); for (JsonNode report : leaderboardWithTrades) { ObjectNode copy = ((ObjectNode) report).deepCopy(); copy.remove("trades"); leaderboard.add(copy); }
        ArrayNode aggregate = JSON.arrayNode(); for (JsonNode report : aggregateLeaderboard) { ObjectNode copy = ((ObjectNode) report).deepCopy(); copy.remove("trades"); aggregate.add(copy); }
        result.set("leaderboard", leaderboard); result.set("aggregate", aggregate);
        ArrayNode retainedIdsNode = JSON.arrayNode(); retainedIds.stream().sorted().forEach(retainedIdsNode::add); result.set("retained_candidate_ids", retainedIdsNode);
        ArrayNode retainedTrades = JSON.arrayNode(); retainedTradeMap.values().forEach(value -> retainedTrades.add(value.deepCopy()));
        result.set("retained_trades", chronologicalTrades(retainedTrades)); result.set("series", seriesOutput);
        ObjectNode config = options != null && options.isObject() ? ((ObjectNode) options).deepCopy() : JSON.objectNode();
        config.put("max_hold_bars", MAX_HOLD_BARS).put("leaderboard_scope", "PURGED_DEVELOPMENT_ONLY"); result.set("config", config);
        result.put("artifact_hash_scope", "canonical run payload without generated_at/run_sha256").set("run_sha256", NullNode.instance);
        result.put("run_sha256", sha256(runHashPayload(result)));
        return result;
    }

    private record Group(String asset, String timeframe, String framework, String channel, List<ObjectNode> rows) {
        private Group(String asset, String timeframe, String framework, String channel) {
            this(asset, timeframe, framework, channel, new ArrayList<>());
        }
    }
    private record Signal(String id, String setupId, String family, int index) {}
    private record StrategyEvent(double time, int priority, ObjectNode candidate, ArrayNode rows, Signal signal) {}
    private record ExitEvent(String type, boolean fullTargetSameBar) {}
    private record Bootstrap(Double low, Double p20, Double high) {}
    private record Development(ArrayNode rows, List<Integer> months, List<Integer> holdout) {}
    private static final class AggregateState {
        final ObjectNode candidate;
        final ArrayNode trades = JSON.arrayNode();
        int rawSetupBars, uniqueSignals, attemptedSignals, openedTrades, eligibleSeriesCount;
        Double periodStart, periodEnd;
        String periodTimeframe;
        AggregateState(ObjectNode candidate) { this.candidate = candidate.deepCopy(); }
    }

    private static List<ObjectNode> extractDatasets(JsonNode input) {
        List<ObjectNode> out = new ArrayList<>();
        if (input != null && input.isArray()) {
            int index = 0;
            for (JsonNode rows : input) { ObjectNode dataset = JSON.objectNode().set("features", rows.deepCopy()); dataset.put("index", index++); out.add(dataset); }
        } else if (input != null && input.path("datasets").isArray()) {
            for (JsonNode dataset : input.path("datasets")) if (dataset.isObject()) out.add((ObjectNode) dataset);
        } else if (input != null && (input.path("features").isArray() || input.path("rows").isArray())) {
            out.add((ObjectNode) input);
        } else throw new IllegalArgumentException("input must contain datasets[].features, features, or rows");
        return out;
    }

    private static String futureLabelPath(JsonNode value, String path) {
        if (value == null || !value.isContainerNode()) return null;
        if (value.isArray()) {
            for (int index = 0; index < value.size(); index++) {
                String nested = futureLabelPath(value.get(index), path.isEmpty() ? String.valueOf(index) : path + '.' + index);
                if (nested != null) return nested;
            }
            return null;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String childPath = path.isEmpty() ? field.getKey() : path + '.' + field.getKey();
            if (FUTURE_LABEL_KEYS.contains(field.getKey()) || path.isEmpty() && Set.of("long", "short").contains(field.getKey())) return childPath;
            String nested = futureLabelPath(field.getValue(), childPath);
            if (nested != null) return nested;
        }
        return null;
    }

    private static ObjectNode normalizeRow(ObjectNode raw, ObjectNode dataset) {
        Double timeNumber = finite(first(raw.get("time"), raw.get("timestamp"), raw.get("open_time")));
        if (timeNumber == null) throw new IllegalArgumentException("feature row has no finite time");
        long time = timeNumber.longValue();
        String timeframe = lower(firstText(raw.get("timeframe"), dataset.get("timeframe")));
        if (timeframe == null || timeframe.isEmpty()) timeframe = "4h";
        String asset = lower(firstText(raw.get("asset"), dataset.get("asset")));
        if (asset != null && asset.isEmpty()) asset = null;
        String framework = firstText(raw.get("framework"), dataset.get("framework"));
        JsonNode channelNode = present(raw, "channel") ? raw.get("channel") : dataset.get("channel");
        String channel = nullableText(channelNode);
        JsonNode legsNode = truthy(raw.get("legs")) ? raw.get("legs") : raw.path("score").path("legs");
        ObjectNode legs = legsNode != null && legsNode.isObject() ? (ObjectNode) legsNode : JSON.objectNode();
        JsonNode componentsNode = truthy(raw.get("leg_components")) ? raw.get("leg_components") : raw.path("score").path("leg_components");
        ObjectNode components = componentsNode != null && componentsNode.isObject() ? (ObjectNode) componentsNode : JSON.objectNode();
        Double score = finite(first(raw.path("score").get("mechanical"), raw.get("mechanical_score"), raw.get("score_value")));
        if (score == null) {
            score = 0d;
            for (String leg : LEG_NAMES) score += numberOr(legs.get(leg), 0);
        }
        JsonNode flowNode = firstTruthy(raw.get("flow_assessment"), raw.get("flowAssessment"), raw.get("_flow_snapshot"));
        JsonNode flow = flowNode != null && flowNode.isObject() ? flowNode : JSON.objectNode();
        ObjectNode triggerSource = raw.path("trigger").isObject() ? (ObjectNode) raw.path("trigger") : JSON.objectNode();
        Double aligned = finite(first(raw.get("flow_aligned_rows"), raw.get("aligned_rows"), flow.get("aligned_rows")));
        ObjectNode state = raw.path("state_legs").isObject() ? ((ObjectNode) raw.path("state_legs")).deepCopy()
                : raw.path("state").isObject() ? ((ObjectNode) raw.path("state")).deepCopy() : componentMap(components, "state");
        ObjectNode impulse = raw.path("impulse_legs").isObject() ? ((ObjectNode) raw.path("impulse_legs")).deepCopy()
                : raw.path("impulse").isObject() ? ((ObjectNode) raw.path("impulse")).deepCopy() : componentMap(components, "impulse");
        double declared = numberOr(first(raw.get("available_at"), raw.get("availability_time")), Double.NEGATIVE_INFINITY);
        long availableAt = (long) Math.max(time + timeframeMs(timeframe), declared);
        ObjectNode out = raw.deepCopy();
        out.put("time", time).put("available_at", availableAt); out.set("asset", textOrNull(asset)); out.put("timeframe", timeframe);
        out.set("framework", textOrNull(framework)); out.set("channel", textOrNull(channel)); out.put("month", monthOf(availableAt));
        for (String field : List.of("open", "high", "low", "close", "volume")) putNullableNumber(out, field, finite(raw.get(field)));
        putNullableNumber(out, "funding_rate", finite(first(raw.get("funding_rate"), raw.path("funding").get("rate"))));
        putNullableNumber(out, "funding_event_time", finite(first(raw.get("funding_event_time"), raw.path("funding").get("time"))));
        putNullableNumber(out, "equity_usd", finite(raw.get("equity_usd")));
        putNullableNumber(out, "stop_distance_pct", finite(raw.get("stop_distance_pct")));
        out.set("legs", legs.deepCopy()); out.set("leg_components", components.deepCopy()); out.set("state_legs", state); out.set("impulse_legs", impulse);
        putNumber(out, "mechanical_score", score); putNullableNumber(out, "flow_aligned_rows", aligned);
        ArrayNode families = setupFamilies(raw, framework, channel);
        out.put("setup_family", families.get(0).asText()).set("setup_families", families);
        ObjectNode trigger = triggerSource.deepCopy();
        trigger.put("valid", bool(triggerSource.get("valid"), false) || "VALID".equals(nullableText(triggerSource.get("status"))) || bool(raw.get("trigger_valid"), false));
        trigger.put("timeframe", textOr(triggerSource.get("timeframe"), timeframe));
        trigger.put("completed_bar", !isFalse(triggerSource.get("completed_bar")) && !isFalse(raw.get("completed_bar")));
        putNullableNumber(trigger, "age_bars", finite(first(triggerSource.get("age_bars"), raw.get("trigger_age_bars"))));
        putNullableNumber(trigger, "window_bars", finite(first(triggerSource.get("window_bars"), raw.get("trigger_window_bars"))));
        out.set("trigger", trigger);
        return out;
    }

    private static ObjectNode componentMap(ObjectNode components, String component) {
        ObjectNode out = JSON.objectNode();
        for (String leg : LEG_NAMES) putNullableNumber(out, leg, finite(components.path(leg).get(component)));
        return out;
    }

    private static ArrayNode setupFamilies(ObjectNode row, String framework, String channel) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        addUpper(names, row.get("setup_family")); addUpper(names, row.get("setup")); addUpper(names, row.get("mechanical_setup"));
        addUpper(names, row.path("trigger").get("kind")); addUpper(names, row.path("trigger").get("setup_family"));
        for (JsonNode name : array(row.get("setup_families"))) addUpper(names, name);
        if ("fallen_knives".equals(framework)) {
            if (flag(row, "higher_low", "higher_low_reclaim", "fk_higher_low")) names.add("FK_HIGHER_LOW");
            if (flag(row, "deleveraging_reversal", "oi_deleveraging_reversal", "fk_deleveraging_reversal")) names.add("FK_DELEVERAGING_REVERSAL");
            if (flag(row, "reclaim", "support_reclaim", "fk_reclaim")) names.add("FK_SUPPORT_RECLAIM");
            if (flag(row, "reversal", "ema_reversal", "fk_reversal")) names.add("FK_REVERSAL_RECLAIM");
        } else if ("A".equals(channel)) {
            if (flag(row, "distribution", "fr_a_distribution")) names.add("FR_A_DISTRIBUTION");
            if (flag(row, "failed_breakout", "failed_breakout_retest", "fr_a_failed_breakout")) names.add("FR_A_FAILED_BREAKOUT");
            if (flag(row, "rejection", "euphoria_rejection", "fr_a_rejection")) names.add("FR_A_EUPHORIA_REJECTION");
        } else {
            if (flag(row, "lower_high", "fr_b_lower_high")) names.add("FR_B_LOWER_HIGH");
            if (flag(row, "breakdown_retest", "fr_b_breakdown_retest")) names.add("FR_B_BREAKDOWN_RETEST");
            if (flag(row, "bear_rally_failure", "fr_b_bear_rally_failure")) names.add("FR_B_BEAR_RALLY_FAILURE");
        }
        if (names.isEmpty()) names.add("UNSPECIFIED");
        ArrayNode out = JSON.arrayNode(); names.forEach(out::add); return out;
    }

    private static boolean flag(ObjectNode row, String... keys) {
        for (String key : keys) if (bool(row.get(key), false) || bool(row.path("patterns").get(key), false) || bool(row.path("setup_flags").get(key), false)) return true;
        return false;
    }

    private static String featureHash(JsonNode store) {
        ObjectNode payload = ((ObjectNode) store).deepCopy(); payload.set("created_at", NullNode.instance); payload.set("features_sha256", NullNode.instance);
        return sha256(payload);
    }

    private static Double defaultThreshold(String framework, String channel, String phase) {
        Integer value = SwingScore.phaseThresholds(framework, channel == null ? "A" : channel).get(phase);
        return value == null ? null : value.doubleValue();
    }

    private static double stopCeiling(String framework, String channel, String phase) {
        if (framework.equals("fallen_knives")) return 15;
        Map<String, Double> map = "B".equals(channel) ? Map.of("1A", 6d, "1B", 6d, "2", 8d)
                : Map.of("1A", 8d, "1B", 10d, "2", 12d, "3", 15d);
        Double value = map.get(phase); return value == null ? Double.NaN : value;
    }

    private static ObjectNode minimumMap(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return JSON.objectNode();
        if (value.isNumber()) return JSON.objectNode().set("technical", value.deepCopy());
        return value.isObject() ? ((ObjectNode) value).deepCopy() : JSON.objectNode();
    }

    private static ArrayNode normalizeFactorFilters(JsonNode value) {
        ArrayNode out = JSON.arrayNode(), values = arrayish(value);
        for (int index = 0; index < values.size(); index++) {
            JsonNode filter = values.get(index);
            if (!filter.isObject()) throw new IllegalArgumentException("factor_filters[" + index + "] must be an object");
            String path = textOr(filter.get("path"), "");
            if (!FACTOR_PATH.matcher(path).matches()) throw new IllegalArgumentException("factor_filters[" + index + "].path must start with factors.");
            String op = lower(textOr(filter.get("op"), "eq"));
            if (!FACTOR_OPS.contains(op)) throw new IllegalArgumentException("factor_filters[" + index + "].op is unsupported");
            JsonNode expected = filter.get("value");
            if (op.equals("between") && (expected == null || !expected.isArray() || expected.size() != 2
                    || finite(expected.get(0)) == null || finite(expected.get(1)) == null))
                throw new IllegalArgumentException("factor_filters[" + index + "].value must be [low, high]");
            if (op.equals("in") && (expected == null || !expected.isArray()))
                throw new IllegalArgumentException("factor_filters[" + index + "].value must be an array");
            if (!Set.of("eq", "neq", "in", "between").contains(op) && finite(expected) == null)
                throw new IllegalArgumentException("factor_filters[" + index + "].value must be finite");
            out.add(JSON.objectNode().put("path", path).put("op", op).set("value", copyOrNull(expected)));
        }
        return out;
    }

    private static boolean factorFiltersPass(JsonNode row, JsonNode filters) {
        for (JsonNode filter : array(filters)) {
            JsonNode actual = valueAtPath(row, filter.path("path").asText());
            if (actual == null || actual.isNull() || actual.isMissingNode()) return false;
            JsonNode expected = filter.get("value"); String op = filter.path("op").asText();
            if (op.equals("in")) { boolean found = false; for (JsonNode item : expected) if (item.equals(actual)) { found = true; break; } if (!found) return false; continue; }
            if (op.equals("eq")) { if (!actual.equals(expected) && !numericEqual(actual, expected)) return false; continue; }
            if (op.equals("neq")) { if (actual.equals(expected) || numericEqual(actual, expected)) return false; continue; }
            Double numeric = finite(actual); if (numeric == null) return false;
            if (op.equals("gt") && !(numeric > number(expected)) || op.equals("gte") && !(numeric >= number(expected))
                    || op.equals("lt") && !(numeric < number(expected)) || op.equals("lte") && !(numeric <= number(expected))
                    || op.equals("between") && !(numeric >= number(expected.get(0)) && numeric <= number(expected.get(1)))) return false;
        }
        return true;
    }

    private static JsonNode valueAtPath(JsonNode object, String path) {
        JsonNode value = object;
        for (String key : path.split("\\.")) { if (value == null) return null; value = value.get(key); }
        return value;
    }

    private static Double candidateScore(ObjectNode row, ObjectNode candidate) {
        ArrayNode excluded = array(candidate.get("excluded_score_legs"));
        if (excluded.isEmpty()) {
            Double mechanical = finite(row.get("mechanical_score")); if (mechanical != null) return mechanical;
            double sum = 0; for (String leg : LEG_NAMES) { Double value = finite(row.path("legs").get(leg)); if (value == null) return null; sum += value; }
            return sum;
        }
        Set<String> excludedNames = new HashSet<>(); excluded.forEach(node -> excludedNames.add(node.asText()));
        double score = 0, max = 0;
        for (String leg : LEG_NAMES) if (!excludedNames.contains(leg)) {
            Double value = finite(row.path("legs").get(leg)); if (value == null) return null; score += value; max += LEG_MAXES.get(leg);
        }
        return "included_max_to_20".equals(candidate.path("score_normalization").asText()) && max > 0 ? score * 20 / max : score;
    }

    private static boolean legPass(ObjectNode row, String component, JsonNode minimums) {
        if (minimums == null || !minimums.isObject()) return true;
        Iterator<Map.Entry<String, JsonNode>> fields = minimums.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> minimum = fields.next();
            JsonNode direct = row.path(component.equals("impulse") ? "impulse_legs" : "state_legs").get(minimum.getKey());
            JsonNode nested = row.path("leg_components").path(minimum.getKey()).get(component);
            Double value = finite(first(direct, nested)); if (value == null || value < number(minimum.getValue())) return false;
        }
        return true;
    }

    private static boolean triggerPass(ObjectNode row, ObjectNode candidate) {
        if (isFalse(row.get("timestamp_safe")) || isFalse(row.get("no_lookahead")) && isFalse(row.path("source_coverage").get("point_in_time_safe"))) return false;
        JsonNode trigger = row.path("trigger"); boolean familyTrigger = false;
        for (JsonNode family : array(candidate.get("setup_families")))
            if (bool(row.path("setup_flags").get(family.asText()), false) || bool(row.path("patterns").get(family.asText()), false)) familyTrigger = true;
        if (!bool(trigger.get("valid"), false) && !familyTrigger || isFalse(trigger.get("completed_bar")) || isFalse(row.get("completed_bar"))) return false;
        if (!"4h".equals(lower(textOr(trigger.get("timeframe"), textOr(row.get("timeframe"), ""))))) return false;
        Double age = finite(trigger.get("age_bars")); return age == null || age <= number(candidate.get("trigger_window_bars"));
    }

    private static boolean rowSetupMatches(ObjectNode row, ObjectNode candidate) {
        ArrayNode wanted = array(candidate.get("setup_families")); if (wanted.isEmpty()) return true;
        Set<String> available = new HashSet<>();
        addUpper(available, row.get("setup_family")); addUpper(available, row.path("trigger").get("kind")); addUpper(available, row.path("trigger").get("setup_family"));
        for (JsonNode family : array(row.get("setup_families"))) addUpper(available, family);
        for (JsonNode family : wanted) if (available.contains(family.asText())) return true; return false;
    }

    private static String matchedSetupFamily(ObjectNode row, ObjectNode candidate) {
        ArrayNode own = array(row.get("setup_families"));
        if (own.isEmpty()) own.add(textOr(row.get("setup_family"), "UNSPECIFIED"));
        ArrayNode wanted = array(candidate.get("setup_families"));
        if (wanted.isEmpty()) return own.get(0).asText();
        for (JsonNode family : wanted) for (JsonNode existing : own) if (family.asText().equals(existing.asText())) return family.asText();
        return own.get(0).asText();
    }

    private static List<Signal> candidateSignalRows(ArrayNode rows, ObjectNode candidate) {
        List<Signal> signals = new ArrayList<>(); Set<String> identities = new HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            ObjectNode row = (ObjectNode) rows.get(index); if (!candidateMatchesNormalized(row, candidate)) continue;
            String family = matchedSetupFamily(row, candidate);
            String id = candidate.path("framework").asText() + ':' + textOr(candidate.get("channel"), "A") + ':'
                    + nullableText(row.get("asset")) + ':' + nullableText(row.get("timeframe")) + ':' + family + ':' + row.path("time").asText();
            if (!identities.add(id)) continue;
            signals.add(new Signal(id, nullableText(row.get("asset")) + ':' + nullableText(row.get("timeframe")) + ':' + family + ':' + row.path("time").asText(), family, index));
        }
        return signals;
    }

    private static ObjectNode breakdown(ArrayNode trades, String field) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode trade : trades) values.add(textOr(trade.get(field), "UNKNOWN"));
        ObjectNode out = JSON.objectNode();
        for (String value : values) {
            ArrayNode subset = JSON.arrayNode(); for (JsonNode trade : trades) if (textOr(trade.get(field), "UNKNOWN").equals(value)) subset.add(trade.deepCopy());
            out.set(value, tradeMetrics(subset, JSON.objectNode().put("rawSetupBars", subset.size()).put("uniqueSignals", subset.size()).put("bootstrapRounds", 0)));
        }
        return out;
    }

    private static double fillPrice(double price, String direction, double slippagePct, boolean entry) {
        double slip = Math.max(0, slippagePct) / 100;
        if (entry) return direction.equals("long") ? price * (1 + slip) : price * (1 - slip);
        return direction.equals("long") ? price * (1 - slip) : price * (1 + slip);
    }

    private static ExitEvent exitReasonForBar(ObjectNode bar, String direction, double stop, double target, Double partialTarget, String collision) {
        boolean stopHit = direction.equals("long") ? number(bar.get("low")) <= stop : number(bar.get("high")) >= stop;
        boolean targetHit = direction.equals("long") ? number(bar.get("high")) >= target : number(bar.get("low")) <= target;
        boolean partialHit = partialTarget != null && (direction.equals("long") ? number(bar.get("high")) >= partialTarget : number(bar.get("low")) <= partialTarget);
        if (stopHit && targetHit) return new ExitEvent(collision.equals("target-first") ? "TARGET" : "STOP", false);
        if (stopHit) return new ExitEvent("STOP", false);
        if (targetHit && partialHit) return new ExitEvent("PARTIAL", true);
        if (targetHit) return new ExitEvent("TARGET", false);
        if (partialHit) return new ExitEvent("PARTIAL", targetHit);
        return null;
    }

    private static double fundingForBar(ObjectNode row, String direction, double notional, boolean enabled) {
        if (!enabled) return 0; Double rate = finite(first(row.get("funding_rate"), row.path("funding").get("rate")));
        if (rate == null) return 0; return direction.equals("long") ? -notional * rate : notional * rate;
    }

    private static ObjectNode status(String status, ObjectNode signal) {
        return JSON.objectNode().put("status", status).set("signal_id", copyOrNull(signal.get("signal_id")));
    }

    private static ObjectNode wilson(int wins, int total) {
        ObjectNode out = JSON.objectNode(); if (total == 0) { out.set("low", NullNode.instance); out.set("high", NullNode.instance); return out; }
        double z = 1.96, p = (double) wins / total, denominator = 1 + z * z / total;
        double centre = (p + z * z / (2 * total)) / denominator;
        double radius = z * Math.sqrt((p * (1 - p) + z * z / (4 * total)) / total) / denominator;
        putNumber(out, "low", Math.max(0, centre - radius)); putNumber(out, "high", Math.min(1, centre + radius)); return out;
    }

    private static Bootstrap bootstrap(double[] values, int rounds, int seed) {
        if (values.length == 0 || rounds <= 0) return new Bootstrap(null, null, null);
        int x = seed == 0 ? 1 : seed; double[] samples = new double[rounds];
        for (int round = 0; round < rounds; round++) {
            double sum = 0; for (int index = 0; index < values.length; index++) {
                x ^= x << 13; x ^= x >>> 17; x ^= x << 5;
                double random = Integer.toUnsignedLong(x) / 4294967296d;
                sum += values[(int) Math.floor(random * values.length)];
            } samples[round] = sum / values.length;
        }
        java.util.Arrays.sort(samples);
        return new Bootstrap(samples[(int) Math.floor(rounds * .025)], samples[(int) Math.floor(rounds * .2)], samples[(int) Math.floor(rounds * .975)]);
    }

    private static double maxDrawdown(List<ObjectNode> trades, double initialEquity) {
        double equity = initialEquity, peak = equity, max = 0;
        for (ObjectNode trade : trades) { equity += number(trade.get("net_pnl")); peak = Math.max(peak, equity); max = Math.max(max, peak == 0 ? 0 : (peak - equity) / peak); }
        return max;
    }

    private static ArrayNode uniqueCandidateModels(ArrayNode candidates) {
        LinkedHashMap<String, JsonNode> unique = new LinkedHashMap<>();
        for (JsonNode candidate : candidates) unique.putIfAbsent(candidateModelKey(candidate), candidate);
        ArrayNode out = JSON.arrayNode(); unique.values().forEach(value -> out.add(value.deepCopy())); return out;
    }

    static ArrayNode defaultCandidates() {
        List<String> families = List.of("FK_REVERSAL_RECLAIM", "FK_SUPPORT_RECLAIM", "FK_HIGHER_LOW", "FK_DELEVERAGING_REVERSAL",
                "FR_A_EUPHORIA_REJECTION", "FR_A_DISTRIBUTION", "FR_A_FAILED_BREAKOUT", "FR_B_BEAR_RALLY_FAILURE", "FR_B_LOWER_HIGH", "FR_B_BREAKDOWN_RETEST");
        ArrayNode out = JSON.arrayNode();
        for (String phase : List.of("1A", "1B", "2", "3")) for (String family : families.subList(0, 4))
            out.add(JSON.objectNode().put("framework", "fallen_knives").put("direction", "long").put("phase", phase)
                    .put("setup_family", family).put("trigger_window_bars", 2));
        for (String channel : List.of("A", "B")) {
            List<String> phases = new ArrayList<>(SwingScore.phaseThresholds("flying_rocket", channel).keySet());
            List<String> selectedFamilies = channel.equals("A") ? families.subList(4, 7) : families.subList(7, families.size());
            for (String phase : phases) for (String family : selectedFamilies)
                out.add(JSON.objectNode().put("framework", "flying_rocket").put("channel", channel).put("direction", "short")
                        .put("phase", phase).put("setup_family", family).put("trigger_window_bars", 2));
        }
        return out;
    }

    private static Development developmentRows(ArrayNode rows, int holdoutMonths) {
        List<Integer> months = splitMonths(rows); int count = Math.max(1, holdoutMonths == 0 ? 6 : holdoutMonths);
        if (months.size() <= count) return new Development(rows.deepCopy(), months, List.of());
        List<Integer> holdout = new ArrayList<>(months.subList(months.size() - count, months.size()));
        long boundary = monthStart(holdout.get(0));
        return new Development(purgeRows(filterRows(rows, row -> !holdout.contains(rowMonth(row))), boundary, MAX_HOLD_BARS),
                new ArrayList<>(months.subList(0, months.size() - count)), holdout);
    }

    private static boolean candidateEligibleForSeries(JsonNode candidate, ArrayNode rows) {
        if (rows.isEmpty()) return false; JsonNode first = rows.get(0);
        if (!Objects.equals(nullableText(first.get("framework")), nullableText(candidate.get("framework")))) return false;
        if ("flying_rocket".equals(nullableText(candidate.get("framework")))
                && !Objects.equals(nullableText(first.get("channel")), nullableText(candidate.get("channel")))) return false;
        return matchesListed(candidate.get("assets"), nullableText(first.get("asset")))
                && matchesListed(candidate.get("timeframes"), nullableText(first.get("timeframe")));
    }

    private static void addAggregate(Map<String, AggregateState> aggregates, JsonNode report, ArrayNode seriesRows) {
        String id = report.path("candidate").path("id").asText();
        AggregateState state = aggregates.computeIfAbsent(id, ignored -> new AggregateState((ObjectNode) report.path("candidate")));
        for (JsonNode trade : array(report.get("trades"))) state.trades.add(trade.deepCopy());
        state.rawSetupBars += report.path("raw_setup_bars").asInt(); state.uniqueSignals += report.path("unique_signals").asInt();
        state.attemptedSignals += report.path("attempted_signals").asInt(); state.openedTrades += report.path("opened_trades").asInt();
        if (candidateEligibleForSeries(report.get("candidate"), seriesRows) && !seriesRows.isEmpty()) {
            state.eligibleSeriesCount++;
            double first = number(seriesRows.get(0).get("time")), last = number(seriesRows.get(seriesRows.size() - 1).get("time"));
            state.periodStart = state.periodStart == null ? first : Math.min(state.periodStart, first);
            state.periodEnd = state.periodEnd == null ? last : Math.max(state.periodEnd, last);
            if (state.periodTimeframe == null) state.periodTimeframe = textOr(seriesRows.get(0).get("timeframe"), "4h");
        }
    }

    private static String candidateModelKey(JsonNode candidate) {
        ObjectNode predicate = JSON.objectNode();
        for (String key : List.of("framework", "channel", "direction", "threshold", "excluded_score_legs", "score_normalization", "min_state", "min_impulse",
                "factor_filters", "min_flow_aligned", "setup_families", "trigger_window_bars", "regime", "assets", "timeframes", "active_from", "active_to", "require_protective_controls"))
            predicate.set(key, copyOrNull(candidate.get(key)));
        ObjectNode trade = JSON.objectNode();
        for (String key : List.of("framework", "direction", "channel", "phase", "stop_pct", "stop_atr_multiple", "stop_min_pct", "stop_max_pct", "stop_ceiling_pct", "cap_pct",
                "target_r", "partial_exit_pct", "partial_target_r", "ratchet_to_entry", "max_hold_bars", "fee_pct", "slippage_pct", "funding_debit", "initial_equity"))
            trade.set(key, copyOrNull(candidate.get(key)));
        trade.put("same_bar_collision", textOr(candidate.path("raw").get("same_bar_collision"), "stop-first"));
        try { return MAPPER.writeValueAsString(predicate) + '|' + MAPPER.writeValueAsString(trade); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException(exception); }
    }

    private static List<Integer> splitMonths(ArrayNode rows) {
        return rowsToMonths(rows).stream().distinct().sorted().toList();
    }

    private static List<Integer> rowsToMonths(ArrayNode rows) {
        List<Integer> out = new ArrayList<>(); for (JsonNode row : rows) { Integer month = rowMonth(row); if (month != null) out.add(month); } return out;
    }

    private static ArrayNode rowsForMonths(ArrayNode rows, List<Integer> months) {
        Set<Integer> wanted = new HashSet<>(months); return filterRows(rows, row -> wanted.contains(rowMonth(row)));
    }

    private static ArrayNode nonOverlappingTrades(ArrayNode trades) {
        List<JsonNode> sorted = toList(trades); sorted.sort(Comparator.comparingDouble((JsonNode t) -> number(t.get("entry_time"))).thenComparingDouble(t -> number(t.get("exit_time"))));
        ArrayNode out = JSON.arrayNode(); double next = Double.NEGATIVE_INFINITY;
        for (JsonNode trade : sorted) { if (number(trade.get("entry_time")) < next) continue; out.add(trade.deepCopy()); next = number(trade.get("exit_time")); } return out;
    }

    private static ArrayNode chronologicalTrades(ArrayNode trades) {
        List<JsonNode> sorted = toList(trades); sorted.sort(Comparator.comparingDouble((JsonNode t) -> number(t.get("exit_time")))
                .thenComparingDouble(t -> number(t.get("entry_time"))).thenComparing(t -> textOr(t.get("trade_id"), "")));
        ArrayNode out = JSON.arrayNode(); sorted.forEach(t -> out.add(t.deepCopy())); return out;
    }

    private static ArrayNode purgeRows(ArrayNode rows, long boundaryTime, int embargoBars) {
        double cutoff = boundaryTime - embargoBars * (double) BAR_MS; return filterRows(rows, row -> availableTime(row) < cutoff);
    }

    private static ArrayNode filterRows(ArrayNode rows, Predicate<JsonNode> predicate) {
        ArrayNode out = JSON.arrayNode(); for (JsonNode row : rows) if (predicate.test(row)) out.add(row.deepCopy()); return out;
    }

    private static ObjectNode firstAdmissible(ArrayNode ranked) {
        for (JsonNode report : ranked) if (bool(report.path("selection").get("admissible"), false)) return (ObjectNode) report; return null;
    }

    private static ObjectNode reportSummary(JsonNode report) {
        ObjectNode out = JSON.objectNode().set("candidate", copyOrNull(report.get("candidate")));
        out.set("metrics", copyOrNull(report.get("metrics"))); out.set("selection", copyOrNull(report.get("selection"))); return out;
    }

    private static ObjectNode runHashPayload(JsonNode result) {
        ObjectNode copy = ((ObjectNode) result).deepCopy(); copy.remove("run_sha256"); copy.remove("generated_at"); return copy;
    }

    private static List<String> metricsTable(ArrayNode items, boolean holdout) {
        List<String> lines = new ArrayList<>(List.of("| Series | Completed | Wins | Losses | Win rate | Expectancy R | Max DD |", "|---|---:|---:|---:|---:|---:|---:|"));
        for (JsonNode item : items) {
            JsonNode metrics = holdout ? item.path("holdout").get("metrics") : item.get("walk_forward_oos");
            lines.add("| " + item.path("series").asText() + " | " + dash(metrics, "completed_trades") + " | " + dash(metrics, "wins") + " | "
                    + dash(metrics, "losses") + " | " + percent1(metrics == null ? null : metrics.get("win_rate")) + " | "
                    + fixed3(metrics == null ? null : metrics.get("expectancy_r")) + " | "
                    + (metrics == null || finite(metrics.get("max_drawdown")) == null ? "—" : String.format(Locale.ROOT, "%.1f%%", number(metrics.get("max_drawdown")) * 100)) + " |");
        } return lines;
    }

    private static String dash(JsonNode object, String field) { return object == null || object.isNull() || object.get(field) == null ? "—" : object.get(field).asText(); }
    private static String percent1(JsonNode value) { return finite(value) == null ? "—" : String.format(Locale.ROOT, "%.1f%%", number(value) * 100); }
    private static String fixed3(JsonNode value) { return finite(value) == null ? "—" : String.format(Locale.ROOT, "%.3f", number(value)); }
    private static String prettyWithoutTerminalNewline(JsonNode value) { String text = NodePrettyJson.write(value); return text.substring(0, text.length() - 1); }

    private static ArrayNode slice(ArrayNode values, int max) { ArrayNode out = JSON.arrayNode(); for (int i = 0; i < Math.min(values.size(), max); i++) out.add(values.get(i).deepCopy()); return out; }
    private static ArrayNode integers(List<Integer> values) { ArrayNode out = JSON.arrayNode(); values.forEach(out::add); return out; }
    private static long monthStart(int month) { return OffsetDateTime.of(month / 12, month % 12 + 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli(); }
    private static Integer rowMonth(JsonNode row) { Double time = availableTimeNullable(row); return time == null ? null : monthOf(time.longValue()); }
    private static int monthOf(long time) { OffsetDateTime date = Instant.ofEpochMilli(time).atOffset(ZoneOffset.UTC); return date.getYear() * 12 + date.getMonthValue() - 1; }
    private static double availableTime(JsonNode row) { Double value = availableTimeNullable(row); return value == null ? Double.NaN : value; }
    private static Double availableTimeNullable(JsonNode row) { Double open = finite(row.get("time")); if (open == null) return null; return Math.max(open + timeframeMs(textOr(row.get("timeframe"), "4h")), numberOr(first(row.get("available_at"), row.get("availability_time")), Double.NEGATIVE_INFINITY)); }
    private static long timeframeMs(String timeframe) { Matcher matcher = TIMEFRAME.matcher(lower(timeframe)); if (!matcher.matches()) return BAR_MS; long unit = switch (matcher.group(2)) { case "m" -> 60_000; case "h" -> 3_600_000; default -> 86_400_000; }; return Long.parseLong(matcher.group(1)) * unit; }
    private static Double evaluationWindowMs(ArrayNode rows) { if (rows.isEmpty() || finite(rows.get(0).get("time")) == null || finite(rows.get(rows.size()-1).get("time")) == null) return null; return Math.max(0, number(rows.get(rows.size()-1).get("time")) - number(rows.get(0).get("time"))) + timeframeMs(textOr(rows.get(0).get("timeframe"), "4h")); }

    private static byte[] gzip(byte[] bytes) throws IOException { ByteArrayOutputStream output = new ByteArrayOutputStream(); try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(bytes); } return output.toByteArray(); }
    private static byte[] gunzip(byte[] bytes) throws IOException { try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(bytes))) { return input.readAllBytes(); } }
    private static String jsIso(Instant instant) { return instant.truncatedTo(ChronoUnit.MILLIS).toString(); }

    private static Double timeBound(JsonNode value, String name) {
        if (value == null || value.isNull() || value.isMissingNode() || value.isTextual() && value.asText().isEmpty()) return null;
        Double parsed;
        if (value.isNumber() || value.isTextual() && value.asText().matches("^\\d+$")) parsed = finite(value);
        else try { parsed = (double) Instant.parse(value.asText()).toEpochMilli(); }
        catch (DateTimeParseException first) { try { parsed = (double) OffsetDateTime.parse(value.asText()).toInstant().toEpochMilli(); }
            catch (DateTimeParseException second) { try { parsed = (double) LocalDate.parse(value.asText()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(); }
                catch (DateTimeParseException third) { throw new IllegalArgumentException(name + " must be a finite timestamp or ISO date"); } } }
        if (parsed == null) throw new IllegalArgumentException(name + " must be a finite timestamp or ISO date"); return parsed;
    }

    private static ObjectNode with(JsonNode options, String key, JsonNode value) { ObjectNode out = options != null && options.isObject() ? ((ObjectNode) options).deepCopy() : JSON.objectNode(); out.set(key, value); return out; }
    private static JsonNode option(JsonNode object, String... names) { if (object == null) return null; for (String name : names) if (object.has(name) && !object.get(name).isNull()) return object.get(name); return null; }
    private static JsonNode first(JsonNode... values) { for (JsonNode value : values) if (value != null && !value.isMissingNode() && !value.isNull()) return value; return null; }
    private static JsonNode firstTruthy(JsonNode... values) { for (JsonNode value : values) if (truthy(value)) return value; return null; }
    private static String firstText(JsonNode... values) { for (JsonNode value : values) if (truthy(value)) return value.asText(); return null; }
    private static boolean present(JsonNode object, String field) { return object != null && object.has(field) && !object.get(field).isNull(); }
    private static boolean truthy(JsonNode node) { if (node == null || node.isNull() || node.isMissingNode()) return false; if (node.isBoolean()) return node.booleanValue(); if (node.isNumber()) return node.doubleValue() != 0 && !Double.isNaN(node.doubleValue()); if (node.isTextual()) return !node.asText().isEmpty(); return true; }
    private static boolean bool(JsonNode node, boolean fallback) { return node != null && node.isBoolean() ? node.booleanValue() : fallback; }
    private static boolean isFalse(JsonNode node) { return node != null && node.isBoolean() && !node.booleanValue(); }
    private static String nullableText(JsonNode node) { return node == null || node.isNull() || node.isMissingNode() ? null : node.asText(); }
    private static String textOr(JsonNode node, String fallback) { return truthy(node) ? node.asText() : fallback; }
    private static String lower(String value) { return value == null ? null : value.toLowerCase(Locale.ROOT); }
    private static JsonNode textOrNull(String value) { return value == null ? NullNode.instance : TextNode.valueOf(value); }
    private static JsonNode copyOrNull(JsonNode node) { return node == null || node.isMissingNode() ? NullNode.instance : node.deepCopy(); }
    private static ArrayNode array(JsonNode node) { return node != null && node.isArray() ? (ArrayNode) node : JSON.arrayNode(); }
    private static ArrayNode arrayish(JsonNode node) { ArrayNode out = JSON.arrayNode(); if (node == null || node.isNull() || node.isMissingNode()) return out; if (node.isArray()) node.forEach(value -> out.add(value.deepCopy())); else out.add(node.deepCopy()); return out; }
    private static ArrayNode strings(ArrayNode input, boolean lowercase) { ArrayNode out = JSON.arrayNode(); for (JsonNode node : input) if (truthy(node)) out.add(lowercase ? node.asText().toLowerCase(Locale.ROOT) : node.asText()); return out; }
    private static ArrayNode upperStrings(ArrayNode input) { ArrayNode out = JSON.arrayNode(); for (JsonNode node : input) if (truthy(node)) out.add(node.asText().toUpperCase(Locale.ROOT)); return out; }
    private static ArrayNode distinctStrings(ArrayNode input, boolean lowercase) { LinkedHashSet<String> values = new LinkedHashSet<>(); for (JsonNode node : input) values.add(lowercase ? node.asText().toLowerCase(Locale.ROOT) : node.asText()); ArrayNode out = JSON.arrayNode(); values.forEach(out::add); return out; }
    private static void addUpper(Set<String> values, JsonNode node) { if (truthy(node)) values.add(node.asText().toUpperCase(Locale.ROOT)); }
    private static boolean matchesListed(JsonNode listed, String value) { ArrayNode values = array(listed); if (values.isEmpty()) return true; for (JsonNode item : values) if (Objects.equals(item.asText(), value)) return true; return false; }
    private static boolean numericEqual(JsonNode left, JsonNode right) { Double a = jsCoerciveNumber(left), b = jsCoerciveNumber(right); return a != null && b != null && Double.compare(a, b) == 0; }
    private static Double jsCoerciveNumber(JsonNode node) { if (node == null || node.isNull()) return 0d; if (node.isBoolean()) return node.booleanValue() ? 1d : 0d; return finite(node); }
    private static Double finite(JsonNode node) { if (node == null || node.isNull() || node.isMissingNode() || node.isBoolean() || node.isContainerNode()) return null; if (node.isTextual() && node.asText().isEmpty()) return null; try { double value = node.isNumber() ? node.doubleValue() : Double.parseDouble(node.asText().trim()); return Double.isFinite(value) ? value : null; } catch (NumberFormatException ignored) { return null; } }
    private static double number(JsonNode node) { Double value = finite(node); return value == null ? Double.NaN : value; }
    private static double numberOr(JsonNode node, double fallback) { Double value = finite(node); return value == null ? fallback : value; }
    private static long longNumber(JsonNode node) { return (long) number(node); }
    private static int trunc(double value) { return (int) (value < 0 ? Math.ceil(value) : Math.floor(value)); }
    private static int integerOption(JsonNode options, int fallback, String... names) { JsonNode node = option(options, names); return node == null ? fallback : trunc(numberOr(node, fallback)); }
    private static void putNullableNumber(ObjectNode object, String field, Double value) { if (value == null) object.set(field, NullNode.instance); else putNumber(object, field, value); }
    private static void putNumber(ObjectNode object, String field, double value) { if (value == Math.rint(value) && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) object.put(field, (long) value); else object.put(field, value); }
    private static double clamp(double value, double low, double high) { return Math.min(high, Math.max(low, value)); }
    private static double sum(List<ObjectNode> nodes, String field) { double total = 0; for (ObjectNode node : nodes) total += numberOr(node.get(field), 0); return total; }
    private static double mean(double[] values) { double total = 0; for (double value : values) total += value; return total / values.length; }
    private static double sampleVariance(double[] values, double mean) { double total = 0; for (double value : values) total += (value - mean) * (value - mean); return total / (values.length - 1); }
    private static List<JsonNode> toList(ArrayNode values) { List<JsonNode> out = new ArrayList<>(); values.forEach(out::add); return out; }
    private static void sortArray(ArrayNode values, Comparator<JsonNode> comparator) { List<JsonNode> sorted = toList(values); sorted.sort(comparator); values.removeAll(); sorted.forEach(values::add); }
    private static boolean isNormalizedCandidate(JsonNode value) { return value != null && value.isObject() && value.has("threshold") && value.has("setup_families"); }
    private static String jsNumber(double value) { return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value); }
}

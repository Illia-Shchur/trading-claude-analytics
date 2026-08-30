package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.regex.Pattern;

/**
 * In-process Java counterpart of {@code tools/strategy-research-v5-worker.mjs}.
 *
 * <p>The Node worker is only a bounded transport around three deterministic
 * strategy-research/5 fixture helpers.  This class keeps that boundary: input
 * rows and the candidate are copied, validated, evaluated, and returned as
 * either {@code {metrics}} or {@code {error}}.  No caller-provided aggregate
 * return or cost is accepted as an authoritative outcome.</p>
 */
public final class StrategyPerformanceV5Worker {
    public static final int DEFAULT_SHARED_BYTES = 16 * 1024 * 1024;
    public static final int DEFAULT_OUTPUT_BYTES = DEFAULT_SHARED_BYTES - 8;

    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");
    private static final Set<String> LABEL_KEYS = Set.of(
            "target", "label", "outcome", "forward_return", "future_return",
            "forward_pnl", "future_pnl", "net_r", "gross_r", "resolved_at",
            "resolution_bars", "exit_price", "exit_time");
    private static final Set<String> V5_UNIVERSE = Set.of(
            "btc", "eth", "sol", "bnb", "xrp", "ada", "link", "aave");
    private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private StrategyPerformanceV5Worker() {}

    /** Execute one worker message with the Node worker's default output bound. */
    public static ObjectNode evaluate(ObjectNode workerData, JsonNode candidate) {
        return evaluate(workerData, candidate, DEFAULT_OUTPUT_BYTES);
    }

    /**
     * Execute one bounded worker message.  Expected worker-data fields are
     * {@code featureRows}, {@code labelRows}, {@code executionRows}, and
     * {@code manifestSha256} (camel/snake aliases are accepted by the port).
     */
    public static ObjectNode evaluate(ObjectNode workerData, JsonNode candidate, int maxOutputBytes) {
        if (maxOutputBytes < 1) throw failure("bounded worker output capacity must be positive");
        ObjectNode response;
        try {
            ObjectNode source = workerData == null ? object() : workerData;
            ArrayNode trades = buildAuthoritativeTradesFixture(
                    requiredArray(first(source, "featureRows", "feature_rows"), "featureRows"),
                    requiredArray(first(source, "labelRows", "label_rows"), "labelRows"),
                    requiredArray(first(source, "executionRows", "execution_rows"), "executionRows"),
                    candidate,
                    text(first(source, "manifestSha256", "manifest_sha256")),
                    nullableText(first(source, "featurePartitionSha256", "feature_partition_sha256")),
                    nullableText(first(source, "labelPartitionSha256", "label_partition_sha256")),
                    nullableText(first(source, "executionPartitionSha256", "execution_partition_sha256")));
            ObjectNode episodeReturns = marketWideEpisodeVector(
                    requiredArray(first(source, "labelRows", "label_rows"), "labelRows"), trades);
            response = object();
            response.set("metrics", metricsFromTrades(trades, episodeReturns));
        } catch (RuntimeException error) {
            response = object().put("error", rootMessage(error));
        }
        if (compactBytes(response) > maxOutputBytes) {
            ObjectNode bounded = object().put("error", "bounded worker result exceeds shared output capacity");
            if (compactBytes(bounded) > maxOutputBytes)
                throw failure("bounded worker output capacity cannot hold the fail-closed result");
            return bounded;
        }
        return response;
    }

    /** Fixture-only authoritative chain used by the original worker. */
    public static ArrayNode buildAuthoritativeTradesFixture(ObjectNode options) {
        ObjectNode source = options == null ? object() : options;
        return buildAuthoritativeTradesFixture(
                requiredArray(first(source, "featureRows", "feature_rows"), "featureRows"),
                requiredArray(first(source, "labelRows", "label_rows"), "labelRows"),
                requiredArray(first(source, "executionRows", "execution_rows"), "executionRows"),
                first(source, "candidate"), text(first(source, "manifestSha256", "manifest_sha256")),
                nullableText(first(source, "featurePartitionSha256", "feature_partition_sha256")),
                nullableText(first(source, "labelPartitionSha256", "label_partition_sha256")),
                nullableText(first(source, "executionPartitionSha256", "execution_partition_sha256")));
    }

    public static ObjectNode marketWideEpisodeVector(ObjectNode options) {
        ObjectNode source = options == null ? object() : options;
        return marketWideEpisodeVector(requiredArray(first(source, "labelRows", "label_rows"), "labelRows"),
                requiredArray(first(source, "trades"), "trades"));
    }

    public static ObjectNode metricsFromTrades(ObjectNode options) {
        ObjectNode source = options == null ? object() : options;
        return metricsFromTrades(requiredArray(first(source, "trades"), "trades"),
                first(source, "episodeReturns", "episode_returns"));
    }

    private static ArrayNode buildAuthoritativeTradesFixture(
            ArrayNode featureRows, ArrayNode labelRows, ArrayNode executionRows,
            JsonNode rawCandidate, String manifestSha256, String featurePartitionSha256,
            String labelPartitionSha256, String executionPartitionSha256) {
        validateRows(featureRows, labelRows, executionRows);
        String manifestBinding = requireHash(manifestSha256, "manifest_sha256");
        String featureBinding = featurePartitionSha256 == null
                ? hash(featureRows) : requireHash(featurePartitionSha256, "feature_partition_sha256");
        String labelBinding = labelPartitionSha256 == null
                ? hash(labelRows) : requireHash(labelPartitionSha256, "label_partition_sha256");
        String executionBinding = executionPartitionSha256 == null
                ? hash(executionRows) : requireHash(executionPartitionSha256, "execution_partition_sha256");
        requireHash(featureBinding, "feature_partition_sha256");
        requireHash(labelBinding, "label_partition_sha256");
        requireHash(executionBinding, "execution_partition_sha256");
        if (!(rawCandidate instanceof ObjectNode candidate)) throw failure("candidate must be an object");

        Map<String, JsonNode> labelsById = new LinkedHashMap<>();
        Map<String, JsonNode> labelsByKey = new LinkedHashMap<>();
        for (JsonNode row : labelRows) {
            if (truthy(row.get("signal_id"))) labelsById.put(text(row.get("signal_id")), row);
            labelsByKey.put(keyFor(row), row);
        }
        Map<String, JsonNode> fillsById = new LinkedHashMap<>();
        Map<String, JsonNode> fillsByKey = new LinkedHashMap<>();
        for (JsonNode row : executionRows) {
            if (truthy(row.get("signal_id"))) fillsById.put(text(row.get("signal_id")), row);
            fillsByKey.put(keyFor(row), row);
        }

        JsonNode definition = truthy(candidate.get("definition")) ? candidate.get("definition") : candidate;
        List<JsonNode> orderedFeatures = new ArrayList<>();
        featureRows.forEach(orderedFeatures::add);
        orderedFeatures.sort(Comparator.comparingLong(row -> millis(firstNullish(row,
                "decision_time", "event_time", "time"))));
        ArrayNode intents = array();
        for (JsonNode feature : orderedFeatures) {
            if (!candidateMatches(feature, definition)) continue;
            long decisionTime = millis(firstNullish(feature, "decision_time", "event_time", "time"));
            String candidateId = firstTruthyText(candidate, "candidate_id", "id");
            String id = truthy(feature.get("signal_id")) ? text(feature.get("signal_id"))
                    : text(feature.get("asset")).toLowerCase(Locale.ROOT) + "-" + decisionTime + "-"
                    + (candidateId == null ? "candidate" : candidateId);
            JsonNode label = labelsById.get(id);
            if (label == null) label = labelsByKey.get(keyForAssetTime(feature.get("asset"), decisionTime));
            if (label == null) throw failure("missing label for eligible signal " + id);
            JsonNode execution = fillsById.get(id);
            if (execution == null) {
                String secondaryId = firstTruthyText(label, "execution_id", "fill_id");
                execution = fillsById.get(secondaryId == null ? "" : secondaryId);
            }
            if (execution == null) execution = fillsByKey.get(keyFor(label));
            if (execution == null) execution = fillsByKey.get(keyForAssetTime(feature.get("asset"), decisionTime));
            if (execution == null) throw failure("missing execution row for eligible signal " + id);

            Outcome outcome = deriveExecutionOutcome(label, execution, definition);
            double fundingDebit = Math.abs(outcome.fundingUsd() / outcome.riskAmount());
            JsonNode scenarioInputs = execution.get("scenario_inputs");
            if (truthy(scenarioInputs) && !scenarioInputs.isContainerNode())
                throw failure("execution scenario_inputs must be an object");
            ObjectNode trade = object().put("signal_id", id);
            if (candidateId != null) trade.put("candidate_id", candidateId);
            trade.put("asset", normalizeAsset(feature.get("asset")))
                    .put("direction", outcome.direction()).put("decision_time", iso(decisionTime))
                    .put("feature_sha256", hash(feature)).put("label_sha256", hash(label))
                    .put("execution_sha256", hash(execution)).put("manifest_sha256", manifestBinding)
                    .put("feature_partition_sha256", featureBinding).put("label_partition_sha256", labelBinding)
                    .put("execution_partition_sha256", executionBinding);
            JsonNode availability = firstTruthy(label, "availability_time");
            if (!truthy(availability)) availability = firstTruthy(execution, "availability_time");
            if (!truthy(availability)) availability = firstTruthy(feature, "availability_time");
            trade.set("availability_time", truthy(availability) ? availability.deepCopy() : NullNode.instance);
            trade.put("risk_amount", outcome.riskAmount()).put("net_r", outcome.netUsd() / outcome.riskAmount())
                    .put("gross_r", outcome.grossUsd() / outcome.riskAmount())
                    .put("fee_r", outcome.feesUsd() / outcome.riskAmount())
                    .put("slippage_r", outcome.slippageUsd() / outcome.riskAmount())
                    .put("funding_debit_r", fundingDebit);
            trade.set("funding_settlements", outcome.fundingSettlements().deepCopy());
            trade.put("fees_usd", outcome.feesUsd()).put("slippage_usd", outcome.slippageUsd())
                    .put("gross_pnl_usd", outcome.grossUsd()).put("net_pnl_usd", outcome.netUsd())
                    .put("quantity", outcome.quantity()).put("contract_multiplier", outcome.multiplier())
                    .put("notional", outcome.entryNotional());
            trade.put("venue", orText(execution, label, "venue", "binance"));
            trade.put("instrument_type", orText(execution, label, "instrument_type", "spot"));
            putTruthyOrNull(trade, "contract_spec_sha256", execution, label);
            putNullishFallback(trade, "liquidation_price", execution, label);
            JsonNode instrument = truthy(execution.get("instrument")) ? execution.get("instrument")
                    : truthy(label.get("instrument")) ? label.get("instrument") : null;
            if (instrument == null) {
                ObjectNode generated = object().put("instrument_type",
                                orText(execution, label, "instrument_type", "spot"))
                        .put("symbol", orText(execution, label, "symbol",
                                text(feature.get("asset")).toUpperCase(Locale.ROOT) + "USDT"))
                        .put("venue", orText(execution, label, "venue", "binance"))
                        .put("asset", normalizeAsset(feature.get("asset")))
                        .put("contract_multiplier", outcome.multiplier());
                JsonNode expiry = truthy(execution.get("expiry")) ? execution.get("expiry")
                        : truthy(label.get("expiry")) ? label.get("expiry") : null;
                generated.set("expiry", expiry == null ? NullNode.instance : expiry.deepCopy());
                instrument = generated;
            }
            trade.set("instrument", instrument.deepCopy());
            trade.put("symbol", orText(execution, label, "symbol",
                    text(feature.get("asset")).toUpperCase(Locale.ROOT) + "USDT"));
            putTruthyOrNull(trade, "margin_mode", execution, label);
            putTruthyOrNull(trade, "leverage", execution, label);
            putNullishFallback(trade, "collateral", execution, label);
            putTruthyOrNull(trade, "collateral_asset", execution, label);
            putUndefinedAwareNullishFallback(trade, "maintenance_margin_ratio", execution, label);
            trade.put("entry_price", outcome.entryPrice()).put("exit_price", outcome.exitPrice());
            JsonNode episode = label.get("episode_id");
            trade.put("episode_id", truthy(episode) ? text(episode)
                    : text(feature.get("asset")) + ":" + decisionTime);
            trade.put("entry_time", iso(outcome.entryTime())).put("exit_time", iso(outcome.exitTime()))
                    .put("exit_reason", outcome.exitReason());
            trade.set("child_bars", execution.get("child_bars").deepCopy());
            trade.set("scenario_inputs", truthy(scenarioInputs) ? scenarioInputs.deepCopy() : NullNode.instance);
            if (truthy(scenarioInputs)) trade.put("scenario_inputs_sha256", hash(scenarioInputs));
            else trade.putNull("scenario_inputs_sha256");
            intents.add(trade);
        }
        Map<String, String> debitBindings = Map.of(
                "delayed_entry_debit_r", "DELAYED_ENTRY",
                "collision_debit_r", "ADVERSE_OHLC_COLLISION",
                "gap_debit_r", "GAP", "capacity_debit_r", "CAPACITY",
                "outage_debit_r", "VENUE_OUTAGE", "expiry_debit_r", "EXPIRY",
                "liquidation_debit_r", "LIQUIDATION");
        for (JsonNode rawTrade : intents) {
            ObjectNode trade = (ObjectNode) rawTrade;
            JsonNode scenarios = trade.get("scenario_inputs");
            for (Map.Entry<String, String> entry : debitBindings.entrySet()) {
                JsonNode debit = scenarios != null && scenarios.isObject()
                        ? scenarios.path(entry.getValue()).get("debit_r") : null;
                trade.set(entry.getKey(), nullish(debit) ? NullNode.instance : debit.deepCopy());
            }
        }
        return intents;
    }

    private static void validateRows(ArrayNode features, ArrayNode labels, ArrayNode executions) {
        for (JsonNode row : features) {
            if (!row.isObject()) throw failure("feature row lacks decision/event time");
            String path = recursivelyHasLabelField(row, "");
            if (path != null) throw failure("feature row contains label/outcome field " + path);
            if (!truthy(row.get("asset")) || !defined(row, "decision_time") && !defined(row, "event_time"))
                throw failure("feature row lacks decision/event time");
            long decision = millis(firstNullish(row, "decision_time", "event_time"));
            long available = millis(row.get("availability_time"));
            if (available > decision) throw failure("feature row is not PIT-available by its decision time");
        }
        for (JsonNode row : labels) {
            if (!row.isObject() || recursivelyHasLabelField(row, "") == null)
                throw failure("label row lacks explicit future outcome field");
            if (!truthy(row.get("asset"))) throw failure("label row lacks asset");
            long available = millis(row.get("availability_time"));
            long resolved = millis(firstNullish(row, "exit_time", "resolution_time", "outcome_time",
                    "availability_time"));
            if (available < resolved) throw failure("label row availability precedes outcome resolution");
        }
        for (JsonNode row : executions) {
            if (!row.isObject() || !truthy(row.get("signal_id"))
                    && !(truthy(row.get("asset")) && (defined(row, "decision_time") || defined(row, "entry_time"))))
                throw failure("execution row lacks signal binding");
            JsonNode child = row.get("child_bars");
            if (!(child instanceof ArrayNode bars) || bars.size() < 2)
                throw failure("execution row lacks physically bound 1m child bars");
            Set<Long> seen = new HashSet<>();
            long prior = Long.MIN_VALUE;
            int index = 0;
            for (JsonNode bar : bars) {
                long event = millis(firstNullish(bar, "event_time", "time", "open_time"));
                if (!seen.add(event) || index > 0 && event != prior + 60_000L)
                    throw failure("execution child bars are not a dense unique 1m path");
                long available = millis(firstNullish(bar, "availability_time", "close_time"));
                if (available < event + 59_000L)
                    throw failure("execution child bar is available before its close");
                prior = event;
                index++;
            }
        }
    }

    private static String recursivelyHasLabelField(JsonNode value, String path) {
        if (value == null || !value.isContainerNode()) return null;
        if (value.isArray()) {
            for (int index = 0; index < value.size(); index++) {
                String nested = recursivelyHasLabelField(value.get(index), path + (path.isEmpty() ? "" : ".") + index);
                if (nested != null) return nested;
            }
            return null;
        }
        var fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
            if (LABEL_KEYS.contains(entry.getKey().toLowerCase(Locale.ROOT))) return childPath;
            String nested = recursivelyHasLabelField(entry.getValue(), childPath);
            if (nested != null) return nested;
        }
        return null;
    }

    private static boolean candidateMatches(JsonNode row, JsonNode rawDefinition) {
        ObjectNode definition = compileLooseDefinition(rawDefinition);
        JsonNode rawRule = truthy(definition.get("signal_rule")) ? definition.get("signal_rule")
                : truthy(definition.get("predicate")) ? definition.get("predicate") : object();
        ObjectNode rule = rawRule instanceof ObjectNode objectRule ? objectRule : object();
        if (rule.path("always").isBoolean() && rule.path("always").booleanValue()
                || rule.isEmpty())
            return !(row.path("signal_eligible").isBoolean() && !row.path("signal_eligible").booleanValue());
        double value = numberJs(row.get(text(rule.get("feature"))));
        double threshold = numberJs(rule.get("value"));
        if (!Double.isFinite(value) || !Double.isFinite(threshold)) return false;
        return switch (text(rule.get("op"))) {
            case ">" -> value > threshold;
            case ">=" -> value >= threshold;
            case "<" -> value < threshold;
            case "<=" -> value <= threshold;
            case "==" -> value == threshold;
            default -> false;
        };
    }

    private static ObjectNode compileLooseDefinition(JsonNode raw) {
        ObjectNode value = raw instanceof ObjectNode object ? object.deepCopy() : object();
        double threshold = numberJs(value.get("threshold"));
        if (!truthy(value.get("signal_rule")) && Double.isFinite(threshold)) {
            ObjectNode rule = object().put("feature", "score")
                    .put("op", truthy(value.get("threshold_op")) ? text(value.get("threshold_op")) : ">=")
                    .put("value", threshold);
            value.set("signal_rule", rule);
        }
        if (!truthy(value.get("direction")) && truthy(value.get("side")))
            value.put("direction", text(value.get("side")).toLowerCase(Locale.ROOT));
        double window = numberJs(value.get("window"));
        if (!truthy(value.get("max_lifecycle_bars")) && Double.isFinite(window))
            value.put("max_lifecycle_bars", window);
        if (value.path("use_filter").isBoolean() && !value.path("use_filter").booleanValue()
                && truthy(value.get("signal_rule")) && value.get("signal_rule").isObject())
            ((ObjectNode) value.get("signal_rule")).putNull("filter");
        return value;
    }

    private static Outcome deriveExecutionOutcome(JsonNode label, JsonNode execution, JsonNode rawDefinition) {
        ObjectNode definition = compileLooseDefinition(rawDefinition);
        String direction = orText(definition, execution, label, "direction", "long").toLowerCase(Locale.ROOT);
        int sign = "short".equals(direction) ? -1 : 1;
        if (!Set.of("long", "short").contains(direction))
            throw failure("execution direction must be long or short");
        for (String key : List.of("net_r", "gross_r", "forward_return", "cost_r", "fee_r",
                "slippage_r", "funding_debit_r", "gross_pnl", "net_pnl")) {
            if (defined(label, key) || defined(execution, key))
                throw failure("precomputed label return/cost row is not authoritative; provide outcome paths and execution bars");
        }
        if (!(execution.get("child_bars") instanceof ArrayNode rawBars) || rawBars.size() < 2)
            throw failure("execution row lacks the required 1m child-bar path");
        double riskAmount = numberJs(firstNullish(execution, label, "risk_amount"));
        if (!(riskAmount > 0)) throw failure("authoritative execution requires a positive account risk_amount");
        double quantity = numberJs(firstNullish(execution, label, "quantity"));
        if (!(quantity > 0)) throw failure("authoritative execution requires an exact positive quantity");
        String instrumentType = orText(execution, label, "instrument_type", "spot").toLowerCase(Locale.ROOT);
        JsonNode multiplierRaw = firstNullish(execution, label, "contract_multiplier");
        double multiplier = nullish(multiplierRaw) && "spot".equals(instrumentType) ? 1 : numberJs(multiplierRaw);
        if (!(multiplier > 0)) throw failure("authoritative derivative execution requires contract_multiplier");

        List<Bar> bars = new ArrayList<>();
        for (JsonNode row : rawBars) {
            bars.add(new Bar(row, millis(firstNullish(row, "time", "event_time")),
                    numberJs(row.get("open")), numberJs(row.get("high")),
                    numberJs(row.get("low")), numberJs(row.get("close"))));
        }
        bars.sort(Comparator.comparingLong(Bar::time));
        JsonNode entryRaw = truthy(execution.get("entry_time")) ? execution.get("entry_time") : label.get("entry_time");
        long entryTime = millis(entryRaw);
        int entryIndex = -1;
        for (int index = 0; index < bars.size(); index++) if (bars.get(index).time() == entryTime) { entryIndex = index; break; }
        if (entryIndex < 0) throw failure("execution child bars do not cover exact entry path");
        Bar entryBar = bars.get(entryIndex);
        double entryPrice = !Double.isNaN(entryBar.open()) ? entryBar.open() : entryBar.close();
        if (!(entryPrice > 0)) throw failure("execution entry bar has no positive price");
        JsonNode lifecycle = truthy(definition.get("lifecycle")) ? definition.get("lifecycle") : object();
        double maxBarsRaw = numberJs(firstNullish(definition.get("max_lifecycle_bars"), lifecycle.get("max_bars"), numberNode(0)));
        double stop = numberJs(firstNullish(definition.get("stop_price"), lifecycle.get("stop_price")));
        double target = numberJs(firstNullish(definition.get("target_price"), lifecycle.get("target_price")));
        int lastIndex = maxBarsRaw > 0
                ? Math.min(bars.size() - 1, entryIndex + (int) Math.floor(maxBarsRaw)) : bars.size() - 1;
        Bar exitBar = null;
        Double forcedPrice = null;
        String exitReason = "BOUND_EXECUTION_POLICY";
        for (int index = entryIndex + 1; index <= lastIndex; index++) {
            Bar bar = bars.get(index);
            if (Double.isFinite(stop) && ("long".equals(direction) && bar.low() <= stop
                    || "short".equals(direction) && bar.high() >= stop)) {
                exitBar = bar; forcedPrice = stop; exitReason = "STOP"; break;
            }
            if (Double.isFinite(target) && ("long".equals(direction) && bar.high() >= target
                    || "short".equals(direction) && bar.low() <= target)) {
                exitBar = bar; forcedPrice = target; exitReason = "TARGET"; break;
            }
        }
        if (exitBar == null && lastIndex >= 0 && lastIndex < bars.size()) exitBar = bars.get(lastIndex);
        double exitPrice = exitBar == null ? Double.NaN : forcedPrice == null ? exitBar.close() : forcedPrice;
        if (exitBar == null || !(exitBar.time() > entryTime) || !(exitPrice > 0))
            throw failure("execution child bars do not cover exact exit outcome path");

        double generalFee = executionFeeRate(execution);
        double entryFee = numberJs(firstNullish(execution.get("entry_fee_rate"), numberNode(generalFee)));
        double exitFee = numberJs(firstNullish(execution.get("exit_fee_rate"), numberNode(generalFee)));
        double generalSlippage = executionSlippageRate(execution);
        double entrySlippage = numberJs(firstNullish(execution.get("entry_slippage_rate"), numberNode(generalSlippage)));
        double exitSlippage = numberJs(firstNullish(execution.get("exit_slippage_rate"), numberNode(generalSlippage)));
        for (double value : new double[]{entryFee, exitFee, entrySlippage, exitSlippage})
            if (!Double.isFinite(value) || value < 0)
                throw failure("execution fee/slippage policy fields are not finite");
        double entryNotional = entryPrice * quantity * multiplier;
        double exitNotional = exitPrice * quantity * multiplier;
        double feesUsd = entryNotional * entryFee + exitNotional * exitFee;
        double slippageUsd = entryNotional * entrySlippage + exitNotional * exitSlippage;
        JsonNode rawSettlements = truthy(execution.get("funding_settlements"))
                ? execution.get("funding_settlements") : array();
        if (!(rawSettlements instanceof ArrayNode settlements)) throw failure("funding settlements must be an array");
        double fundingUsd = 0;
        for (JsonNode settlement : settlements) {
            if (!truthy(settlement.get("event_id")) || !truthy(settlement.get("source"))
                    || !truthy(settlement.get("venue")) || !truthy(settlement.get("instrument"))
                    || !Double.isFinite(numberJs(firstNullish(settlement, "amount", "pnl"))))
                throw failure("funding settlement identity is incomplete");
            fundingUsd += numberJs(firstNullish(settlement, "amount", "pnl"));
        }
        double grossUsd = sign * (exitPrice - entryPrice) * quantity * multiplier;
        double netUsd = grossUsd - feesUsd - slippageUsd + fundingUsd;
        return new Outcome(direction, riskAmount, quantity, multiplier, entryTime, exitBar.time(),
                entryPrice, exitPrice, entryNotional, grossUsd, feesUsd, slippageUsd,
                fundingUsd, netUsd, exitReason, settlements.deepCopy());
    }

    private static double executionFeeRate(JsonNode execution) {
        if (defined(execution, "fee_rate")) return numberJs(execution.get("fee_rate"));
        if (defined(execution, "taker_fee_rate")) return numberJs(execution.get("taker_fee_rate"));
        if (defined(execution, "fee_bps")) return numberJs(execution.get("fee_bps")) / 10_000D;
        return 0;
    }

    private static double executionSlippageRate(JsonNode execution) {
        if (defined(execution, "slippage_rate")) return numberJs(execution.get("slippage_rate"));
        if (defined(execution, "slippage_bps")) return numberJs(execution.get("slippage_bps")) / 10_000D;
        return 0;
    }

    private static ObjectNode marketWideEpisodeVector(ArrayNode labels, ArrayNode trades) {
        Set<String> unique = new HashSet<>();
        for (JsonNode row : labels) {
            String id = truthy(row.get("episode_id")) ? text(row.get("episode_id"))
                    : text(row.get("asset")) + ":" + millis(firstNullish(row,
                    "decision_time", "event_time", "time"));
            unique.add(id);
        }
        List<String> ids = new ArrayList<>(unique); ids.sort(String::compareTo);
        Map<String, JsonNode> values = new LinkedHashMap<>();
        for (JsonNode row : trades) values.put(text(row.get("episode_id")), row.get("net_r"));
        ObjectNode result = object();
        for (String id : ids) result.set(id, values.containsKey(id) ? values.get(id).deepCopy() : numberNode(0));
        return result;
    }

    private static ObjectNode metricsFromTrades(ArrayNode trades, JsonNode episodeReturns) {
        List<Double> values = new ArrayList<>();
        double wins = 0, losses = 0, cost = 0;
        for (JsonNode trade : trades) {
            double value = numberJs(trade.get("net_r"));
            values.add(value);
            if (value > 0) wins += value;
            if (value < 0) losses += Math.abs(value);
            cost += numberJs(truthy(trade.get("fee_r")) ? trade.get("fee_r") : numberNode(0));
            cost += numberJs(truthy(trade.get("slippage_r")) ? trade.get("slippage_r") : numberNode(0));
            cost += numberJs(truthy(trade.get("funding_debit_r")) ? trade.get("funding_debit_r") : numberNode(0));
        }
        List<Double> bootstrap = blockBootstrapMeans(values, 512, 0x5eed);
        Double p20 = p20(bootstrap);
        ObjectNode result = object().put("completed_episodes", trades.size())
                .put("expectancy_r", values.isEmpty() ? 0 : mean(values));
        if (p20 == null) result.putNull("bootstrap_p20"); else result.put("bootstrap_p20", p20);
        if (p20 == null) result.putNull("weighted_bootstrap_p20"); else result.put("weighted_bootstrap_p20", p20);
        result.put("profit_factor", losses != 0 ? wins / losses : wins > 0 ? 999_999 : 0);
        result.set("episode_returns", nullish(episodeReturns) ? NullNode.instance : episodeReturns.deepCopy());
        return result.put("turnover", trades.size()).put("annualized_turnover", trades.size()).put("cost_r", cost);
    }

    private static List<Double> blockBootstrapMeans(List<Double> values, int iterations, int seed) {
        if (values.isEmpty()) return List.of();
        DoubleSupplier random = xorshift(seed);
        int block = Math.max(1, (int) Math.ceil(Math.sqrt(values.size())));
        List<Double> output = new ArrayList<>(iterations);
        for (int iteration = 0; iteration < iterations; iteration++) {
            List<Double> sample = new ArrayList<>(values.size());
            while (sample.size() < values.size()) {
                double target = random.getAsDouble();
                int start = values.size() - 1;
                double cumulative = 0;
                for (int index = 0; index < values.size(); index++) {
                    cumulative += 1D / values.size();
                    if (target <= cumulative) { start = index; break; }
                }
                for (int offset = 0; offset < block && sample.size() < values.size(); offset++)
                    sample.add(values.get((start + offset) % values.size()));
            }
            output.add(mean(sample));
        }
        return output;
    }

    private static DoubleSupplier xorshift(int seed) {
        int initial = seed == 0 ? 1 : seed;
        int[] state = {initial};
        return () -> {
            int value = state[0];
            value ^= value << 13;
            value ^= value >>> 17;
            value ^= value << 5;
            state[0] = value;
            return Integer.toUnsignedLong(value) / 4_294_967_296D;
        };
    }

    private static Double p20(List<Double> values) {
        if (values.isEmpty()) return null;
        List<Double> sorted = new ArrayList<>(values); sorted.sort(Double::compareTo);
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * .2D) - 1));
    }

    private static double mean(List<Double> values) {
        double total = 0; for (double value : values) total += value;
        return values.isEmpty() ? Double.NaN : total / values.size();
    }

    private static String keyFor(JsonNode row) {
        return text(row.get("asset")).toLowerCase(Locale.ROOT) + "|"
                + millis(firstNullish(row, "decision_time", "entry_time", "event_time", "time"));
    }

    private static String keyForAssetTime(JsonNode asset, long time) {
        return text(asset).toLowerCase(Locale.ROOT) + "|" + time;
    }

    private static String normalizeAsset(JsonNode value) {
        String asset = text(value).toLowerCase(Locale.ROOT);
        if (!V5_UNIVERSE.contains(asset)) throw failure("asset " + (asset.isEmpty() ? "?" : asset) + " is outside the v5 universe");
        return asset;
    }

    private static long millis(JsonNode value) {
        if (value != null && value.isNumber()) {
            double number = value.doubleValue();
            if (Double.isFinite(number)) return (long) number;
        }
        String raw = text(value);
        try { return Instant.parse(raw).toEpochMilli(); }
        catch (RuntimeException ignored) {
            try { return OffsetDateTime.parse(raw).toInstant().toEpochMilli(); }
            catch (RuntimeException error) { throw failure("invalid timestamp " + (nullish(value) ? "undefined" : raw)); }
        }
    }

    private static String iso(long millis) { return ISO_MILLIS.format(Instant.ofEpochMilli(millis)); }
    private static String hash(JsonNode value) { return StrategyPerformanceV5.hashV5Performance(value); }

    private static String requireHash(String value, String label) {
        if (value == null || !HASH.matcher(value).matches()) throw failure(label + " must be a SHA-256 hash");
        return value;
    }

    private static ArrayNode requiredArray(JsonNode value, String label) {
        if (!(value instanceof ArrayNode rows))
            throw failure("v5 authoritative chain requires separate feature, label, and execution rows");
        return rows;
    }

    private static JsonNode first(JsonNode value, String... names) {
        if (value == null || !value.isObject()) return null;
        for (String name : names) if (value.has(name)) return value.get(name);
        return null;
    }

    /** JavaScript nullish-coalescing over field names. */
    private static JsonNode firstNullish(JsonNode value, String... names) {
        if (value == null || !value.isObject()) return null;
        for (String name : names) {
            JsonNode candidate = value.get(name);
            if (!nullish(candidate)) return candidate;
        }
        return null;
    }

    private static JsonNode firstNullish(JsonNode left, JsonNode right, String field) {
        JsonNode value = left == null ? null : left.get(field);
        return nullish(value) ? right == null ? null : right.get(field) : value;
    }

    private static JsonNode firstNullish(JsonNode... values) {
        for (JsonNode value : values) if (!nullish(value)) return value;
        return null;
    }

    private static JsonNode firstTruthy(JsonNode value, String... names) {
        if (value == null || !value.isObject()) return null;
        for (String name : names) if (truthy(value.get(name))) return value.get(name);
        return null;
    }

    private static String firstTruthyText(JsonNode value, String... names) {
        JsonNode result = firstTruthy(value, names); return result == null ? null : text(result);
    }

    private static String orText(JsonNode left, JsonNode right, String field, String fallback) {
        JsonNode value = truthy(left == null ? null : left.get(field)) ? left.get(field)
                : truthy(right == null ? null : right.get(field)) ? right.get(field) : null;
        return value == null ? fallback : text(value);
    }

    private static String orText(JsonNode first, JsonNode second, JsonNode third, String field, String fallback) {
        JsonNode value = truthy(first == null ? null : first.get(field)) ? first.get(field)
                : truthy(second == null ? null : second.get(field)) ? second.get(field)
                : truthy(third == null ? null : third.get(field)) ? third.get(field) : null;
        return value == null ? fallback : text(value);
    }

    private static void putTruthyOrNull(ObjectNode target, String field, JsonNode left, JsonNode right) {
        JsonNode value = truthy(left.get(field)) ? left.get(field) : truthy(right.get(field)) ? right.get(field) : null;
        target.set(field, value == null ? NullNode.instance : value.deepCopy());
    }

    private static void putNullishFallback(ObjectNode target, String field, JsonNode left, JsonNode right) {
        JsonNode value = firstNullish(left, right, field);
        target.set(field, nullish(value) ? NullNode.instance : value.deepCopy());
    }

    /** JS omits an object property when both nullish-coalescing operands are undefined. */
    private static void putUndefinedAwareNullishFallback(ObjectNode target, String field, JsonNode left, JsonNode right) {
        JsonNode value = firstNullish(left, right, field);
        if (value != null) target.set(field, value.deepCopy());
    }

    private static boolean defined(JsonNode value, String field) {
        return value != null && value.isObject() && value.has(field);
    }

    private static boolean nullish(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode();
    }

    private static boolean truthy(JsonNode value) {
        if (nullish(value)) return false;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) {
            double number = value.doubleValue();
            return number != 0 && !Double.isNaN(number);
        }
        if (value.isTextual()) return !value.textValue().isEmpty();
        return true;
    }

    private static double numberJs(JsonNode value) {
        if (value == null || value.isMissingNode()) return Double.NaN;
        if (value.isNull()) return 0;
        if (value.isBoolean()) return value.booleanValue() ? 1 : 0;
        if (value.isNumber()) return value.doubleValue();
        if (value.isTextual()) {
            String raw = value.textValue().trim();
            if (raw.isEmpty()) return 0;
            try { return Double.parseDouble(raw); } catch (NumberFormatException ignored) { return Double.NaN; }
        }
        return Double.NaN;
    }

    private static String text(JsonNode value) { return nullish(value) ? "" : value.asText(); }
    private static String nullableText(JsonNode value) { return nullish(value) ? null : text(value); }
    private static ObjectNode object() { return MAPPER.createObjectNode(); }
    private static ArrayNode array() { return MAPPER.createArrayNode(); }
    private static JsonNode numberNode(double value) { return MAPPER.getNodeFactory().numberNode(value); }
    private static int compactBytes(JsonNode value) {
        try { return MAPPER.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length; }
        catch (Exception error) { throw failure("bounded worker result cannot be serialized"); }
    }
    private static String rootMessage(Throwable error) {
        Throwable value = error; while (value.getCause() != null) value = value.getCause();
        return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage();
    }
    private static IllegalArgumentException failure(String message) { return new IllegalArgumentException(message); }

    private record Bar(JsonNode source, long time, double open, double high, double low, double close) {}
    private record Outcome(String direction, double riskAmount, double quantity, double multiplier,
                           long entryTime, long exitTime, double entryPrice, double exitPrice,
                           double entryNotional, double grossUsd, double feesUsd, double slippageUsd,
                           double fundingUsd, double netUsd, String exitReason, ArrayNode fundingSettlements) {}
}

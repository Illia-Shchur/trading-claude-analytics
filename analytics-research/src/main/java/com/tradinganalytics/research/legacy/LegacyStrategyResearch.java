package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.research.swing.SwingEngine;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.tradinganalytics.research.legacy.LegacyResearchSupport.*;

/**
 * Programmatic port of the two exports declared by {@code strategy-research.mjs}.
 *
 * <p>The command script historically mixed CLI wiring with two reusable operations. Keeping
 * those operations here makes the Java command adapter a thin boundary and preserves a usable
 * API for callers that imported the JavaScript module directly.</p>
 */
public final class LegacyStrategyResearch {
    private static final Map<String, Long> TIMEFRAME_MILLIS = Map.of(
            "1m", 60_000L,
            "5m", 300_000L,
            "15m", 900_000L,
            "1h", 3_600_000L,
            "4h", 14_400_000L,
            "1d", 86_400_000L);

    private LegacyStrategyResearch() {}

    @FunctionalInterface
    public interface CandidateEvaluator {
        JsonNode evaluate(ArrayNode series, JsonNode candidate, JsonNode options);
    }

    /** Exact port of the exported {@code v3Stress} helper. */
    public static ObjectNode v3Stress(JsonNode trades, JsonNode contract) {
        return v3Stress(trades, contract, false, null);
    }

    public static ObjectNode v3Stress(
            JsonNode trades,
            JsonNode contract,
            boolean derivativesRequired,
            String experimentSha256) {
        LegacyResearchV3.validateAcceptanceContract(contract);
        ArrayNode scenarios = JSON.arrayNode();

        for (JsonNode scenario : rows(contract.get("stress_scenarios"))) {
            String name = text(scenario.get("name"));
            JsonNode spec = scenario.get("parameters");
            Set<String> missing = new LinkedHashSet<>();
            Set<String> violations = new LinkedHashSet<>();
            Set<String> affected = new LinkedHashSet<>();
            List<Double> values = new ArrayList<>();

            for (JsonNode trade : rows(trades)) {
                String id = truthyText(trade.get("trade_id"),
                        truthyText(trade.get("signal_id"), "UNKNOWN_TRADE"));
                Double base = finiteNumber(firstDefined(trade, "net_r", "return_r", "r"));
                double risk = jsNumber(firstDefined(trade, "risk_dollars", "risk_amount"));
                if (base == null) {
                    missing.add(id);
                    continue;
                }

                double value = base;
                if ("DOUBLED_FEES_SLIPPAGE".equals(name)) {
                    Double fee = debitInR(trade.get("fee_r"), trade.get("fees"), risk);
                    Double slippage = debitInR(
                            trade.get("slippage_r"), trade.get("slippage_debit"), risk);
                    if (fee == null || slippage == null) {
                        missing.add(id);
                        continue;
                    }
                    value -= (Math.abs(fee) + Math.abs(slippage))
                            * (jsNumber(spec.get("multiplier")) - 1);
                }
                if ("DOUBLED_FUNDING".equals(name) && derivativesRequired) {
                    double fundingPnl = finiteNumber(trade.get("funding_pnl")) == null
                            ? 0 : jsNumber(trade.get("funding_pnl"));
                    Double debit = debitInR(
                            trade.get("funding_debit_r"),
                            JSON.numberNode(Math.max(0, -fundingPnl)), risk);
                    if (debit == null) {
                        missing.add(id);
                        continue;
                    }
                    value -= Math.abs(debit) * (jsNumber(spec.get("multiplier")) - 1);
                }
                if ("ADVERSE_GAP".equals(name)) {
                    Double notional = finiteNumber(trade.get("notional"));
                    Double mae = finiteNumber(trade.get("mae_r"));
                    Double maePercent = finiteNumber(trade.get("mae_pct"));
                    if (mae == null && maePercent != null && notional != null && risk > 0) {
                        mae = Math.abs(maePercent) / 100 * notional / risk;
                    }
                    Double gap = finiteNumber(firstDefined(
                            trade, "adverse_gap_r", "gap_r", "adverse_gap_debit_r"));
                    Double accountGap = finiteNumber(trade.get("adverse_gap_debit"));
                    if (gap == null && accountGap != null && risk > 0) gap = accountGap / risk;
                    if (gap == null) gap = mae;
                    if (gap == null) {
                        missing.add(id);
                        continue;
                    }
                    double debit = gap == 0 ? jsNumber(spec.get("debit_r")) : gap;
                    value -= Math.abs(debit);
                }
                if ("LIQUIDITY_CAPACITY".equals(name)) {
                    double notional = jsNumber(trade.get("notional"));
                    double available = jsNumber(firstDefined(
                            trade, "available_liquidity_notional", "venue_capacity_notional"));
                    if (!(notional >= 0 && available > 0)) {
                        missing.add(id);
                        continue;
                    }
                    if (notional / available > jsNumber(spec.get("maximum_participation_rate"))) {
                        violations.add(id);
                        continue;
                    }
                }
                if ("VENUE_OUTAGE".equals(name)) {
                    String venue = truthyText(trade.get("venue"),
                            text(trade.path("instrument").get("venue")));
                    JsonNode entryValue = firstDefined(trade, "entry_time", "signal_time", "time");
                    JsonNode exitValue = firstDefined(trade, "exit_time", "close_time");
                    if (exitValue == null) exitValue = entryValue;
                    if (venue.isEmpty() || entryValue == null || exitValue == null) {
                        missing.add(id);
                        continue;
                    }
                    Long entry = stressTimestamp(entryValue);
                    Long exit = stressTimestamp(exitValue);
                    if (entry == null || exit == null) {
                        missing.add(id);
                        continue;
                    }
                    boolean outage = false;
                    for (JsonNode window : rows(spec.get("blackout_windows"))) {
                        String targetVenue = text(window.get("venue"));
                        long start = requiredStressTimestamp(window.get("start_time"));
                        long end = requiredStressTimestamp(window.get("end_time"));
                        if (("*".equals(targetVenue)
                                || targetVenue.equalsIgnoreCase(venue))
                                && entry < end && exit >= start) {
                            outage = true;
                            break;
                        }
                    }
                    if (outage) {
                        affected.add(id);
                        continue;
                    }
                }
                values.add(value);
            }

            if ("VENUE_OUTAGE".equals(name) && affected.isEmpty()) {
                violations.add("NO_TRADE_OVERLAPPED_DECLARED_OUTAGE");
            }
            Double expectancy = values.isEmpty() ? null : jsMean(values);
            List<String> missingRows = missing.stream().sorted().toList();
            List<String> violationRows = violations.stream().sorted().toList();
            List<String> affectedRows = affected.stream().sorted().toList();
            boolean pass = missingRows.isEmpty()
                    && violationRows.isEmpty()
                    && values.size() >= jsNumber(spec.get("minimum_observations"))
                    && expectancy != null
                    && expectancy >= jsNumber(spec.get("minimum_expectancy_r"));
            ObjectNode output = JSON.objectNode().put("name", name);
            output.set("parameters", cloneNode(spec));
            output.put("pass", pass);
            putNullableNumber(output, "expectancy_r", expectancy);
            output.put("observations", values.size());
            output.set("affected_trade_ids", strings(affectedRows));
            output.set("missing_model_inputs", strings(missingRows));
            output.set("violations", strings(violationRows));
            output.put("model_completeness",
                    missingRows.isEmpty() && violationRows.isEmpty() && !rows(trades).isEmpty());
            scenarios.add(output);
        }

        ObjectNode suiteIdentity = JSON.objectNode()
                .put("contract_sha256", text(contract.get("content_sha256")));
        ArrayNode scenarioIdentity = JSON.arrayNode();
        for (JsonNode scenario : scenarios) {
            ObjectNode item = JSON.objectNode().put("name", text(scenario.get("name")));
            item.set("parameters", cloneNode(scenario.get("parameters")));
            scenarioIdentity.add(item);
        }
        suiteIdentity.set("scenarios", scenarioIdentity);
        suiteIdentity.put("derivatives_required", derivativesRequired);
        putNullable(suiteIdentity, "experiment_sha256", experimentSha256);

        ObjectNode result = JSON.objectNode()
                .put("schema", "strategy-stress-result/1")
                .put("suite_sha256", LegacyResearchV1.hash(suiteIdentity));
        putNullable(result, "experiment_sha256", experimentSha256);
        result.put("contract_sha256", text(contract.get("content_sha256")));
        result.put("derivatives_required", derivativesRequired);
        result.set("scenarios", scenarios);
        result.put("pass", rows(scenarios).stream().allMatch(row -> row.path("pass").asBoolean()));
        result.put("model_completeness",
                rows(scenarios).stream().allMatch(row -> row.path("model_completeness").asBoolean()));
        result.put("provenance", "AUTHORITATIVE_RECOMPUTED");
        return result;
    }

    /** Uses the registered Java swing executor. */
    public static ObjectNode evaluateLocalV3(JsonNode options) {
        return evaluateLocalV3(options, (series, candidate, executorOptions) ->
                SwingEngine.evaluateCandidate(series, candidate, executorOptions));
    }

    /**
     * Port of {@code evaluateLocalV3}; the evaluator seam mirrors the JavaScript callback seam and
     * makes deterministic oracle testing possible without weakening production validation.
     */
    public static ObjectNode evaluateLocalV3(JsonNode options, CandidateEvaluator evaluator) {
        object(options, "options");
        Objects.requireNonNull(evaluator, "evaluateCandidateImpl");
        JsonNode experiment = options.get("experiment");
        JsonNode manifest = options.get("manifest");
        JsonNode featureSet = options.get("featureSet");
        JsonNode labelSet = options.get("labelSet");
        JsonNode candidates = options.get("candidates");
        JsonNode featureRows = options.get("featureRows");
        JsonNode parentEvidence = options.get("parentEvidence");

        LegacyResearchV3.validateExperimentV3(
                experiment, experiment.get("acceptance_contract"), null);
        LegacyResearchV3.validateAcceptanceContract(experiment.get("acceptance_contract"));
        String phase = text(experiment.get("evidence_phase"));
        if ("CI_ATTESTED_CONFIRMATION".equals(phase)) {
            throw new IllegalArgumentException("CONFIRMATION_RUNNER_UNAVAILABLE: "
                    + "CI_ATTESTED_CONFIRMATION requires the unavailable public-unseen-data "
                    + "custody runner");
        }

        verifyRetainedHashes(experiment, manifest, featureSet, labelSet, candidates);
        List<String> requiredAssets = requiredAssets(experiment);
        if (requiredAssets.contains("doge")) {
            throw new IllegalArgumentException("DOGE is excluded from the v3 research universe");
        }

        JsonNode parentWfo = null;
        ObjectNode frozenByAsset = null;
        if ("EXPOSED_CONFIRMATION".equals(phase)) {
            parentWfo = LegacyResearchV3.validateExposedParentEvidence(parentEvidence, experiment);
            frozenByAsset = LegacyResearchV3.frozenSelectionByAsset(experiment);
        }

        List<JsonNode> candidateRows = new ArrayList<>(rows(
                candidates != null && candidates.has("candidates")
                        ? candidates.get("candidates") : candidates));
        if (frozenByAsset != null) {
            Set<String> frozenIds = new LinkedHashSet<>();
            frozenByAsset.elements().forEachRemaining(value -> frozenIds.add(text(value)));
            candidateRows.removeIf(candidate -> !frozenIds.contains(candidateId(candidate)));
        }
        if (candidateRows.isEmpty()) {
            throw new IllegalArgumentException(
                    "v3 candidate set is empty or lacks the frozen exposed selection");
        }
        int declaredK = "EXPOSED_CONFIRMATION".equals(phase)
                ? candidateRows.size()
                : candidateCount(candidates, candidateRows.size());
        if (declaredK != candidateRows.size()) {
            throw new IllegalArgumentException("candidate accounting mismatch: declared K="
                    + declaredK + ", effective K=" + candidateRows.size());
        }

        ArrayNode normalized = normalizeFeatureRows(featureRows, requiredAssets);
        Map<String, ArrayNode> byAsset = rowsByAsset(normalized, requiredAssets);
        List<String> missingAssets = requiredAssets.stream()
                .filter(asset -> byAsset.get(asset).isEmpty()).toList();
        if (!missingAssets.isEmpty()) {
            throw new IllegalArgumentException("feature set is missing required crypto assets: "
                    + String.join(", ", missingAssets));
        }
        validateAvailability(byAsset, experiment);

        int seed = experiment.path("chronology").path("seeds").path(0).asInt();
        boolean wfoPhase = "WALK_FORWARD_OOS".equals(phase);
        ArrayNode allTrades = JSON.arrayNode();
        ArrayNode zeroEpisodes = JSON.arrayNode();
        Map<String, ArrayNode> candidateTrades = new LinkedHashMap<>();
        List<String> candidateIds = candidateRows.stream()
                .map(LegacyStrategyResearch::candidateId).toList();

        for (JsonNode candidate : candidateRows) {
            String id = candidateId(candidate);
            if (id.isEmpty()) throw new IllegalArgumentException("candidate_id is required");
            for (String asset : requiredAssets) {
                boolean selected = frozenByAsset == null
                        || !frozenByAsset.has(asset)
                        || id.equals(text(frozenByAsset.get(asset)));
                JsonNode report = wfoPhase || !selected
                        ? JSON.objectNode().set("trades", JSON.arrayNode())
                        : evaluator.evaluate(byAsset.get(asset), candidateDefinition(candidate),
                                executorOptions(declaredK));
                ArrayNode trades = mapTrades(report.path("trades"), id, asset, null);
                trades.forEach(allTrades::add);
                candidateTrades.put(id + "|" + asset, trades);
                if (!wfoPhase) addZeroEpisodes(zeroEpisodes, byAsset.get(asset), trades,
                        experiment, id, asset);
            }
        }

        double priceFraction = finiteOrZero(manifest.path("coverage_summary").get("price_fraction"));
        double derivativesFraction = finiteOrZero(
                manifest.path("coverage_summary").get("derivatives_fraction"));
        ArrayNode internalTrades = JSON.arrayNode();
        allTrades.forEach(internalTrades::add);
        zeroEpisodes.forEach(internalTrades::add);
        ArrayNode metrics = JSON.arrayNode();
        if (!wfoPhase) {
            for (JsonNode candidate : candidateRows) {
                String id = candidateId(candidate);
                for (String asset : requiredAssets) {
                    ArrayNode trades = candidateTrades.get(id + "|" + asset);
                    boolean derivatives = candidateDeclaresDerivative(candidate)
                            || rows(trades).stream().anyMatch(LegacyStrategyResearch::isDerivativeTrade);
                    boolean funding = !derivatives || (!trades.isEmpty()
                            && rows(trades).stream().allMatch(
                                    LegacyStrategyResearch::hasAuthoritativeFundingSettlements));
                    ObjectNode metricOptions = JSON.objectNode()
                            .put("candidateId", id)
                            .put("asset", asset)
                            .put("candidateCount", declaredK)
                            .put("initialEquity", initialEquity(experiment))
                            .put("seed", seed)
                            .put("bootstrapIterations", bootstrapIterations(experiment))
                            .put("fundingProcessed", funding);
                    metricOptions.set("candidateIds", strings(candidateIds));
                    metricOptions.set("allTrades", internalTrades);
                    metricOptions.set("coverage", JSON.objectNode()
                            .put("price_fraction", priceFraction)
                            .put("derivatives_fraction", derivativesFraction));
                    ObjectNode metric = LegacyResearchV3.computeCandidateMetrics(trades, metricOptions);
                    metric.put("derivatives_required", derivatives);
                    metric.put("funding_processed", funding);
                    metric.put("candidate_id", id);
                    metric.put("asset", asset);
                    metric.put("selected", false);
                    metric.put("selection_basis", "EXPOSED_CONFIRMATION".equals(phase)
                            ? "FROZEN_PARENT_SELECTION_DIAGNOSTIC"
                            : "DEVELOPMENT_FULL_SAMPLE_DIAGNOSTIC");
                    metric.set("execution", JSON.objectNode()
                            .put("status", "EVALUATED")
                            .put("adapter", text(experiment.get("executor_sha256"))));
                    metrics.add(metric);
                }
            }
        }

        ObjectNode wfo = null;
        if (wfoPhase) {
            wfo = evaluateWfo(candidateRows, byAsset, requiredAssets, experiment,
                    declaredK, seed, evaluator, metrics);
        }

        ObjectNode selectedByAsset = selectByAsset(
                requiredAssets, metrics, frozenByAsset, wfo);
        ArrayNode selectedTrades = wfo == null
                ? selectTrades(allTrades, selectedByAsset)
                : filterNonZero(wfo.get("oos_trades"));

        ObjectNode aggregate = aggregateMetrics(
                phase, metrics, selectedTrades, wfo, experiment, seed,
                priceFraction, derivativesFraction);
        JsonNode evidenceWfo = wfo != null ? wfo : parentWfo;
        if (parentWfo != null) {
            ObjectNode copy = objectCopy(parentWfo, "parent WFO");
            copy.put("parent_evidence_sha256", text(experiment.get("parent_evidence_sha256")));
            evidenceWfo = copy;
        }

        ArrayNode marks = portfolioMarks(normalized);
        ObjectNode portfolio = simulatePortfolio(selectedTrades, marks, experiment);
        boolean hasWfo = wfo != null;
        boolean derivativesRequired = rows(selectedTrades).stream()
                .anyMatch(LegacyStrategyResearch::isDerivativeTrade)
                || rows(metrics).stream().anyMatch(row -> row.path("derivatives_required").asBoolean()
                && (row.path("selected").asBoolean() || !hasWfo));
        boolean fundingProcessed = !derivativesRequired || (!selectedTrades.isEmpty()
                && rows(selectedTrades).stream().allMatch(
                        LegacyStrategyResearch::hasAuthoritativeFundingSettlements));
        ObjectNode stress = v3Stress(selectedTrades, experiment.get("acceptance_contract"),
                derivativesRequired, text(experiment.get("content_sha256")));
        ObjectNode coverage = JSON.objectNode()
                .put("verified", manifest.path("authoritative").asBoolean()
                        || "DEVELOPMENT".equals(phase))
                .put("price_fraction", priceFraction)
                .put("derivatives_fraction", derivativesFraction)
                .put("derivatives_required", derivativesRequired);

        ObjectNode acceptanceOptions = JSON.objectNode().put("phase", phase);
        putNullable(acceptanceOptions, "wfo", evidenceWfo);
        acceptanceOptions.set("stress", stress);
        acceptanceOptions.set("portfolio", portfolio);
        if (derivativesRequired) acceptanceOptions.put("funding", fundingProcessed);
        else acceptanceOptions.putNull("funding");
        acceptanceOptions.set("coverage", coverage);
        ObjectNode acceptance = LegacyResearchV3.evaluateAcceptance(
                aggregate, experiment.get("acceptance_contract"), acceptanceOptions);
        String acceptanceBasis = wfo != null
                ? "WALK_FORWARD_OOS_AGGREGATE"
                : "EXPOSED_CONFIRMATION".equals(phase)
                ? "FROZEN_PARENT_WFO_SELECTION" : "DEVELOPMENT_FULL_SAMPLE";
        ObjectNode acceptanceResult = acceptance.deepCopy();
        acceptanceResult.put("acceptance_basis", acceptanceBasis);
        ObjectNode decision = JSON.objectNode().put("status", text(acceptance.get("decision")));
        decision.set("reasons", cloneNode(acceptance.get("failures")));
        decision.set("acceptance_result", acceptanceResult);
        for (JsonNode raw : metrics) {
            ObjectNode metric = (ObjectNode) raw;
            if (wfo == null
                    && text(metric.get("candidate_id")).equals(text(aggregate.get("candidate_id")))
                    && Objects.equals(nullableText(metric.get("asset")),
                    nullableText(aggregate.get("asset")))) {
                metric.set("acceptance", acceptance);
            } else metric.putNull("acceptance");
        }

        ObjectNode candidateAccounting = candidateAccounting(
                candidateRows, requiredAssets, candidateTrades, zeroEpisodes,
                normalized, internalTrades, wfo, declaredK, candidateIds);
        ObjectNode decisions = JSON.objectNode();
        ArrayNode perAsset = JSON.arrayNode();
        for (String asset : requiredAssets) {
            ObjectNode row = JSON.objectNode().put("asset", asset);
            putNullable(row, "candidate_id", nullableText(selectedByAsset.get(asset)));
            row.put("status", "SHADOW");
            row.set("reasons", strings(List.of("RESEARCH_EVIDENCE_ONLY")));
            perAsset.add(row);
        }
        decisions.set("per_asset", perAsset);
        decisions.set("portfolio", JSON.objectNode()
                .put("status", text(decision.get("status")))
                .set("reasons", cloneNode(decision.get("reasons"))));

        ObjectNode bundleOptions = JSON.objectNode();
        bundleOptions.set("experiment", cloneNode(experiment));
        bundleOptions.set("metrics", metrics);
        bundleOptions.set("trades", selectedTrades);
        bundleOptions.set("stress", stress);
        bundleOptions.set("portfolio", portfolio);
        putNullable(bundleOptions, "wfo", evidenceWfo);
        bundleOptions.set("decision", decision);
        bundleOptions.set("decisions", decisions);
        bundleOptions.set("candidateAccounting", candidateAccounting);
        bundleOptions.put("acceptanceBasis", acceptanceBasis);
        putNullable(bundleOptions, "parentEvidence", parentEvidence);
        bundleOptions.put("provenance", "AUTHORITATIVE_RECOMPUTED");
        ObjectNode bundle = LegacyResearchV3.makeEvidenceBundle(bundleOptions);

        ObjectNode runOptions = JSON.objectNode();
        runOptions.set("experiment", cloneNode(experiment));
        runOptions.set("evidenceBundle", bundle);
        runOptions.set("decisions", decisions);
        runOptions.put("provenance", "AUTHORITATIVE_RECOMPUTED");
        ObjectNode run = LegacyResearchV3.makeRunV3(runOptions);

        ObjectNode result = JSON.objectNode();
        result.set("bundle", bundle);
        result.set("run", run);
        result.set("metrics", metrics);
        result.set("trades", selectedTrades);
        result.set("selected_trades", selectedTrades.deepCopy());
        result.set("portfolio", portfolio);
        result.set("stress", stress);
        result.set("acceptance", acceptance);
        result.set("coverage", coverage);
        putNullable(result, "wfo", evidenceWfo);
        result.set("selected_by_asset", selectedByAsset);
        result.set("candidate_accounting", candidateAccounting);
        return result;
    }

    private static void verifyRetainedHashes(
            JsonNode experiment,
            JsonNode manifest,
            JsonNode featureSet,
            JsonNode labelSet,
            JsonNode candidates) {
        if (present(manifest, "content_sha256")) {
            ObjectNode copy = objectCopy(manifest, "manifest");
            copy.remove(List.of("content_sha256", "created_at"));
            if (!text(manifest.get("content_sha256")).equals(LegacyResearchV3.hash(copy))) {
                throw new IllegalArgumentException("data manifest retained-hash tampering");
            }
            if (present(experiment, "data_manifest_sha256")
                    && !text(experiment.get("data_manifest_sha256"))
                    .equals(text(manifest.get("content_sha256")))) {
                throw new IllegalArgumentException("experiment/data manifest lineage mismatch");
            }
        }
        verifyOwnHash("feature set", featureSet);
        verifyOwnHash("label set", labelSet);
        verifyOwnHash("candidate set", candidates);
        if (featureSet != null && featureSet.path("labels_allowed").asBoolean()) {
            throw new IllegalArgumentException("feature set permits labels");
        }
        if (labelSet != null && labelSet.path("predictor_eligible").asBoolean()) {
            throw new IllegalArgumentException("label set is predictor-eligible");
        }
        lineage(manifest, featureSet, "data_manifest_sha256",
                "feature set/data manifest lineage mismatch");
        lineage(manifest, labelSet, "data_manifest_sha256",
                "label set/data manifest lineage mismatch");
        lineage(featureSet, experiment, "feature_set_sha256",
                "experiment/feature set lineage mismatch");
        lineage(labelSet, experiment, "label_set_sha256",
                "experiment/label set lineage mismatch");
        lineage(candidates, experiment, "candidate_set_sha256",
                "experiment/candidate set lineage mismatch");
    }

    private static void verifyOwnHash(String name, JsonNode value) {
        if (present(value, "content_sha256")
                && !text(value.get("content_sha256")).equals(LegacyResearchV3.ownHash(value))) {
            throw new IllegalArgumentException(name + " retained-hash tampering");
        }
    }

    private static void lineage(
            JsonNode source, JsonNode target, String targetField, String message) {
        if (present(source, "content_sha256") && present(target, targetField)
                && !text(source.get("content_sha256")).equals(text(target.get(targetField)))) {
            throw new IllegalArgumentException(message);
        }
    }

    private static ArrayNode normalizeFeatureRows(JsonNode values, List<String> assets) {
        List<ObjectNode> normalized = new ArrayList<>();
        for (JsonNode row : rows(values)) {
            JsonNode timeNode = firstDefined(row, "time", "event_time", "timestamp");
            Double time = finiteNumber(timeNode);
            if (time == null) throw new IllegalArgumentException("feature row requires event_time/time");
            for (String forbidden : List.of(
                    "target", "label", "outcome", "forward_return", "future_return")) {
                if (row.has(forbidden)) {
                    throw new IllegalArgumentException(
                            "future-label field entered predictor feature rows");
                }
            }
            ObjectNode copy = objectCopy(row, "feature row");
            copy.put("time", time);
            Double available = finiteNumber(firstDefined(row, "available_at", "availability_time"));
            copy.put("available_at", available == null ? time : available);
            copy.put("asset", lower(row.get("asset")));
            copy.put("timeframe", truthyText(row.get("timeframe"), "4h"));
            if (assets.contains(text(copy.get("asset")))) normalized.add(copy);
        }
        normalized.sort(Comparator.comparingDouble((ObjectNode row) -> row.path("time").asDouble())
                .thenComparing(row -> text(row.get("asset"))));
        return arrayOf(normalized);
    }

    private static Map<String, ArrayNode> rowsByAsset(ArrayNode rows, List<String> assets) {
        Map<String, ArrayNode> result = new LinkedHashMap<>();
        assets.forEach(asset -> result.put(asset, JSON.arrayNode()));
        for (JsonNode row : rows) {
            ArrayNode target = result.get(lower(row.get("asset")));
            if (target != null) target.add(cloneNode(row));
        }
        return result;
    }

    private static void validateAvailability(Map<String, ArrayNode> values, JsonNode experiment) {
        long declaredBar = experiment.path("chronology").path("bar_duration_ms").asLong();
        for (Map.Entry<String, ArrayNode> entry : values.entrySet()) {
            ArrayNode series = entry.getValue();
            for (int index = 0; index < series.size(); index++) {
                JsonNode row = series.get(index);
                long inferred = TIMEFRAME_MILLIS.getOrDefault(
                        lower(row.get("timeframe")), 0L);
                double boundary = index + 1 < series.size()
                        ? series.get(index + 1).path("time").asDouble()
                        : row.path("time").asDouble() + (declaredBar != 0 ? declaredBar : inferred);
                if (!(row.path("available_at").asDouble() <= boundary)) {
                    throw new IllegalArgumentException("feature availability leak for "
                            + entry.getKey() + " at " + numberText(row.get("time"))
                            + ": available_at is after next-entry boundary");
                }
            }
        }
    }

    private static ObjectNode evaluateWfo(
            List<JsonNode> candidates,
            Map<String, ArrayNode> byAsset,
            List<String> requiredAssets,
            JsonNode experiment,
            int declaredK,
            int seed,
            CandidateEvaluator evaluator,
            ArrayNode metrics) {
        ArrayNode folds = JSON.arrayNode();
        int counter = 0;
        for (JsonNode source : rows(experiment.path("chronology").get("folds"))) {
            ObjectNode fold = objectCopy(source, "fold");
            if (!present(fold, "fold_id")) fold.put("fold_id", "fold-" + (++counter));
            else counter++;
            copyFoldBound(fold, "train_start", "train", "start");
            copyFoldBound(fold, "train_end", "train", "end");
            copyFoldBound(fold, "test_start", "test", "start");
            copyFoldBound(fold, "test_end", "test", "end");
            folds.add(fold);
        }
        LegacyResearchV3.WfoEvaluator train = (candidate, fold, index) -> evaluateWindow(
                candidate, fold, byAsset, requiredAssets, declaredK, seed, evaluator,
                number(firstDefined(fold, "train_start")), number(firstDefined(fold, "train_end")));
        LegacyResearchV3.WfoEvaluator test = (candidate, fold, index) -> evaluateWindow(
                candidate, fold, byAsset, requiredAssets, declaredK, seed, evaluator,
                number(firstDefined(fold, "test_start")), number(firstDefined(fold, "test_end")));
        ObjectNode wfoOptions = JSON.objectNode()
                .put("barDurationMs", experiment.path("chronology").path("bar_duration_ms").asLong())
                .put("purgeBars", experiment.path("chronology").path("purge_bars").asInt(0))
                .put("embargoBars", experiment.path("chronology").path("embargo_bars").asInt(0))
                .put("experimentSha256", text(experiment.get("content_sha256")));
        wfoOptions.set("trainingSelectionPolicy",
                cloneNode(experiment.get("training_selection_policy")));
        wfoOptions.set("requiredAssets", strings(requiredAssets));
        ObjectNode wfo = LegacyResearchV3.walkForwardV3(
                arrayOf(candidates), folds, train, test,
                experiment.get("acceptance_contract"), wfoOptions);
        for (JsonNode fold : rows(wfo.get("folds"))) {
            for (JsonNode candidate : rows(fold.path("train").get("candidates"))) {
                for (JsonNode raw : rows(candidate.get("candidate_asset_metrics"))) {
                    ObjectNode metric = objectCopy(raw, "train metric");
                    metric.put("phase", "TRAIN");
                    metric.put("fold_id", text(fold.get("fold_id")));
                    metric.set("window", cloneNode(fold.get("train_window")));
                    metric.put("selected", false);
                    metric.put("selection_basis", "WFO_TRAIN_ONLY_POLICY");
                    metric.set("execution", JSON.objectNode()
                            .put("status", "TRAIN_EVALUATED")
                            .put("adapter", text(experiment.get("executor_sha256"))));
                    metrics.add(metric);
                }
            }
            for (JsonNode raw : rows(fold.path("test").get("candidate_asset_metrics"))) {
                ObjectNode metric = objectCopy(raw, "test metric");
                metric.put("phase", "OOS");
                metric.put("fold_id", text(fold.get("fold_id")));
                metric.set("window", cloneNode(fold.get("test_window")));
                metric.put("selected", true);
                metric.put("selection_basis", "WFO_TRAIN_ONLY_POLICY");
                metric.set("execution", JSON.objectNode()
                        .put("status", "OOS_WINNER_ONLY")
                        .put("adapter", text(experiment.get("executor_sha256"))));
                metrics.add(metric);
            }
        }
        return wfo;
    }

    private static ObjectNode evaluateWindow(
            JsonNode candidate,
            JsonNode fold,
            Map<String, ArrayNode> byAsset,
            List<String> requiredAssets,
            int declaredK,
            int seed,
            CandidateEvaluator evaluator,
            double start,
            double end) {
        String id = candidateId(candidate);
        ArrayNode trades = JSON.arrayNode();
        ArrayNode byAssetMetrics = JSON.arrayNode();
        for (String asset : requiredAssets) {
            ArrayNode window = JSON.arrayNode();
            for (JsonNode row : byAsset.get(asset)) {
                double time = row.path("time").asDouble();
                if (time >= start && time < end) window.add(cloneNode(row));
            }
            JsonNode report = evaluator.evaluate(window, candidateDefinition(candidate),
                    executorOptions(declaredK));
            ArrayNode scoped = mapTrades(report.path("trades"), id, asset,
                    text(fold.get("fold_id")));
            scoped.forEach(trades::add);
            ArrayNode accounting = scoped.isEmpty()
                    ? JSON.arrayNode().add(JSON.objectNode()
                    .put("candidate_id", id)
                    .put("episode_id", text(fold.get("fold_id")) + "|" + id + "|" + asset + "|ZERO")
                    .put("net_r", 0).put("exit_time", end))
                    : scoped;
            ObjectNode metricOptions = JSON.objectNode()
                    .put("candidateId", id).put("asset", asset)
                    .put("candidateCount", 1).put("seed", seed)
                    .put("bootstrapIterations", 256);
            metricOptions.set("candidateIds", strings(List.of(id)));
            metricOptions.set("allTrades", accounting);
            ObjectNode metric = LegacyResearchV3.computeCandidateMetrics(scoped, metricOptions);
            metric.put("candidate_id", id).put("asset", asset);
            byAssetMetrics.add(metric);
        }
        ArrayNode accounting = trades.isEmpty()
                ? JSON.arrayNode().add(JSON.objectNode()
                .put("candidate_id", id)
                .put("episode_id", text(fold.get("fold_id")) + "|" + id + "|ZERO")
                .put("net_r", 0).put("exit_time", end))
                : trades;
        ObjectNode metricOptions = JSON.objectNode()
                .put("candidateId", id).put("candidateCount", 1)
                .put("seed", seed).put("bootstrapIterations", 256);
        metricOptions.set("candidateIds", strings(List.of(id)));
        metricOptions.set("allTrades", accounting);
        ObjectNode result = JSON.objectNode().put("candidate_id", id);
        result.set("trades", trades);
        result.set("metrics", LegacyResearchV3.computeCandidateMetrics(trades, metricOptions));
        result.set("by_asset", byAssetMetrics);
        return result;
    }

    private static ObjectNode selectByAsset(
            List<String> assets, ArrayNode metrics, ObjectNode frozen, ObjectNode wfo) {
        ObjectNode selected = JSON.objectNode();
        if (wfo != null) {
            wfo.path("final_selection_by_asset").fields()
                    .forEachRemaining(entry -> selected.set(entry.getKey(), cloneNode(entry.getValue())));
            return selected;
        }
        if (frozen != null) {
            frozen.fields().forEachRemaining(
                    entry -> selected.set(entry.getKey(), cloneNode(entry.getValue())));
            return selected;
        }
        for (String asset : assets) {
            List<JsonNode> scoped = rows(metrics).stream()
                    .filter(row -> asset.equals(text(row.get("asset"))))
                    .sorted(Comparator
                            .comparingDouble((JsonNode row) -> -sortableExpectancy(row))
                            .thenComparing(row -> text(row.get("candidate_id"))))
                    .toList();
            if (!scoped.isEmpty()) {
                ObjectNode winner = (ObjectNode) scoped.get(0);
                winner.put("selected", true);
                winner.put("selection_basis", "DEVELOPMENT_FULL_SAMPLE");
                selected.put(asset, text(winner.get("candidate_id")));
            }
        }
        return selected;
    }

    private static ObjectNode aggregateMetrics(
            String phase,
            ArrayNode metrics,
            ArrayNode selectedTrades,
            ObjectNode wfo,
            JsonNode experiment,
            int seed,
            double priceFraction,
            double derivativesFraction) {
        if (wfo != null) return objectCopy(wfo.get("aggregate_oos_metrics"), "aggregate metrics");
        if ("EXPOSED_CONFIRMATION".equals(phase)) {
            ArrayNode aggregateTrades = JSON.arrayNode();
            for (JsonNode raw : selectedTrades) {
                ObjectNode trade = objectCopy(raw, "trade");
                trade.put("candidate_id", "__frozen_selection__");
                aggregateTrades.add(trade);
            }
            ObjectNode options = JSON.objectNode()
                    .put("candidateId", "__frozen_selection__")
                    .put("candidateCount", 1)
                    .put("initialEquity", initialEquity(experiment))
                    .put("seed", seed)
                    .put("bootstrapIterations", bootstrapIterations(experiment))
                    .put("fundingProcessed", false);
            options.set("candidateIds", strings(List.of("__frozen_selection__")));
            options.set("allTrades", aggregateTrades);
            options.set("coverage", JSON.objectNode()
                    .put("price_fraction", priceFraction)
                    .put("derivatives_fraction", derivativesFraction));
            ObjectNode result = LegacyResearchV3.computeCandidateMetrics(aggregateTrades, options);
            result.put("candidate_id", "__frozen_selection__");
            result.putNull("asset");
            result.put("selection_basis", "FROZEN_PARENT_WFO_SELECTION");
            return result;
        }
        return rows(metrics).stream()
                .sorted(Comparator
                        .comparingDouble((JsonNode row) -> -sortableExpectancy(row))
                        .thenComparing(row -> text(row.get("candidate_id"))))
                .findFirst().map(row -> objectCopy(row, "metric"))
                .orElseGet(JSON::objectNode);
    }

    private static ObjectNode candidateAccounting(
            List<JsonNode> candidates,
            List<String> assets,
            Map<String, ArrayNode> candidateTrades,
            ArrayNode zeroEpisodes,
            ArrayNode normalized,
            ArrayNode internalTrades,
            ObjectNode wfo,
            int declaredK,
            List<String> candidateIds) {
        JsonNode accountingRows;
        if (wfo != null) accountingRows = wfo.get("candidate_accounting");
        else {
            ArrayNode rows = JSON.arrayNode();
            for (JsonNode candidate : candidates) {
                String id = candidateId(candidate);
                for (String asset : assets) {
                    ArrayNode actual = candidateTrades.get(id + "|" + asset);
                    ArrayNode zeros = JSON.arrayNode();
                    for (JsonNode row : zeroEpisodes) {
                        if (id.equals(text(row.get("candidate_id")))
                                && asset.equals(text(row.get("asset")))) zeros.add(row);
                    }
                    ArrayNode digestRows = JSON.arrayNode();
                    for (JsonNode row : actual) digestRows.add(pick(
                            row, "episode_id", "net_r", "zero_episode"));
                    for (JsonNode row : zeros) digestRows.add(pick(
                            row, "episode_id", "net_r", "zero_episode"));
                    rows.add(JSON.objectNode()
                            .put("candidate_id", id)
                            .put("asset", asset)
                            .put("phase", "DEVELOPMENT")
                            .put("actual_trade_count", actual.size())
                            .put("zero_trade", actual.isEmpty())
                            .put("zero_episode_count", zeros.size())
                            .put("outcome_digest_sha256", LegacyResearchV3.hash(digestRows)));
                }
            }
            accountingRows = rows;
        }
        Set<String> episodeIds = new LinkedHashSet<>();
        normalized.forEach(row -> episodeIds.add(text(row.get("asset")) + "|" + numberText(row.get("time"))));
        ObjectNode result = JSON.objectNode()
                .put("schema", "strategy-candidate-accounting/1")
                .put("declared_k", declaredK)
                .put("effective_k", candidates.size());
        result.set("candidate_ids", strings(candidateIds));
        result.put("market_episode_ids_sha256",
                LegacyResearchV3.hash(strings(episodeIds.stream().sorted().toList())));
        result.set("per_candidate_asset", cloneNode(accountingRows));
        result.put("zero_episode_digest_sha256", wfo != null
                ? LegacyResearchV3.hash(filterZeroAccounting(accountingRows))
                : LegacyResearchV3.hash(zeroEpisodes));
        result.put("internal_trade_digest_sha256", wfo != null
                ? text(wfo.get("candidate_accounting_sha256"))
                : LegacyResearchV3.hash(internalTrades));
        putNullable(result, "wfo_accounting_sha256",
                wfo == null ? null : text(wfo.get("candidate_accounting_sha256")));
        result.put("storage_policy", "zero episodes and non-selected trades are internal; "
                + "only compact selected/OOS trades are persisted");
        return LegacyResearchV3.withHash(result);
    }

    private static ObjectNode simulatePortfolio(
            ArrayNode trades, ArrayNode marks, JsonNode experiment) {
        ArrayNode signals = JSON.arrayNode();
        for (JsonNode raw : trades) {
            ObjectNode signal = objectCopy(raw, "trade");
            signal.put("signal_id", text(raw.get("trade_id")));
            signal.set("instrument", cloneNode(raw.get("instrument")));
            signals.add(signal);
        }
        ObjectNode policy = experiment.path("portfolio_policy").isObject()
                ? objectCopy(experiment.get("portfolio_policy"), "portfolio policy")
                : JSON.objectNode();
        policy.put("authoritative", true);
        policy.put("advanced_risk", true);
        policy.put("require_authoritative_funding_identity", true);
        policy.put("initial_equity", initialEquity(experiment));
        if (!policy.has("max_mark_gap_ms")
                && experiment.path("chronology").has("bar_duration_ms")) {
            policy.set("max_mark_gap_ms",
                    cloneNode(experiment.path("chronology").get("bar_duration_ms")));
        }
        policy.set("marks", marks);
        policy.set("acceptance", experiment.path("portfolio_policy").path("acceptance").isObject()
                ? cloneNode(experiment.path("portfolio_policy").get("acceptance"))
                : JSON.objectNode());
        try {
            Class<?> type = Class.forName(
                    "com.tradinganalytics.research.v5.StrategyPortfolioV5");
            Method method = type.getMethod("simulateCryptoPortfolio", JsonNode.class, JsonNode.class);
            return (ObjectNode) method.invoke(null, signals, policy);
        } catch (ClassNotFoundException | NoSuchMethodException error) {
            return portfolioFailure("Java strategy portfolio simulator is unavailable");
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            return portfolioFailure(cause.getMessage());
        } catch (IllegalAccessException | ClassCastException error) {
            return portfolioFailure(error.getMessage());
        }
    }

    private static ObjectNode portfolioFailure(String message) {
        ObjectNode result = JSON.objectNode().put("pass", false);
        result.set("failures", strings(List.of("PORTFOLIO_RECOMPUTATION_FAILED")));
        result.put("rejection_reason", message == null ? "portfolio recomputation failed" : message);
        result.put("activation", "RESEARCH_ONLY");
        return result;
    }

    private static void addZeroEpisodes(
            ArrayNode output,
            ArrayNode series,
            ArrayNode trades,
            JsonNode experiment,
            String candidate,
            String asset) {
        Set<String> observed = new LinkedHashSet<>();
        trades.forEach(trade -> observed.add(text(trade.get("episode_id"))));
        long bar = experiment.path("chronology").path("bar_duration_ms").asLong(1);
        for (int index = 0; index < series.size(); index++) {
            JsonNode source = series.get(index);
            String episode = asset + "|" + numberText(source.get("time"));
            if (observed.contains(episode)) continue;
            ObjectNode row = JSON.objectNode()
                    .put("candidate_id", candidate)
                    .put("asset", asset)
                    .put("trade_id", candidate + "|" + episode + "|ZERO")
                    .put("episode_id", episode)
                    .put("entry_time", source.path("time").asDouble())
                    .put("exit_time", source.path("time").asDouble() + bar)
                    .put("net_r", 0).put("net_pnl", 0)
                    .put("zero_episode", true)
                    .put("venue", "public")
                    .put("market_episode_index", index);
            row.set("instrument", defaultInstrument(asset));
            output.add(row);
        }
    }

    private static ArrayNode mapTrades(
            JsonNode values, String candidate, String asset, String foldId) {
        ArrayNode output = JSON.arrayNode();
        int index = 0;
        for (JsonNode raw : rows(values)) {
            ObjectNode trade = objectCopy(raw, "trade");
            trade.put("candidate_id", candidate);
            trade.put("asset", asset);
            String id = foldId == null
                    ? candidate + "|" + asset + "|" + index
                    : candidate + "|" + asset + "|" + foldId + "|" + index;
            trade.put("trade_id", truthyText(raw.get("trade_id"), id));
            JsonNode episodeSource = firstDefined(raw, "signal_time", "entry_time");
            trade.put("episode_id", asset + "|"
                    + (episodeSource == null ? index : numberText(episodeSource)));
            trade.put("venue", truthyText(raw.get("venue"), "public"));
            if (!present(raw, "instrument")) trade.set("instrument", defaultInstrument(asset));
            output.add(trade);
            index++;
        }
        return output;
    }

    private static ObjectNode candidateDefinition(JsonNode candidate) {
        JsonNode source = candidate.path("definition").isObject()
                ? candidate.get("definition")
                : candidate.path("candidate").isObject()
                ? candidate.get("candidate") : candidate;
        ObjectNode result = objectCopy(source, "candidate definition");
        result.put("id", candidateId(candidate));
        return result;
    }

    private static String candidateId(JsonNode candidate) {
        return truthyText(candidate == null ? null : candidate.get("candidate_id"),
                truthyText(candidate == null ? null : candidate.get("id"),
                        text(candidate == null ? null : candidate.path("definition").get("id"))));
    }

    private static ObjectNode executorOptions(int declaredK) {
        return JSON.objectNode()
                .put("candidate_count", declaredK)
                .put("bootstrap_rounds", 0)
                .put("same_bar_collision", "stop-first");
    }

    private static ObjectNode defaultInstrument(String asset) {
        return JSON.objectNode()
                .put("asset", asset)
                .put("asset_class", "crypto")
                .put("instrument_type", "spot")
                .put("venue", "public")
                .put("symbol", asset.toUpperCase(Locale.ROOT) + "USDT");
    }

    private static ArrayNode portfolioMarks(ArrayNode features) {
        ArrayNode marks = JSON.arrayNode();
        for (JsonNode row : features) {
            Double close = finiteNumber(row.get("close"));
            if (close == null) continue;
            String asset = text(row.get("asset"));
            marks.add(JSON.objectNode()
                    .put("asset", asset)
                    .put("time", row.path("time").asDouble())
                    .put("price", close)
                    .put("venue", truthyText(row.get("venue"), "public"))
                    .put("symbol", truthyText(row.get("symbol"),
                            asset.toUpperCase(Locale.ROOT) + "USDT")));
        }
        return marks;
    }

    private static boolean candidateDeclaresDerivative(JsonNode candidate) {
        JsonNode definition = candidate.path("definition").isObject()
                ? candidate.get("definition")
                : candidate.path("candidate").isObject() ? candidate.get("candidate") : candidate;
        String type = lower(firstDefined(
                definition, "instrument.instrument_type", "instrument_type",
                "instrument.type", "instrument_class"));
        return type.contains("perp") || type.contains("future")
                || type.contains("derivative") || type.contains("margin");
    }

    private static boolean isDerivativeTrade(JsonNode trade) {
        String type = lower(firstDefined(
                trade, "instrument.instrument_type", "instrument_type",
                "instrument.type", "instrument_class"));
        return !type.isEmpty() && !"spot".equals(type) && !"cash".equals(type);
    }

    private static boolean hasAuthoritativeFundingSettlements(JsonNode trade) {
        JsonNode settlements = trade.get("funding_settlements");
        if (settlements == null || !settlements.isArray()) return false;
        for (JsonNode settlement : settlements) {
            if (finiteNumber(firstDefined(settlement, "amount", "pnl")) == null
                    || !bool(settlement.get("event_id"))
                    || !bool(settlement.get("source"))
                    || !(bool(settlement.get("venue"))
                    || bool(trade.path("instrument").get("venue")))
                    || !(bool(settlement.get("instrument"))
                    || bool(trade.path("instrument").get("symbol"))
                    || bool(trade.path("instrument").get("instrument_id")))) return false;
        }
        return true;
    }

    private static List<String> requiredAssets(JsonNode experiment) {
        List<String> assets = new ArrayList<>();
        for (JsonNode item : rows(experiment.get("required_assets"))) {
            String asset = lower(item.isTextual() ? item : item.get("asset"));
            assets.add(asset);
        }
        return assets;
    }

    private static int candidateCount(JsonNode candidates, int fallback) {
        JsonNode value = firstDefined(candidates, "effective_k", "declared_k");
        return value == null ? fallback : (int) jsNumber(value);
    }

    private static int bootstrapIterations(JsonNode experiment) {
        return experiment.path("chronology").path("bootstrap_iterations").asInt(512);
    }

    private static double initialEquity(JsonNode experiment) {
        return experiment.path("portfolio_policy").path("initial_equity").asDouble(100_000);
    }

    private static ArrayNode selectTrades(ArrayNode trades, ObjectNode selected) {
        ArrayNode output = JSON.arrayNode();
        for (JsonNode trade : trades) {
            String asset = lower(trade.get("asset"));
            if (!trade.path("zero_episode").asBoolean(false)
                    && text(selected.get(asset)).equals(text(trade.get("candidate_id")))) {
                output.add(cloneNode(trade));
            }
        }
        return output;
    }

    private static ArrayNode filterNonZero(JsonNode values) {
        ArrayNode output = JSON.arrayNode();
        for (JsonNode row : rows(values)) {
            if (!row.path("zero_episode").asBoolean(false)) output.add(cloneNode(row));
        }
        return output;
    }

    private static ArrayNode filterZeroAccounting(JsonNode values) {
        ArrayNode output = JSON.arrayNode();
        for (JsonNode row : rows(values)) {
            if (row.path("zero_trade").asBoolean(false)) output.add(cloneNode(row));
        }
        return output;
    }

    private static void copyFoldBound(
            ObjectNode fold, String field, String object, String nested) {
        if (!fold.has(field) && fold.path(object).has(nested)) {
            fold.set(field, cloneNode(fold.path(object).get(nested)));
        }
    }

    private static double number(JsonNode value) {
        Double result = finiteNumber(value);
        if (result == null) throw new IllegalArgumentException("WFO fold timestamp is invalid");
        return result;
    }

    private static double sortableExpectancy(JsonNode row) {
        Double value = finiteNumber(row.get("expectancy_r"));
        return value == null ? Double.NEGATIVE_INFINITY : value;
    }

    private static double finiteOrZero(JsonNode value) {
        Double number = finiteNumber(value);
        return number == null ? 0 : number;
    }

    private static Double debitInR(JsonNode direct, JsonNode accountCurrency, double risk) {
        Double directValue = finiteNumber(direct);
        if (directValue != null) return directValue;
        Double accountValue = finiteNumber(accountCurrency);
        return accountValue != null && risk > 0 ? accountValue / risk : null;
    }

    private static Double finiteNumber(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        double number = jsNumber(value);
        return Double.isFinite(number) ? number : null;
    }

    private static Long stressTimestamp(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        if (value.isNumber()) {
            double number = value.doubleValue();
            return Double.isFinite(number) ? (long) number : null;
        }
        try {
            return Instant.parse(text(value)).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            try {
                double number = Double.parseDouble(text(value));
                return Double.isFinite(number) ? (long) number : null;
            } catch (NumberFormatException invalid) {
                return null;
            }
        }
    }

    private static long requiredStressTimestamp(JsonNode value) {
        Long result = stressTimestamp(value);
        if (result == null) {
            throw new IllegalArgumentException("stress timestamp is invalid: " + text(value));
        }
        return result;
    }

    private static JsonNode firstDefined(JsonNode value, String... paths) {
        if (value == null) return null;
        for (String path : paths) {
            JsonNode current = value;
            for (String component : path.split("\\.")) {
                if (current == null || !current.isObject() || !current.has(component)) {
                    current = null;
                    break;
                }
                current = current.get(component);
            }
            if (current != null) return current;
        }
        return null;
    }

    private static boolean present(JsonNode value, String field) {
        return value != null && value.isObject() && value.has(field)
                && value.get(field) != null && !value.get(field).isNull();
    }

    private static String truthyText(JsonNode value, String fallback) {
        return bool(value) ? text(value) : fallback;
    }

    private static String numberText(JsonNode value) {
        if (value == null || value.isNull()) return "";
        if (value.isIntegralNumber()) return Long.toString(value.longValue());
        if (value.isFloatingPointNumber()) {
            double number = value.doubleValue();
            if (number == Math.rint(number)) return Long.toString((long) number);
        }
        return text(value);
    }

    private static void putNullable(ObjectNode target, String field, String value) {
        if (value == null) target.putNull(field);
        else target.put(field, value);
    }

    private static void putNullable(ObjectNode target, String field, JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) target.putNull(field);
        else target.set(field, cloneNode(value));
    }

    private static void putNullableNumber(ObjectNode target, String field, Double value) {
        if (value == null) target.putNull(field);
        else target.put(field, value);
    }
}

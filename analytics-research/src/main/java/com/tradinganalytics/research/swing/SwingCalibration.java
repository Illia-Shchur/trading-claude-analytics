package com.tradinganalytics.research.swing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.swing.SwingScore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic calculation layer for {@code tools/swing-calibrate.mjs}. */
public final class SwingCalibration {
    public static final String SCHEMA = "swing-calibration/1";
    public static final String MODEL = "swing-score/1";
    public static final long BAR_MS = 4L * 60 * 60 * 1000;

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter JS_ISO = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final List<String> LEG_NAMES = List.of("flow", "technical", "macro", "sentiment", "valuation", "structure");

    private SwingCalibration() {}

    public record Options(double years, double costPct, double slippagePct, double minHoldoutSignals,
            double minCoverageRatio, double minRegimes, double minTrainSignals, double trainPrecisionMin) {
        public static Options defaults() { return new Options(3, 0.20, 0.10, 5, 0.80, 3, 3, 0.40); }
    }

    public static ArrayNode defaultCandidates() {
        ArrayNode output = JSON.arrayNode();
        for (String phase : List.of("1A", "1B", "2", "3")) for (int bars : List.of(1, 2)) {
            output.add(JSON.objectNode().put("framework", "fallen_knives").put("direction", "long")
                    .put("phase", phase).put("trigger_window_bars", bars));
        }
        for (String channel : List.of("A", "B")) for (String phase : jsObjectKeys(SwingScore.phaseThresholds("flying_rocket", channel).keySet()))
            for (int bars : List.of(1, 2)) output.add(JSON.objectNode().put("framework", "flying_rocket")
                    .put("channel", channel).put("direction", "short").put("phase", phase).put("trigger_window_bars", bars));
        return output;
    }

    public static ObjectNode calibrate(ArrayNode suppliedDatasets, JsonNode inputContract, ArrayNode sharedCandidates,
            Options options, Clock clock) {
        Options config = options == null ? Options.defaults() : options;
        ArrayNode datasets = expandDatasets(suppliedDatasets);
        Set<String> requiredPolicy = new LinkedHashSet<>(List.of("btc:fallen_knives", "btc:flying_rocket:A", "btc:flying_rocket:B",
                "eth:fallen_knives", "eth:flying_rocket:A", "eth:flying_rocket:B"));
        JsonNode suppliedPolicy = inputContract == null ? null : inputContract.get("activation_policy");
        Set<String> suppliedSeries = new LinkedHashSet<>();
        SwingCrossValidator.array(suppliedPolicy == null ? null : suppliedPolicy.get("required_series")).forEach(node -> suppliedSeries.add(node.asText()));
        boolean contractAccepted = inputContract != null && inputContract.path("point_in_time_safe").asBoolean(false)
                && inputContract.path("proxy_contract").path("accepted").asBoolean(false)
                && suppliedPolicy != null && suppliedPolicy.path("point_in_time_safe_required").asBoolean(false)
                && suppliedPolicy.path("proxy_inputs_accepted").asBoolean(false) && suppliedSeries.containsAll(requiredPolicy);
        ObjectNode activationPolicy;
        if (contractAccepted) {
            activationPolicy = ((ObjectNode) suppliedPolicy).deepCopy();
            ArrayNode required = JSON.arrayNode(); requiredPolicy.forEach(required::add); activationPolicy.set("required_series", required);
        } else {
            activationPolicy = JSON.objectNode().put("point_in_time_safe_required", true).put("proxy_inputs_accepted", false);
            ArrayNode required = JSON.arrayNode(); requiredPolicy.forEach(required::add); activationPolicy.set("required_series", required);
            activationPolicy.put("note", "Live ETF/on-chain/reserve/stablecoin inputs are not reproduced by this historical proxy contract.");
        }

        ObjectNode output = JSON.objectNode().put("schema", SCHEMA).put("model", MODEL)
                .put("generated_at", JS_ISO.format(clock.instant()));
        putNumber(output, "years", config.years());
        output.put("label", ">=1.5x 20-day ATR favorable move before 1x ATR adverse move within 30 days")
                .put("early_capture_window", "first 25% of the 30-day (180 completed 4h bars) label horizon");
        output.set("split", JSON.objectNode().put("development_months", 18).put("fold_months", 12)
                .put("untouched_holdout_months", 6).put("fold_width_months", 3));
        ObjectNode criteria = JSON.objectNode();
        putNumber(criteria, "min_holdout_signals", config.minHoldoutSignals());
        putNumber(criteria, "min_train_signals", config.minTrainSignals());
        putNumber(criteria, "train_precision_min", config.trainPrecisionMin());
        putNumber(criteria, "min_coverage_ratio", config.minCoverageRatio());
        putNumber(criteria, "min_regimes", config.minRegimes());
        criteria.put("precision_min", 0.45).put("expectancy_r_min", 0).put("early_capture_min", 0).put("anti_overlap_bars", 180);
        output.set("criteria", criteria);
        ObjectNode costs = JSON.objectNode(); putNumber(costs, "fee_pct_one_way", config.costPct()); putNumber(costs, "slippage_pct_one_way", config.slippagePct());
        costs.put("accounting", "round-trip fee + slippage debited in R using stop distance"); output.set("costs", costs);
        output.set("activation_policy", activationPolicy);
        output.put("point_in_time_safe", contractAccepted);
        if (contractAccepted) output.set("proxy_contract", inputContract.get("proxy_contract").deepCopy());
        else {
            ObjectNode proxy = JSON.objectNode().put("status", "UNACCEPTED").put("accepted", false);
            ArrayNode fields = JSON.arrayNode(); List.of("macro", "sentiment", "valuation", "structure").forEach(fields::add); proxy.set("fields", fields);
            proxy.put("note", "Proxy families are disclosed but cannot activate this model without explicit policy acceptance."); output.set("proxy_contract", proxy);
        }
        output.put("activation", "SHADOW");
        ObjectNode modelActivation = JSON.objectNode().put("status", "SHADOW");
        modelActivation.set("artifact", NullNode.instance); modelActivation.set("sha256", NullNode.instance); modelActivation.set("activated_at", NullNode.instance);
        output.set("model_activation", modelActivation);
        output.set("candidate_space", sharedCandidates.deepCopy());
        ArrayNode resultDatasets = JSON.arrayNode(); output.set("datasets", resultDatasets);

        for (JsonNode dataset : datasets) {
            ArrayNode labels = dataset.has("labels") && !dataset.get("labels").isNull()
                    ? SwingCrossValidator.array(dataset.get("labels")).deepCopy() : labelRows(SwingCrossValidator.array(dataset.get("bars")));
            String framework = truthyText(dataset.get("framework"));
            if (framework == null) framework = "flying_rocket".equals(text(dataset.get("asset"))) ? "flying_rocket" : "fallen_knives";
            String channel = truthyText(dataset.get("channel"));
            if (channel == null && "flying_rocket".equals(framework)) channel = "A";
            int direction = "fallen_knives".equals(framework) ? 1 : -1;
            FeatureRows features = featureRows(dataset, labels, direction);
            JsonNode requestedCandidates = dataset.has("candidates") && !dataset.get("candidates").isNull() ? dataset.get("candidates") : sharedCandidates;
            ArrayNode candidates = validCandidates(requestedCandidates, framework, channel);
            ObjectNode wf = walkForward(features.rows(), candidates, framework, channel, config);
            ArrayNode holdoutReports = wf.path("holdout").path("reports").isArray() ? (ArrayNode) wf.path("holdout").path("reports") : JSON.arrayNode();
            String side = "fallen_knives".equals(framework) ? "long" : "short";
            JsonNode holdoutSide = holdoutReports.isEmpty() ? null : holdoutReports.get(0).get(side);
            int regimeCount = holdoutReports.isEmpty() ? 0 : holdoutReports.get(0).path("regime_coverage").path("count").asInt(0);
            double coverageRatio = (double) features.rows().size() / Math.max(1, labels.size());
            boolean sidePass = holdoutSide != null
                    && number(holdoutSide.get("signals")) >= config.minHoldoutSignals()
                    && nullableNumber(holdoutSide.get("precision"), Double.NEGATIVE_INFINITY) >= 0.45
                    && nullableNumber(holdoutSide.get("expectancy_r"), Double.NEGATIVE_INFINITY) > 0
                    && nullableNumber(holdoutSide.get("early_capture"), Double.NEGATIVE_INFINITY) > 0
                    && regimeCount >= config.minRegimes() && coverageRatio >= config.minCoverageRatio();
            boolean pass = !candidates.isEmpty() && sidePass && output.path("point_in_time_safe").asBoolean(false)
                    && output.path("proxy_contract").path("accepted").asBoolean(false);
            ObjectNode result = JSON.objectNode();
            result.set("asset", copyOrNull(dataset.get("asset")));
            result.set("symbol", truthy(dataset.get("symbol")) ? dataset.get("symbol").deepCopy() : NullNode.instance);
            double barsFallback = SwingCrossValidator.array(dataset.get("bars")).size();
            double bars = jsOr(dataset.path("coverage_meta").get("bars"), barsFallback); putNumber(result, "bars", bars);
            result.put("labels", labels.size());
            String coverage = truthyText(dataset.get("coverage"));
            result.put("coverage", coverage == null ? (features.complete() ? "COMPLETE" : "PARTIAL") : coverage);
            result.set("coverage_meta", dataset.has("coverage_meta") ? copyOrNull(dataset.get("coverage_meta")) : NullNode.instance);
            result.set("provenance", dataset.has("provenance") ? copyOrNull(dataset.get("provenance")) : NullNode.instance);
            String featureCoverage = features.complete() ? "COMPLETE"
                    : (features.rows().size() > 0 || !"HISTORICAL_PROXY".equals(text(dataset.get("coverage")))) ? "PARTIAL" : "HISTORICAL_PROXY";
            result.put("feature_coverage", featureCoverage).put("coverage_ratio", coverageRatio)
                    .put("excluded_bars", features.excluded().size());
            ArrayNode excludedExamples = JSON.arrayNode(); for (int i = 0; i < Math.min(5, features.excluded().size()); i++) excludedExamples.add(features.excluded().get(i).deepCopy());
            result.set("excluded_examples", excludedExamples);
            result.set("coverage_reason", features.reason() == null ? NullNode.instance : JSON.textNode(features.reason()));
            result.put("framework", framework); result.set("channel", channel == null ? NullNode.instance : JSON.textNode(channel));
            result.put("candidates_declared", candidates.size()).put("holdout_pass", pass);
            ObjectNode hc = JSON.objectNode(); putNumber(hc, "min_signals", config.minHoldoutSignals());
            hc.set("actual_signals", holdoutSide == null ? JSON.numberNode(0) : copyOrNull(holdoutSide.get("signals")));
            hc.set("precision", holdoutSide == null ? NullNode.instance : copyOrNull(holdoutSide.get("precision")));
            hc.set("expectancy_r", holdoutSide == null ? NullNode.instance : copyOrNull(holdoutSide.get("expectancy_r")));
            hc.set("early_capture", holdoutSide == null ? NullNode.instance : copyOrNull(holdoutSide.get("early_capture")));
            hc.put("regime_count", regimeCount).put("coverage_ratio", coverageRatio)
                    .put("point_in_time_safe", output.path("point_in_time_safe").asBoolean(false))
                    .put("proxy_contract_accepted", output.path("proxy_contract").path("accepted").asBoolean(false)).put("pass", pass);
            result.set("holdout_criteria", hc);
            result.set("walk_forward", wf);
            result.put("activation", pass ? "CANDIDATE_REVIEW" : "SHADOW"); resultDatasets.add(result);
        }

        boolean allPass = true;
        for (String asset : List.of("btc", "eth")) {
            allPass &= hasPassing(resultDatasets, asset, "fallen_knives", null);
            allPass &= hasPassing(resultDatasets, asset, "flying_rocket", "A");
            allPass &= hasPassing(resultDatasets, asset, "flying_rocket", "B");
        }
        boolean active = allPass && output.path("point_in_time_safe").asBoolean(false)
                && output.path("proxy_contract").path("accepted").asBoolean(false);
        output.put("activation", active ? "ACTIVE" : "SHADOW");
        ((ObjectNode) output.get("model_activation")).put("status", active ? "ACTIVE" : "SHADOW");
        if (active) {
            String digest = SwingEngine.sha256(SwingCalibrationLinter.canonicalPayload(output));
            ObjectNode activated = JSON.objectNode().put("status", "ACTIVE").put("artifact", "calibrations/swing-btc-eth.json")
                    .put("sha256", digest).put("activated_at", output.path("generated_at").asText());
            output.set("model_activation", activated);
            output.set("artifact", JSON.objectNode().put("path", "calibrations/swing-btc-eth.json").put("sha256", digest)
                    .put("hash_scope", "canonical calibration payload with model_activation artifact metadata stripped"));
        }
        return output;
    }

    public static ArrayNode labelRows(ArrayNode rows) {
        ArrayNode result = JSON.arrayNode();
        for (int index = 120; index < rows.size() - 180; index++) {
            double unit = atr(rows, index, 120); if (!Double.isFinite(unit) || unit == 0 || unit < 0) continue;
            JsonNode base = rows.get(index); double close = number(base.get("close"));
            double longFav = close + 1.5 * unit, longBad = close - unit, shortFav = close - 1.5 * unit, shortBad = close + unit;
            Integer lf = null, lb = null, sf = null, sb = null;
            for (int next = index + 1; next <= Math.min(rows.size() - 1, index + 180); next++) {
                JsonNode row = rows.get(next);
                if (lf == null && number(row.get("high")) >= longFav) lf = next;
                if (lb == null && number(row.get("low")) <= longBad) lb = next;
                if (sf == null && number(row.get("low")) <= shortFav) sf = next;
                if (sb == null && number(row.get("high")) >= shortBad) sb = next;
                if (lf != null && lb != null && sf != null && sb != null) break;
            }
            int longResolution = minOr(lf, lb, 180), shortResolution = minOr(sf, sb, 180);
            long time = (long) number(base.get("time")); int month = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC).getYear() * 12
                    + Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC).getMonthValue() - 1;
            ObjectNode label = JSON.objectNode().put("time", time).put("month", month).put("close", close).put("atr_20d", unit)
                    .put("long", lf != null && (lb == null || lf < lb)).put("short", sf != null && (sb == null || sf < sb));
            label.set("long_favorable_bars", lf == null ? NullNode.instance : JSON.numberNode(lf - index));
            label.set("short_favorable_bars", sf == null ? NullNode.instance : JSON.numberNode(sf - index));
            label.put("long_early_capture", lf != null && lf - index <= 45).put("short_early_capture", sf != null && sf - index <= 45)
                    .put("early_window_bars", 45).put("long_resolution_bars", longResolution).put("short_resolution_bars", shortResolution);
            result.add(label);
        }
        return result;
    }

    static FeatureRows featureRows(JsonNode raw, ArrayNode labels, int direction) {
        JsonNode supplied = raw.has("features") ? raw.get("features") : raw.get("rows");
        if (supplied == null || !supplied.isArray()) return new FeatureRows(false, JSON.arrayNode(), JSON.arrayNode(), 0,
                "aligned feature rows are required; OHLC labels alone are SHADOW");
        Map<Double, JsonNode> byTime = new LinkedHashMap<>();
        for (JsonNode row : supplied) byTime.put(number(first(row.get("time"), row.get("timestamp"))), row);
        ArrayNode excluded = JSON.arrayNode(), rows = JSON.arrayNode();
        for (JsonNode label : labels) {
            JsonNode row = byTime.get(number(label.get("time")));
            if (row == null) { excluded.add(exclusion(label.get("time"), "missing_feature_row")); continue; }
            JsonNode legs = row.has("legs") ? row.get("legs") : row.path("score").get("legs");
            boolean missingLeg = legs == null;
            if (!missingLeg) for (String name : LEG_NAMES) if (!finiteCoerced(legs.get(name))) { missingLeg = true; break; }
            if (missingLeg) { excluded.add(exclusion(label.get("time"), "missing_leg")); continue; }
            JsonNode components = row.has("leg_components") ? row.get("leg_components") : row.path("score").get("leg_components");
            boolean invalid = components == null;
            if (!invalid) for (Map.Entry<String, SwingScore.ComponentMax> entry : SwingScore.LEG_COMPONENT_MAXES.entrySet()) {
                JsonNode value = components.path(entry.getKey()); double state = coerce(value.get("state")), impulse = coerce(value.get("impulse"));
                if (!Double.isFinite(state) || !Double.isFinite(impulse) || state < 0 || state > entry.getValue().state()
                        || impulse < 0 || impulse > entry.getValue().impulse()
                        || jsRound((state + impulse) * 2) / 2.0 != coerce(legs.get(entry.getKey()))) { invalid = true; break; }
            }
            if (invalid) { excluded.add(exclusion(label.get("time"), "invalid_leg_components")); continue; }
            JsonNode flowPanel = direction == 1 ? first(row.get("flow_panel_long"), row.path("flow_panels").get("long"), row.get("flow_panel"), row.get("market_flow"), row.path("context").get("market_flow"))
                    : first(row.get("flow_panel_short"), row.path("flow_panels").get("short"), row.get("flow_panel"), row.get("market_flow"), row.path("context").get("market_flow"));
            String coverage = truthyText(first(row.get("flow_coverage"), row.get("coverage"), flowPanel == null ? null : flowPanel.get("coverage")));
            if (coverage == null) coverage = "PARTIAL";
            SwingScore.FlowAssessment flow = SwingScore.assessFlowPanel(flowPanel == null ? JSON.objectNode() : flowPanel,
                    new SwingScore.FlowOptions((double) direction, coverage));
            if (!flow.eligible_for_entry()) {
                excluded.add(exclusion(label.get("time"), flow.reason() == null ? "incomplete_flow_panel" : flow.reason())); continue;
            }
            ObjectNode merged = label.deepCopy(); merged.setAll((ObjectNode) row); merged.set("time", label.get("time").deepCopy());
            merged.set("month", label.has("month") ? label.get("month").deepCopy() : JSON.numberNode(monthIndex(number(label.get("time")))));
            ObjectNode normalizedLegs = JSON.objectNode(); for (String name : LEG_NAMES) putNumber(normalizedLegs, name, coerce(legs.get(name))); merged.set("legs", normalizedLegs);
            merged.set("flow_panel", flowPanel == null ? NullNode.instance : flowPanel.deepCopy());
            merged.set("leg_components", components.deepCopy());
            merged.set("flow_assessment", MAPPER.valueToTree(flow)); merged.put("flow_coverage", coverage);
            merged.set("flow_panel_long", first(row.get("flow_panel_long"), row.path("flow_panels").get("long")) == null ? NullNode.instance
                    : first(row.get("flow_panel_long"), row.path("flow_panels").get("long")).deepCopy());
            merged.set("flow_panel_short", first(row.get("flow_panel_short"), row.path("flow_panels").get("short")) == null ? NullNode.instance
                    : first(row.get("flow_panel_short"), row.path("flow_panels").get("short")).deepCopy());
            rows.add(merged);
        }
        boolean complete = !labels.isEmpty() && rows.size() == labels.size();
        String reason = complete ? null : "aligned state/impulse components and error-free 4h flow with 24h+3d windows are required for every labeled bar";
        return new FeatureRows(complete, rows, excluded, labels.isEmpty() ? 0 : (double) rows.size() / labels.size(), reason);
    }

    static ArrayNode validCandidates(JsonNode candidates, String framework, String channel) {
        ArrayNode output = JSON.arrayNode(); if (candidates == null || !candidates.isArray()) return output;
        Set<String> allowed = SwingScore.phaseThresholds(framework, channel).keySet(); String direction = "fallen_knives".equals(framework) ? "long" : "short";
        for (JsonNode candidate : candidates) {
            if (candidate == null || !candidate.isObject() || !framework.equals(text(candidate.get("framework")))
                    || ("flying_rocket".equals(framework) && !java.util.Objects.equals(channel, text(candidate.get("channel"))))
                    || !direction.equals(text(candidate.get("direction"))) || !allowed.contains(text(candidate.get("phase")))
                    || ("flying_rocket".equals(framework) && "B".equals(channel) && "3".equals(text(candidate.get("phase"))))) continue;
            JsonNode bars = candidate.get("trigger_window_bars");
            if (!strictInteger(bars) || bars.intValue() < 1 || bars.intValue() > 2) continue;
            if (candidate.has("threshold_offset") && coerce(candidate.get("threshold_offset")) != 0) continue;
            JsonNode minFlow = candidate.get("min_flow_aligned");
            if (minFlow != null && (!strictInteger(minFlow) || minFlow.intValue() < 0 || minFlow.intValue() > 5)) continue;
            JsonNode minTech = candidate.get("min_technical"); double mt = coerce(minTech);
            if (minTech != null && (!Double.isFinite(mt) || mt < 0 || mt > 4)) continue;
            ObjectNode normalized = ((ObjectNode) candidate).deepCopy().put("threshold_offset", 0)
                    .put("min_flow_aligned", truthy(minFlow) ? (int) coerce(minFlow) : 0);
            putNumber(normalized, "min_technical", truthy(minTech) ? mt : 0); output.add(normalized);
        }
        return output;
    }

    static ObjectNode evaluateCandidate(ArrayNode rows, JsonNode candidate, String framework, String channel, Options options) {
        ArrayNode signals = JSON.arrayNode(); String directionText = text(candidate.get("direction")); int direction = "long".equals(directionText) ? 1 : -1;
        for (JsonNode row : rows) {
            JsonNode panel = direction == 1 ? first(row.get("flow_panel_long"), row.path("flow_panels").get("long"), row.get("flow_panel"))
                    : first(row.get("flow_panel_short"), row.path("flow_panels").get("short"), row.get("flow_panel"));
            SwingScore.FlowAssessment flow = SwingScore.assessFlowPanel(panel == null ? JSON.objectNode() : panel,
                    new SwingScore.FlowOptions((double) direction, text(row.get("flow_coverage"))));
            LinkedHashMap<String, Object> legs = new LinkedHashMap<>(); for (String name : LEG_NAMES) legs.put(name, coerce(row.path("legs").get(name))); legs.put("flow", flow.score());
            SwingScore.ScoreResult score = SwingScore.scoreSwing(new SwingScore.ScoreInput(legs, null, 0,
                    truthy(row.get("impulse")) ? coerce(row.get("impulse")) : 0));
            JsonNode rawTrigger = row.path("trigger"); JsonNode age = nullish(first(rawTrigger.get("age_bars"), row.get("trigger_age_bars")));
            SwingScore.TriggerWindow trigger = SwingScore.triggerWindow(new SwingScore.TriggerInput(
                    truthyText(rawTrigger.get("timeframe")) == null ? "4h" : rawTrigger.get("timeframe").asText(),
                    rawTrigger.path("valid").asBoolean(false), truthyText(rawTrigger.get("created_at")),
                    truthy(rawTrigger.get("level")) ? jsonValue(rawTrigger.get("level")) : null,
                    candidate.get("trigger_window_bars"), age == null ? null : jsonValue(age), !rawTrigger.has("completed_bar") || rawTrigger.path("completed_bar").asBoolean(true)));
            JsonNode veto = row.has("vetoes") ? row.get("vetoes") : row.path("veto_flags");
            List<SwingScore.Veto> vetoes = SwingScore.hardVetoes(new SwingScore.HardVetoInput(flow.eligible_for_entry() ? "COMPLETE" : "PARTIAL",
                    flow.opposing_rows() > 0 || veto.path("opposing_flow").asBoolean(false), veto.path("regime_mismatch").asBoolean(false),
                    veto.path("risk_budget").asBoolean(false), veto.path("narrative_exit").asBoolean(false), veto.path("carry").asBoolean(false),
                    veto.path("funding").asBoolean(false), veto.path("macro_shock").asBoolean(false)));
            SwingScore.ActivePhaseResult base = SwingScore.activePhase(new SwingScore.ActivePhaseInput(framework, channel,
                    text(candidate.get("phase")), score, trigger, vetoes));
            int threshold = (base.threshold() == null ? 0 : base.threshold()) + (int) coerce(candidate.get("threshold_offset"));
            boolean scorePass = score.mechanical() >= threshold, triggerPass = base.trigger_pass(), vetoPass = base.veto_pass();
            boolean unlocked = scorePass && triggerPass && vetoPass && flow.aligned_rows() >= (int) coerce(candidate.get("min_flow_aligned"))
                    && coerce(row.path("legs").get("technical")) >= coerce(candidate.get("min_technical"))
                    && protectiveControls(row, framework, channel, text(candidate.get("phase")));
            int cap = SwingScore.phaseCaps(framework, channel).getOrDefault(text(candidate.get("phase")), 0);
            SwingScore.RiskBudgetResult budget = SwingScore.riskBudget(new SwingScore.RiskBudgetInput(cap,
                    finiteCoerced(row.get("equity_usd")) ? coerce(row.get("equity_usd")) : null,
                    finiteCoerced(row.get("stop_distance_pct")) ? coerce(row.get("stop_distance_pct")) : null));
            if (!"AVAILABLE".equals(budget.status())) unlocked = false;
            if ("flying_rocket".equals(framework) && "B".equals(channel) && (truthy(row.get("book_pct")) ? coerce(row.get("book_pct")) : 0) + cap > 30) unlocked = false;
            if (unlocked) {
                ObjectNode signal = ((ObjectNode) row).deepCopy(); ObjectNode outcome = JSON.objectNode()
                        .put("long", row.path("long").asBoolean(false)).put("short", row.path("short").asBoolean(false))
                        .put("long_early", row.path("long_early_capture").asBoolean(false)).put("short_early", row.path("short_early_capture").asBoolean(false));
                signal.set("outcome", outcome);
                signal.set("score", MAPPER.valueToTree(score));
                ObjectNode phase = JSON.objectNode().set("phase", copyOrNull(candidate.get("phase")));
                phase.put("threshold", threshold).put("score", score.mechanical()).put("score_pass", scorePass).put("trigger_pass", triggerPass)
                        .put("veto_pass", vetoPass).put("unlocked", true);
                phase.set("vetoes", MAPPER.valueToTree(base.vetoes()));
                phase.put("risk_status", budget.status()); signal.set("phase", phase); signal.set("budget", MAPPER.valueToTree(budget)); signals.add(signal);
            }
        }
        return metrics(signals, directionText, options);
    }

    static ObjectNode metrics(ArrayNode rows, String side, Options options) {
        if (rows.isEmpty()) return emptyMetrics();
        ArrayNode episodes = nonOverlapping(rows, 180); int winners = 0, early = 0; double costSum = 0;
        for (JsonNode row : episodes) {
            if (row.path("outcome").path(side).asBoolean(false)) winners++;
            if (row.path("outcome").path(side + "_early").asBoolean(false)) early++;
            double stop = coerce(row.get("stop_distance_pct"));
            costSum += Double.isFinite(stop) && stop > 0 ? 2 * (options.costPct() + options.slippagePct()) / stop : Double.POSITIVE_INFINITY;
        }
        int losses = episodes.size() - winners; double precision = (double) winners / episodes.size(), costs = costSum / episodes.size();
        ObjectNode result = JSON.objectNode().put("signals", episodes.size()).put("raw_signals", rows.size()).put("wins", winners).put("losses", losses)
                .put("precision", precision).put("early_capture", (double) early / episodes.size());
        putFiniteOrNull(result, "costs_r", costs); putFiniteOrNull(result, "expectancy_r", precision * 1.5 - (1 - precision) - costs); return result;
    }

    static ObjectNode walkForward(ArrayNode rows, ArrayNode candidates, String framework, String channel, Options options) {
        if (rows.isEmpty() || candidates.isEmpty()) {
            ObjectNode empty = JSON.objectNode(); empty.set("development", NullNode.instance); empty.set("folds", JSON.arrayNode());
            empty.set("holdout", NullNode.instance); empty.put("candidates_declared", candidates.size()); return empty;
        }
        List<Integer> months = new ArrayList<>(); for (JsonNode row : rows) { int month = row.path("month").asInt(); if (!months.contains(month)) months.add(month); } months.sort(Integer::compareTo);
        if (months.size() < 36) {
            ObjectNode out = JSON.objectNode(); out.set("development", NullNode.instance); out.set("folds", JSON.arrayNode()); out.set("holdout", NullNode.instance);
            out.put("candidates_declared", candidates.size()).put("split_status", "INSUFFICIENT_36_CALENDAR_MONTHS")
                    .put("required_months", 36).put("observed_months", months.size()); out.set("months", intArray(months)); return out;
        }
        List<Integer> developmentMonths = months.subList(0, 18), foldMonths = months.subList(18, 30), holdoutMonths = months.subList(30, 36);
        ArrayNode developmentRows = filterMonths(rows, developmentMonths); ObjectNode development = summarize(developmentRows, candidates, framework, channel, options);
        ArrayNode folds = JSON.arrayNode();
        for (int index = 0; index < foldMonths.size(); index += 3) {
            int cutoff = months.indexOf(foldMonths.get(index)); ArrayNode train = filterBeforeMonthIndex(rows, months, cutoff);
            ArrayNode test = filterMonths(rows, foldMonths.subList(index, Math.min(foldMonths.size(), index + 3)));
            JsonNode selectedReport = bestCandidate(summarizeCandidates(train, candidates, framework, channel, options), framework, options);
            JsonNode selected = selectedReport == null ? null : selectedReport.get("candidate");
            ObjectNode fold = JSON.objectNode().set("train", summarizeCandidates(train, candidates, framework, channel, options));
            fold.set("selected", selected == null ? NullNode.instance : selected.deepCopy()); fold.put("selection_blocked", selected == null);
            fold.set("test", summarizeCandidates(test, selected == null ? JSON.arrayNode() : JSON.arrayNode().add(selected.deepCopy()), framework, channel, options)); folds.add(fold);
        }
        ArrayNode holdoutRows = filterMonths(rows, holdoutMonths); ArrayNode train = JSON.arrayNode();
        for (JsonNode row : rows) if (!holdoutMonths.contains(row.path("month").asInt())) train.add(row.deepCopy());
        JsonNode selectedReport = bestCandidate(summarizeCandidates(train, candidates, framework, channel, options), framework, options);
        JsonNode selected = selectedReport == null ? null : selectedReport.get("candidate");
        ObjectNode holdout = JSON.objectNode(); holdout.set("selected", selected == null ? NullNode.instance : selected.deepCopy());
        holdout.set("reports", summarizeCandidates(holdoutRows, selected == null ? JSON.arrayNode() : JSON.arrayNode().add(selected.deepCopy()), framework, channel, options));
        holdout.put("count", holdoutRows.size()); holdout.set("months", intArray(holdoutMonths)); holdout.put("untouched", true).put("selection_blocked", selected == null)
                .put("train_end_month", holdoutMonths.isEmpty() ? 0 : holdoutMonths.getFirst() - 1);
        ObjectNode output = JSON.objectNode(); output.set("development", development); output.set("folds", folds); output.set("holdout", holdout);
        output.put("candidates_declared", candidates.size()); return output;
    }

    static ArrayNode summarizeCandidates(ArrayNode rows, ArrayNode candidates, String framework, String channel, Options options) {
        ArrayNode reports = JSON.arrayNode();
        for (JsonNode candidate : candidates) {
            ArrayNode signals = evaluateSignals(rows, candidate, framework, channel);
            LinkedHashMap<String, Integer> regimes = new LinkedHashMap<>();
            for (JsonNode signal : signals) regimes.merge(truthyText(signal.get("regime")) == null ? "UNKNOWN" : signal.get("regime").asText(), 1, Integer::sum);
            ObjectNode report = JSON.objectNode().set("candidate", candidate.deepCopy()); String direction = text(candidate.get("direction"));
            report.set("long", "long".equals(direction) ? metrics(signals, "long", options) : emptyMetrics());
            report.set("short", "short".equals(direction) ? metrics(signals, "short", options) : emptyMetrics()); report.put("signal_rows", signals.size());
            ObjectNode counts = JSON.objectNode(); regimes.forEach(counts::put); report.set("regime_coverage", JSON.objectNode().put("count", regimes.size()).set("counts", counts)); reports.add(report);
        }
        return reports;
    }

    /* evaluateCandidate above returns metrics for direct callers; this helper retains the source signal rows for summaries. */
    private static ArrayNode evaluateSignals(ArrayNode rows, JsonNode candidate, String framework, String channel) {
        Options zeroCosts = new Options(3, 0, 0, 5, .8, 3, 3, .4);
        // Duplicate only the final signal extraction by tagging the input and
        // delegating through the same score logic would obscure row identity;
        // use the compact evaluator below, behavior-identical to evaluateCandidate.
        ArrayNode out = JSON.arrayNode(); String directionText = text(candidate.get("direction")); int direction = "long".equals(directionText) ? 1 : -1;
        for (JsonNode row : rows) {
            JsonNode panel = direction == 1 ? first(row.get("flow_panel_long"), row.path("flow_panels").get("long"), row.get("flow_panel"))
                    : first(row.get("flow_panel_short"), row.path("flow_panels").get("short"), row.get("flow_panel"));
            SwingScore.FlowAssessment flow = SwingScore.assessFlowPanel(panel == null ? JSON.objectNode() : panel, new SwingScore.FlowOptions((double) direction, text(row.get("flow_coverage"))));
            LinkedHashMap<String, Object> legs = new LinkedHashMap<>(); for (String name : LEG_NAMES) legs.put(name, coerce(row.path("legs").get(name))); legs.put("flow", flow.score());
            SwingScore.ScoreResult score = SwingScore.scoreSwing(new SwingScore.ScoreInput(legs, null, 0, truthy(row.get("impulse")) ? coerce(row.get("impulse")) : 0));
            JsonNode rawTrigger = row.path("trigger"); JsonNode age = nullish(first(rawTrigger.get("age_bars"), row.get("trigger_age_bars")));
            SwingScore.TriggerWindow trigger = SwingScore.triggerWindow(new SwingScore.TriggerInput(truthyText(rawTrigger.get("timeframe")) == null ? "4h" : rawTrigger.get("timeframe").asText(),
                    rawTrigger.path("valid").asBoolean(false), truthyText(rawTrigger.get("created_at")), truthy(rawTrigger.get("level")) ? jsonValue(rawTrigger.get("level")) : null,
                    candidate.get("trigger_window_bars"), age == null ? null : jsonValue(age), !rawTrigger.has("completed_bar") || rawTrigger.path("completed_bar").asBoolean(true)));
            JsonNode flags = row.has("vetoes") ? row.get("vetoes") : row.path("veto_flags");
            List<SwingScore.Veto> vetoes = SwingScore.hardVetoes(new SwingScore.HardVetoInput(flow.eligible_for_entry() ? "COMPLETE" : "PARTIAL",
                    flow.opposing_rows() > 0 || flags.path("opposing_flow").asBoolean(false), flags.path("regime_mismatch").asBoolean(false), flags.path("risk_budget").asBoolean(false),
                    flags.path("narrative_exit").asBoolean(false), flags.path("carry").asBoolean(false), flags.path("funding").asBoolean(false), flags.path("macro_shock").asBoolean(false)));
            SwingScore.ActivePhaseResult phase = SwingScore.activePhase(new SwingScore.ActivePhaseInput(framework, channel, text(candidate.get("phase")), score, trigger, vetoes));
            int threshold = (phase.threshold() == null ? 0 : phase.threshold()) + (int) coerce(candidate.get("threshold_offset"));
            boolean unlocked = score.mechanical() >= threshold && phase.trigger_pass() && phase.veto_pass()
                    && flow.aligned_rows() >= (int) coerce(candidate.get("min_flow_aligned")) && coerce(row.path("legs").get("technical")) >= coerce(candidate.get("min_technical"))
                    && protectiveControls(row, framework, channel, text(candidate.get("phase")));
            int cap = SwingScore.phaseCaps(framework, channel).getOrDefault(text(candidate.get("phase")), 0);
            SwingScore.RiskBudgetResult budget = SwingScore.riskBudget(new SwingScore.RiskBudgetInput(cap, finiteCoerced(row.get("equity_usd")) ? coerce(row.get("equity_usd")) : null,
                    finiteCoerced(row.get("stop_distance_pct")) ? coerce(row.get("stop_distance_pct")) : null));
            if (!"AVAILABLE".equals(budget.status()) || ("flying_rocket".equals(framework) && "B".equals(channel)
                    && (truthy(row.get("book_pct")) ? coerce(row.get("book_pct")) : 0) + cap > 30)) unlocked = false;
            if (unlocked) { ObjectNode signal = ((ObjectNode) row).deepCopy(); signal.set("outcome", JSON.objectNode().put("long", row.path("long").asBoolean(false))
                    .put("short", row.path("short").asBoolean(false)).put("long_early", row.path("long_early_capture").asBoolean(false)).put("short_early", row.path("short_early_capture").asBoolean(false)));
                signal.set("score", MAPPER.valueToTree(score)); signal.set("budget", MAPPER.valueToTree(budget)); out.add(signal); }
        }
        return out;
    }

    private static ObjectNode summarize(ArrayNode rows, ArrayNode candidates, String framework, String channel, Options options) {
        ObjectNode out = JSON.objectNode().set("reports", summarizeCandidates(rows, candidates, framework, channel, options)); out.put("count", rows.size());
        LinkedHashSet<Integer> months = new LinkedHashSet<>(); rows.forEach(row -> months.add(row.path("month").asInt())); out.set("months", intArray(new ArrayList<>(months))); return out;
    }
    private static JsonNode bestCandidate(ArrayNode reports, String framework, Options options) {
        String side = "fallen_knives".equals(framework) ? "long" : "short"; List<JsonNode> admissible = new ArrayList<>();
        for (JsonNode report : reports) { JsonNode metrics = report.get(side); if (number(metrics.get("signals")) >= options.minTrainSignals()
                && nullableNumber(metrics.get("precision"), Double.NEGATIVE_INFINITY) >= options.trainPrecisionMin()
                && nullableNumber(metrics.get("expectancy_r"), Double.NEGATIVE_INFINITY) > 0) admissible.add(report); }
        admissible.sort(Comparator.<JsonNode>comparingDouble(report -> nullableNumber(report.path(side).get("early_capture"), Double.NEGATIVE_INFINITY)).reversed()
                .thenComparing(Comparator.comparingDouble((JsonNode report) -> nullableNumber(report.path(side).get("expectancy_r"), Double.NEGATIVE_INFINITY)).reversed())
                .thenComparing(Comparator.comparingInt((JsonNode report) -> report.path("signal_rows").asInt()).reversed()));
        return admissible.isEmpty() ? null : admissible.getFirst();
    }
    private static boolean protectiveControls(JsonNode row, String framework, String channel, String phase) {
        JsonNode controls = row.has("protective_controls") ? row.get("protective_controls") : row.path("risk_controls");
        if (!"flying_rocket".equals(framework)) return true; if ("B".equals(channel) && "3".equals(phase)) return false;
        return controls.path("stop_valid").asBoolean(false) && controls.path("time_stop_valid").asBoolean(false)
                && controls.path("ratchet_valid").asBoolean(false) && !controls.path("carry_veto").asBoolean(false);
    }
    private static ArrayNode nonOverlapping(ArrayNode rows, int horizon) {
        List<JsonNode> sorted = new ArrayList<>(); rows.forEach(sorted::add); sorted.sort(Comparator.comparingDouble(row -> number(row.get("time"))));
        ArrayNode out = JSON.arrayNode(); double next = Double.NEGATIVE_INFINITY;
        for (JsonNode row : sorted) { double time = number(row.get("time")); if (time < next) continue; out.add(row.deepCopy());
            double resolution = coerce(first(row.get("resolution_bars"), row.path("outcome").get("resolution_bars")));
            if (!truthy(first(row.get("resolution_bars"), row.path("outcome").get("resolution_bars")))) resolution = horizon;
            next = time + Math.max(1, resolution) * BAR_MS; }
        return out;
    }
    private static ObjectNode emptyMetrics() { ObjectNode out = JSON.objectNode().put("signals", 0).put("raw_signals", 0).put("wins", 0).put("losses", 0);
        out.set("precision", NullNode.instance); out.set("early_capture", NullNode.instance); out.put("costs_r", 0); out.set("expectancy_r", NullNode.instance); return out; }
    private static ArrayNode expandDatasets(ArrayNode datasets) { ArrayNode out = JSON.arrayNode(); for (JsonNode dataset : datasets) {
        if (truthy(dataset.get("framework"))) out.add(dataset.deepCopy()); else { ObjectNode fk = ((ObjectNode) dataset).deepCopy().put("framework", "fallen_knives"); fk.set("channel", NullNode.instance); out.add(fk);
            out.add(((ObjectNode) dataset).deepCopy().put("framework", "flying_rocket").put("channel", "A")); out.add(((ObjectNode) dataset).deepCopy().put("framework", "flying_rocket").put("channel", "B")); } } return out; }
    private static boolean hasPassing(ArrayNode datasets, String asset, String framework, String channel) { for (JsonNode dataset : datasets)
        if (asset.equals(text(dataset.get("asset"))) && framework.equals(text(dataset.get("framework"))) && java.util.Objects.equals(channel, nullishText(dataset.get("channel")))
                && dataset.path("holdout_pass").asBoolean(false)) return true; return false; }
    private static ObjectNode exclusion(JsonNode time, String reason) { ObjectNode out = JSON.objectNode(); out.set("time", copyOrNull(time)); out.put("reason", reason); return out; }
    private static ArrayNode filterMonths(ArrayNode rows, List<Integer> months) { ArrayNode out = JSON.arrayNode(); for (JsonNode row : rows) if (months.contains(row.path("month").asInt())) out.add(row.deepCopy()); return out; }
    private static ArrayNode filterBeforeMonthIndex(ArrayNode rows, List<Integer> months, int cutoff) { ArrayNode out = JSON.arrayNode(); for (JsonNode row : rows)
        if (months.indexOf(row.path("month").asInt()) < cutoff) out.add(row.deepCopy()); return out; }
    private static ArrayNode intArray(List<Integer> values) { ArrayNode out = JSON.arrayNode(); values.forEach(out::add); return out; }
    private static List<String> jsObjectKeys(Set<String> keys) { List<String> numeric = new ArrayList<>(), other = new ArrayList<>();
        for (String key : keys) { if (key.matches("^(0|[1-9]\\d*)$") && Long.parseLong(key) <= 4_294_967_294L) numeric.add(key); else other.add(key); }
        numeric.sort(Comparator.comparingLong(Long::parseLong)); numeric.addAll(other); return numeric; }
    private static double atr(ArrayNode rows, int index, int length) { if (index < length) return Double.NaN; double total = 0;
        for (int i = index - length + 1; i <= index; i++) total += trueRange(rows.get(i), rows.get(i - 1)); return total / length; }
    private static double trueRange(JsonNode row, JsonNode prior) { return Math.max(number(row.get("high")) - number(row.get("low")),
            Math.max(Math.abs(number(row.get("high")) - number(prior.get("close"))), Math.abs(number(row.get("low")) - number(prior.get("close"))))); }
    private static int minOr(Integer a, Integer b, int fallback) { if (a == null && b == null) return fallback; if (a == null) return b; if (b == null) return a; return Math.min(a, b); }
    private static int monthIndex(double time) { Instant instant = Instant.ofEpochMilli((long) time); return instant.atZone(ZoneOffset.UTC).getYear() * 12 + instant.atZone(ZoneOffset.UTC).getMonthValue() - 1; }
    private static boolean strictInteger(JsonNode node) { return node != null && node.isIntegralNumber(); }
    private static boolean finiteCoerced(JsonNode node) { return Double.isFinite(coerce(node)); }
    private static double coerce(JsonNode node) { if (node == null || node.isMissingNode()) return Double.NaN; if (node.isNull()) return 0; if (node.isBoolean()) return node.asBoolean() ? 1 : 0;
        if (node.isNumber()) return node.doubleValue(); if (node.isTextual()) { if (node.asText().trim().isEmpty()) return 0; try { return Double.parseDouble(node.asText()); } catch (RuntimeException ignored) { return Double.NaN; } } return Double.NaN; }
    private static double number(JsonNode node) { return node == null || node.isNull() ? 0 : node.asDouble(); }
    private static double nullableNumber(JsonNode node, double fallback) { return node == null || node.isNull() ? fallback : node.asDouble(); }
    private static double jsOr(JsonNode node, double fallback) { double value = coerce(node); return !Double.isFinite(value) || value == 0 ? fallback : value; }
    private static long jsRound(double value) { return (long) Math.floor(value + 0.5); }
    private static JsonNode first(JsonNode... nodes) { for (JsonNode node : nodes) if (node != null && !node.isMissingNode() && !node.isNull()) return node; return null; }
    private static JsonNode nullish(JsonNode node) { return node == null || node.isNull() || node.isMissingNode() ? null : node; }
    private static Object jsonValue(JsonNode node) { if (node == null || node.isNull()) return null; if (node.isNumber()) return node.numberValue(); if (node.isBoolean()) return node.booleanValue(); return node.asText(); }
    private static String text(JsonNode node) { return node == null || node.isNull() || node.isMissingNode() ? null : node.asText(); }
    private static String truthyText(JsonNode node) { return truthy(node) ? node.asText() : null; }
    private static String nullishText(JsonNode node) { return node == null || node.isNull() || node.isMissingNode() ? null : node.asText(); }
    private static boolean truthy(JsonNode node) { return SwingCrossValidator.truthy(node); }
    private static JsonNode copyOrNull(JsonNode node) { return node == null || node.isMissingNode() ? NullNode.instance : node.deepCopy(); }
    private static void putNumber(ObjectNode object, String field, double value) { if (Double.isFinite(value) && value == Math.rint(value) && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) object.put(field, (long) value); else if (Double.isFinite(value)) object.put(field, value); else object.set(field, NullNode.instance); }
    private static void putFiniteOrNull(ObjectNode object, String field, double value) { if (Double.isFinite(value)) object.put(field, value); else object.set(field, NullNode.instance); }

    record FeatureRows(boolean complete, ArrayNode rows, ArrayNode excluded, double coverageRatio, String reason) {}
}

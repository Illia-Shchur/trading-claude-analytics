package com.tradinganalytics.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.swing.SwingScore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import static com.tradinganalytics.reporting.ReportJsonSupport.array;
import static com.tradinganalytics.reporting.ReportJsonSupport.basename;
import static com.tradinganalytics.reporting.ReportJsonSupport.field;
import static com.tradinganalytics.reporting.ReportJsonSupport.fieldNames;
import static com.tradinganalytics.reporting.ReportJsonSupport.finite;
import static com.tradinganalytics.reporting.ReportJsonSupport.halfPoint;
import static com.tradinganalytics.reporting.ReportJsonSupport.hasDuplicates;
import static com.tradinganalytics.reporting.ReportJsonSupport.iso;
import static com.tradinganalytics.reporting.ReportJsonSupport.jsNumber;
import static com.tradinganalytics.reporting.ReportJsonSupport.jsNumberConversion;
import static com.tradinganalytics.reporting.ReportJsonSupport.jsRoundHalf;
import static com.tradinganalytics.reporting.ReportJsonSupport.object;
import static com.tradinganalytics.reporting.ReportJsonSupport.parseDateMillis;
import static com.tradinganalytics.reporting.ReportJsonSupport.stringList;
import static com.tradinganalytics.reporting.ReportJsonSupport.text;
import static com.tradinganalytics.reporting.ReportJsonSupport.upper;

/** Semantic half of the {@code report-machine/3} contract. */
final class ReportMachine3Semantics {
    private static final List<String> EXACT_LEGS = List.of(
            "flow", "technical", "macro", "sentiment", "valuation", "structure");
    private static final Map<String, Double> LEG_MAXES = orderedMap(
            Map.entry("flow", 5.0), Map.entry("technical", 4.0), Map.entry("macro", 3.0),
            Map.entry("sentiment", 3.0), Map.entry("valuation", 3.0), Map.entry("structure", 2.0));
    private static final Map<String, double[]> COMPONENT_MAXES = orderedMap(
            Map.entry("technical", new double[] {2, 2}),
            Map.entry("macro", new double[] {1.5, 1.5}),
            Map.entry("sentiment", new double[] {1.5, 1.5}),
            Map.entry("valuation", new double[] {2, 1}),
            Map.entry("structure", new double[] {1, 1}));
    private static final List<String> CANONICAL_VETOES = List.of(
            "FLOW_COVERAGE", "OPPOSING_FLOW", "REGIME_MISMATCH", "RISK_BUDGET",
            "NARRATIVE_EXIT", "CARRY", "FUNDING", "MACRO_SHOCK");
    private static final Map<String, Integer> CLOCK_DAYS = orderedMap(
            Map.entry("1A", 7), Map.entry("1B", 14), Map.entry("2", 21), Map.entry("3", 30));

    private ReportMachine3Semantics() {
    }

    static SemanticIssues validate(JsonNode report, String filename) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String id = text(report, "report_id");
        Matcher identityMatch = ReportPaths.REPORT_REPORT_ID_RE.matcher(id == null ? "" : id);
        if (!identityMatch.matches()) {
            errors.add("report_id has invalid identity format");
        } else {
            String asset = identityMatch.group(1);
            String framework = identityMatch.group(2);
            String stamp = identityMatch.group(3);
            String time = identityMatch.group(4);
            String expected = asset + "_" + framework + "_" + stamp + "_" + time + ".json";
            JsonNode identity = field(report, "identity");
            if (!asset.toUpperCase(java.util.Locale.ROOT).equals(text(identity, "asset"))) {
                errors.add("identity.asset does not match report_id");
            }
            if (!framework.equals(text(identity, "framework"))) {
                errors.add("identity.framework does not match report_id");
            }
            String expectedDate = stamp.substring(0, 4) + "-" + stamp.substring(4, 6) + "-" + stamp.substring(6, 8);
            if (!expectedDate.equals(text(identity, "date"))) {
                errors.add("identity.date does not match report_id");
            }
            String expectedTime = time.substring(0, 2) + ":" + time.substring(2);
            if (!expectedTime.equals(text(identity, "local_time"))) {
                errors.add("identity.local_time does not match report_id");
            }
            if (!expected.equals(text(identity, "filename"))) {
                errors.add("identity.filename must be " + expected);
            }
            if (filename != null && !basename(filename).equals(expected)) {
                errors.add("filename " + basename(filename) + " does not match identity.filename " + expected);
            }
        }

        JsonNode identity = field(report, "identity");
        JsonNode timestamps = field(report, "timestamps");
        if (!java.util.Objects.equals(text(timestamps, "timezone"), text(identity, "timezone"))) {
            errors.add("timestamps.timezone and identity.timezone differ");
        }
        for (String key : List.of("generated_at", "report_at", "data_as_of")) {
            if (!iso(field(timestamps, key))) {
                errors.add("timestamps." + key + " is not a UTC ISO timestamp");
            }
        }

        JsonNode activation = object(report, "model_activation");
        String activationStatus = text(activation, "status");
        if ("ACTIVE".equals(activationStatus)
                && (!truthy(field(activation, "artifact"))
                || !matchesSha256(field(activation, "sha256"))
                || !iso(field(activation, "activated_at")))) {
            errors.add("ACTIVE swing model requires a named, hashed, timestamped calibration artifact");
        }
        if (!"ACTIVE".equals(activationStatus)
                && (truthy(field(activation, "artifact"))
                || truthy(field(activation, "sha256"))
                || truthy(field(activation, "activated_at")))) {
            warnings.add("non-ACTIVE swing model carries activation artifact metadata");
        }

        JsonNode setup = field(report, "setup");
        String setupFramework = text(setup, "framework");
        if (!java.util.Objects.equals(setupFramework, text(identity, "framework"))) {
            errors.add("setup.framework and identity.framework differ");
        }
        JsonNode horizon = field(setup, "horizon_days");
        if (!numericStrictEquals(field(horizon, "min"), 3) || !numericStrictEquals(field(horizon, "max"), 30)) {
            errors.add("setup.horizon_days must be 3..30");
        }

        JsonNode legs = field(setup, "legs");
        if (!fieldNames(legs).equals(new LinkedHashSet<>(EXACT_LEGS))) {
            errors.add("setup.legs must contain exactly six canonical legs");
        }
        for (String leg : EXACT_LEGS) {
            JsonNode value = field(legs, leg);
            double max = LEG_MAXES.get(leg);
            if (!finite(value) || value.doubleValue() < 0 || value.doubleValue() > max) {
                errors.add("setup.legs." + leg + " outside 0.." + jsNumber(max));
            }
            if (finite(value) && !halfPoint(value.doubleValue())) {
                errors.add("setup.legs." + leg + " must use half-point increments");
            }
        }

        for (String key : List.of("score", "mechanical_score", "discretion", "impulse")) {
            JsonNode value = field(setup, key);
            if (value.isNull() || value.isMissingNode()) {
                continue;
            }
            boolean invalidRange = finite(value)
                    && ("discretion".equals(key)
                    ? value.doubleValue() < -1 || value.doubleValue() > 1
                    : value.doubleValue() < 0 || value.doubleValue() > 20);
            if (!finite(value) || invalidRange) {
                errors.add("setup." + key + " outside its permitted range");
            }
            if (finite(value) && !halfPoint(value.doubleValue())) {
                errors.add("setup." + key + " must use half-point increments");
            }
        }

        double legTotal = 0;
        for (String leg : EXACT_LEGS) {
            // Number(value) || 0: schema-valid legs are numeric, including zero.
            double converted = jsNumberConversion(field(legs, leg));
            legTotal += Double.isFinite(converted) && converted != 0 ? converted : 0;
        }
        JsonNode components = field(setup, "leg_components");
        for (Map.Entry<String, double[]> entry : COMPONENT_MAXES.entrySet()) {
            String leg = entry.getKey();
            double stateMax = entry.getValue()[0];
            double impulseMax = entry.getValue()[1];
            JsonNode component = field(components, leg);
            for (Map.Entry<String, Double> part : List.of(
                    Map.entry("state", stateMax), Map.entry("impulse", impulseMax))) {
                JsonNode value = field(component, part.getKey());
                if (!finite(value) || value.doubleValue() < 0 || value.doubleValue() > part.getValue()
                        || !halfPoint(value.doubleValue())) {
                    errors.add("setup.leg_components." + leg + "." + part.getKey()
                            + " must be a half-point in 0.." + jsNumber(part.getValue()));
                }
            }
            double expectedTotal = truthy(field(component, "state"))
                    ? jsNumberConversion(field(component, "state")) : 0;
            expectedTotal += truthy(field(component, "impulse"))
                    ? jsNumberConversion(field(component, "impulse")) : 0;
            if (!numericStrictEquals(field(component, "total"), expectedTotal)) {
                errors.add("setup.leg_components." + leg + ".total must equal state plus impulse");
            }
            if (!numericStrictEquals(field(legs, leg), expectedTotal)) {
                errors.add("setup.legs." + leg + " must equal its state-plus-impulse components");
            }
        }

        double expectedMechanical = jsRoundHalf(legTotal);
        double discretion = truthy(field(setup, "discretion"))
                ? jsNumberConversion(field(setup, "discretion")) : 0;
        double expectedAdjusted = Math.max(0, Math.min(20, jsRoundHalf(expectedMechanical + discretion)));
        if (!numericStrictEquals(field(setup, "mechanical_score"), expectedMechanical)) {
            errors.add("setup.mechanical_score=" + jsValue(field(setup, "mechanical_score"))
                    + " but expected leg sum " + jsNumber(expectedMechanical));
        }
        if (!numericStrictEquals(field(setup, "score"), expectedAdjusted)) {
            errors.add("setup.score=" + jsValue(field(setup, "score"))
                    + " but expected mechanical plus discretion " + jsNumber(expectedAdjusted));
        }

        JsonNode phaseNode = field(setup, "phase");
        if (!phaseNode.isNull() && !phaseNode.isMissingNode()) {
            String phase = text(phaseNode);
            Map<String, Integer> thresholds = thresholds(setupFramework, text(setup, "channel"));
            Integer threshold = thresholds.get(phase);
            if (threshold == null) {
                errors.add("setup.phase " + phase + " is not valid for this framework/channel");
            } else if (!numericStrictEquals(field(setup, "phase_threshold"), threshold)) {
                errors.add("setup.phase_threshold must equal pinned " + phase + " threshold " + threshold);
            }
        }

        JsonNode trigger = field(report, "trigger");
        if ("VALID".equals(text(trigger, "status"))) {
            if (!truthy(field(trigger, "created_at")) || !truthy(field(trigger, "expires_at"))
                    || field(trigger, "level").isNull() || field(trigger, "level").isMissingNode()) {
                errors.add("VALID trigger requires created_at, expires_at and level");
            }
            if (truthy(field(trigger, "created_at")) && truthy(field(trigger, "expires_at"))
                    && parseDateMillis(field(trigger, "expires_at")) <= parseDateMillis(field(trigger, "created_at"))) {
                errors.add("trigger.expires_at must be after created_at");
            }
            if (truthy(field(trigger, "created_at")) && truthy(field(trigger, "expires_at"))) {
                long created = parseDateMillis(field(trigger, "created_at"));
                long expires = parseDateMillis(field(trigger, "expires_at"));
                double expectedExpiry = created == Long.MIN_VALUE
                        ? Double.NaN
                        : created + jsNumberConversion(field(trigger, "window_bars")) * 4 * 3_600_000;
                if (!dateStrictEquals(expires, expectedExpiry)) {
                    errors.add("trigger expiry must equal its one- or two-completed-4h-bar window");
                }
                if (parseDateMillis(field(timestamps, "report_at")) > expires) {
                    errors.add("VALID trigger is expired at report_at");
                }
            }
        }
        JsonNode ageBars = field(trigger, "age_bars");
        if (!ageBars.isMissingNode() && !ageBars.isNull()
                && (!ageBars.isIntegralNumber() || ageBars.longValue() < 0
                || ageBars.doubleValue() > jsNumberConversion(field(trigger, "window_bars")))) {
            errors.add("trigger.age_bars must be a fresh completed-bar age within window_bars");
        }

        ArrayNode vetoes = array(report, "vetoes");
        List<JsonNode> activeVetoes = new ArrayList<>();
        vetoes.forEach(veto -> {
            if (truthy(field(veto, "active"))) {
                activeVetoes.add(veto);
            }
        });
        boolean entryAuthorized = truthy(field(setup, "entry_authorized"));
        if (entryAuthorized && !activeVetoes.isEmpty()) {
            errors.add("entry_authorized cannot coexist with an active veto");
        }
        if ("AUTHORIZED".equals(text(setup, "status")) && !entryAuthorized) {
            errors.add("AUTHORIZED setup must set entry_authorized:true");
        }
        if (entryAuthorized && !"AUTHORIZED".equals(text(setup, "status"))) {
            errors.add("entry_authorized:true requires setup.status=AUTHORIZED");
        }
        if (entryAuthorized && !"ACTIVE".equals(activationStatus)) {
            errors.add("SHADOW/CANDIDATE_REVIEW swing models cannot authorize entries");
        }

        JsonNode riskBudget = field(report, "risk_budget");
        if (!numericStrictEquals(field(riskBudget, "portfolio_risk_pct"), 1.5)
                || !numericStrictEquals(field(riskBudget, "asset_risk_pct"), 3)) {
            errors.add("risk budget must preserve 1.5% portfolio and 3% asset risk caps");
        }

        List<String> codes = new ArrayList<>();
        vetoes.forEach(veto -> codes.add(text(veto, "code")));
        if (hasDuplicates(codes)) {
            errors.add("veto codes must be unique");
        }
        for (String code : CANONICAL_VETOES) {
            if (!codes.contains(code)) {
                errors.add("canonical v3 veto ledger is missing " + code);
            }
        }
        JsonNode audit = field(report, "audit");
        if ("COMPLETE".equals(text(audit, "coverage")) && fieldNames(field(audit, "sources")).isEmpty()) {
            errors.add("COMPLETE audit requires source entries");
        }
        if (fieldNames(field(report, "sources")).isEmpty()) {
            errors.add("canonical sidecar requires source records");
        }
        if (fieldNames(field(report, "provenance")).isEmpty()) {
            errors.add("canonical sidecar requires provenance");
        }

        JsonNode tags = field(report, "tags");
        JsonNode reservedNode = field(tags, "reserved");
        JsonNode activeNode = field(tags, "active");
        if (!reservedNode.isArray() || !activeNode.isArray()) {
            errors.add("canonical sidecar requires reserved and active internal tags");
        } else {
            List<String> reserved = stringList(reservedNode);
            List<String> active = stringList(activeNode);
            Set<String> reservedSet = new LinkedHashSet<>(reserved);
            if (reservedSet.size() != reserved.size()) {
                errors.add("canonical sidecar reserved tags must be unique");
            }
            for (String tag : active) {
                if (!reservedSet.contains(tag)) {
                    errors.add("active internal tag " + tag + " is not reserved");
                }
            }
            if (!active.isEmpty() && !"FILLED".equals(text(field(field(report, "trade_plan"), "entry"), "status"))) {
                errors.add("active internal tags require an actually FILLED entry");
            }
        }

        JsonNode features = field(report, "features");
        JsonNode flow = truthy(field(features, "flow")) ? field(features, "flow")
                : truthy(field(features, "market_flow")) ? field(features, "market_flow")
                : MissingNode.getInstance();
        double setupDirection = "fallen_knives".equals(setupFramework) ? 1 : -1;
        SwingScore.FlowAssessment flowAssessment = SwingScore.assessFlowPanel(
                flow, new SwingScore.FlowOptions(setupDirection, text(audit, "coverage")));
        int flowAlignedRows = flowAssessment.aligned_rows();
        int flowOpposingRows = flowAssessment.opposing_rows();
        boolean flowComplete = flowAssessment.eligible_for_entry();
        double expectedFlowLeg = flowAssessment.score();
        if (!numericStrictEquals(field(legs, "flow"), expectedFlowLeg)) {
            errors.add("flow leg must equal audited two-horizon score " + jsNumber(expectedFlowLeg));
        }
        if ("COMPLETE".equals(text(audit, "coverage")) && !flowComplete) {
            errors.add("COMPLETE audit coverage requires an error-free five-row 24h/3d completed-4h flow panel");
        }
        if (!flowComplete && !hasActiveVeto(vetoes, "FLOW_COVERAGE")) {
            errors.add("incomplete flow requires active FLOW_COVERAGE veto");
        }
        if (flowOpposingRows > 0 && !hasActiveVeto(vetoes, "OPPOSING_FLOW")) {
            errors.add("opposing two-horizon flow requires active OPPOSING_FLOW veto");
        }

        if (entryAuthorized) {
            if (!flowComplete) {
                errors.add("entry authorization requires COMPLETE, error-free two-horizon flow coverage");
            }
            boolean freshTrigger = "VALID".equals(text(trigger, "status"))
                    && "4h".equals(text(trigger, "timeframe"))
                    && field(trigger, "completed_bar_required").isBoolean()
                    && field(trigger, "completed_bar_required").booleanValue()
                    && !(field(trigger, "completed_bar").isBoolean() && !field(trigger, "completed_bar").booleanValue())
                    && jsNumberConversion(field(trigger, "window_bars")) <= 2
                    && (field(trigger, "age_bars").isNull() || field(trigger, "age_bars").isMissingNode()
                    || jsNumberConversion(field(trigger, "age_bars")) <= jsNumberConversion(field(trigger, "window_bars")));
            if (!freshTrigger) {
                errors.add("entry authorization requires a VALID fresh completed 4h trigger within two bars");
            }
            if (!activeVetoes.isEmpty()) {
                errors.add("entry authorization requires no active veto");
            }
            if (!"AVAILABLE".equals(text(riskBudget, "status"))) {
                errors.add("entry authorization requires AVAILABLE risk budget");
            }
            JsonNode mechanical = field(setup, "mechanical_score");
            JsonNode phaseThreshold = field(setup, "phase_threshold");
            if (!finite(mechanical) || phaseThreshold.isNull() || phaseThreshold.isMissingNode()
                    || mechanical.doubleValue() < phaseThreshold.doubleValue()) {
                errors.add("entry authorization requires mechanical score at the pinned phase threshold");
            }
            if (!numericStrictEquals(field(legs, "flow"), flowAlignedRows)) {
                errors.add("flow leg must equal directionally aligned two-horizon rows ("
                        + flowAlignedRows + "), never observed-row count");
            }
            if (flowOpposingRows > 0) {
                errors.add("opposing two-horizon flow blocks authorization");
            }
        }

        if ("AVAILABLE".equals(text(riskBudget, "status"))) {
            boolean finiteRisk = List.of("equity_usd", "stop_distance_pct", "phase_cap_pct", "notional_usd")
                    .stream().allMatch(key -> finite(field(riskBudget, key)));
            if (!finiteRisk) {
                errors.add("AVAILABLE risk budget requires equity, stop distance, phase cap, and notional");
            } else {
                double equity = field(riskBudget, "equity_usd").doubleValue();
                double stopFraction = field(riskBudget, "stop_distance_pct").doubleValue() / 100;
                if (!(equity > 0 && stopFraction > 0)) {
                    errors.add("AVAILABLE risk budget requires positive equity and stop distance");
                }
                String phase = text(setup, "phase");
                Integer phaseCap = phaseCaps(setupFramework).get(phase);
                if (entryAuthorized && phaseCap != null
                        && !numericStrictEquals(field(riskBudget, "phase_cap_pct"), phaseCap)) {
                    errors.add("risk phase cap must equal pinned " + phaseCap + "% for " + phase);
                }
                double cap = phaseCap == null ? field(riskBudget, "phase_cap_pct").doubleValue() : phaseCap;
                double expected = Math.min(
                        equity * (cap / 100),
                        Math.min(equity * 0.015 / stopFraction, equity * 0.03 / stopFraction));
                if (Double.isFinite(expected)
                        && Math.abs(field(riskBudget, "notional_usd").doubleValue() - Math.max(0, expected))
                        > Math.max(0.01, Math.abs(expected) * 1e-9)) {
                    errors.add("risk budget notional does not recompute from equity, stop, and caps");
                }
            }
        }

        JsonNode plan = object(report, "trade_plan");
        if (entryAuthorized) {
            String phase = text(setup, "phase");
            Integer expectedClock = CLOCK_DAYS.get(phase);
            String expectedDirection = "fallen_knives".equals(setupFramework) ? "LONG" : "SHORT";
            if (!"AUTHORIZED".equals(text(plan, "status"))
                    || !expectedDirection.equals(text(plan, "direction"))) {
                errors.add("authorized entry requires an authorized, direction-matched trade plan");
            }
            if (expectedClock == null || !numericStrictEquals(field(plan, "clock_days"), expectedClock)) {
                errors.add("trade plan clock must be " + (expectedClock == null ? "undefined" : expectedClock)
                        + " days for " + phase);
            }
            if (!truthy(field(plan, "entry")) || !truthy(field(plan, "stop"))) {
                errors.add("authorized trade plan requires entry and stop");
            }
            ArrayNode targets = array(plan, "targets");
            boolean targetsInvalid = targets.size() != 3;
            int[] shares = {40, 40, 20};
            for (int index = 0; !targetsInvalid && index < targets.size(); index++) {
                JsonNode target = targets.get(index);
                if (!numericStrictEquals(field(target, "r"), index + 1)
                        || !numericStrictEquals(field(target, "share_pct"), shares[index])) {
                    targetsInvalid = true;
                }
            }
            if (!targetsInvalid && !truthy(field(targets.get(2), "trailing"))) {
                targetsInvalid = true;
            }
            if (targetsInvalid) {
                errors.add("trade plan targets must be 1R/2R/trail at 40/40/20");
            }
            long reportAt = parseDateMillis(field(timestamps, "report_at"));
            long timeStop = parseDateMillis(field(plan, "time_stop"));
            if (!truthy(field(plan, "time_stop")) || timeStop <= reportAt) {
                errors.add("authorized trade plan requires a future time stop");
            }

            JsonNode entry = field(plan, "entry");
            JsonNode stop = field(plan, "stop");
            if ("fallen_knives".equals(setupFramework)) {
                String mode = upper(text(stop, "mode"));
                if (!Set.of("TACTICAL", "DEEP_COMPOUND").contains(mode)) {
                    errors.add("Fallen Knives stop must declare TACTICAL or DEEP_COMPOUND mode");
                }
                if ("TACTICAL".equals(mode)) {
                    JsonNode tactical = object(stop, "tactical");
                    double atr = jsNumberConversion(field(tactical, "atr"));
                    double invalidation = jsNumberConversion(field(tactical, "invalidation_price"));
                    double buffer = jsNumberConversion(field(tactical, "buffer_atr"));
                    double distanceAtr = jsNumberConversion(field(tactical, "distance_atr"));
                    if (!Double.isFinite(atr) || !Double.isFinite(invalidation)
                            || Double.compare(buffer, 0.25) != 0 || distanceAtr < 1) {
                        errors.add("Fallen Knives tactical stop requires ATR, invalidation, 0.25 ATR buffer, and at least 1 ATR distance");
                    }
                    double distancePct = jsNumberConversion(field(stop, "distance_pct"));
                    if (!Double.isFinite(distancePct) || distancePct > 15) {
                        errors.add("Fallen Knives tactical stop must be no more than 15% from entry");
                    }
                }
                if ("DEEP_COMPOUND".equals(mode)) {
                    JsonNode compound = object(stop, "compound");
                    if (!field(compound, "slow_anchor").isBoolean() || !field(compound, "slow_anchor").booleanValue()
                            || !field(compound, "extreme_fear").isBoolean() || !field(compound, "extreme_fear").booleanValue()
                            || !field(compound, "deleveraging").isBoolean() || !field(compound, "deleveraging").booleanValue()) {
                        errors.add("Fallen Knives deep-compound stop requires slow anchor, extreme fear, and deleveraging");
                    }
                }
                double entryPrice = jsNumberConversion(field(entry, "price"));
                double stopPrice = jsNumberConversion(field(stop, "price"));
                if (!Double.isFinite(entryPrice) || !Double.isFinite(stopPrice) || stopPrice >= entryPrice) {
                    errors.add("authorized Fallen Knives plan requires a numeric long entry and lower stop");
                }
            } else {
                JsonNode ratchet = field(plan, "ratchet");
                if (!truthy(ratchet) || !field(ratchet, "can_loosen").isBoolean()
                        || field(ratchet, "can_loosen").booleanValue()
                        || !truthy(field(ratchet, "after_t1")) || !truthy(field(ratchet, "after_t2"))) {
                    errors.add("Flying Rocket trade plan requires a non-loosening T1/T2 ratchet");
                }
                JsonNode carry = field(plan, "carry");
                if (!truthy(carry) || !"PASS".equals(text(carry, "status"))
                        || !field(carry, "veto_active").isBoolean() || field(carry, "veto_active").booleanValue()) {
                    errors.add("Flying Rocket trade plan requires explicit passing carry controls");
                }
                double entryPrice = jsNumberConversion(field(entry, "price"));
                double stopPrice = jsNumberConversion(field(stop, "price"));
                if (!Double.isFinite(entryPrice) || !Double.isFinite(stopPrice) || stopPrice <= entryPrice) {
                    errors.add("authorized Flying Rocket plan requires a numeric short entry and higher stop");
                }
                if ("B".equals(text(setup, "channel")) && "3".equals(phase)) {
                    errors.add("Flying Rocket Channel B cannot authorize Phase 3");
                }
                if ("B".equals(text(setup, "channel"))) {
                    JsonNode constraints = field(riskBudget, "constraints");
                    JsonNode bookPct = !field(constraints, "book_pct").isNull()
                            && !field(constraints, "book_pct").isMissingNode()
                            ? field(constraints, "book_pct") : field(riskBudget, "book_pct");
                    double book = jsNumberConversion(bookPct);
                    if (!Double.isFinite(book) || book > 30) {
                        errors.add("Flying Rocket Channel B requires an explicit short-book percentage at or below 30%");
                    }
                }
                if ("B".equals(text(setup, "channel")) && hasActiveVeto(vetoes, "FUNDING")) {
                    errors.add("Flying Rocket Channel B funding veto blocks authorization");
                }
            }

            double expectedTimeStop = expectedClock == null || reportAt == Long.MIN_VALUE
                    ? Double.NaN : reportAt + expectedClock * 86_400_000d;
            if (!dateStrictEquals(timeStop, expectedTimeStop)) {
                errors.add("time stop must equal the " + (expectedClock == null ? "undefined" : expectedClock)
                        + "-day phase clock from report_at");
            }
        }
        if (!"PASS".equals(text(audit, "lint"))) {
            errors.add("audit.lint must be PASS before publication");
        }
        return new SemanticIssues(errors, warnings);
    }

    private static boolean hasActiveVeto(JsonNode vetoes, String code) {
        if (vetoes == null || !vetoes.isArray()) {
            return false;
        }
        for (JsonNode veto : vetoes) {
            if (code.equals(text(veto, "code")) && truthy(field(veto, "active"))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Integer> thresholds(String framework, String channel) {
        if ("fallen_knives".equals(framework)) {
            return SwingScore.PHASE_THRESHOLDS.fallen_knives();
        }
        return "B".equals(channel)
                ? SwingScore.PHASE_THRESHOLDS.flying_rocket().B()
                : SwingScore.PHASE_THRESHOLDS.flying_rocket().A();
    }

    private static Map<String, Integer> phaseCaps(String framework) {
        if ("fallen_knives".equals(framework)) {
            return SwingScore.PHASE_CAPS_PCT.fallen_knives();
        }
        if ("flying_rocket".equals(framework)) {
            return SwingScore.PHASE_CAPS_PCT.flying_rocket();
        }
        return Map.of();
    }

    private static boolean numericStrictEquals(JsonNode value, double expected) {
        return finite(value) && value.doubleValue() == expected;
    }

    private static boolean dateStrictEquals(long parsed, double expected) {
        return parsed != Long.MIN_VALUE && Double.isFinite(expected) && Double.compare(parsed, expected) == 0;
    }

    private static boolean matchesSha256(JsonNode value) {
        String text = text(value);
        return text != null && text.matches("^[0-9a-f]{64}$");
    }

    /** JavaScript truthiness for the JSON-domain values accepted by this contract. */
    private static boolean truthy(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            return value.doubleValue() != 0 && !Double.isNaN(value.doubleValue());
        }
        if (value.isTextual()) {
            return !value.textValue().isEmpty();
        }
        return true;
    }

    private static String jsValue(JsonNode value) {
        if (value == null || value.isMissingNode()) {
            return "undefined";
        }
        if (value.isNull()) {
            return "null";
        }
        if (value.isNumber()) {
            return jsNumber(value.doubleValue());
        }
        if (value.isTextual()) {
            return value.textValue();
        }
        if (value.isBoolean()) {
            return Boolean.toString(value.booleanValue());
        }
        return value.toString();
    }

    @SafeVarargs
    private static <K, V> Map<K, V> orderedMap(Map.Entry<K, V>... entries) {
        Map<K, V> result = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : entries) {
            result.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }
}

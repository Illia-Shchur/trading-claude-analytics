package com.tradinganalytics.core.swing;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pure Java 21 port of {@code tools/swing-score.mjs} ({@code swing-score/1}).
 *
 * <p>The API deliberately consumes normalized observations and performs no I/O.
 * Dynamic market-flow values remain {@link JsonNode}s because the JavaScript
 * contract accepts both numeric signs and textual directions. All produced
 * contract objects are immutable records whose component names match the JSON
 * keys emitted by the JavaScript implementation.</p>
 */
public final class SwingScore {

    public static final String SWING_SCORE_VERSION = "swing-score/1";
    public static final HorizonDays SWING_HORIZON_DAYS = new HorizonDays(3, 30);

    public static final List<String> FLOW_PANEL_ROWS = List.of(
            "spot_cvd",
            "futures_bid_ask_delta",
            "futures_cvd",
            "open_interest",
            "oi_weighted_funding"
    );

    public static final List<String> FLOW_EVIDENCE_FAMILIES = List.of(
            "spot_cvd",
            "futures_taker_flow",
            "open_interest",
            "oi_weighted_funding"
    );

    public static final Map<String, Integer> SCORE_MAXES = immutableOrderedMap(
            Map.entry("flow", 5),
            Map.entry("technical", 4),
            Map.entry("macro", 3),
            Map.entry("sentiment", 3),
            Map.entry("valuation", 3),
            Map.entry("structure", 2)
    );

    public static final Map<String, ComponentMax> LEG_COMPONENT_MAXES = immutableOrderedMap(
            Map.entry("technical", new ComponentMax(2.0, 2.0)),
            Map.entry("macro", new ComponentMax(1.5, 1.5)),
            Map.entry("sentiment", new ComponentMax(1.5, 1.5)),
            Map.entry("valuation", new ComponentMax(2.0, 1.0)),
            Map.entry("structure", new ComponentMax(1.0, 1.0))
    );

    public static final PhaseThresholdConstants PHASE_THRESHOLDS = phaseThresholdConstants();
    public static final PhaseCapConstants PHASE_CAPS_PCT = phaseCapConstants();

    private static final Pattern POSITIVE_FLOW = Pattern.compile("positive|up|buy|rising|increase|absorb");
    private static final Pattern NEGATIVE_FLOW = Pattern.compile("negative|down|sell|fall|decrease|build");
    private static final Pattern ALIGNED = Pattern.compile("aligned|favourable|favorable|confirm");
    private static final Pattern OPPOSING = Pattern.compile("opposing|adverse|diverg");
    private static final Pattern NEUTRAL = Pattern.compile("neutral|flat|mixed");
    private static final DateTimeFormatter JS_ISO_MILLIS = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private SwingScore() {
    }

    /** JavaScript-compatible {@code Math.round(value * 2) / 2}. */
    public static double roundHalf(double value) {
        if (!Double.isFinite(value)) {
            throw new SwingTypeException("swing score requires finite numeric inputs");
        }
        return half(value);
    }

    /**
     * Normalizes the six bounded score legs. Missing and null values become
     * zero; values are range-checked before half-point rounding, as in JS.
     */
    public static Map<String, Double> normalizeLegs(Map<String, ?> legs) {
        Map<String, ?> input = legs == null ? Map.of() : legs;
        LinkedHashMap<String, Double> result = new LinkedHashMap<>();
        SCORE_MAXES.forEach((name, max) -> {
            Object candidate = input.get(name);
            double value = candidate == null ? 0.0 : requireFiniteNumber(candidate, "legs." + name + " must be finite");
            if (value < 0.0 || value > max) {
                throw new SwingRangeException("legs." + name + " must be between 0 and " + max);
            }
            result.put(name, half(value));
        });
        return Collections.unmodifiableMap(result);
    }

    /**
     * Normalizes the state/impulse decomposition of all non-flow legs.
     * Bounds are checked after half-point rounding, matching the source.
     */
    public static Map<String, LegComponent> normalizeLegComponents(
            Map<String, LegComponentInput> components) {
        Map<String, LegComponentInput> input = components == null ? Map.of() : components;
        LinkedHashMap<String, LegComponent> result = new LinkedHashMap<>();
        LEG_COMPONENT_MAXES.forEach((name, maxima) -> {
            LegComponentInput component = input.get(name);
            double state = roundHalf(component == null || component.state() == null ? 0.0 : component.state());
            double impulse = roundHalf(component == null || component.impulse() == null ? 0.0 : component.impulse());
            if (state < 0.0 || state > maxima.state()) {
                throw new SwingRangeException(name + ".state must be between 0 and " + jsNumber(maxima.state()));
            }
            if (impulse < 0.0 || impulse > maxima.impulse()) {
                throw new SwingRangeException(name + ".impulse must be between 0 and " + jsNumber(maxima.impulse()));
            }
            result.put(name, new LegComponent(
                    state,
                    impulse,
                    roundHalf(state + impulse),
                    maxima.state() + maxima.impulse()
            ));
        });
        return Collections.unmodifiableMap(result);
    }

    public static ScoreResult scoreSwing() {
        return scoreSwing(new ScoreInput(Map.of(), null, 0.0, 0.0));
    }

    public static ScoreResult scoreSwing(ScoreInput input) {
        ScoreInput source = input == null ? new ScoreInput(Map.of(), null, 0.0, 0.0) : input;
        Map<String, LegComponent> normalizedComponents = source.components() == null
                ? null
                : normalizeLegComponents(source.components());

        LinkedHashMap<String, Object> mergedLegs = new LinkedHashMap<>();
        if (source.legs() != null) {
            mergedLegs.putAll(source.legs());
        }
        if (normalizedComponents != null) {
            normalizedComponents.forEach((name, value) -> mergedLegs.put(name, value.total()));
        }
        Map<String, Double> normalized = normalizeLegs(mergedLegs);

        Object discretionInput = source.discretion() == null ? 0.0 : source.discretion();
        if (!finiteNumber(discretionInput)) {
            throw new SwingRangeException("discretion must be a half-point in the range -1..1");
        }
        double discretion = ((Number) discretionInput).doubleValue();
        if (discretion < -1.0 || discretion > 1.0
                || Math.abs(discretion * 2.0 - jsMathRound(discretion * 2.0)) > 1e-9) {
            throw new SwingRangeException("discretion must be a half-point in the range -1..1");
        }

        Object impulseInput = source.impulse() == null ? 0.0 : source.impulse();
        double impulse = requireFiniteNumber(impulseInput, "impulse must be finite");

        double sum = normalized.values().stream().mapToDouble(Double::doubleValue).sum();
        double mechanical = clamp(half(sum), 0.0, 20.0);
        double adjusted = clamp(half(mechanical + discretion), 0.0, 20.0);
        return new ScoreResult(
                SWING_SCORE_VERSION,
                normalized,
                normalizedComponents,
                half(impulse),
                half(discretion),
                mechanical,
                adjusted,
                half(mechanical + discretion),
                20
        );
    }

    public static FlowAssessment assessFlowPanel(JsonNode panel) {
        return assessFlowPanel(panel, null);
    }

    /** Audits the five completed-bar flow rows and returns one bounded leg. */
    public static FlowAssessment assessFlowPanel(JsonNode panel, FlowOptions options) {
        JsonNode source = isObject(panel) ? panel : MissingNode.getInstance();
        double direction = options == null || options.direction() == null ? 1.0 : options.direction();
        int sign = direction >= 0.0 ? 1 : -1;
        String coverage = resolveCoverage(source, options);

        List<FlowRow> rows = FLOW_PANEL_ROWS.stream()
                .map(name -> assessFlowRow(name, property(source, name), sign))
                .toList();

        JsonNode intervalInput = nullishProperty(source, "interval_hours", "intervalHours");
        double interval = jsNumber(intervalInput);
        JsonNode errorsNode = property(source, "errors");
        int errorCount = errorsNode.isArray() ? errorsNode.size() : 0;
        JsonNode completedNode = property(source, "completed_through");
        boolean hasCompletedString = completedNode.isTextual();
        boolean complete = "COMPLETE".equals(coverage.toUpperCase(Locale.ROOT))
                && interval == 4.0
                && hasCompletedString
                && errorCount == 0
                && rows.stream().allMatch(row -> row.available() && row.state() != null && row.impulse() != null);

        int alignedRows = (int) rows.stream().filter(FlowRow::aligned).count();
        int opposingRows = (int) rows.stream().filter(FlowRow::opposing).count();
        Map<String, FlowRow> byName = new LinkedHashMap<>();
        rows.forEach(row -> byName.put(row.name(), row));

        List<EvidenceFamily> evidenceFamilies = List.of(
                evidenceFamily("spot_cvd", List.of("spot_cvd"), byName),
                evidenceFamily("futures_taker_flow", List.of("futures_bid_ask_delta", "futures_cvd"), byName),
                evidenceFamily("open_interest", List.of("open_interest"), byName),
                evidenceFamily("oi_weighted_funding", List.of("oi_weighted_funding"), byName)
        );
        int alignedEvidence = (int) evidenceFamilies.stream().filter(EvidenceFamily::aligned).count();
        int opposingEvidence = (int) evidenceFamilies.stream().filter(EvidenceFamily::opposing).count();
        double evidenceScore = alignedEvidence * (SCORE_MAXES.get("flow").doubleValue() / FLOW_EVIDENCE_FAMILIES.size());
        String completedThrough = hasCompletedString && !completedNode.textValue().isEmpty()
                ? completedNode.textValue()
                : null;

        return new FlowAssessment(
                SWING_SCORE_VERSION,
                coverage.toUpperCase(Locale.ROOT),
                complete ? "COMPLETE" : "PARTIAL",
                Double.isFinite(interval) ? interval : null,
                completedThrough,
                rows,
                alignedRows,
                opposingRows,
                evidenceFamilies,
                alignedEvidence,
                opposingEvidence,
                complete,
                complete,
                half(complete ? evidenceScore : Math.min(evidenceScore, 2.5)),
                complete ? null : "requires error-free completed 4h bars with 24h and 3d directions for all five rows"
        );
    }

    public static double flowLegFromPanel(JsonNode panel) {
        return assessFlowPanel(panel).score();
    }

    public static double flowLegFromPanel(JsonNode panel, FlowOptions options) {
        return assessFlowPanel(panel, options).score();
    }

    public static Map<String, Integer> phaseThresholds(String framework) {
        return phaseThresholds(framework, "A");
    }

    public static Map<String, Integer> phaseThresholds(String framework, String channel) {
        if ("fallen_knives".equals(framework)) {
            return immutableCopy(PHASE_THRESHOLDS.fallen_knives());
        }
        return immutableCopy("B".equals(channel)
                ? PHASE_THRESHOLDS.flying_rocket().B()
                : PHASE_THRESHOLDS.flying_rocket().A());
    }

    public static Map<String, Integer> phaseCaps(String framework) {
        return phaseCaps(framework, "A");
    }

    public static Map<String, Integer> phaseCaps(String framework, String channel) {
        Map<String, Integer> source = switch (String.valueOf(framework)) {
            case "fallen_knives" -> PHASE_CAPS_PCT.fallen_knives();
            case "flying_rocket" -> PHASE_CAPS_PCT.flying_rocket();
            default -> null;
        };
        LinkedHashMap<String, Integer> caps = source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
        if ("flying_rocket".equals(framework) && "B".equals(channel)) {
            caps.remove("3");
        }
        return Collections.unmodifiableMap(caps);
    }

    public static ActivePhaseResult activePhase(ActivePhaseInput input) {
        ActivePhaseInput source = input == null
                ? new ActivePhaseInput(null, "A", null, null, null, List.of())
                : input;
        String channel = source.channel() == null ? "A" : source.channel();
        Integer threshold = phaseThresholds(source.framework(), channel).get(source.phase());
        List<?> suppliedVetoes = source.vetoes() == null ? List.of() : source.vetoes();
        List<Object> activeVetoes = suppliedVetoes.stream()
                .filter(Objects::nonNull)
                .filter(SwingScore::isActiveVeto)
                .map(value -> (Object) value)
                .toList();

        Double scoreValue = source.score() == null ? null : source.score().mechanical();
        boolean scorePass = scoreValue != null && Double.isFinite(scoreValue)
                && threshold != null && scoreValue >= threshold;
        TriggerWindow trigger = source.trigger();
        boolean triggerPass = trigger != null
                && "VALID".equals(trigger.status())
                && "4h".equals(trigger.timeframe())
                && trigger.completed_bar_required()
                && trigger.completed_bar()
                && Double.isFinite(trigger.window_bars())
                && trigger.window_bars() >= 1.0
                && trigger.window_bars() <= 2.0
                && (trigger.age_bars() == null
                    || jsNumber(trigger.age_bars()) <= trigger.window_bars());

        boolean vetoPass = activeVetoes.isEmpty();
        return new ActivePhaseResult(
                source.phase(),
                threshold,
                scoreValue,
                scorePass,
                triggerPass,
                vetoPass,
                scorePass && triggerPass && vetoPass,
                activeVetoes
        );
    }

    public static Veto veto(String code, boolean active) {
        return veto(code, active, "");
    }

    public static Veto veto(String code, boolean active, String reason) {
        return new Veto(String.valueOf(code), active, reason == null ? "" : reason);
    }

    public static List<Veto> hardVetoes() {
        return hardVetoes(new HardVetoInput(null, false, false, false, false, false, false, false));
    }

    public static List<Veto> hardVetoes(HardVetoInput input) {
        HardVetoInput source = input == null
                ? new HardVetoInput(null, false, false, false, false, false, false, false)
                : input;
        String coverage = source.coverage() == null ? "COMPLETE" : source.coverage();
        return List.of(
                veto("FLOW_COVERAGE", !"COMPLETE".equals(coverage),
                        "Flow coverage is incomplete or not common across required horizons."),
                veto("OPPOSING_FLOW", source.flowOpposes(),
                        "Two-horizon flow points against the proposed setup."),
                veto("REGIME_MISMATCH", source.regimeMismatch(),
                        "The setup does not match the prevailing macro/technical regime."),
                veto("RISK_BUDGET", source.riskBudgetExhausted(),
                        "Portfolio or asset risk budget is exhausted."),
                veto("NARRATIVE_EXIT", source.narrativeExit(),
                        "A live narrative or position-exit condition is active."),
                veto("CARRY", source.carryVeto(),
                        "Carry cost is outside the permitted edge."),
                veto("FUNDING", source.fundingVeto(),
                        "Funding/carry veto is active for this setup."),
                veto("MACRO_SHOCK", source.macroShock(),
                        "A multi-family macro shock is at the extreme rolling percentile.")
        );
    }

    public static TriggerWindow triggerWindow() {
        return triggerWindow(new TriggerInput(null, false, null, null, null, null, null));
    }

    public static TriggerWindow triggerWindow(TriggerInput input) {
        TriggerInput source = input == null
                ? new TriggerInput(null, false, null, null, null, null, null)
                : input;
        String timeframe = source.timeframe() == null ? "4h" : source.timeframe();
        boolean completedBar = !Boolean.FALSE.equals(source.completedBar());
        double barsNumber = source.bars() == null ? 2.0 : jsNumber(source.bars());
        double windowBars = clamp(jsTruthyNumber(barsNumber) ? barsNumber : 2.0, 1.0, 2.0);

        String expiresAt = null;
        if (source.createdAt() != null && !source.createdAt().isEmpty()) {
            Instant created = parseJsDate(source.createdAt());
            if (created != null) {
                long addedMillis = (long) (windowBars * 4.0 * 3_600_000.0);
                Instant expiry = created.truncatedTo(ChronoUnit.MILLIS).plusMillis(addedMillis);
                String formatted = JS_ISO_MILLIS.format(expiry);
                expiresAt = formatted.endsWith(".000Z")
                        ? formatted.substring(0, formatted.length() - 5) + "Z"
                        : formatted;
            }
        }

        Object ageBars = source.ageBars();
        double ageNumber = ageBars == null ? Double.NaN : jsNumber(ageBars);
        boolean fresh = ageBars == null || Double.isFinite(ageNumber) && ageNumber <= windowBars;
        String status = source.valid() && completedBar && fresh
                ? "VALID"
                : source.valid() && !fresh ? "EXPIRED" : "WAIT";

        return new TriggerWindow(
                status,
                timeframe,
                true,
                completedBar,
                source.level(),
                source.createdAt(),
                expiresAt,
                windowBars,
                ageBars
        );
    }

    public static RiskBudgetResult riskBudget(RiskBudgetInput input) {
        RiskBudgetInput source = input == null
                ? new RiskBudgetInput(null, null, null, null, null)
                : input;
        if (!finiteNumber(source.equityUsd())
                || !finiteNumber(source.stopDistancePct())
                || !finiteNumber(source.phaseCapPct())
                || source.equityUsd().doubleValue() <= 0.0
                || source.stopDistancePct().doubleValue() <= 0.0
                || source.phaseCapPct().doubleValue() < 0.0) {
            return new DataLimitedRiskBudget(
                    "DATA_LIMITED",
                    null,
                    "portfolio equity and a valid stop are required"
            );
        }

        double equity = source.equityUsd().doubleValue();
        double stopDistance = source.stopDistancePct().doubleValue();
        double phaseCap = source.phaseCapPct().doubleValue();
        double remainingAssetRisk = source.remainingAssetRiskPct() == null
                ? 3.0 : source.remainingAssetRiskPct().doubleValue();
        double remainingPortfolioRisk = source.remainingPortfolioRiskPct() == null
                ? 1.5 : source.remainingPortfolioRiskPct().doubleValue();
        double stopFraction = stopDistance / 100.0;
        double byPortfolioRisk = equity * (remainingPortfolioRisk / 100.0) / stopFraction;
        double byAssetRisk = equity * (remainingAssetRisk / 100.0) / stopFraction;
        double cap = equity * (phaseCap / 100.0);
        double notional = Math.max(0.0, Math.min(cap, Math.min(byPortfolioRisk, byAssetRisk)));

        return new AvailableRiskBudget(
                "AVAILABLE",
                equity,
                stopDistance,
                phaseCap,
                notional,
                new RiskConstraints(cap, byPortfolioRisk, byAssetRisk)
        );
    }

    public static ExpectancyResult expectancyR() {
        return expectancyR(new ExpectancyInput(0.0, 0.0, 0.0, 0.0, 0.0));
    }

    public static ExpectancyResult expectancyR(ExpectancyInput input) {
        ExpectancyInput source = input == null
                ? new ExpectancyInput(0.0, 0.0, 0.0, 0.0, 0.0)
                : input;
        double winProbability = expectancyValue(source.winProbability());
        double avgWinR = expectancyValue(source.avgWinR());
        double lossProbability = expectancyValue(source.lossProbability());
        double avgLossR = expectancyValue(source.avgLossR());
        double costsR = expectancyValue(source.costsR());
        return new ExpectancyResult(
                winProbability,
                avgWinR,
                lossProbability,
                avgLossR,
                costsR,
                winProbability * avgWinR - lossProbability * avgLossR - costsR
        );
    }

    public static SetupSummary setupSummary(SetupSummaryInput input) {
        SetupSummaryInput source = input == null
                ? new SetupSummaryInput(null, null, null, null, null, List.of())
                : input;
        List<Veto> vetoes = source.vetoes() == null ? List.of() : source.vetoes();
        boolean activeVeto = vetoes.stream().filter(Objects::nonNull).anyMatch(Veto::active);
        return new SetupSummary(
                source.framework(),
                source.channel(),
                SWING_HORIZON_DAYS,
                source.score() == null ? null : source.score().adjusted(),
                source.score() == null ? null : source.score().mechanical(),
                source.phase(),
                source.trigger() == null || source.trigger().status() == null
                        || source.trigger().status().isEmpty() ? "WAIT" : source.trigger().status(),
                activeVeto ? "VETO" : "CLEAR",
                source.phase() != null && source.phase().unlocked()
        );
    }

    private static FlowRow assessFlowRow(String name, JsonNode entry, int direction) {
        Integer state = horizonValue(entry, "24h", name, direction);
        Integer impulse = horizonValue(entry, "3d", name, direction);
        boolean available = !entry.isMissingNode() && !entry.isNull()
                && !(entry.isObject() && property(entry, "available").isBoolean()
                    && !property(entry, "available").booleanValue());
        return new FlowRow(
                name,
                state,
                impulse,
                available,
                state != null && impulse != null && state == direction && impulse == direction,
                state != null && impulse != null && state == -direction && impulse == -direction
        );
    }

    private static Integer horizonValue(JsonNode entry, String suffix, String name, int direction) {
        if (!isObject(entry)) {
            return null;
        }
        JsonNode interpreted = nullishThenFinalProperty(
                entry,
                "setup_signal_" + suffix,
                "alignment_" + suffix
        );
        if (!interpreted.isMissingNode()) {
            String text = jsString(interpreted).toLowerCase(Locale.ROOT);
            if (ALIGNED.matcher(text).find()) {
                return direction;
            }
            if (OPPOSING.matcher(text).find()) {
                return -direction;
            }
            if (NEUTRAL.matcher(text).find()) {
                return 0;
            }
            return flowSign(interpreted);
        }
        if ("open_interest".equals(name)) {
            return null;
        }
        JsonNode raw = nullishThenFinalProperty(
                entry,
                "direction_" + suffix,
                "signal_" + suffix,
                "delta_" + suffix + "_usd",
                "change_" + suffix + "_pct",
                suffix
        );
        if (raw.isMissingNode()) {
            return null;
        }
        int value = flowSign(raw);
        return "oi_weighted_funding".equals(name) ? -value : value;
    }

    private static int flowSign(JsonNode value) {
        if (value != null && value.isNumber() && Double.isFinite(value.doubleValue())) {
            double numeric = value.doubleValue();
            return numeric > 0.0 ? 1 : numeric < 0.0 ? -1 : 0;
        }
        String text = jsString(value == null || value.isNull() ? MissingNode.getInstance() : value)
                .toLowerCase(Locale.ROOT);
        if (POSITIVE_FLOW.matcher(text).find()) {
            return 1;
        }
        if (NEGATIVE_FLOW.matcher(text).find()) {
            return -1;
        }
        return 0;
    }

    private static EvidenceFamily evidenceFamily(
            String name,
            List<String> memberNames,
            Map<String, FlowRow> byName) {
        List<FlowRow> members = memberNames.stream().map(byName::get).toList();
        return new EvidenceFamily(
                name,
                memberNames,
                members.stream().allMatch(FlowRow::available),
                members.stream().allMatch(FlowRow::aligned),
                members.stream().allMatch(FlowRow::opposing)
        );
    }

    private static String resolveCoverage(JsonNode panel, FlowOptions options) {
        if (options != null && options.coverage() != null) {
            return options.coverage();
        }
        JsonNode panelCoverage = property(panel, "coverage");
        if (jsTruthy(panelCoverage)) {
            return jsString(panelCoverage);
        }
        return "COMPLETE";
    }

    private static double expectancyValue(Number value) {
        if (!finiteNumber(value)) {
            throw new SwingTypeException("expectancy inputs must be finite");
        }
        return value.doubleValue();
    }

    private static boolean finiteNumber(Object value) {
        return value instanceof Number number && Double.isFinite(number.doubleValue());
    }

    private static boolean isActiveVeto(Object value) {
        if (Boolean.TRUE.equals(value)) {
            return true;
        }
        if (value instanceof Veto veto) {
            return veto.active();
        }
        if (value instanceof JsonNode node) {
            JsonNode active = property(node, "active");
            return active.isBoolean() && active.booleanValue();
        }
        if (value instanceof Map<?, ?> map) {
            return Boolean.TRUE.equals(map.get("active"));
        }
        return false;
    }

    private static double requireFiniteNumber(Object value, String message) {
        if (!finiteNumber(value)) {
            throw new SwingTypeException(message);
        }
        return ((Number) value).doubleValue();
    }

    private static double half(double value) {
        return jsMathRound(value * 2.0) / 2.0;
    }

    /** ECMAScript Math.round, including its negative-zero tie behavior. */
    private static double jsMathRound(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value == 0.0) {
            return value;
        }
        double floor = Math.floor(value);
        double rounded = value - floor < 0.5 ? floor : floor + 1.0;
        if (rounded == 0.0 && value < 0.0) {
            return -0.0;
        }
        return rounded;
    }

    private static double clamp(double value, double low, double high) {
        return Math.min(high, Math.max(low, value));
    }

    private static JsonNode property(JsonNode object, String name) {
        if (!isObject(object)) {
            return MissingNode.getInstance();
        }
        JsonNode value = object.get(name);
        return value == null ? MissingNode.getInstance() : value;
    }

    private static JsonNode nullishProperty(JsonNode object, String... names) {
        for (String name : names) {
            JsonNode value = property(object, name);
            if (!value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return MissingNode.getInstance();
    }

    /** Models {@code a ?? b ?? finalProperty}, where the final null is retained. */
    private static JsonNode nullishThenFinalProperty(JsonNode object, String... names) {
        for (int i = 0; i < names.length - 1; i++) {
            JsonNode value = property(object, names[i]);
            if (!value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return property(object, names[names.length - 1]);
    }

    private static boolean isObject(JsonNode node) {
        return node != null && node.isObject();
    }

    private static boolean jsTruthy(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            double number = value.doubleValue();
            return number != 0.0 && !Double.isNaN(number);
        }
        if (value.isTextual()) {
            return !value.textValue().isEmpty();
        }
        return true;
    }

    private static boolean jsTruthyNumber(double value) {
        return value != 0.0 && !Double.isNaN(value);
    }

    /** A focused implementation of JavaScript Number() for JSON values. */
    private static double jsNumber(Object value) {
        if (value == null || value instanceof MissingNode) {
            return Double.NaN;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1.0 : 0.0;
        }
        if (value instanceof JsonNode node) {
            if (node.isNull()) {
                return 0.0;
            }
            if (node.isNumber()) {
                return node.doubleValue();
            }
            if (node.isBoolean()) {
                return node.booleanValue() ? 1.0 : 0.0;
            }
            if (node.isTextual()) {
                return parseJsNumber(node.textValue());
            }
            if (node.isArray()) {
                if (node.isEmpty()) {
                    return 0.0;
                }
                return node.size() == 1 ? parseJsNumber(jsString(node.get(0))) : Double.NaN;
            }
            return Double.NaN;
        }
        if (value instanceof CharSequence text) {
            return parseJsNumber(text.toString());
        }
        return Double.NaN;
    }

    private static double parseJsNumber(String raw) {
        String text = raw.trim();
        if (text.isEmpty()) {
            return 0.0;
        }
        if ("Infinity".equals(text) || "+Infinity".equals(text)) {
            return Double.POSITIVE_INFINITY;
        }
        if ("-Infinity".equals(text)) {
            return Double.NEGATIVE_INFINITY;
        }
        try {
            if (text.matches("0[xX][0-9a-fA-F]+")) {
                return new BigDecimal(new java.math.BigInteger(text.substring(2), 16)).doubleValue();
            }
            if (text.matches("0[bB][01]+")) {
                return new BigDecimal(new java.math.BigInteger(text.substring(2), 2)).doubleValue();
            }
            if (text.matches("0[oO][0-7]+")) {
                return new BigDecimal(new java.math.BigInteger(text.substring(2), 8)).doubleValue();
            }
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    private static String jsString(JsonNode value) {
        if (value == null || value.isMissingNode()) {
            return "";
        }
        if (value.isNull()) {
            return "null";
        }
        if (value.isTextual()) {
            return value.textValue();
        }
        if (value.isBoolean()) {
            return Boolean.toString(value.booleanValue());
        }
        if (value.isNumber()) {
            return jsNumber(value.doubleValue());
        }
        if (value.isArray()) {
            List<String> pieces = new ArrayList<>();
            value.forEach(element -> pieces.add(element.isNull() ? "" : jsString(element)));
            return String.join(",", pieces);
        }
        return "[object Object]";
    }

    private static String jsNumber(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (value == Double.POSITIVE_INFINITY) {
            return "Infinity";
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-Infinity";
        }
        if (value == 0.0) {
            return "0";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e21) {
            return BigDecimal.valueOf(value).toBigInteger().toString();
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toString().replace("E+", "e+").replace("E-", "e-");
    }

    private static Instant parseJsDate(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeException ignored) {
            // Date.parse also accepts an ISO offset and a bare ISO date.
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeException ignored) {
            // Continue to the date-only form.
        }
        try {
            return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    @SafeVarargs
    private static <K, V> Map<K, V> immutableOrderedMap(Map.Entry<K, V>... entries) {
        LinkedHashMap<K, V> map = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : entries) {
            map.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(map);
    }

    private static <K, V> Map<K, V> immutableCopy(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static PhaseThresholdConstants phaseThresholdConstants() {
        return new PhaseThresholdConstants(
                immutableOrderedMap(
                        Map.entry("1A", 8), Map.entry("1B", 11), Map.entry("2", 15), Map.entry("3", 17)),
                new FlyingRocketThresholds(
                        immutableOrderedMap(
                                Map.entry("1A", 11), Map.entry("1B", 13), Map.entry("2", 15), Map.entry("3", 19)),
                        immutableOrderedMap(
                                Map.entry("1A", 13), Map.entry("1B", 15), Map.entry("2", 17)))
        );
    }

    private static PhaseCapConstants phaseCapConstants() {
        return new PhaseCapConstants(
                immutableOrderedMap(
                        Map.entry("1A", 10), Map.entry("1B", 15), Map.entry("2", 30), Map.entry("3", 45)),
                immutableOrderedMap(
                        Map.entry("1A", 5), Map.entry("1B", 10), Map.entry("2", 15), Map.entry("3", 20))
        );
    }

    public record HorizonDays(int min, int max) {
    }

    public record ComponentMax(double state, double impulse) {
    }

    public record PhaseThresholdConstants(
            Map<String, Integer> fallen_knives,
            FlyingRocketThresholds flying_rocket) {
    }

    public record FlyingRocketThresholds(Map<String, Integer> A, Map<String, Integer> B) {
    }

    public record PhaseCapConstants(
            Map<String, Integer> fallen_knives,
            Map<String, Integer> flying_rocket) {
    }

    public record LegComponentInput(Double state, Double impulse) {
    }

    public record LegComponent(double state, double impulse, double total, double max) {
    }

    public record ScoreInput(
            Map<String, ?> legs,
            Map<String, LegComponentInput> components,
            Object discretion,
            Object impulse) {
    }

    public record ScoreResult(
            String version,
            Map<String, Double> legs,
            @JsonInclude(JsonInclude.Include.ALWAYS) Map<String, LegComponent> leg_components,
            double impulse,
            double discretion,
            double mechanical,
            double adjusted,
            double raw,
            int max) {
    }

    public record FlowOptions(Double direction, String coverage) {
    }

    public record FlowRow(
            String name,
            Integer state,
            Integer impulse,
            boolean available,
            boolean aligned,
            boolean opposing) {
    }

    public record EvidenceFamily(
            String name,
            List<String> members,
            boolean available,
            boolean aligned,
            boolean opposing) {
    }

    public record FlowAssessment(
            String version,
            String requested_coverage,
            String coverage,
            Double interval_hours,
            String completed_through,
            List<FlowRow> rows,
            int aligned_rows,
            int opposing_rows,
            List<EvidenceFamily> evidence_families,
            int aligned_evidence_families,
            int opposing_evidence_families,
            boolean horizon_agreement,
            boolean eligible_for_entry,
            double score,
            String reason) {
    }

    public record ActivePhaseInput(
            String framework,
            String channel,
            String phase,
            ScoreResult score,
            TriggerWindow trigger,
            List<?> vetoes) {
    }

    public record ActivePhaseResult(
            String phase,
            Integer threshold,
            Double score,
            boolean score_pass,
            boolean trigger_pass,
            boolean veto_pass,
            boolean unlocked,
            List<Object> vetoes) {
    }

    public record Veto(String code, boolean active, String reason) {
    }

    public record HardVetoInput(
            String coverage,
            boolean flowOpposes,
            boolean regimeMismatch,
            boolean riskBudgetExhausted,
            boolean narrativeExit,
            boolean carryVeto,
            boolean fundingVeto,
            boolean macroShock) {
    }

    public record TriggerInput(
            String timeframe,
            boolean valid,
            String createdAt,
            Object level,
            Object bars,
            Object ageBars,
            Boolean completedBar) {
    }

    public record TriggerWindow(
            String status,
            String timeframe,
            boolean completed_bar_required,
            boolean completed_bar,
            Object level,
            String created_at,
            String expires_at,
            double window_bars,
            Object age_bars) {
    }

    public record RiskBudgetInput(
            Number phaseCapPct,
            Number equityUsd,
            Number stopDistancePct,
            Number remainingAssetRiskPct,
            Number remainingPortfolioRiskPct) {

        public RiskBudgetInput(Number phaseCapPct, Number equityUsd, Number stopDistancePct) {
            this(phaseCapPct, equityUsd, stopDistancePct, null, null);
        }
    }

    public sealed interface RiskBudgetResult permits DataLimitedRiskBudget, AvailableRiskBudget {
        String status();

        Double notional_usd();
    }

    public record DataLimitedRiskBudget(
            String status,
            Double notional_usd,
            String reason) implements RiskBudgetResult {
    }

    public record AvailableRiskBudget(
            String status,
            double equity_usd,
            double stop_distance_pct,
            double phase_cap_pct,
            Double notional_usd,
            RiskConstraints constraints) implements RiskBudgetResult {
    }

    public record RiskConstraints(
            double phase_cap_usd,
            double portfolio_risk_usd,
            double asset_risk_usd) {
    }

    public record ExpectancyInput(
            Number winProbability,
            Number avgWinR,
            Number lossProbability,
            Number avgLossR,
            Number costsR) {
    }

    public record ExpectancyResult(
            double win_probability,
            double avg_win_r,
            double loss_probability,
            double avg_loss_r,
            double costs_r,
            double value_r) {
    }

    public record SetupSummaryInput(
            String framework,
            String channel,
            ScoreResult score,
            ActivePhaseResult phase,
            TriggerWindow trigger,
            List<Veto> vetoes) {
    }

    public record SetupSummary(
            String framework,
            String channel,
            HorizonDays horizon_days,
            Double score,
            Double mechanical_score,
            ActivePhaseResult phase,
            String trigger_status,
            String veto_status,
            boolean entry_authorized) {
    }

    public static final class SwingTypeException extends IllegalArgumentException {
        public SwingTypeException(String message) {
            super(message);
        }
    }

    public static final class SwingRangeException extends IllegalArgumentException {
        public SwingRangeException(String message) {
            super(message);
        }
    }
}

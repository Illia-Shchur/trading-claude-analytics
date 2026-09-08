package com.tradinganalytics.core.swing;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private SwingScore() {
    }

    /** JavaScript-compatible {@code Math.round(value * 2) / 2}. */
    public static double roundHalf(double value) {
        return SwingScoring.roundHalf(value);
    }

    /**
     * Normalizes the six bounded score legs. Missing and null values become
     * zero; values are range-checked before half-point rounding, as in JS.
     */
    public static Map<String, Double> normalizeLegs(Map<String, ?> legs) {
        return SwingScoring.normalizeLegs(legs);
    }

    /**
     * Normalizes the state/impulse decomposition of all non-flow legs.
     * Bounds are checked after half-point rounding, matching the source.
     */
    public static Map<String, LegComponent> normalizeLegComponents(
            Map<String, LegComponentInput> components) {
        return SwingScoring.normalizeLegComponents(components);
    }

    public static ScoreResult scoreSwing() {
        return scoreSwing(null);
    }

    public static ScoreResult scoreSwing(ScoreInput input) {
        return SwingScoring.scoreSwing(input);
    }

    public static FlowAssessment assessFlowPanel(JsonNode panel) {
        return assessFlowPanel(panel, null);
    }

    /** Audits the five completed-bar flow rows and returns one bounded leg. */
    public static FlowAssessment assessFlowPanel(JsonNode panel, FlowOptions options) {
        return SwingFlowAssessment.assess(panel, options);
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
        return SwingPhaseRisk.phaseThresholds(framework, channel);
    }

    public static Map<String, Integer> phaseCaps(String framework) {
        return phaseCaps(framework, "A");
    }

    public static Map<String, Integer> phaseCaps(String framework, String channel) {
        return SwingPhaseRisk.phaseCaps(framework, channel);
    }

    public static ActivePhaseResult activePhase(ActivePhaseInput input) {
        return SwingPhaseRisk.activePhase(input);
    }

    public static Veto veto(String code, boolean active) {
        return veto(code, active, "");
    }

    public static Veto veto(String code, boolean active, String reason) {
        return new Veto(String.valueOf(code), active, reason == null ? "" : reason);
    }

    public static List<Veto> hardVetoes() {
        return hardVetoes(null);
    }

    public static List<Veto> hardVetoes(HardVetoInput input) {
        return SwingPhaseRisk.hardVetoes(input);
    }

    public static TriggerWindow triggerWindow() {
        return triggerWindow(null);
    }

    public static TriggerWindow triggerWindow(TriggerInput input) {
        return SwingPhaseRisk.triggerWindow(input);
    }

    /**
     * Shared completed-bar age policy used by calculation and report
     * validation. Missing/null age is accepted by design; supplied ages must
     * be finite, integral, non-negative, and no older than the window.
     */
    public static boolean isValidTriggerAge(Object ageBars, double windowBars) {
        return SwingPhaseRisk.validTriggerAge(ageBars, windowBars);
    }

    public static RiskBudgetResult riskBudget(RiskBudgetInput input) {
        return SwingPhaseRisk.riskBudget(input);
    }

    public static ExpectancyResult expectancyR() {
        return expectancyR(null);
    }

    public static ExpectancyResult expectancyR(ExpectancyInput input) {
        return SwingPhaseRisk.expectancyR(input);
    }

    public static SetupSummary setupSummary(SetupSummaryInput input) {
        return SwingPhaseRisk.setupSummary(input);
    }

    @SafeVarargs
    private static <K, V> Map<K, V> immutableOrderedMap(Map.Entry<K, V>... entries) {
        LinkedHashMap<K, V> map = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : entries) {
            map.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(map);
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

package com.tradinganalytics.core.swing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.swing.SwingScore.ActivePhaseInput;
import com.tradinganalytics.core.swing.SwingScore.ActivePhaseResult;
import com.tradinganalytics.core.swing.SwingScore.AvailableRiskBudget;
import com.tradinganalytics.core.swing.SwingScore.DataLimitedRiskBudget;
import com.tradinganalytics.core.swing.SwingScore.ExpectancyInput;
import com.tradinganalytics.core.swing.SwingScore.FlowAssessment;
import com.tradinganalytics.core.swing.SwingScore.FlowOptions;
import com.tradinganalytics.core.swing.SwingScore.HardVetoInput;
import com.tradinganalytics.core.swing.SwingScore.LegComponentInput;
import com.tradinganalytics.core.swing.SwingScore.RiskBudgetInput;
import com.tradinganalytics.core.swing.SwingScore.ScoreInput;
import com.tradinganalytics.core.swing.SwingScore.ScoreResult;
import com.tradinganalytics.core.swing.SwingScore.SetupSummaryInput;
import com.tradinganalytics.core.swing.SwingScore.SwingRangeException;
import com.tradinganalytics.core.swing.SwingScore.SwingTypeException;
import com.tradinganalytics.core.swing.SwingScore.TriggerInput;
import com.tradinganalytics.core.swing.SwingScore.TriggerWindow;
import com.tradinganalytics.core.swing.SwingScore.Veto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SwingScoreTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void exportsTheCompleteVersionedConstantContract() throws Exception {
        assertThat(SwingScore.SWING_SCORE_VERSION).isEqualTo("swing-score/1");
        assertThat(SwingScore.SWING_HORIZON_DAYS.min()).isEqualTo(3);
        assertThat(SwingScore.SWING_HORIZON_DAYS.max()).isEqualTo(30);
        assertThat(SwingScore.FLOW_PANEL_ROWS).containsExactly(
                "spot_cvd", "futures_bid_ask_delta", "futures_cvd",
                "open_interest", "oi_weighted_funding");
        assertThat(SwingScore.FLOW_EVIDENCE_FAMILIES).containsExactly(
                "spot_cvd", "futures_taker_flow", "open_interest", "oi_weighted_funding");
        assertThat(SwingScore.SCORE_MAXES).containsExactly(
                Map.entry("flow", 5), Map.entry("technical", 4), Map.entry("macro", 3),
                Map.entry("sentiment", 3), Map.entry("valuation", 3), Map.entry("structure", 2));
        assertThat(SwingScore.LEG_COMPONENT_MAXES.get("macro"))
                .isEqualTo(new SwingScore.ComponentMax(1.5, 1.5));

        JsonNode thresholdJson = JSON.valueToTree(SwingScore.PHASE_THRESHOLDS);
        assertThat(thresholdJson)
                .isEqualTo(JSON.readTree("""
                        {"fallen_knives":{"1A":8,"1B":11,"2":15,"3":17},
                         "flying_rocket":{"A":{"1A":11,"1B":13,"2":15,"3":19},
                                           "B":{"1A":13,"1B":15,"2":17}}}
                        """));
        JsonNode capJson = JSON.valueToTree(SwingScore.PHASE_CAPS_PCT);
        assertThat(capJson)
                .isEqualTo(JSON.readTree("""
                        {"fallen_knives":{"1A":10,"1B":15,"2":30,"3":45},
                         "flying_rocket":{"1A":5,"1B":10,"2":15,"3":20}}
                        """));
        assertThatThrownBy(() -> SwingScore.SCORE_MAXES.put("other", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void roundHalfUsesJavascriptTieDirectionAndRejectsNonFiniteNumbers() {
        assertThat(SwingScore.roundHalf(0.24)).isZero();
        assertThat(SwingScore.roundHalf(0.25)).isEqualTo(0.5);
        assertThat(SwingScore.roundHalf(0.74)).isEqualTo(0.5);
        assertThat(SwingScore.roundHalf(0.75)).isEqualTo(1.0);
        assertThat(SwingScore.roundHalf(-0.25)).isEqualTo(-0.0);
        assertThat(Double.doubleToRawLongBits(SwingScore.roundHalf(-0.25)))
                .isEqualTo(Double.doubleToRawLongBits(-0.0));
        assertThat(SwingScore.roundHalf(-0.75)).isEqualTo(-0.5);
        assertThatThrownBy(() -> SwingScore.roundHalf(Double.NaN))
                .isInstanceOf(SwingTypeException.class)
                .hasMessage("swing score requires finite numeric inputs");
        assertThatThrownBy(() -> SwingScore.roundHalf(Double.POSITIVE_INFINITY))
                .isInstanceOf(SwingTypeException.class);
    }

    @Test
    void normalizesLegsInCanonicalOrderWithDefaultsAndPreRoundBounds() {
        Map<String, Double> result = SwingScore.normalizeLegs(Map.of(
                "flow", 4.9,
                "technical", 1.24,
                "unknown", 999));

        assertThat(result).containsExactly(
                Map.entry("flow", 5.0), Map.entry("technical", 1.0),
                Map.entry("macro", 0.0), Map.entry("sentiment", 0.0),
                Map.entry("valuation", 0.0), Map.entry("structure", 0.0));
        assertThat(SwingScore.normalizeLegs(null).values()).containsOnly(0.0);
        LinkedHashMap<String, Object> explicitNull = new LinkedHashMap<>();
        explicitNull.put("flow", null);
        assertThat(SwingScore.normalizeLegs(explicitNull).get("flow")).isZero();
        assertThatThrownBy(() -> SwingScore.normalizeLegs(Map.of("flow", 5.01)))
                .isInstanceOf(SwingRangeException.class)
                .hasMessage("legs.flow must be between 0 and 5");
        assertThatThrownBy(() -> SwingScore.normalizeLegs(Map.of("structure", -0.01)))
                .isInstanceOf(SwingRangeException.class)
                .hasMessage("legs.structure must be between 0 and 2");
        assertThatThrownBy(() -> SwingScore.normalizeLegs(Map.of("macro", "3")))
                .isInstanceOf(SwingTypeException.class)
                .hasMessage("legs.macro must be finite");
        assertThatThrownBy(() -> SwingScore.normalizeLegs(Map.of("valuation", Double.NaN)))
                .isInstanceOf(SwingTypeException.class)
                .hasMessage("legs.valuation must be finite");
        assertThatThrownBy(() -> result.put("flow", 0.0))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void normalizesAllLegComponentsAfterRounding() {
        assertThat(SwingScore.normalizeLegComponents(null)).hasSize(5)
                .allSatisfy((name, component) -> assertThat(component.total()).isZero());
        Map<String, SwingScore.LegComponent> result = SwingScore.normalizeLegComponents(Map.of(
                "technical", new LegComponentInput(1.76, 1.24),
                "macro", new LegComponentInput(null, 1.49)));

        assertThat(result.get("technical"))
                .isEqualTo(new SwingScore.LegComponent(2.0, 1.0, 3.0, 4.0));
        assertThat(result.get("macro"))
                .isEqualTo(new SwingScore.LegComponent(0.0, 1.5, 1.5, 3.0));
        assertThat(result.get("sentiment"))
                .isEqualTo(new SwingScore.LegComponent(0.0, 0.0, 0.0, 3.0));
        // Component bounds intentionally run after rounding in swing-score/1.
        assertThat(SwingScore.normalizeLegComponents(Map.of(
                "structure", new LegComponentInput(-0.24, 1.24))).get("structure").state())
                .isEqualTo(-0.0);
        assertThatThrownBy(() -> SwingScore.normalizeLegComponents(Map.of(
                "technical", new LegComponentInput(2.25, 0.0))))
                .isInstanceOf(SwingRangeException.class)
                .hasMessage("technical.state must be between 0 and 2");
        assertThatThrownBy(() -> SwingScore.normalizeLegComponents(Map.of(
                "macro", new LegComponentInput(0.0, 1.75))))
                .isInstanceOf(SwingRangeException.class)
                .hasMessage("macro.impulse must be between 0 and 1.5");
        assertThatThrownBy(() -> SwingScore.normalizeLegComponents(Map.of(
                "valuation", new LegComponentInput(Double.NaN, 0.0))))
                .isInstanceOf(SwingTypeException.class)
                .hasMessage("swing score requires finite numeric inputs");
    }

    @Test
    void scoresSixLegsAtTwentyAndKeepsRawBeforeAdjustedClamp() {
        ScoreResult result = SwingScore.scoreSwing(new ScoreInput(
                maxLegs(), null, 0.5, 1.0));
        assertThat(result.version()).isEqualTo("swing-score/1");
        assertThat(result.legs()).hasSize(6);
        assertThat(result.mechanical()).isEqualTo(20.0);
        assertThat(result.adjusted()).isEqualTo(20.0);
        assertThat(result.raw()).isEqualTo(20.5);
        assertThat(result.discretion()).isEqualTo(0.5);
        assertThat(result.impulse()).isEqualTo(1.0);
        assertThat(result.max()).isEqualTo(20);
        assertThat(SwingScore.scoreSwing().mechanical()).isZero();
    }

    @Test
    void componentsOverrideMatchingNarratedLegsButNeverTheFlowLeg() {
        Map<String, LegComponentInput> components = fullComponents();
        ScoreResult result = SwingScore.scoreSwing(new ScoreInput(
                Map.of("flow", 2.0, "technical", 0.0, "macro", 0.0,
                        "sentiment", 0.0, "valuation", 0.0, "structure", 0.0),
                components, -1.0, -0.25));

        assertThat(result.legs()).containsEntry("flow", 2.0).containsEntry("technical", 4.0);
        assertThat(result.leg_components()).hasSize(5);
        assertThat(result.mechanical()).isEqualTo(17.0);
        assertThat(result.adjusted()).isEqualTo(16.0);
        assertThat(result.raw()).isEqualTo(16.0);
        assertThat(result.impulse()).isEqualTo(-0.0);
    }

    @ParameterizedTest
    @MethodSource("invalidDiscretions")
    void discretionMustBeAFiniteHalfPoint(Object discretion) {
        assertThatThrownBy(() -> SwingScore.scoreSwing(new ScoreInput(Map.of(), null, discretion, 0.0)))
                .isInstanceOf(SwingRangeException.class)
                .hasMessage("discretion must be a half-point in the range -1..1");
    }

    static Stream<Object> invalidDiscretions() {
        return Stream.of(-1.5, 1.5, 0.25, Double.NaN, Double.POSITIVE_INFINITY, "0.5");
    }

    @Test
    void impulseMustBeFinite() {
        assertThatThrownBy(() -> SwingScore.scoreSwing(new ScoreInput(Map.of(), null, 0.0, "1")))
                .isInstanceOf(SwingTypeException.class).hasMessage("impulse must be finite");
        assertThatThrownBy(() -> SwingScore.scoreSwing(new ScoreInput(Map.of(), null, 0.0, Double.NaN)))
                .isInstanceOf(SwingTypeException.class).hasMessage("impulse must be finite");
    }

    @Test
    void fullTwoHorizonPanelEarnsFiveAndIsEntryEligible() throws Exception {
        FlowAssessment result = SwingScore.assessFlowPanel(fullAlignedPanel(), new FlowOptions(1.0, "complete"));

        assertThat(result.requested_coverage()).isEqualTo("COMPLETE");
        assertThat(result.coverage()).isEqualTo("COMPLETE");
        assertThat(result.interval_hours()).isEqualTo(4.0);
        assertThat(result.completed_through()).isEqualTo("2026-08-22T00:00:00Z");
        assertThat(result.rows()).hasSize(5).allMatch(row -> row.available() && row.aligned());
        assertThat(result.aligned_rows()).isEqualTo(5);
        assertThat(result.opposing_rows()).isZero();
        assertThat(result.evidence_families()).hasSize(4).allMatch(family -> family.aligned());
        assertThat(result.aligned_evidence_families()).isEqualTo(4);
        assertThat(result.opposing_evidence_families()).isZero();
        assertThat(result.horizon_agreement()).isTrue();
        assertThat(result.eligible_for_entry()).isTrue();
        assertThat(result.score()).isEqualTo(5.0);
        assertThat(result.reason()).isNull();
        assertThat(SwingScore.flowLegFromPanel(fullAlignedPanel(), new FlowOptions(1.0, "COMPLETE")))
                .isEqualTo(5.0);
        assertThat(SwingScore.flowLegFromPanel(fullAlignedPanel())).isEqualTo(5.0);
    }

    @Test
    void partialPanelCapsContextScoreAtTwoAndAHalf() throws Exception {
        JsonNode panel = json("""
                {"interval_hours":4,"completed_through":"2026-08-22T00:00:00Z",
                 "spot_cvd":{"direction_24h":"positive","direction_3d":"positive"},
                 "futures_bid_ask_delta":{"direction_24h":"positive","direction_3d":"positive"},
                 "futures_cvd":{"direction_24h":"positive","direction_3d":"positive"},
                 "open_interest":{"direction_24h":"positive","direction_3d":"positive"},
                 "oi_weighted_funding":{"direction_24h":"positive","direction_3d":"positive"}}
                """);

        FlowAssessment result = SwingScore.assessFlowPanel(panel, new FlowOptions(null, "PARTIAL"));
        assertThat(result.score()).isEqualTo(2.5);
        assertThat(result.coverage()).isEqualTo("PARTIAL");
        assertThat(result.eligible_for_entry()).isFalse();
        assertThat(result.reason()).isEqualTo(
                "requires error-free completed 4h bars with 24h and 3d directions for all five rows");
    }

    @Test
    void duplicateFuturesRowsRemainPrintableButCountAsOneEvidenceFamily() throws Exception {
        JsonNode panel = json("""
                {"interval_hours":4,"completed_through":"2026-08-22T00:00:00Z",
                 "spot_cvd":{"direction_24h":"negative","direction_3d":"negative"},
                 "futures_bid_ask_delta":{"direction_24h":"positive","direction_3d":"positive"},
                 "futures_cvd":{"direction_24h":"positive","direction_3d":"positive"},
                 "open_interest":{"setup_signal_24h":"neutral","setup_signal_3d":"neutral"},
                 "oi_weighted_funding":{"direction_24h":"positive","direction_3d":"positive"}}
                """);
        FlowAssessment result = SwingScore.assessFlowPanel(panel, new FlowOptions(1.0, "COMPLETE"));

        assertThat(result.aligned_rows()).isEqualTo(2);
        assertThat(result.opposing_rows()).isEqualTo(2);
        assertThat(result.aligned_evidence_families()).isEqualTo(1);
        assertThat(result.opposing_evidence_families()).isEqualTo(2);
        assertThat(result.score()).isEqualTo(1.5);
        assertThat(result.evidence_families().get(1).members())
                .containsExactly("futures_bid_ask_delta", "futures_cvd");
    }

    @Test
    void flowDirectionsSupportTextNumbersInterpretationAndFundingInversion() throws Exception {
        JsonNode panel = json("""
                {"intervalHours":"4","completed_through":"done","errors":{},
                 "spot_cvd":{"direction_24h":1,"direction_3d":"buyers rising"},
                 "futures_bid_ask_delta":{"signal_24h":-2,"delta_3d_usd":"sell build"},
                 "futures_cvd":{"change_24h_pct":"flat","24h":"increase"},
                 "open_interest":{"direction_24h":"positive","direction_3d":"positive"},
                 "oi_weighted_funding":{"direction_24h":"negative","direction_3d":-0.01}}
                """);
        FlowAssessment result = SwingScore.assessFlowPanel(panel, new FlowOptions(1.0, null));

        assertThat(result.rows().get(0).state()).isEqualTo(1);
        assertThat(result.rows().get(1).state()).isEqualTo(-1);
        assertThat(result.rows().get(2).state()).isZero();
        assertThat(result.rows().get(3).state()).isNull(); // raw OI is never directional by itself
        assertThat(result.rows().get(4).state()).isEqualTo(1); // negative funding is FK-aligned
        assertThat(result.coverage()).isEqualTo("PARTIAL");

        JsonNode interpreted = json("""
                {"interval_hours":4,"completed_through":"done",
                 "spot_cvd":{"setup_signal_24h":"opposing","setup_signal_3d":"adverse"},
                 "futures_bid_ask_delta":{"alignment_24h":"confirm","alignment_3d":"favourable"},
                 "futures_cvd":{"alignment_24h":"confirm","alignment_3d":"favorable"},
                 "open_interest":{"setup_signal_24h":"diverging","setup_signal_3d":"mixed"},
                 "oi_weighted_funding":{"setup_signal_24h":"aligned","setup_signal_3d":"aligned"}}
                """);
        FlowAssessment shortResult = SwingScore.assessFlowPanel(interpreted, new FlowOptions(-1.0, "COMPLETE"));
        assertThat(shortResult.rows()).extracting(SwingScore.FlowRow::state)
                .containsExactly(1, -1, -1, 1, -1);
        assertThat(shortResult.rows().get(3).impulse()).isZero();
    }

    @ParameterizedTest
    @MethodSource("incompletePanels")
    void completenessFailsClosedForEveryRequiredPanelCondition(JsonNode panel) {
        FlowAssessment result = SwingScore.assessFlowPanel(panel, new FlowOptions(1.0, null));
        assertThat(result.coverage()).isEqualTo("PARTIAL");
        assertThat(result.horizon_agreement()).isFalse();
        assertThat(result.eligible_for_entry()).isFalse();
    }

    static Stream<JsonNode> incompletePanels() throws Exception {
        ObjectNode wrongInterval = (ObjectNode) fullAlignedPanel();
        wrongInterval.put("interval_hours", 1);
        ObjectNode noCompleted = (ObjectNode) fullAlignedPanel();
        noCompleted.remove("completed_through");
        ObjectNode errors = (ObjectNode) fullAlignedPanel();
        errors.putArray("errors").add("provider failed");
        ObjectNode unavailable = (ObjectNode) fullAlignedPanel();
        ((ObjectNode) unavailable.get("spot_cvd")).put("available", false);
        ObjectNode missingHorizon = (ObjectNode) fullAlignedPanel();
        ((ObjectNode) missingHorizon.get("spot_cvd")).remove("setup_signal_3d");
        ObjectNode requestedPartial = (ObjectNode) fullAlignedPanel();
        requestedPartial.put("coverage", "PARTIAL");
        return Stream.of(wrongInterval, noCompleted, errors, unavailable, missingHorizon, requestedPartial,
                JSON.createObjectNode(), JSON.nullNode());
    }

    @Test
    void absentRowsAndExplicitFalseAvailabilityAreDistinguished() throws Exception {
        JsonNode panel = json("""
                {"coverage":"","interval_hours":null,"intervalHours":"not-a-number",
                 "completed_through":"","spot_cvd":{"available":false,
                 "setup_signal_24h":null,"alignment_24h":null,"alignment_3d":"unknown"}}
                """);
        FlowAssessment result = SwingScore.assessFlowPanel(panel);

        assertThat(result.requested_coverage()).isEqualTo("COMPLETE");
        assertThat(result.interval_hours()).isNull();
        assertThat(result.completed_through()).isNull();
        assertThat(result.rows().get(0).available()).isFalse();
        assertThat(result.rows().get(0).state()).isZero();
        assertThat(result.rows().get(1).available()).isFalse();
        assertThat(result.score()).isZero();
    }

    @Test
    void phaseThresholdAndCapCopiesMatchBothFrameworksAndChannels() {
        assertThat(SwingScore.phaseThresholds("fallen_knives"))
                .containsExactly(Map.entry("1A", 8), Map.entry("1B", 11),
                        Map.entry("2", 15), Map.entry("3", 17));
        assertThat(SwingScore.phaseThresholds("flying_rocket", "A"))
                .containsExactly(Map.entry("1A", 11), Map.entry("1B", 13),
                        Map.entry("2", 15), Map.entry("3", 19));
        assertThat(SwingScore.phaseThresholds("flying_rocket", "B"))
                .containsExactly(Map.entry("1A", 13), Map.entry("1B", 15), Map.entry("2", 17));
        assertThat(SwingScore.phaseThresholds("unknown", "B"))
                .isEqualTo(SwingScore.phaseThresholds("flying_rocket", "B"));
        assertThat(SwingScore.phaseCaps("fallen_knives", "B"))
                .containsExactly(Map.entry("1A", 10), Map.entry("1B", 15),
                        Map.entry("2", 30), Map.entry("3", 45));
        assertThat(SwingScore.phaseCaps("flying_rocket", "A"))
                .containsExactly(Map.entry("1A", 5), Map.entry("1B", 10),
                        Map.entry("2", 15), Map.entry("3", 20));
        assertThat(SwingScore.phaseCaps("flying_rocket", "B")).doesNotContainKey("3");
        assertThat(SwingScore.phaseCaps("unknown")).isEmpty();
        assertThatThrownBy(() -> SwingScore.phaseThresholds("fallen_knives").put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void phaseUnlockUsesMechanicalScoreValidCompletedTriggerAndNoActiveVeto() {
        ScoreResult threshold = scoreAt(8.0, 0.0);
        TriggerWindow valid = validTrigger();
        ActivePhaseResult result = SwingScore.activePhase(new ActivePhaseInput(
                "fallen_knives", null, "1A", threshold, valid,
                Arrays.asList(null, false, SwingScore.veto("OFF", false), Map.of("active", false))));

        assertThat(result.threshold()).isEqualTo(8);
        assertThat(result.score()).isEqualTo(8.0);
        assertThat(result.score_pass()).isTrue();
        assertThat(result.trigger_pass()).isTrue();
        assertThat(result.veto_pass()).isTrue();
        assertThat(result.unlocked()).isTrue();
        assertThat(result.vetoes()).isEmpty();

        ScoreResult discretionOnly = scoreAt(7.5, 1.0);
        assertThat(discretionOnly.adjusted()).isEqualTo(8.5);
        assertThat(SwingScore.activePhase(new ActivePhaseInput(
                "fallen_knives", "A", "1A", discretionOnly, valid, List.of())).unlocked()).isFalse();

        ActivePhaseResult vetoed = SwingScore.activePhase(new ActivePhaseInput(
                "fallen_knives", "A", "1A", threshold, valid,
                List.of(SwingScore.veto("X", true), true, Map.of("active", true))));
        assertThat(vetoed.veto_pass()).isFalse();
        assertThat(vetoed.vetoes()).hasSize(3);
        assertThat(vetoed.unlocked()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("invalidActivationTriggers")
    void phaseRejectsEveryInvalidTriggerContract(TriggerWindow trigger) {
        ActivePhaseResult result = SwingScore.activePhase(new ActivePhaseInput(
                "fallen_knives", "A", "1A", scoreAt(8.0, 0.0), trigger, List.of()));
        assertThat(result.trigger_pass()).isFalse();
        assertThat(result.unlocked()).isFalse();
    }

    static Stream<TriggerWindow> invalidActivationTriggers() {
        TriggerWindow valid = validTrigger();
        return Stream.of(
                null,
                new TriggerWindow("WAIT", "4h", true, true, null, null, null, 2, null),
                new TriggerWindow("VALID", "1h", true, true, null, null, null, 2, null),
                new TriggerWindow("VALID", "4h", false, true, null, null, null, 2, null),
                new TriggerWindow("VALID", "4h", true, false, null, null, null, 2, null),
                new TriggerWindow("VALID", "4h", true, true, null, null, null, 0, null),
                new TriggerWindow("VALID", "4h", true, true, null, null, null, 3, null),
                new TriggerWindow("VALID", "4h", true, true, null, null, null, 2, 3),
                new TriggerWindow("VALID", "4h", true, true, null, null, null, 2, "bad"),
                new TriggerWindow(valid.status(), valid.timeframe(), true, true,
                        null, null, null, Double.NaN, null)
        );
    }

    @Test
    void missingPhaseAndScoreCannotUnlock() {
        ActivePhaseResult missing = SwingScore.activePhase(null);
        assertThat(missing.threshold()).isNull();
        assertThat(missing.score()).isNull();
        assertThat(missing.score_pass()).isFalse();
        assertThat(missing.trigger_pass()).isFalse();
        assertThat(missing.unlocked()).isFalse();
        assertThat(SwingScore.activePhase(new ActivePhaseInput(
                "fallen_knives", "A", "missing", scoreAt(20, 0), validTrigger(), List.of())).score_pass())
                .isFalse();
    }

    @Test
    void vetoAndHardVetoesPreserveCanonicalCodesReasonsAndOrdering() {
        assertThat(SwingScore.veto(null, true, null)).isEqualTo(new Veto("null", true, ""));
        List<Veto> defaults = SwingScore.hardVetoes();
        assertThat(defaults).extracting(Veto::code).containsExactly(
                "FLOW_COVERAGE", "OPPOSING_FLOW", "REGIME_MISMATCH", "RISK_BUDGET",
                "NARRATIVE_EXIT", "CARRY", "FUNDING", "MACRO_SHOCK");
        assertThat(defaults).noneMatch(Veto::active);

        List<Veto> all = SwingScore.hardVetoes(new HardVetoInput(
                "PARTIAL", true, true, true, true, true, true, true));
        assertThat(all).allMatch(Veto::active);
        assertThat(all.get(0).reason()).isEqualTo(
                "Flow coverage is incomplete or not common across required horizons.");
        assertThat(SwingScore.hardVetoes(null)).noneMatch(Veto::active);
    }

    @Test
    void triggerWindowHasTwoCompletedBarLifetimeAndJavascriptCoercion() {
        TriggerWindow valid = SwingScore.triggerWindow(new TriggerInput(
                null, true, "2026-08-22T00:00:00Z", 100, null, null, null));
        assertThat(valid.status()).isEqualTo("VALID");
        assertThat(valid.timeframe()).isEqualTo("4h");
        assertThat(valid.completed_bar_required()).isTrue();
        assertThat(valid.completed_bar()).isTrue();
        assertThat(valid.level()).isEqualTo(100);
        assertThat(valid.created_at()).isEqualTo("2026-08-22T00:00:00Z");
        assertThat(valid.expires_at()).isEqualTo("2026-08-22T08:00:00Z");
        assertThat(valid.window_bars()).isEqualTo(2.0);
        assertThat(valid.age_bars()).isNull();

        assertThat(SwingScore.triggerWindow(new TriggerInput(
                "4h", true, null, null, 2, 3, true)).status()).isEqualTo("EXPIRED");
        assertThat(SwingScore.triggerWindow(new TriggerInput(
                "4h", true, null, null, 2, 2, false)).status()).isEqualTo("WAIT");
        assertThat(SwingScore.triggerWindow(new TriggerInput(
                "4h", false, null, null, 2, 3, true)).status()).isEqualTo("WAIT");
        assertThat(SwingScore.triggerWindow().status()).isEqualTo("WAIT");
        assertThat(SwingScore.triggerWindow(null).window_bars()).isEqualTo(2.0);
    }

    @ParameterizedTest
    @MethodSource("triggerBarWindows")
    void triggerBarsFollowNumberFallbackAndOneToTwoClamp(Object bars, double expected) {
        TriggerWindow result = SwingScore.triggerWindow(new TriggerInput(
                "4h", true, "2026-08-22T00:00:00.125Z", null, bars, -1, true));
        assertThat(result.window_bars()).isEqualTo(expected);
        assertThat(result.status()).isEqualTo("VALID");
        assertThat(result.expires_at()).isNotNull();
    }

    static Stream<Arguments> triggerBarWindows() {
        return Stream.of(
                Arguments.of(0, 2.0),
                Arguments.of("bad", 2.0),
                Arguments.of(-5, 1.0),
                Arguments.of(3, 2.0),
                Arguments.of("1.5", 1.5),
                Arguments.of(true, 1.0),
                Arguments.of(Double.POSITIVE_INFINITY, 2.0)
        );
    }

    @Test
    void triggerExpirySupportsOffsetsDatesAndInvalidDates() {
        assertThat(SwingScore.triggerWindow(new TriggerInput(
                "4h", true, "2026-08-22T03:00+03:00", null, 1, null, true)).expires_at())
                .isEqualTo("2026-08-22T04:00:00Z");
        assertThat(SwingScore.triggerWindow(new TriggerInput(
                "4h", true, "2026-08-22", null, 1, null, true)).expires_at())
                .isEqualTo("2026-08-22T04:00:00Z");
        assertThat(SwingScore.triggerWindow(new TriggerInput(
                "4h", true, "not-a-date", null, 1, null, true)).expires_at()).isNull();
    }

    @Test
    void riskBudgetReturnsTheMinimumOfPhasePortfolioAndAssetConstraints() {
        AvailableRiskBudget result = (AvailableRiskBudget) SwingScore.riskBudget(
                new RiskBudgetInput(10, 10_000, 5));
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.equity_usd()).isEqualTo(10_000.0);
        assertThat(result.stop_distance_pct()).isEqualTo(5.0);
        assertThat(result.phase_cap_pct()).isEqualTo(10.0);
        assertThat(result.notional_usd()).isEqualTo(1_000.0);
        assertThat(result.constraints().phase_cap_usd()).isEqualTo(1_000.0);
        assertThat(result.constraints().portfolio_risk_usd()).isEqualTo(3_000.0);
        assertThat(result.constraints().asset_risk_usd()).isEqualTo(6_000.0);

        AvailableRiskBudget portfolioLimited = (AvailableRiskBudget) SwingScore.riskBudget(
                new RiskBudgetInput(100, 10_000, 5));
        assertThat(portfolioLimited.notional_usd()).isEqualTo(3_000.0);
        AvailableRiskBudget assetLimited = (AvailableRiskBudget) SwingScore.riskBudget(
                new RiskBudgetInput(100, 10_000, 5, 0.5, 10));
        assertThat(assetLimited.notional_usd()).isEqualTo(1_000.0);
        AvailableRiskBudget zeroCap = (AvailableRiskBudget) SwingScore.riskBudget(
                new RiskBudgetInput(0, 10_000, 5));
        assertThat(zeroCap.notional_usd()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidRiskBudgets")
    void invalidRequiredRiskInputsFailClosed(RiskBudgetInput input) {
        DataLimitedRiskBudget result = (DataLimitedRiskBudget) SwingScore.riskBudget(input);
        assertThat(result.status()).isEqualTo("DATA_LIMITED");
        assertThat(result.notional_usd()).isNull();
        assertThat(result.reason()).isEqualTo("portfolio equity and a valid stop are required");
    }

    static Stream<RiskBudgetInput> invalidRiskBudgets() {
        return Stream.of(
                new RiskBudgetInput(null, 10_000, 5),
                new RiskBudgetInput(10, null, 5),
                new RiskBudgetInput(10, 10_000, null),
                new RiskBudgetInput(-1, 10_000, 5),
                new RiskBudgetInput(10, 0, 5),
                new RiskBudgetInput(10, -1, 5),
                new RiskBudgetInput(10, 10_000, 0),
                new RiskBudgetInput(Double.NaN, 10_000, 5),
                new RiskBudgetInput(10, Double.POSITIVE_INFINITY, 5),
                null
        );
    }

    @Test
    void expectancyComputesAfterCostRAndRejectsEveryNonFiniteInput() {
        SwingScore.ExpectancyResult result = SwingScore.expectancyR(
                new ExpectancyInput(0.6, 2, 0.4, 1, 0.1));
        assertThat(result.win_probability()).isEqualTo(0.6);
        assertThat(result.avg_win_r()).isEqualTo(2.0);
        assertThat(result.loss_probability()).isEqualTo(0.4);
        assertThat(result.avg_loss_r()).isEqualTo(1.0);
        assertThat(result.costs_r()).isEqualTo(0.1);
        assertThat(result.value_r()).isCloseTo(0.7, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(SwingScore.expectancyR().value_r()).isZero();
        assertThat(SwingScore.expectancyR(null).value_r()).isZero();

        List<ExpectancyInput> invalid = List.of(
                new ExpectancyInput(Double.NaN, 1, 1, 1, 1),
                new ExpectancyInput(1, Double.NaN, 1, 1, 1),
                new ExpectancyInput(1, 1, Double.NaN, 1, 1),
                new ExpectancyInput(1, 1, 1, Double.NaN, 1),
                new ExpectancyInput(1, 1, 1, 1, Double.NaN));
        invalid.forEach(input -> assertThatThrownBy(() -> SwingScore.expectancyR(input))
                .isInstanceOf(SwingTypeException.class)
                .hasMessage("expectancy inputs must be finite"));
    }

    @Test
    void setupSummaryUsesAdjustedAndMechanicalScoresWithVetoAndEntryState() {
        ScoreResult score = scoreAt(8, 0.5);
        ActivePhaseResult phase = SwingScore.activePhase(new ActivePhaseInput(
                "fallen_knives", "A", "1A", score, validTrigger(), List.of()));
        SwingScore.SetupSummary summary = SwingScore.setupSummary(new SetupSummaryInput(
                "fallen_knives", null, score, phase, validTrigger(), List.of(SwingScore.veto("X", true))));

        assertThat(summary.framework()).isEqualTo("fallen_knives");
        assertThat(summary.channel()).isNull();
        assertThat(summary.horizon_days()).isEqualTo(new SwingScore.HorizonDays(3, 30));
        assertThat(summary.score()).isEqualTo(8.5);
        assertThat(summary.mechanical_score()).isEqualTo(8.0);
        assertThat(summary.phase()).isEqualTo(phase);
        assertThat(summary.trigger_status()).isEqualTo("VALID");
        assertThat(summary.veto_status()).isEqualTo("VETO");
        assertThat(summary.entry_authorized()).isTrue();

        SwingScore.SetupSummary empty = SwingScore.setupSummary(null);
        assertThat(empty.score()).isNull();
        assertThat(empty.mechanical_score()).isNull();
        assertThat(empty.trigger_status()).isEqualTo("WAIT");
        assertThat(empty.veto_status()).isEqualTo("CLEAR");
        assertThat(empty.entry_authorized()).isFalse();
    }

    @Test
    void outputRecordsSerializeWithJavascriptContractFieldNames() throws Exception {
        JsonNode score = JSON.valueToTree(SwingScore.scoreSwing(new ScoreInput(maxLegs(), fullComponents(), 0, 0)));
        assertThat(score.has("leg_components")).isTrue();
        assertThat(score.has("mechanical")).isTrue();
        assertThat(score.has("adjusted")).isTrue();

        JsonNode flow = JSON.valueToTree(SwingScore.assessFlowPanel(fullAlignedPanel()));
        assertThat(flow.has("requested_coverage")).isTrue();
        assertThat(flow.has("interval_hours")).isTrue();
        assertThat(flow.has("aligned_evidence_families")).isTrue();
        assertThat(flow.has("eligible_for_entry")).isTrue();

        JsonNode risk = JSON.valueToTree(SwingScore.riskBudget(new RiskBudgetInput(10, 10_000, 5)));
        assertThat(risk.has("notional_usd")).isTrue();
        assertThat(risk.path("constraints").has("portfolio_risk_usd")).isTrue();
        JsonNode trigger = JSON.valueToTree(validTrigger());
        assertThat(trigger.has("completed_bar_required")).isTrue();
        assertThat(trigger.has("window_bars")).isTrue();
        assertThat(trigger.has("age_bars")).isTrue();
    }

    @Test
    void jsonCoercionMatchesJavascriptForCoverageSignalsAndIntervalAliases() throws Exception {
        ObjectNode panel = (ObjectNode) fullAlignedPanel();

        panel.set("coverage", JSON.getNodeFactory().booleanNode(true));
        assertThat(SwingScore.assessFlowPanel(panel).requested_coverage()).isEqualTo("TRUE");
        panel.set("coverage", JSON.getNodeFactory().booleanNode(false));
        assertThat(SwingScore.assessFlowPanel(panel).requested_coverage()).isEqualTo("COMPLETE");
        panel.set("coverage", JSON.getNodeFactory().numberNode(1));
        assertThat(SwingScore.assessFlowPanel(panel).requested_coverage()).isEqualTo("1");
        panel.set("coverage", JSON.getNodeFactory().numberNode(1.5));
        assertThat(SwingScore.assessFlowPanel(panel).requested_coverage()).isEqualTo("1.5");
        panel.set("coverage", JSON.getNodeFactory().numberNode(Double.POSITIVE_INFINITY));
        assertThat(SwingScore.assessFlowPanel(panel).requested_coverage()).isEqualTo("INFINITY");
        panel.set("coverage", JSON.getNodeFactory().numberNode(Double.NEGATIVE_INFINITY));
        assertThat(SwingScore.assessFlowPanel(panel).requested_coverage()).isEqualTo("-INFINITY");
        panel.set("coverage", JSON.getNodeFactory().numberNode(Double.NaN));
        assertThat(SwingScore.assessFlowPanel(panel).requested_coverage()).isEqualTo("COMPLETE");
        panel.set("coverage", JSON.createArrayNode().add("complete"));
        assertThat(SwingScore.assessFlowPanel(panel).requested_coverage()).isEqualTo("COMPLETE");
        panel.set("coverage", JSON.createArrayNode());
        assertThat(SwingScore.assessFlowPanel(panel).requested_coverage()).isEmpty();
        panel.set("coverage", JSON.createObjectNode());
        assertThat(SwingScore.assessFlowPanel(panel).requested_coverage()).isEqualTo("[OBJECT OBJECT]");

        panel.remove("interval_hours");
        panel.set("intervalHours", JSON.getNodeFactory().booleanNode(true));
        assertThat(SwingScore.assessFlowPanel(panel).interval_hours()).isEqualTo(1.0);
        panel.set("intervalHours", JSON.createArrayNode());
        assertThat(SwingScore.assessFlowPanel(panel).interval_hours()).isZero();
        panel.set("intervalHours", JSON.createArrayNode().add("4"));
        assertThat(SwingScore.assessFlowPanel(panel).interval_hours()).isEqualTo(4.0);
        panel.set("intervalHours", JSON.createArrayNode().add(1).add(2));
        assertThat(SwingScore.assessFlowPanel(panel).interval_hours()).isNull();
        panel.set("intervalHours", JSON.createObjectNode());
        assertThat(SwingScore.assessFlowPanel(panel).interval_hours()).isNull();
    }

    @Test
    void heterogeneousFlowValuesUseJavascriptStringAndSignRules() throws Exception {
        ObjectNode panel = (ObjectNode) fullAlignedPanel();
        ObjectNode spot = (ObjectNode) panel.get("spot_cvd");
        spot.removeAll();
        spot.set("direction_24h", JSON.getNodeFactory().booleanNode(true));
        spot.set("direction_3d", JSON.createArrayNode().add("buy").add("rising"));
        SwingScore.FlowRow row = SwingScore.assessFlowPanel(panel).rows().get(0);
        assertThat(row.state()).isZero();
        assertThat(row.impulse()).isEqualTo(1);

        spot.set("direction_24h", JSON.createObjectNode());
        spot.set("direction_3d", JSON.getNodeFactory().numberNode(0));
        row = SwingScore.assessFlowPanel(panel).rows().get(0);
        assertThat(row.state()).isZero();
        assertThat(row.impulse()).isZero();

        spot.set("direction_24h", JSON.getNodeFactory().numberNode(Double.NaN));
        spot.set("direction_3d", JSON.getNodeFactory().numberNode(Double.POSITIVE_INFINITY));
        row = SwingScore.assessFlowPanel(panel).rows().get(0);
        assertThat(row.state()).isZero();
        assertThat(row.impulse()).isZero();

        spot.put("direction_24h", "positive then negative");
        spot.put("direction_3d", "decrease and build");
        row = SwingScore.assessFlowPanel(panel).rows().get(0);
        assertThat(row.state()).isEqualTo(1); // positive regex is intentionally checked first
        assertThat(row.impulse()).isEqualTo(-1);
    }

    @Test
    void triggerNumberCoercionCoversJsonContainersRadicesAndNonNumbers() {
        List<Arguments> vectors = List.of(
                Arguments.of(JSON.nullNode(), 2.0),
                Arguments.of(com.fasterxml.jackson.databind.node.MissingNode.getInstance(), 2.0),
                Arguments.of(JSON.getNodeFactory().booleanNode(true), 1.0),
                Arguments.of(JSON.createArrayNode(), 2.0),
                Arguments.of(JSON.createArrayNode().add("1.25"), 1.25),
                Arguments.of(JSON.createArrayNode().addNull(), 2.0),
                Arguments.of(JSON.createArrayNode().add(1).add(2), 2.0),
                Arguments.of(JSON.createObjectNode(), 2.0),
                Arguments.of("0x1", 1.0),
                Arguments.of("0b1", 1.0),
                Arguments.of("0o1", 1.0),
                Arguments.of("+0x1", 2.0),
                Arguments.of("", 2.0),
                Arguments.of("Infinity", 2.0),
                Arguments.of("-Infinity", 1.0),
                Arguments.of(new Object(), 2.0));

        vectors.forEach(vector -> {
            Object bars = vector.get()[0];
            double expected = (double) vector.get()[1];
            assertThat(SwingScore.triggerWindow(new TriggerInput(
                    "4h", true, null, null, bars, null, true)).window_bars()).isEqualTo(expected);
        });
    }

    @Test
    void activeVetoAcceptsJsonObjectsAndNullVetoLists() throws Exception {
        ScoreResult score = scoreAt(8, 0);
        JsonNode activeJson = json("{" + "\"active\":true" + "}");
        JsonNode inactiveJson = json("{" + "\"active\":false" + "}");
        ActivePhaseResult vetoed = SwingScore.activePhase(new ActivePhaseInput(
                "fallen_knives", "A", "1A", score, validTrigger(),
                List.of(activeJson, inactiveJson, "unstructured")));
        assertThat(vetoed.vetoes()).containsExactly(activeJson);
        assertThat(SwingScore.activePhase(new ActivePhaseInput(
                "fallen_knives", "A", "1A", score, validTrigger(), null)).unlocked()).isTrue();
    }

    @Test
    void nullScoreInputFieldsUseFunctionDefaultsAndExtremeFiniteImpulseMatchesJavascriptOverflow() {
        assertThat(SwingScore.scoreSwing(null)).isEqualTo(SwingScore.scoreSwing());
        ScoreResult defaulted = SwingScore.scoreSwing(new ScoreInput(null, null, null, null));
        assertThat(defaulted.mechanical()).isZero();
        assertThat(defaulted.discretion()).isZero();
        assertThat(defaulted.impulse()).isZero();
        assertThat(SwingScore.scoreSwing(new ScoreInput(Map.of(), null, 0, Double.MAX_VALUE)).impulse())
                .isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test
    void summaryHandlesNullCollectionsAndMissingOrEmptyTriggerStatuses() {
        TriggerWindow nullStatus = new TriggerWindow(
                null, "4h", true, true, null, null, null, 2, null);
        TriggerWindow emptyStatus = new TriggerWindow(
                "", "4h", true, true, null, null, null, 2, null);
        assertThat(SwingScore.setupSummary(new SetupSummaryInput(
                "fk", null, null, null, nullStatus, null)).trigger_status()).isEqualTo("WAIT");
        assertThat(SwingScore.setupSummary(new SetupSummaryInput(
                "fk", null, null, null, emptyStatus, List.of())).trigger_status()).isEqualTo("WAIT");
    }

    private static Map<String, Double> maxLegs() {
        LinkedHashMap<String, Double> legs = new LinkedHashMap<>();
        legs.put("flow", 5.0);
        legs.put("technical", 4.0);
        legs.put("macro", 3.0);
        legs.put("sentiment", 3.0);
        legs.put("valuation", 3.0);
        legs.put("structure", 2.0);
        return legs;
    }

    private static Map<String, LegComponentInput> fullComponents() {
        LinkedHashMap<String, LegComponentInput> components = new LinkedHashMap<>();
        components.put("technical", new LegComponentInput(2.0, 2.0));
        components.put("macro", new LegComponentInput(1.5, 1.5));
        components.put("sentiment", new LegComponentInput(1.5, 1.5));
        components.put("valuation", new LegComponentInput(2.0, 1.0));
        components.put("structure", new LegComponentInput(1.0, 1.0));
        return components;
    }

    private static ScoreResult scoreAt(double total, double discretion) {
        LinkedHashMap<String, Double> legs = new LinkedHashMap<>();
        double remaining = total;
        for (Map.Entry<String, Integer> entry : SwingScore.SCORE_MAXES.entrySet()) {
            double value = Math.min(entry.getValue(), remaining);
            legs.put(entry.getKey(), value);
            remaining -= value;
        }
        return SwingScore.scoreSwing(new ScoreInput(legs, null, discretion, 0));
    }

    private static TriggerWindow validTrigger() {
        return SwingScore.triggerWindow(new TriggerInput("4h", true, null, null, 2, 0, true));
    }

    private static JsonNode fullAlignedPanel() throws Exception {
        return json("""
                {"interval_hours":4,"completed_through":"2026-08-22T00:00:00Z","coverage":"COMPLETE",
                 "spot_cvd":{"available":true,"setup_signal_24h":"aligned","setup_signal_3d":"aligned"},
                 "futures_bid_ask_delta":{"available":true,"setup_signal_24h":"aligned","setup_signal_3d":"aligned"},
                 "futures_cvd":{"available":true,"setup_signal_24h":"aligned","setup_signal_3d":"aligned"},
                 "open_interest":{"available":true,"setup_signal_24h":"aligned","setup_signal_3d":"aligned"},
                 "oi_weighted_funding":{"available":true,"setup_signal_24h":"aligned","setup_signal_3d":"aligned"}}
                """);
    }

    private static JsonNode json(String value) throws Exception {
        return JSON.readTree(value);
    }
}

package com.tradinganalytics.core.swing;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.swing.SwingScore.ActivePhaseInput;
import com.tradinganalytics.core.swing.SwingScore.RiskBudgetInput;
import com.tradinganalytics.core.swing.SwingScore.ScoreInput;
import com.tradinganalytics.core.swing.SwingScore.TriggerInput;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SwingScorePropertyTest {

    @Property(tries = 1_000)
    void halfRoundingAlwaysReturnsAHalfPointWithinQuarterPoint(
            @ForAll @DoubleRange(min = -1_000, max = 1_000) double value) {
        double rounded = SwingScore.roundHalf(value);

        assertThat(rounded * 2.0).isEqualTo(Math.rint(rounded * 2.0));
        assertThat(Math.abs(rounded - value)).isLessThanOrEqualTo(0.25 + 1e-12);
    }

    @Property(tries = 500)
    void normalizedLegsStayBoundedHalfPointAndMechanicalScoreNeverExceedsTwenty(
            @ForAll @DoubleRange(min = 0, max = 5) double flow,
            @ForAll @DoubleRange(min = 0, max = 4) double technical,
            @ForAll @DoubleRange(min = 0, max = 3) double macro,
            @ForAll @DoubleRange(min = 0, max = 3) double sentiment,
            @ForAll @DoubleRange(min = 0, max = 3) double valuation,
            @ForAll @DoubleRange(min = 0, max = 2) double structure) {
        Map<String, Double> legs = Map.of(
                "flow", flow,
                "technical", technical,
                "macro", macro,
                "sentiment", sentiment,
                "valuation", valuation,
                "structure", structure);
        SwingScore.ScoreResult result = SwingScore.scoreSwing(
                new ScoreInput(legs, null, 0.0, 0.0));

        result.legs().forEach((name, value) -> {
            assertThat(value).isBetween(0.0, SwingScore.SCORE_MAXES.get(name).doubleValue());
            assertThat(value * 2.0).isEqualTo(Math.rint(value * 2.0));
        });
        assertThat(result.mechanical()).isBetween(0.0, 20.0);
        assertThat(result.mechanical() * 2.0).isEqualTo(Math.rint(result.mechanical() * 2.0));
    }

    @Property(tries = 200)
    void positiveDiscretionCanNeverUnlockABelowThresholdMechanicalScore(
            @ForAll @IntRange(min = 0, max = 1) int channelIndex) {
        String channel = channelIndex == 0 ? "A" : "B";
        SwingScore.TriggerWindow trigger = SwingScore.triggerWindow(
                new TriggerInput("4h", true, null, null, 2, 0, true));

        verifyBelowThresholdCannotUnlock("fallen_knives", channel, trigger);
        verifyBelowThresholdCannotUnlock("flying_rocket", channel, trigger);
    }

    @Property(tries = 500)
    void riskNotionalIsExactlyTheMinimumOfAllThreeNonNegativeConstraints(
            @ForAll @DoubleRange(min = 0, max = 100) double phaseCapPct,
            @ForAll @DoubleRange(min = 1, max = 10_000_000) double equityUsd,
            @ForAll @DoubleRange(min = 0.01, max = 100) double stopDistancePct,
            @ForAll @DoubleRange(min = 0, max = 20) double assetRiskPct,
            @ForAll @DoubleRange(min = 0, max = 20) double portfolioRiskPct) {
        SwingScore.AvailableRiskBudget result = (SwingScore.AvailableRiskBudget) SwingScore.riskBudget(
                new RiskBudgetInput(phaseCapPct, equityUsd, stopDistancePct,
                        assetRiskPct, portfolioRiskPct));
        double expected = Math.max(0.0, Math.min(
                equityUsd * (phaseCapPct / 100.0),
                Math.min(
                        equityUsd * (portfolioRiskPct / 100.0) / (stopDistancePct / 100.0),
                        equityUsd * (assetRiskPct / 100.0) / (stopDistancePct / 100.0))));

        assertThat(result.notional_usd()).isEqualTo(expected);
        assertThat(result.notional_usd()).isGreaterThanOrEqualTo(0.0);
        assertThat(result.notional_usd()).isLessThanOrEqualTo(result.constraints().phase_cap_usd());
        assertThat(result.notional_usd()).isLessThanOrEqualTo(result.constraints().portfolio_risk_usd());
        assertThat(result.notional_usd()).isLessThanOrEqualTo(result.constraints().asset_risk_usd());
    }

    @Property(tries = 200)
    void flowScoreIsRoundedEvidenceFamilyCountTimesOneAndAQuarter(
            @ForAll @IntRange(min = 0, max = 15) int alignedMask) {
        ObjectNode panel = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        panel.put("interval_hours", 4);
        panel.put("completed_through", "2026-08-22T00:00:00Z");
        putRow(panel, "spot_cvd", (alignedMask & 1) != 0);
        putRow(panel, "futures_bid_ask_delta", (alignedMask & 2) != 0);
        putRow(panel, "futures_cvd", (alignedMask & 2) != 0);
        putRow(panel, "open_interest", (alignedMask & 4) != 0);
        putRow(panel, "oi_weighted_funding", (alignedMask & 8) != 0);

        SwingScore.FlowAssessment result = SwingScore.assessFlowPanel(panel);
        int familyCount = Integer.bitCount(alignedMask);
        double expected = SwingScore.roundHalf(familyCount * 1.25);

        assertThat(result.coverage()).isEqualTo("COMPLETE");
        assertThat(result.aligned_evidence_families()).isEqualTo(familyCount);
        assertThat(result.score()).isEqualTo(expected);
        assertThat(result.score()).isBetween(0.0, 5.0);
    }

    private static void verifyBelowThresholdCannotUnlock(
            String framework,
            String channel,
            SwingScore.TriggerWindow trigger) {
        SwingScore.phaseThresholds(framework, channel).forEach((phase, threshold) -> {
            SwingScore.ScoreResult below = scoreAt(threshold - 0.5, 1.0);
            SwingScore.ActivePhaseResult activation = SwingScore.activePhase(new ActivePhaseInput(
                    framework, channel, phase, below, trigger, List.of()));
            assertThat(activation.score_pass()).isFalse();
            assertThat(activation.unlocked()).isFalse();
        });
    }

    private static SwingScore.ScoreResult scoreAt(double total, double discretion) {
        LinkedHashMap<String, Double> legs = new LinkedHashMap<>();
        double remaining = total;
        for (Map.Entry<String, Integer> entry : SwingScore.SCORE_MAXES.entrySet()) {
            double value = Math.min(entry.getValue(), Math.max(0.0, remaining));
            legs.put(entry.getKey(), value);
            remaining -= value;
        }
        return SwingScore.scoreSwing(new ScoreInput(legs, null, discretion, 0.0));
    }

    private static void putRow(ObjectNode panel, String name, boolean aligned) {
        ObjectNode row = panel.putObject(name);
        row.put("setup_signal_24h", aligned ? "aligned" : "neutral");
        row.put("setup_signal_3d", aligned ? "aligned" : "neutral");
    }
}

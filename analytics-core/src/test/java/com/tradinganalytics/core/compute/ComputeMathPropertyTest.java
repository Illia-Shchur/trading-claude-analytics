package com.tradinganalytics.core.compute;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComputeMathPropertyTest {

    @Property(tries = 1_000)
    void scoreRoundingStaysWithinOneAndUsesConservativeTieDirection(
            @ForAll @DoubleRange(min = -1_000_000, max = 1_000_000) double raw) {
        int up = ComputeMath.roundScore(raw, "half-up");
        int down = ComputeMath.roundScore(raw, "half-down");

        assertThat(Math.abs(up - raw)).isLessThanOrEqualTo(0.5 + 1e-12);
        assertThat(Math.abs(down - raw)).isLessThanOrEqualTo(0.5 + 1e-12);
        assertThat(Math.abs(up - down)).isLessThanOrEqualTo(1);
    }

    @Property(tries = 500)
    void percentileRankAlwaysLivesOnTheClosedPercentScale(
            @ForAll List<@DoubleRange(min = -1_000, max = 1_000) Double> values,
            @ForAll @DoubleRange(min = -1_000, max = 1_000) double current) {
        Double rank = ComputeMath.percentileRank(values, current);
        if (values.isEmpty()) {
            assertThat(rank).isNull();
        } else {
            assertThat(rank).isBetween(0.0, 100.0);
        }
    }

    @Property(tries = 300)
    void percentileRankIsMonotoneInTheQueriedValue(
            @ForAll List<@DoubleRange(min = -1_000, max = 1_000) Double> values,
            @ForAll @DoubleRange(min = -1_000, max = 1_000) double first,
            @ForAll @DoubleRange(min = -1_000, max = 1_000) double second) {
        if (values.isEmpty()) return;
        double low = Math.min(first, second);
        double high = Math.max(first, second);
        assertThat(ComputeMath.percentileRank(values, low))
                .isLessThanOrEqualTo(ComputeMath.percentileRank(values, high));
    }

    @Property(tries = 300)
    void drawdownAndCycleCapAreConsistentAcrossPriceRatios(
            @ForAll @DoubleRange(min = 0.01, max = 1) double spotToAth,
            @ForAll @DoubleRange(min = 1, max = 1_000_000) double ath) {
        double percentBelow = ComputeMath.drawdownPct(spotToAth * ath, ath);
        assertThat(percentBelow).isBetween(0.0, 100.0);
        Integer cap = ComputeMath.frPhaseCycleCap(percentBelow);
        if (percentBelow > 20) assertThat(cap).isEqualTo(8);
        else if (percentBelow >= 10) assertThat(cap).isEqualTo(14);
        else assertThat(cap).isNull();
    }

    @Property(tries = 300)
    void stopCoherenceIsExactlyStrictLessThan(
            @ForAll @DoubleRange(min = -1_000_000, max = 1_000_000) double stop,
            @ForAll @DoubleRange(min = -1_000_000, max = 1_000_000) double floor) {
        assertThat(ComputeMath.stopCoherence(stop, floor).path("pass").asBoolean())
                .isEqualTo(stop < floor);
    }

    @Property(tries = 500)
    void streakNeverExceedsInputAndStopsAtFirstBreach(
            @ForAll List<@DoubleRange(min = 0, max = 100) Double> newestFirst,
            @ForAll @DoubleRange(min = 0, max = 100) double threshold) {
        int streak = ComputeMath.fngStreak(newestFirst, threshold);
        assertThat(streak).isBetween(0, newestFirst.size());
        assertThat(newestFirst.subList(0, streak)).allMatch(value -> value <= threshold);
        if (streak < newestFirst.size()) assertThat(newestFirst.get(streak)).isGreaterThan(threshold);
    }

    @Property(tries = 500)
    void shortEvNeverLetsPositiveCarryHelpTheGate(
            @ForAll @DoubleRange(min = -20, max = 20) double directional,
            @ForAll @DoubleRange(min = -200, max = 200) double annualizedFunding,
            @ForAll @IntRange(min = 1, max = 365) int days) {
        var result = ComputeMath.shortEv(directional, annualizedFunding, (double) days, null);
        double trueCarry = result.path("carry_ev_pct_true").doubleValue();
        double floored = result.path("carry_ev_pct_floored").doubleValue();
        assertThat(floored).isLessThanOrEqualTo(0.0);
        assertThat(floored).isEqualTo(Math.min(trueCarry, 0.0));
        assertThat(result.path("total_short_ev_for_gates").doubleValue())
                .isLessThanOrEqualTo(result.path("directional_ev_pct").doubleValue());
    }
}

package com.tradinganalytics.core.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComputeMathTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void constantsExposeTheCompleteRoundingAndCalendarContract() {
        assertThat(ComputeMath.ROUNDING).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "btc", "half-up", "gold", "half-up", "eth", "half-down",
                "spx", "half-down", "sp500", "half-down", "ndx", "half-down", "nasdaq", "half-down"));
        assertThat(ComputeMath.US_MARKET_HOLIDAYS).hasSize(30)
                .contains("2025-01-01", "2026-07-03", "2027-12-24");
        assertThatThrownBy(() -> ComputeMath.ROUNDING.put("sol", "half-up"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void wilderRsiPreservesSeedConfidenceAndFlatLossSemantics() {
        ObjectNode insufficient = ComputeMath.wilderRsi(List.of(1.0, 2.0), 14);
        assertThat(insufficient.path("rsi").isNull()).isTrue();
        assertThat(insufficient.path("confidence").asText()).isEqualTo("insufficient");
        assertThat(insufficient.path("note").asText()).contains("need ≥15 closes");

        List<Double> seedOnly = IntStream.rangeClosed(1, 15).mapToObj(value -> (double) value).toList();
        ObjectNode low = ComputeMath.wilderRsi(seedOnly, 14);
        assertThat(low.path("rsi").doubleValue()).isEqualTo(100.0);
        assertThat(low.path("confidence").asText()).isEqualTo("low");

        List<Double> falling = IntStream.range(0, 30).mapToObj(i -> 100.0 - i).toList();
        ObjectNode full = ComputeMath.wilderRsi(falling, 14);
        assertThat(full.path("rsi").doubleValue()).isZero();
        assertThat(full.path("confidence").asText()).isEqualTo("ok");
    }

    @ParameterizedTest
    @MethodSource("roundingCases")
    void scoreRoundingMatchesPinnedTieConventions(double raw, String convention, int expected) {
        assertThat(ComputeMath.roundScore(raw, convention)).isEqualTo(expected);
    }

    static Stream<Arguments> roundingCases() {
        return Stream.of(
                Arguments.of(12.49, "half-up", 12), Arguments.of(12.5, "half-up", 13),
                Arguments.of(-1.5, "half-up", -1), Arguments.of(12.5, "half-down", 12),
                Arguments.of(12.51, "half-down", 13), Arguments.of(-1.5, "half-down", -2));
    }

    @Test
    void thresholdConvertersCoverEveryAllowedDenominatorAndRejectTheRest() {
        for (int active = 1; active <= 9; active++) {
            ObjectNode fk = ComputeMath.ceilThresholds(active);
            ObjectNode fr = ComputeMath.frThresholds(active);
            assertThat(fk.path("active").asInt()).isEqualTo(active);
            assertThat(fr.path("active").asInt()).isEqualTo(active);
            assertThat(fk.path("p1a").asInt()).isLessThanOrEqualTo(fk.path("p1b").asInt());
            assertThat(fk.path("p1b").asInt()).isLessThanOrEqualTo(fk.path("p2").asInt());
            assertThat(fk.path("p2").asInt()).isLessThanOrEqualTo(fk.path("p3").asInt());
            assertThat(fr.path("p3").asInt()).isGreaterThanOrEqualTo(fk.path("p3").asInt());
        }
        assertThatThrownBy(() -> ComputeMath.ceilThresholds(0))
                .isInstanceOf(ComputeMath.ComputeValidationException.class)
                .hasMessage("active denominator must be an integer 1–9");
        assertThatThrownBy(() -> ComputeMath.frThresholds(10))
                .isInstanceOf(ComputeMath.ComputeValidationException.class);
        assertThatThrownBy(() -> ComputeMath.roundScore(2.5, "bankers"))
                .isInstanceOf(ComputeMath.ComputeValidationException.class)
                .hasMessageContaining("unknown rounding convention");
    }

    @ParameterizedTest(name = "{0}({1})={2}")
    @MethodSource("bandBoundaryCases")
    void everyBandClassifierPinsItsStrictAndInclusiveEdges(String kind, double value, int expected) {
        int actual = switch (kind) {
            case "fk-sentiment" -> ComputeMath.fkSentimentBand(value);
            case "fk-mvrv" -> ComputeMath.fkMvrvBand(value);
            case "fk-drawdown" -> ComputeMath.fkDrawdownBand(value);
            case "fk-gold" -> ComputeMath.fkGoldLowVolBand(value, false);
            case "fr-euphoria" -> ComputeMath.frEuphoriaBand(value);
            case "fr-momentum" -> ComputeMath.frMomentumBand(value);
            case "fr-mvrv" -> ComputeMath.frMvrvBand(value);
            case "fr-ath" -> ComputeMath.frAthDistanceBand(value);
            case "fr-distribution" -> ComputeMath.frDistributionBand(value);
            case "fr-vulnerability" -> ComputeMath.frVulnerabilityBand(value);
            default -> throw new AssertionError(kind);
        };
        assertThat(actual).isEqualTo(expected);
    }

    static Stream<Arguments> bandBoundaryCases() {
        return Stream.of(
                Arguments.of("fk-sentiment", 10, 5), Arguments.of("fk-sentiment", 10.01, 4),
                Arguments.of("fk-mvrv", 0.099, 5), Arguments.of("fk-mvrv", 0.1, 4), Arguments.of("fk-mvrv", 5.01, -2),
                Arguments.of("fk-drawdown", 69.99, 4), Arguments.of("fk-drawdown", 70, 5),
                Arguments.of("fk-gold", 45, 2), Arguments.of("fr-euphoria", 90, 5), Arguments.of("fr-euphoria", 89.99, 4),
                Arguments.of("fr-momentum", 75, 3), Arguments.of("fr-momentum", 75.01, 4),
                Arguments.of("fr-mvrv", 5, 4), Arguments.of("fr-mvrv", 5.01, 5),
                Arguments.of("fr-ath", 5, 3), Arguments.of("fr-ath", 4.99, 5),
                Arguments.of("fr-distribution", -1, 0), Arguments.of("fr-distribution", 2.9, 2), Arguments.of("fr-distribution", 4, 3),
                Arguments.of("fr-vulnerability", -1, 0), Arguments.of("fr-vulnerability", 3, 3));
    }

    @Test
    void momentumConfidenceRuleOnlyDowngradesNearAnEdge() {
        assertThat(ComputeMath.fkMomentumBand(28, false).path("band").asInt()).isEqualTo(4);
        assertThat(ComputeMath.fkMomentumBand(28, true).path("band").asInt()).isEqualTo(3);
        assertThat(ComputeMath.fkMomentumBand(25, true).path("band").asInt()).isEqualTo(4);
        assertThat(ComputeMath.fkMomentumBand(28, true).path("low_confidence_edge_rule_applied").asBoolean()).isTrue();
    }

    @Test
    void statisticsDropNonFiniteValuesAndUseMidranksAndSampleDeviation() {
        assertThat(ComputeMath.median(List.of())).isNull();
        assertThat(ComputeMath.median(List.of(4.0, 1.0, 3.0, 2.0))).isEqualTo(2.5);
        assertThat(ComputeMath.sampleStdev(List.of(1.0))).isNull();
        assertThat(ComputeMath.sampleStdev(List.of(1.0, 2.0, 3.0))).isEqualTo(1.0);
        assertThat(ComputeMath.percentileRank(List.of(1.0, 2.0, 2.0, 4.0), 2)).isEqualTo(50.0);
        assertThat(ComputeMath.percentileRank(List.of(Double.NaN, Double.POSITIVE_INFINITY), 2)).isNull();
        ObjectNode empty = ComputeMath.distributionStats(List.of(Double.NaN));
        assertThat(empty.path("n").asInt()).isZero();
        assertThat(empty.path("mean").isNull()).isTrue();
    }

    @Test
    void volatilityRequiresFullWindowAndIgnoresInvalidPricePairs() {
        assertThat(ComputeMath.logReturns(List.of(100.0, 0.0, 101.0, -1.0, 102.0))).isEmpty();
        assertThat(ComputeMath.realizedVol(List.of(1.0, 2.0), 2, 365)).isNull();
        List<Double> closes = IntStream.rangeClosed(0, 100)
                .mapToObj(i -> 100 * Math.exp(i * 0.001 + Math.sin(i) * 0.01)).toList();
        assertThat(ComputeMath.realizedVolBlock(closes, 365).path("rv90").isNumber()).isTrue();
        assertThat(ComputeMath.rollingRealizedVol(closes, 30, 365)).hasSize(71);
    }

    @Test
    void protectiveEdgesRemainStrict() {
        assertThat(ComputeMath.stopCoherence(100, 100).path("pass").asBoolean()).isFalse();
        assertThat(ComputeMath.stopCoherence(99.99, 100).path("pass").asBoolean()).isTrue();
        assertThat(ComputeMath.frPhaseCycleCap(9.999)).isNull();
        assertThat(ComputeMath.frPhaseCycleCap(10)).isEqualTo(14);
        assertThat(ComputeMath.frPhaseCycleCap(20)).isEqualTo(14);
        assertThat(ComputeMath.frPhaseCycleCap(20.001)).isEqualTo(8);

        assertThat(ComputeMath.squeezeTrapPenalty(-5, true, true, false).path("tier").asText()).isEqualTo("none");
        assertThat(ComputeMath.squeezeTrapPenalty(-5.01, true, false, false).path("tier").asText()).isEqualTo("base");
        assertThat(ComputeMath.squeezeTrapPenalty(-5.01, true, true, false).path("tier").asText()).isEqualTo("escalated");
        assertThat(ComputeMath.squeezeTrapPenalty(0, false, true, true).path("tier").asText()).isEqualTo("escalated");
    }

    @Test
    void shortEvFloorsIncomeOnlyForGatesAndKeepsStrictThreeAndFortyPercentEdges() {
        ObjectNode income = ComputeMath.shortEv(3.0, 365.0, 1.0, 10.0);
        assertThat(income.path("carry_ev_pct_true").doubleValue()).isEqualTo(1.0);
        assertThat(income.path("carry_ev_pct_floored").doubleValue()).isZero();
        assertThat(income.path("passes_min_edge_filter").asBoolean()).isFalse();

        ObjectNode cost = ComputeMath.shortEv(5.0, -365.0, 1.0, 5.0);
        assertThat(cost.path("carry_ev_pct_floored").doubleValue()).isEqualTo(-1.0);
        assertThat(cost.path("carry_pct_of_target").doubleValue()).isEqualTo(20.0);
        assertThat(cost.path("carry_veto").asBoolean()).isFalse();
        assertThat(ComputeMath.shortEv(null, 1.0, 1.0, null).path("available").asBoolean()).isFalse();
    }

    @Test
    void unavailableProviderBlocksStayExplicitlyUnavailable() throws Exception {
        assertThat(ComputeMath.basisBlock(null, 100.0, null, null).path("available").asBoolean()).isFalse();
        assertThat(ComputeMath.netLiquidity(null, 1.0, 1.0).path("available").asBoolean()).isFalse();
        assertThat(ComputeMath.borrowBlock(JSON.readTree("[]")).path("available").asBoolean()).isFalse();
        assertThat(ComputeMath.stablecoinBlock(JSON.readTree("[]")).path("available").asBoolean()).isFalse();
        assertThat(ComputeMath.deribitVolBlock(JSON.createArrayNode(), JSON.createArrayNode(), null, 0)
                .path("available").asBoolean()).isFalse();
        assertThat(ComputeMath.positioningBlock(JSON.createArrayNode(), JSON.createArrayNode(), JSON.createArrayNode())
                .path("history_days").asInt()).isZero();
    }

    @Test
    void exportedCorrelationPrimitivesPreserveAlignmentAndFailClosedDefaults() throws Exception {
        ArrayNode first = (ArrayNode) JSON.readTree("""
                [{"date":"2026-01-01","value":1},{"date":"2026-01-02","value":2}]
                """);
        ArrayNode second = (ArrayNode) JSON.readTree("""
                [{"date":"2026-01-02","value":20},{"date":"2026-01-03","value":30}]
                """);
        ObjectNode aligned = ComputeMath.alignSeries(first, second);
        assertThat(aligned.path("dates").findValuesAsText("")).isEmpty();
        assertThat(aligned.path("dates").get(0).asText()).isEqualTo("2026-01-02");
        assertThat(aligned.path("xs").get(0).asInt()).isEqualTo(2);
        assertThat(aligned.path("ys").get(0).asInt()).isEqualTo(20);
        assertThat(aligned.path("dropped").path("a").asInt()).isOne();
        assertThat(aligned.path("dropped").path("b").asInt()).isOne();
        assertThat(ComputeMath.pearson(List.of(1.0, 2.0, 3.0), List.of(3.0, 2.0, 1.0)))
                .isEqualTo(-1.0);
        assertThat(ComputeMath.pearson(List.of(1.0, 1.0), List.of(1.0, 2.0))).isNull();
        assertThatThrownBy(() -> ComputeMath.pearson(List.of(1.0), List.of(1.0, 2.0)))
                .isInstanceOf(ComputeMath.ComputeValidationException.class);
        assertThat(ComputeMath.corrSurcharge(0.7)).isFalse();
        assertThat(ComputeMath.corrSurcharge(0.7001)).isTrue();
        assertThat(ComputeMath.correlationRegime(null).path("phase2_corr_condition").asBoolean()).isTrue();
    }

    @Test
    void channelBExportedBandsPinQualifiersAndMaturityEdges() {
        assertThat(ComputeMath.frChannel(null, true, true)).isEqualTo("none");
        assertThat(ComputeMath.frChannel(20.0, false, false)).isEqualTo("A");
        assertThat(ComputeMath.frChannel(20.1, true, true)).isEqualTo("B");
        assertThat(ComputeMath.frChannel(20.1, true, false)).isEqualTo("none");
        assertThat(ComputeMath.frBRallyBand(18)).isEqualTo(2);
        assertThat(ComputeMath.frBRallyBand(18.01)).isEqualTo(3);
        assertThat(ComputeMath.frBMomentumBand(66, 50.0)).isZero();
        assertThat(ComputeMath.frBMomentumBand(66, 49.99)).isEqualTo(4);
        assertThat(ComputeMath.frBResistanceBand(4)).isEqualTo(5);
        assertThat(ComputeMath.frBStructureBand(9)).isEqualTo(3);
        assertThat(ComputeMath.frBSentimentBand(-1)).isZero();
        assertThat(ComputeMath.frBMaturityPenalty(7.99)).isEqualTo(-2);
        assertThat(ComputeMath.frBMaturityPenalty(8)).isZero();
    }

    @Test
    void calendarDistinguishesEquityHolidaysFromAlwaysOpenCrypto() {
        assertThat(ComputeMath.weekdayOf("2026-07-03")).isEqualTo("Friday");
        assertThat(ComputeMath.isTradingDay("2026-07-03", "equity")).isFalse();
        assertThat(ComputeMath.isTradingDay("2026-07-03", "crypto")).isTrue();
        assertThat(ComputeMath.nextNTradingDays("2026-07-02", 3, "equity"))
                .containsExactly("2026-07-06", "2026-07-07", "2026-07-08");
        assertThat(ComputeMath.nextNTradingDays("2026-07-02", 3, "crypto"))
                .containsExactly("2026-07-03", "2026-07-04", "2026-07-05");
    }

    @Test
    void commandSupportsAtFileJsonAndCapturesErrorsWithoutMutatingGlobalStreams(@TempDir Path temp) throws Exception {
        Path ticker = temp.resolve("ticker.json");
        Files.writeString(ticker, "[3.56e-8,2e-8,2,0.82,4e-8,7,1.27]");
        ComputeCommand command = new ComputeCommand(temp,
                Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneOffset.UTC));

        ComputeCommand.Result success = command.execute("borrow", "--ticker", "@" + ticker);
        assertThat(success.exitCode()).isZero();
        assertThat(success.stderr()).isEmpty();
        assertThat(JSON.readTree(success.stdout()).path("available").asBoolean()).isTrue();

        ComputeCommand.Result failure = command.execute("round", "oops", "--asset", "btc");
        assertThat(failure.exitCode()).isEqualTo(1);
        assertThat(failure.stdout()).isEmpty();
        assertThat(failure.stderr()).isEqualTo("error: not a number: oops\n");
    }

    @Test
    void directCommandApiDefaultConstructorAndJsonSyntaxDiagnosticAreUsable() throws Exception {
        ComputeCommand command = new ComputeCommand();
        assertThat(command.compute("round", "2.5", "--asset", "btc").path("adjusted").asInt())
                .isEqualTo(3);

        ComputeCommand.Result malformed = command.execute("borrow", "--ticker", "{");
        assertThat(malformed.exitCode()).isEqualTo(1);
        assertThat(malformed.stdout()).isEmpty();
        assertThat(malformed.stderr()).startsWith("SyntaxError:").endsWith("\n");
    }

    @Test
    void javascriptNumberCoercionCoversJsonScalarsAndArrays() throws Exception {
        assertThat(ComputeMath.jsNumber(JSON.readTree("null"))).isZero();
        assertThat(ComputeMath.jsNumber(JSON.readTree("true"))).isEqualTo(1.0);
        assertThat(ComputeMath.jsNumber(JSON.readTree("\" 0x10 \""))).isEqualTo(16.0);
        assertThat(ComputeMath.jsNumber(JSON.readTree("[]"))).isZero();
        assertThat(ComputeMath.jsNumber(JSON.readTree("[2]"))).isEqualTo(2.0);
        assertThat(ComputeMath.jsNumber(JSON.readTree("[null]"))).isZero();
        assertThat(ComputeMath.jsNumber(JSON.readTree("[1,2]"))).isNaN();
        assertThat(ComputeMath.jsNumber(JSON.readTree("{}"))).isNaN();
    }
}

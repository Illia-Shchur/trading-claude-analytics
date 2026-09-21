package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared, pure arithmetic for the frozen v002 core and staged evidence calculations. */
final class LiquidationV2EvidenceMathV1 {
    static final String FULL_POSITION_RISK_NUMERIC_REPRESENTATION =
            "CANONICAL_JSON_BINARY64_ROUNDING_INTERVAL_INTERSECTION;ACCOUNTING_ARITHMETIC_UNCHANGED";

    private record Binary64RoundingInterval(BigDecimal lower, BigDecimal upper) {
        boolean overlaps(Binary64RoundingInterval other) {
            return lower.compareTo(other.upper) <= 0 && other.lower.compareTo(upper) <= 0;
        }
    }

    record MarketInterval(String id, String asset, Instant decisionTime, Instant firstFillTime, Instant exitTime) {
        MarketInterval {
            if (id == null || id.isBlank() || asset == null || asset.isBlank() || decisionTime == null) {
                throw new IllegalArgumentException("market interval requires identity, asset and decision time");
            }
            if (exitTime != null && (firstFillTime == null || exitTime.isBefore(firstFillTime))) {
                throw new IllegalArgumentException("market interval exit must follow its first fill");
            }
            if (firstFillTime != null && firstFillTime.isBefore(decisionTime)) {
                throw new IllegalArgumentException("market interval first fill cannot precede its decision");
            }
        }
    }

    private LiquidationV2EvidenceMathV1() {}

    /** Builds exact frozen-window blocks, merging nominal boundaries crossed by any position. */
    static List<LiquidationChronologicalPolicyV1.MarketTimeBlock> marketTimeBlocks(ObjectNode profile,
            Collection<MarketInterval> intervals) {
        LiquidationDailyStressProfileV1.validate(profile);
        Instant start = Instant.parse(profile.path("windows").path("decision_start").asText());
        Instant end = Instant.parse(profile.path("windows").path("decision_end_exclusive").asText());
        long totalDays = Duration.between(start, end).toDays();
        int blockDays = profile.path("fold_contract").path("outer_and_inner_purge_days").asInt(-1);
        if (blockDays != LiquidationChronologicalPolicyV1.MINIMUM_BLOCK_DAYS || totalDays < blockDays) {
            throw new IllegalArgumentException("frozen v002 evidence requires at least one 67-day dependence block");
        }
        List<MarketInterval> rows = List.copyOf(intervals);
        Set<String> ids = new HashSet<>();
        for (MarketInterval row : rows) {
            if (!ids.add(row.id())) throw new IllegalArgumentException("duplicate market interval identity: " + row.id());
            if (row.decisionTime().isBefore(start) || !row.decisionTime().isBefore(end)) {
                throw new IllegalArgumentException("market interval decision is outside the frozen profile window: " + row.id());
            }
        }
        int fullBlocks = Math.toIntExact(totalDays / blockDays);
        ArrayList<Instant> boundaries = new ArrayList<>();
        boundaries.add(start);
        for (int index = 1; index < fullBlocks; index++) {
            Instant boundary = start.plus(Duration.ofDays((long) index * blockDays));
            if (!hasPositionCrossing(boundary, rows)) boundaries.add(boundary);
        }
        boundaries.add(end); // the final partial tail belongs to the preceding block
        ArrayList<LiquidationChronologicalPolicyV1.MarketTimeBlock> result = new ArrayList<>();
        for (int index = 0; index + 1 < boundaries.size(); index++) {
            Instant from = boundaries.get(index), until = boundaries.get(index + 1);
            List<String> observationIds = rows.stream()
                    .filter(row -> !row.decisionTime().isBefore(from) && row.decisionTime().isBefore(until))
                    .map(MarketInterval::id).sorted().toList();
            result.add(new LiquidationChronologicalPolicyV1.MarketTimeBlock(
                    "market-time-67d-" + String.format(java.util.Locale.ROOT, "%02d", index + 1),
                    from, until, observationIds));
        }
        return List.copyOf(result);
    }

    /** Conservative connected-component count over actual completed trade episodes. */
    static int independentCompletedEpisodes(Collection<MarketInterval> intervals, int blockDays) {
        if (blockDays < 1) throw new IllegalArgumentException("dependence span must be positive");
        List<MarketInterval> rows = intervals.stream().filter(row -> row.firstFillTime() != null && row.exitTime() != null)
                .sorted(Comparator.comparing(MarketInterval::decisionTime).thenComparing(MarketInterval::id)).toList();
        int components = 0;
        Instant decisionEnd = null, exitEnd = null;
        for (MarketInterval row : rows) {
            boolean connected = decisionEnd != null && row.decisionTime().isBefore(decisionEnd);
            connected |= exitEnd != null && !row.firstFillTime().isAfter(exitEnd);
            if (!connected) components++;
            Instant rowDecisionEnd = row.decisionTime().plus(Duration.ofDays(blockDays));
            if (decisionEnd == null || rowDecisionEnd.isAfter(decisionEnd)) decisionEnd = rowDecisionEnd;
            if (exitEnd == null || row.exitTime().isAfter(exitEnd)) exitEnd = row.exitTime();
        }
        return components;
    }

    /** Applies the same sampled block indices to a per-observation vector. */
    static List<Double> synchronizedBlockMeans(LiquidationChronologicalPolicyV1.SynchronizedBlockSample sample,
            List<LiquidationChronologicalPolicyV1.MarketTimeBlock> blocks, Map<String, Double> valuesByObservation) {
        Map<String, List<String>> observationsByBlock = new java.util.HashMap<>();
        for (LiquidationChronologicalPolicyV1.MarketTimeBlock block : blocks) {
            observationsByBlock.put(block.blockId(), block.synchronizedObservationIds());
        }
        ArrayList<Double> result = new ArrayList<>(sample.draws().size());
        for (LiquidationChronologicalPolicyV1.BlockDraw draw : sample.draws()) {
            ArrayList<Double> selected = new ArrayList<>();
            for (String blockId : draw.sampledBlockIds()) {
                for (String id : observationsByBlock.getOrDefault(blockId, List.of())) {
                    Double value = valuesByObservation.get(id);
                    if (value != null) selected.add(value);
                }
            }
            result.add(mean(selected));
        }
        return List.copyOf(result);
    }

    static double mean(Collection<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /** Nearest-rank lower fifth quintile used by the v002 synchronized bootstrap. */
    static double percentile20(Collection<Double> values) {
        if (values.isEmpty()) return 0.0;
        ArrayList<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * 0.20) - 1));
    }

    /** Add-one empirical p-value for a frozen centered-null statistic distribution. */
    static double adjustedPValue(Collection<Double> maximumNull, double observedContrast) {
        if (maximumNull.isEmpty()) return 1.0;
        long exceedances = maximumNull.stream().filter(value -> value >= observedContrast).count();
        return (exceedances + 1.0) / (maximumNull.size() + 1.0);
    }

    static boolean zeroVariance(Collection<Double> values) {
        if (values.size() < 2) return true;
        double first = values.iterator().next();
        return values.stream().allMatch(value -> Double.compare(value, first) == 0);
    }

    /** Signed canonical drawdown of the cumulative completed-trade net-R path. */
    static double cumulativeTradeDrawdownR(Collection<Double> orderedNetR) {
        return StrategyStatisticalV5.drawdown(orderedNetR);
    }

    /** Cost-R is a trade-count-weighted mean of per-position cost divided by that position's R. */
    static double meanPositionCostR(Collection<Double> perPositionCostR) {
        return mean(perPositionCostR);
    }

    /**
     * Tests whether two serialized positive binary64 values could represent the same exact
     * quantity after independent round-to-nearest serialization. Bounds are exact decimal
     * midpoints between adjacent doubles; there is no arbitrary epsilon or ULP-count allowance.
     */
    static boolean roundedBinary64IntervalsOverlap(double first, double second) {
        Binary64RoundingInterval a = roundingInterval(first), b = roundingInterval(second);
        return a != null && b != null && a.overlaps(b);
    }

    /**
     * Tests whether a serialized output could be the exact positive-decimal multiple of the
     * serialized input, accounting only for their independent binary64 rounding intervals.
     */
    static boolean scaledRoundedBinary64IntervalsOverlap(double input, double output, BigDecimal multiplier) {
        if (multiplier == null || multiplier.signum() <= 0) return false;
        Binary64RoundingInterval inputInterval = roundingInterval(input);
        Binary64RoundingInterval outputInterval = roundingInterval(output);
        if (inputInterval == null || outputInterval == null) return false;
        Binary64RoundingInterval scaledInput = new Binary64RoundingInterval(
                inputInterval.lower().multiply(multiplier), inputInterval.upper().multiply(multiplier));
        return scaledInput.overlaps(outputInterval);
    }

    private static Binary64RoundingInterval roundingInterval(double value) {
        if (!Double.isFinite(value) || value <= 0.0) return null;
        double previous = Math.nextDown(value), next = Math.nextUp(value);
        if (!Double.isFinite(previous) || !Double.isFinite(next)) return null;
        BigDecimal exact = new BigDecimal(value);
        BigDecimal lower = exact.add(new BigDecimal(previous)).divide(BigDecimal.valueOf(2));
        BigDecimal upper = exact.add(new BigDecimal(next)).divide(BigDecimal.valueOf(2));
        return new Binary64RoundingInterval(lower, upper);
    }

    /** Returns null when any required paid-cost component is absent or malformed. */
    static Double positionCostR(com.fasterxml.jackson.databind.JsonNode episode, java.math.BigDecimal referenceRisk) {
        if (episode == null || referenceRisk == null || referenceRisk.signum() <= 0
                || !finiteNonnegative(episode.path("entry_costs_usdt"))
                || !finiteNonnegative(episode.path("exit_costs_usdt"))
                || !finiteNonnegative(episode.path("funding_debits_for_risk_headroom_usdt"))) return null;
        java.math.BigDecimal costs = episode.path("entry_costs_usdt").decimalValue()
                .add(episode.path("exit_costs_usdt").decimalValue())
                .add(episode.path("funding_debits_for_risk_headroom_usdt").decimalValue());
        return costs.divide(referenceRisk, java.math.MathContext.DECIMAL128).doubleValue();
    }

    private static boolean finiteNonnegative(com.fasterxml.jackson.databind.JsonNode value) {
        return value != null && value.isNumber() && Double.isFinite(value.asDouble())
                && value.decimalValue().signum() >= 0;
    }

    private static boolean hasPositionCrossing(Instant boundary, List<MarketInterval> rows) {
        for (MarketInterval row : rows) {
            if (row.firstFillTime() == null || !row.firstFillTime().isBefore(boundary)) continue;
            Instant end = row.exitTime() == null
                    ? row.firstFillTime().plus(Duration.ofDays(60)) : row.exitTime();
            if (!end.isBefore(boundary)) return true;
        }
        return false;
    }
}

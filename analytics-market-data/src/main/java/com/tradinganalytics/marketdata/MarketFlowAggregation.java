package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.compute.ComputeMath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Provider-neutral aggregation and completed-candle resampling from {@code tools/lib.mjs}. */
public final class MarketFlowAggregation {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private MarketFlowAggregation() {
    }

    public static ArrayNode aggregateFlowRows(ArrayNode groups) {
        List<String> expectedSymbols = expectedSymbols(groups);
        TreeMap<Long, FlowAccumulator> byTime = new TreeMap<>();
        for (JsonNode group : iterable(groups)) {
            String symbol = group.path("symbol").asText("");
            for (JsonNode row : iterable(group.get("rows"))) {
                Double rawTime = finite(row.get("time"));
                Double buy = finite(row.get("buy_usd"));
                Double sell = finite(row.get("sell_usd"));
                Double close = finite(row.get("close"));
                if (rawTime == null || buy == null || sell == null || buy < 0.0 || sell < 0.0) continue;
                long time = normalizedTime(rawTime);
                FlowAccumulator current = byTime.computeIfAbsent(time, FlowAccumulator::new);
                current.buy += buy;
                current.sell += sell;
                double gross = buy + sell;
                if (close != null && gross > 0.0) {
                    current.closeNumerator += close * gross;
                    current.closeDenominator += gross;
                }
                if (!symbol.isEmpty()) current.symbols.add(symbol);
            }
        }
        ArrayNode output = NODES.arrayNode();
        byTime.values().forEach(row -> {
            ObjectNode value = output.addObject();
            value.put("time", row.time);
            value.put("buy_usd", ComputeMath.round2(row.buy));
            value.put("sell_usd", ComputeMath.round2(row.sell));
            if (row.closeDenominator != 0.0) {
                value.put("close", ComputeMath.round2(row.closeNumerator / row.closeDenominator));
            } else {
                value.set("close", NullNode.instance);
            }
            appendCoverage(value, row.symbols, expectedSymbols);
        });
        return output;
    }

    public static ArrayNode aggregateValueSnapshots(ArrayNode groups, long timeBucketMillis) {
        List<String> expectedSymbols = expectedSymbols(groups);
        TreeMap<Long, ValueAccumulator> byTime = new TreeMap<>();
        for (JsonNode group : iterable(groups)) {
            String symbol = group.path("symbol").asText("");
            for (JsonNode row : iterable(group.get("rows"))) {
                Double rawTime = finite(row.get("time"));
                Double rawValue = finite(row.get("value"));
                if (rawTime == null || rawValue == null || rawValue < 0.0) continue;
                long time = Math.floorDiv(normalizedTime(rawTime), timeBucketMillis) * timeBucketMillis;
                ValueAccumulator current = byTime.computeIfAbsent(time, ValueAccumulator::new);
                current.value += rawValue;
                if (!symbol.isEmpty()) current.symbols.add(symbol);
            }
        }
        ArrayNode output = NODES.arrayNode();
        byTime.values().forEach(row -> {
            ObjectNode value = output.addObject();
            value.put("time", row.time);
            value.put("value", ComputeMath.round2(row.value));
            appendCoverage(value, row.symbols, expectedSymbols);
        });
        return output;
    }

    public static ArrayNode oiWeightedFundingSnapshots(ArrayNode oiGroups, ArrayNode fundingGroups,
                                                        long timeBucketMillis) {
        Map<String, List<FundingPoint>> fundingBySymbol = new HashMap<>();
        for (JsonNode group : iterable(fundingGroups)) {
            String symbol = group.path("symbol").asText("");
            List<FundingPoint> rows = new ArrayList<>();
            for (JsonNode row : iterable(group.get("rows"))) {
                Double rawTime = finite(row.get("time"));
                Double rate = finite(row.get("rate"));
                if (rawTime != null && rate != null) rows.add(new FundingPoint(normalizedTime(rawTime), rate));
            }
            rows.sort(java.util.Comparator.comparingLong(FundingPoint::time));
            if (!symbol.isEmpty() && !rows.isEmpty()) fundingBySymbol.put(symbol, rows);
        }
        Set<String> expectedSet = new HashSet<>();
        for (JsonNode group : iterable(oiGroups)) {
            String symbol = group.path("symbol").asText("");
            if (fundingBySymbol.containsKey(symbol)) expectedSet.add(symbol);
        }
        List<String> expectedSymbols = new ArrayList<>(expectedSet);
        Collections.sort(expectedSymbols);
        TreeMap<Long, WeightedAccumulator> byTime = new TreeMap<>();
        for (JsonNode group : iterable(oiGroups)) {
            String symbol = group.path("symbol").asText("");
            List<FundingPoint> funding = fundingBySymbol.get(symbol);
            if (funding == null) continue;
            List<ValuePoint> oiRows = new ArrayList<>();
            for (JsonNode row : iterable(group.get("rows"))) {
                Double rawTime = finite(row.get("time"));
                Double value = finite(row.get("value"));
                if (rawTime != null && value != null && value >= 0.0) {
                    long time = Math.floorDiv(normalizedTime(rawTime), timeBucketMillis) * timeBucketMillis;
                    oiRows.add(new ValuePoint(time, value));
                }
            }
            oiRows.sort(java.util.Comparator.comparingLong(ValuePoint::time));
            int fundingIndex = -1;
            for (ValuePoint oi : oiRows) {
                while (fundingIndex + 1 < funding.size() && funding.get(fundingIndex + 1).time() <= oi.time()) {
                    fundingIndex++;
                }
                if (fundingIndex < 0) continue;
                WeightedAccumulator current = byTime.computeIfAbsent(oi.time(), WeightedAccumulator::new);
                current.weighted += funding.get(fundingIndex).rate() * oi.value();
                current.totalOi += oi.value();
                current.symbols.add(symbol);
            }
        }
        ArrayNode output = NODES.arrayNode();
        byTime.values().stream().filter(row -> row.totalOi > 0.0).forEach(row -> {
            ObjectNode value = output.addObject();
            value.put("time", row.time);
            value.put("value", row.weighted / row.totalOi);
            value.put("total_oi_usd", ComputeMath.round2(row.totalOi));
            appendCoverage(value, row.symbols, expectedSymbols);
        });
        return output;
    }

    public static ArrayNode resampleSnapshotsToCandles(ArrayNode rows, int intervalHours,
                                                        int sampleMinutes, int maxBars, long nowMillis) {
        long width = intervalHours * 3_600_000L;
        long expectedSamples = Math.round(intervalHours * 60.0 / sampleMinutes);
        TreeMap<Long, CandleAccumulator> buckets = new TreeMap<>();
        for (JsonNode row : iterable(rows)) {
            Double rawTime = finite(row.get("time"));
            Double value = finite(row.get("value"));
            if (rawTime == null || value == null) continue;
            long time = normalizedTime(rawTime);
            long bucket = Math.floorDiv(time, width) * width;
            if (bucket + width > nowMillis) continue;
            CandleAccumulator current = buckets.computeIfAbsent(bucket, CandleAccumulator::new);
            current.values.add(new ValuePoint(time, value));
            Double coverage = finite(row.get("coverage_count"));
            Double expected = finite(row.get("expected_count"));
            if (coverage != null) current.coverage.add(coverage);
            if (expected != null) current.expected.add(expected);
        }
        List<CandleAccumulator> selected = new ArrayList<>(buckets.values());
        if (selected.size() > maxBars) selected = selected.subList(selected.size() - maxBars, selected.size());
        ArrayNode output = NODES.arrayNode();
        for (CandleAccumulator bucket : selected) {
            bucket.values.sort(java.util.Comparator.comparingLong(ValuePoint::time));
            List<Double> values = bucket.values.stream().map(ValuePoint::value).toList();
            Double minimumCoverage = bucket.coverage.stream().mapToDouble(Double::doubleValue).min().stream().boxed().findFirst().orElse(null);
            Double maximumExpected = bucket.expected.stream().mapToDouble(Double::doubleValue).max().stream().boxed().findFirst().orElse(null);
            ObjectNode candle = output.addObject();
            candle.put("time", bucket.time);
            candle.put("open", values.get(0));
            candle.put("high", values.stream().mapToDouble(Double::doubleValue).max().orElseThrow());
            candle.put("low", values.stream().mapToDouble(Double::doubleValue).min().orElseThrow());
            candle.put("close", values.get(values.size() - 1));
            candle.put("samples", values.size());
            candle.put("expected_samples", expectedSamples);
            candle.put("sampling_complete", values.size() >= expectedSamples);
            putNullable(candle, "min_contract_coverage", minimumCoverage);
            putNullable(candle, "expected_contracts", maximumExpected);
            if (minimumCoverage == null || maximumExpected == null) {
                candle.set("contract_coverage_complete", NullNode.instance);
            } else {
                candle.put("contract_coverage_complete", Double.compare(minimumCoverage, maximumExpected) == 0);
            }
        }
        return output;
    }

    private static List<String> expectedSymbols(ArrayNode groups) {
        Set<String> values = new HashSet<>();
        for (JsonNode group : iterable(groups)) {
            String symbol = group.path("symbol").asText("");
            if (!symbol.isEmpty()) values.add(symbol);
        }
        List<String> output = new ArrayList<>(values);
        Collections.sort(output);
        return output;
    }

    private static void appendCoverage(ObjectNode target, Set<String> covered, List<String> expected) {
        List<String> sorted = new ArrayList<>(covered);
        Collections.sort(sorted);
        ArrayNode symbols = target.putArray("symbols_covered");
        sorted.forEach(symbols::add);
        target.put("coverage_count", covered.size());
        target.put("expected_count", expected.size());
        target.put("coverage_complete", !expected.isEmpty() && covered.size() == expected.size());
    }

    private static Iterable<JsonNode> iterable(JsonNode value) {
        return value != null && value.isArray() ? value : List.of();
    }

    private static Double finite(JsonNode value) {
        if (value == null || value.isMissingNode()) return null;
        double number = ComputeMath.jsNumber(value);
        return Double.isFinite(number) ? number : null;
    }

    private static long normalizedTime(double rawTime) {
        return (long) (rawTime < 1e12 ? rawTime * 1_000.0 : rawTime);
    }

    private static void putNullable(ObjectNode target, String key, Double value) {
        if (value == null) target.set(key, NullNode.instance); else target.put(key, value);
    }

    private static final class FlowAccumulator {
        private final long time;
        private double buy;
        private double sell;
        private double closeNumerator;
        private double closeDenominator;
        private final Set<String> symbols = new LinkedHashSet<>();

        private FlowAccumulator(long time) { this.time = time; }
    }

    private static class ValueAccumulator {
        private final long time;
        private double value;
        private final Set<String> symbols = new LinkedHashSet<>();

        private ValueAccumulator(long time) { this.time = time; }
    }

    private static final class WeightedAccumulator {
        private final long time;
        private double weighted;
        private double totalOi;
        private final Set<String> symbols = new LinkedHashSet<>();

        private WeightedAccumulator(long time) { this.time = time; }
    }

    private static final class CandleAccumulator {
        private final long time;
        private final List<ValuePoint> values = new ArrayList<>();
        private final List<Double> coverage = new ArrayList<>();
        private final List<Double> expected = new ArrayList<>();

        private CandleAccumulator(long time) { this.time = time; }
    }

    private record FundingPoint(long time, double rate) { }
    private record ValuePoint(long time, double value) { }
}

package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.compute.ComputeMath;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Pure completed-bar series and spot-panel contracts from {@code tools/lib.mjs}. */
public final class MarketSeriesAnalytics {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private MarketSeriesAnalytics() {
    }

    public static ObjectNode spotPanel(ArrayNode quotes, long nowMillis, int windowMinutes, double spreadFlagPercent) {
        if (quotes == null || quotes.isEmpty()) {
            ObjectNode output = NODES.objectNode();
            output.set("canonical", NullNode.instance);
            output.put("method", "median");
            output.set("median_kind", NullNode.instance);
            output.put("n_sources", 0);
            output.put("n_synchronized", 0);
            output.set("spread_pct", NullNode.instance);
            output.set("spread_gt_0_5pct", NullNode.instance);
            output.put("low_confidence", true);
            output.put("low_confidence_reason", "no quotes supplied");
            output.put("synchronized_window_min", windowMinutes);
            output.set("sources", NODES.arrayNode());
            output.set("excluded", NODES.arrayNode());
            output.set("priority_first", NullNode.instance);
            output.set("priority_first_delta_pct", NullNode.instance);
            output.put("warning", "no usable spot quotes");
            return output;
        }

        long windowMillis = windowMinutes * 60_000L;
        List<JsonNode> fresh = new ArrayList<>();
        List<JsonNode> stale = new ArrayList<>();
        List<JsonNode> barCloses = new ArrayList<>();
        for (JsonNode quote : quotes) {
            if ("bar_close".equals(quote.path("ts_kind").asText())) {
                barCloses.add(quote);
                continue;
            }
            JsonNode timestamp = quote.get("ts");
            if (timestamp == null || timestamp.isNull() || nowMillis - timestamp.longValue() <= windowMillis) {
                fresh.add(quote);
            } else {
                stale.add(quote);
            }
        }

        Double provisionalMedian = medianValues(fresh);
        ArrayNode included = NODES.arrayNode();
        fresh.forEach(value -> included.add(value.deepCopy()));
        ArrayNode excluded = NODES.arrayNode();
        for (JsonNode quote : stale) {
            long ageMinutes = Math.round((nowMillis - quote.path("ts").doubleValue()) / 60_000.0);
            Double deltaPercent = provisionalMedian != null && provisionalMedian != 0.0
                    ? Math.abs(quote.path("value").doubleValue() / provisionalMedian - 1.0) * 100.0 : null;
            ObjectNode row = ((ObjectNode) quote).deepCopy();
            row.put("age_min", ageMinutes);
            if (deltaPercent != null && deltaPercent <= spreadFlagPercent) {
                row.set("reason", NullNode.instance);
                row.put("note", "stale but within tolerance of the live cluster — excluded from the median, not flagged");
            } else {
                row.put("reason", "EXCLUDED — outside " + windowMinutes + "min window, divergent");
            }
            excluded.add(row);
        }
        for (JsonNode quote : barCloses) {
            ObjectNode row = ((ObjectNode) quote).deepCopy();
            row.set("age_min", NullNode.instance);
            row.put("reason", "frozen bar close — never enters the median");
            excluded.add(row);
        }

        List<Double> values = new ArrayList<>();
        fresh.forEach(value -> values.add(value.path("value").doubleValue()));
        Double canonical = ComputeMath.median(values);
        Double spreadPercent = null;
        if (values.size() >= 2) {
            double minimum = values.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            double maximum = values.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            spreadPercent = round3((maximum - minimum) / minimum * 100.0);
        } else if (values.size() == 1) {
            spreadPercent = 0.0;
        }
        Boolean spreadGreater = spreadPercent == null ? null : spreadPercent > spreadFlagPercent;
        JsonNode firstValue = quotes.get(0).get("value");
        Double priorityFirst = firstValue != null && firstValue.isNumber() ? firstValue.doubleValue() : null;
        Double priorityDelta = canonical != null && priorityFirst != null
                ? round3((priorityFirst / canonical - 1.0) * 100.0) : null;
        boolean lowConfidence = values.size() < 2;

        ObjectNode output = NODES.objectNode();
        putNullable(output, "canonical", canonical);
        output.put("method", "median");
        output.put("median_kind", values.size() + "-source");
        output.put("n_sources", quotes.size());
        output.put("n_synchronized", values.size());
        putNullable(output, "spread_pct", spreadPercent);
        putNullable(output, "spread_gt_0_5pct", spreadGreater);
        output.put("low_confidence", lowConfidence);
        if (lowConfidence) {
            output.put("low_confidence_reason", values.isEmpty()
                    ? "no synchronized quotes available"
                    : "only one synchronized quote — no independent cross-check");
        } else {
            output.set("low_confidence_reason", NullNode.instance);
        }
        output.put("synchronized_window_min", windowMinutes);
        output.set("sources", included);
        output.set("excluded", excluded);
        putNullable(output, "priority_first", priorityFirst);
        putNullable(output, "priority_first_delta_pct", priorityDelta);
        if (Boolean.TRUE.equals(spreadGreater)) {
            output.put("warning", "inter-source spread " + numberText(spreadPercent) + "% > "
                    + numberText(spreadFlagPercent) + "% — reconcile before scoring");
        } else {
            output.set("warning", NullNode.instance);
        }
        return output;
    }

    public static Double percentChange(List<Double> values, int periods) {
        if (values == null || values.size() <= periods || periods < 1) return null;
        double from = values.get(values.size() - 1 - periods);
        double to = values.get(values.size() - 1);
        return from == 0.0 ? null : ComputeMath.round2((to / from - 1.0) * 100.0);
    }

    public static <T> int consecutiveRun(List<T> values, Predicate<T> predicate, boolean fromEnd) {
        if (values == null || values.isEmpty()) return 0;
        int count = 0;
        if (fromEnd) {
            for (int index = values.size() - 1; index >= 0; index--) {
                if (!predicate.test(values.get(index))) break;
                count++;
            }
        } else {
            for (T value : values) {
                if (!predicate.test(value)) break;
                count++;
            }
        }
        return count;
    }

    public static Double smaSlope(List<Double> values, int periods, int lookback) {
        if (values == null || values.size() < periods + lookback) return null;
        Double current = ComputeMath.sma(values, periods);
        Double previous = ComputeMath.sma(values.subList(0, values.size() - lookback), periods);
        return current == null || previous == null || previous == 0.0
                ? null : ComputeMath.round2((current / previous - 1.0) * 100.0);
    }

    public static List<Double> rollingRealizedVol(List<Double> closes, int window, int annualize) {
        return ComputeMath.rollingRealizedVol(closes, window, annualize);
    }

    public static List<Double> rollingWilderRsi(List<Double> closes, int period) {
        List<Double> output = new ArrayList<>();
        if (closes == null) return output;
        for (int end = period + 1; end <= closes.size(); end++) {
            JsonNode reading = ComputeMath.wilderRsi(closes.subList(0, end), period).get("rsi");
            if (reading != null && reading.isNumber()) output.add(reading.doubleValue());
        }
        return output;
    }

    public static List<Double> rollingDrawdownFromAth(List<Double> closes) {
        List<Double> output = new ArrayList<>();
        if (closes == null) return output;
        double runningHigh = Double.NEGATIVE_INFINITY;
        for (Double close : closes) {
            if (close == null) continue;
            runningHigh = Math.max(runningHigh, close);
            output.add(ComputeMath.drawdownPct(close, runningHigh));
        }
        return output;
    }

    public static List<Double> rollingSmaDistance(List<Double> closes, int periods) {
        List<Double> output = new ArrayList<>();
        if (closes == null) return output;
        for (int end = periods; end <= closes.size(); end++) {
            List<Double> prefix = closes.subList(0, end);
            Double average = ComputeMath.sma(prefix, periods);
            if (average != null && average != 0.0) {
                output.add(ComputeMath.round2((prefix.get(prefix.size() - 1) / average - 1.0) * 100.0));
            }
        }
        return output;
    }

    public static List<Double> rollingBouncePercent(List<Double> closes, int lowPeriods) {
        List<Double> output = new ArrayList<>();
        if (closes == null) return output;
        for (int end = lowPeriods; end <= closes.size(); end++) {
            List<Double> window = closes.subList(end - lowPeriods, end);
            double low = window.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            if (low > 0.0) output.add(ComputeMath.round2((closes.get(end - 1) / low - 1.0) * 100.0));
        }
        return output;
    }

    public static List<Double> rollingTrailingHighDistance(List<Double> closes, int windowPeriods) {
        List<Double> output = new ArrayList<>();
        if (closes == null) return output;
        for (int end = windowPeriods; end <= closes.size(); end++) {
            List<Double> window = closes.subList(end - windowPeriods, end);
            double high = window.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            if (high > 0.0) output.add(ComputeMath.drawdownPct(closes.get(end - 1), high));
        }
        return output;
    }

    private static Double medianValues(List<JsonNode> quotes) {
        List<Double> values = new ArrayList<>();
        quotes.forEach(value -> values.add(value.path("value").doubleValue()));
        return ComputeMath.median(values);
    }

    private static double round3(double value) {
        double scaled = value * 1_000.0;
        double floor = Math.floor(scaled);
        double rounded = scaled - floor < 0.5 ? floor : floor + 1.0;
        return rounded / 1_000.0;
    }

    private static void putNullable(ObjectNode output, String key, Double value) {
        if (value == null) output.set(key, NullNode.instance); else output.put(key, value);
    }

    private static void putNullable(ObjectNode output, String key, Boolean value) {
        if (value == null) output.set(key, NullNode.instance); else output.put(key, value);
    }

    private static String numberText(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
}

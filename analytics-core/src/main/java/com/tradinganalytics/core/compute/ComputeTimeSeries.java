package com.tradinganalytics.core.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

import static com.tradinganalytics.core.compute.ComputeJsonSupport.object;
import static com.tradinganalytics.core.compute.ComputeJsonSupport.putBoolean;
import static com.tradinganalytics.core.compute.ComputeJsonSupport.putNumber;

/** Time-series statistics and trend measurements used by both frameworks. */
final class ComputeTimeSeries {

    private ComputeTimeSeries() {
    }

    static ObjectNode wilderRsi(List<Double> closes, int period) {
        int count = closes == null ? 0 : closes.size();
        ObjectNode output = object();
        if (closes == null || closes.size() < period + 1) {
            output.set("rsi", NullNode.getInstance());
            output.put("closes_used", count);
            output.put("confidence", "insufficient");
            output.put("note", "need ≥" + (period + 1)
                    + " closes for a seed, ≥15 for a low-confidence read, ≥30 for unflagged (FK momentum input rule)");
            return output;
        }
        double gain = 0.0;
        double loss = 0.0;
        for (int index = 1; index <= period; index++) {
            double difference = closes.get(index) - closes.get(index - 1);
            if (difference >= 0.0) gain += difference;
            else loss -= difference;
        }
        double averageGain = gain / period;
        double averageLoss = loss / period;
        for (int index = period + 1; index < closes.size(); index++) {
            double difference = closes.get(index) - closes.get(index - 1);
            averageGain = (averageGain * (period - 1) + Math.max(difference, 0.0)) / period;
            averageLoss = (averageLoss * (period - 1) + Math.max(-difference, 0.0)) / period;
        }
        double rsi = averageLoss == 0.0 ? 100.0 : 100.0 - 100.0 / (1.0 + averageGain / averageLoss);
        putNumber(output, "rsi", ComputeNumericSupport.round2(rsi));
        output.put("closes_used", closes.size());
        output.put("period", period);
        output.put("confidence", closes.size() >= 30 ? "ok" : "low");
        return output;
    }

    static Double sma(List<Double> values, int n) {
        if (values == null || values.size() < n || n <= 0) return null;
        double sum = 0.0;
        for (int index = values.size() - n; index < values.size(); index++) sum += values.get(index);
        return sum / n;
    }

    static Double median(List<Double> values) {
        if (values == null || values.isEmpty()) return null;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    static Double sampleStdev(List<Double> values) {
        if (values == null || values.size() < 2) return null;
        // Deliberately preserve the left-to-right JS reduce order for parity.
        double total = 0.0;
        for (double value : values) total += value;
        double mean = total / values.size();
        double squares = 0.0;
        for (double value : values) squares += Math.pow(value - mean, 2);
        return Math.sqrt(squares / (values.size() - 1));
    }

    static Double percentileRank(List<Double> values, double x) {
        List<Double> clean = finiteValues(values);
        if (clean.isEmpty() || !Double.isFinite(x)) return null;
        int below = 0;
        int equal = 0;
        for (double value : clean) {
            if (value < x) below++;
            else if (value == x) equal++;
        }
        return ComputeNumericSupport.jsRound(((below + equal / 2.0) / clean.size()) * 10_000.0) / 100.0;
    }

    static ObjectNode distributionStats(List<Double> values) {
        List<Double> clean = finiteValues(values);
        ObjectNode output = object();
        output.put("n", clean.size());
        if (clean.isEmpty()) {
            output.set("min", NullNode.getInstance());
            output.set("max", NullNode.getInstance());
            output.set("median", NullNode.getInstance());
            output.set("mean", NullNode.getInstance());
            output.set("stdev", NullNode.getInstance());
            return output;
        }
        double total = 0.0;
        for (double value : clean) total += value;
        double mean = total / clean.size();
        putNumber(output, "min", clean.stream().mapToDouble(Double::doubleValue).min().orElseThrow());
        putNumber(output, "max", clean.stream().mapToDouble(Double::doubleValue).max().orElseThrow());
        putNumber(output, "median", median(clean));
        putNumber(output, "mean", ComputeNumericSupport.jsRound(mean * 10_000.0) / 10_000.0);
        putNumber(output, "stdev", sampleStdev(clean));
        return output;
    }

    static List<Double> logReturns(List<Double> closes) {
        List<Double> output = new ArrayList<>();
        if (closes == null) return output;
        for (int index = 1; index < closes.size(); index++) {
            Double first = closes.get(index - 1);
            Double second = closes.get(index);
            if (first == null || second == null || first <= 0.0 || second <= 0.0) continue;
            output.add(Math.log(second / first));
        }
        return output;
    }

    static Double realizedVol(List<Double> closes, int window, int annualize) {
        if (closes == null || closes.size() <= window) return null;
        List<Double> returns = logReturns(closes.subList(closes.size() - window - 1, closes.size()));
        if (returns.size() < 2) return null;
        Double standardDeviation = sampleStdev(returns);
        return standardDeviation == null ? null
                : ComputeNumericSupport.jsRound(standardDeviation * Math.sqrt(annualize) * 10_000.0) / 100.0;
    }

    static ObjectNode realizedVolBlock(List<Double> closes, int annualize) {
        ObjectNode output = object();
        putNumber(output, "rv10", realizedVol(closes, 10, annualize));
        putNumber(output, "rv30", realizedVol(closes, 30, annualize));
        putNumber(output, "rv90", realizedVol(closes, 90, annualize));
        output.put("annualize_convention", annualize);
        return output;
    }

    static List<Double> rollingRealizedVol(List<Double> closes, int window, int annualize) {
        List<Double> output = new ArrayList<>();
        if (closes == null) return output;
        for (int index = window + 1; index <= closes.size(); index++) {
            Double value = realizedVol(closes.subList(0, index), window, annualize);
            if (value != null) output.add(value);
        }
        return output;
    }

    static ObjectNode dailyTrend(ArrayNode sessions, Double spot, int fast, int slow, int slopeN, int lowN) {
        int needed = slow + slopeN;
        if (sessions == null || sessions.size() < needed) {
            ObjectNode insufficient = object();
            insufficient.put("insufficient", "need ≥" + needed + " daily sessions for a " + slow
                    + "dma + " + slopeN + "-session slope, got " + (sessions == null ? 0 : sessions.size()));
            return insufficient;
        }
        List<Double> closes = new ArrayList<>();
        sessions.forEach(session -> closes.add(ComputeNumericSupport.jsNumber(session.get("close"))));
        double price = spot != null ? spot : closes.get(closes.size() - 1);
        ObjectNode rsi = wilderRsi(closes, 14);
        Double movingFast = sma(closes, fast);
        Double movingSlow = sma(closes, slow);
        Double slope = smaSlope(closes, slow, slopeN);
        Boolean slowFalling = slope == null ? null : slope < 0.0;
        Boolean belowSlow = movingSlow == null ? null : price < movingSlow;
        Boolean fastBelowSlow = movingFast == null || movingSlow == null ? null : movingFast < movingSlow;

        List<Double> past = closes.subList(0, closes.size() - slopeN);
        Double pastFast = sma(past, fast);
        Double pastSlow = sma(past, slow);
        Double gapNow = movingFast == null || movingSlow == null || movingSlow == 0.0
                ? null : Math.abs(movingFast - movingSlow) / movingSlow * 100.0;
        Double gapPast = pastFast == null || pastSlow == null || pastSlow == 0.0
                ? null : Math.abs(pastFast - pastSlow) / pastSlow * 100.0;
        Boolean gapNarrowed = gapNow == null || gapPast == null ? null : gapNow < gapPast;
        boolean structureB = Boolean.TRUE.equals(fastBelowSlow) && Boolean.TRUE.equals(gapNarrowed);
        Boolean withinSlow = withinPercent(price, movingSlow, 3.0);
        Boolean withinFastFromBelow = movingFast == null ? null
                : price <= movingFast && Boolean.TRUE.equals(withinPercent(price, movingFast, 3.0));

        int start = Math.max(0, sessions.size() - lowN);
        List<JsonNode> lowWindow = new ArrayList<>();
        for (int index = start; index < sessions.size(); index++) lowWindow.add(sessions.get(index));
        double low = lowWindow.stream().mapToDouble(row -> ComputeNumericSupport.jsNumber(row.get("low")))
                .min().orElse(Double.POSITIVE_INFINITY);
        int lowIndex = 0;
        for (int index = 0; index < lowWindow.size(); index++) {
            if (ComputeNumericSupport.jsNumber(lowWindow.get(index).get("low")) == low) {
                lowIndex = index;
                break;
            }
        }
        Double bounce = low == 0.0 ? null : ComputeNumericSupport.round2((price / low - 1.0) * 100.0);
        int bounceAge = lowWindow.size() - 1 - lowIndex;
        int sessionsLowToHigh = 0;
        double highAfterLow = Double.NEGATIVE_INFINITY;
        for (int index = lowIndex; index < lowWindow.size(); index++) {
            double high = ComputeNumericSupport.jsNumber(lowWindow.get(index).get("high"));
            if (high > highAfterLow) {
                highAfterLow = high;
                sessionsLowToHigh = index - lowIndex;
            }
        }

        ObjectNode output = object();
        output.set("insufficient", NullNode.getInstance());
        output.set("rsi14", rsi.get("rsi"));
        output.set("rsi14_confidence", rsi.get("confidence"));
        putNumber(output, "ma50", roundedNullable(movingFast));
        putNumber(output, "ma200", roundedNullable(movingSlow));
        putNumber(output, "ma200_slope20_pct", slope);
        putBoolean(output, "ma200_falling", slowFalling);
        putBoolean(output, "price_below_ma200", belowSlow);
        putBoolean(output, "ma50_below_ma200", fastBelowSlow);
        putNumber(output, "gap_now_pct", roundedNullable(gapNow));
        putBoolean(output, "gap_narrowed_20", gapNarrowed);
        output.put("structure_b", structureB);
        putBoolean(output, "within_3pct_of_ma200", withinSlow);
        putBoolean(output, "within_3pct_of_ma50_from_below", withinFastFromBelow);
        putNumber(output, "low_40s", ComputeNumericSupport.round2(low));
        putNumber(output, "bounce_pct", bounce);
        output.put("bounce_age_sessions", bounceAge);
        output.put("sessions_low_to_high", sessionsLowToHigh);
        return output;
    }

    private static Double smaSlope(List<Double> values, int n, int lookback) {
        if (values == null || values.size() < n + lookback) return null;
        Double now = sma(values, n);
        Double past = sma(values.subList(0, values.size() - lookback), n);
        return now == null || past == null || past == 0.0
                ? null : ComputeNumericSupport.round2((now / past - 1.0) * 100.0);
    }

    private static Boolean withinPercent(double first, Double second, double percent) {
        return second == null || second == 0.0 ? null : Math.abs(first / second - 1.0) * 100.0 <= percent;
    }

    private static Double roundedNullable(Double value) {
        return value == null ? null : ComputeNumericSupport.round2(value);
    }

    private static List<Double> finiteValues(List<Double> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && Double.isFinite(value)).toList();
    }
}

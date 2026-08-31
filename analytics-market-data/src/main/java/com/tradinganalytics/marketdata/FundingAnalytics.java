package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.compute.ComputeMath;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Funding-window disclosure and squeeze inputs from {@code tools/lib.mjs}. */
public final class FundingAnalytics {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private FundingAnalytics() {
    }

    public static ObjectNode fundingBlock(ArrayNode intervals, int count) {
        if (intervals == null || intervals.isEmpty()) {
            ObjectNode output = NODES.objectNode();
            output.put("insufficient", "no funding intervals supplied");
            output.put("n_intervals", 0);
            return output;
        }
        int start = Math.max(0, intervals.size() - count);
        List<Double> perEightHourPercent = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        for (int index = start; index < intervals.size(); index++) {
            JsonNode interval = intervals.get(index);
            perEightHourPercent.add(ComputeMath.jsNumber(interval.get("fundingRate")) * 100.0);
            times.add((long) ComputeMath.jsNumber(interval.get("fundingTime")));
        }
        // Array.prototype.reduce in the Node oracle is a strict left-to-right
        // IEEE-754 fold. DoubleStream.sum uses compensated summation and can
        // cross a half-cent rounding boundary on alternating funding prints.
        double total = 0.0;
        for (double value : perEightHourPercent) total += value;
        double mean = total / perEightHourPercent.size();
        Map<String, List<Double>> days = new LinkedHashMap<>();
        for (int index = 0; index < times.size(); index++) {
            String day = Instant.ofEpochMilli(times.get(index)).atZone(ZoneOffset.UTC).toLocalDate().toString();
            days.computeIfAbsent(day, ignored -> new ArrayList<>()).add(perEightHourPercent.get(index));
        }
        List<Double> dailyAverages = new ArrayList<>();
        for (List<Double> values : days.values()) {
            double dayTotal = 0.0;
            for (double value : values) dayTotal += value;
            dailyAverages.add(dayTotal / values.size());
        }
        List<Double> annualized = perEightHourPercent.stream().map(ComputeMath::frAnnualizedFunding).toList();
        int runBelowFive = MarketSeriesAnalytics.consecutiveRun(annualized, value -> value < -5.0, true);
        Integer lastBelowSeven = null;
        for (int index = 0; index < annualized.size(); index++) if (annualized.get(index) < -7.0) lastBelowSeven = index;

        ObjectNode output = NODES.objectNode();
        output.put("n_intervals", perEightHourPercent.size());
        output.put("n_sessions", days.size());
        output.put("mean_per_8h_pct", ComputeMath.round2(mean));
        output.put("mean_annualized_pct", ComputeMath.frAnnualizedFunding(mean));
        output.put("longest_negative_run_intervals",
                MarketSeriesAnalytics.consecutiveRun(perEightHourPercent, value -> value < 0.0, true));
        output.put("longest_negative_run_sessions",
                MarketSeriesAnalytics.consecutiveRun(dailyAverages, value -> value < 0.0, true));
        output.put("longest_run_below_minus5_annualized_intervals", runBelowFive);
        output.put("sustained3_below_minus5", runBelowFive >= 3);
        if (annualized.isEmpty()) output.set("min_interval_annualized_pct", NullNode.instance);
        else output.put("min_interval_annualized_pct", annualized.stream().mapToDouble(Double::doubleValue).min().orElseThrow());
        output.put("single_interval_below_minus7", lastBelowSeven != null);
        if (lastBelowSeven == null) output.set("most_recent_below_minus7_intervals_ago", NullNode.instance);
        else output.put("most_recent_below_minus7_intervals_ago", annualized.size() - 1 - lastBelowSeven);
        output.put("oi_90d_high_available", false);
        output.set("oi_within_5pct_of_90d_high", NullNode.instance);
        output.put("sign_convention", "POSITIVE funding = longs pay shorts = carry INCOME to a short (FR SKILL, Jul 2026)");
        output.put("threshold_note", "sustained3_below_minus5 is the boolean fr.squeezeTrapPenalty({sustained3Intervals}) wants: >=3 consecutive intervals each ANNUALIZED below -5% (= -0.004566% per 8h). longest_negative_run_intervals counts MERELY NEGATIVE prints (FK capitulation-(b)) and is a ~1000x looser bar — it must never be read as the squeeze-trap input. single_interval_below_minus7 scans the whole used window; most_recent_below_minus7_intervals_ago exposes its recency so a stale print is not read as \"prints\".");
        return output;
    }
}

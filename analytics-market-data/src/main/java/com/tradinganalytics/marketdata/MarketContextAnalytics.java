package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.compute.ComputeMath;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic context-only market panels from {@code tools/lib.mjs}. */
public final class MarketContextAnalytics {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private MarketContextAnalytics() {
    }

    public static ObjectNode onchainDistributionBlock(ArrayNode inputRows) {
        List<OnchainRow> rows = new ArrayList<>();
        if (inputRows != null) {
            for (JsonNode source : inputRows) {
                String date = truthyText(source.get("time"));
                if (date == null) date = truthyText(source.get("date"));
                date = date == null ? "" : date.substring(0, Math.min(10, date.length()));
                double mvrv = jsNumeric(source.get("CapMVRVCur"));
                double marketCap = jsNumeric(source.get("CapMrktCurUSD"));
                if (!date.isEmpty() && Double.isFinite(mvrv) && mvrv > 0.0 && Double.isFinite(marketCap)) {
                    rows.add(new OnchainRow(date, mvrv, marketCap,
                            jsNumeric(source.get("FlowInExUSD")), jsNumeric(source.get("FlowOutExUSD")),
                            jsNumeric(source.get("SplyExNtv")), jsNumeric(source.get("SplyCur"))));
                }
            }
        }
        if (rows.size() < 30) {
            ObjectNode unavailable = NODES.objectNode();
            unavailable.put("available", false);
            unavailable.put("reason", "need >=30 usable daily Coin Metrics rows; got " + rows.size());
            return unavailable;
        }
        OnchainRow last = rows.get(rows.size() - 1);
        List<Double> caps = rows.stream().map(OnchainRow::marketCapUsd).toList();
        Double standardDeviation = ComputeMath.sampleStdev(caps);
        double realizedCap = last.marketCapUsd() / last.mvrv();
        OnchainRow lookback = rows.get(Math.max(0, rows.size() - 31));
        Double reserveChange = Double.isFinite(last.exchangeReserveNative())
                && Double.isFinite(lookback.exchangeReserveNative())
                ? last.exchangeReserveNative() - lookback.exchangeReserveNative() : null;
        Double reserveChangePercent = reserveChange != null && lookback.exchangeReserveNative() != 0.0
                ? reserveChange / lookback.exchangeReserveNative() * 100.0 : null;
        List<OnchainRow> flowRows = rows.subList(Math.max(0, rows.size() - 30), rows.size()).stream()
                .filter(row -> Double.isFinite(row.inflowUsd()) && Double.isFinite(row.outflowUsd())).toList();
        Double netThirty = flowRows.isEmpty() ? null
                : flowRows.stream().mapToDouble(row -> row.inflowUsd() - row.outflowUsd()).sum();

        ObjectNode output = NODES.objectNode();
        output.put("available", standardDeviation != null);
        output.put("as_of", last.date());
        output.put("history_days", rows.size());
        output.put("mvrv_ratio", round3(last.mvrv()));
        putNullable(output, "mvrv_z", standardDeviation == null ? null
                : round3((last.marketCapUsd() - realizedCap) / standardDeviation));
        output.put("mvrv_z_method", "reconstructed: (market cap - realized cap) / sample stdev(full-history market cap); realized cap = market cap / MVRV");
        ObjectNode reserve = output.putObject("exchange_reserve");
        putNullable(reserve, "native", Double.isFinite(last.exchangeReserveNative())
                ? round3(last.exchangeReserveNative()) : null);
        putNullable(reserve, "pct_of_supply", Double.isFinite(last.exchangeReserveNative())
                && Double.isFinite(last.supplyNative()) && last.supplyNative() != 0.0
                ? round3(last.exchangeReserveNative() / last.supplyNative() * 100.0) : null);
        putNullable(reserve, "change_30d_native", reserveChange == null ? null : round3(reserveChange));
        putNullable(reserve, "change_30d_pct", reserveChangePercent == null ? null : round3(reserveChangePercent));
        ObjectNode flows = output.putObject("exchange_flows");
        putNullable(flows, "inflow_usd_1d", Double.isFinite(last.inflowUsd())
                ? ComputeMath.round2(last.inflowUsd()) : null);
        putNullable(flows, "outflow_usd_1d", Double.isFinite(last.outflowUsd())
                ? ComputeMath.round2(last.outflowUsd()) : null);
        putNullable(flows, "net_inflow_usd_30d", netThirty == null ? null : ComputeMath.round2(netThirty));
        flows.put("days_used", flowRows.size());
        flows.put("sign_convention", "positive net inflow = exchange inflows exceeded outflows");
        ObjectNode lth = output.putObject("lth");
        lth.put("available", false);
        lth.put("status", "PROVIDER_GATED");
        lth.put("reason", "true LTH supply/flow is not available in Coin Metrics Community; no proxy age band is substituted");
        output.put("status_note", "Coin Metrics flash values can back-revise; current values are descriptive unless the framework explicitly scores them");
        return output;
    }

    public static ObjectNode coinbasePremiumBlock(ArrayNode coinbaseRows, ArrayNode binanceRows, ArrayNode usdtUsdRows) {
        Map<String, Double> coinbase = completedCloses(coinbaseRows);
        Map<String, Double> binance = completedCloses(binanceRows);
        Map<String, Double> usdt = completedCloses(usdtUsdRows);
        List<PremiumPoint> points = coinbase.keySet().stream()
                .filter(date -> binance.containsKey(date) && usdt.containsKey(date))
                .sorted()
                .map(date -> new PremiumPoint(date,
                        round3((coinbase.get(date) / (binance.get(date) * usdt.get(date)) - 1.0) * 100.0)))
                .toList();
        if (points.size() < 3) {
            ObjectNode unavailable = NODES.objectNode();
            unavailable.put("available", false);
            unavailable.put("reason", "need >=3 aligned completed daily closes; got " + points.size());
            return unavailable;
        }
        int negativeRun = 0;
        for (int index = points.size() - 1; index >= 0 && points.get(index).premiumPercent() < 0.0; index--) {
            negativeRun++;
        }
        List<PremiumPoint> lastThree = points.subList(points.size() - 3, points.size());
        ObjectNode output = NODES.objectNode();
        output.put("available", true);
        output.put("as_of", points.get(points.size() - 1).date());
        output.put("latest_pct", points.get(points.size() - 1).premiumPercent());
        ArrayNode last = output.putArray("last_3_completed_days");
        lastThree.forEach(point -> {
            ObjectNode row = last.addObject();
            row.put("date", point.date());
            row.put("premium_pct", point.premiumPercent());
        });
        output.put("negative_3_completed_days", lastThree.stream().allMatch(point -> point.premiumPercent() < 0.0));
        output.put("consecutive_negative_completed_days", negativeRun);
        output.put("days_aligned", points.size());
        output.put("method", "Coinbase USD close / (Binance USDT close x Coinbase USDT-USD close) - 1");
        output.put("note", "completed UTC daily closes only; current partial day excluded");
        return output;
    }

    public static ObjectNode oi90dBlock(ArrayNode inputRows) {
        Map<String, Double> dailyHigh = new LinkedHashMap<>();
        if (inputRows != null) {
            for (JsonNode source : inputRows) {
                String date = truthyText(source.get("date"));
                if (date == null) date = truthyText(source.get("create_time"));
                date = date == null ? "" : date.substring(0, Math.min(10, date.length()));
                JsonNode valueNode = source.has("sum_open_interest_value")
                        ? source.get("sum_open_interest_value") : source.get("sumOpenInterestValue");
                double value = jsNumeric(valueNode);
                if (!date.isEmpty() && Double.isFinite(value)) dailyHigh.merge(date, value, Math::max);
            }
        }
        List<OiPoint> daily = dailyHigh.entrySet().stream()
                .map(entry -> new OiPoint(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(OiPoint::date)).toList();
        if (daily.size() > 90) daily = daily.subList(daily.size() - 90, daily.size());
        if (daily.size() < 80) {
            ObjectNode unavailable = NODES.objectNode();
            unavailable.put("available", false);
            unavailable.put("history_days", daily.size());
            unavailable.put("reason", "need >=80 distinct archived days for a 90d claim; got " + daily.size());
            return unavailable;
        }
        OiPoint latest = daily.get(daily.size() - 1);
        double high = daily.stream().mapToDouble(OiPoint::valueUsd).max().orElseThrow();
        OiPoint highPoint = daily.stream().filter(point -> point.valueUsd() == high).findFirst().orElseThrow();
        double below = round3((1.0 - latest.valueUsd() / high) * 100.0);
        ObjectNode output = NODES.objectNode();
        output.put("available", true);
        output.put("as_of", latest.date());
        output.put("history_days", daily.size());
        output.put("latest_open_interest_usd", ComputeMath.round2(latest.valueUsd()));
        output.put("high_90d_usd", ComputeMath.round2(high));
        output.put("high_90d_date", highPoint.date());
        output.put("pct_below_90d_high", below);
        output.put("within_5pct_of_90d_high", below <= 5.0);
        output.put("scope_note", "Binance USD-M single-venue open interest; not market-wide OI");
        return output;
    }

    public static ObjectNode breadth200Block(ArrayNode inputRows, Double universeSize,
                                             String universeAsOf, double minimumCoveragePercent) {
        List<JsonNode> usable = new ArrayList<>();
        if (inputRows != null) {
            for (JsonNode row : inputRows) {
                double close = jsNumeric(row.get("close"));
                double average = jsNumeric(row.get("sma200"));
                if (Double.isFinite(close) && Double.isFinite(average)) usable.add(row);
            }
        }
        double denominator = universeSize != null && Double.isFinite(universeSize) && universeSize > 0.0
                ? universeSize : usable.size();
        double coverage = denominator != 0.0 ? usable.size() / denominator * 100.0 : 0.0;
        long above = usable.stream().filter(row -> row.path("close").asDouble() > row.path("sma200").asDouble()).count();
        ObjectNode output = NODES.objectNode();
        if (denominator == 0.0 || coverage < minimumCoveragePercent) {
            output.put("available", false);
            putNullable(output, "universe_as_of", universeAsOf);
            putNumber(output, "universe_size", denominator);
            output.put("matched", usable.size());
            output.put("coverage_pct", ComputeMath.round2(coverage));
            output.put("reason", "coverage " + numberText(ComputeMath.round2(coverage)) + "% below "
                    + numberText(minimumCoveragePercent) + "% minimum");
            return output;
        }
        output.put("available", true);
        putNullable(output, "universe_as_of", universeAsOf);
        putNumber(output, "universe_size", denominator);
        output.put("matched", usable.size());
        output.put("coverage_pct", ComputeMath.round2(coverage));
        output.put("above_200dma_count", above);
        output.put("pct_above_200dma", ComputeMath.round2(above / (double) usable.size() * 100.0));
        output.put("method", "close > SMA200; equal does not count as above");
        return output;
    }

    public static ObjectNode sentimentProxyBlock(List<Double> volCloses, List<Double> cefCloses,
                                                  List<Double> referenceCloses, int baselineWindow,
                                                  int percentileWindow) {
        ObjectNode output = NODES.objectNode();
        output.put("scored", false);
        output.put("note", "DISCLOSED REGIME CONTEXT ONLY — not a scored leg input. Both proxies were tested over 10y on 2026-08-05 and failed as scored inputs (GVZ: no gradient; PHYS premium: era-dependent, effective N~16). The sentiment leg keeps its NOT-FOUND fallback of 2.");
        if (volCloses != null && !volCloses.isEmpty()) {
            double latest = volCloses.get(volCloses.size() - 1);
            int from = Math.max(0, volCloses.size() - percentileWindow - 1);
            List<Double> history = volCloses.subList(from, volCloses.size() - 1);
            ObjectNode vol = output.putObject("vol_index");
            vol.put("last", ComputeMath.round2(latest));
            putNullable(vol, "percentile_vs_2y", history.isEmpty() ? null : ComputeMath.percentileRank(history, latest));
            vol.put("history_days", volCloses.size());
            vol.put("interpretation", "direction-blind — a high print means turbulence, NOT fear; never read as a fear signal");
        }
        if (cefCloses != null && referenceCloses != null && cefCloses.size() == referenceCloses.size()
                && cefCloses.size() > baselineWindow) {
            List<Double> ratios = new ArrayList<>();
            for (int index = 0; index < cefCloses.size(); index++) {
                Double reference = referenceCloses.get(index);
                ratios.add(reference != null && reference != 0.0 ? cefCloses.get(index) / reference : null);
            }
            List<Double> premiums = new ArrayList<>();
            for (int index = 0; index < ratios.size(); index++) {
                Double ratio = ratios.get(index);
                if (ratio == null || index < baselineWindow - 1) {
                    premiums.add(null);
                    continue;
                }
                double sum = 0.0;
                int count = 0;
                for (int cursor = index - baselineWindow + 1; cursor <= index; cursor++) {
                    if (ratios.get(cursor) != null) {
                        sum += ratios.get(cursor);
                        count++;
                    }
                }
                premiums.add(count == baselineWindow ? ComputeMath.round2((ratio / (sum / count) - 1.0) * 100.0) : null);
            }
            Double latest = premiums.get(premiums.size() - 1);
            if (latest != null) {
                int from = Math.max(0, premiums.size() - percentileWindow - 1);
                List<Double> history = premiums.subList(from, premiums.size() - 1).stream()
                        .filter(value -> value != null).toList();
                ObjectNode cef = output.putObject("cef_premium");
                cef.put("premium_pct", latest);
                putNullable(cef, "percentile_vs_2y", history.isEmpty() ? null
                        : ComputeMath.percentileRank(history, latest));
                cef.put("baseline_window_days", baselineWindow);
                cef.put("history_days", premiums.stream().filter(value -> value != null).count());
                cef.put("sign", latest < 0.0 ? "DISCOUNT" : "PREMIUM");
                cef.put("interpretation", "discount = investors paying below metal value (fear-shaped); UNSCORED — the signal is era-dependent and did not survive split-half validation");
            }
        }
        return output;
    }

    private static Map<String, Double> completedCloses(ArrayNode rows) {
        Map<String, Double> output = new LinkedHashMap<>();
        if (rows == null) return output;
        for (JsonNode row : rows) {
            String rawDate = row.path("date").asText("");
            String date = rawDate.substring(0, Math.min(10, rawDate.length()));
            double close = jsNumeric(row.get("close"));
            if (Double.isFinite(close)) output.put(date, close);
        }
        return output;
    }

    private static String truthyText(JsonNode value) {
        if (value == null || value.isNull()) return null;
        String text = value.asText();
        return text.isEmpty() ? null : text;
    }

    private static double jsNumeric(JsonNode value) {
        if (value == null || value.isNull()) return Double.NaN;
        if (value.isTextual() && value.textValue().isEmpty()) return Double.NaN;
        return ComputeMath.jsNumber(value);
    }

    private static double round3(double value) {
        double scaled = value * 1_000.0;
        double floor = Math.floor(scaled);
        return (scaled - floor < 0.5 ? floor : floor + 1.0) / 1_000.0;
    }

    private static void putNullable(ObjectNode target, String key, Double value) {
        if (value == null) target.set(key, NullNode.instance); else target.put(key, value);
    }

    private static void putNullable(ObjectNode target, String key, String value) {
        if (value == null) target.set(key, NullNode.instance); else target.put(key, value);
    }

    private static void putNumber(ObjectNode target, String key, double value) {
        if (value == Math.rint(value) && Math.abs(value) <= Long.MAX_VALUE) target.put(key, (long) value);
        else target.put(key, value);
    }

    private static String numberText(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private record OnchainRow(String date, double mvrv, double marketCapUsd, double inflowUsd,
                              double outflowUsd, double exchangeReserveNative, double supplyNative) {
    }

    private record PremiumPoint(String date, double premiumPercent) {
    }

    private record OiPoint(String date, double valueUsd) {
    }
}

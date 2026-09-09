package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.compute.ComputeMath;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Completed-bar {@code market-flow/1} disclosure panel from {@code tools/lib.mjs}. */
public final class MarketFlowPanel {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final java.time.format.DateTimeFormatter ISO_MILLIS =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private MarketFlowPanel() {
    }

    public static ObjectNode build(ArrayNode spotRows, ArrayNode futuresRows, ArrayNode openInterestRows,
                                   ArrayNode oiWeightedFundingRows, int intervalHours, String scope) {
        ObjectNode spot = flowSummary(spotRows, intervalHours);
        ObjectNode futures = flowSummary(futuresRows, intervalHours);
        ObjectNode openInterest = candleSummary(openInterestRows, intervalHours, false);
        ObjectNode weightedFunding = candleSummary(oiWeightedFundingRows, intervalHours, true);
        Double price24 = available(spot) ? nullableNumber(spot.get("price_change_24h_pct")) : null;
        Double spot24 = available(spot) ? nullableNumber(spot.get("delta_24h_usd")) : null;
        Double futures24 = available(futures) ? nullableNumber(futures.get("delta_24h_usd")) : null;
        Double oi24 = available(openInterest) ? nullableNumber(openInterest.get("change_24h_pct")) : null;
        List<String> candidates = new ArrayList<>();
        if (finite(price24, spot24) && price24 > 0.0 && spot24 < 0.0) {
            candidates.add("PRICE_UP_SPOT_CVD_DOWN: potential distribution/spot demand non-confirmation");
        }
        if (finite(price24, spot24) && price24 < 0.0 && spot24 > 0.0) {
            candidates.add("PRICE_DOWN_SPOT_CVD_UP: potential spot absorption");
        }
        if (finite(price24, futures24, spot24, oi24)
                && price24 > 0.0 && futures24 > 0.0 && spot24 <= 0.0 && oi24 > 0.0) {
            candidates.add("LEVERAGE_LED_RALLY: futures buying + rising OI without spot confirmation");
        }
        if (finite(price24, futures24, oi24) && price24 < 0.0 && futures24 < 0.0 && oi24 < 0.0) {
            candidates.add("LONG_DELEVERAGING: price/futures CVD/OI falling together");
        }
        if (finite(price24, futures24, oi24) && price24 < 0.0 && futures24 < 0.0 && oi24 > 0.0) {
            candidates.add("FRESH_SHORT_BUILD: price and futures CVD down while OI rises");
        }
        if (finite(price24, futures24, oi24) && price24 > 0.0 && futures24 > 0.0 && oi24 < 0.0) {
            candidates.add("SHORT_COVERING: price/futures CVD up while OI falls");
        }
        String asOf = latestText(List.of(spot, futures, openInterest, weightedFunding), "as_of");
        String completedThrough = latestText(List.of(spot, futures, openInterest, weightedFunding), "completed_through");

        ObjectNode output = NODES.objectNode();
        output.put("schema", "market-flow/1");
        output.put("available", available(spot) || available(futures) || available(openInterest) || available(weightedFunding));
        output.put("scope", scope == null ? "unknown" : scope);
        output.put("interval_hours", intervalHours);
        putNullable(output, "as_of", asOf);
        putNullable(output, "completed_through", completedThrough);
        output.set("spot_cvd", spot);
        output.set("futures_cvd", futures);
        if (available(futures)) {
            ObjectNode delta = output.putObject("futures_bid_ask_delta");
            copy(delta, "latest_usd", futures.get("latest_delta_usd"));
            copy(delta, "delta_24h_usd", futures.get("delta_24h_usd"));
            copy(delta, "delta_3d_usd", futures.get("delta_3d_usd"));
            copy(delta, "imbalance_24h_pct", futures.get("imbalance_24h_pct"));
            delta.put("direction_24h", sign(futures24));
            delta.put("direction_3d", sign(nullableNumber(futures.get("delta_3d_usd"))));
            delta.put("sign_convention", "positive = aggressive/taker buys exceed sells; negative = aggressive/taker sells exceed buys");
        } else {
            ObjectNode delta = output.putObject("futures_bid_ask_delta");
            delta.put("available", false);
            copy(delta, "reason", futures.get("reason"));
        }
        output.set("open_interest", openInterest);
        if (available(weightedFunding)) {
            ObjectNode weighted = weightedFunding.deepCopy();
            weighted.put("oi_weighted", true);
            output.set("oi_weighted_funding", weighted);
        } else {
            output.set("oi_weighted_funding", weightedFunding);
        }
        ObjectNode signs = output.putObject("relationship_signs_24h");
        signs.put("price", sign(price24));
        signs.put("spot_cvd", sign(spot24));
        signs.put("futures_cvd", sign(futures24));
        signs.put("open_interest", sign(oi24));
        signs.put("oi_weighted_funding", available(weightedFunding)
                ? sign(nullableNumber(weightedFunding.path("latest").get("close"))) : "flat_or_unavailable");
        ArrayNode labels = output.putArray("interpretation_candidates");
        candidates.forEach(labels::add);
        output.put("interpretation_note", "Candidates are relationship labels, not signals. Require magnitude, at least two horizons, and an independent source family before using one in D1/S1; all fields from this block count as ONE provider/derived family.");
        output.put("note", "Legacy report-machine/1–2: disclosed context only. Shadow swing-score/1 may score this panel only after completed 24h/3d coverage and setup-relative OI interpretation; it still cannot authorize until model_activation is ACTIVE.");
        return output;
    }

    static ObjectNode flowSummary(ArrayNode rows, int intervalHours) {
        List<FlowRow> clean = new ArrayList<>();
        for (JsonNode source : iterable(rows)) {
            Double time = timeMillis(source.get("time"));
            Double buy = finiteNumber(source.get("buy_usd"));
            Double sell = finiteNumber(source.get("sell_usd"));
            JsonNode closeNode = source.get("close");
            Double close = closeNode != null && !closeNode.isNull() ? finiteNumber(closeNode) : null;
            if (time != null && buy != null && sell != null) clean.add(new FlowRow(time.longValue(), buy, sell, close));
        }
        clean.sort(Comparator.comparingLong(FlowRow::time));
        if (clean.isEmpty()) {
            ObjectNode unavailable = NODES.objectNode();
            unavailable.put("available", false);
            unavailable.put("reason", "no completed taker buy/sell bars");
            return unavailable;
        }
        List<FlowBar> bars = new ArrayList<>();
        double cumulative = 0.0;
        for (FlowRow row : clean) {
            double delta = row.buy() - row.sell();
            cumulative += delta;
            bars.add(new FlowBar(row.time(), row.buy(), row.sell(), row.close(),
                    ComputeMath.round2(delta), ComputeMath.round2(cumulative)));
        }
        int bars24 = Math.max(1, (int) Math.round(24.0 / intervalHours));
        int bars72 = Math.max(1, (int) Math.round(72.0 / intervalHours));
        Double firstClose = bars.stream().map(FlowBar::close).filter(MarketFlowPanel::isFinite).findFirst().orElse(null);
        Double lastClose = null;
        for (int index = bars.size() - 1; index >= 0; index--) {
            if (isFinite(bars.get(index).close())) { lastClose = bars.get(index).close(); break; }
        }
        List<FlowBar> close24Rows = tail(bars, Math.min(bars24 + 1, bars.size())).stream()
                .filter(row -> isFinite(row.close())).toList();
        Double close24 = close24Rows.size() >= 2
                ? round3((close24Rows.get(close24Rows.size() - 1).close() / close24Rows.get(0).close() - 1.0) * 100.0)
                : null;
        Double closeWindow = isFinite(firstClose) && isFinite(lastClose) && firstClose != 0.0
                ? round3((lastClose / firstClose - 1.0) * 100.0) : null;
        FlowTail hours24 = summarizeTail(bars, bars24);
        FlowTail hours72 = summarizeTail(bars, bars72);
        FlowTail full = summarizeTail(bars, bars.size());
        FlowBar latest = bars.get(bars.size() - 1);

        ObjectNode output = NODES.objectNode();
        output.put("available", true);
        output.put("completed_bars", bars.size());
        output.put("from", iso(bars.get(0).time()));
        output.put("as_of", iso(latest.time()));
        output.put("completed_through", iso(latest.time() + intervalHours * 3_600_000L));
        output.put("latest_delta_usd", latest.delta());
        output.put("delta_24h_usd", hours24.delta());
        output.put("delta_3d_usd", hours72.delta());
        output.put("delta_window_usd", full.delta());
        putNullable(output, "imbalance_24h_pct", hours24.imbalance());
        putNullable(output, "imbalance_3d_pct", hours72.imbalance());
        putNullable(output, "imbalance_window_pct", full.imbalance());
        putNullable(output, "price_change_24h_pct", close24);
        putNullable(output, "price_change_window_pct", closeWindow);
        output.put("direction_24h", sign(hours24.delta()));
        output.put("direction_3d", sign(hours72.delta()));
        output.put("direction_window", sign(full.delta()));
        ArrayNode recent = output.putArray("recent_bars");
        for (FlowBar row : tail(bars, 12)) {
            ObjectNode value = recent.addObject();
            value.put("time", iso(row.time()));
            value.put("delta_usd", row.delta());
            value.put("cumulative_delta_window_usd", row.cumulative());
            putNullable(value, "close", isFinite(row.close()) ? row.close() : null);
        }
        output.put("anchor_note", "CVD is rebased to zero at the first returned completed bar; compare slope/direction/divergence, never absolute level across runs");
        return output;
    }

    static ObjectNode candleSummary(ArrayNode rows, int intervalHours, boolean funding) {
        List<CandleRow> clean = new ArrayList<>();
        for (JsonNode source : iterable(rows)) {
            Double time = timeMillis(source.get("time"));
            Double open = finiteNumber(source.get("open"));
            Double high = finiteNumber(source.get("high"));
            Double low = finiteNumber(source.get("low"));
            Double close = finiteNumber(source.get("close"));
            if (time == null || close == null) continue;
            clean.add(new CandleRow(time.longValue(), open, high, low, close,
                    optionalFiniteCoercion(source, "samples"), optionalFiniteCoercion(source, "expected_samples"),
                    optionalBoolean(source, "sampling_complete"), optionalFiniteCoercion(source, "min_contract_coverage"),
                    optionalFiniteCoercion(source, "expected_contracts"), optionalBoolean(source, "contract_coverage_complete")));
        }
        clean.sort(Comparator.comparingLong(CandleRow::time));
        if (clean.isEmpty()) {
            ObjectNode unavailable = NODES.objectNode();
            unavailable.put("available", false);
            unavailable.put("reason", "no completed candles");
            return unavailable;
        }
        int bars24 = Math.max(1, (int) Math.round(24.0 / intervalHours));
        int bars72 = Math.max(1, (int) Math.round(72.0 / intervalHours));
        CandleRow latest = clean.get(clean.size() - 1);
        CandleRow prior24 = clean.get(Math.max(0, clean.size() - 1 - bars24));
        CandleRow prior72 = clean.get(Math.max(0, clean.size() - 1 - bars72));
        List<Double> closes = clean.stream().map(CandleRow::close).toList();
        boolean sampled = clean.stream().anyMatch(row -> row.expectedSamples() != null);
        Double percent24 = !funding && prior24.close() != 0.0
                ? round3((latest.close() / prior24.close() - 1.0) * 100.0) : null;
        Double percent72 = !funding && prior72.close() != 0.0
                ? round3((latest.close() / prior72.close() - 1.0) * 100.0) : null;
        Double percentWindow = !funding && clean.get(0).close() != 0.0
                ? round3((latest.close() / clean.get(0).close() - 1.0) * 100.0) : null;
        double mean24 = meanTail(closes, bars24);
        double mean72 = meanTail(closes, bars72);

        ObjectNode output = NODES.objectNode();
        output.put("available", true);
        output.put("completed_bars", clean.size());
        output.put("from", iso(clean.get(0).time()));
        output.put("as_of", iso(latest.time()));
        output.put("completed_through", iso(latest.time() + intervalHours * 3_600_000L));
        output.set("latest", candleJson(latest, funding));
        putNullable(output, "change_24h_pct", percent24);
        putNullable(output, "change_3d_pct", percent72);
        putNullable(output, "change_window_pct", percentWindow);
        putNullable(output, "mean_close_24h", funding ? display(mean24, true) : null);
        putNullable(output, "mean_close_3d", funding ? display(mean72, true) : null);
        double windowTotal = 0.0;
        for (double close : closes) windowTotal += close;
        putNullable(output, "mean_close_window", funding
                ? display(windowTotal / closes.size(), true) : null);
        putNullable(output, "latest_percentile_vs_prior_window", clean.size() > 1
                ? ComputeMath.percentileRank(closes.subList(0, closes.size() - 1), latest.close()) : null);
        output.put("direction_24h", funding ? sign(mean24) : sign(percent24));
        output.put("direction_3d", funding ? sign(mean72) : sign(percent72));
        output.put("direction_window", funding ? sign(latest.close()) : sign(percentWindow));
        output.put("ohlc_available", clean.stream().anyMatch(row -> isFinite(row.open()) && isFinite(row.high()) && isFinite(row.low())));
        output.put("ohlc_method", sampled
                ? "resampled discrete snapshots; high/low are sampled observations, not continuous extrema"
                : "provider OHLC");
        if (sampled) {
            ObjectNode quality = output.putObject("sampling_quality");
            quality.put("complete_bars", clean.stream().filter(row -> Boolean.TRUE.equals(row.samplingComplete())).count());
            quality.put("incomplete_bars", clean.stream().filter(row -> Boolean.FALSE.equals(row.samplingComplete())).count());
            quality.put("bars_with_full_contract_coverage", clean.stream().filter(row -> Boolean.TRUE.equals(row.contractCoverageComplete())).count());
        } else {
            output.set("sampling_quality", NullNode.instance);
        }
        ArrayNode recent = output.putArray("recent_candles");
        for (CandleRow row : tail(clean, 12)) recent.add(candleJson(row, funding));
        return output;
    }

    private static ObjectNode candleJson(CandleRow row, boolean funding) {
        ObjectNode value = NODES.objectNode();
        value.put("time", iso(row.time()));
        putNullable(value, "open", display(row.open(), funding));
        putNullable(value, "high", display(row.high(), funding));
        putNullable(value, "low", display(row.low(), funding));
        putNullable(value, "close", display(row.close(), funding));
        putNullable(value, "samples", row.samples());
        putNullable(value, "expected_samples", row.expectedSamples());
        putNullable(value, "sampling_complete", row.samplingComplete());
        putNullable(value, "min_contract_coverage", row.minimumCoverage());
        putNullable(value, "expected_contracts", row.expectedContracts());
        putNullable(value, "contract_coverage_complete", row.contractCoverageComplete());
        return value;
    }

    private static FlowTail summarizeTail(List<FlowBar> bars, int count) {
        List<FlowBar> used = tail(bars, Math.min(count, bars.size()));
        double buy = 0.0, sell = 0.0;
        for (FlowBar row : used) {
            buy += row.buy(); sell += row.sell();
        }
        double delta = buy - sell;
        double gross = buy + sell;
        return new FlowTail(used.size(), ComputeMath.round2(buy), ComputeMath.round2(sell),
                ComputeMath.round2(delta), gross != 0.0 ? round3(delta / gross * 100.0) : null);
    }

    private static <T> List<T> tail(List<T> values, int count) {
        return values.subList(Math.max(0, values.size() - count), values.size());
    }

    private static double meanTail(List<Double> values, int count) {
        List<Double> selected = tail(values, count);
        double total = 0.0;
        for (double value : selected) total += value;
        return total / Math.min(count, values.size());
    }

    private static String latestText(List<ObjectNode> values, String field) {
        return values.stream().map(value -> value.get(field)).filter(node -> node != null && node.isTextual())
                .map(JsonNode::textValue).sorted().reduce((left, right) -> right).orElse(null);
    }

    private static boolean available(ObjectNode value) {
        return value.path("available").asBoolean(false);
    }

    private static String sign(Double value) {
        return !isFinite(value) || value == 0.0 ? "flat_or_unavailable" : value > 0.0 ? "positive" : "negative";
    }

    private static boolean finite(Double... values) {
        for (Double value : values) if (!isFinite(value)) return false;
        return true;
    }

    private static boolean isFinite(Double value) {
        return value != null && Double.isFinite(value);
    }

    private static Double timeMillis(JsonNode value) {
        Double number = finiteNumber(value);
        if (number == null) return null;
        return number < 1e12 ? number * 1_000.0 : number;
    }

    private static Double finiteNumber(JsonNode value) {
        if (value == null || value.isMissingNode()) return null;
        double number = ComputeMath.jsNumber(value);
        return Double.isFinite(number) ? number : null;
    }

    private static Double nullableNumber(JsonNode value) {
        return value == null || value.isNull() || !value.isNumber() ? null : value.doubleValue();
    }

    private static Double optionalFiniteCoercion(JsonNode source, String field) {
        JsonNode value = source.get(field);
        return value == null ? null : finiteNumber(value);
    }

    private static Boolean optionalBoolean(JsonNode source, String field) {
        JsonNode value = source.get(field);
        return value != null && value.isBoolean() ? value.booleanValue() : null;
    }

    private static Double display(Double value, boolean funding) {
        if (!isFinite(value)) return null;
        if (!funding) return value;
        double scaled = value * 1e10;
        double floor = Math.floor(scaled);
        return (scaled - floor < 0.5 ? floor : floor + 1.0) / 1e10;
    }

    private static double round3(double value) {
        double scaled = value * 1_000.0;
        double floor = Math.floor(scaled);
        return (scaled - floor < 0.5 ? floor : floor + 1.0) / 1_000.0;
    }

    private static String iso(long epochMillis) {
        return ISO_MILLIS.format(Instant.ofEpochMilli(epochMillis));
    }

    private static Iterable<JsonNode> iterable(JsonNode value) {
        return value != null && value.isArray() ? value : List.of();
    }

    private static void putNullable(ObjectNode target, String key, Double value) {
        if (value == null) target.set(key, NullNode.instance); else target.put(key, value);
    }

    private static void putNullable(ObjectNode target, String key, Boolean value) {
        if (value == null) target.set(key, NullNode.instance); else target.put(key, value);
    }

    private static void putNullable(ObjectNode target, String key, String value) {
        if (value == null) target.set(key, NullNode.instance); else target.put(key, value);
    }

    private static void copy(ObjectNode target, String key, JsonNode value) {
        target.set(key, value == null ? NullNode.instance : value.deepCopy());
    }

    private record FlowRow(long time, double buy, double sell, Double close) { }
    private record FlowBar(long time, double buy, double sell, Double close, double delta, double cumulative) { }
    private record FlowTail(int bars, double buy, double sell, double delta, Double imbalance) { }
    private record CandleRow(long time, Double open, Double high, Double low, double close,
                             Double samples, Double expectedSamples, Boolean samplingComplete,
                             Double minimumCoverage, Double expectedContracts,
                             Boolean contractCoverageComplete) { }
}

package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.compute.ComputeMath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Constructs the canonical spot panel and its source-level reconciliation fields. */
final class SpotSnapshotAssembler {
    private final ObjectMapper json;

    SpotSnapshotAssembler(ObjectMapper json) {
        this.json = json;
    }

    ObjectNode assemble(MarketFetchSupport.AssetConfig asset, JsonNode cgSpot,
                        ArrayNode daily, ArrayNode cross, Map<String, JsonNode> fetched, long now) {
        Double yahoo = lastClose(daily);
        Double cg = asset.coinGeckoId() == null ? null : nullableNumber(at(cgSpot, asset.coinGeckoId(), "usd"));
        Double crossValue = lastClose(cross);
        ArrayNode sources = json.createArrayNode();
        addSource(sources, "CoinGecko", cg);
        addSource(sources, "Yahoo " + asset.yahooSymbol() + " (last daily close)",
                yahoo == null ? null : ComputeMath.round2(yahoo));
        addSource(sources, "Yahoo " + asset.crossYahooSymbol(),
                crossValue == null ? null : ComputeMath.round2(crossValue));

        Double divergence = null;
        if (sources.size() >= 2) {
            List<Double> values = new ArrayList<>();
            sources.forEach(row -> values.add(row.path("value").asDouble()));
            divergence = ComputeMath.round2((Collections.max(values) / Collections.min(values) - 1.0) * 100.0);
        }
        Long cgTime = asset.coinGeckoId() == null ? null
                : nullableLong(at(cgSpot, asset.coinGeckoId(), "last_updated_at"));
        ArrayNode quotes = json.createArrayNode();
        if (cg != null) {
            addQuote(quotes, "CoinGecko", asset.coinGeckoId(), cg,
                    cgTime == null ? null : cgTime * 1_000L, "venue");
        }
        if (yahoo != null) {
            addQuote(quotes, "Yahoo " + asset.yahooSymbol(), asset.yahooSymbol(),
                    ComputeMath.round2(yahoo), null, "bar_close");
        }
        if (crossValue != null) {
            addQuote(quotes, "Yahoo " + asset.crossYahooSymbol(), asset.crossYahooSymbol(),
                    ComputeMath.round2(crossValue), null, "bar_close");
        }
        for (String key : List.of("binanceQ", "coinbaseQ", "krakenQ")) {
            JsonNode quote = fetched.get(key);
            if (quote != null) {
                quotes.add(quote.deepCopy());
            }
        }

        ObjectNode panel = MarketSeriesAnalytics.spotPanel(quotes, now, 120, 0.5);
        Double panelMedian = nullableNumber(panel.get("canonical"));
        Double priority = sources.isEmpty() ? null : sources.get(0).path("value").asDouble();
        Double canonical = panelMedian != null ? panelMedian : priority;
        ObjectNode output = json.createObjectNode();
        putNullable(output, "canonical", canonical);
        output.set("sources", sources);
        putNullable(output, "divergence_pct", divergence);
        if (divergence != null && divergence > 1.5) {
            output.put("warning", "inter-source spread " + numberText(divergence) + "% > 1.5% — reconcile before scoring");
        } else {
            output.set("warning", NullNode.instance);
        }
        output.put("canonical_source", panelMedian != null ? "panel_median" : "priority_first_fallback");
        output.set("panel", panel);
        putNullable(output, "canonical_median", panelMedian);
        output.set("method_conflict", NullNode.instance);
        return output;
    }

    private void addSource(ArrayNode target, String source, Double value) {
        if (value == null) {
            return;
        }
        ObjectNode row = target.addObject();
        row.put("source", source);
        putNumber(row, "value", value);
    }

    private void addQuote(ArrayNode target, String source, String symbol, double value,
                          Long timestamp, String timestampKind) {
        ObjectNode row = target.addObject();
        row.put("source", source);
        row.put("symbol", symbol);
        putNumber(row, "value", value);
        if (timestamp == null) {
            row.putNull("ts");
        } else {
            row.put("ts", timestamp);
        }
        row.put("ts_kind", timestampKind);
    }

    private static Double lastClose(ArrayNode rows) {
        return rows == null || rows.isEmpty() ? null : nullableNumber(rows.get(rows.size() - 1).get("close"));
    }

    private static JsonNode at(JsonNode value, String... path) {
        JsonNode current = value;
        for (String key : path) {
            if (current == null || current.isNull() || !current.isContainerNode()) {
                return null;
            }
            current = current.get(key);
        }
        return current;
    }

    private static Double nullableNumber(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        double number = ComputeMath.jsNumber(value);
        return Double.isFinite(number) ? number : null;
    }

    private static Long nullableLong(JsonNode value) {
        Double number = nullableNumber(value);
        return number == null ? null : number.longValue();
    }

    private static void putNumber(ObjectNode target, String key, double value) {
        target.set(key, ComputeMath.normalizedNumberNode(value));
    }

    private static void putNullable(ObjectNode target, String key, Double value) {
        if (value == null) {
            target.putNull(key);
        } else {
            putNumber(target, key, value);
        }
    }

    private static String numberText(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}

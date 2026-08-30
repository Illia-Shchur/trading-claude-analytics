package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.compute.ComputeMath;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Asset catalog and deterministic candle transforms from {@code tools/fetch.mjs}. */
public final class MarketFetchSupport {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final DateTimeFormatter ISO_DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);
    public static final Map<String, AssetConfig> ASSETS;

    static {
        LinkedHashMap<String, AssetConfig> assets = new LinkedHashMap<>();
        assets.put("btc", new AssetConfig("bitcoin", "btc", "BTC-USD", null, true, "BTCUSDT", 365,
                "BTC", "fBTC", null, Map.of("binance", "BTCUSDT", "coinbase", "BTC-USD", "kraken", "XBTUSD"), null));
        assets.put("eth", new AssetConfig("ethereum", "eth", "ETH-USD", null, true, "ETHUSDT", 365,
                "ETH", "fETH", null, Map.of("binance", "ETHUSDT", "coinbase", "ETH-USD", "kraken", "ETHUSD"), null));
        assets.put("sol", new AssetConfig("solana", null, "SOL-USD", null, true, "SOLUSDT", 365,
                null, "fSOL", null, Map.of("binance", "SOLUSDT", "coinbase", "SOL-USD", "kraken", "SOLUSD"), null));
        assets.put("gold", new AssetConfig(null, null, "GC=F", "MGC=F", false, null, 252,
                null, null, "10y", Map.of(), new SentimentProxy("^GVZ", "PHYS", "GC=F")));
        assets.put("spx", new AssetConfig(null, null, "^GSPC", "ES=F", false, null, 252,
                null, null, "10y", Map.of(), null));
        assets.put("ndx", new AssetConfig(null, null, "^NDX", "NQ=F", false, null, 252,
                null, null, "10y", Map.of(), null));
        ASSETS = Collections.unmodifiableMap(assets);
    }

    private MarketFetchSupport() {
    }

    public static ArrayNode completedCandles(ArrayNode candles, long barMillis, long nowMillis) {
        int end = candles == null ? 0 : candles.size();
        while (end > 0 && nowMillis < ComputeMath.jsNumber(candles.get(end - 1).get("t")) + barMillis) end--;
        ArrayNode output = NODES.arrayNode();
        if (candles != null) for (int index = 0; index < end; index++) output.add(candles.get(index).deepCopy());
        return output;
    }

    public static ObjectNode weeklyBlock(ArrayNode candles, Double spot, long nowMillis) {
        ArrayNode completed = completedCandles(candles, 7L * 86_400_000L, nowMillis);
        List<Double> completedCloses = closes(completed);
        List<Double> allCloses = closes(candles);
        ObjectNode rsi = ComputeMath.wilderRsi(completedCloses, 14);
        ObjectNode liveRsi = ComputeMath.wilderRsi(allCloses, 14);
        Double average = completedCloses.size() >= 200 ? ComputeMath.sma(completedCloses, 200) : null;

        ObjectNode output = NODES.objectNode();
        output.put("boundary", "Yahoo weekly candles (week-start timestamps, UTC)");
        output.put("completed_closes", completedCloses.size());
        if (completed.isEmpty()) output.set("last_completed_week", NullNode.instance);
        else output.set("last_completed_week", completed.get(completed.size() - 1).get("date").deepCopy());
        output.set("rsi14", rsi);
        copy(output, "rsi14_including_live_week", liveRsi.get("rsi"));
        ObjectNode sma = output.putObject("sma_200w");
        if (average == null) {
            sma.set("value", NullNode.instance);
            sma.put("note", "only " + completedCloses.size() + " weekly closes available");
        } else {
            double rounded = ComputeMath.round2(average);
            putNumber(sma, "value", rounded);
            double price = spot == null ? 0.0 : spot;
            putNumber(sma, "pct_vs_spot", ComputeMath.round2((price / rounded - 1.0) * 100.0));
            sma.put("within_8pct", Math.abs(price / rounded - 1.0) <= 0.08);
            sma.put("note", "gate 6: price within ±8% of the 200-week MA, above OR below");
        }
        return output;
    }

    public static List<Double> dailyAnnualizedFundingSeries(ArrayNode intervals) {
        LinkedHashMap<String, List<Double>> days = new LinkedHashMap<>();
        if (intervals != null) {
            for (JsonNode interval : intervals) {
                long time = (long) ComputeMath.jsNumber(interval.get("fundingTime"));
                String day = ISO_DAY.format(Instant.ofEpochMilli(time));
                double percent = ComputeMath.jsNumber(interval.get("fundingRate")) * 100.0;
                days.computeIfAbsent(day, ignored -> new ArrayList<>()).add(percent);
            }
        }
        List<Double> output = new ArrayList<>();
        for (List<Double> values : days.values()) {
            double total = 0.0;
            for (double value : values) total += value;
            double mean = total / values.size();
            output.add(ComputeMath.frAnnualizedFunding(mean));
        }
        return output;
    }

    public static ArrayNode parseYahooChart(JsonNode response, String symbol) {
        JsonNode result = response.path("chart").path("result");
        if (!result.isArray() || result.isEmpty() || !result.get(0).path("timestamp").isArray()) {
            throw new IllegalArgumentException("yahoo: empty result for " + symbol);
        }
        JsonNode root = result.get(0);
        JsonNode timestamps = root.path("timestamp");
        JsonNode quote = root.path("indicators").path("quote").path(0);
        ArrayNode output = NODES.arrayNode();
        for (int index = 0; index < timestamps.size(); index++) {
            JsonNode close = element(quote.get("close"), index);
            if (close == null || close.isNull()) continue;
            long millis = timestamps.get(index).longValue() * 1_000L;
            ObjectNode candle = output.addObject();
            candle.put("t", millis);
            candle.put("date", ISO_DAY.format(Instant.ofEpochMilli(millis)));
            copy(candle, "open", element(quote.get("open"), index));
            copy(candle, "high", element(quote.get("high"), index));
            copy(candle, "low", element(quote.get("low"), index));
            copy(candle, "close", close);
            copy(candle, "volume", element(quote.get("volume"), index));
        }
        return output;
    }

    private static List<Double> closes(ArrayNode candles) {
        List<Double> output = new ArrayList<>();
        if (candles != null) for (JsonNode candle : candles) output.add(ComputeMath.jsNumber(candle.get("close")));
        return output;
    }

    private static JsonNode element(JsonNode array, int index) {
        return array != null && array.isArray() && index < array.size() ? array.get(index) : null;
    }

    private static void copy(ObjectNode target, String key, JsonNode value) {
        target.set(key, value == null ? NullNode.instance : value.deepCopy());
    }

    private static void putNumber(ObjectNode target, String key, double value) {
        target.set(key, ComputeMath.normalizedNumberNode(value));
    }

    public record SentimentProxy(String volatilitySymbol, String closedEndFundSymbol, String referenceSymbol) {
    }

    public record AssetConfig(String coinGeckoId, String coinMetricsId, String yahooSymbol,
                              String crossYahooSymbol, boolean fearAndGreed, String perpetualSymbol,
                              int annualize, String deribitCurrency, String bitfinexFundingSymbol,
                              String athRange, Map<String, String> venues, SentimentProxy sentimentProxy) {
    }
}

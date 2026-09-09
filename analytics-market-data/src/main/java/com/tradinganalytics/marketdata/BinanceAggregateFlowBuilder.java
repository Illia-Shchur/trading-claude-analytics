package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.compute.ComputeMath;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.LongSupplier;

/** Builds the single-venue Binance market-flow fallback and its provenance. */
final class BinanceAggregateFlowBuilder {
    private static final java.util.Set<String> USD_QUOTES = java.util.Set.of(
            "USDT", "USDC", "FDUSD", "TUSD", "BUSD", "USDP");

    private final MarketDataEndpoints endpoints;
    private final ObjectMapper json;
    private final LongSupplier clock;

    BinanceAggregateFlowBuilder(MarketDataEndpoints endpoints, ObjectMapper json, LongSupplier clock) {
        this.endpoints = endpoints;
        this.json = json;
        this.clock = clock;
    }

    ObjectNode build(String baseAsset, String preferredSpot, String preferredPerpetual, int maxBars) {
        List<String> errors = new ArrayList<>();
        JsonNode spotInfo = safe("spot exchangeInfo", endpoints::binanceSpotExchangeInfo, null, errors);
        JsonNode futuresInfo = safe("USD-M exchangeInfo", endpoints::binanceFuturesExchangeInfo, null, errors);
        String base = baseAsset == null ? "" : baseAsset.toUpperCase(Locale.ROOT);
        List<String> spotSymbols = symbols(spotInfo, symbol -> "TRADING".equals(symbol.path("status").asText())
                && symbol.path("isSpotTradingAllowed").asBoolean(true), base);
        List<String> perpetualSymbols = symbols(futuresInfo,
                symbol -> "TRADING".equals(symbol.path("status").asText())
                        && "PERPETUAL".equals(symbol.path("contractType").asText()), base);
        if (spotSymbols.isEmpty() && preferredSpot != null) {
            spotSymbols = new ArrayList<>(List.of(preferredSpot));
            errors.add("spot symbol discovery empty; used configured primary pair");
        }
        if (perpetualSymbols.isEmpty() && preferredPerpetual != null) {
            perpetualSymbols = new ArrayList<>(List.of(preferredPerpetual));
            errors.add("perpetual symbol discovery empty; used configured primary contract");
        }

        ArrayNode spotGroups = groups(spotSymbols, false, maxBars, errors);
        ArrayNode futuresGroups = groups(perpetualSymbols, true, maxBars, errors);
        ArrayNode oiGroups = openInterestGroups(perpetualSymbols, errors);
        ArrayNode fundingGroups = fundingGroups(perpetualSymbols, errors);
        ArrayNode oiSnapshots = MarketFlowAggregation.aggregateValueSnapshots(oiGroups, 30L * 60_000L);
        ArrayNode fundingSnapshots = MarketFlowAggregation.oiWeightedFundingSnapshots(
                oiGroups, fundingGroups, 30L * 60_000L);
        List<String> futuresFlowSymbols = groupSymbols(futuresGroups);
        List<String> oiSymbols = groupSymbols(oiGroups);
        List<String> fundingSymbols = groupSymbols(fundingGroups).stream().filter(oiSymbols::contains).toList();

        ObjectNode output = json.createObjectNode();
        output.set("spotRows", MarketFlowAggregation.aggregateFlowRows(spotGroups));
        output.set("futuresRows", MarketFlowAggregation.aggregateFlowRows(futuresGroups));
        output.set("openInterestRows", MarketFlowAggregation.resampleSnapshotsToCandles(
                oiSnapshots, 4, 30, maxBars, clock.getAsLong()));
        output.set("oiWeightedFundingRows", MarketFlowAggregation.resampleSnapshotsToCandles(
                fundingSnapshots, 4, 30, maxBars, clock.getAsLong()));
        ObjectNode metadata = output.putObject("metadata");
        metadata.put("venue", "Binance");
        metadata.put("scope", "single venue, aggregated across active stable-USD spot pairs and USD-M perpetuals");
        metadata.set("spot_symbols_discovered", json.valueToTree(spotSymbols));
        metadata.set("spot_symbols_included", json.valueToTree(groupSymbols(spotGroups)));
        metadata.set("perpetual_symbols_discovered", json.valueToTree(perpetualSymbols));
        metadata.set("perpetual_symbols_included", json.valueToTree(perpetualSymbols.stream()
                .filter(symbol -> futuresFlowSymbols.contains(symbol) && oiSymbols.contains(symbol)
                        && fundingSymbols.contains(symbol)).toList()));
        metadata.set("futures_flow_symbols_included", json.valueToTree(futuresFlowSymbols));
        metadata.set("oi_symbols_included", json.valueToTree(oiSymbols));
        metadata.set("funding_symbols_included", json.valueToTree(fundingSymbols));
        metadata.set("quote_assets_treated_as_nominal_usd", json.valueToTree(
                List.of("USDT", "USDC", "FDUSD", "TUSD", "BUSD", "USDP")));
        metadata.put("oi_sampling", "30-minute sumOpenInterestValue snapshots resampled to completed 4h OHLC; highs/lows are sampled, not continuous");
        metadata.put("funding_method", "latest settled fundingRate per contract, weighted by contemporaneous 30-minute USD OI, then resampled to completed 4h OHLC");
        metadata.put("funding_unit", "raw Binance funding-rate fraction per contract funding interval (0.0001 = 0.01%)");
        metadata.put("funding_interval_caveat", "Compare sign and relative history. Do not annualize the aggregate unless each included contract funding interval is separately verified.");
        metadata.set("errors", json.valueToTree(errors));
        return output;
    }

    private ArrayNode openInterestGroups(List<String> symbols, List<String> errors) {
        ArrayNode groups = json.createArrayNode();
        for (String symbol : symbols) {
            ArrayNode raw = safe("30m OI " + symbol,
                    () -> endpoints.binanceOpenInterestHistory(symbol, 500, "30m"),
                    json.createArrayNode(), errors);
            ArrayNode rows = json.createArrayNode();
            for (JsonNode row : raw) {
                ObjectNode mapped = rows.addObject();
                putNumber(mapped, "time", number(row.get("timestamp")));
                putNumber(mapped, "value", number(row.get("sumOpenInterestValue")));
            }
            if (!rows.isEmpty()) {
                ObjectNode group = groups.addObject();
                group.put("symbol", symbol);
                group.set("rows", rows);
            }
        }
        return groups;
    }

    private ArrayNode fundingGroups(List<String> symbols, List<String> errors) {
        ArrayNode groups = json.createArrayNode();
        for (String symbol : symbols) {
            ArrayNode raw = safe("funding history " + symbol,
                    () -> endpoints.binanceFundingHistory(symbol, 1_000),
                    json.createArrayNode(), errors);
            ArrayNode rows = json.createArrayNode();
            for (JsonNode row : raw) {
                ObjectNode mapped = rows.addObject();
                putNumber(mapped, "time", number(row.get("fundingTime")));
                putNumber(mapped, "rate", number(row.get("fundingRate")));
            }
            if (!rows.isEmpty()) {
                ObjectNode group = groups.addObject();
                group.put("symbol", symbol);
                group.set("rows", rows);
            }
        }
        return groups;
    }

    private List<String> symbols(JsonNode exchangeInfo, Predicate<JsonNode> predicate, String baseAsset) {
        if (exchangeInfo == null || !exchangeInfo.path("symbols").isArray()) {
            return new ArrayList<>();
        }
        List<String> output = new ArrayList<>();
        for (JsonNode symbol : exchangeInfo.path("symbols")) {
            if (!predicate.test(symbol) || !baseAsset.equals(symbol.path("baseAsset").asText())
                    || !USD_QUOTES.contains(symbol.path("quoteAsset").asText())) {
                continue;
            }
            output.add(symbol.path("symbol").asText());
        }
        output.sort(String::compareTo);
        return output;
    }

    private ArrayNode groups(List<String> symbols, boolean futures, int maxBars, List<String> errors) {
        ArrayNode output = json.createArrayNode();
        for (String symbol : symbols) {
            String label = (futures ? "futures" : "spot") + " klines " + symbol;
            ArrayNode rows = safe(label, () -> endpoints.binanceFlowKlines(symbol, futures, "4h", maxBars),
                    json.createArrayNode(), errors);
            if (!rows.isEmpty()) {
                ObjectNode group = output.addObject();
                group.put("symbol", symbol);
                group.set("rows", rows);
            }
        }
        return output;
    }

    private static List<String> groupSymbols(ArrayNode groups) {
        List<String> output = new ArrayList<>();
        for (JsonNode group : groups) {
            output.add(group.path("symbol").asText());
        }
        return output;
    }

    private static <T> T safe(String label, ThrowingSupplier<T> supplier, T fallback, List<String> errors) {
        try {
            return supplier.get();
        } catch (Exception exception) {
            errors.add(label + ": " + exception.getMessage());
            return fallback;
        }
    }

    private static double number(JsonNode value) {
        return ComputeMath.jsNumber(value);
    }

    private static void putNumber(ObjectNode target, String key, double value) {
        target.set(key, ComputeMath.normalizedNumberNode(value));
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}

package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.compute.ComputeMath;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds the public asset snapshot from fetched rows and late verification calls. */
final class AssetSnapshotAssembler {
    private static final DateTimeFormatter ISO_MILLIS = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private final MarketDataEndpoints endpoints;
    private final ObjectMapper json;
    private final AssetContextAssembler contextAssembler;
    private final SpotSnapshotAssembler spotAssembler;

    AssetSnapshotAssembler(MarketDataEndpoints endpoints, ObjectMapper json, boolean coinglassConfigured) {
        this.endpoints = endpoints;
        this.json = json;
        this.contextAssembler = new AssetContextAssembler(endpoints, json, coinglassConfigured);
        this.spotAssembler = new SpotSnapshotAssembler(json);
    }

    ObjectNode assemble(String key, MarketFetchSupport.AssetConfig asset, boolean includeSeries,
                        Map<String, JsonNode> fetched, long now, List<String> errors) {
        ObjectNode output = json.createObjectNode();
        output.put("asset", key.toUpperCase(Locale.ROOT));
        output.put("fetched_at", ISO_MILLIS.format(Instant.ofEpochMilli(now)));
        ArrayNode errorArray = output.putArray("errors");

        ArrayNode weekly = array(fetched.get("weekly"));
        ArrayNode daily = array(fetched.get("daily"));
        ArrayNode cross = array(fetched.get("cross"));
        JsonNode cgSpot = fetched.get("cgSpot");
        JsonNode cgCoin = fetched.get("cgCoin");
        JsonNode fng = fetched.get("fng");
        ArrayNode funding = array(fetched.get("funding"));
        output.set("spot", spotAssembler.assemble(asset, cgSpot, daily, cross, fetched, now));
        Double canonicalSpot = nullableNumber(output.path("spot").get("canonical"));

        buildAth(output, asset, cgCoin, canonicalSpot, errors);
        if (daily != null && weekly != null && canonicalSpot != null) {
            buildOneYearHigh(output, asset, weekly, canonicalSpot, now);
        }
        appendWeekly(output, asset, weekly, canonicalSpot, now);
        if (daily != null) {
            buildDaily(output, asset, daily, canonicalSpot, includeSeries);
        }
        if (fng != null && fng.path("data").isArray()) {
            buildSentiment(output, fng);
        }
        appendFunding(output, asset, funding);
        appendOnchain(output, fetched);
        appendCoinbasePremium(output, asset, fetched);
        contextAssembler.append(output, asset, daily, weekly, fng, funding, fetched, now, errors);
        if (asset.coinMetricsId() != null) {
            contextAssembler.appendGapCoverage(output);
        }
        // Context enrichments may perform additional calls. Publish all failures in
        // one deterministic list after those late calls have completed.
        errors.forEach(errorArray::add);
        return output;
    }

    private void appendWeekly(ObjectNode output, MarketFetchSupport.AssetConfig asset, ArrayNode weekly,
                              Double canonicalSpot, long now) {
        if (weekly == null) {
            return;
        }
        ObjectNode block = json.createObjectNode();
        block.put("source", "Yahoo " + asset.yahooSymbol() + " 5y 1wk (" + weekly.size() + " candles)");
        block.setAll(MarketFetchSupport.weeklyBlock(weekly, canonicalSpot, now));
        output.set("weekly", block);
    }

    private void appendFunding(ObjectNode output, MarketFetchSupport.AssetConfig asset, ArrayNode funding) {
        if (funding == null) {
            return;
        }
        ObjectNode block = json.createObjectNode();
        block.put("source", "Binance fapi fundingRate (" + asset.perpetualSymbol() + ", " + funding.size() + " intervals)");
        block.setAll(FundingAnalytics.fundingBlock(funding, 45));
        output.set("funding", block);
    }

    private void appendOnchain(ObjectNode output, Map<String, JsonNode> fetched) {
        ArrayNode onchain = array(fetched.get("onchain"));
        if (onchain == null) {
            return;
        }
        ObjectNode block = json.createObjectNode();
        block.put("source", "Coin Metrics Community API (daily; current rows may be flash/back-revised)");
        block.setAll(MarketContextAnalytics.onchainDistributionBlock(onchain));
        output.set("onchain", block);
    }

    private void appendCoinbasePremium(ObjectNode output, MarketFetchSupport.AssetConfig asset,
                                       Map<String, JsonNode> fetched) {
        ObjectNode premiumRows = object(fetched.get("premiumRows"));
        if (premiumRows == null) {
            return;
        }
        ObjectNode block = json.createObjectNode();
        block.put("source", "Coinbase Exchange " + venue(asset, "coinbase")
                + " + Coinbase USDT-USD + Binance " + venue(asset, "binance") + " completed daily candles");
        block.setAll(MarketContextAnalytics.coinbasePremiumBlock(arrayOrEmpty(premiumRows.get("coinbaseRows")),
                arrayOrEmpty(premiumRows.get("binanceRows")), arrayOrEmpty(premiumRows.get("usdtUsdRows"))));
        output.set("coinbase_premium", block);
    }

    private void buildAth(ObjectNode output, MarketFetchSupport.AssetConfig asset, JsonNode cgCoin,
                          Double spot, List<String> errors) {
        JsonNode market = at(cgCoin, "market_data");
        if (market != null) {
            ObjectNode ath = output.putObject("ath");
            putNumber(ath, "value", number(at(market, "ath", "usd")));
            String rawDate = at(market, "ath_date", "usd").asText();
            ath.put("date", rawDate.substring(0, Math.min(10, rawDate.length())));
            double drawdown = spot != null ? ComputeMath.drawdownPct(spot, number(at(market, "ath", "usd")))
                    : ComputeMath.round2(-number(at(market, "ath_change_percentage", "usd")));
            putNumber(ath, "drawdown_pct", drawdown);
            ath.put("source", "CoinGecko");
            return;
        }
        if (asset.athRange() == null || spot == null) {
            return;
        }
        ArrayNode window = attempt("yahoo " + asset.athRange() + " high",
                () -> endpoints.yahooChart(asset.yahooSymbol(), asset.athRange(), "1wk"), null, errors);
        ArrayNode full = attempt("yahoo max high (ATH verification)",
                () -> endpoints.yahooChart(asset.yahooSymbol(), "max", "1mo"), null, errors);
        if (window == null || window.isEmpty()) {
            return;
        }
        JsonNode highest = highest(window);
        ObjectNode ath = json.createObjectNode();
        putNumber(ath, "value", ComputeMath.round2(number(highest.get("high"))));
        ath.put("date", highest.path("date").asText());
        putNumber(ath, "drawdown_pct", ComputeMath.drawdownPct(spot, number(highest.get("high"))));
        long cutoff = window.get(0).path("t").asLong();
        List<JsonNode> prior = new ArrayList<>();
        if (full != null) {
            for (JsonNode candle : full) {
                if (candle.path("t").asLong() < cutoff && candle.hasNonNull("high")) {
                    prior.add(candle);
                }
            }
        }
        if (!prior.isEmpty()) {
            JsonNode priorHigh = prior.stream().max(Comparator.comparingDouble(row -> number(row.get("high")))).orElseThrow();
            boolean verified = number(priorHigh.get("high")) < number(highest.get("high"));
            ath.put("all_time_verified", verified);
            ObjectNode pre = ath.putObject("pre_window_high");
            putNumber(pre, "value", ComputeMath.round2(number(priorHigh.get("high"))));
            pre.put("date", priorHigh.path("date").asText());
            pre.put("history_from", full.get(0).path("date").asText());
            pre.put("bars", prior.size());
            ath.put("source", verified
                    ? "Yahoo " + asset.yahooSymbol() + " " + asset.athRange() + " weekly high — VERIFIED all-time: pre-window max "
                    + numberText(ComputeMath.round2(number(priorHigh.get("high")))) + " @ " + priorHigh.path("date").asText()
                    + " (monthly bars back to " + full.get(0).path("date").asText() + ") is below it"
                    : "Yahoo " + asset.yahooSymbol() + " " + asset.athRange() + " weekly high — NOT all-time: "
                    + numberText(ComputeMath.round2(number(priorHigh.get("high")))) + " @ " + priorHigh.path("date").asText()
                    + " traded higher BEFORE the window; the drawdown denominator understates the true ATH drawdown");
        } else {
            ath.set("all_time_verified", NullNode.instance);
            ath.put("source", "Yahoo " + asset.yahooSymbol() + " " + asset.athRange()
                    + " weekly high — NOT all-time (pre-window history unavailable); flag the window in the report");
        }
        output.set("ath", ath);
    }

    private void buildOneYearHigh(ObjectNode output, MarketFetchSupport.AssetConfig asset,
                                  ArrayNode weekly, double spot, long now) {
        List<JsonNode> values = new ArrayList<>();
        for (JsonNode candle : weekly) {
            if (candle.path("t").asLong() >= now - 366L * 86_400_000L) {
                values.add(candle);
            }
        }
        JsonNode highest = values.stream().filter(row -> row.hasNonNull("high"))
                .max(Comparator.comparingDouble(row -> number(row.get("high")))).orElse(null);
        if (highest == null || number(highest.get("high")) <= 0.0) {
            return;
        }
        ObjectNode block = output.putObject("high_1y");
        putNumber(block, "value", ComputeMath.round2(number(highest.get("high"))));
        block.put("date", highest.path("date").asText());
        putNumber(block, "pct_below", ComputeMath.drawdownPct(spot, number(highest.get("high"))));
        block.put("source", "Yahoo " + asset.yahooSymbol() + " trailing-1y weekly highs");
    }

    private void buildDaily(ObjectNode output, MarketFetchSupport.AssetConfig asset, ArrayNode daily,
                            Double spot, boolean includeSeries) {
        ArrayNode sessions = json.createArrayNode();
        int start = Math.max(0, daily.size() - 12);
        for (int index = start; index < daily.size(); index++) {
            JsonNode source = daily.get(index);
            ObjectNode row = sessions.addObject();
            row.put("date", source.path("date").asText());
            for (String field : List.of("high", "low", "close")) {
                putNumber(row, field, ComputeMath.round2(number(source.get(field))));
            }
        }
        ObjectNode block = json.createObjectNode();
        block.put("source", "Yahoo " + asset.yahooSymbol() + " 2y 1d (" + daily.size() + " candles)");
        block.set("last_sessions", sessions);
        block.set("adr5", ComputeMath.adr(sessions, 5, List.of()));
        block.put("note", "ADR must use 5 FULL sessions — if any listed session is holiday-abbreviated, recompute with tools/compute.mjs adr --exclude <date> and disclose");
        if (includeSeries) {
            ArrayNode series = block.putArray("series");
            for (JsonNode source : daily) {
                ObjectNode row = series.addObject();
                row.put("date", source.path("date").asText());
                for (String field : List.of("open", "high", "low", "close")) {
                    putNumber(row, field, ComputeMath.round2(number(source.get(field))));
                }
            }
        }
        output.set("daily", block);
        ArrayNode trendInput = json.createArrayNode();
        for (JsonNode source : daily) {
            ObjectNode row = trendInput.addObject();
            row.put("date", source.path("date").asText());
            for (String field : List.of("high", "low", "close")) {
                row.set(field, source.get(field).deepCopy());
            }
        }
        output.set("trend", ComputeMath.dailyTrend(trendInput, spot, 50, 200, 20, 40));
    }

    private void buildSentiment(ObjectNode output, JsonNode response) {
        ArrayNode source = (ArrayNode) response.path("data");
        ArrayNode series = json.createArrayNode();
        for (JsonNode item : source) {
            ObjectNode row = series.addObject();
            putNumber(row, "value", number(item.get("value")));
            long seconds = (long) number(item.get("timestamp"));
            row.put("date", Instant.ofEpochSecond(seconds).atZone(ZoneOffset.UTC).toLocalDate().toString());
        }
        if (series.size() < 3) {
            return;
        }
        ArrayNode streak = json.createArrayNode();
        for (int index = 0; index < Math.min(30, series.size()); index++) {
            streak.add(series.get(index).path("value").deepCopy());
        }
        List<Double> streakValues = new ArrayList<>();
        streak.forEach(value -> streakValues.add(value.asDouble()));
        ObjectNode block = output.putObject("sentiment");
        block.put("source", "alternative.me (pinned provider, raw API daily series)");
        block.set("spot", series.get(0).get("value").deepCopy());
        block.put("classification", source.get(0).path("value_classification").asText());
        putNumber(block, "avg_3d", ComputeMath.round2((series.get(0).path("value").asDouble()
                + series.get(1).path("value").asDouble() + series.get(2).path("value").asDouble()) / 3.0));
        ObjectNode streaks = block.putObject("streaks_daily_prints");
        for (int threshold : List.of(10, 15, 20, 25)) {
            streaks.put("le" + threshold, ComputeMath.fngStreak(streakValues, threshold));
        }
        ArrayNode last = block.putArray("last_10_prints");
        for (int index = 0; index < Math.min(10, series.size()); index++) {
            last.add(series.get(index).deepCopy());
        }
        block.put("note", "score the 3-day average; gate-1 streak counts DAILY prints ≤15 (≥7 consecutive)");
    }

    private <T> T attempt(String label, MarketFetchExecutor.ThrowingSupplier<T> supplier, T fallback,
                          List<String> errors) {
        try {
            return supplier.get();
        } catch (Exception exception) {
            errors.add(label + ": " + MarketFetchExecutor.message(exception));
            return fallback;
        }
    }

    private static String venue(MarketFetchSupport.AssetConfig asset, String key) {
        return asset == null || asset.venues() == null ? null : asset.venues().get(key);
    }

    private static JsonNode highest(ArrayNode rows) {
        JsonNode result = null;
        if (rows != null) {
            for (JsonNode row : rows) {
                if (!row.hasNonNull("high")) {
                    continue;
                }
                if (result == null || number(row.get("high")) > number(result.get("high"))) {
                    result = row;
                }
            }
        }
        return result;
    }

    private static ArrayNode array(JsonNode value) {
        return value != null && value.isArray() ? (ArrayNode) value : null;
    }

    private ArrayNode arrayOrEmpty(JsonNode value) {
        return value != null && value.isArray() ? (ArrayNode) value : json.createArrayNode();
    }

    private static ObjectNode object(JsonNode value) {
        return value != null && value.isObject() ? (ObjectNode) value : null;
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

    private static double number(JsonNode value) {
        return ComputeMath.jsNumber(value);
    }

    private static void putNumber(ObjectNode target, String key, double value) {
        target.set(key, ComputeMath.normalizedNumberNode(value));
    }

    private static String numberText(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}

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
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Live market-data backbone replacing {@code tools/fetch.mjs}. */
public final class LiveMarketFetchService implements MarketFetchOperations {
    private static final DateTimeFormatter ISO_MILLIS = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final MarketDataEndpoints endpoints;
    private final ObjectMapper json;
    private final java.util.function.LongSupplier clock;
    private final boolean coinglassConfigured;

    public LiveMarketFetchService(MarketDataEndpoints endpoints, ObjectMapper json,
                                  java.util.function.LongSupplier clock, boolean coinglassConfigured) {
        this.endpoints = endpoints;
        this.json = json == null ? new ObjectMapper() : json;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.coinglassConfigured = coinglassConfigured;
    }

    @Override
    public ObjectNode fetchAsset(String key, boolean includeSeries) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        MarketFetchSupport.AssetConfig asset = MarketFetchSupport.ASSETS.get(normalized);
        if (asset == null) throw new IllegalArgumentException("unknown asset \"" + normalized + "\"");
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        long now = clock.getAsLong();
        ObjectNode output = json.createObjectNode();
        output.put("asset", normalized.toUpperCase(Locale.ROOT));
        output.put("fetched_at", ISO_MILLIS.format(Instant.ofEpochMilli(now)));
        ArrayNode errorArray = output.putArray("errors");

        Map<String, FetchTask> tasks = new LinkedHashMap<>();
        add(tasks, "cgSpot", "coingecko spot", asset.coinGeckoId() == null ? null : () ->
                endpointsJson("https://api.coingecko.com/api/v3/simple/price?ids=" + asset.coinGeckoId()
                        + "&vs_currencies=usd&include_last_updated_at=true"));
        add(tasks, "cgCoin", "coingecko coin/ath", asset.coinGeckoId() == null ? null : () ->
                endpointsJson("https://api.coingecko.com/api/v3/coins/" + asset.coinGeckoId()
                        + "?localization=false&tickers=false&market_data=true&community_data=false&developer_data=false"));
        add(tasks, "weekly", "yahoo weekly", () -> endpoints.yahooChart(asset.yahooSymbol(), "5y", "1wk"));
        add(tasks, "daily", "yahoo daily", () -> endpoints.yahooChart(asset.yahooSymbol(), "2y", "1d"));
        add(tasks, "cross", "yahoo cross-spot", asset.crossYahooSymbol() == null ? null
                : () -> endpoints.yahooChart(asset.crossYahooSymbol(), "5d", "1d"));
        add(tasks, "fng", "alternative.me fng", !asset.fearAndGreed() ? null
                : () -> endpointsJson("https://api.alternative.me/fng/?limit=730"));
        add(tasks, "binanceQ", "binance spot", venue(asset, "binance") == null ? null
                : () -> endpoints.binanceQuote(venue(asset, "binance")));
        add(tasks, "coinbaseQ", "coinbase spot", venue(asset, "coinbase") == null ? null
                : () -> endpoints.coinbaseQuote(venue(asset, "coinbase")));
        add(tasks, "krakenQ", "kraken spot", venue(asset, "kraken") == null ? null
                : () -> endpoints.krakenQuote(venue(asset, "kraken")));
        add(tasks, "funding", "binance funding", asset.perpetualSymbol() == null ? null
                : () -> endpoints.binanceFunding(asset.perpetualSymbol(), 1_000));
        add(tasks, "dvol", "deribit dvol", asset.deribitCurrency() == null ? null
                : () -> endpoints.deribitDvol(asset.deribitCurrency()));
        add(tasks, "optionBook", "deribit option book", asset.deribitCurrency() == null ? null
                : () -> endpoints.deribitOptionBook(asset.deribitCurrency()));
        add(tasks, "premiumIndex", "binance premiumIndex", asset.perpetualSymbol() == null ? null
                : () -> endpoints.binancePremiumIndex(asset.perpetualSymbol()));
        add(tasks, "longShort", "binance long/short ratio", asset.perpetualSymbol() == null ? null
                : () -> endpoints.binanceLongShortRatio(asset.perpetualSymbol(), 30));
        add(tasks, "taker", "binance taker ratio", asset.perpetualSymbol() == null ? null
                : () -> endpoints.binanceTakerRatio(asset.perpetualSymbol(), 30));
        add(tasks, "oi", "binance open interest hist", asset.perpetualSymbol() == null ? null
                : () -> endpoints.binanceOpenInterestHistory(asset.perpetualSymbol(), 30, "1d"));
        add(tasks, "borrow", "bitfinex funding ticker", asset.bitfinexFundingSymbol() == null ? null
                : () -> endpoints.bitfinexFundingTicker(asset.bitfinexFundingSymbol()));
        add(tasks, "onchain", "Coin Metrics on-chain", asset.coinMetricsId() == null ? null
                : () -> endpoints.coinMetricsOnchain(asset.coinMetricsId()));
        add(tasks, "premiumRows", "Coinbase premium daily series",
                venue(asset, "coinbase") == null || venue(asset, "binance") == null ? null
                        : () -> endpoints.coinbasePremiumSeries(venue(asset, "coinbase"), venue(asset, "binance")));
        add(tasks, "oi90", "Binance 90d OI archives", asset.perpetualSymbol() == null ? null
                : () -> endpoints.binanceOi90d(asset.perpetualSymbol()));
        add(tasks, "binanceSpotFlow", "Binance spot 4h taker flow", venue(asset, "binance") == null ? null
                : () -> endpoints.binanceFlowKlines(venue(asset, "binance"), false, "4h", 43));
        add(tasks, "binanceFlow", "Binance aggregate market-flow fallback", asset.perpetualSymbol() == null ? null
                : () -> endpoints.binanceAggregateMarketFlow(normalized, venue(asset, "binance"), asset.perpetualSymbol(), 43));
        if (asset.perpetualSymbol() != null && coinglassConfigured) addCoinglassTasks(tasks, normalized);
        Map<String, JsonNode> fetched = run(tasks, errors);
        errors.forEach(errorArray::add);

        ArrayNode weekly = array(fetched.get("weekly"));
        ArrayNode daily = array(fetched.get("daily"));
        ArrayNode cross = array(fetched.get("cross"));
        JsonNode cgSpot = fetched.get("cgSpot");
        JsonNode cgCoin = fetched.get("cgCoin");
        JsonNode fng = fetched.get("fng");
        ArrayNode funding = array(fetched.get("funding"));
        ObjectNode spot = buildSpot(normalized, asset, cgSpot, daily, cross, fetched, now);
        output.set("spot", spot);
        Double canonicalSpot = nullableNumber(spot.get("canonical"));

        buildAth(output, asset, cgCoin, canonicalSpot, errors);
        if (daily != null && weekly != null && canonicalSpot != null) buildOneYearHigh(output, asset, weekly, canonicalSpot, now);
        if (weekly != null) {
            ObjectNode block = json.createObjectNode();
            block.put("source", "Yahoo " + asset.yahooSymbol() + " 5y 1wk (" + weekly.size() + " candles)");
            block.setAll(MarketFetchSupport.weeklyBlock(weekly, canonicalSpot, now));
            output.set("weekly", block);
        }
        if (daily != null) buildDaily(output, asset, daily, canonicalSpot, includeSeries);
        if (fng != null && fng.path("data").isArray()) buildSentiment(output, fng);
        if (funding != null) {
            ObjectNode block = json.createObjectNode();
            block.put("source", "Binance fapi fundingRate (" + asset.perpetualSymbol() + ", " + funding.size() + " intervals)");
            block.setAll(FundingAnalytics.fundingBlock(funding, 45));
            output.set("funding", block);
        }
        ArrayNode onchain = array(fetched.get("onchain"));
        if (onchain != null) {
            ObjectNode block = json.createObjectNode();
            block.put("source", "Coin Metrics Community API (daily; current rows may be flash/back-revised)");
            block.setAll(MarketContextAnalytics.onchainDistributionBlock(onchain));
            output.set("onchain", block);
        }
        ObjectNode premiumRows = object(fetched.get("premiumRows"));
        if (premiumRows != null) {
            ObjectNode block = json.createObjectNode();
            block.put("source", "Coinbase Exchange " + venue(asset, "coinbase")
                    + " + Coinbase USDT-USD + Binance " + venue(asset, "binance") + " completed daily candles");
            block.setAll(MarketContextAnalytics.coinbasePremiumBlock(arrayOrEmpty(premiumRows.get("coinbaseRows")),
                    arrayOrEmpty(premiumRows.get("binanceRows")), arrayOrEmpty(premiumRows.get("usdtUsdRows"))));
            output.set("coinbase_premium", block);
        }
        buildContext(output, normalized, asset, daily, weekly, fng, funding, fetched, now, errors);
        if (asset.coinMetricsId() != null) buildAssetGapCoverage(output);
        // Late fetches (ATH verification/sentiment proxies) append errors after
        // the initial fan-out; refresh the live array in deterministic list order.
        errorArray.removeAll(); errors.forEach(errorArray::add);
        return output;
    }

    /** Fetches the asset-agnostic macro backbone used by both frameworks. */
    @Override
    public ObjectNode fetchMacro() {
        long now = clock.getAsLong();
        ObjectNode output = json.createObjectNode();
        output.put("scope", "macro");
        output.put("fetched_at", ISO_MILLIS.format(Instant.ofEpochMilli(now)));
        ArrayNode errorArray = output.putArray("errors");
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        List<MacroSeries> series = List.of(
                new MacroSeries("vix", "^VIX", "CBOE VIX", "1mo"),
                new MacroSeries("dxy", "DX-Y.NYB", "US Dollar Index", "1mo"),
                new MacroSeries("brent", "BZ=F", "Brent crude", "1mo"),
                new MacroSeries("spx", "^GSPC", "S&P 500", "3mo"),
                new MacroSeries("ndx", "^IXIC", "Nasdaq Composite", "1mo"),
                new MacroSeries("us10y", "^TNX", "US 10y nominal yield (×10 units)", "1mo"),
                new MacroSeries("gold", "GC=F", "COMEX gold front month", "1mo"),
                new MacroSeries("irx", "^IRX", "13-week T-bill discount rate (%)", "1mo"),
                new MacroSeries("move", "^MOVE", "ICE BofA MOVE Index (bond vol)", "1mo"));

        Map<String, FetchTask> tasks = new LinkedHashMap<>();
        add(tasks, "fred", "FRED DFII10", () -> endpoints.fredCsv("DFII10"));
        add(tasks, "fred3mo", "FRED DGS3MO", () -> endpoints.fredCsv("DGS3MO"));
        add(tasks, "hyOas", "FRED BAMLH0A0HYM2 (HY OAS)", () -> endpoints.fredCsv("BAMLH0A0HYM2"));
        add(tasks, "nfci", "FRED NFCI", () -> endpoints.fredCsv("NFCI"));
        add(tasks, "walcl", "FRED WALCL", () -> endpoints.fredCsv("WALCL"));
        add(tasks, "rrpontsyd", "FRED RRPONTSYD", () -> endpoints.fredCsv("RRPONTSYD"));
        add(tasks, "wtregen", "FRED WTREGEN", () -> endpoints.fredCsv("WTREGEN"));
        add(tasks, "stablecoinRows", "DefiLlama stablecoincharts", endpoints::stablecoinCharts);
        add(tasks, "breadthData", "S&P 500 breadth above 200dma", endpoints::equityBreadth200);
        for (MacroSeries item : series) add(tasks, "chart:" + item.key(), "yahoo " + item.symbol(),
                () -> endpoints.yahooChart(item.symbol(), item.range(), "1d"));
        Map<String, JsonNode> fetched = run(tasks, errors);

        ArrayNode fred = array(fetched.get("fred"));
        if (nonEmpty(fred)) {
            ObjectNode block = output.putObject("real_yield_10y_tips");
            block.put("source", "FRED DFII10 (daily, %)");
            block.set("last", fred.get(fred.size() - 1).deepCopy());
            int prior = Math.max(0, fred.size() - 6);
            putNumber(block, "delta_5_prints", ComputeMath.round2(number(fred.get(fred.size() - 1).get("value"))
                    - number(fred.get(prior).get("value"))));
            block.set("last_10", fred.deepCopy());
        }
        ArrayNode hyOas = array(fetched.get("hyOas"));
        if (nonEmpty(hyOas)) {
            ObjectNode block = output.putObject("hy_oas");
            block.put("source", "FRED BAMLH0A0HYM2 (ICE BofA US High Yield OAS, daily, %)");
            block.set("last", hyOas.get(hyOas.size() - 1).deepCopy());
            int prior = Math.max(0, hyOas.size() - 6);
            putNumber(block, "delta_5_prints", ComputeMath.round2(number(hyOas.get(hyOas.size() - 1).get("value"))
                    - number(hyOas.get(prior).get("value"))));
            block.put("note", "DISCLOSED CONTEXT ONLY — credit stress, not a scored input");
        }
        ArrayNode nfci = array(fetched.get("nfci"));
        if (nonEmpty(nfci)) {
            ObjectNode block = output.putObject("nfci");
            block.put("source", "FRED NFCI (Chicago Fed National Financial Conditions Index, weekly)");
            block.set("last", nfci.get(nfci.size() - 1).deepCopy());
            block.put("note", "DISCLOSED CONTEXT ONLY — 0 = historical average; positive = tighter-than-average conditions");
        }
        ArrayNode walcl = array(fetched.get("walcl"));
        ArrayNode rrp = array(fetched.get("rrpontsyd"));
        ArrayNode tga = array(fetched.get("wtregen"));
        if (nonEmpty(walcl) && nonEmpty(rrp) && nonEmpty(tga)) {
            ObjectNode block = output.putObject("net_liquidity");
            block.put("source", "FRED WALCL + RRPONTSYD + WTREGEN (weekly, Thursdays)");
            block.put("as_of", walcl.get(walcl.size() - 1).path("date").asText());
            block.setAll(ComputeMath.netLiquidity(
                    nullableNumber(walcl.get(walcl.size() - 1).get("value")),
                    nullableNumber(rrp.get(rrp.size() - 1).get("value")),
                    nullableNumber(tga.get(tga.size() - 1).get("value"))));
        }
        ArrayNode stablecoinRows = array(fetched.get("stablecoinRows"));
        if (nonEmpty(stablecoinRows)) {
            ObjectNode block = output.putObject("stablecoin_supply");
            block.put("source", "DefiLlama stablecoincharts/all (aggregate across all tracked stablecoins/chains)");
            block.setAll(ComputeMath.stablecoinBlock(stablecoinRows));
        }
        ObjectNode breadthData = object(fetched.get("breadthData"));
        if (breadthData != null) {
            ObjectNode block = output.putObject("equities_breadth_200dma");
            block.put("source", "State Street SPY daily holdings universe + TradingView America scanner close/SMA200");
            block.setAll(MarketContextAnalytics.breadth200Block(
                    arrayOrEmpty(breadthData.get("rows")), nullableNumber(breadthData.get("universeSize")),
                    breadthData.path("universeAsOf").isNull() ? null : breadthData.path("universeAsOf").asText(), 95.0));
            block.put("scope_note", "SPY constituent universe; descriptive macro breadth, not a scored Channel B leg or gate");
        }

        for (MacroSeries item : series) {
            ArrayNode chart = array(fetched.get("chart:" + item.key()));
            if (!nonEmpty(chart)) continue;
            JsonNode last = chart.get(chart.size() - 1);
            JsonNode prior = chart.get(Math.max(0, chart.size() - 6));
            ObjectNode block = output.putObject(item.key());
            block.put("source", "Yahoo " + item.symbol() + " (" + item.label() + ")");
            putNumber(block, "last_close", ComputeMath.round2(number(last.get("close"))));
            block.put("date", last.path("date").asText());
            putNumber(block, "delta_5_sessions_pct", ComputeMath.round2(
                    (number(last.get("close")) / number(prior.get("close")) - 1.0) * 100.0));
            if ("spx".equals(item.key())) {
                ArrayNode values = block.putArray("series");
                for (JsonNode source : chart) {
                    ObjectNode row = values.addObject(); row.put("date", source.path("date").asText());
                    putNumber(row, "close", ComputeMath.round2(number(source.get("close"))));
                }
            }
        }

        Double irx = nullableNumber(at(output, "irx", "last_close"));
        ArrayNode fred3mo = array(fetched.get("fred3mo"));
        Double dgs3mo = nonEmpty(fred3mo) ? nullableNumber(fred3mo.get(fred3mo.size() - 1).get("value")) : null;
        if (irx != null || dgs3mo != null) {
            ObjectNode block = output.putObject("dry_powder_benchmark");
            putNumber(block, "annualized_pct", irx != null ? irx : dgs3mo);
            if (irx != null) block.put("source", "Yahoo ^IRX (" + output.path("irx").path("date").asText() + ")");
            else block.put("source", "FRED DGS3MO (" + fred3mo.get(fred3mo.size() - 1).path("date").asText() + ")");
            if (irx != null && dgs3mo != null) {
                ObjectNode cross = block.putObject("cross_check");
                putNumber(cross, "irx", irx); putNumber(cross, "dgs3mo", dgs3mo);
                putNumber(cross, "delta_pct_pts", ComputeMath.round2(irx - dgs3mo));
            } else block.putNull("cross_check");
            block.put("note", "idle-cash opportunity cost — what dry powder earns risk-free while unallocated");
        }
        ObjectNode coverage = output.putObject("gap_coverage");
        coverage.put("equities_breadth_pct_above_200dma",
                output.path("equities_breadth_200dma").path("available").asBoolean() ? "AVAILABLE" : "UNKNOWN");
        coverage.put("report_rule", "inspect this block before labeling equities breadth UNKNOWN");
        errors.forEach(errorArray::add);
        return output;
    }

    private ObjectNode buildSpot(String key, MarketFetchSupport.AssetConfig asset, JsonNode cgSpot,
                                 ArrayNode daily, ArrayNode cross, Map<String, JsonNode> fetched, long now) {
        Double yahoo = lastClose(daily);
        Double cg = asset.coinGeckoId() == null ? null : nullableNumber(at(cgSpot, asset.coinGeckoId(), "usd"));
        Double crossValue = lastClose(cross);
        ArrayNode sources = json.createArrayNode();
        if (cg != null) { ObjectNode row = sources.addObject(); row.put("source", "CoinGecko"); putNumber(row, "value", cg); }
        if (yahoo != null) { ObjectNode row = sources.addObject(); row.put("source", "Yahoo " + asset.yahooSymbol() + " (last daily close)"); putNumber(row, "value", ComputeMath.round2(yahoo)); }
        if (crossValue != null) { ObjectNode row = sources.addObject(); row.put("source", "Yahoo " + asset.crossYahooSymbol()); putNumber(row, "value", ComputeMath.round2(crossValue)); }
        Double divergence = null;
        if (sources.size() >= 2) {
            List<Double> values = new ArrayList<>(); sources.forEach(row -> values.add(row.path("value").asDouble()));
            divergence = ComputeMath.round2((Collections.max(values) / Collections.min(values) - 1.0) * 100.0);
        }
        Long cgTime = asset.coinGeckoId() == null ? null : nullableLong(at(cgSpot, asset.coinGeckoId(), "last_updated_at"));
        ArrayNode quotes = json.createArrayNode();
        if (cg != null) quote(quotes, "CoinGecko", asset.coinGeckoId(), cg, cgTime == null ? null : cgTime * 1_000L, "venue");
        if (yahoo != null) quote(quotes, "Yahoo " + asset.yahooSymbol(), asset.yahooSymbol(), ComputeMath.round2(yahoo), null, "bar_close");
        if (crossValue != null) quote(quotes, "Yahoo " + asset.crossYahooSymbol(), asset.crossYahooSymbol(), ComputeMath.round2(crossValue), null, "bar_close");
        for (String keyName : List.of("binanceQ", "coinbaseQ", "krakenQ")) {
            JsonNode quote = fetched.get(keyName); if (quote != null) quotes.add(quote.deepCopy());
        }
        ObjectNode panel = MarketSeriesAnalytics.spotPanel(quotes, now, 120, 0.5);
        Double panelMedian = nullableNumber(panel.get("canonical"));
        Double priority = sources.isEmpty() ? null : sources.get(0).path("value").asDouble();
        Double canonical = panelMedian != null ? panelMedian : priority;
        ObjectNode output = json.createObjectNode();
        putNullable(output, "canonical", canonical); output.set("sources", sources);
        putNullable(output, "divergence_pct", divergence);
        if (divergence != null && divergence > 1.5) output.put("warning", "inter-source spread " + numberText(divergence) + "% > 1.5% — reconcile before scoring");
        else output.set("warning", NullNode.instance);
        output.put("canonical_source", panelMedian != null ? "panel_median" : "priority_first_fallback");
        output.set("panel", panel); putNullable(output, "canonical_median", panelMedian);
        output.set("method_conflict", NullNode.instance);
        return output;
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
            putNumber(ath, "drawdown_pct", drawdown); ath.put("source", "CoinGecko");
            return;
        }
        if (asset.athRange() == null || spot == null) return;
        ArrayNode window = attempt("yahoo " + asset.athRange() + " high",
                () -> endpoints.yahooChart(asset.yahooSymbol(), asset.athRange(), "1wk"), null, errors);
        ArrayNode full = attempt("yahoo max high (ATH verification)",
                () -> endpoints.yahooChart(asset.yahooSymbol(), "max", "1mo"), null, errors);
        if (window == null || window.isEmpty()) return;
        JsonNode highest = highest(window);
        ObjectNode ath = json.createObjectNode();
        putNumber(ath, "value", ComputeMath.round2(number(highest.get("high"))));
        ath.put("date", highest.path("date").asText());
        putNumber(ath, "drawdown_pct", ComputeMath.drawdownPct(spot, number(highest.get("high"))));
        long cutoff = window.get(0).path("t").asLong();
        List<JsonNode> prior = new ArrayList<>();
        if (full != null) for (JsonNode candle : full) if (candle.path("t").asLong() < cutoff && candle.hasNonNull("high")) prior.add(candle);
        if (!prior.isEmpty()) {
            JsonNode priorHigh = prior.stream().max(Comparator.comparingDouble(row -> number(row.get("high")))).orElseThrow();
            boolean verified = number(priorHigh.get("high")) < number(highest.get("high"));
            ath.put("all_time_verified", verified);
            ObjectNode pre = ath.putObject("pre_window_high");
            putNumber(pre, "value", ComputeMath.round2(number(priorHigh.get("high")))); pre.put("date", priorHigh.path("date").asText());
            pre.put("history_from", full.get(0).path("date").asText()); pre.put("bars", prior.size());
            ath.put("source", verified
                    ? "Yahoo " + asset.yahooSymbol() + " " + asset.athRange() + " weekly high — VERIFIED all-time: pre-window max " + numberText(ComputeMath.round2(number(priorHigh.get("high")))) + " @ " + priorHigh.path("date").asText() + " (monthly bars back to " + full.get(0).path("date").asText() + ") is below it"
                    : "Yahoo " + asset.yahooSymbol() + " " + asset.athRange() + " weekly high — NOT all-time: " + numberText(ComputeMath.round2(number(priorHigh.get("high")))) + " @ " + priorHigh.path("date").asText() + " traded higher BEFORE the window; the drawdown denominator understates the true ATH drawdown");
        } else {
            ath.set("all_time_verified", NullNode.instance);
            ath.put("source", "Yahoo " + asset.yahooSymbol() + " " + asset.athRange() + " weekly high — NOT all-time (pre-window history unavailable); flag the window in the report");
        }
        output.set("ath", ath);
    }

    private void buildOneYearHigh(ObjectNode output, MarketFetchSupport.AssetConfig asset,
                                  ArrayNode weekly, double spot, long now) {
        List<JsonNode> values = new ArrayList<>();
        for (JsonNode candle : weekly) if (candle.path("t").asLong() >= now - 366L * 86_400_000L) values.add(candle);
        JsonNode highest = values.stream().filter(row -> row.hasNonNull("high"))
                .max(Comparator.comparingDouble(row -> number(row.get("high")))).orElse(null);
        if (highest == null || number(highest.get("high")) <= 0.0) return;
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
            JsonNode source = daily.get(index); ObjectNode row = sessions.addObject();
            row.put("date", source.path("date").asText());
            for (String field : List.of("high", "low", "close")) putNumber(row, field, ComputeMath.round2(number(source.get(field))));
        }
        ObjectNode block = json.createObjectNode();
        block.put("source", "Yahoo " + asset.yahooSymbol() + " 2y 1d (" + daily.size() + " candles)");
        block.set("last_sessions", sessions); block.set("adr5", ComputeMath.adr(sessions, 5, List.of()));
        block.put("note", "ADR must use 5 FULL sessions — if any listed session is holiday-abbreviated, recompute with tools/compute.mjs adr --exclude <date> and disclose");
        if (includeSeries) {
            ArrayNode series = block.putArray("series");
            for (JsonNode source : daily) {
                ObjectNode row = series.addObject(); row.put("date", source.path("date").asText());
                for (String field : List.of("open", "high", "low", "close")) putNumber(row, field, ComputeMath.round2(number(source.get(field))));
            }
        }
        output.set("daily", block);
        ArrayNode trendInput = json.createArrayNode();
        for (JsonNode source : daily) {
            ObjectNode row = trendInput.addObject(); row.put("date", source.path("date").asText());
            for (String field : List.of("high", "low", "close")) row.set(field, source.get(field).deepCopy());
        }
        output.set("trend", ComputeMath.dailyTrend(trendInput, spot, 50, 200, 20, 40));
    }

    private void buildSentiment(ObjectNode output, JsonNode response) {
        ArrayNode source = (ArrayNode) response.path("data");
        ArrayNode series = json.createArrayNode();
        for (JsonNode item : source) {
            ObjectNode row = series.addObject(); putNumber(row, "value", number(item.get("value")));
            long seconds = (long) number(item.get("timestamp"));
            row.put("date", Instant.ofEpochSecond(seconds).atZone(ZoneOffset.UTC).toLocalDate().toString());
        }
        if (series.size() < 3) return;
        ArrayNode streak = json.createArrayNode();
        for (int index = 0; index < Math.min(30, series.size()); index++) streak.add(series.get(index).path("value").deepCopy());
        List<Double> streakValues = new ArrayList<>(); streak.forEach(value -> streakValues.add(value.asDouble()));
        ObjectNode block = output.putObject("sentiment");
        block.put("source", "alternative.me (pinned provider, raw API daily series)");
        block.set("spot", series.get(0).get("value").deepCopy());
        block.put("classification", source.get(0).path("value_classification").asText());
        putNumber(block, "avg_3d", ComputeMath.round2((series.get(0).path("value").asDouble()
                + series.get(1).path("value").asDouble() + series.get(2).path("value").asDouble()) / 3.0));
        ObjectNode streaks = block.putObject("streaks_daily_prints");
        for (int threshold : List.of(10, 15, 20, 25)) streaks.put("le" + threshold, ComputeMath.fngStreak(streakValues, threshold));
        ArrayNode last = block.putArray("last_10_prints");
        for (int index = 0; index < Math.min(10, series.size()); index++) last.add(series.get(index).deepCopy());
        block.put("note", "score the 3-day average; gate-1 streak counts DAILY prints ≤15 (≥7 consecutive)");
    }

    private void buildContext(ObjectNode output, String key, MarketFetchSupport.AssetConfig asset,
                              ArrayNode daily, ArrayNode weekly, JsonNode fng, ArrayNode funding,
                              Map<String, JsonNode> fetched, long now, List<String> errors) {
        ObjectNode context = json.createObjectNode();
        List<Double> dailyCloses = closes(daily);
        if (daily != null) {
            ObjectNode realized = ComputeMath.realizedVolBlock(dailyCloses, asset.annualize());
            List<Double> history = MarketSeriesAnalytics.rollingRealizedVol(dailyCloses, 30, asset.annualize());
            Double rv30 = nullableNumber(realized.get("rv30"));
            putNullable(realized, "rv30_percentile_vs_2y", history.isEmpty() || rv30 == null
                    ? null : ComputeMath.percentileRank(history, rv30));
            context.set("realized_vol", realized);

            List<Double> drawdowns = MarketSeriesAnalytics.rollingDrawdownFromAth(dailyCloses);
            Double currentDrawdown = drawdowns.isEmpty() ? null : drawdowns.get(drawdowns.size() - 1);
            putNullable(context, "drawdown_pct_vs_2y_high", currentDrawdown);
            putNullable(context, "drawdown_pct_vs_2y_high_percentile", drawdowns.isEmpty() || currentDrawdown == null
                    ? null : ComputeMath.percentileRank(drawdowns, currentDrawdown));
            context.put("drawdown_note", "running high WITHIN the fetched 2y daily window, not the true all-time high — see outp.ath for the ATH drawdown");

            JsonNode trend = output.get("trend");
            Double canonical = nullableNumber(output.path("spot").get("canonical"));
            Double ma200 = nullableNumber(at(trend, "ma200"));
            if (ma200 != null && canonical != null) {
                double distance = ComputeMath.round2((canonical / ma200 - 1.0) * 100.0);
                List<Double> distances = MarketSeriesAnalytics.rollingSmaDistance(dailyCloses, 200);
                putNumber(context, "distance_to_200dma_pct", distance);
                putNullable(context, "distance_to_200dma_percentile", distances.isEmpty()
                        ? null : ComputeMath.percentileRank(distances, distance));
            }
            if (trend != null && !ComputeMath.truthy(trend.get("insufficient"))) {
                Double rsi = nullableNumber(trend.get("rsi14"));
                if (rsi != null) {
                    List<Double> values = MarketSeriesAnalytics.rollingWilderRsi(dailyCloses, 14);
                    putNullable(context, "daily_rsi14_percentile_vs_2y", values.isEmpty()
                            ? null : ComputeMath.percentileRank(values, rsi));
                }
                Double bounce = nullableNumber(trend.get("bounce_pct"));
                if (bounce != null) {
                    List<Double> values = MarketSeriesAnalytics.rollingBouncePercent(dailyCloses, 40);
                    putNullable(context, "bounce_pct_percentile_vs_2y", values.isEmpty()
                            ? null : ComputeMath.percentileRank(values, bounce));
                }
            }
            Double below = nullableNumber(at(output, "high_1y", "pct_below"));
            if (below != null && dailyCloses.size() > 365) {
                List<Double> values = MarketSeriesAnalytics.rollingTrailingHighDistance(dailyCloses, 365);
                putNullable(context, "high_1y_pct_below_percentile_vs_2y", values.isEmpty()
                        ? null : ComputeMath.percentileRank(values, below));
                context.put("high_1y_pct_below_percentile_note", "proxy: a 365-daily-CLOSE trailing-high window over the fetched 2y series, not the weekly-high computation outp.high_1y itself uses — related, not identical");
            }
            if (!daily.isEmpty() && daily.get(daily.size() - 1).hasNonNull("volume")) {
                double latest = number(daily.get(daily.size() - 1).get("volume"));
                List<Double> volumes = new ArrayList<>();
                for (int index = 0; index < daily.size() - 1; index++) {
                    if (daily.get(index).hasNonNull("volume")) volumes.add(number(daily.get(index).get("volume")));
                }
                ObjectNode volume = context.putObject("volume"); putNumber(volume, "last", latest);
                putNullable(volume, "percentile_vs_2y", volumes.isEmpty() ? null : ComputeMath.percentileRank(volumes, latest));
                volume.put("units_note", "Yahoo-reported units are asset-class-specific (crypto pairs: USD quote volume; futures like GC=F: contract count) — not converted, not comparable across assets");
            }
        }
        if (weekly != null) {
            ArrayNode completed = MarketFetchSupport.completedCandles(weekly, 7L * 86_400_000L, now);
            List<Double> weeklyCloses = closes(completed);
            if (weeklyCloses.size() >= 15) {
                List<Double> history = MarketSeriesAnalytics.rollingWilderRsi(weeklyCloses, 14);
                Double current = nullableNumber(ComputeMath.wilderRsi(weeklyCloses, 14).get("rsi"));
                putNullable(context, "weekly_rsi14_percentile", history.isEmpty() || current == null
                        ? null : ComputeMath.percentileRank(history, current));
            }
        }
        if (funding != null && !funding.isEmpty() && output.has("funding")) {
            List<Double> values = MarketFetchSupport.dailyAnnualizedFundingSeries(funding);
            Double current = nullableNumber(output.path("funding").get("mean_annualized_pct"));
            putNullable(context, "funding_annualized_percentile_vs_history", values.isEmpty() || current == null
                    ? null : ComputeMath.percentileRank(values, current));
            context.put("funding_history_days_available", values.size());
        }
        ObjectNode premium = object(fetched.get("premiumIndex"));
        if (premium != null && output.has("funding")) {
            ObjectNode basis = json.createObjectNode();
            basis.put("source", "Binance fapi premiumIndex (" + asset.perpetualSymbol() + ")");
            basis.setAll(ComputeMath.basisBlock(nullableNumber(premium.get("markPrice")),
                    nullableNumber(premium.get("indexPrice")),
                    nullableNumber(output.path("funding").get("mean_annualized_pct")), null));
            context.set("basis", basis);
        }
        ArrayNode longShort = array(fetched.get("longShort"));
        ArrayNode taker = array(fetched.get("taker"));
        ArrayNode oi = array(fetched.get("oi"));
        if (asset.perpetualSymbol() != null && (nonEmpty(longShort) || nonEmpty(taker) || nonEmpty(oi))) {
            ObjectNode positioning = json.createObjectNode();
            positioning.put("source", "Binance fapi globalLongShortAccountRatio + takerlongshortRatio + openInterestHist ("
                    + asset.perpetualSymbol() + ")");
            positioning.setAll(ComputeMath.positioningBlock(emptyIfNull(longShort), emptyIfNull(taker), emptyIfNull(oi)));
            ObjectNode archived = MarketContextAnalytics.oi90dBlock(emptyIfNull(array(fetched.get("oi90"))));
            ObjectNode archivedWithSource = json.createObjectNode();
            archivedWithSource.put("source", "Binance Data Vision USD-M daily metrics archives (" + asset.perpetualSymbol() + ")");
            archivedWithSource.setAll(archived); positioning.set("open_interest_90d", archivedWithSource);
            if (positioning.path("open_interest").isObject() && archived.path("available").asBoolean()) {
                ((ObjectNode) positioning.path("open_interest")).put("oi_90d_high_available", true);
                ((ObjectNode) positioning.path("open_interest")).set("oi_within_5pct_of_90d_high",
                        archived.get("within_5pct_of_90d_high").deepCopy());
            }
            context.set("positioning", positioning);
        }
        if (asset.perpetualSymbol() != null) context.set("market_flow",
                buildMarketFlowContext(asset, fetched, output));
        if (asset.bitfinexFundingSymbol() != null) {
            ObjectNode borrow = json.createObjectNode();
            borrow.put("source", "Bitfinex margin funding (" + asset.bitfinexFundingSymbol() + ")");
            borrow.setAll(ComputeMath.borrowBlock(emptyIfNull(array(fetched.get("borrow")))));
            context.set("borrow", borrow);
        }
        if (fng != null && fng.path("data").isArray()) {
            List<Double> values = new ArrayList<>(); fng.path("data").forEach(row -> values.add(number(row.get("value"))));
            putNullable(context, "fng_percentile_vs_2y", values.size() > 1
                    ? ComputeMath.percentileRank(values.subList(1, values.size()), values.get(0)) : null);
            context.put("fng_history_days_available", values.size());
        }
        if (asset.sentimentProxy() != null) buildSentimentProxy(context, asset, errors);
        context.set("proximity", SnapshotPanels.proximityPanel(output));
        if (asset.deribitCurrency() != null) {
            ObjectNode deribit = json.createObjectNode();
            deribit.put("source", "Deribit get_volatility_index_data + get_book_summary_by_currency ("
                    + asset.deribitCurrency() + ")");
            deribit.setAll(ComputeMath.deribitVolBlock(emptyIfNull(array(fetched.get("optionBook"))),
                    emptyIfNull(array(fetched.get("dvol"))), nullableNumber(at(context, "realized_vol", "rv30")), now));
            context.set("deribit", deribit);
        }
        if (!context.isEmpty()) {
            ObjectNode wrapper = json.createObjectNode();
            wrapper.put("note", "disclosed context only except open_interest_90d, which may populate the pre-existing FR squeeze condition; promoting any other field into the rubric is a framework-calibration job");
            wrapper.setAll(context); output.set("context", wrapper);
        }
    }

    private ObjectNode buildMarketFlowContext(MarketFetchSupport.AssetConfig asset,
                                              Map<String, JsonNode> fetched, ObjectNode output) {
        ArrayNode cgSpot = endpoints.coinglassFlowRows(emptyIfNull(array(fetched.get("cgSpotFlow"))), 4);
        ArrayNode cgFutures = endpoints.coinglassFlowRows(emptyIfNull(array(fetched.get("cgFuturesFlow"))), 4);
        ArrayNode binanceSpot = emptyIfNull(array(fetched.get("binanceSpotFlow")));
        ObjectNode fallback = object(fetched.get("binanceFlow"));
        Map<Long, Double> priceByTime = new LinkedHashMap<>();
        for (JsonNode row : binanceSpot) priceByTime.put(row.path("time").asLong(), nullableNumber(row.get("close")));
        ArrayNode spotRows = !cgSpot.isEmpty() ? attachClose(cgSpot, priceByTime)
                : fallback != null && !fallback.path("spotRows").isEmpty() ? (ArrayNode) fallback.path("spotRows").deepCopy() : binanceSpot;
        ArrayNode futuresRows = !cgFutures.isEmpty() ? attachClose(cgFutures, priceByTime)
                : fallback == null ? json.createArrayNode() : emptyIfNull(array(fallback.get("futuresRows")));
        ArrayNode cgOi = endpoints.coinglassCandleRows(emptyIfNull(array(fetched.get("cgOi"))), 4);
        ArrayNode fallbackOi = fallback == null ? json.createArrayNode() : emptyIfNull(array(fallback.get("openInterestRows")));
        ArrayNode cgFunding = endpoints.coinglassCandleRows(emptyIfNull(array(fetched.get("cgFunding"))), 4);
        ArrayNode fallbackFunding = fallback == null ? json.createArrayNode() : emptyIfNull(array(fallback.get("oiWeightedFundingRows")));
        ArrayNode fundingRows = !cgFunding.isEmpty() ? cgFunding : fallbackFunding;
        ObjectNode metadata = fallback == null ? null : object(fallback.get("metadata"));
        List<String> fallbackSpot = texts(at(metadata, "spot_symbols_included"));
        List<String> fallbackFlows = texts(first(at(metadata, "futures_flow_symbols_included"), at(metadata, "perpetual_symbols_included")));
        List<String> fallbackOis = texts(first(at(metadata, "oi_symbols_included"), at(metadata, "perpetual_symbols_included")));
        List<String> fallbackFundings = texts(first(at(metadata, "funding_symbols_included"), at(metadata, "perpetual_symbols_included")));
        ObjectNode fields = json.createObjectNode();
        fields.put("spot_cvd", !cgSpot.isEmpty() ? "Coinglass aggregated Binance+OKX+Bybit"
                : "Binance aggregate spot CVD (" + fallbackName(fallbackSpot, venue(asset, "binance")) + "; stable-USD quotes treated as nominal USD)");
        fields.put("futures_cvd_and_delta", !cgFutures.isEmpty() ? "Coinglass aggregated Binance+OKX+Bybit"
                : "Binance aggregate USD-M perpetual CVD (" + fallbackName(fallbackFlows, asset.perpetualSymbol()) + ")");
        fields.put("open_interest", !cgOi.isEmpty() ? "Coinglass cross-exchange OHLC"
                : "Binance aggregate USD-M OI; 30m snapshots resampled to sampled 4h OHLC ("
                + fallbackName(fallbackOis, asset.perpetualSymbol()) + ")");
        fields.put("oi_weighted_funding", !cgFunding.isEmpty() ? "Coinglass cross-exchange OI-weighted OHLC"
                : !fundingRows.isEmpty() ? "Binance USD-M OI-weighted funding across "
                + fallbackName(fallbackFundings, asset.perpetualSymbol()) + "; single venue"
                : "NOT AVAILABLE — Binance aggregate funding calculation failed");
        int nCg = (!cgSpot.isEmpty() ? 1 : 0) + (!cgFutures.isEmpty() ? 1 : 0)
                + (!cgOi.isEmpty() ? 1 : 0) + (!cgFunding.isEmpty() ? 1 : 0);
        String scope = nCg == 4 ? "Coinglass cross-exchange (Binance, OKX, Bybit)"
                : nCg > 0 ? "mixed Coinglass cross-exchange + Binance fallback"
                : "Binance aggregate fallback (single venue; not cross-exchange/market-wide)";
        ObjectNode block = MarketFlowPanel.build(spotRows, futuresRows,
                !cgOi.isEmpty() ? cgOi : fallbackOi, fundingRows, 4, scope);
        if (fundingRows.isEmpty() && output.has("funding")) {
            ObjectNode reference = ((ObjectNode) block.path("oi_weighted_funding")).putObject("fallback_reference");
            reference.put("source", "Binance " + asset.perpetualSymbol() + " single-venue funding — NOT OI-weighted");
            copy(reference, "mean_annualized_pct", output.path("funding").get("mean_annualized_pct"));
            copy(reference, "sign_convention", output.path("funding").get("sign_convention"));
        } else if (!cgFunding.isEmpty()) {
            ((ObjectNode) block.path("oi_weighted_funding")).put("unit_note", "Coinglass funding-rate values are preserved exactly as reported; use sign/relative history here and do not annualize this candle series without a separately verified interval/unit contract");
        } else if (!fundingRows.isEmpty()) {
            ((ObjectNode) block.path("oi_weighted_funding")).put("unit_note", textOr(at(metadata, "funding_unit"), "raw Binance funding-rate fraction"));
            ((ObjectNode) block.path("oi_weighted_funding")).put("method_note", textOr(at(metadata, "funding_method"), "OI-weighted across the available Binance USD-M perpetual set"));
            ((ObjectNode) block.path("oi_weighted_funding")).put("interval_caveat", textOr(at(metadata, "funding_interval_caveat"), "Use sign and relative history; verify contract funding intervals before annualizing."));
        }
        ObjectNode result = json.createObjectNode(); result.set("source", fields);
        result.put("coinglass_api_configured", coinglassConfigured);
        result.set("binance_aggregate", metadata == null ? NullNode.instance : metadata.deepCopy());
        result.put("coinglass_setup_note", coinglassConfigured
                ? "COINGLASS_API_KEY configured; any unavailable field fell back independently and is named above"
                : "Set COINGLASS_API_KEY to enable cross-exchange data. The keyless fallback aggregates active Binance stable-USD spot pairs and USD-M perpetuals, but remains single-venue; stablecoin quotes are nominal USD and 4h OI highs/lows are sampled from 30m observations.");
        result.setAll(block); return result;
    }

    private void buildSentimentProxy(ObjectNode context, MarketFetchSupport.AssetConfig asset,
                                     List<String> errors) {
        MarketFetchSupport.SentimentProxy proxy = asset.sentimentProxy();
        Map<String, FetchTask> tasks = new LinkedHashMap<>();
        add(tasks, "vol", "yahoo " + proxy.volatilitySymbol() + " (sentiment proxy)",
                () -> endpoints.yahooChart(proxy.volatilitySymbol(), "5y", "1d"));
        add(tasks, "cef", "yahoo " + proxy.closedEndFundSymbol() + " (sentiment proxy)",
                () -> endpoints.yahooChart(proxy.closedEndFundSymbol(), "5y", "1d"));
        add(tasks, "ref", "yahoo " + proxy.referenceSymbol() + " (sentiment proxy ref)",
                () -> endpoints.yahooChart(proxy.referenceSymbol(), "5y", "1d"));
        Map<String, JsonNode> values = run(tasks, errors);
        ArrayNode vol = array(values.get("vol")), cef = array(values.get("cef")), ref = array(values.get("ref"));
        List<Double> cefCloses = null, refCloses = null;
        if (cef != null && ref != null) {
            Map<String, Double> refByDate = new LinkedHashMap<>();
            for (JsonNode row : ref) refByDate.put(row.path("date").asText(), number(row.get("close")));
            cefCloses = new ArrayList<>(); refCloses = new ArrayList<>();
            for (JsonNode row : cef) if (refByDate.containsKey(row.path("date").asText())) {
                cefCloses.add(number(row.get("close"))); refCloses.add(refByDate.get(row.path("date").asText()));
            }
        }
        ObjectNode block = MarketContextAnalytics.sentimentProxyBlock(
                vol == null ? null : closes(vol), cefCloses, refCloses, 250, 504);
        if (block.has("vol_index") || block.has("cef_premium")) {
            ObjectNode result = json.createObjectNode();
            result.put("source", "Yahoo " + String.join(" + ", List.of(proxy.volatilitySymbol(),
                    proxy.closedEndFundSymbol(), proxy.referenceSymbol())));
            result.setAll(block); context.set("sentiment_proxy", result);
        }
    }

    private void buildAssetGapCoverage(ObjectNode output) {
        ObjectNode coverage = output.putObject("gap_coverage");
        coverage.put("mvrv_z", output.path("onchain").path("available").asBoolean() ? "AVAILABLE" : "UNKNOWN");
        coverage.put("exchange_reserve_and_flows", output.path("onchain").path("available").asBoolean() ? "AVAILABLE" : "UNKNOWN");
        coverage.put("lth", output.path("onchain").path("lth").has("status")
                ? output.path("onchain").path("lth").path("status").asText() : "UNKNOWN");
        coverage.put("coinbase_premium_3d", output.path("coinbase_premium").path("available").asBoolean() ? "AVAILABLE" : "UNKNOWN");
        coverage.put("open_interest_90d_high", output.path("context").path("positioning")
                .path("open_interest_90d").path("available").asBoolean() ? "AVAILABLE" : "UNKNOWN");
        coverage.put("report_rule", "inspect this block before labeling any listed item UNKNOWN or NOT_COVERED");
    }

    private void addCoinglassTasks(Map<String, FetchTask> tasks, String asset) {
        String symbol = asset.toUpperCase(Locale.ROOT);
        Map<String, String> flow = Map.of("exchange_list", "Binance,OKX,Bybit", "symbol", symbol,
                "interval", "4h", "limit", "43", "unit", "usd");
        Map<String, String> candle = Map.of("symbol", symbol, "interval", "4h", "limit", "43", "unit", "usd");
        add(tasks, "cgSpotFlow", "Coinglass aggregated spot taker flow",
                () -> endpoints.coinglassJson("/api/spot/aggregated-taker-buy-sell-volume/history", flow));
        add(tasks, "cgFuturesFlow", "Coinglass aggregated futures taker flow",
                () -> endpoints.coinglassJson("/api/futures/aggregated-taker-buy-sell-volume/history", flow));
        add(tasks, "cgOi", "Coinglass aggregated OI candles",
                () -> endpoints.coinglassJson("/api/futures/open-interest/aggregated-history", candle));
        add(tasks, "cgFunding", "Coinglass OI-weighted funding candles",
                () -> endpoints.coinglassJson("/api/futures/funding-rate/oi-weight-history",
                        Map.of("symbol", symbol, "interval", "4h", "limit", "43")));
    }

    private JsonNode endpointsJson(String url) throws Exception {
        return endpoints.rawJson(url);
    }

    private Map<String, JsonNode> run(Map<String, FetchTask> tasks, List<String> errors) {
        Map<String, JsonNode> output = Collections.synchronizedMap(new LinkedHashMap<>());
        if (tasks.isEmpty()) return output;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<JsonNode>> futures = new LinkedHashMap<>();
            tasks.forEach((key, task) -> futures.put(key, executor.submit(() -> task.supplier().get())));
            // Consume in declaration order so the error array is deterministic even
            // when the network calls themselves complete in a different order.
            futures.forEach((key, future) -> {
                try {
                    JsonNode value = future.get();
                    if (value != null) output.put(key, value);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    errors.add(tasks.get(key).label() + ": interrupted");
                } catch (Exception exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    errors.add(tasks.get(key).label() + ": " + message(cause));
                }
            });
        }
        return output;
    }

    private void add(Map<String, FetchTask> tasks, String key, String label,
                     ThrowingSupplier<? extends JsonNode> supplier) {
        if (supplier != null) tasks.put(key, new FetchTask(label, supplier));
    }

    private <T> T attempt(String label, ThrowingSupplier<T> supplier, T fallback,
                          List<String> errors) {
        try {
            return supplier.get();
        } catch (Exception exception) {
            errors.add(label + ": " + message(exception));
            return fallback;
        }
    }

    private static String message(Throwable throwable) {
        String value = throwable == null ? null : throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getSimpleName() : value;
    }

    private void quote(ArrayNode target, String source, String symbol, double value,
                       Long timestamp, String timestampKind) {
        ObjectNode row = target.addObject();
        row.put("source", source); row.put("symbol", symbol); putNumber(row, "value", value);
        if (timestamp == null) row.putNull("ts"); else row.put("ts", timestamp);
        row.put("ts_kind", timestampKind);
    }

    private static String venue(MarketFetchSupport.AssetConfig asset, String key) {
        return asset == null || asset.venues() == null ? null : asset.venues().get(key);
    }

    private static Double lastClose(ArrayNode rows) {
        return rows == null || rows.isEmpty() ? null : nullableNumber(rows.get(rows.size() - 1).get("close"));
    }

    private static JsonNode highest(ArrayNode rows) {
        JsonNode output = null;
        if (rows != null) for (JsonNode row : rows) {
            if (!row.hasNonNull("high")) continue;
            if (output == null || number(row.get("high")) > number(output.get("high"))) output = row;
        }
        return output;
    }

    private ArrayNode attachClose(ArrayNode rows, Map<Long, Double> prices) {
        ArrayNode output = json.createArrayNode();
        for (JsonNode source : rows) {
            ObjectNode row = source.deepCopy();
            Double close = prices.get(source.path("time").asLong());
            putNullable(row, "close", close); output.add(row);
        }
        return output;
    }

    private static List<Double> closes(ArrayNode rows) {
        List<Double> output = new ArrayList<>();
        if (rows != null) for (JsonNode row : rows) {
            Double close = nullableNumber(row.get("close"));
            if (close != null) output.add(close);
        }
        return output;
    }

    private static ArrayNode array(JsonNode value) {
        return value != null && value.isArray() ? (ArrayNode) value : null;
    }

    private ArrayNode arrayOrEmpty(JsonNode value) {
        return value != null && value.isArray() ? (ArrayNode) value : json.createArrayNode();
    }

    private ArrayNode emptyIfNull(ArrayNode value) {
        return value == null ? json.createArrayNode() : value;
    }

    private static ObjectNode object(JsonNode value) {
        return value != null && value.isObject() ? (ObjectNode) value : null;
    }

    private static boolean nonEmpty(ArrayNode value) {
        return value != null && !value.isEmpty();
    }

    private static JsonNode at(JsonNode value, String... path) {
        JsonNode current = value;
        for (String key : path) {
            if (current == null || current.isNull() || !current.isContainerNode()) return null;
            current = current.get(key);
        }
        return current;
    }

    private static JsonNode first(JsonNode... values) {
        for (JsonNode value : values) if (value != null && !value.isNull()) return value;
        return null;
    }

    private static List<String> texts(JsonNode value) {
        List<String> output = new ArrayList<>();
        if (value != null && value.isArray()) value.forEach(item -> output.add(item.asText()));
        return output;
    }

    private static String fallbackName(List<String> values, String fallback) {
        return values == null || values.isEmpty() ? String.valueOf(fallback) : String.join(", ", values);
    }

    private static String textOr(JsonNode value, String fallback) {
        return value == null || value.isNull() || value.asText().isEmpty() ? fallback : value.asText();
    }

    private static Double nullableNumber(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        double number = ComputeMath.jsNumber(value);
        return Double.isFinite(number) ? number : null;
    }

    private static Long nullableLong(JsonNode value) {
        Double number = nullableNumber(value);
        return number == null ? null : number.longValue();
    }

    private static double number(JsonNode value) {
        return ComputeMath.jsNumber(value);
    }

    private static void putNumber(ObjectNode target, String key, double value) {
        target.set(key, ComputeMath.normalizedNumberNode(value));
    }

    private static void putNullable(ObjectNode target, String key, Double value) {
        if (value == null) target.putNull(key); else putNumber(target, key, value);
    }

    private static void copy(ObjectNode target, String key, JsonNode value) {
        if (value != null) target.set(key, value.deepCopy());
    }

    private static String numberText(double value) {
        if (value == Math.rint(value)) return Long.toString((long) value);
        return Double.toString(value);
    }

    private record MacroSeries(String key, String symbol, String label, String range) { }

    private record FetchTask(String label, ThrowingSupplier<? extends JsonNode> supplier) { }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}

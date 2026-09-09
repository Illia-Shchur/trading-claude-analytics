package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.compute.ComputeMath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds disclosed technical, positioning, flow, and derivative context blocks. */
final class AssetContextAssembler {
    private final MarketDataEndpoints endpoints;
    private final ObjectMapper json;
    private final boolean coinglassConfigured;

    AssetContextAssembler(MarketDataEndpoints endpoints, ObjectMapper json, boolean coinglassConfigured) {
        this.endpoints = endpoints;
        this.json = json;
        this.coinglassConfigured = coinglassConfigured;
    }

    void append(ObjectNode output, MarketFetchSupport.AssetConfig asset,
                ArrayNode daily, ArrayNode weekly, JsonNode fng, ArrayNode funding,
                Map<String, JsonNode> fetched, long now, List<String> errors) {
        ObjectNode context = json.createObjectNode();
        List<Double> dailyCloses = closes(daily);
        if (daily != null) {
            appendDailyContext(context, output, asset, daily, dailyCloses);
        }
        if (weekly != null) {
            appendWeeklyContext(context, weekly, now);
        }
        appendFundingContext(context, output, funding);
        appendPositioningContext(context, output, asset, fetched);
        if (asset.perpetualSymbol() != null) {
            context.set("market_flow", buildMarketFlowContext(asset, fetched, output));
        }
        if (asset.bitfinexFundingSymbol() != null) {
            ObjectNode borrow = json.createObjectNode();
            borrow.put("source", "Bitfinex margin funding (" + asset.bitfinexFundingSymbol() + ")");
            borrow.setAll(ComputeMath.borrowBlock(emptyIfNull(array(fetched.get("borrow")))));
            context.set("borrow", borrow);
        }
        appendSentimentContext(context, asset, fng, errors);
        context.set("proximity", SnapshotPanels.proximityPanel(output));
        appendDeribitContext(context, output, asset, fetched, now);
        if (!context.isEmpty()) {
            ObjectNode wrapper = json.createObjectNode();
            wrapper.put("note", "disclosed context only except open_interest_90d, which may populate the pre-existing FR squeeze condition; promoting any other field into the rubric is a framework-calibration job");
            wrapper.setAll(context);
            output.set("context", wrapper);
        }
    }

    void appendGapCoverage(ObjectNode output) {
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

    private void appendDailyContext(ObjectNode context, ObjectNode output, MarketFetchSupport.AssetConfig asset,
                                    ArrayNode daily, List<Double> dailyCloses) {
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
        appendVolumeContext(context, daily);
    }

    private void appendVolumeContext(ObjectNode context, ArrayNode daily) {
        if (daily.isEmpty() || !daily.get(daily.size() - 1).hasNonNull("volume")) {
            return;
        }
        double latest = number(daily.get(daily.size() - 1).get("volume"));
        List<Double> volumes = new ArrayList<>();
        for (int index = 0; index < daily.size() - 1; index++) {
            if (daily.get(index).hasNonNull("volume")) {
                volumes.add(number(daily.get(index).get("volume")));
            }
        }
        ObjectNode volume = context.putObject("volume");
        putNumber(volume, "last", latest);
        putNullable(volume, "percentile_vs_2y", volumes.isEmpty() ? null : ComputeMath.percentileRank(volumes, latest));
        volume.put("units_note", "Yahoo-reported units are asset-class-specific (crypto pairs: USD quote volume; futures like GC=F: contract count) — not converted, not comparable across assets");
    }

    private void appendWeeklyContext(ObjectNode context, ArrayNode weekly, long now) {
        ArrayNode completed = MarketFetchSupport.completedCandles(weekly, 7L * 86_400_000L, now);
        List<Double> weeklyCloses = closes(completed);
        if (weeklyCloses.size() < 15) {
            return;
        }
        List<Double> history = MarketSeriesAnalytics.rollingWilderRsi(weeklyCloses, 14);
        Double current = nullableNumber(ComputeMath.wilderRsi(weeklyCloses, 14).get("rsi"));
        putNullable(context, "weekly_rsi14_percentile", history.isEmpty() || current == null
                ? null : ComputeMath.percentileRank(history, current));
    }

    private void appendFundingContext(ObjectNode context, ObjectNode output, ArrayNode funding) {
        if (funding == null || funding.isEmpty() || !output.has("funding")) {
            return;
        }
        List<Double> values = MarketFetchSupport.dailyAnnualizedFundingSeries(funding);
        Double current = nullableNumber(output.path("funding").get("mean_annualized_pct"));
        putNullable(context, "funding_annualized_percentile_vs_history", values.isEmpty() || current == null
                ? null : ComputeMath.percentileRank(values, current));
        context.put("funding_history_days_available", values.size());
    }

    private void appendPositioningContext(ObjectNode context, ObjectNode output,
                                          MarketFetchSupport.AssetConfig asset,
                                          Map<String, JsonNode> fetched) {
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
        if (asset.perpetualSymbol() == null || (!nonEmpty(longShort) && !nonEmpty(taker) && !nonEmpty(oi))) {
            return;
        }
        ObjectNode positioning = json.createObjectNode();
        positioning.put("source", "Binance fapi globalLongShortAccountRatio + takerlongshortRatio + openInterestHist ("
                + asset.perpetualSymbol() + ")");
        positioning.setAll(ComputeMath.positioningBlock(emptyIfNull(longShort), emptyIfNull(taker), emptyIfNull(oi)));
        ObjectNode archived = MarketContextAnalytics.oi90dBlock(emptyIfNull(array(fetched.get("oi90"))));
        ObjectNode archivedWithSource = json.createObjectNode();
        archivedWithSource.put("source", "Binance Data Vision USD-M daily metrics archives (" + asset.perpetualSymbol() + ")");
        archivedWithSource.setAll(archived);
        positioning.set("open_interest_90d", archivedWithSource);
        if (positioning.path("open_interest").isObject() && archived.path("available").asBoolean()) {
            ((ObjectNode) positioning.path("open_interest")).put("oi_90d_high_available", true);
            ((ObjectNode) positioning.path("open_interest")).set("oi_within_5pct_of_90d_high",
                    archived.get("within_5pct_of_90d_high").deepCopy());
        }
        context.set("positioning", positioning);
    }

    private void appendSentimentContext(ObjectNode context, MarketFetchSupport.AssetConfig asset,
                                        JsonNode fng, List<String> errors) {
        if (fng != null && fng.path("data").isArray()) {
            List<Double> values = new ArrayList<>();
            fng.path("data").forEach(row -> values.add(number(row.get("value"))));
            putNullable(context, "fng_percentile_vs_2y", values.size() > 1
                    ? ComputeMath.percentileRank(values.subList(1, values.size()), values.get(0)) : null);
            context.put("fng_history_days_available", values.size());
        }
        if (asset.sentimentProxy() != null) {
            buildSentimentProxy(context, asset, errors);
        }
    }

    private void appendDeribitContext(ObjectNode context, ObjectNode output,
                                      MarketFetchSupport.AssetConfig asset, Map<String, JsonNode> fetched, long now) {
        if (asset.deribitCurrency() == null) {
            return;
        }
        ObjectNode deribit = json.createObjectNode();
        deribit.put("source", "Deribit get_volatility_index_data + get_book_summary_by_currency ("
                + asset.deribitCurrency() + ")");
        deribit.setAll(ComputeMath.deribitVolBlock(emptyIfNull(array(fetched.get("optionBook"))),
                emptyIfNull(array(fetched.get("dvol"))), nullableNumber(at(context.get("realized_vol"), "rv30")), now));
        context.set("deribit", deribit);
    }

    private ObjectNode buildMarketFlowContext(MarketFetchSupport.AssetConfig asset,
                                              Map<String, JsonNode> fetched, ObjectNode output) {
        ArrayNode cgSpot = endpoints.coinglassFlowRows(emptyIfNull(array(fetched.get("cgSpotFlow"))), 4);
        ArrayNode cgFutures = endpoints.coinglassFlowRows(emptyIfNull(array(fetched.get("cgFuturesFlow"))), 4);
        ArrayNode binanceSpot = emptyIfNull(array(fetched.get("binanceSpotFlow")));
        ObjectNode fallback = object(fetched.get("binanceFlow"));
        Map<Long, Double> priceByTime = new LinkedHashMap<>();
        for (JsonNode row : binanceSpot) {
            priceByTime.put(row.path("time").asLong(), nullableNumber(row.get("close")));
        }
        ArrayNode spotRows = !cgSpot.isEmpty() ? attachClose(cgSpot, priceByTime)
                : fallback != null && !fallback.path("spotRows").isEmpty()
                ? (ArrayNode) fallback.path("spotRows").deepCopy() : binanceSpot;
        ArrayNode futuresRows = !cgFutures.isEmpty() ? attachClose(cgFutures, priceByTime)
                : fallback == null ? json.createArrayNode() : emptyIfNull(array(fallback.get("futuresRows")));
        ArrayNode cgOi = endpoints.coinglassCandleRows(emptyIfNull(array(fetched.get("cgOi"))), 4);
        ArrayNode fallbackOi = fallback == null ? json.createArrayNode() : emptyIfNull(array(fallback.get("openInterestRows")));
        ArrayNode cgFunding = endpoints.coinglassCandleRows(emptyIfNull(array(fetched.get("cgFunding"))), 4);
        ArrayNode fallbackFunding = fallback == null ? json.createArrayNode()
                : emptyIfNull(array(fallback.get("oiWeightedFundingRows")));
        ArrayNode fundingRows = !cgFunding.isEmpty() ? cgFunding : fallbackFunding;
        ObjectNode metadata = fallback == null ? null : object(fallback.get("metadata"));
        List<String> fallbackSpot = texts(at(metadata, "spot_symbols_included"));
        List<String> fallbackFlows = texts(first(at(metadata, "futures_flow_symbols_included"),
                at(metadata, "perpetual_symbols_included")));
        List<String> fallbackOis = texts(first(at(metadata, "oi_symbols_included"),
                at(metadata, "perpetual_symbols_included")));
        List<String> fallbackFundings = texts(first(at(metadata, "funding_symbols_included"),
                at(metadata, "perpetual_symbols_included")));
        ObjectNode fields = json.createObjectNode();
        fields.put("spot_cvd", !cgSpot.isEmpty() ? "Coinglass aggregated Binance+OKX+Bybit"
                : "Binance aggregate spot CVD (" + fallbackName(fallbackSpot, venue(asset, "binance"))
                + "; stable-USD quotes treated as nominal USD)");
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
        ObjectNode result = json.createObjectNode();
        result.set("source", fields);
        result.put("coinglass_api_configured", coinglassConfigured);
        result.set("binance_aggregate", metadata == null ? NullNode.instance : metadata.deepCopy());
        result.put("coinglass_setup_note", coinglassConfigured
                ? "COINGLASS_API_KEY configured; any unavailable field fell back independently and is named above"
                : "Set COINGLASS_API_KEY to enable cross-exchange data. The keyless fallback aggregates active Binance stable-USD spot pairs and USD-M perpetuals, but remains single-venue; stablecoin quotes are nominal USD and 4h OI highs/lows are sampled from 30m observations.");
        result.setAll(block);
        return result;
    }

    private void buildSentimentProxy(ObjectNode context, MarketFetchSupport.AssetConfig asset,
                                     List<String> errors) {
        MarketFetchSupport.SentimentProxy proxy = asset.sentimentProxy();
        Map<String, MarketFetchExecutor.Task> tasks = new LinkedHashMap<>();
        add(tasks, "vol", "yahoo " + proxy.volatilitySymbol() + " (sentiment proxy)",
                () -> endpoints.yahooChart(proxy.volatilitySymbol(), "5y", "1d"));
        add(tasks, "cef", "yahoo " + proxy.closedEndFundSymbol() + " (sentiment proxy)",
                () -> endpoints.yahooChart(proxy.closedEndFundSymbol(), "5y", "1d"));
        add(tasks, "ref", "yahoo " + proxy.referenceSymbol() + " (sentiment proxy ref)",
                () -> endpoints.yahooChart(proxy.referenceSymbol(), "5y", "1d"));
        Map<String, JsonNode> values = MarketFetchExecutor.run(tasks, errors);
        ArrayNode vol = array(values.get("vol"));
        ArrayNode cef = array(values.get("cef"));
        ArrayNode ref = array(values.get("ref"));
        List<Double> cefCloses = null;
        List<Double> refCloses = null;
        if (cef != null && ref != null) {
            Map<String, Double> refByDate = new LinkedHashMap<>();
            for (JsonNode row : ref) {
                refByDate.put(row.path("date").asText(), number(row.get("close")));
            }
            cefCloses = new ArrayList<>();
            refCloses = new ArrayList<>();
            for (JsonNode row : cef) {
                if (refByDate.containsKey(row.path("date").asText())) {
                    cefCloses.add(number(row.get("close")));
                    refCloses.add(refByDate.get(row.path("date").asText()));
                }
            }
        }
        ObjectNode block = MarketContextAnalytics.sentimentProxyBlock(
                vol == null ? null : closes(vol), cefCloses, refCloses, 250, 504);
        if (block.has("vol_index") || block.has("cef_premium")) {
            ObjectNode result = json.createObjectNode();
            result.put("source", "Yahoo " + String.join(" + ", List.of(proxy.volatilitySymbol(),
                    proxy.closedEndFundSymbol(), proxy.referenceSymbol())));
            result.setAll(block);
            context.set("sentiment_proxy", result);
        }
    }

    private void add(Map<String, MarketFetchExecutor.Task> tasks, String key, String label,
                     MarketFetchExecutor.ThrowingSupplier<? extends JsonNode> supplier) {
        MarketFetchExecutor.add(tasks, key, label, supplier);
    }

    private static String venue(MarketFetchSupport.AssetConfig asset, String key) {
        return asset == null || asset.venues() == null ? null : asset.venues().get(key);
    }

    private ArrayNode attachClose(ArrayNode rows, Map<Long, Double> prices) {
        ArrayNode output = json.createArrayNode();
        for (JsonNode source : rows) {
            ObjectNode row = source.deepCopy();
            Double close = prices.get(source.path("time").asLong());
            putNullable(row, "close", close);
            output.add(row);
        }
        return output;
    }

    private static List<Double> closes(ArrayNode rows) {
        List<Double> output = new ArrayList<>();
        if (rows != null) {
            for (JsonNode row : rows) {
                Double close = nullableNumber(row.get("close"));
                if (close != null) {
                    output.add(close);
                }
            }
        }
        return output;
    }

    private static ArrayNode array(JsonNode value) {
        return value != null && value.isArray() ? (ArrayNode) value : null;
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
            if (current == null || current.isNull() || !current.isContainerNode()) {
                return null;
            }
            current = current.get(key);
        }
        return current;
    }

    private static JsonNode first(JsonNode... values) {
        for (JsonNode value : values) {
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private static List<String> texts(JsonNode value) {
        List<String> output = new ArrayList<>();
        if (value != null && value.isArray()) {
            value.forEach(item -> output.add(item.asText()));
        }
        return output;
    }

    private static String fallbackName(List<String> values, String fallback) {
        return values == null || values.isEmpty() ? String.valueOf(fallback) : String.join(", ", values);
    }

    private static String textOr(JsonNode value, String fallback) {
        return value == null || value.isNull() || value.asText().isEmpty() ? fallback : value.asText();
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

    private static void putNullable(ObjectNode target, String key, Double value) {
        if (value == null) {
            target.putNull(key);
        } else {
            putNumber(target, key, value);
        }
    }

    private static void copy(ObjectNode target, String key, JsonNode value) {
        if (value != null) {
            target.set(key, value.deepCopy());
        }
    }
}

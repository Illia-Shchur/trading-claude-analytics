package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Builds the ordered endpoint work list for one configured asset. */
final class AssetFetchPlan {
    private AssetFetchPlan() {
    }

    static Map<String, MarketFetchExecutor.Task> create(
            MarketDataEndpoints endpoints,
            String assetKey,
            MarketFetchSupport.AssetConfig asset,
            boolean coinglassConfigured) {
        Map<String, MarketFetchExecutor.Task> tasks = new LinkedHashMap<>();
        add(tasks, "cgSpot", "coingecko spot", asset.coinGeckoId() == null ? null : () ->
                endpoints.rawJson("https://api.coingecko.com/api/v3/simple/price?ids=" + asset.coinGeckoId()
                        + "&vs_currencies=usd&include_last_updated_at=true"));
        add(tasks, "cgCoin", "coingecko coin/ath", asset.coinGeckoId() == null ? null : () ->
                endpoints.rawJson("https://api.coingecko.com/api/v3/coins/" + asset.coinGeckoId()
                        + "?localization=false&tickers=false&market_data=true&community_data=false&developer_data=false"));
        add(tasks, "weekly", "yahoo weekly", () -> endpoints.yahooChart(asset.yahooSymbol(), "5y", "1wk"));
        add(tasks, "daily", "yahoo daily", () -> endpoints.yahooChart(asset.yahooSymbol(), "2y", "1d"));
        add(tasks, "cross", "yahoo cross-spot", asset.crossYahooSymbol() == null
                ? null : () -> endpoints.yahooChart(asset.crossYahooSymbol(), "5d", "1d"));
        add(tasks, "fng", "alternative.me fng", !asset.fearAndGreed() ? null
                : () -> endpoints.rawJson("https://api.alternative.me/fng/?limit=730"));
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
        add(tasks, "binanceFlow", "Binance aggregate market-flow fallback", asset.perpetualSymbol() == null
                ? null : () -> endpoints.binanceAggregateMarketFlow(assetKey, venue(asset, "binance"),
                        asset.perpetualSymbol(), 43));
        if (asset.perpetualSymbol() != null && coinglassConfigured) {
            addCoinglassTasks(tasks, assetKey, endpoints);
        }
        return tasks;
    }

    private static void addCoinglassTasks(Map<String, MarketFetchExecutor.Task> tasks, String assetKey,
                                          MarketDataEndpoints endpoints) {
        String symbol = assetKey.toUpperCase(Locale.ROOT);
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

    private static void add(Map<String, MarketFetchExecutor.Task> tasks, String key, String label,
                            MarketFetchExecutor.ThrowingSupplier<? extends JsonNode> supplier) {
        MarketFetchExecutor.add(tasks, key, label, supplier);
    }

    private static String venue(MarketFetchSupport.AssetConfig asset, String key) {
        return asset == null || asset.venues() == null ? null : asset.venues().get(key);
    }
}

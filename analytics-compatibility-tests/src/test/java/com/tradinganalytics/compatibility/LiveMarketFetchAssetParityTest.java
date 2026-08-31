package com.tradinganalytics.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.marketdata.PublicDataAdapters;
import com.tradinganalytics.marketdata.LiveMarketFetchService;
import com.tradinganalytics.marketdata.MarketDataEndpoints;
import com.tradinganalytics.marketdata.http.MarketHttpClient;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class LiveMarketFetchAssetParityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long NOW = Instant.parse("2026-08-28T12:34:56.789Z").toEpochMilli();
    @Test
    void completeBtcFixtureMatchesNodeExactly() throws Exception {
        assertParity(fixture(false));
    }

    @Test
    void coinglassConfiguredBtcFixtureMatchesNodeExactly() throws Exception {
        assertParity(fixture(true));
    }

    private static void assertParity(ObjectNode fixture) throws Exception {
        String name = fixture.path("coinglass").asBoolean()
                ? "live-fetch-btc-coinglass-v1.json" : "live-fetch-btc-public-v1.json";
        JsonNode expected;
        try (InputStream stream = LiveMarketFetchAssetParityTest.class
                .getResourceAsStream("/oracles/" + name)) {
            assertThat(stream).as(name).isNotNull(); expected = JSON.readTree(stream);
        }

        PublicDataAdapters.InjectableHttpClient getter = (uri, headers) -> route(uri.toString(), fixture);
        MarketHttpClient http = new MarketHttpClient(getter, null, millis -> { }, JSON);
        String key = fixture.path("coinglass").asBoolean() ? "fixture-key" : null;
        ObjectNode actual = new LiveMarketFetchService(
                new MarketDataEndpoints(http, JSON, () -> NOW, key), JSON, () -> NOW, key != null)
                .fetchAsset("btc", true);
        actual.remove("fetched_at");

        assertThat(firstDifference(expected, actual, "$"))
                .as("Node/Java output must match field-for-field and in insertion order")
                .isNull();
    }

    private static PublicDataAdapters.FetchResponse route(String u, ObjectNode input) throws Exception {
        if (u.contains("/api/spot/aggregated-taker-buy-sell-volume/history")) return jsonResponse(input.get("cg_spot_flow"));
        if (u.contains("/api/futures/aggregated-taker-buy-sell-volume/history")) return jsonResponse(input.get("cg_futures_flow"));
        if (u.contains("/api/futures/open-interest/aggregated-history")) return jsonResponse(input.get("cg_oi"));
        if (u.contains("/api/futures/funding-rate/oi-weight-history")) return jsonResponse(input.get("cg_funding"));
        if (u.contains("data.binance.vision")) return response(200, Base64.getDecoder().decode(input.path("metrics_zip").asText()));
        if (u.contains("/simple/price")) return jsonResponse(input.get("cg_spot"));
        if (u.contains("/api/v3/coins/bitcoin")) return jsonResponse(input.get("cg_coin"));
        if (u.contains("/finance/chart/")) return jsonResponse(input.get("yahoo"));
        if (u.contains("alternative.me")) return jsonResponse(input.get("fng"));
        if (u.contains("/ticker/24hr")) return jsonResponse(input.get("binance_quote"));
        if (u.contains("/products/BTC-USD/ticker")) return jsonResponse(input.get("coinbase_quote"));
        if (u.contains("/products/BTC-USD/candles")) return jsonResponse(input.get("coinbase_daily"));
        if (u.contains("/products/USDT-USD/candles")) return jsonResponse(input.get("usdt_daily"));
        if (u.contains("kraken.com")) return jsonResponse(input.get("kraken_quote"));
        if (u.contains("/fundingRate")) return jsonResponse(input.get("funding"));
        if (u.contains("get_volatility_index_data")) return jsonResponse(input.get("dvol"));
        if (u.contains("get_book_summary_by_currency")) return jsonResponse(input.get("option_book"));
        if (u.contains("/premiumIndex")) return jsonResponse(input.get("premium"));
        if (u.contains("globalLongShortAccountRatio")) return jsonResponse(input.get("long_short"));
        if (u.contains("takerlongshortRatio")) return jsonResponse(input.get("taker"));
        if (u.contains("openInterestHist")) return jsonResponse(input.get("open_interest"));
        if (u.contains("api-pub.bitfinex.com")) return jsonResponse(input.get("borrow"));
        if (u.contains("community-api.coinmetrics.io")) return jsonResponse(input.get("onchain"));
        if (u.contains("api.binance.com/api/v3/exchangeInfo")) return jsonResponse(input.get("spot_info"));
        if (u.contains("fapi.binance.com/fapi/v1/exchangeInfo")) return jsonResponse(input.get("futures_info"));
        if (u.contains("api.binance.com/api/v3/klines") && u.contains("interval=1d")) return jsonResponse(input.get("binance_daily"));
        if (u.contains("/klines")) return jsonResponse(input.get("flow_klines"));
        return response(404, ("missing " + u).getBytes(StandardCharsets.UTF_8));
    }

    private static ObjectNode fixture(boolean coinglass) throws Exception {
        ObjectNode f = JSON.createObjectNode(); f.put("now", NOW); f.put("coinglass", coinglass);
        f.set("yahoo", yahoo(240));
        f.set("cg_spot", JSON.readTree("{\"bitcoin\":{\"usd\":100,\"last_updated_at\":" + NOW / 1_000L + "}}"));
        f.set("cg_coin", JSON.readTree("{\"market_data\":{\"ath\":{\"usd\":200},\"ath_date\":{\"usd\":\"2025-01-01T00:00:00Z\"},\"ath_change_percentage\":{\"usd\":-50}}}"));
        f.set("fng", fearAndGreed());
        f.set("binance_quote", JSON.readTree("{\"lastPrice\":\"101\",\"closeTime\":" + (NOW - 1_000) + "}"));
        f.set("coinbase_quote", JSON.readTree("{\"price\":\"102\",\"time\":\"2026-08-28T12:34:55.000Z\"}"));
        f.set("kraken_quote", JSON.readTree("{\"error\":[],\"result\":{\"XXBTZUSD\":{\"c\":[\"103\",\"1\"]}}}"));
        f.set("funding", funding());
        f.set("dvol", JSON.readTree("{\"result\":{\"data\":[[" + (NOW - 43_200_000L) + ",0,0,0,55],[" + (NOW - 1_000) + ",0,0,0,56]]}}"));
        f.set("option_book", JSON.readTree("{\"result\":[]}"));
        f.set("premium", JSON.readTree("{\"markPrice\":\"101\",\"indexPrice\":\"100\"}"));
        f.set("long_short", ratioRows("longShortRatio", 1.1));
        f.set("taker", ratioRows("buySellRatio", 0.9));
        f.set("open_interest", openInterestRows());
        f.set("borrow", JSON.readTree("[0.0001,0.00009,2,1000,0.00011,2,2000]"));
        f.set("onchain", onchain());
        f.set("coinbase_daily", coinbaseDaily(100.0));
        f.set("usdt_daily", coinbaseDaily(1.0));
        f.set("binance_daily", binanceDaily(101.0));
        f.set("spot_info", JSON.readTree("{\"symbols\":[{\"symbol\":\"BTCUSDT\",\"status\":\"TRADING\",\"isSpotTradingAllowed\":true,\"baseAsset\":\"BTC\",\"quoteAsset\":\"USDT\"}]}"));
        f.set("futures_info", JSON.readTree("{\"symbols\":[{\"symbol\":\"BTCUSDT\",\"status\":\"TRADING\",\"contractType\":\"PERPETUAL\",\"baseAsset\":\"BTC\",\"quoteAsset\":\"USDT\"}]}"));
        f.set("flow_klines", flowKlines());
        f.put("metrics_zip", Base64.getEncoder().encodeToString(metricsZip()));
        if (coinglass) addCoinglass(f);
        return f;
    }

    private static void addCoinglass(ObjectNode fixture) {
        ArrayNode spot = JSON.createArrayNode(), futures = JSON.createArrayNode();
        ArrayNode oi = JSON.createArrayNode(), funding = JSON.createArrayNode();
        for (int index = 0; index < 43; index++) {
            long time = NOW - (43L - index) * 14_400_000L;
            spot.addObject().put("time", time).put("aggregated_buy_volume_usd", 700 + index)
                    .put("aggregated_sell_volume_usd", 500 + index);
            futures.addObject().put("time", time).put("agg_taker_buy_vol", 800 + index)
                    .put("agg_taker_sell_vol", 600 + index);
            oi.addObject().put("time", time).put("open", 1_000 + index).put("high", 1_010 + index)
                    .put("low", 990 + index).put("close", 1_005 + index);
            funding.addObject().put("time", time).put("open", 0.00001 + index * 1e-7)
                    .put("high", 0.00002 + index * 1e-7).put("low", 0.0)
                    .put("close", 0.000015 + index * 1e-7);
        }
        fixture.set("cg_spot_flow", coinglassResponse(spot));
        fixture.set("cg_futures_flow", coinglassResponse(futures));
        fixture.set("cg_oi", coinglassResponse(oi));
        fixture.set("cg_funding", coinglassResponse(funding));
    }

    private static ObjectNode coinglassResponse(ArrayNode rows) {
        ObjectNode response = JSON.createObjectNode(); response.put("code", "0"); response.set("data", rows);
        return response;
    }

    private static ObjectNode yahoo(int count) {
        ObjectNode root = JSON.createObjectNode(); ObjectNode chart = root.putObject("chart");
        ObjectNode result = chart.putArray("result").addObject();
        ArrayNode ts = result.putArray("timestamp"); ObjectNode quote = result.putObject("indicators").putArray("quote").addObject();
        ArrayNode open = quote.putArray("open"), high = quote.putArray("high"), low = quote.putArray("low");
        ArrayNode close = quote.putArray("close"), volume = quote.putArray("volume");
        long first = Instant.parse("2026-01-01T00:00:00Z").getEpochSecond();
        for (int i = 0; i < count; i++) {
            ts.add(first + i * 86_400L); open.add(100 + i); high.add(101 + i);
            low.add(99 + i); close.add(100 + i); volume.add(1_000 + i);
        }
        return root;
    }

    private static ObjectNode fearAndGreed() {
        ObjectNode root = JSON.createObjectNode(); ArrayNode data = root.putArray("data");
        for (int i = 0; i < 40; i++) data.addObject().put("value", Integer.toString(10 + i % 20))
                .put("value_classification", "Fear").put("timestamp", NOW / 1_000L - i * 86_400L);
        return root;
    }

    private static ArrayNode funding() {
        ArrayNode rows = JSON.createArrayNode();
        for (int i = 0; i < 60; i++) rows.addObject().put("fundingRate", i % 2 == 0 ? "0.0001" : "-0.00005")
                .put("fundingTime", NOW - (60L - i) * 28_800_000L);
        return rows;
    }

    private static ArrayNode ratioRows(String field, double base) {
        ArrayNode rows = JSON.createArrayNode();
        for (int i = 0; i < 30; i++) rows.addObject().put(field, Double.toString(base + i * 0.001));
        return rows;
    }

    private static ArrayNode openInterestRows() {
        ArrayNode rows = JSON.createArrayNode();
        for (int i = 0; i < 50; i++) rows.addObject().put("timestamp", NOW - (50L - i) * 1_800_000L)
                .put("sumOpenInterest", Double.toString(1_000 + i))
                .put("sumOpenInterestValue", Double.toString(100_000 + i * 1_000));
        return rows;
    }

    private static ObjectNode onchain() {
        ObjectNode root = JSON.createObjectNode(); ArrayNode data = root.putArray("data");
        for (int i = 0; i < 40; i++) data.addObject().put("time", "2026-07-" + String.format("%02d", i + 1))
                .put("CapMVRVCur", Double.toString(1.5 + i * 0.01)).put("CapMrktCurUSD", Double.toString(1_000_000 + i * 10_000))
                .put("FlowInExUSD", "1000").put("FlowOutExUSD", "900")
                .put("SplyExNtv", Double.toString(100 + i)).put("SplyCur", "1000");
        return root;
    }

    private static ArrayNode coinbaseDaily(double base) {
        ArrayNode rows = JSON.createArrayNode(); long first = Instant.parse("2026-07-19T00:00:00Z").getEpochSecond();
        for (int i = 0; i < 40; i++) {
            ArrayNode row = rows.addArray(); row.add(first + i * 86_400L).add(base - 1).add(base + 1).add(base).add(base).add(10);
        }
        return rows;
    }

    private static ArrayNode binanceDaily(double base) {
        ArrayNode rows = JSON.createArrayNode(); long first = Instant.parse("2026-07-19T00:00:00Z").toEpochMilli();
        for (int i = 0; i < 40; i++) rows.add(kline(first + i * 86_400_000L, base, 86_400_000L));
        return rows;
    }

    private static ArrayNode flowKlines() {
        ArrayNode rows = JSON.createArrayNode();
        for (int i = 0; i < 43; i++) rows.add(kline(NOW - (43L - i) * 14_400_000L, 100 + i * 0.1, 14_400_000L));
        return rows;
    }

    private static ArrayNode kline(long openTime, double close, long width) {
        ArrayNode row = JSON.createArrayNode();
        row.add(openTime).add(close).add(close + 1).add(close - 1).add(close).add(10)
                .add(openTime + width - 1).add(1_000).add(1).add(5).add(600).add(0);
        return row;
    }

    private static byte[] metricsZip() throws Exception {
        byte[] csv = "sum_open_interest,sum_open_interest_value\n1000,100000\n"
                .getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("BTCUSDT-metrics.csv")); zip.write(csv); zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static PublicDataAdapters.FetchResponse jsonResponse(JsonNode value) throws Exception {
        return response(200, JSON.writeValueAsBytes(value));
    }

    private static PublicDataAdapters.FetchResponse response(int status, byte[] body) {
        return new PublicDataAdapters.FetchResponse(status, body, Map.of());
    }

    private static String firstDifference(JsonNode expected, JsonNode actual, String path) {
        if (expected == null || actual == null) return expected == actual ? null
                : path + " null mismatch: expected=" + expected + " actual=" + actual;
        if (expected.isObject() && actual.isObject()) {
            List<String> expectedFields = new java.util.ArrayList<>(), actualFields = new java.util.ArrayList<>();
            expected.fieldNames().forEachRemaining(expectedFields::add);
            actual.fieldNames().forEachRemaining(actualFields::add);
            if (!expectedFields.equals(actualFields)) return path + " field order/set mismatch: expected="
                    + expectedFields + " actual=" + actualFields;
            for (String field : expectedFields) {
                String difference = firstDifference(expected.get(field), actual.get(field), path + "." + field);
                if (difference != null) return difference;
            }
            return null;
        }
        if (expected.isArray() && actual.isArray()) {
            if (expected.size() != actual.size()) return path + " size mismatch: expected="
                    + expected.size() + " actual=" + actual.size();
            for (int index = 0; index < expected.size(); index++) {
                String difference = firstDifference(expected.get(index), actual.get(index), path + "[" + index + "]");
                if (difference != null) return difference;
            }
            return null;
        }
        String expectedScalar = com.tradinganalytics.contracts.json.CanonicalJson.canonicalize(expected);
        String actualScalar = com.tradinganalytics.contracts.json.CanonicalJson.canonicalize(actual);
        return expectedScalar.equals(actualScalar) ? null : path + " mismatch: expected="
                + expectedScalar + " actual=" + actualScalar;
    }
}

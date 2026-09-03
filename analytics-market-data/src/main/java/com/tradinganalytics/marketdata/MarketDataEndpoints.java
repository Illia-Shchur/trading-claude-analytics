package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.marketdata.PublicDataAdapters;
import com.tradinganalytics.marketdata.http.MarketHttpClient;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/** Verified live endpoint adapters used by the Java {@code fetch} command. */
public final class MarketDataEndpoints {
    private final MarketHttpClient http;
    private final ObjectMapper json;
    private final java.util.function.LongSupplier clock;
    private final String coinglassApiKey;
    private final BinanceAggregateFlowBuilder binanceAggregateFlow;
    private final MacroDataEndpoints macroEndpoints;

    public MarketDataEndpoints(MarketHttpClient http, ObjectMapper json,
                               java.util.function.LongSupplier clock, String coinglassApiKey) {
        this.http = http == null ? MarketHttpClient.production() : http;
        this.json = json == null ? new ObjectMapper() : json;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.coinglassApiKey = coinglassApiKey;
        this.binanceAggregateFlow = new BinanceAggregateFlowBuilder(this, this.json, this.clock);
        this.macroEndpoints = new MacroDataEndpoints(this.http, this.json, this.clock);
    }

    public JsonNode rawJson(String url) throws IOException {
        return http.getJson(uri(url));
    }

    public ObjectNode binanceQuote(String symbol) throws IOException {
        JsonNode value = http.getJson(uri("https://api.binance.com/api/v3/ticker/24hr?symbol=" + encode(symbol)));
        if (value.get("lastPrice") == null) throw new IllegalArgumentException("binance: no lastPrice for " + symbol);
        ObjectNode output = json.createObjectNode();
        output.put("source", "Binance"); output.put("symbol", symbol);
        output.put("value", number(value.get("lastPrice"))); output.put("ts", (long) number(value.get("closeTime")));
        output.put("ts_kind", "venue");
        return output;
    }

    public ObjectNode coinbaseQuote(String product) throws IOException {
        JsonNode value = http.getJson(uri("https://api.exchange.coinbase.com/products/" + encode(product) + "/ticker"));
        if (value.get("price") == null) throw new IllegalArgumentException("coinbase: no price for " + product);
        ObjectNode output = json.createObjectNode();
        output.put("source", "Coinbase"); output.put("symbol", product);
        output.put("value", number(value.get("price")));
        output.put("ts", Instant.parse(value.path("time").asText()).toEpochMilli());
        output.put("ts_kind", "venue");
        return output;
    }

    public ObjectNode krakenQuote(String pair) throws IOException {
        JsonNode value = http.getJson(uri("https://api.kraken.com/0/public/Ticker?pair=" + encode(pair)));
        if (value.path("error").isArray() && !value.path("error").isEmpty()) {
            List<String> errors = new ArrayList<>(); value.path("error").forEach(row -> errors.add(row.asText()));
            throw new IllegalArgumentException("kraken: " + String.join(", ", errors));
        }
        JsonNode result = value.get("result");
        JsonNode ticker = null;
        if (result != null && result.isObject()) {
            Iterator<JsonNode> values = result.elements();
            if (values.hasNext()) ticker = values.next();
        }
        if (ticker == null || !ticker.path("c").isArray()) throw new IllegalArgumentException("kraken: no ticker for " + pair);
        ObjectNode output = json.createObjectNode();
        output.put("source", "Kraken"); output.put("symbol", pair);
        output.put("value", number(ticker.path("c").get(0))); output.putNull("ts");
        output.put("ts_kind", "receipt");
        return output;
    }

    public ArrayNode binanceLongShortRatio(String symbol, int limit) throws IOException {
        return arrayOrEmpty(http.getJson(uri("https://fapi.binance.com/futures/data/globalLongShortAccountRatio?symbol="
                + encode(symbol) + "&period=1d&limit=" + limit)));
    }

    public ArrayNode binanceTakerRatio(String symbol, int limit) throws IOException {
        return arrayOrEmpty(http.getJson(uri("https://fapi.binance.com/futures/data/takerlongshortRatio?symbol="
                + encode(symbol) + "&period=1d&limit=" + limit)));
    }

    public ArrayNode binanceOpenInterestHistory(String symbol, int limit, String period) throws IOException {
        return arrayOrEmpty(http.getJson(uri("https://fapi.binance.com/futures/data/openInterestHist?symbol="
                + encode(symbol) + "&period=" + encode(period) + "&limit=" + limit)));
    }

    public ArrayNode binanceFundingHistory(String symbol, int limit) throws IOException {
        return arrayOrEmpty(http.getJson(uri("https://fapi.binance.com/fapi/v1/fundingRate?symbol="
                + encode(symbol) + "&limit=" + limit)));
    }

    public ArrayNode binanceFunding(String symbol, int limit) throws IOException {
        ArrayNode rows = binanceFundingHistory(symbol, limit);
        if (rows.isEmpty()) throw new IllegalArgumentException("binance fapi: no funding history for " + symbol);
        ArrayNode output = json.createArrayNode();
        for (JsonNode row : rows) {
            ObjectNode mapped = output.addObject();
            mapped.set("fundingRate", row.get("fundingRate").deepCopy());
            mapped.put("fundingTime", (long) number(row.get("fundingTime")));
        }
        return output;
    }

    public ArrayNode binanceFlowKlines(String symbol, boolean futures, String interval, int limit) throws IOException {
        String root = futures ? "https://fapi.binance.com/fapi/v1/klines" : "https://api.binance.com/api/v3/klines";
        ArrayNode rows = arrayOrEmpty(http.getJson(uri(root + "?symbol=" + encode(symbol)
                + "&interval=" + encode(interval) + "&limit=" + limit)));
        ArrayNode output = json.createArrayNode();
        long now = clock.getAsLong();
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() <= 10 || number(row.get(6)) >= now) continue;
            double quote = number(row.get(7));
            double buy = number(row.get(10));
            double sell = quote - buy;
            if (!Double.isFinite(buy) || !Double.isFinite(sell) || sell < 0.0) continue;
            ObjectNode mapped = output.addObject();
            mapped.put("time", (long) number(row.get(0)));
            putNumber(mapped, "buy_usd", buy); putNumber(mapped, "sell_usd", sell);
            putNumber(mapped, "close", number(row.get(4)));
        }
        return output;
    }

    public JsonNode binanceSpotExchangeInfo() throws IOException {
        return http.getJson(uri("https://api.binance.com/api/v3/exchangeInfo"));
    }

    public JsonNode binanceFuturesExchangeInfo() throws IOException {
        return http.getJson(uri("https://fapi.binance.com/fapi/v1/exchangeInfo"));
    }

    public ArrayNode coinglassJson(String path, Map<String, String> params) throws IOException {
        if (coinglassApiKey == null || coinglassApiKey.isBlank()) {
            throw new IllegalArgumentException("COINGLASS_API_KEY not configured");
        }
        StringBuilder query = new StringBuilder();
        params.forEach((key, value) -> query.append(query.isEmpty() ? "" : "&")
                .append(encode(key)).append('=').append(encode(value)));
        JsonNode response = http.getJson(uri("https://open-api-v4.coinglass.com" + path + "?" + query),
                2, Map.of("CG-API-KEY", coinglassApiKey));
        if (!"0".equals(response.path("code").asText()) || !response.path("data").isArray()) {
            String detail = response.hasNonNull("msg") ? response.path("msg").asText() : response.path("code").asText("malformed response");
            throw new IllegalArgumentException("Coinglass " + path + ": " + detail);
        }
        return (ArrayNode) response.path("data").deepCopy();
    }

    public ArrayNode completedCoinglassRows(ArrayNode rows, int intervalHours) {
        List<ObjectNode> completed = new ArrayList<>();
        long width = intervalHours * 3_600_000L;
        long now = clock.getAsLong();
        for (JsonNode source : rows == null ? List.<JsonNode>of() : rows) {
            double raw = number(source.get("time"));
            double time = raw < 1e12 ? raw * 1_000.0 : raw;
            if (!Double.isFinite(time) || time + width > now) continue;
            ObjectNode row = source.deepCopy(); row.put("time", (long) time); completed.add(row);
        }
        completed.sort(Comparator.comparingLong(row -> row.path("time").asLong()));
        ArrayNode output = json.createArrayNode(); completed.forEach(output::add); return output;
    }

    public ArrayNode coinglassFlowRows(ArrayNode rows, int intervalHours) {
        ArrayNode output = json.createArrayNode();
        for (JsonNode row : completedCoinglassRows(rows, intervalHours)) {
            ObjectNode mapped = output.addObject(); mapped.set("time", row.get("time").deepCopy());
            JsonNode buy = row.has("aggregated_buy_volume_usd") ? row.get("aggregated_buy_volume_usd") : row.get("agg_taker_buy_vol");
            JsonNode sell = row.has("aggregated_sell_volume_usd") ? row.get("aggregated_sell_volume_usd") : row.get("agg_taker_sell_vol");
            putNumber(mapped, "buy_usd", number(buy)); putNumber(mapped, "sell_usd", number(sell));
        }
        return output;
    }

    public ArrayNode coinglassCandleRows(ArrayNode rows, int intervalHours) {
        ArrayNode output = json.createArrayNode();
        for (JsonNode row : completedCoinglassRows(rows, intervalHours)) {
            ObjectNode mapped = output.addObject(); mapped.set("time", row.get("time").deepCopy());
            for (String key : List.of("open", "high", "low", "close")) putNumber(mapped, key, number(row.get(key)));
        }
        return output;
    }

    public ArrayNode yahooChart(String symbol, String range, String interval) throws IOException {
        JsonNode response = http.getJson(uri("https://query1.finance.yahoo.com/v8/finance/chart/"
                + encode(symbol) + "?range=" + encode(range) + "&interval=" + encode(interval)));
        return MarketFetchSupport.parseYahooChart(response, symbol);
    }

    public ArrayNode coinMetricsOnchain(String asset) throws IOException {
        String metrics = "CapMVRVCur,CapMrktCurUSD,FlowInExUSD,FlowOutExUSD,SplyExNtv,SplyCur";
        JsonNode response = http.getJson(uri("https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?assets="
                + encode(asset) + "&metrics=" + encode(metrics) + "&frequency=1d&start_time=2009-01-01&page_size=10000"));
        if (!response.path("data").isArray() || response.path("data").isEmpty()) {
            throw new IllegalArgumentException("Coin Metrics: empty on-chain series for " + asset);
        }
        return (ArrayNode) response.path("data").deepCopy();
    }

    public ArrayNode coinbaseDaily(String product, int days) throws IOException {
        String today = isoDayOffset(0);
        String endpoint = "https://api.exchange.coinbase.com/products/" + encode(product)
                + "/candles?granularity=86400&start=" + isoDayOffset(-days) + "T00%3A00%3A00Z&end=" + today + "T00%3A00%3A00Z";
        ArrayNode rows = arrayOrEmpty(http.getJson(uri(endpoint)));
        List<ObjectNode> mapped = new ArrayList<>();
        for (JsonNode row : rows) {
            String date = Instant.ofEpochSecond((long) number(row.get(0))).atZone(ZoneOffset.UTC).toLocalDate().toString();
            if (date.compareTo(today) >= 0) continue;
            ObjectNode value = json.createObjectNode(); value.put("date", date); putNumber(value, "close", number(row.get(4))); mapped.add(value);
        }
        mapped.sort(Comparator.comparing(value -> value.path("date").asText()));
        ArrayNode output = json.createArrayNode(); mapped.forEach(output::add); return output;
    }

    public ArrayNode binanceDaily(String symbol, int days) throws IOException {
        String today = isoDayOffset(0);
        ArrayNode rows = arrayOrEmpty(http.getJson(uri("https://api.binance.com/api/v3/klines?symbol="
                + encode(symbol) + "&interval=1d&limit=" + days)));
        List<ObjectNode> mapped = new ArrayList<>();
        for (JsonNode row : rows) {
            String date = Instant.ofEpochMilli((long) number(row.get(0))).atZone(ZoneOffset.UTC).toLocalDate().toString();
            if (date.compareTo(today) >= 0) continue;
            ObjectNode value = json.createObjectNode(); value.put("date", date); putNumber(value, "close", number(row.get(4))); mapped.add(value);
        }
        mapped.sort(Comparator.comparing(value -> value.path("date").asText()));
        ArrayNode output = json.createArrayNode(); mapped.forEach(output::add); return output;
    }

    public ObjectNode coinbasePremiumSeries(String product, String binanceSymbol) throws IOException {
        ObjectNode output = json.createObjectNode();
        output.set("coinbaseRows", coinbaseDaily(product, 40));
        output.set("binanceRows", binanceDaily(binanceSymbol, 40));
        output.set("usdtUsdRows", coinbaseDaily("USDT-USD", 40));
        return output;
    }

    public ObjectNode binancePremiumIndex(String symbol) throws IOException {
        JsonNode response = http.getJson(uri("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=" + encode(symbol)));
        if (response.get("markPrice") == null || response.get("indexPrice") == null) {
            throw new IllegalArgumentException("binance premiumIndex: missing mark/index for " + symbol);
        }
        ObjectNode output = json.createObjectNode();
        putNumber(output, "markPrice", number(response.get("markPrice")));
        putNumber(output, "indexPrice", number(response.get("indexPrice")));
        return output;
    }

    public ArrayNode bitfinexFundingTicker(String symbol) throws IOException {
        return arrayOrEmpty(http.getJson(uri("https://api-pub.bitfinex.com/v2/ticker/" + encode(symbol))));
    }

    public ArrayNode deribitDvol(String currency) throws IOException {
        long end = clock.getAsLong(), start = end - 2L * 86_400_000L;
        JsonNode response = http.getJson(uri("https://www.deribit.com/api/v2/public/get_volatility_index_data?currency="
                + encode(currency) + "&start_timestamp=" + start + "&end_timestamp=" + end + "&resolution=43200"));
        return response.path("result").path("data").isArray()
                ? (ArrayNode) response.path("result").path("data").deepCopy() : json.createArrayNode();
    }

    public ArrayNode deribitOptionBook(String currency) throws IOException {
        JsonNode response = http.getJson(uri("https://www.deribit.com/api/v2/public/get_book_summary_by_currency?currency="
                + encode(currency) + "&kind=option"));
        return response.path("result").isArray() ? (ArrayNode) response.path("result").deepCopy() : json.createArrayNode();
    }

    public ObjectNode binanceAggregateMarketFlow(String baseAsset, String preferredSpot,
                                                  String preferredPerpetual, int maxBars) {
        return binanceAggregateFlow.build(baseAsset, preferredSpot, preferredPerpetual, maxBars);
    }

    public ObjectNode binanceMetricsDay(String symbol, String date) throws IOException {
        String endpoint = "https://data.binance.vision/data/futures/um/daily/metrics/" + symbol + "/"
                + symbol + "-metrics-" + date + ".zip";
        List<PublicDataAdapters.ZipMember> members = PublicDataAdapters.parseZipArchive(
                http.getBytes(uri(endpoint), 1, Map.of()));
        PublicDataAdapters.ZipMember csv = members.stream().filter(member -> member.name().endsWith(".csv"))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "Binance metrics archive: CSV missing for " + symbol + " " + date));
        List<Map<String, String>> rows = parseCsv(new String(csv.bytes(), StandardCharsets.UTF_8));
        if (rows.isEmpty()) throw new IllegalArgumentException("Binance metrics archive: empty " + symbol + " " + date);
        Map<String, String> last = rows.get(rows.size() - 1);
        ObjectNode output = json.createObjectNode(); output.put("date", date);
        output.put("sum_open_interest", last.get("sum_open_interest"));
        output.put("sum_open_interest_value", last.get("sum_open_interest_value"));
        return output;
    }

    public ArrayNode binanceOi90d(String symbol) {
        List<String> dates = new ArrayList<>();
        for (int index = 0; index < 92; index++) dates.add(isoDayOffset(-92 + index));
        List<ObjectNode> rows = java.util.Collections.synchronizedList(new ArrayList<>());
        try (var executor = Executors.newFixedThreadPool(10)) {
            List<Callable<Void>> tasks = dates.stream().<Callable<Void>>map(date -> () -> {
                try { rows.add(binanceMetricsDay(symbol, date)); } catch (Exception ignored) { }
                return null;
            }).toList();
            executor.invokeAll(tasks);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Binance OI archive fetch interrupted", exception);
        }
        rows.sort(Comparator.comparing(row -> row.path("date").asText()));
        ArrayNode output = json.createArrayNode(); rows.forEach(output::add); return output;
    }

    public ObjectNode stateStreetHoldings(byte[] workbook) {
        return macroEndpoints.stateStreetHoldings(workbook);
    }

    public ObjectNode equityBreadth200() throws IOException {
        return macroEndpoints.equityBreadth200();
    }

    public ArrayNode stablecoinCharts() throws IOException {
        return macroEndpoints.stablecoinCharts();
    }

    public ArrayNode fredCsv(String seriesId) throws IOException {
        return macroEndpoints.fredCsv(seriesId);
    }

    public String isoDayOffset(int days) {
        return macroEndpoints.isoDayOffset(days);
    }

    private static List<Map<String, String>> parseCsv(String text) {
        String[] lines = text.trim().split("\\r?\\n");
        if (lines.length < 2) return List.of();
        String[] headers = lines[0].split(",", -1);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int line = 1; line < lines.length; line++) {
            String[] values = lines[line].split(",", -1);
            Map<String, String> row = new LinkedHashMap<>();
            for (int index = 0; index < values.length; index++) {
                row.put(index < headers.length ? headers[index] : null, values[index]);
            }
            rows.add(row);
        }
        return rows;
    }

    private static ArrayNode arrayOrEmpty(JsonNode value) {
        return value != null && value.isArray() ? (ArrayNode) value.deepCopy() : new ObjectMapper().createArrayNode();
    }

    private static double number(JsonNode value) {
        return com.tradinganalytics.core.compute.ComputeMath.jsNumber(value);
    }

    private static void putNumber(ObjectNode target, String key, double value) {
        target.set(key, com.tradinganalytics.core.compute.ComputeMath.normalizedNumberNode(value));
    }

    static URI uri(String value) {
        return URI.create(value);
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
                .replace("%21", "!").replace("%27", "'").replace("%28", "(")
                .replace("%29", ")").replace("%2A", "*");
    }
}

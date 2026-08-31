package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.marketdata.PublicDataAdapters;
import com.tradinganalytics.marketdata.http.MarketHttpClient;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Exact Java port of the diagnostic {@code strategy-public-smoke.mjs} boundary. */
public final class PublicDataSmokeService {
    public static final List<String> UNIVERSE = PublicDataAdapters.CORE_CRYPTO_ASSETS;
    public static final String SPOT_ROUTE = "BINANCE_SPOT_PUBLIC";
    public static final String DERIVATIVES_ROUTE = "BINANCE_USDM_PUBLIC";
    public static final String NOTE = "Smoke output is diagnostic only; it cannot mint PIT, prospective, or ACTIVE evidence.";

    private final MarketHttpClient http;
    private final ObjectMapper json;
    private final LongSupplier clock;

    public PublicDataSmokeService(MarketHttpClient http, ObjectMapper json, LongSupplier clock) {
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PublicDataSmokeService production() {
        return new PublicDataSmokeService(MarketHttpClient.production(), new ObjectMapper(), System::currentTimeMillis);
    }

    public ObjectNode run(boolean network) {
        ArrayNode results = json.createArrayNode();
        for (String asset : UNIVERSE) {
            String symbol = asset.toUpperCase(java.util.Locale.ROOT) + "USDT";
            if (!network) {
                results.add(routeReady(asset, symbol));
                continue;
            }
            try {
                long now = clock.getAsLong();
                JsonNode spot = requestKlines(
                        URI.create("https://api.binance.com/api/v3/klines?symbol=" + symbol + "&interval=1m&limit=2"),
                        false);
                JsonNode closedSpot = lastCompleted(spot, now, "malformed Binance kline response", "no completed spot bar");
                JsonNode derivatives = requestKlines(
                        URI.create("https://fapi.binance.com/fapi/v1/klines?symbol=" + symbol + "&interval=1m&limit=2"),
                        true);
                JsonNode closedDerivatives = lastCompleted(
                        derivatives, now, "malformed USD-M kline response", "no completed USD-M bar");
                results.add(networkSuccess(asset, symbol, closedSpot.get(0), closedDerivatives.get(0)));
            } catch (Exception exception) {
                results.add(unavailable(asset, symbol, message(exception)));
            }
        }

        boolean pass = true;
        for (JsonNode row : results) {
            if ("UNAVAILABLE".equals(row.path("status").asText())) pass = false;
        }
        ObjectNode output = json.createObjectNode();
        output.put("schema", "strategy-public-data-smoke/1");
        output.set("universe", json.valueToTree(UNIVERSE));
        output.put("network", network);
        output.set("results", results);
        output.put("pass", pass);
        output.put("note", NOTE);
        return output;
    }

    private JsonNode requestKlines(URI uri, boolean derivatives) throws Exception {
        try {
            return http.getJson(uri, 0, Map.of());
        } catch (MarketHttpClient.HttpStatusException exception) {
            throw new IllegalStateException((derivatives ? "USD_M_HTTP_" : "HTTP_") + exception.status());
        }
    }

    private static JsonNode lastCompleted(JsonNode rows, long now, String malformed, String missing) {
        if (rows == null || !rows.isArray() || rows.size() < 2) throw new IllegalStateException(malformed);
        JsonNode closed = null;
        for (JsonNode row : rows) {
            if (row.isArray() && row.size() > 6 && row.get(6).canConvertToLong()
                    && row.get(6).longValue() <= now) closed = row;
        }
        if (closed == null) throw new IllegalStateException(missing);
        return closed;
    }

    private ObjectNode routeReady(String asset, String symbol) {
        ObjectNode row = json.createObjectNode();
        row.put("asset", asset);
        row.put("symbol", symbol);
        row.put("route", SPOT_ROUTE);
        row.put("derivatives_route", DERIVATIVES_ROUTE);
        row.put("network", false);
        row.put("status", "ROUTE_READY");
        return row;
    }

    private ObjectNode networkSuccess(String asset, String symbol, JsonNode spotOpen, JsonNode derivativesOpen) {
        ObjectNode row = json.createObjectNode();
        row.put("asset", asset);
        row.put("symbol", symbol);
        row.put("route", SPOT_ROUTE);
        row.put("derivatives_route", DERIVATIVES_ROUTE);
        row.put("network", true);
        row.put("status", "OK");
        row.set("completed_bar_open_time", spotOpen.deepCopy());
        row.set("completed_usdm_bar_open_time", derivativesOpen.deepCopy());
        return row;
    }

    private ObjectNode unavailable(String asset, String symbol, String error) {
        ObjectNode row = json.createObjectNode();
        row.put("asset", asset);
        row.put("symbol", symbol);
        row.put("route", SPOT_ROUTE);
        row.put("network", true);
        row.put("status", "UNAVAILABLE");
        row.put("error", error);
        return row;
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}

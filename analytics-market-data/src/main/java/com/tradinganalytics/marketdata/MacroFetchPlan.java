package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ordered macro series and endpoint work list shared by the macro assembler. */
final class MacroFetchPlan {
    static final List<Series> SERIES = List.of(
            new Series("vix", "^VIX", "CBOE VIX", "1mo"),
            new Series("dxy", "DX-Y.NYB", "US Dollar Index", "1mo"),
            new Series("brent", "BZ=F", "Brent crude", "1mo"),
            new Series("spx", "^GSPC", "S&P 500", "3mo"),
            new Series("ndx", "^IXIC", "Nasdaq Composite", "1mo"),
            new Series("us10y", "^TNX", "US 10y nominal yield (×10 units)", "1mo"),
            new Series("gold", "GC=F", "COMEX gold front month", "1mo"),
            new Series("irx", "^IRX", "13-week T-bill discount rate (%)", "1mo"),
            new Series("move", "^MOVE", "ICE BofA MOVE Index (bond vol)", "1mo"));

    private MacroFetchPlan() {
    }

    static Map<String, MarketFetchExecutor.Task> create(MarketDataEndpoints endpoints) {
        Map<String, MarketFetchExecutor.Task> tasks = new LinkedHashMap<>();
        add(tasks, "fred", "FRED DFII10", () -> endpoints.fredCsv("DFII10"));
        add(tasks, "fred3mo", "FRED DGS3MO", () -> endpoints.fredCsv("DGS3MO"));
        add(tasks, "hyOas", "FRED BAMLH0A0HYM2 (HY OAS)", () -> endpoints.fredCsv("BAMLH0A0HYM2"));
        add(tasks, "nfci", "FRED NFCI", () -> endpoints.fredCsv("NFCI"));
        add(tasks, "walcl", "FRED WALCL", () -> endpoints.fredCsv("WALCL"));
        add(tasks, "rrpontsyd", "FRED RRPONTSYD", () -> endpoints.fredCsv("RRPONTSYD"));
        add(tasks, "wtregen", "FRED WTREGEN", () -> endpoints.fredCsv("WTREGEN"));
        add(tasks, "stablecoinRows", "DefiLlama stablecoincharts", endpoints::stablecoinCharts);
        add(tasks, "breadthData", "S&P 500 breadth above 200dma", endpoints::equityBreadth200);
        for (Series item : SERIES) {
            add(tasks, "chart:" + item.key(), "yahoo " + item.symbol(),
                    () -> endpoints.yahooChart(item.symbol(), item.range(), "1d"));
        }
        return tasks;
    }

    private static void add(Map<String, MarketFetchExecutor.Task> tasks, String key, String label,
                            MarketFetchExecutor.ThrowingSupplier<? extends JsonNode> supplier) {
        MarketFetchExecutor.add(tasks, key, label, supplier);
    }

    record Series(String key, String symbol, String label, String range) {
    }
}

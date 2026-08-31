package com.tradinganalytics.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinganalytics.infrastructure.marketdata.PublicDataAdapters;
import com.tradinganalytics.marketdata.http.MarketHttpClient;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class LiveMarketFetchServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long NOW = Instant.parse("2026-08-28T12:34:56.789Z").toEpochMilli();

    @Test
    void macroFetchBuildsEveryAvailableBlockAndPreservesCrossChecks() throws Exception {
        byte[] workbook = spyWorkbook();
        PublicDataAdapters.InjectableHttpClient getter = (uri, headers) -> {
            String value = uri.toString();
            if (value.contains("fredgraph.csv")) return response(200, fredCsv().getBytes(StandardCharsets.UTF_8));
            if (value.contains("stablecoincharts")) return response(200, stablecoins().getBytes(StandardCharsets.UTF_8));
            if (value.contains("finance/chart")) return response(200, yahooChart().getBytes(StandardCharsets.UTF_8));
            if (value.contains("holdings-daily")) return response(200, workbook);
            return response(404, new byte[0]);
        };
        MarketHttpClient.Poster poster = (uri, headers, body, timeout) -> new MarketHttpClient.Response(
                200, "{\"data\":[{\"d\":[\"AAPL\",200,150]},{\"d\":[\"MSFT\",90,100]}]}"
                .getBytes(StandardCharsets.UTF_8), Map.of());
        MarketHttpClient http = new MarketHttpClient(getter, poster, millis -> { }, JSON);
        LiveMarketFetchService service = new LiveMarketFetchService(
                new MarketDataEndpoints(http, JSON, () -> NOW, null), JSON, () -> NOW, false);

        var result = service.fetchMacro();

        assertThat(result.path("scope").asText()).isEqualTo("macro");
        assertThat(result.path("fetched_at").asText()).isEqualTo("2026-08-28T12:34:56.789Z");
        assertThat(result.path("errors")).isEmpty();
        assertThat(result.path("real_yield_10y_tips").path("delta_5_prints").asDouble()).isEqualTo(5.0);
        assertThat(result.path("net_liquidity").path("available").asBoolean()).isTrue();
        assertThat(result.path("stablecoin_supply").path("available").asBoolean()).isTrue();
        assertThat(result.path("equities_breadth_200dma").path("pct_above_200dma").asDouble()).isEqualTo(50.0);
        assertThat(result.path("spx").path("series")).hasSize(7);
        assertThat(result.path("dry_powder_benchmark").path("annualized_pct").asDouble()).isEqualTo(106.0);
        assertThat(result.path("dry_powder_benchmark").path("cross_check").path("dgs3mo").asDouble()).isEqualTo(109.0);
        assertThat(result.path("gap_coverage").path("equities_breadth_pct_above_200dma").asText())
                .isEqualTo("AVAILABLE");
    }

    @Test
    void macroFetchDegradesPerSourceAndKeepsDeterministicErrorOrder() {
        PublicDataAdapters.InjectableHttpClient getter = (uri, headers) -> {
            String value = uri.toString();
            if (value.contains("DFII10")) return response(200, fredCsv().getBytes(StandardCharsets.UTF_8));
            return response(404, new byte[0]);
        };
        MarketHttpClient http = new MarketHttpClient(getter, null, millis -> { }, JSON);
        LiveMarketFetchService service = new LiveMarketFetchService(
                new MarketDataEndpoints(http, JSON, () -> NOW, null), JSON, () -> NOW, false);

        var result = service.fetchMacro();

        assertThat(result.path("real_yield_10y_tips").path("last").path("value").asDouble()).isEqualTo(109.0);
        assertThat(result.path("errors").get(0).asText()).startsWith("FRED DGS3MO: 404 ");
        assertThat(result.path("errors").get(1).asText()).startsWith("FRED BAMLH0A0HYM2 (HY OAS): 404 ");
        assertThat(result.path("gap_coverage").path("equities_breadth_pct_above_200dma").asText())
                .isEqualTo("UNKNOWN");
    }

    @Test
    void goldFetchUsesYahooFallbackWithoutInventingCryptoBlocks() {
        PublicDataAdapters.InjectableHttpClient getter = (uri, headers) -> {
            if (uri.toString().contains("finance/chart")) {
                return response(200, yahooChart(240).getBytes(StandardCharsets.UTF_8));
            }
            return response(404, new byte[0]);
        };
        MarketHttpClient http = new MarketHttpClient(getter, null, millis -> { }, JSON);
        LiveMarketFetchService service = new LiveMarketFetchService(
                new MarketDataEndpoints(http, JSON, () -> NOW, null), JSON, () -> NOW, false);

        var result = service.fetchAsset("gold", true);

        assertThat(result.path("asset").asText()).isEqualTo("GOLD");
        assertThat(result.path("errors")).isEmpty();
        assertThat(result.path("spot").path("canonical").asDouble()).isEqualTo(339.0);
        assertThat(result.path("spot").path("canonical_source").asText()).isEqualTo("priority_first_fallback");
        assertThat(result.path("daily").path("series")).hasSize(240);
        assertThat(result.path("trend").path("ma200").isNumber()).isTrue();
        assertThat(result.path("context").path("sentiment_proxy").path("vol_index").isObject()).isTrue();
        assertThat(result.has("funding")).isFalse();
        assertThat(result.path("context").has("market_flow")).isFalse();
        assertThat(result.has("gap_coverage")).isFalse();
    }

    private static String fredCsv() {
        StringBuilder value = new StringBuilder("DATE,VALUE\n");
        for (int index = 0; index < 10; index++) value.append("2026-08-")
                .append(String.format("%02d", 18 + index)).append(',').append(100 + index).append('\n');
        return value.toString();
    }

    private static String yahooChart() {
        return """
                {"chart":{"result":[{"timestamp":[1787356800,1787443200,1787529600,1787616000,1787702400,1787788800,1787875200],
                  "indicators":{"quote":[{"open":[100,101,102,103,104,105,106],
                    "high":[101,102,103,104,105,106,107],"low":[99,100,101,102,103,104,105],
                    "close":[100,101,102,103,104,105,106],"volume":[1,2,3,4,5,6,7]}]}}]}}
                """;
    }

    private static String yahooChart(int count) {
        StringBuilder timestamps = new StringBuilder(), open = new StringBuilder(), high = new StringBuilder();
        StringBuilder low = new StringBuilder(), close = new StringBuilder(), volume = new StringBuilder();
        long first = Instant.parse("2026-01-01T00:00:00Z").getEpochSecond();
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                timestamps.append(','); open.append(','); high.append(','); low.append(','); close.append(','); volume.append(',');
            }
            timestamps.append(first + index * 86_400L);
            open.append(100 + index); high.append(101 + index); low.append(99 + index);
            close.append(100 + index); volume.append(1_000 + index);
        }
        return "{\"chart\":{\"result\":[{\"timestamp\":[" + timestamps + "],\"indicators\":{\"quote\":[{"
                + "\"open\":[" + open + "],\"high\":[" + high + "],\"low\":[" + low + "],"
                + "\"close\":[" + close + "],\"volume\":[" + volume + "]}]}}]}}";
    }

    private static String stablecoins() {
        StringBuilder value = new StringBuilder("[");
        for (int index = 0; index < 100; index++) {
            if (index > 0) value.append(',');
            value.append("{\"date\":").append(1_700_000_000L + index * 86_400L)
                    .append(",\"totalCirculatingUSD\":{\"peggedUSD\":")
                    .append(1_000_000 + index * 1_000).append("}}");
        }
        return value.append(']').toString();
    }

    private static byte[] spyWorkbook() throws Exception {
        String shared = """
                <sst><si><t>Ticker</t></si><si><t>As of 2026-08-27</t></si>
                <si><t>AAPL</t></si><si><t>MSFT</t></si></sst>
                """;
        String sheet = """
                <worksheet><sheetData>
                <row><c r="B1" t="s"><v>1</v></c></row>
                <row><c r="A2" t="s"><v>0</v></c></row>
                <row><c r="A3" t="s"><v>2</v></c></row>
                <row><c r="A4" t="s"><v>3</v></c></row>
                </sheetData></worksheet>
                """;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
            zip.write(shared.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(sheet.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static PublicDataAdapters.FetchResponse response(int status, byte[] body) {
        return new PublicDataAdapters.FetchResponse(status, body, Map.of());
    }
}

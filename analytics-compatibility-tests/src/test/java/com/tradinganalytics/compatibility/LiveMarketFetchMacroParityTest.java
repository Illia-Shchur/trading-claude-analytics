package com.tradinganalytics.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
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

class LiveMarketFetchMacroParityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long NOW = Instant.parse("2026-08-28T12:34:56.789Z").toEpochMilli();



    @Test
    void completeMacroFixtureMatchesNodeExactly() throws Exception {
        byte[] workbook = spyWorkbook();
        JsonNode yahoo = JSON.readTree(yahooChart());
        JsonNode stablecoins = JSON.readTree(stablecoins());
        JsonNode scanner = JSON.readTree("{\"data\":[{\"d\":[\"AAPL\",200,150]},{\"d\":[\"MSFT\",90,100]}]}");
        ObjectNode input = JSON.createObjectNode();
        input.put("fred", fredCsv()); input.set("yahoo", yahoo); input.set("stablecoins", stablecoins);
        input.put("workbook", Base64.getEncoder().encodeToString(workbook)); input.set("scanner", scanner);
        JsonNode expected = frozen("live-fetch-macro-v1.json");

        PublicDataAdapters.InjectableHttpClient getter = (uri, headers) -> {
            String value = uri.toString();
            if (value.contains("fredgraph.csv")) return response(200, fredCsv().getBytes(StandardCharsets.UTF_8));
            if (value.contains("stablecoincharts")) return response(200, JSON.writeValueAsBytes(stablecoins));
            if (value.contains("finance/chart")) return response(200, JSON.writeValueAsBytes(yahoo));
            if (value.contains("holdings-daily")) return response(200, workbook);
            return response(404, new byte[0]);
        };
        MarketHttpClient.Poster poster = (uri, headers, body, timeout) -> new MarketHttpClient.Response(
                200, JSON.writeValueAsBytes(scanner), Map.of());
        MarketHttpClient http = new MarketHttpClient(getter, poster, millis -> { }, JSON);
        ObjectNode actual = new LiveMarketFetchService(
                new MarketDataEndpoints(http, JSON, () -> NOW, null), JSON, () -> NOW, false).fetchMacro();
        actual.remove("fetched_at");

        assertThat(NodePrettyJson.write(actual)).isEqualTo(NodePrettyJson.write(expected));
    }

    @Test
    void completeGoldFixtureMatchesNodeExactly() throws Exception {
        JsonNode yahoo = JSON.readTree(yahooChart(240));
        ObjectNode input = JSON.createObjectNode(); input.put("now", NOW); input.set("yahoo", yahoo);
        JsonNode expected = frozen("live-fetch-gold-v1.json");

        PublicDataAdapters.InjectableHttpClient getter = (uri, headers) -> uri.toString().contains("finance/chart")
                ? response(200, JSON.writeValueAsBytes(yahoo)) : response(404, new byte[0]);
        MarketHttpClient http = new MarketHttpClient(getter, null, millis -> { }, JSON);
        ObjectNode actual = new LiveMarketFetchService(
                new MarketDataEndpoints(http, JSON, () -> NOW, null), JSON, () -> NOW, false)
                .fetchAsset("gold", true);
        actual.remove("fetched_at");

        assertThat(NodePrettyJson.write(actual)).isEqualTo(NodePrettyJson.write(expected));
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
        String shared = "<sst><si><t>Ticker</t></si><si><t>As of 2026-08-27</t></si>"
                + "<si><t>AAPL</t></si><si><t>MSFT</t></si></sst>";
        String sheet = "<worksheet><sheetData>"
                + "<row><c r=\"B1\" t=\"s\"><v>1</v></c></row>"
                + "<row><c r=\"A2\" t=\"s\"><v>0</v></c></row>"
                + "<row><c r=\"A3\" t=\"s\"><v>2</v></c></row>"
                + "<row><c r=\"A4\" t=\"s\"><v>3</v></c></row>"
                + "</sheetData></worksheet>";
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

    private static JsonNode frozen(String name) throws Exception {
        try (InputStream stream = LiveMarketFetchMacroParityTest.class
                .getResourceAsStream("/oracles/" + name)) {
            assertThat(stream).as(name).isNotNull();
            return JSON.readTree(stream);
        }
    }
}

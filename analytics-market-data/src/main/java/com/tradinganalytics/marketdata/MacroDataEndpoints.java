package com.tradinganalytics.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.marketdata.PublicDataAdapters;
import com.tradinganalytics.marketdata.http.MarketHttpClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Adapters for macro and auxiliary market datasets. */
final class MacroDataEndpoints {
    private static final Pattern SHARED_STRING = Pattern.compile("<si(?:\\s[^>]*)?>([\\s\\S]*?)</si>");
    private static final Pattern ROW = Pattern.compile("<row(?:\\s[^>]*)?>([\\s\\S]*?)</row>");
    private static final Pattern CELL = Pattern.compile("<c\\s+([^>]*)>([\\s\\S]*?)</c>");
    private static final Pattern REFERENCE = Pattern.compile("r=\"([A-Z]+)\\d+\"");
    private static final Pattern VALUE = Pattern.compile("<v>([\\s\\S]*?)</v>");

    private final MarketHttpClient http;
    private final ObjectMapper json;
    private final LongSupplier clock;

    MacroDataEndpoints(MarketHttpClient http, ObjectMapper json, LongSupplier clock) {
        this.http = http;
        this.json = json;
        this.clock = clock;
    }

    ObjectNode stateStreetHoldings(byte[] workbook) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (PublicDataAdapters.ZipMember member : PublicDataAdapters.parseZipArchive(workbook)) {
            entries.put(member.name(), member.bytes());
        }
        String sharedXml = new String(entries.getOrDefault("xl/sharedStrings.xml", new byte[0]), StandardCharsets.UTF_8);
        List<String> shared = matches(SHARED_STRING, sharedXml).stream().map(MacroDataEndpoints::xmlText).toList();
        String sheet = new String(entries.getOrDefault("xl/worksheets/sheet1.xml", new byte[0]), StandardCharsets.UTF_8);
        if (sheet.isEmpty()) throw new IllegalArgumentException("State Street XLSX: sheet1.xml missing");
        List<Map<String, String>> rows = parseSheetRows(sheet, shared);
        int headerIndex = findTickerHeader(rows);
        if (headerIndex < 0) throw new IllegalArgumentException("State Street XLSX: Ticker header missing");
        String tickerColumn = rows.get(headerIndex).entrySet().stream()
                .filter(entry -> "Ticker".equals(entry.getValue().trim())).findFirst().orElseThrow().getKey();
        LinkedHashSet<String> tickers = new LinkedHashSet<>();
        for (int index = headerIndex + 1; index < rows.size(); index++) {
            String ticker = rows.get(index).getOrDefault(tickerColumn, "").trim();
            if (ticker.matches("[A-Z0-9.\\-]+")) tickers.add(ticker);
        }
        String asOf = findAsOf(rows);
        ObjectNode output = json.createObjectNode();
        output.set("tickers", json.valueToTree(tickers));
        if (asOf == null) output.putNull("asOf"); else output.put("asOf", asOf);
        return output;
    }

    ObjectNode equityBreadth200() throws IOException {
        ObjectNode universe = stateStreetHoldings(http.getBytes(MarketDataEndpoints.uri(
                "https://www.ssga.com/library-content/products/fund-data/etfs/us/holdings-daily-us-en-spy.xlsx"), 2, Map.of()));
        if (universe.path("tickers").isEmpty()) throw new IllegalArgumentException("State Street SPY holdings: no tickers parsed");
        ObjectNode request = json.createObjectNode();
        ObjectNode filter = request.putArray("filter").addObject();
        filter.put("left", "name"); filter.put("operation", "in_range"); filter.set("right", universe.path("tickers").deepCopy());
        request.putObject("options").put("lang", "en"); request.putArray("markets").add("america");
        ObjectNode symbols = request.putObject("symbols");
        symbols.putObject("query").putArray("types").add("stock"); symbols.putArray("tickers");
        request.putArray("columns").add("name").add("close").add("SMA200");
        request.putArray("range").add(0).add(universe.path("tickers").size() + 20);
        JsonNode response = http.postJson(MarketDataEndpoints.uri("https://scanner.tradingview.com/america/scan"), request);
        ArrayNode rows = json.createArrayNode();
        for (JsonNode row : response.path("data")) {
            JsonNode data = row.path("d"); ObjectNode mapped = rows.addObject();
            mapped.put("ticker", data.path(0).asText()); putNumber(mapped, "close", number(data.get(1)));
            putNumber(mapped, "sma200", number(data.get(2)));
        }
        ObjectNode output = json.createObjectNode(); output.set("rows", rows);
        output.put("universeSize", universe.path("tickers").size());
        output.set("universeAsOf", universe.get("asOf").deepCopy());
        return output;
    }

    ArrayNode stablecoinCharts() throws IOException {
        return arrayOrEmpty(http.getJson(MarketDataEndpoints.uri(
                "https://stablecoins.llama.fi/stablecoincharts/all?stablecoin=1")));
    }

    ArrayNode fredCsv(String seriesId) throws IOException {
        String text = http.getText(MarketDataEndpoints.uri(
                "https://fred.stlouisfed.org/graph/fredgraph.csv?id=" + MarketDataEndpoints.encode(seriesId)));
        String[] lines = text.trim().split("\\r?\\n");
        ArrayNode output = json.createArrayNode();
        for (int index = Math.max(1, lines.length - 10); index < lines.length; index++) {
            String[] cells = lines[index].split(",", -1);
            if (cells.length < 2 || ".".equals(cells[1])) continue;
            ObjectNode row = output.addObject(); row.put("date", cells[0]); putNumber(row, "value", Double.parseDouble(cells[1]));
        }
        return output;
    }

    String isoDayOffset(int days) {
        return Instant.ofEpochMilli(clock.getAsLong()).atZone(ZoneOffset.UTC).toLocalDate().plusDays(days).toString();
    }

    private static List<Map<String, String>> parseSheetRows(String sheet, List<String> shared) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (String rowXml : matches(ROW, sheet)) {
            Map<String, String> cells = new LinkedHashMap<>();
            Matcher cell = CELL.matcher(rowXml);
            while (cell.find()) {
                Matcher reference = REFERENCE.matcher(cell.group(1)); Matcher value = VALUE.matcher(cell.group(2));
                if (!reference.find() || !value.find()) continue;
                String rawValue = value.group(1);
                String text;
                if (cell.group(1).contains("t=\"s\"")) {
                    int sharedIndex = Integer.parseInt(rawValue);
                    if (sharedIndex < 0 || sharedIndex >= shared.size()) {
                        throw new IllegalArgumentException("State Street XLSX: invalid shared-string index " + sharedIndex);
                    }
                    text = shared.get(sharedIndex);
                } else {
                    text = xmlText(rawValue);
                }
                cells.put(reference.group(1), text);
            }
            rows.add(cells);
        }
        return rows;
    }

    private static int findTickerHeader(List<Map<String, String>> rows) {
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).values().stream().anyMatch(value -> "Ticker".equals(value.trim()))) return index;
        }
        return -1;
    }

    private static String findAsOf(List<Map<String, String>> rows) {
        for (Map<String, String> row : rows) {
            String found = row.values().stream().filter(value -> value.startsWith("As of")).findFirst().orElse(null);
            if (found != null) return found.replaceFirst("^As of\\s+", "");
        }
        return null;
    }

    private static List<String> matches(Pattern pattern, String value) {
        List<String> output = new ArrayList<>();
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) output.add(matcher.group(1));
        return output;
    }

    private static String xmlText(String value) {
        return value.replaceAll("<[^>]+>", "").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
    }

    private ArrayNode arrayOrEmpty(JsonNode value) {
        return value != null && value.isArray() ? (ArrayNode) value.deepCopy() : json.createArrayNode();
    }

    private static double number(JsonNode value) {
        return com.tradinganalytics.core.compute.ComputeMath.jsNumber(value);
    }

    private static void putNumber(ObjectNode target, String key, double value) {
        target.set(key, com.tradinganalytics.core.compute.ComputeMath.normalizedNumberNode(value));
    }
}

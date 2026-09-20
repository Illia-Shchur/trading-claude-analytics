package com.tradinganalytics.infrastructure.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Bounded Coinalyze daily liquidation acquisition; evidence is diagnostic, never PIT-verified. */
public final class CoinalyzeDailyData {
    public static final String MANIFEST_NAME = "coinalyze-daily-acquisition.json";
    private static final String BASE = "https://api.coinalyze.net/v1/";
    private static final String API_KEY_HEADER = "api_key";
    private static final int MAX_SYMBOL_CALLS_PER_MINUTE = 40;
    private static final int MAX_RETRIES = 3;
    private static final List<String> ASSETS = List.of("BTC", "ETH", "SOL", "AAVE");

    private CoinalyzeDailyData() {}

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public record Options(
            LocalDate fromInclusive,
            LocalDate toExclusive,
            Instant asOf,
            Path root,
            String apiKey,
            PublicDataAdapters.InjectableHttpClient client,
            Sleeper sleeper,
            LongSupplier nanoTime) {
        public Options {
            if (fromInclusive == null || toExclusive == null || asOf == null || root == null) {
                throw new IllegalArgumentException("Coinalyze daily requires from, to, as-of, and root");
            }
            if (!fromInclusive.isBefore(toExclusive)) {
                throw new IllegalArgumentException("Coinalyze daily --from must be before exclusive --to");
            }
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("COINALYZE_API_KEY is required");
            }
            client = client == null ? defaultClient() : client;
            sleeper = sleeper == null ? Thread::sleep : sleeper;
            nanoTime = nanoTime == null ? System::nanoTime : nanoTime;
        }

        @Override public String toString() {
            return "Options[fromInclusive=" + fromInclusive + ", toExclusive=" + toExclusive
                    + ", asOf=" + asOf + ", root=" + root + ", apiKey=[redacted]]";
        }
    }

    /** Acquires catalogues and yearly-bounded daily histories into a new immutable root. */
    public static ObjectNode acquire(Options options) throws IOException {
        try {
            return acquireInternal(options);
        } catch (IOException error) {
            throw new IOException(sanitize(error.getMessage(), options == null ? null : options.apiKey()));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    sanitize(error.getMessage(), options == null ? null : options.apiKey()));
        }
    }

    private static ObjectNode acquireInternal(Options options) throws IOException {
        Path root = options.root().toAbsolutePath().normalize();
        prepareNewRoot(root);
        Path rawDir = root.resolve("raw");
        Files.createDirectory(rawDir);
        RateLimiter limiter = new RateLimiter(options.sleeper(), options.nanoTime());
        Fetcher fetcher = new Fetcher(options.apiKey(), options.client(), options.sleeper(), limiter);
        List<ObjectNode> refs = new ArrayList<>();
        JsonNode exchanges = fetcher.fetch("exchanges", "exchanges", Map.of(), 1, refs,
                root, "raw/exchanges.json");
        JsonNode markets = fetcher.fetch("future-markets", "future-markets", Map.of(), 1, refs,
                root, "raw/future-markets.json");
        MarketResolution resolution = resolveMarkets(exchanges, markets);

        LocalDate closedTo = min(options.toExclusive(), options.asOf().atZone(ZoneOffset.UTC).toLocalDate());
        if (closedTo.isBefore(options.fromInclusive())) closedTo = options.fromInclusive();
        Map<String, JsonNode> histories = new LinkedHashMap<>();
        resolution.symbolByAsset().values().forEach(symbol -> histories.put(symbol, array()));
        LocalDate cursor = options.fromInclusive();
        while (cursor.isBefore(closedTo) && !resolution.symbolByAsset().isEmpty()) {
            LocalDate nextYear = LocalDate.of(cursor.getYear() + 1, 1, 1);
            LocalDate chunkEnd = min(nextYear, closedTo);
            long from = cursor.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            long to = chunkEnd.minusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            List<String> symbols = List.copyOf(resolution.symbolByAsset().values());
            Map<String, String> query = new LinkedHashMap<>();
            query.put("symbols", String.join(",", symbols));
            query.put("interval", "daily");
            query.put("from", Long.toString(from));
            query.put("to", Long.toString(to));
            query.put("convert_to_usd", "true");
            JsonNode response = fetcher.fetch("liquidation-history", "liquidation_history", query,
                    symbols.size(), refs, root,
                    "raw/liquidation-history-" + String.format(Locale.ROOT, "%04d", cursor.getYear()) + ".json");
            mergeHistory(histories, response, symbols, cursor, chunkEnd);
            cursor = chunkEnd;
        }
        ObjectNode qualified = qualifyInternal(exchanges, markets, histories,
                options.fromInclusive(), options.toExclusive(), options.asOf(), closedTo);
        ObjectNode manifest = JsonHashes.mapper().createObjectNode();
        manifest.put("schema", "coinalyze-daily-acquisition/1");
        manifest.put("immutable", true);
        manifest.put("diagnostic", true);
        manifest.put("pit_verified", false);
        manifest.put("source_vintage_status", "RETROSPECTIVE_UNKNOWN");
        manifest.put("source", "Coinalyze API");
        manifest.put("captured_at", Instant.now().toString());
        manifest.put("from_inclusive", options.fromInclusive().toString());
        manifest.put("to_exclusive", options.toExclusive().toString());
        manifest.put("as_of", options.asOf().toString());
        manifest.set("symbols", qualified.path("symbols").deepCopy());
        manifest.set("rows", qualified.path("rows").deepCopy());
        manifest.set("coverage", qualified.path("coverage").deepCopy());
        ArrayNode rawRefs = manifest.putArray("raw_responses");
        refs.forEach(rawRefs::add);
        manifest.put("content_sha256", ownHash(manifest));
        Path destination = root.resolve(MANIFEST_NAME);
        Files.write(destination, pretty(manifest), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return manifest;
    }

    /**
     * Reopens physical receipts, verifies hashes/path confinement, and independently re-qualifies
     * the exact raw responses. Consumers should use these returned rows rather than manifest.rows.
     */
    public static ObjectNode reopenAndQualify(Path manifestOrRoot) throws IOException {
        Path manifestPath = Files.isDirectory(manifestOrRoot, LinkOption.NOFOLLOW_LINKS)
                ? manifestOrRoot.resolve(MANIFEST_NAME) : manifestOrRoot;
        Path absoluteManifest = manifestPath.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absoluteManifest)) {
            throw new IllegalArgumentException("Coinalyze manifest must not be a symlink");
        }
        ObjectNode manifest = readObject(Files.readAllBytes(absoluteManifest), "Coinalyze manifest");
        if (!"coinalyze-daily-acquisition/1".equals(manifest.path("schema").asText())
                || !manifest.path("immutable").asBoolean(false)
                || !manifest.path("diagnostic").asBoolean(false)
                || manifest.path("pit_verified").asBoolean(true)) {
            throw new IllegalArgumentException("unsupported or non-diagnostic Coinalyze acquisition manifest");
        }
        if (!manifest.path("content_sha256").asText().equals(ownHash(manifest))) {
            throw new IllegalArgumentException("Coinalyze manifest content hash mismatch");
        }
        LocalDate from = date(manifest.path("from_inclusive").asText(), "from_inclusive");
        LocalDate to = date(manifest.path("to_exclusive").asText(), "to_exclusive");
        if (!from.isBefore(to)) throw new IllegalArgumentException("Coinalyze manifest window is invalid");
        Instant asOf = instant(manifest.path("as_of").asText(), "as_of");
        Path root = absoluteManifest.getParent();
        if (root == null || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("Coinalyze manifest root is invalid");
        }
        JsonNode exchanges = null;
        JsonNode markets = null;
        Map<String, JsonNode> histories = new LinkedHashMap<>();
        JsonNode refs = manifest.path("raw_responses");
        if (!refs.isArray()) throw new IllegalArgumentException("Coinalyze raw_responses must be an array");
        for (JsonNode ref : refs) {
            String relative = requiredText(ref, "path");
            Path relativePath = Path.of(relative);
            if (relativePath.isAbsolute() || relativePath.getNameCount() != 2
                    || !"raw".equals(relativePath.getName(0).toString())
                    || !relativePath.equals(relativePath.normalize())) {
                throw new IllegalArgumentException("Coinalyze raw response path escapes acquisition root");
            }
            Path file = root.resolve(relativePath).normalize();
            if (!file.startsWith(root) || Files.isSymbolicLink(root.resolve("raw")) || Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Coinalyze raw response file is missing or unsafe");
            }
            byte[] bytes = Files.readAllBytes(file);
            if (!JsonHashes.sha256(bytes).equals(requiredText(ref, "sha256"))) {
                throw new IllegalArgumentException("Coinalyze raw response SHA-256 mismatch");
            }
            if (requiredLong(ref, "status") != 200 || requiredLong(ref, "bytes") != bytes.length) {
                throw new IllegalArgumentException("Coinalyze raw response receipt metadata mismatch");
            }
            instant(requiredText(ref, "captured_at"), "raw response captured_at");
            JsonNode raw = JsonHashes.parse(bytes, "Coinalyze raw response");
            String kind = requiredText(ref, "kind");
            String endpoint = requiredText(ref, "endpoint");
            if (!endpointFor(kind).equals(endpoint)) {
                throw new IllegalArgumentException("Coinalyze raw response endpoint/kind mismatch");
            }
            switch (kind) {
                case "exchanges" -> {
                    requireEmptyRequest(ref.path("request"));
                    if (exchanges != null) throw new IllegalArgumentException("duplicate Coinalyze exchanges receipt");
                    exchanges = raw;
                }
                case "future-markets" -> {
                    requireEmptyRequest(ref.path("request"));
                    if (markets != null) throw new IllegalArgumentException("duplicate Coinalyze future-markets receipt");
                    markets = raw;
                }
                case "liquidation_history" -> {
                    validateHistoryRequest(ref.path("request"), from, to, asOf);
                    MarketResolution resolution = markets == null || exchanges == null
                            ? null : resolveMarkets(exchanges, markets);
                    if (resolution == null) {
                        // Catalogue refs precede every history ref in canonical acquisitions.
                        throw new IllegalArgumentException("Coinalyze history receipt precedes its catalogues");
                    }
                    List<String> expected = requestSymbols(ref.path("request"));
                    validateExpectedSymbols(expected, resolution.symbolByAsset());
                    long start = requiredLong(ref.path("request"), "from");
                    long end = requiredLong(ref.path("request"), "to");
                    LocalDate requestStart = Instant.ofEpochSecond(start).atZone(ZoneOffset.UTC).toLocalDate();
                    LocalDate requestEnd = Instant.ofEpochSecond(end).atZone(ZoneOffset.UTC).toLocalDate().plusDays(1);
                    mergeHistory(histories, raw, expected, requestStart, requestEnd);
                }
                default -> throw new IllegalArgumentException("unknown Coinalyze raw response kind");
            }
        }
        if (exchanges == null || markets == null) {
            throw new IllegalArgumentException("Coinalyze acquisition lacks required catalogues");
        }
        MarketResolution resolution = resolveMarkets(exchanges, markets);
        resolution.symbolByAsset().values().forEach(symbol -> histories.putIfAbsent(symbol, array()));
        LocalDate closedTo = min(to, asOf.atZone(ZoneOffset.UTC).toLocalDate());
        if (closedTo.isBefore(from)) closedTo = from;
        ObjectNode recalculated = qualifyInternal(exchanges, markets, histories, from, to, asOf, closedTo);
        if (!JsonHashes.canonicalString(recalculated.path("symbols"))
                .equals(JsonHashes.canonicalString(manifest.path("symbols")))) {
            throw new IllegalArgumentException("Coinalyze symbol mapping does not match verified raw catalogues");
        }
        if (!JsonHashes.canonicalString(recalculated.path("rows"))
                .equals(JsonHashes.canonicalString(manifest.path("rows")))) {
            throw new IllegalArgumentException("Coinalyze normalized rows do not match verified raw receipts");
        }
        if (!JsonHashes.canonicalString(recalculated.path("coverage"))
                .equals(JsonHashes.canonicalString(manifest.path("coverage")))) {
            throw new IllegalArgumentException("Coinalyze coverage does not match verified raw receipts");
        }
        ObjectNode result = recalculated.deepCopy();
        result.put("schema", "coinalyze-daily-qualification/1");
        result.put("source_vintage_status", "RETROSPECTIVE_UNKNOWN");
        result.put("pit_verified", false);
        result.put("manifest_path", absoluteManifest.toString());
        result.set("raw_responses", refs.deepCopy());
        return result;
    }

    /** Offline qualification entry point used by tests and fixture-based audits. */
    public static ObjectNode qualify(
            JsonNode exchanges, JsonNode futureMarkets, Map<String, JsonNode> historyBySymbol,
            LocalDate fromInclusive, LocalDate toExclusive, Instant asOf) {
        if (fromInclusive == null || toExclusive == null || asOf == null
                || !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("invalid Coinalyze qualification window");
        }
        Map<String, JsonNode> histories = new LinkedHashMap<>();
        if (historyBySymbol != null) historyBySymbol.forEach((symbol, history) ->
                histories.put(symbol, history == null ? array() : history.deepCopy()));
        LocalDate closedTo = min(toExclusive, asOf.atZone(ZoneOffset.UTC).toLocalDate());
        if (closedTo.isBefore(fromInclusive)) closedTo = fromInclusive;
        return qualifyInternal(exchanges, futureMarkets, histories,
                fromInclusive, toExclusive, asOf, closedTo);
    }

    private static ObjectNode qualifyInternal(
            JsonNode exchanges, JsonNode futureMarkets, Map<String, JsonNode> histories,
            LocalDate from, LocalDate to, Instant asOf, LocalDate closedTo) {
        MarketResolution markets = resolveMarkets(exchanges, futureMarkets);
        Set<String> expectedSymbols = new HashSet<>(markets.symbolByAsset().values());
        for (String symbol : histories.keySet()) {
            if (!expectedSymbols.contains(symbol)) {
                throw new IllegalArgumentException("Coinalyze history symbol does not match resolved market");
            }
        }
        ObjectNode result = JsonHashes.mapper().createObjectNode();
        ObjectNode symbolMap = result.putObject("symbols");
        for (String asset : ASSETS) {
            String symbol = markets.symbolByAsset().get(asset);
            if (symbol == null) symbolMap.putNull(asset);
            else {
                ObjectNode mapping = symbolMap.putObject(asset);
                mapping.put("symbol", symbol);
                mapping.put("exchange", markets.exchangeCode());
                mapping.put("base_asset", asset);
                mapping.put("quote_asset", "USDT");
                mapping.put("is_perpetual", true);
                mapping.put("margined", "STABLE");
            }
        }

        Map<String, String> assetBySymbol = new HashMap<>();
        markets.symbolByAsset().forEach((asset, symbol) -> assetBySymbol.put(symbol, asset));
        Map<String, Map<LocalDate, Liquidation>> points = new LinkedHashMap<>();
        for (var mapping : markets.symbolByAsset().entrySet()) {
            JsonNode response = histories.get(mapping.getValue());
            if (response == null) continue;
            JsonNode series = historyForSymbol(response, mapping.getValue());
            if (!series.isArray()) throw new IllegalArgumentException("Coinalyze history must be an array");
            for (JsonNode row : series) {
                if (!row.isObject()) throw new IllegalArgumentException("Coinalyze liquidation row must be an object");
                long epochSeconds = requiredLong(row, "t");
                if (Math.floorMod(epochSeconds, 86_400L) != 0) {
                    throw new IllegalArgumentException("Coinalyze timestamp is not UTC midnight");
                }
                LocalDate day = Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).toLocalDate();
                if (day.isBefore(from) || !day.isBefore(to)) {
                    throw new IllegalArgumentException("Coinalyze row is outside the requested date range");
                }
                BigDecimalValue longs = nonnegativeNumber(row, "l");
                BigDecimalValue shorts = nonnegativeNumber(row, "s");
                if (!day.isBefore(closedTo)) continue;
                Map<LocalDate, Liquidation> byDate = points.computeIfAbsent(mapping.getKey(), ignored -> new LinkedHashMap<>());
                if (byDate.putIfAbsent(day, new Liquidation(longs.value(), shorts.value())) != null) {
                    throw new IllegalArgumentException("duplicate Coinalyze symbol/day timestamp");
                }
            }
        }

        ArrayNode rows = result.putArray("rows");
        points.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                entry.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(dayEntry -> {
                    ObjectNode row = rows.addObject();
                    String symbol = markets.symbolByAsset().get(entry.getKey());
                    row.put("asset", entry.getKey());
                    row.put("symbol", symbol);
                    row.put("day_start_utc", dayEntry.getKey().atStartOfDay(ZoneOffset.UTC).toInstant().toString());
                    row.put("long_liquidations_usd", dayEntry.getValue().longs());
                    row.put("short_liquidations_usd", dayEntry.getValue().shorts());
                }));
        result.set("coverage", coverage(markets, points, from, to, closedTo, asOf));
        return result;
    }

    private static ObjectNode coverage(
            MarketResolution markets, Map<String, Map<LocalDate, Liquidation>> points,
            LocalDate from, LocalDate to, LocalDate closedTo, Instant asOf) {
        ObjectNode coverage = JsonHashes.mapper().createObjectNode();
        coverage.put("from_inclusive", from.toString());
        coverage.put("to_exclusive", to.toString());
        coverage.put("closed_data_to_exclusive", closedTo.toString());
        coverage.put("as_of", asOf.toString());
        ObjectNode assets = coverage.putObject("by_asset");
        for (String asset : ASSETS) {
            LocalDate effectiveEnd = closedTo.isBefore(from) ? from : closedTo;
            long expected = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(from, effectiveEnd));
            Map<LocalDate, Liquidation> observed = points.getOrDefault(asset, Map.of());
            ArrayNode missingRanges = JsonHashes.mapper().createArrayNode();
            long missing = 0;
            LocalDate rangeStart = null;
            LocalDate prior = null;
            for (LocalDate day = from; day.isBefore(effectiveEnd); day = day.plusDays(1)) {
                if (!observed.containsKey(day)) {
                    missing++;
                    if (rangeStart == null) rangeStart = day;
                    prior = day;
                } else if (rangeStart != null) {
                    addMissingRange(missingRanges, rangeStart, prior.plusDays(1));
                    rangeStart = null;
                }
            }
            if (rangeStart != null) addMissingRange(missingRanges, rangeStart, prior.plusDays(1));
            ObjectNode stat = assets.putObject(asset);
            stat.put("expected_days", expected);
            stat.put("observed_days", observed.size());
            stat.put("missing_days", missing);
            stat.put("complete", expected > 0 && missing == 0);
            stat.put("status", expected == 0 ? "NO_CLOSED_DAYS" : missing == 0 ? "COMPLETE" : "INCOMPLETE");
            if (!markets.symbolByAsset().containsKey(asset)) stat.put("reason", "binance_stable_usdt_perpetual_not_listed");
            stat.set("missing_day_ranges", missingRanges);
        }
        return coverage;
    }

    private static void addMissingRange(ArrayNode ranges, LocalDate from, LocalDate to) {
        ObjectNode range = ranges.addObject();
        range.put("from_inclusive", from.toString());
        range.put("to_exclusive", to.toString());
    }

    private static MarketResolution resolveMarkets(JsonNode exchanges, JsonNode futureMarkets) {
        if (!exchanges.isArray() || !futureMarkets.isArray()) {
            throw new IllegalArgumentException("Coinalyze catalogues must be JSON arrays");
        }
        List<JsonNode> binance = new ArrayList<>();
        for (JsonNode exchange : exchanges) {
            String name = text(exchange, "name");
            if (name != null && name.equalsIgnoreCase("Binance")) binance.add(exchange);
        }
        if (binance.size() != 1) {
            throw new IllegalArgumentException(binance.isEmpty()
                    ? "Coinalyze Binance exchange is missing" : "Coinalyze Binance exchange is ambiguous");
        }
        String exchangeCode = requiredText(binance.get(0), "code");
        Map<String, List<JsonNode>> candidates = new LinkedHashMap<>();
        for (String asset : ASSETS) candidates.put(asset, new ArrayList<>());
        for (JsonNode market : futureMarkets) {
            if (!market.isObject()) continue;
            if (!exchangeCode.equals(text(market, "exchange"))) continue;
            String asset = upper(text(market, "base_asset"));
            if (!candidates.containsKey(asset)
                    || !"USDT".equals(upper(text(market, "quote_asset")))
                    || !market.path("is_perpetual").asBoolean(false)
                    || !"STABLE".equals(upper(text(market, "margined")))) continue;
            candidates.get(asset).add(market);
        }
        Map<String, String> symbols = new LinkedHashMap<>();
        for (String asset : ASSETS) {
            List<JsonNode> matching = candidates.get(asset);
            if (matching.size() > 1) {
                throw new IllegalArgumentException("Coinalyze Binance stable USDT perpetual is ambiguous for " + asset);
            }
            if (matching.size() == 1) symbols.put(asset, requiredText(matching.get(0), "symbol"));
        }
        return new MarketResolution(exchangeCode, symbols);
    }

    private static JsonNode historyForSymbol(JsonNode response, String symbol) {
        if (!response.isArray()) throw new IllegalArgumentException("Coinalyze liquidation response must be an array");
        if (response.isEmpty() || response.get(0).has("t")) return response;
        JsonNode match = null;
        Set<String> seen = new HashSet<>();
        for (JsonNode item : response) {
            String returned = requiredText(item, "symbol");
            if (!seen.add(returned)) throw new IllegalArgumentException("duplicate Coinalyze history symbol");
            if (!symbol.equals(returned)) {
                throw new IllegalArgumentException("Coinalyze history symbol does not match requested market");
            }
            match = item.path("history");
        }
        return match == null ? array() : match;
    }

    private static void validateHistoryRequest(JsonNode request, LocalDate from, LocalDate to, Instant asOf) {
        if (!"daily".equals(request.path("interval").asText())
                || !"true".equals(request.path("convert_to_usd").asText())) {
            throw new IllegalArgumentException("Coinalyze history receipt request is not daily USD data");
        }
        long requestFrom = requiredLong(request, "from");
        long requestTo = requiredLong(request, "to");
        if (requestFrom % 86_400L != 0 || requestTo % 86_400L != 0 || requestTo < requestFrom) {
            throw new IllegalArgumentException("Coinalyze history request bounds are invalid");
        }
        LocalDate requestStart = Instant.ofEpochSecond(requestFrom).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate requestEnd = Instant.ofEpochSecond(requestTo).atZone(ZoneOffset.UTC).toLocalDate().plusDays(1);
        if (requestStart.getYear() != requestEnd.minusDays(1).getYear()) {
            throw new IllegalArgumentException("Coinalyze history request is not bounded to one year");
        }
        LocalDate closedTo = min(to, asOf.atZone(ZoneOffset.UTC).toLocalDate());
        if (closedTo.isBefore(from)) closedTo = from;
        if (requestStart.isBefore(from) || requestEnd.isAfter(closedTo)) {
            throw new IllegalArgumentException("Coinalyze history request exceeds the closed acquisition window");
        }
        requestSymbols(request);
    }

    private static List<String> requestSymbols(JsonNode request) {
        JsonNode symbols = request.path("symbols");
        if (!symbols.isArray() || symbols.isEmpty()) {
            throw new IllegalArgumentException("Coinalyze history request symbols are missing");
        }
        List<String> values = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        symbols.forEach(node -> {
            if (!node.isTextual() || node.asText().isBlank() || !unique.add(node.asText())) {
                throw new IllegalArgumentException("Coinalyze history request symbols are invalid");
            }
            values.add(node.asText());
        });
        return values;
    }

    private static void validateExpectedSymbols(List<String> requested, Map<String, String> expected) {
        Set<String> known = new HashSet<>(expected.values());
        if (!known.equals(new HashSet<>(requested)) || requested.size() != known.size()) {
            throw new IllegalArgumentException("Coinalyze history request does not match qualified symbols");
        }
    }

    private static final class Fetcher {
        private final String apiKey;
        private final PublicDataAdapters.InjectableHttpClient client;
        private final Sleeper sleeper;
        private final RateLimiter limiter;

        private Fetcher(String apiKey, PublicDataAdapters.InjectableHttpClient client,
                        Sleeper sleeper, RateLimiter limiter) {
            this.apiKey = apiKey;
            this.client = client;
            this.sleeper = sleeper;
            this.limiter = limiter;
        }

        private JsonNode fetch(String endpoint, String kind, Map<String, String> query, int weight,
                              List<ObjectNode> refs, Path root, String relativePath) throws IOException {
            URI uri = URI.create(BASE + endpoint + encodeQuery(query));
            for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
                limiter.acquire(weight);
                PublicDataAdapters.FetchResponse response;
                try {
                    response = client.fetch(uri, Map.of(API_KEY_HEADER, apiKey, "Accept", "application/json"));
                } catch (Exception error) {
                    throw new IOException(sanitize(error.getMessage(), apiKey));
                }
                if (response.status() == 429) {
                    if (attempt == MAX_RETRIES) throw new IOException("Coinalyze rate limit retry limit exceeded");
                    long waitMillis = retryAfterMillis(response.firstHeader("Retry-After"));
                    try { sleeper.sleep(waitMillis); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Coinalyze retry wait interrupted");
                    }
                    continue;
                }
                if (response.status() >= 300 && response.status() < 400) {
                    throw new IOException("Coinalyze redirected; refusing to forward API key");
                }
                if (response.status() != 200) {
                    throw new IOException("Coinalyze " + endpoint + " returned HTTP " + response.status());
                }
                byte[] body = response.body();
                String bodyText = new String(body, StandardCharsets.UTF_8);
                if (bodyText.contains(apiKey)) throw new IOException("Coinalyze response unexpectedly contains credential material");
                JsonNode parsed;
                try { parsed = JsonHashes.parse(body, "Coinalyze " + endpoint + " response"); }
                catch (RuntimeException error) {
                    throw new IOException("Coinalyze " + endpoint + " returned invalid JSON");
                }
                Path output = root.resolve(relativePath);
                Files.write(output, body, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                ObjectNode ref = JsonHashes.mapper().createObjectNode();
                ref.put("kind", kind);
                ref.put("endpoint", endpoint);
                ObjectNode sanitizedRequest = ref.putObject("request");
                query.forEach((name, value) -> {
                    if ("symbols".equals(name)) {
                        ArrayNode array = sanitizedRequest.putArray(name);
                        for (String symbol : value.split(",")) array.add(symbol);
                    } else if ("from".equals(name) || "to".equals(name)) {
                        sanitizedRequest.put(name, Long.parseLong(value));
                    } else sanitizedRequest.put(name, value);
                });
                ref.put("path", relativePath);
                ref.put("sha256", JsonHashes.sha256(body));
                ref.put("status", response.status());
                ref.put("captured_at", Instant.now().toString());
                ref.put("bytes", body.length);
                refs.add(ref);
                return parsed;
            }
            throw new IOException("Coinalyze request failed");
        }
    }

    private static final class RateLimiter {
        private final Sleeper sleeper;
        private final LongSupplier nanoTime;
        private final Deque<Long> tokenTimes = new ArrayDeque<>();

        private RateLimiter(Sleeper sleeper, LongSupplier nanoTime) {
            this.sleeper = sleeper;
            this.nanoTime = nanoTime;
        }

        private void acquire(int weight) throws IOException {
            if (weight < 1 || weight > MAX_SYMBOL_CALLS_PER_MINUTE) {
                throw new IllegalArgumentException("Coinalyze request weight is outside rate limit bounds");
            }
            while (true) {
                long now = nanoTime.getAsLong();
                while (!tokenTimes.isEmpty() && now - tokenTimes.peekFirst() >= Duration.ofMinutes(1).toNanos()) {
                    tokenTimes.removeFirst();
                }
                if (tokenTimes.size() + weight <= MAX_SYMBOL_CALLS_PER_MINUTE) {
                    for (int i = 0; i < weight; i++) tokenTimes.addLast(now);
                    return;
                }
                long waitNanos = Duration.ofMinutes(1).toNanos() - (now - tokenTimes.peekFirst()) + 1_000_000L;
                long waitMillis = Math.max(1L, (waitNanos + 999_999L) / 1_000_000L);
                try { sleeper.sleep(waitMillis); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Coinalyze rate-limit wait interrupted");
                }
            }
        }
    }

    private static PublicDataAdapters.InjectableHttpClient defaultClient() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new PublicDataAdapters.JdkInjectableHttpClient(client);
    }

    private static void prepareNewRoot(Path root) throws IOException {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Coinalyze acquisition root must be a real directory");
            }
            try (var children = Files.list(root)) {
                if (children.findAny().isPresent()) {
                    throw new IllegalArgumentException("Coinalyze acquisition root is not empty; refusing overwrite");
                }
            }
        } else Files.createDirectories(root);
    }

    private static String encodeQuery(Map<String, String> query) {
        if (query.isEmpty()) return "";
        StringBuilder result = new StringBuilder("?");
        query.forEach((name, value) -> {
            if (result.length() > 1) result.append('&');
            result.append(URLEncoder.encode(name, StandardCharsets.UTF_8));
            result.append('=');
            result.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return result.toString();
    }

    private static long retryAfterMillis(String value) throws IOException {
        if (value == null) throw new IOException("Coinalyze 429 response omitted Retry-After");
        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds < 0 || seconds > 900) throw new NumberFormatException();
            return Math.max(1, seconds * 1000L);
        } catch (NumberFormatException invalid) {
            throw new IOException("Coinalyze Retry-After value is invalid or exceeds 900 seconds");
        }
    }

    private static String sanitize(String value, String secret) {
        if (value == null || value.isBlank()) return "Coinalyze request failed";
        return secret == null || secret.isEmpty() ? value : value.replace(secret, "[redacted]");
    }

    private static LocalDate date(String value, String name) {
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException invalid) { throw new IllegalArgumentException("Coinalyze " + name + " is invalid"); }
    }

    private static Instant instant(String value, String name) {
        try { return Instant.parse(value); }
        catch (DateTimeParseException invalid) { throw new IllegalArgumentException("Coinalyze " + name + " is invalid"); }
    }

    private static BigDecimalValue nonnegativeNumber(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isNumber()) throw new IllegalArgumentException("Coinalyze field " + field + " must be numeric");
        double finite = value.doubleValue();
        if (!Double.isFinite(finite) || finite < 0) {
            throw new IllegalArgumentException("Coinalyze field " + field + " must be finite and nonnegative");
        }
        return new BigDecimalValue(value.decimalValue());
    }

    private static long requiredLong(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("Coinalyze field " + field + " must be an integer");
        }
        return value.longValue();
    }

    private static String requiredText(JsonNode object, String field) {
        String value = text(object, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Coinalyze field " + field + " is missing");
        return value;
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String upper(String value) { return value == null ? "" : value.toUpperCase(Locale.ROOT); }
    private static LocalDate min(LocalDate left, LocalDate right) { return left.isBefore(right) ? left : right; }

    private static String endpointFor(String kind) {
        return switch (kind) {
            case "exchanges" -> "exchanges";
            case "future-markets" -> "future-markets";
            case "liquidation_history" -> "liquidation-history";
            default -> "";
        };
    }

    private static void requireEmptyRequest(JsonNode request) {
        if (!(request instanceof ObjectNode) || request.size() != 0) {
            throw new IllegalArgumentException("Coinalyze catalogue request metadata is invalid");
        }
    }

    private static JsonNode array() { return JsonHashes.mapper().createArrayNode(); }

    private static void mergeHistory(Map<String, JsonNode> accumulated, JsonNode response,
                                     List<String> expectedSymbols, LocalDate requestFrom, LocalDate requestTo) {
        if (!response.isArray()) throw new IllegalArgumentException("Coinalyze liquidation response must be an array");
        Set<String> expected = new LinkedHashSet<>(expectedSymbols);
        Set<String> seen = new HashSet<>();
        for (JsonNode item : response) {
            String symbol = requiredText(item, "symbol");
            if (!expected.contains(symbol)) throw new IllegalArgumentException("Coinalyze returned an unrequested symbol");
            if (!seen.add(symbol)) throw new IllegalArgumentException("duplicate Coinalyze history symbol");
            JsonNode history = item.path("history");
            if (!history.isArray()) throw new IllegalArgumentException("Coinalyze history field must be an array");
            for (JsonNode point : history) {
                long time = requiredLong(point, "t");
                if (Math.floorMod(time, 86_400L) != 0) {
                    throw new IllegalArgumentException("Coinalyze timestamp is not UTC midnight");
                }
                LocalDate day = Instant.ofEpochSecond(time).atZone(ZoneOffset.UTC).toLocalDate();
                if (day.isBefore(requestFrom) || !day.isBefore(requestTo)) {
                    throw new IllegalArgumentException("Coinalyze row is outside its bounded yearly request");
                }
            }
            JsonNode target = accumulated.computeIfAbsent(symbol, ignored -> array());
            if (!(target instanceof ArrayNode targetArray)) throw new IllegalArgumentException("invalid accumulated history");
            history.forEach(row -> targetArray.add(row.deepCopy()));
        }
    }

    private static ObjectNode readObject(byte[] bytes, String description) {
        JsonNode value = JsonHashes.parse(bytes, description);
        if (!(value instanceof ObjectNode object)) throw new IllegalArgumentException(description + " must be an object");
        return object.deepCopy();
    }

    private static String ownHash(ObjectNode object) {
        ObjectNode copy = object.deepCopy();
        copy.remove("content_sha256");
        return JsonHashes.canonicalSha256(copy);
    }

    private static byte[] pretty(JsonNode value) throws IOException {
        return (JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private record MarketResolution(String exchangeCode, Map<String, String> symbolByAsset) {}
    private record Liquidation(java.math.BigDecimal longs, java.math.BigDecimal shorts) {}
    private record BigDecimalValue(java.math.BigDecimal value) {}
}

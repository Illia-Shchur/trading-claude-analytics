package com.tradinganalytics.infrastructure.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.PathConfinement;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Public-first, PIT-labelled adapters ported from {@code public-data-adapters.mjs}. */
public final class PublicDataAdapters {
    public record Adapter(
            String adapterId, String pitTier, String pitProvenance, String revisionStatus,
            String availability, String venue, String instrument) {}

    public static final Map<String, Adapter> PUBLIC_ADAPTERS;
    public static final String ADAPTER_CODE_SHA256 =
            "004aacee2dd64a2ab96225a6df16e978b828ec6831f1eb30d4cc184ddb8c8c6d";
    public static final List<String> CORE_CRYPTO_ASSETS =
            List.of("btc", "eth", "sol", "bnb", "xrp", "ada", "link", "aave");

    private static final int MAX_ENTRIES = 10_000;
    private static final long HARD_MEMBER_BYTES = 512L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;
    private static final Pattern METRIC_NUMBER = Pattern.compile(
            "^[+-]?(?:(?:\\d+(?:\\.\\d*)?)|(?:\\.\\d+))(?:[eE][+-]?\\d+)?$");
    private static final List<String> METRIC_FIELDS = List.of(
            "open_interest", "open_interest_value", "top_trader_account_long_short_ratio",
            "top_trader_position_long_short_ratio", "global_long_short_ratio",
            "taker_buy_sell_volume_ratio");
    private static final DateTimeFormatter ISO_MILLIS =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    static {
        Map<String, Adapter> adapters = new LinkedHashMap<>();
        adapters.put("BINANCE_SPOT_OHLC", revised("binance-public-spot-ohlc/1", "binance", "spot", "close_time"));
        adapters.put("BINANCE_LINEAR_OHLC", revised("binance-public-linear-ohlc/1", "binance", "linear_perpetual", "close_time"));
        adapters.put("BINANCE_LINEAR_MARK_OHLC", revised("binance-public-linear-mark-ohlc/1", "binance", "linear_perpetual_mark", "close_time"));
        adapters.put("BINANCE_LINEAR_EXCHANGE_INFO", new Adapter(
                "binance-public-linear-exchange-info/1", "T2_CAPTURED_AS_OF", null, null,
                "response_time", "binance", "linear_contract_specification"));
        adapters.put("BINANCE_OPEN_INTEREST", new Adapter(
                "binance-public-open-interest/1", "T2_CAPTURED_AS_OF", null, null,
                "response_time", "binance", "linear_perpetual"));
        adapters.put("BINANCE_DATA_VISION_DATED_KLINES", revised(
                "binance-data-vision-dated-kline-archive/1", "binance",
                "linear_dated_future", "close_time"));
        adapters.put("BINANCE_DATA_VISION_METRICS", revised(
                "binance-data-vision-metrics-archive/1", "binance",
                "linear_perpetual_metrics", "create_time"));
        adapters.put("BINANCE_FUNDING_EVENTS", revised(
                "binance-public-funding-events/1", "binance", "linear_perpetual", "event_time"));
        adapters.put("ALTERNATIVE_ME_SENTIMENT", new Adapter(
                "alternative-me-public-sentiment/1", "T1_PUBLICATION_VINTAGE", null, null,
                "response_time", "alternative.me", "context"));
        adapters.put("ALFRED_FRED_VINTAGE", new Adapter(
                "alfred-fred-vintage/1", "T1_PUBLICATION_VINTAGE", null, null,
                "release_or_vintage_time", "fred", "context"));
        adapters.put("ONCHAIN_PROSPECTIVE_CAPTURE", new Adapter(
                "onchain-prospective-capture/1", "T2_CAPTURED_AS_OF", null, null,
                "captured_at", "public-endpoint", "context"));
        PUBLIC_ADAPTERS = Collections.unmodifiableMap(adapters);
    }

    private PublicDataAdapters() {}

    public record ZipMember(String name, byte[] bytes, int compressionMethod, long crc32) {
        public ZipMember { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public record DatedArchiveOptions(
            String asset, String symbol, String interval, Long startTime, Long endTime) {
        public DatedArchiveOptions {
            interval = interval == null ? "4h" : interval;
        }
    }

    public record MetricsArchiveOptions(String asset, String symbol, Long startTime, Long endTime) {}

    public record ParsedArchive(
            List<ObjectNode> rows, String archiveMember, String expiryAt, List<String> header,
            ObjectNode semanticMapping, List<String> metricFields) {
        public ParsedArchive { rows = immutableRows(rows); header = header == null ? List.of() : List.copyOf(header); }
        @Override public List<ObjectNode> rows() { return immutableRows(rows); }
    }

    public record MetricsAggregationOptions(
            String interval, Long startTime, Long endTime, List<String> requiredFields,
            Double minimumFieldCoverage) {
        public MetricsAggregationOptions { interval = interval == null ? "4h" : interval; }
    }

    public record AggregatedMetrics(List<ObjectNode> rows, ObjectNode coverage) {
        public AggregatedMetrics { rows = immutableRows(rows); coverage = coverage.deepCopy(); }
        @Override public List<ObjectNode> rows() { return immutableRows(rows); }
        @Override public ObjectNode coverage() { return coverage.deepCopy(); }
    }

    public record FetchResponse(int status, byte[] body, Map<String, List<String>> headers) {
        public FetchResponse {
            body = body == null ? new byte[0] : body.clone();
            headers = headers == null ? Map.of() : deepHeaders(headers);
        }
        @Override public byte[] body() { return body.clone(); }
        @Override public Map<String, List<String>> headers() { return deepHeaders(headers); }
        public String firstHeader(String name) {
            for (var entry : headers.entrySet()) if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue().isEmpty() ? null : entry.getValue().get(0);
            }
            return null;
        }
    }

    @FunctionalInterface
    public interface InjectableHttpClient {
        FetchResponse fetch(URI uri, Map<String, String> headers) throws Exception;
    }

    public static final class JdkInjectableHttpClient implements InjectableHttpClient {
        private final HttpClient client;
        public JdkInjectableHttpClient() {
            this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
        }
        public JdkInjectableHttpClient(HttpClient client) { this.client = client; }
        @Override public FetchResponse fetch(URI uri, Map<String, String> headers) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET();
            headers.forEach(builder::header);
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new FetchResponse(response.statusCode(), response.body(), response.headers().map());
        }
    }

    public record HttpOptions(
            InjectableHttpClient client, String capturedAt, boolean fixtureOnly, int retries,
            long retryDelayMillis) {
        public HttpOptions {
            client = client == null ? new JdkInjectableHttpClient() : client;
            retries = retries < 0 ? 3 : retries;
            retryDelayMillis = retryDelayMillis < 0 ? 250 : retryDelayMillis;
        }
        public static HttpOptions production() {
            return new HttpOptions(null, null, false, 3, 250);
        }
    }

    private record ObservedResponse(
            byte[] body, JsonNode json, String retrievedAt, String observedAt,
            String captureTimeSource, int status) {}

    public record Capture(
            String schema, String adapterId, String adapterCodeSha256, String pitTier,
            String pitProvenance, String revisionStatus, ObjectNode request,
            String responseSha256, byte[] responseBody, String capturedAt, String observedAt,
            String captureTimeSource, List<ObjectNode> rows) {
        public Capture {
            request = request.deepCopy(); responseBody = responseBody.clone(); rows = immutableRows(rows);
        }
        @Override public ObjectNode request() { return request.deepCopy(); }
        @Override public byte[] responseBody() { return responseBody.clone(); }
        @Override public List<ObjectNode> rows() { return immutableRows(rows); }
    }

    public record OhlcOptions(
            String asset, String symbolOverride, Long startTime, Long endTime, String interval,
            int limit, boolean linear, HttpOptions http) {
        public OhlcOptions {
            interval = interval == null ? "4h" : interval;
            limit = limit <= 0 ? 1000 : limit;
            http = http == null ? HttpOptions.production() : http;
        }
    }

    public record OpenInterestOptions(
            String asset, String period, Long startTime, Long endTime, int limit,
            boolean linear, HttpOptions http) {
        public OpenInterestOptions {
            period = period == null ? "4h" : period;
            limit = limit <= 0 ? 500 : limit;
            http = http == null ? HttpOptions.production() : http;
        }
    }

    public record FundingOptions(
            String asset, String symbolOverride, Long startTime, Long endTime, int limit,
            HttpOptions http) {
        public FundingOptions {
            limit = limit <= 0 ? 1000 : limit;
            http = http == null ? HttpOptions.production() : http;
        }
    }

    public record AlfredOptions(
            String seriesId, String apiKey, String realtimeStart, String realtimeEnd,
            HttpOptions http) {
        public AlfredOptions { http = http == null ? HttpOptions.production() : http; }
    }

    public record ArchiveFetchOptions(
            String asset, String symbol, String interval, String periodToken,
            Long startTime, Long endTime, HttpOptions http, Path rawOutputRoot) {
        public ArchiveFetchOptions {
            interval = interval == null ? "4h" : interval;
            http = http == null ? HttpOptions.production() : http;
        }
    }

    public record RawResponse(
            String sha256, byte[] body, String path, long bytes, ObjectNode request) {
        public RawResponse {
            body = body == null ? null : body.clone(); request = request.deepCopy();
        }
        @Override public byte[] body() { return body == null ? null : body.clone(); }
        @Override public ObjectNode request() { return request.deepCopy(); }
    }

    public record ArchiveCapture(
            List<ObjectNode> rows, String archiveMember, String expiryAt, List<String> header,
            ObjectNode semanticMapping, List<String> metricFields, List<String> responseSha256,
            List<RawResponse> rawResponses, String archiveSha256, String checksumSha256,
            String capturedAt, String adapterId) {
        public ArchiveCapture { rows = immutableRows(rows); rawResponses = List.copyOf(rawResponses); }
        @Override public List<ObjectNode> rows() { return immutableRows(rows); }
    }

    public record ArchiveBackfillOptions(
            String asset, String symbol, String interval, long startTime, long endTime,
            int maxFiles, HttpOptions http, Path rawOutputRoot, Path checkpointPath,
            String expectedCheckpointSha256, int concurrency, boolean forceReopen) {
        public ArchiveBackfillOptions {
            interval = interval == null ? "4h" : interval;
            maxFiles = maxFiles <= 0 ? 10_000 : maxFiles;
            http = http == null ? HttpOptions.production() : http;
            concurrency = concurrency <= 0 ? 2 : Math.min(8, concurrency);
        }
    }

    public record BackfillResult(
            List<ObjectNode> rows, List<RawResponse> rawResponses, List<String> responseSha256,
            String capturedAt, ObjectNode coverage, ObjectNode receipt) {
        public BackfillResult {
            rows = immutableRows(rows); rawResponses = List.copyOf(rawResponses);
            coverage = coverage.deepCopy(); receipt = receipt == null ? null : receipt.deepCopy();
        }
        @Override public List<ObjectNode> rows() { return immutableRows(rows); }
        @Override public ObjectNode coverage() { return coverage.deepCopy(); }
        @Override public ObjectNode receipt() { return receipt == null ? null : receipt.deepCopy(); }
    }

    @FunctionalInterface public interface PageFetcher { Page fetch(Long cursor, int pageSize, int page); }
    @FunctionalInterface public interface NextCursor { Long next(List<ObjectNode> rows, Long cursor); }
    public record Page(
            List<ObjectNode> rows, String responseSha256, byte[] responseBody,
            ObjectNode request, String capturedAt) {
        public Page { rows = immutableRows(rows); responseBody = responseBody == null ? null : responseBody.clone(); }
        @Override public List<ObjectNode> rows() { return immutableRows(rows); }
        @Override public byte[] responseBody() {
            return responseBody == null ? null : responseBody.clone();
        }
    }
    public record PaginationOptions(
            PageFetcher fetchPage, Long startCursor, Long endCursor, int pageSize,
            int maxPages, int maxRows, long rateLimitMillis, NextCursor nextCursor,
            ToLongFunction<ObjectNode> rowTime) {
        public PaginationOptions {
            pageSize = pageSize <= 0 ? 1000 : pageSize;
            maxPages = maxPages <= 0 ? 1000 : maxPages;
            maxRows = maxRows <= 0 ? 1_000_000 : maxRows;
            rowTime = rowTime == null ? row -> row.path("event_time").asLong() : rowTime;
        }
    }

    /** Strict central-directory ZIP parser with traversal, overlap, CRC and bomb bounds. */
    public static List<ZipMember> parseZipArchive(byte[] bytes) {
        return parseZipArchive(bytes, HARD_MEMBER_BYTES);
    }

    public static List<ZipMember> parseZipArchive(byte[] bytes, long maxMemberBytes) {
        byte[] body = bytes == null ? new byte[0] : bytes.clone();
        long memberLimit = Math.min(HARD_MEMBER_BYTES, maxMemberBytes);
        if (memberLimit < 1) throw failure("Binance archive decompression limit is invalid");
        int eocd = -1;
        for (int index = body.length - 22; index >= Math.max(0, body.length - 65_557); index--) {
            if (u32(body, index) == 0x06054b50L) { eocd = index; break; }
        }
        if (eocd < 0 || eocd + 22 > body.length) {
            throw failure("Binance archive is not a ZIP (missing EOCD)");
        }
        int disk = u16(body, eocd + 4);
        int centralDisk = u16(body, eocd + 6);
        int entries = u16(body, eocd + 10);
        long centralBytes = u32(body, eocd + 12);
        long centralOffset = u32(body, eocd + 16);
        long centralEnd = centralOffset + centralBytes;
        if (disk != 0 || centralDisk != 0 || entries == 0xffff || entries > MAX_ENTRIES
                || centralEnd > eocd || centralEnd < centralOffset) {
            throw failure("Binance archive uses unsupported multi-disk, ZIP64, or invalid central-directory bounds");
        }
        List<ZipMember> output = new ArrayList<>();
        List<long[]> ranges = new ArrayList<>();
        long cursor = centralOffset;
        long total = 0;
        for (int index = 0; index < entries; index++) {
            if (cursor < centralOffset || cursor + 46 > centralEnd
                    || u32(body, (int) cursor) != 0x02014b50L) {
                throw failure("Binance archive central directory is truncated or invalid");
            }
            int method = u16(body, (int) cursor + 10);
            long expectedCrc = u32(body, (int) cursor + 16);
            long compressedSize = u32(body, (int) cursor + 20);
            long uncompressedSize = u32(body, (int) cursor + 24);
            int nameLength = u16(body, (int) cursor + 28);
            int extraLength = u16(body, (int) cursor + 30);
            int commentLength = u16(body, (int) cursor + 32);
            long localOffset = u32(body, (int) cursor + 42);
            long centralRecordEnd = cursor + 46L + nameLength + extraLength + commentLength;
            if (centralRecordEnd > centralEnd) {
                throw failure("Binance archive central-directory record exceeds its declared bounds");
            }
            String name = safeArchiveName(new String(body, (int) cursor + 46, nameLength,
                    StandardCharsets.UTF_8));
            cursor = centralRecordEnd;
            if (localOffset + 30 > body.length || u32(body, (int) localOffset) != 0x04034b50L) {
                throw failure("Binance archive local header is invalid or out of bounds: " + name);
            }
            int localNameLength = u16(body, (int) localOffset + 26);
            int localExtraLength = u16(body, (int) localOffset + 28);
            long localNameStart = localOffset + 30;
            long dataStart = localNameStart + localNameLength + localExtraLength;
            if (dataStart > body.length || dataStart > centralOffset
                    || localNameStart + localNameLength > body.length) {
                throw failure("Binance archive local header fields exceed bounds: " + name);
            }
            String localName = new String(body, (int) localNameStart, localNameLength,
                    StandardCharsets.UTF_8);
            if (!localName.equals(name)) {
                throw failure("Binance archive central/local filename mismatch: " + name);
            }
            long dataEnd = dataStart + compressedSize;
            if (dataEnd > body.length || dataEnd > centralOffset || dataEnd < dataStart) {
                throw failure("Binance archive member is truncated or overlaps the central directory: " + name);
            }
            for (long[] range : ranges) if (dataStart < range[1] && dataEnd > range[0]) {
                throw failure("Binance archive members overlap: " + name);
            }
            ranges.add(new long[] { dataStart, dataEnd });
            if (uncompressedSize > memberLimit || compressedSize > memberLimit
                    || total + uncompressedSize > MAX_TOTAL_BYTES) {
                throw failure("Binance archive member exceeds bounded decompression limits: " + name);
            }
            byte[] compressed = java.util.Arrays.copyOfRange(body, (int) dataStart, (int) dataEnd);
            byte[] content;
            if (method == 0) content = compressed;
            else if (method == 8) content = inflate(compressed,
                    Math.min(memberLimit, MAX_TOTAL_BYTES - total), name);
            else throw failure("Binance archive compression method is unsupported for "
                    + name + ": " + method);
            CRC32 crc = new CRC32(); crc.update(content);
            if (content.length != uncompressedSize || crc.getValue() != expectedCrc) {
                throw failure("Binance archive member checksum/size mismatch: " + name);
            }
            total += content.length;
            output.add(new ZipMember(name, content, method, expectedCrc));
        }
        if (cursor != centralEnd) {
            throw failure("Binance archive central-directory entry count/length mismatch");
        }
        return List.copyOf(output);
    }

    public static ParsedArchive parseBinanceDatedKlineArchive(
            byte[] bytes, DatedArchiveOptions options) {
        if (options == null || !Set.of("1m", "1h", "4h", "1d").contains(options.interval())) {
            throw failure("unsupported dated archive interval "
                    + (options == null ? null : options.interval()));
        }
        String expiry = quarterExpiry(options.symbol());
        List<ZipMember> csv = parseZipArchive(bytes).stream()
                .filter(member -> member.name().toLowerCase(Locale.ROOT).endsWith(".csv")).toList();
        if (csv.size() != 1) throw failure(
                "dated kline archive must contain exactly one CSV member, found " + csv.size());
        List<ObjectNode> rows = new ArrayList<>();
        for (String line : new String(csv.get(0).bytes(), StandardCharsets.UTF_8).split("\\R")) {
            if (line.isEmpty()) continue;
            String[] fields = line.split(",", -1);
            if (fields.length == 0 || !fields[0].trim().matches("^\\d+$")) continue;
            if (fields.length < 12) throw failure(
                    "dated kline archive row has fewer than 12 Binance fields");
            long event = longNumber(fields[0]);
            if (options.startTime() != null && event < options.startTime()
                    || options.endTime() != null && event > options.endTime()) continue;
            long closeTime = longNumber(fields[6]);
            ObjectNode row = JsonHashes.mapper().createObjectNode();
            row.put("asset", lower(options.asset()));
            row.put("venue", "binance"); row.put("instrument", "linear_dated_future");
            row.put("symbol", upper(options.symbol())); row.put("contract_symbol", upper(options.symbol()));
            row.put("expiry_at", expiry);
            row.put("expiry_derivation", "BINANCE_QUARTERLY_SYMBOL_DATE_08:00Z");
            row.put("timeframe", options.interval()); row.put("series_role", "PRICE");
            row.put("event_time", event); row.put("close_time", closeTime);
            row.put("availability_time", closeTime); row.put("completed_bar", true);
            row.put("open", number(fields[1])); row.put("high", number(fields[2]));
            row.put("low", number(fields[3])); row.put("close", number(fields[4]));
            row.put("volume", number(fields[5])); row.put("quote_volume", number(fields[7]));
            row.put("trades", number(fields[8])); row.put("taker_buy_base_volume", number(fields[9]));
            row.put("taker_buy_quote_volume", number(fields[10])); row.put("archive_member", csv.get(0).name());
            rows.add(row);
        }
        rows.sort(Comparator.comparingLong(row -> row.path("event_time").asLong()));
        ensureUnique(rows, "event_time", "dated kline archive contains duplicate event times");
        long step = intervalMilliseconds(options.interval());
        for (int index = 1; index < rows.size(); index++) if (
                rows.get(index).path("event_time").asLong()
                        - rows.get(index - 1).path("event_time").asLong() != step) {
            throw failure("dated kline archive contains an internal cadence gap");
        }
        for (ObjectNode row : rows) if (!finite(row, "open", "high", "low", "close")
                || row.path("low").asDouble() > row.path("high").asDouble()
                || row.path("close_time").asLong() < row.path("event_time").asLong()) {
            throw failure("dated kline archive contains invalid OHLC data");
        }
        return new ParsedArchive(rows, csv.get(0).name(), expiry, null, null, null);
    }

    public static ParsedArchive parseBinanceMetricsArchive(
            byte[] bytes, MetricsArchiveOptions options) {
        List<ZipMember> csv = parseZipArchive(bytes).stream()
                .filter(member -> member.name().toLowerCase(Locale.ROOT).endsWith(".csv")).toList();
        if (csv.size() != 1) throw failure(
                "metrics archive must contain exactly one CSV member, found " + csv.size());
        List<List<String>> records = parseCsvRecords(
                new String(csv.get(0).bytes(), StandardCharsets.UTF_8));
        if (records.isEmpty()) return new ParsedArchive(
                List.of(), csv.get(0).name(), null, List.of(), null, null);
        List<String> header = records.remove(0).stream().map(String::trim).toList();
        if (header.stream().anyMatch(String::isEmpty)) {
            throw failure("metrics archive CSV header contains an empty field");
        }
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (String name : List.of("create_time", "symbol", "sum_open_interest",
                "sum_open_interest_value", "count_toptrader_long_short_ratio",
                "sum_toptrader_long_short_ratio", "count_long_short_ratio",
                "sum_taker_long_short_vol_ratio")) {
            int position = header.indexOf(name);
            if (position < 0) throw failure("metrics archive is missing required field " + name);
            indexes.put(name, position);
        }
        String expectedSymbol = symbolOverride(options.symbol());
        ObjectNode mapping = JsonHashes.mapper().createObjectNode();
        mapping.put("count_toptrader_long_short_ratio", "top_trader_account_long_short_ratio");
        mapping.put("sum_toptrader_long_short_ratio", "top_trader_position_long_short_ratio");
        mapping.put("count_long_short_ratio", "global_long_short_ratio");
        mapping.put("sum_taker_long_short_vol_ratio", "taker_buy_sell_volume_ratio");
        List<ObjectNode> rows = new ArrayList<>();
        for (List<String> values : records) {
            if (values.size() != header.size()) throw failure(values.size() < header.size()
                    ? "metrics archive row is truncated" : "metrics archive row has extra fields");
            String sourceSymbol = values.get(indexes.get("symbol")).trim().toUpperCase(Locale.ROOT);
            if (sourceSymbol.isEmpty() || !sourceSymbol.equals(expectedSymbol)) throw failure(
                    "metrics archive row symbol " + (sourceSymbol.isEmpty() ? "<blank>" : sourceSymbol)
                            + " does not match requested symbol " + expectedSymbol);
            long event = metricTimestamp(values.get(indexes.get("create_time")));
            if (options.startTime() != null && event < options.startTime()
                    || options.endTime() != null && event > options.endTime()) continue;
            ObjectNode row = JsonHashes.mapper().createObjectNode();
            row.put("asset", lower(options.asset())); row.put("venue", "binance");
            row.put("instrument", "linear_perpetual_metrics"); row.put("symbol", sourceSymbol);
            row.put("timeframe", "5m"); row.put("series_role", "METRICS");
            row.put("event_time", event); row.put("availability_time", event);
            row.put("availability_derivation", "ARCHIVE_CREATE_TIME_BOUND");
            putMetric(row, "open_interest", values.get(indexes.get("sum_open_interest")));
            putMetric(row, "open_interest_value", values.get(indexes.get("sum_open_interest_value")));
            putMetric(row, "top_trader_account_long_short_ratio",
                    values.get(indexes.get("count_toptrader_long_short_ratio")));
            putMetric(row, "top_trader_position_long_short_ratio",
                    values.get(indexes.get("sum_toptrader_long_short_ratio")));
            putMetric(row, "global_long_short_ratio",
                    values.get(indexes.get("count_long_short_ratio")));
            putMetric(row, "taker_buy_sell_volume_ratio",
                    values.get(indexes.get("sum_taker_long_short_vol_ratio")));
            row.set("metric_families", JsonHashes.mapper().valueToTree(List.of(
                    "OPEN_INTEREST", "TOP_TRADER_ACCOUNT_LS", "TOP_TRADER_POSITION_LS",
                    "GLOBAL_LS", "TAKER_BUY_SELL_RATIO")));
            row.put("deduplication_group", "BINANCE_UM_METRICS_5M");
            row.set("source_header", JsonHashes.mapper().valueToTree(header));
            row.put("source_header_sha256", JsonHashes.canonicalSha256(header));
            row.set("semantic_mapping", mapping);
            row.put("archive_member", csv.get(0).name());
            rows.add(row);
        }
        rows.sort(Comparator.comparingLong(row -> row.path("event_time").asLong()));
        ensureUnique(rows, "event_time", "metrics archive contains duplicate create_time observations");
        return new ParsedArchive(rows, csv.get(0).name(), null, header, mapping, METRIC_FIELDS);
    }

    public static AggregatedMetrics aggregateBinanceMetricsRows(
            List<ObjectNode> rows, MetricsAggregationOptions options) {
        options = options == null
                ? new MetricsAggregationOptions("4h", null, null, null, null) : options;
        long step = switch (options.interval()) {
            case "1h" -> 3_600_000L; case "4h" -> 14_400_000L; case "1d" -> 86_400_000L;
            default -> throw failure("metrics aggregation interval is unsupported: " + options.interval());
        };
        long nativeStep = 300_000L;
        List<String> required = options.requiredFields() == null
                ? METRIC_FIELDS : new ArrayList<>(new java.util.TreeSet<>(options.requiredFields()));
        for (String field : required) if (!METRIC_FIELDS.contains(field)) throw failure(
                "metrics aggregation required field is unknown: " + field);
        double minimum = options.minimumFieldCoverage() == null
                ? (options.requiredFields() == null ? 1 : .95) : options.minimumFieldCoverage();
        if (!Double.isFinite(minimum) || minimum < 0 || minimum > 1) {
            throw failure("metrics aggregation minimum field coverage is invalid");
        }
        long firstBucket = options.startTime() == null ? Long.MIN_VALUE
                : Math.floorDiv(options.startTime(), step) * step;
        long lastBucket = options.endTime() == null ? Long.MAX_VALUE
                : Math.floorDiv(options.endTime(), step) * step;
        Map<String, List<ObjectNode>> buckets = new TreeMap<>();
        Set<String> nativeEvents = new HashSet<>();
        for (ObjectNode row : rows.stream().sorted(
                Comparator.comparingLong(value -> value.path("event_time").asLong())).toList()) {
            if (!row.path("event_time").isNumber()) continue;
            long event = row.path("event_time").asLong();
            long bucket = Math.floorDiv(event, step) * step;
            if (bucket < firstBucket || bucket > lastBucket) continue;
            String symbol = row.path("symbol").asText().toUpperCase(Locale.ROOT);
            if (!nativeEvents.add(symbol + "|" + event)) {
                throw failure("metrics aggregation has duplicate native event " + event);
            }
            buckets.computeIfAbsent(symbol + "|" + bucket, ignored -> new ArrayList<>()).add(row);
        }
        int expectedPerBucket = (int) (step / nativeStep);
        List<ObjectNode> output = new ArrayList<>();
        ArrayNode missingBuckets = JsonHashes.mapper().createArrayNode();
        Map<String, ArrayNode> missingByField = new LinkedHashMap<>();
        Map<String, Integer> observedByField = new LinkedHashMap<>();
        Map<String, Integer> expectedByField = new LinkedHashMap<>();
        METRIC_FIELDS.forEach(field -> {
            missingByField.put(field, JsonHashes.mapper().createArrayNode());
            observedByField.put(field, 0); expectedByField.put(field, 0);
        });
        for (var entry : buckets.entrySet()) {
            long bucket = Long.parseLong(entry.getKey().substring(entry.getKey().lastIndexOf('|') + 1));
            List<ObjectNode> values = entry.getValue();
            values.sort(Comparator.comparingLong(row -> row.path("event_time").asLong()));
            boolean cadence = values.size() == expectedPerBucket;
            for (int index = 0; cadence && index < values.size(); index++) cadence =
                    values.get(index).path("event_time").asLong() == bucket + index * nativeStep;
            if (!cadence) { missingBuckets.add(iso(bucket)); continue; }
            ObjectNode aggregated = values.get(values.size() - 1).deepCopy();
            ArrayNode missingFields = aggregated.putArray("metric_missing_fields");
            ObjectNode fieldCoverage = aggregated.putObject("metric_field_coverage");
            for (String field : METRIC_FIELDS) {
                List<JsonNode> available = values.stream().map(row -> row.get(field))
                        .filter(value -> value != null && value.isNumber()
                                && Double.isFinite(value.asDouble())).toList();
                observedByField.compute(field, (ignored, count) -> count + available.size());
                expectedByField.compute(field, (ignored, count) -> count + expectedPerBucket);
                ObjectNode coverage = fieldCoverage.putObject(field);
                coverage.put("observed", available.size()); coverage.put("expected", expectedPerBucket);
                coverage.put("fraction", (double) available.size() / expectedPerBucket);
                if (available.isEmpty()) aggregated.putNull(field);
                else aggregated.set(field, available.get(available.size() - 1));
                if (available.size() != expectedPerBucket) {
                    missingFields.add(field);
                    missingByField.get(field).add(iso(bucket));
                }
            }
            aggregated.put("timeframe", options.interval()); aggregated.put("event_time", bucket);
            aggregated.put("close_time", bucket + step - nativeStep);
            aggregated.put("availability_time",
                    values.get(values.size() - 1).path("availability_time").asLong());
            aggregated.put("completed_bar", true);
            aggregated.put("aggregation_policy", "LAST_COMPLETED_5M_OBSERVATION_IN_UTC_BUCKET");
            aggregated.put("native_observations", expectedPerBucket);
            output.add(aggregated);
        }
        output.sort(Comparator.comparingLong(row -> row.path("event_time").asLong()));
        ObjectNode coverage = JsonHashes.mapper().createObjectNode();
        long first = output.isEmpty() ? Long.MIN_VALUE : output.get(0).path("event_time").asLong();
        long last = output.isEmpty() ? Long.MIN_VALUE
                : output.get(output.size() - 1).path("event_time").asLong();
        long expectedBuckets = options.startTime() != null && options.endTime() != null
                ? Math.floorDiv(options.endTime() - options.startTime(), step) + 1 : output.size();
        ObjectNode fields = coverage.putObject("field_coverage");
        boolean requiredComplete = true;
        ArrayNode requiredCoverage = JsonHashes.mapper().createArrayNode();
        for (String field : METRIC_FIELDS) {
            int observed = observedByField.get(field), expected = expectedByField.get(field);
            ObjectNode value = fields.putObject(field);
            value.put("observed", observed); value.put("expected", expected);
            value.put("fraction", expected == 0 ? 0 : (double) observed / expected);
            value.set("missing_buckets", missingByField.get(field));
            if (required.contains(field)) {
                ObjectNode copy = value.deepCopy(); copy.put("field", field); requiredCoverage.add(copy);
                requiredComplete &= value.path("fraction").asDouble() >= minimum;
            }
        }
        boolean boundary = output.isEmpty() || options.startTime() == null || options.endTime() == null
                || (first == Math.floorDiv(options.startTime(), step) * step
                    && last == Math.floorDiv(options.endTime(), step) * step);
        coverage.put("complete", missingBuckets.isEmpty() && output.size() == expectedBuckets
                && requiredComplete && boundary);
        coverage.put("aggregation", "LAST_COMPLETED_5M_OBSERVATION_IN_UTC_BUCKET");
        coverage.put("native_step_ms", nativeStep); coverage.put("expected_step_ms", step);
        coverage.put("expected_rows", expectedBuckets); coverage.put("observed_rows", output.size());
        coverage.set("missing_buckets", missingBuckets);
        coverage.set("required_metric_fields", JsonHashes.mapper().valueToTree(required));
        coverage.put("minimum_field_coverage", minimum);
        coverage.set("required_field_coverage", requiredCoverage);
        if (output.isEmpty()) { coverage.putNull("first_event_time"); coverage.putNull("last_event_time"); }
        else { coverage.put("first_event_time", first); coverage.put("last_event_time", last); }
        return new AggregatedMetrics(output, coverage);
    }

    public static Capture fetchBinanceOhlc(OhlcOptions options) {
        Adapter adapter = PUBLIC_ADAPTERS.get(options.linear() ? "BINANCE_LINEAR_OHLC" : "BINANCE_SPOT_OHLC");
        String endpoint = options.linear() ? "https://fapi.binance.com/fapi/v1/klines"
                : "https://api.binance.com/api/v3/klines";
        return fetchKlines(options, adapter, endpoint, false);
    }

    public static Capture fetchBinanceMarkPriceOhlc(OhlcOptions options) {
        return fetchKlines(options, PUBLIC_ADAPTERS.get("BINANCE_LINEAR_MARK_OHLC"),
                "https://fapi.binance.com/fapi/v1/markPriceKlines", true);
    }

    public static Capture fetchBinanceExchangeInfo(HttpOptions options) {
        Adapter adapter = PUBLIC_ADAPTERS.get("BINANCE_LINEAR_EXCHANGE_INFO");
        String endpoint = "https://fapi.binance.com/fapi/v1/exchangeInfo";
        ObservedResponse response = request(endpoint, options == null ? HttpOptions.production() : options,
                "application/json");
        List<ObjectNode> rows = new ArrayList<>();
        for (JsonNode source : response.json().path("symbols")) {
            ObjectNode row = source.deepCopy(); row.put("venue", adapter.venue());
            row.put("source", adapter.adapterId()); row.put("availability_time", response.retrievedAt());
            row.put("pit_tier", adapter.pitTier()); rows.add(row);
        }
        return capture(adapter, endpoint, Map.of(), response, rows, true);
    }

    public static Capture fetchBinanceOpenInterest(OpenInterestOptions options) {
        if (!options.linear()) throw failure("open-interest adapter requires Binance linear futures");
        Adapter adapter = PUBLIC_ADAPTERS.get("BINANCE_OPEN_INTEREST");
        String endpoint = "https://fapi.binance.com/futures/data/openInterestHist";
        Map<String, String> params = params("symbol", symbol(options.asset()), "period", options.period(),
                "limit", String.valueOf(Math.min(500, Math.max(1, options.limit()))));
        optional(params, "startTime", options.startTime()); optional(params, "endTime", options.endTime());
        ObservedResponse response = request(url(endpoint, params), options.http(), "application/json");
        List<ObjectNode> rows = new ArrayList<>();
        for (JsonNode value : response.json()) {
            ObjectNode row = JsonHashes.mapper().createObjectNode();
            row.put("asset", lower(options.asset())); row.put("venue", adapter.venue());
            row.put("instrument", adapter.instrument()); row.put("timeframe", options.period());
            row.put("event_time", number(value.get("timestamp")));
            row.put("availability_time", Instant.parse(response.retrievedAt()).toEpochMilli());
            row.put("open_interest", number(value.get("sumOpenInterest")));
            row.put("open_interest_value", number(value.get("sumOpenInterestValue")));
            row.put("source", adapter.adapterId()); row.put("pit_tier", adapter.pitTier());
            rows.add(row);
        }
        return capture(adapter, endpoint, params, response, rows);
    }

    public static Capture fetchBinanceFundingEvents(FundingOptions options) {
        Adapter adapter = PUBLIC_ADAPTERS.get("BINANCE_FUNDING_EVENTS");
        String endpoint = "https://fapi.binance.com/fapi/v1/fundingRate";
        String requested = options.symbolOverride() == null
                ? symbol(options.asset()) : symbolOverride(options.symbolOverride());
        Map<String, String> params = params("symbol", requested, "limit",
                String.valueOf(Math.min(1000, Math.max(1, options.limit()))));
        optional(params, "startTime", options.startTime()); optional(params, "endTime", options.endTime());
        ObservedResponse response = request(url(endpoint, params), options.http(), "application/json");
        List<ObjectNode> rows = new ArrayList<>();
        for (JsonNode value : response.json()) {
            String sourceSymbol = value.path("symbol").asText().trim().toUpperCase(Locale.ROOT);
            if (sourceSymbol.isEmpty() || !sourceSymbol.equals(requested)) throw failure(
                    "funding response row symbol " + (sourceSymbol.isEmpty() ? "<blank>" : sourceSymbol)
                            + " does not match requested symbol " + requested);
            long fundingTime = value.path("fundingTime").asLong();
            ObjectNode row = JsonHashes.mapper().createObjectNode();
            row.put("asset", options.asset() == null ? lower(requested.substring(0, requested.length() - 4))
                    : lower(options.asset()));
            row.put("symbol", requested); row.put("venue", adapter.venue());
            row.put("instrument", adapter.instrument()); row.put("interval", "event");
            row.put("timeframe", "event"); row.put("event_time", fundingTime);
            row.put("raw_event_time", fundingTime); row.put("availability_time", fundingTime);
            row.put("funding_rate", number(value.get("fundingRate")));
            Double mark = positiveOrNull(value.get("markPrice"));
            if (mark == null) { row.putNull("settlement_mark"); row.putNull("mark_price"); }
            else { row.put("settlement_mark", mark); row.put("mark_price", mark); }
            row.put("event_id", sourceSymbol + ":" + fundingTime);
            row.put("rate_type", value.path("rateType").asText("Regular"));
            row.put("source", adapter.adapterId()); row.put("pit_tier", adapter.pitTier());
            rows.add(row);
        }
        return capture(adapter, endpoint, params, response, rows);
    }

    public static Capture fetchAlternativeSentiment(int limit, HttpOptions options) {
        Adapter adapter = PUBLIC_ADAPTERS.get("ALTERNATIVE_ME_SENTIMENT");
        String endpoint = "https://api.alternative.me/fng/?limit=" + limit + "&format=json";
        ObservedResponse response = request(endpoint, options == null ? HttpOptions.production() : options,
                "application/json");
        List<ObjectNode> rows = new ArrayList<>();
        for (JsonNode value : response.json().path("data")) {
            ObjectNode row = JsonHashes.mapper().createObjectNode();
            row.put("asset", "crypto-market"); row.put("asset_class", "context");
            row.put("event_time", value.path("timestamp").asLong() * 1000);
            row.put("availability_time", Instant.parse(response.retrievedAt()).toEpochMilli());
            row.put("value", value.path("value").asDouble());
            row.put("classification", value.path("value_classification").asText());
            row.put("source", adapter.adapterId()); row.put("pit_tier", adapter.pitTier());
            rows.add(row);
        }
        return capture(adapter, endpoint, Map.of(), response, rows);
    }

    public static Capture fetchAlfredVintage(AlfredOptions options) {
        if (options.seriesId() == null || options.apiKey() == null) {
            throw failure("ALFRED/FRED adapter requires series_id and API key");
        }
        Adapter adapter = PUBLIC_ADAPTERS.get("ALFRED_FRED_VINTAGE");
        String endpoint = "https://api.stlouisfed.org/fred/series/observations";
        Map<String, String> params = params("series_id", options.seriesId(), "api_key",
                options.apiKey(), "file_type", "json");
        if (options.realtimeStart() != null) params.put("realtime_start", options.realtimeStart());
        if (options.realtimeEnd() != null) params.put("realtime_end", options.realtimeEnd());
        ObservedResponse response = request(url(endpoint, params), options.http(), "application/json");
        List<ObjectNode> rows = new ArrayList<>();
        for (JsonNode value : response.json().path("observations")) {
            Vintage vintage = vintage(value.path("realtime_start").asText());
            Long vintageEnd = parseTimestamp(value.path("realtime_end").asText());
            ObjectNode row = JsonHashes.mapper().createObjectNode();
            row.put("asset", options.seriesId()); row.put("asset_class", "context");
            row.put("event_time", requireTimestamp(value.path("date").asText()));
            row.put("release_time", vintage.vintage()); row.put("vintage_time", vintage.vintage());
            if (vintageEnd == null) row.putNull("vintage_end_time"); else row.put("vintage_end_time", vintageEnd);
            row.put("release_vintage_precision", vintage.precision());
            row.put("availability_time", vintage.time());
            if (".".equals(value.path("value").asText())) row.putNull("value");
            else row.put("value", value.path("value").asDouble());
            row.put("source", adapter.adapterId()); row.put("pit_tier", adapter.pitTier()); rows.add(row);
        }
        Map<String, String> redacted = new LinkedHashMap<>(params); redacted.put("api_key", "REDACTED");
        return capture(adapter, endpoint, redacted, response, rows);
    }

    public static ArchiveCapture fetchBinanceDatedKlineArchive(ArchiveFetchOptions options) {
        String token = upper(options.symbol()) + "-" + options.interval() + "-" + options.periodToken();
        String base = "https://data.binance.vision/data/futures/um/monthly/klines/"
                + upper(options.symbol()) + "/" + options.interval() + "/" + token;
        return fetchArchivePair(options, base + ".zip", base + ".zip.CHECKSUM", true);
    }

    public static ArchiveCapture fetchBinanceMetricsArchive(ArchiveFetchOptions options) {
        String requested = options.symbol() == null ? upper(options.asset()) + "USDT"
                : symbolOverride(options.symbol());
        String token = requested + "-metrics-" + options.periodToken();
        String base = "https://data.binance.vision/data/futures/um/daily/metrics/"
                + requested + "/" + token;
        ArchiveFetchOptions normalized = new ArchiveFetchOptions(
                options.asset(), requested, "5m", options.periodToken(), options.startTime(),
                options.endTime(), options.http(), options.rawOutputRoot());
        return fetchArchivePair(normalized, base + ".zip", base + ".zip.CHECKSUM", false);
    }

    public static BackfillResult backfillBinanceDatedKlineArchives(ArchiveBackfillOptions options) {
        if (options.startTime() > options.endTime()) throw failure("dated archive bounds are invalid");
        List<String> months = monthStrings(options.startTime(), options.endTime());
        if (months.size() > options.maxFiles()) throw failure("dated archive month bound exceeded");
        return backfillArchives(options, months, true);
    }

    public static BackfillResult backfillBinanceMetricsArchives(ArchiveBackfillOptions options) {
        if (options.startTime() > options.endTime()) throw failure("metrics archive bounds are invalid");
        String requested = options.symbol() == null || options.symbol().isBlank()
                ? upper(options.asset()) + "USDT" : symbolOverride(options.symbol());
        ArchiveBackfillOptions normalized = new ArchiveBackfillOptions(
                options.asset(), requested, "5m", options.startTime(), options.endTime(),
                options.maxFiles(), options.http(), options.rawOutputRoot(), options.checkpointPath(),
                options.expectedCheckpointSha256(), options.concurrency(), options.forceReopen());
        List<String> days = dayStrings(normalized.startTime(), normalized.endTime());
        if (days.size() > options.maxFiles()) throw failure("metrics archive file bound exceeded");
        return backfillArchives(normalized, days, false);
    }

    public static BackfillResult paginatePublic(PaginationOptions options) {
        if (options == null || options.fetchPage() == null || options.nextCursor() == null) {
            throw failure("paginatePublic requires fetchPage and nextCursor");
        }
        List<ObjectNode> rows = new ArrayList<>();
        ArrayNode pages = JsonHashes.mapper().createArrayNode();
        List<byte[]> bodies = new ArrayList<>();
        List<RawResponse> rawResponses = new ArrayList<>();
        List<String> captures = new ArrayList<>();
        Long cursor = options.startCursor();
        boolean complete = false;
        for (int pageIndex = 0; pageIndex < options.maxPages()
                && rows.size() < options.maxRows(); pageIndex++) {
            Page page = options.fetchPage().fetch(cursor, options.pageSize(), pageIndex);
            List<ObjectNode> pageRows = page == null ? List.of() : page.rows();
            ObjectNode receiptPage = pages.addObject();
            receiptPage.put("page", pageIndex);
            if (cursor == null) receiptPage.putNull("cursor"); else receiptPage.put("cursor", cursor);
            receiptPage.put("row_count", pageRows.size());
            putNullable(receiptPage, "response_sha256", page == null ? null : page.responseSha256());
            JsonNode request = page == null ? null : page.request();
            putNullable(receiptPage, "endpoint", request == null ? null : request.path("endpoint").asText(null));
            putNullable(receiptPage, "symbol", request == null ? null : request.path("params").path("symbol").asText(null));
            putNullable(receiptPage, "interval", request == null ? null : request.path("params").path("interval").asText(null));
            bodies.add(page == null ? null : page.responseBody());
            if (page != null && page.responseSha256() != null && page.responseBody() != null) {
                ObjectNode rawRequest = JsonHashes.mapper().createObjectNode();
                putNullable(rawRequest, "endpoint", request == null
                        ? null : request.path("endpoint").asText(null));
                putNullable(rawRequest, "symbol", request == null
                        ? null : request.path("params").path("symbol").asText(null));
                putNullable(rawRequest, "interval", request == null
                        ? null : request.path("params").path("interval").asText(null));
                rawResponses.add(new RawResponse(page.responseSha256(), page.responseBody(), null,
                        page.responseBody().length, rawRequest));
            }
            if (page != null && page.capturedAt() != null) captures.add(page.capturedAt());
            if (pageRows.isEmpty()) { complete = true; break; }
            int remaining = Math.max(0, options.maxRows() - rows.size());
            rows.addAll(pageRows.subList(0, Math.min(remaining, pageRows.size())));
            long lastTime = options.rowTime().applyAsLong(pageRows.get(pageRows.size() - 1));
            Long advanced = options.nextCursor().next(pageRows, cursor);
            if (options.rateLimitMillis() > 0) try {
                Thread.sleep(options.rateLimitMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw failure("pagination interrupted", interrupted);
            }
            if (advanced == null || cursor != null && advanced <= cursor
                    || options.endCursor() != null && lastTime >= options.endCursor()
                    || pageRows.size() < options.pageSize()) {
                complete = options.endCursor() == null || options.endCursor() != null
                        && lastTime >= options.endCursor() || pageRows.size() < options.pageSize();
                break;
            }
            cursor = advanced;
        }
        ObjectNode coverage = JsonHashes.mapper().createObjectNode();
        putNullable(coverage, "start_cursor", options.startCursor());
        putNullable(coverage, "end_cursor", options.endCursor());
        if (rows.isEmpty()) { coverage.putNull("first_event_time"); coverage.putNull("last_event_time"); }
        else {
            coverage.put("first_event_time", options.rowTime().applyAsLong(rows.get(0)));
            coverage.put("last_event_time", options.rowTime().applyAsLong(rows.get(rows.size() - 1)));
        }
        coverage.put("observed_rows", rows.size()); coverage.put("pages", pages.size());
        coverage.put("max_pages", options.maxPages()); coverage.put("complete", complete);
        coverage.put("bounded", true);
        ObjectNode receipt = paginationReceipt(pages, coverage, options.rateLimitMillis());
        List<String> responseHashes = new ArrayList<>();
        pages.forEach(page -> { if (page.hasNonNull("response_sha256")) {
            responseHashes.add(page.path("response_sha256").asText());
        }});
        return new BackfillResult(rows, rawResponses, responseHashes,
                latestCaptureTime(captures), coverage, receipt);
    }

    public static BackfillResult backfillBinanceOhlc(
            OhlcOptions fetch, Long startTime, Long endTime, int pageSize,
            int maxPages, int maxRows, long rateLimitMillis) {
        long step = intervalMilliseconds(fetch.interval());
        return paginatePublic(new PaginationOptions((cursor, size, page) -> {
            Capture capture = fetchBinanceOhlc(new OhlcOptions(
                    fetch.asset(), fetch.symbolOverride(), cursor, endTime, fetch.interval(),
                    Math.min(1000, pageSize), fetch.linear(), fetch.http()));
            return page(capture);
        }, startTime, endTime, Math.min(1000, pageSize), maxPages, maxRows,
                rateLimitMillis, (rows, cursor) ->
                    rows.get(rows.size() - 1).path("event_time").asLong() + step, null));
    }

    public static BackfillResult backfillBinanceMarkPriceOhlc(
            OhlcOptions fetch, Long startTime, Long endTime, int pageSize,
            int maxPages, int maxRows, long rateLimitMillis) {
        long step = intervalMilliseconds(fetch.interval());
        return paginatePublic(new PaginationOptions((cursor, size, page) -> {
            Capture capture = fetchBinanceMarkPriceOhlc(new OhlcOptions(
                    fetch.asset(), fetch.symbolOverride(), cursor, endTime, fetch.interval(),
                    Math.min(1000, pageSize), true, fetch.http()));
            return page(capture);
        }, startTime, endTime, Math.min(1000, pageSize), maxPages, maxRows,
                rateLimitMillis, (rows, cursor) ->
                    rows.get(rows.size() - 1).path("event_time").asLong() + step, null));
    }

    public static BackfillResult backfillBinanceFunding(
            FundingOptions fetch, Long startTime, Long endTime, int pageSize,
            int maxPages, int maxRows, long rateLimitMillis) {
        return paginatePublic(new PaginationOptions((cursor, size, page) -> {
            Capture capture = fetchBinanceFundingEvents(new FundingOptions(
                    fetch.asset(), fetch.symbolOverride(), cursor, endTime,
                    Math.min(1000, pageSize), fetch.http()));
            return page(capture);
        }, startTime, endTime, Math.min(1000, pageSize), maxPages, maxRows,
                rateLimitMillis, (rows, cursor) ->
                    rows.get(rows.size() - 1).path("event_time").asLong() + 1, null));
    }

    public static BackfillResult backfillBinanceOpenInterest(
            OpenInterestOptions fetch, Long startTime, Long endTime, int pageSize,
            int maxPages, int maxRows, long rateLimitMillis) {
        long step = intervalMilliseconds(fetch.period());
        return paginatePublic(new PaginationOptions((cursor, size, page) -> {
            Capture capture = fetchBinanceOpenInterest(new OpenInterestOptions(
                    fetch.asset(), fetch.period(), cursor, endTime, Math.min(500, pageSize),
                    true, fetch.http()));
            return page(capture);
        }, startTime, endTime, Math.min(500, pageSize), maxPages, maxRows,
                rateLimitMillis, (rows, cursor) ->
                    rows.get(rows.size() - 1).path("event_time").asLong() + step, null));
    }

    public static ObjectNode prospectiveCapture(
            String sourceId, String endpoint, String asset, String capturedAt,
            JsonNode payload, ObjectNode metadata, Path out) {
        if (sourceId == null || endpoint == null || payload == null) {
            throw failure("prospective capture requires source_id, endpoint and payload");
        }
        ObjectNode record = JsonHashes.mapper().createObjectNode();
        record.put("schema", "prospective-capture/1");
        record.put("adapter_id", PUBLIC_ADAPTERS.get("ONCHAIN_PROSPECTIVE_CAPTURE").adapterId());
        record.put("source_id", sourceId); record.put("endpoint", endpoint);
        record.put("asset", asset == null ? "context" : asset); record.put("asset_class", "context");
        String time = capturedAt == null ? iso(System.currentTimeMillis()) : capturedAt;
        record.put("captured_at", time); record.put("availability_time", time);
        record.set("request_metadata", metadata == null
                ? JsonHashes.mapper().createObjectNode() : metadata.deepCopy());
        record.set("payload", payload.deepCopy()); record.put("payload_sha256", JsonHashes.canonicalSha256(payload));
        record.put("pit_tier", "T2_CAPTURED_AS_OF");
        if (out != null) {
            try {
                Files.createDirectories(out.toAbsolutePath().normalize().getParent());
                Files.write(out, pretty(record), java.nio.file.StandardOpenOption.CREATE_NEW);
                record.put("path", out.toAbsolutePath().normalize().toString());
            } catch (IOException error) { throw failure(error.getMessage(), error); }
        }
        return record;
    }

    private static Capture fetchKlines(
            OhlcOptions options, Adapter adapter, String endpoint, boolean mark) {
        String requested = options.symbolOverride() == null ? symbol(options.asset())
                : symbolOverride(options.symbolOverride());
        Map<String, String> params = params("symbol", requested, "interval", options.interval(),
                "limit", String.valueOf(Math.min(1000, Math.max(1, options.limit()))));
        optional(params, "startTime", options.startTime()); optional(params, "endTime", options.endTime());
        ObservedResponse response = request(url(endpoint, params), options.http(), "application/json");
        long captureTime = Instant.parse(response.retrievedAt()).toEpochMilli();
        List<ObjectNode> rows = new ArrayList<>();
        for (JsonNode value : response.json()) {
            long closeTime = value.path(6).asLong();
            if (closeTime > captureTime) continue;
            ObjectNode row = JsonHashes.mapper().createObjectNode();
            row.put("asset", options.asset() == null
                    ? lower(requested.substring(0, requested.length() - 4)) : lower(options.asset()));
            row.put("symbol", requested); row.put("venue", adapter.venue());
            row.put("instrument", adapter.instrument());
            if (mark) row.put("series_role", "MARK");
            row.put("interval", options.interval()); row.put("timeframe", options.interval());
            row.put("event_time", value.path(0).asLong()); row.put("close_time", closeTime);
            row.put("completed_bar", true); row.put("availability_time", closeTime);
            double open = value.path(1).asDouble(), high = value.path(2).asDouble();
            double low = value.path(3).asDouble(), close = value.path(4).asDouble();
            if (mark) {
                row.put("mark_open", open); row.put("mark_high", high); row.put("mark_low", low);
                row.put("mark_close", close); row.put("price", close);
            }
            row.put("open", open); row.put("high", high); row.put("low", low); row.put("close", close);
            if (!mark) {
                row.put("volume", value.path(5).asDouble());
                if (value.size() > 7 && finite(value.get(7))) row.put("quote_volume", value.get(7).asDouble());
                if (value.size() > 8 && finite(value.get(8))) row.put("trades", value.get(8).asDouble());
            }
            row.put("source", adapter.adapterId()); row.put("pit_tier", adapter.pitTier()); rows.add(row);
        }
        return capture(adapter, endpoint, params, response, rows);
    }

    private static Capture capture(
            Adapter adapter, String endpoint, Map<String, String> params,
            ObservedResponse response, List<ObjectNode> sourceRows) {
        return capture(adapter, endpoint, params, response, sourceRows, false);
    }

    private static Capture capture(
            Adapter adapter, String endpoint, Map<String, String> params,
            ObservedResponse response, List<ObjectNode> sourceRows, boolean includeEmptyParams) {
        List<ObjectNode> rows = new ArrayList<>();
        for (ObjectNode source : sourceRows) {
            ObjectNode row = source.deepCopy(); row.put("adapter_code_sha256", ADAPTER_CODE_SHA256);
            row.put("pit_tier", row.path("pit_tier").asText(adapter.pitTier()));
            putNullable(row, "pit_provenance", row.hasNonNull("pit_provenance")
                    ? row.path("pit_provenance").asText() : adapter.pitProvenance());
            putNullable(row, "revision_status", row.hasNonNull("revision_status")
                    ? row.path("revision_status").asText() : adapter.revisionStatus());
            rows.add(row);
        }
        ObjectNode request = JsonHashes.mapper().createObjectNode();
        request.put("endpoint", endpoint);
        if (includeEmptyParams || !params.isEmpty()) {
            request.set("params", JsonHashes.mapper().valueToTree(params));
        }
        return new Capture("public-data-capture/1", adapter.adapterId(), ADAPTER_CODE_SHA256,
                adapter.pitTier(), adapter.pitProvenance(), adapter.revisionStatus(), request,
                JsonHashes.sha256(response.body()), response.body(), response.retrievedAt(),
                response.observedAt(), response.captureTimeSource(), rows);
    }

    private static ArchiveCapture fetchArchivePair(
            ArchiveFetchOptions options, String zipUrl, String checksumUrl, boolean dated) {
        ObservedResponse archive = archiveRequest(zipUrl, options.http());
        ObservedResponse checksum = archiveRequest(checksumUrl, options.http());
        String archiveHash = JsonHashes.sha256(archive.body());
        String checksumHash = JsonHashes.sha256(checksum.body());
        if (!checksumValue(checksum.body()).equals(archiveHash)) throw failure(
                (dated ? "dated archive CHECKSUM mismatch for " : "metrics archive CHECKSUM mismatch for ")
                        + options.periodToken());
        RawResponse zip = rawResponse(options.rawOutputRoot(), archive.body(), zipUrl,
                options, "ARCHIVE_ZIP", archiveHash);
        RawResponse sum = rawResponse(options.rawOutputRoot(), checksum.body(), checksumUrl,
                options, "ARCHIVE_CHECKSUM", checksumHash);
        ParsedArchive parsed = dated
                ? parseBinanceDatedKlineArchive(archive.body(), new DatedArchiveOptions(
                        options.asset(), options.symbol(), options.interval(), options.startTime(),
                        options.endTime()))
                : parseBinanceMetricsArchive(archive.body(), new MetricsArchiveOptions(
                        options.asset(), options.symbol(), options.startTime(), options.endTime()));
        return new ArchiveCapture(parsed.rows(), parsed.archiveMember(), parsed.expiryAt(),
                parsed.header(), parsed.semanticMapping(), parsed.metricFields(),
                List.of(archiveHash, checksumHash), List.of(zip, sum), archiveHash, checksumHash,
                latestCaptureTime(List.of(archive.retrievedAt(), checksum.retrievedAt())),
                dated ? PUBLIC_ADAPTERS.get("BINANCE_DATA_VISION_DATED_KLINES").adapterId() : null);
    }

    private static BackfillResult backfillArchives(
            ArchiveBackfillOptions options, List<String> files, boolean dated) {
        Path custodyRoot = options.rawOutputRoot() == null
                ? null : prepareCustodyRoot(options.rawOutputRoot());
        Path checkpointPath = options.checkpointPath();
        if (checkpointPath == null && custodyRoot != null) checkpointPath =
                custodyRoot.resolve("checkpoints/" + (dated ? "dated-" : "metrics-")
                        + lower(options.asset()) + "-" + lower(options.symbol())
                        + (dated ? "-" + options.interval() : "") + ".json");
        if (checkpointPath != null) {
            if (custodyRoot == null) {
                throw failure("archive checkpoint requires rawOutputRoot for physical custody");
            }
            Path requestedRoot = options.rawOutputRoot().toAbsolutePath().normalize();
            Path requestedCheckpoint = checkpointPath.toAbsolutePath().normalize();
            if (requestedCheckpoint.startsWith(requestedRoot)) {
                checkpointPath = custodyRoot.resolve(requestedRoot.relativize(requestedCheckpoint));
            } else if (!requestedCheckpoint.startsWith(custodyRoot)) {
                throw failure("archive checkpoint escapes its custody root");
            }
            ensureSecureDirectories(custodyRoot, checkpointPath.getParent(), "archive checkpoint");
        }
        final Path checkpoint = checkpointPath;
        ObjectNode checkpointIdentity = JsonHashes.mapper().createObjectNode();
        checkpointIdentity.put("kind", dated
                ? "DATED-" + lower(options.asset()) + "-" + upper(options.symbol())
                        + "-" + options.interval()
                : "METRICS-" + lower(options.asset()) + "-" + upper(options.symbol()));
        checkpointIdentity.put("asset", lower(options.asset()));
        checkpointIdentity.put("symbol", upper(options.symbol()));
        if (dated) checkpointIdentity.put("interval", options.interval());
        checkpointIdentity.put("start", options.startTime());
        checkpointIdentity.put("end", options.endTime());
        checkpointIdentity.set("files", JsonHashes.mapper().valueToTree(files));
        String checkpointKey = JsonHashes.canonicalSha256(checkpointIdentity);
        ObjectNode state = readCheckpoint(checkpoint, custodyRoot, checkpointKey,
                options.expectedCheckpointSha256());
        List<ArchiveCapture> results = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String token : files) {
            JsonNode saved = state.path("files").path(token);
            if (!options.forceReopen() && saved.path("status").asInt() == 404) {
                Long checked = parseTimestamp(saved.path("checked_at").asText());
                if (checked != null && System.currentTimeMillis() < checked + 30L * 86_400_000L) {
                    missing.add(token);
                    continue;
                }
            }
            try {
                ArchiveCapture page = !options.forceReopen() && saved.isObject()
                        && saved.path("status").asInt(200) == 200
                        ? reopenArchive(saved, options, token, dated)
                        : dated
                            ? fetchBinanceDatedKlineArchive(new ArchiveFetchOptions(
                                    options.asset(), options.symbol(), options.interval(), token,
                                    options.startTime(), options.endTime(), options.http(), options.rawOutputRoot()))
                            : fetchBinanceMetricsArchive(new ArchiveFetchOptions(
                                    options.asset(), options.symbol(), "5m", token,
                                    options.startTime(), options.endTime(), options.http(), options.rawOutputRoot()));
                results.add(page);
                ObjectNode savedPage = state.with("files").putObject(token);
                savedPage.put("status", 200); savedPage.put("captured_at", page.capturedAt());
                savedPage.put("archive_sha256", page.archiveSha256());
                savedPage.put("checksum_sha256", page.checksumSha256());
                ArrayNode raw = savedPage.putArray("raw");
                for (RawResponse reference : page.rawResponses()) {
                    ObjectNode row = raw.addObject(); row.put("path", reference.path());
                    row.put("sha256", reference.sha256()); row.put("bytes", reference.bytes());
                    row.set("request", reference.request());
                    row.put("kind", reference.request().path("kind").asText());
                }
                writeCheckpoint(checkpoint, state, checkpointKey);
            } catch (HttpStatusException error) {
                if (error.status != 404) throw error;
                missing.add(token);
                ObjectNode savedPage = state.with("files").putObject(token);
                savedPage.put("status", 404); savedPage.put("status_code", 404);
                savedPage.put("checked_at", error.observedAt);
                savedPage.put("recheck_after_ms", 30L * 86_400_000L);
                ArrayNode rawErrors = savedPage.putArray("raw");
                if (error.body.length > 0 && options.rawOutputRoot() != null) {
                    ObjectNode request = JsonHashes.mapper().createObjectNode();
                    request.put("endpoint", error.url); request.put("kind", "HTTP_ERROR");
                    request.put("status", 404); request.put("symbol", options.symbol());
                    request.put("interval", options.interval()); request.put("period", token);
                    RawResponse reference = persistRawResponse(
                            options.rawOutputRoot(), error.body, request);
                    ObjectNode row = rawErrors.addObject(); row.put("kind", "HTTP_ERROR");
                    row.put("path", reference.path()); row.put("sha256", reference.sha256());
                    row.put("bytes", reference.bytes()); row.set("request", reference.request());
                }
                writeCheckpoint(checkpoint, state, checkpointKey);
            }
        }
        List<ObjectNode> rows = new ArrayList<>();
        List<RawResponse> raw = new ArrayList<>();
        List<String> captures = new ArrayList<>();
        results.forEach(page -> { rows.addAll(page.rows()); raw.addAll(page.rawResponses());
            if (page.capturedAt() != null) captures.add(page.capturedAt()); });
        long step = dated ? intervalMilliseconds(options.interval()) : 300_000L;
        ObjectNode coverage = archiveCoverage(rows, options.startTime(), options.endTime(), step,
                missing, dated ? "missing_months" : "missing_days");
        if (!dated) {
            coverage.put("duplicate_events", !coverage.path("duplicate_events").isEmpty());
            coverage.remove("boundaries_covered");
        }
        coverage.put("archive_files", files.size()); coverage.put("bounded", true);
        coverage.put("concurrency", options.concurrency());
        putNullable(coverage, "checkpoint_path", checkpoint == null ? null : checkpoint.toString());
        putNullable(coverage, "checkpoint_sha256",
                checkpoint == null ? null : state.path("content_sha256").asText(null));
        coverage.put("source", dated ? "BINANCE_DATA_VISION_ZIP_CHECKSUM"
                : "BINANCE_DATA_VISION_DAILY_METRICS_ZIP_CHECKSUM");
        return new BackfillResult(rows, raw, raw.stream().map(RawResponse::sha256).toList(),
                latestCaptureTime(captures), coverage, null);
    }

    private static ArchiveCapture reopenArchive(
            JsonNode saved, ArchiveBackfillOptions options, String token, boolean dated) {
        if (options.rawOutputRoot() == null) throw failure("saved archive checkpoint has no custody root");
        byte[] zip = null, checksum = null;
        List<RawResponse> refs = new ArrayList<>();
        for (JsonNode reference : saved.path("raw")) {
            Path path = PathConfinement.resolve(options.rawOutputRoot(), reference.path("path").asText(),
                    "archive raw reference", PathConfinement.ExpectedType.FILE).absolute();
            byte[] bytes;
            try { bytes = Files.readAllBytes(path); }
            catch (IOException error) { throw failure(error.getMessage(), error); }
            if (!JsonHashes.sha256(bytes).equals(reference.path("sha256").asText())) {
                throw failure("saved archive checkpoint bytes changed: " + token);
            }
            ObjectNode request = (ObjectNode) reference.path("request").deepCopy();
            refs.add(new RawResponse(reference.path("sha256").asText(), null,
                    reference.path("path").asText(), bytes.length, request));
            if ("ARCHIVE_ZIP".equals(reference.path("kind").asText())) zip = bytes;
            if ("ARCHIVE_CHECKSUM".equals(reference.path("kind").asText())) checksum = bytes;
        }
        if (zip == null || checksum == null || !JsonHashes.sha256(zip).equals(saved.path("archive_sha256").asText())
                || !checksumValue(checksum).equals(saved.path("archive_sha256").asText())) {
            throw failure("saved archive checkpoint bytes changed: " + token);
        }
        ParsedArchive parsed = dated
                ? parseBinanceDatedKlineArchive(zip, new DatedArchiveOptions(
                        options.asset(), options.symbol(), options.interval(), options.startTime(), options.endTime()))
                : parseBinanceMetricsArchive(zip, new MetricsArchiveOptions(
                        options.asset(), options.symbol(), options.startTime(), options.endTime()));
        return new ArchiveCapture(parsed.rows(), parsed.archiveMember(), parsed.expiryAt(), parsed.header(),
                parsed.semanticMapping(), parsed.metricFields(), refs.stream().map(RawResponse::sha256).toList(),
                refs, saved.path("archive_sha256").asText(), saved.path("checksum_sha256").asText(),
                saved.path("captured_at").asText(null), dated
                    ? PUBLIC_ADAPTERS.get("BINANCE_DATA_VISION_DATED_KLINES").adapterId() : null);
    }

    private static ObservedResponse request(String url, HttpOptions options, String accept) {
        if (options.capturedAt() != null && !options.fixtureOnly()) throw failure(
                "caller-supplied capturedAt is fixture-only; production source custody uses the observed response/call time");
        RuntimeException last = null;
        for (int attempt = 0; attempt <= options.retries(); attempt++) {
            try {
                FetchResponse response = options.client().fetch(URI.create(url), Map.of("accept", accept));
                String observed = responseTime(response);
                if (response.status() < 200 || response.status() >= 300) {
                    throw new HttpStatusException(response.status(), url, observed, response.body());
                }
                JsonNode json = JsonHashes.mapper().readTree(response.body());
                String retrieved = options.fixtureOnly() && options.capturedAt() != null
                        ? normalizeInstant(options.capturedAt()) : observed;
                return new ObservedResponse(response.body(), json, retrieved, observed,
                        options.fixtureOnly() && options.capturedAt() != null
                                ? "FIXTURE_SUPPLIED" : "CALL_OBSERVED", response.status());
            } catch (HttpStatusException status) {
                if (status.status == 404) throw status;
                last = status;
            } catch (Exception error) {
                last = error instanceof RuntimeException runtime ? runtime : failure(error.getMessage(), error);
            }
            if (attempt < options.retries() && options.retryDelayMillis() > 0) try {
                Thread.sleep(options.retryDelayMillis() * (attempt + 1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw failure("public adapter request interrupted", interrupted);
            }
        }
        throw last;
    }

    private static ObservedResponse archiveRequest(String url, HttpOptions options) {
        if (options.capturedAt() != null && !options.fixtureOnly()) {
            throw failure("caller-supplied capturedAt is fixture-only for archive acquisition");
        }
        try {
            FetchResponse response = options.client().fetch(
                    URI.create(url), Map.of("accept", "application/zip, text/plain"));
            String observed = responseTime(response);
            String retained = options.fixtureOnly() && options.capturedAt() != null
                    ? normalizeInstant(options.capturedAt()) : observed;
            if (response.status() < 200 || response.status() >= 300) {
                throw new HttpStatusException(response.status(), url, retained,
                        response.body(), true);
            }
            return new ObservedResponse(response.body(), null, retained, observed,
                    options.fixtureOnly() && options.capturedAt() != null
                            ? "FIXTURE_SUPPLIED" : "CALL_OBSERVED", response.status());
        } catch (HttpStatusException error) {
            throw error;
        } catch (Exception error) {
            throw error instanceof RuntimeException runtime
                    ? runtime : failure(error.getMessage(), error);
        }
    }

    private static RawResponse rawResponse(
            Path root, byte[] body, String endpoint, ArchiveFetchOptions options,
            String kind, String hash) {
        ObjectNode request = JsonHashes.mapper().createObjectNode();
        request.put("endpoint", endpoint); request.put("symbol", options.symbol());
        request.put("interval", options.interval()); request.put("period", options.periodToken());
        request.put("kind", kind);
        return persistRawResponse(root, body, request);
    }

    private static RawResponse persistRawResponse(Path root, byte[] body, ObjectNode request) {
        String hash = JsonHashes.sha256(body);
        String path = null;
        if (root != null) {
            path = "raw-archives/" + hash + ".bin";
            Path base = prepareCustodyRoot(root);
            Path directory = base.resolve("raw-archives");
            ensureSecureDirectories(base, directory, "archive raw directory");
            Path target = directory.resolve(hash + ".bin");
            try {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    PathConfinement.validateSinglyLinkedFile(target, "immutable archive");
                    if (!JsonHashes.sha256(target).equals(hash)) {
                        Files.delete(target);
                        Files.write(target, body, java.nio.file.StandardOpenOption.CREATE_NEW);
                    }
                } else Files.write(target, body, java.nio.file.StandardOpenOption.CREATE_NEW);
            } catch (IOException error) { throw failure(error.getMessage(), error); }
        }
        return new RawResponse(hash, root == null ? body : null, path, body.length, request);
    }

    private static Path prepareCustodyRoot(Path root) {
        Path requested = root.toAbsolutePath().normalize();
        try { Files.createDirectories(requested); }
        catch (IOException error) { throw failure(error.getMessage(), error); }
        return PathConfinement.requireRealDirectory(requested, "archive custody root");
    }

    private static void ensureSecureDirectories(Path root, Path directory, String label) {
        Path base = root.toAbsolutePath().normalize();
        Path target = directory.toAbsolutePath().normalize();
        if (!target.startsWith(base)) throw failure(label + " escapes its custody root");
        Path cursor = base;
        try {
            for (Path component : base.relativize(target)) {
                cursor = cursor.resolve(component);
                if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(cursor)
                            || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                        throw failure(label + " contains an unsafe directory component");
                    }
                } else Files.createDirectory(cursor);
            }
        } catch (IOException error) { throw failure(error.getMessage(), error); }
    }

    private static ObjectNode archiveCoverage(
            List<ObjectNode> input, long start, long end, long step,
            List<String> missing, String missingLabel) {
        List<ObjectNode> rows = input.stream().sorted(
                Comparator.comparingLong(row -> row.path("event_time").asLong())).toList();
        ArrayNode duplicates = JsonHashes.mapper().createArrayNode();
        ArrayNode gaps = JsonHashes.mapper().createArrayNode();
        for (int index = 1; index < rows.size(); index++) {
            long current = rows.get(index).path("event_time").asLong();
            long previous = rows.get(index - 1).path("event_time").asLong();
            if (current == previous) duplicates.add(current);
            else if (current - previous != step) gaps.add(iso(previous + step));
        }
        Long first = rows.isEmpty() ? null : rows.get(0).path("event_time").asLong();
        Long last = rows.isEmpty() ? null : rows.get(rows.size() - 1).path("event_time").asLong();
        long expectedFirst = ceilDiv(start, step) * step;
        long expectedLast = Math.floorDiv(end, step) * step;
        long expectedRows = expectedLast >= expectedFirst ? (expectedLast - expectedFirst) / step + 1 : 0;
        boolean boundary = !rows.isEmpty() && first == expectedFirst && last == expectedLast
                && rows.size() == expectedRows;
        ObjectNode coverage = JsonHashes.mapper().createObjectNode();
        coverage.put("complete", missing.isEmpty() && duplicates.isEmpty() && gaps.isEmpty() && boundary);
        coverage.put("start_cursor", start); coverage.put("end_cursor", end);
        if (first == null) coverage.putNull("first_event_time"); else coverage.put("first_event_time", first);
        if (last == null) coverage.putNull("last_event_time"); else coverage.put("last_event_time", last);
        coverage.put("expected_first_event_time", expectedFirst);
        coverage.put("expected_last_event_time", expectedLast);
        coverage.put("expected_rows", expectedRows); coverage.put("observed_rows", rows.size());
        coverage.set(missingLabel, JsonHashes.mapper().valueToTree(new java.util.TreeSet<>(missing)));
        coverage.set("duplicate_events", duplicates); coverage.set("gap_starts", gaps);
        coverage.put("boundaries_covered", boundary);
        return coverage;
    }

    private static ObjectNode readCheckpoint(
            Path path, Path root, String key, String expectedHash) {
        ObjectNode empty = JsonHashes.mapper().createObjectNode();
        empty.put("key", key); empty.putObject("files");
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return empty;
        if (root == null) throw failure("archive checkpoint requires rawOutputRoot for physical custody");
        Path relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        Path physical = PathConfinement.resolve(root, relative.toString().replace('\\', '/'),
                "archive checkpoint", PathConfinement.ExpectedType.FILE).absolute();
        try {
            JsonNode parsed = JsonHashes.parse(Files.readAllBytes(physical), "archive checkpoint");
            ObjectNode value = (ObjectNode) parsed;
            String actual = value.path("content_sha256").asText();
            ObjectNode copy = value.deepCopy(); copy.remove("content_sha256");
            if (!key.equals(value.path("key").asText())
                    || !actual.equals(JsonHashes.canonicalSha256(copy))) {
                throw failure("archive checkpoint is invalid or bound to a different request");
            }
            if (expectedHash != null && !expectedHash.equals(actual)) {
                throw failure("archive checkpoint predecessor hash mismatch");
            }
            var files = value.with("files").fields();
            while (files.hasNext()) {
                var file = files.next();
                boolean changed = false;
                for (JsonNode reference : file.getValue().path("raw")) {
                    Path rawPath = PathConfinement.resolve(root, reference.path("path").asText(),
                            "archive raw reference", PathConfinement.ExpectedType.FILE).absolute();
                    if (!JsonHashes.sha256(rawPath).equals(reference.path("sha256").asText())) {
                        changed = true;
                        break;
                    }
                }
                if (changed) files.remove();
            }
            return value;
        } catch (IOException error) { throw failure(error.getMessage(), error); }
    }

    private static void writeCheckpoint(Path path, ObjectNode state, String key) {
        if (path == null) return;
        ObjectNode value = JsonHashes.mapper().createObjectNode(); value.put("key", key);
        ObjectNode files = value.putObject("files");
        TreeMap<String, JsonNode> sorted = new TreeMap<>();
        state.path("files").fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
        sorted.forEach((name, row) -> files.set(name, row.deepCopy()));
        value.put("content_sha256", JsonHashes.canonicalSha256(value));
        try {
            Files.createDirectories(path.toAbsolutePath().normalize().getParent());
            Path temporary = path.resolveSibling("." + path.getFileName() + "."
                    + value.path("content_sha256").asText() + ".tmp");
            Files.write(temporary, pretty(value), java.nio.file.StandardOpenOption.CREATE_NEW);
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            state.removeAll(); state.setAll(value);
        } catch (IOException error) { throw failure(error.getMessage(), error); }
    }

    private static ObjectNode paginationReceipt(ArrayNode pages, ObjectNode coverage, long rateLimit) {
        ObjectNode receipt = JsonHashes.mapper().createObjectNode();
        receipt.put("schema", "public-data-backfill-receipt/1"); receipt.set("pages", pages);
        receipt.set("coverage", coverage); ObjectNode policy = receipt.putObject("policy");
        policy.put("bounded", true); policy.put("rate_limit_ms", rateLimit);
        policy.put("retry_policy", "exponential_backoff_3_attempts");
        receipt.put("content_sha256", JsonHashes.canonicalSha256(receipt));
        return receipt;
    }

    private static Page page(Capture capture) {
        return new Page(capture.rows(), capture.responseSha256(), capture.responseBody(),
                capture.request(), capture.capturedAt());
    }

    private static Adapter revised(String id, String venue, String instrument, String availability) {
        return new Adapter(id, "T3_REVISED_OR_PROXY",
                "RECONSTRUCTED_EXCHANGE_EVENT_LATEST_CAPTURE",
                "LATEST_RETRIEVAL_NOT_HISTORICAL_VINTAGE", availability, venue, instrument);
    }

    private static byte[] inflate(byte[] compressed, long limit, String name) {
        Inflater inflater = new Inflater(true);
        inflater.setInput(compressed);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && inflater.needsInput()) break;
                if (count == 0 && inflater.needsDictionary()) throw new DataFormatException();
                if ((long) output.size() + count > limit) throw failure(
                        "Binance archive decompression output exceeds the hard limit: " + name);
                output.write(buffer, 0, count);
            }
            if (!inflater.finished()) throw new DataFormatException();
            return output.toByteArray();
        } catch (DataFormatException error) {
            throw failure("Binance archive DEFLATE stream is invalid: " + name, error);
        } finally { inflater.end(); }
    }

    private static String safeArchiveName(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\")
                || List.of(name.split("/", -1)).contains("..")) {
            throw failure("Binance archive contains an unsafe member path: " + name);
        }
        return name;
    }

    private static long u32(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) return -1;
        return Integer.toUnsignedLong(ByteBuffer.wrap(bytes, offset, 4)
                .order(ByteOrder.LITTLE_ENDIAN).getInt());
    }

    private static int u16(byte[] bytes, int offset) {
        if (offset < 0 || offset + 2 > bytes.length) return -1;
        return Short.toUnsignedInt(ByteBuffer.wrap(bytes, offset, 2)
                .order(ByteOrder.LITTLE_ENDIAN).getShort());
    }

    private static String quarterExpiry(String symbol) {
        Matcher match = Pattern.compile("^[A-Z]+USDT_(\\d{6})$").matcher(upper(symbol));
        if (!match.matches()) throw failure(
                "dated futures symbol has no exact YYMMDD expiry: " + symbol);
        String token = match.group(1);
        try {
            LocalDate date = LocalDate.of(2000 + Integer.parseInt(token.substring(0, 2)),
                    Integer.parseInt(token.substring(2, 4)), Integer.parseInt(token.substring(4, 6)));
            return iso(date.atTime(8, 0).toInstant(ZoneOffset.UTC).toEpochMilli());
        } catch (RuntimeException error) {
            throw failure("dated futures symbol has an invalid expiry: " + symbol);
        }
    }

    private static List<List<String>> parseCsvRecords(String source) {
        List<List<String>> records = new ArrayList<>(); List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder(); boolean quoted = false, afterQuote = false;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (quoted) {
                if (character == '"') {
                    if (index + 1 < source.length() && source.charAt(index + 1) == '"') {
                        cell.append('"'); index++;
                    } else { quoted = false; afterQuote = true; }
                } else cell.append(character);
            } else if (afterQuote) {
                if (character == ' ' || character == '\t') continue;
                if (character == ',') { row.add(cell.toString()); cell.setLength(0); afterQuote = false; }
                else if (character == '\r' || character == '\n') {
                    if (character == '\r' && index + 1 < source.length()
                            && source.charAt(index + 1) == '\n') index++;
                    row.add(cell.toString()); cell.setLength(0); afterQuote = false;
                    records.add(row); row = new ArrayList<>();
                } else throw failure("metrics archive CSV has data after a closing quote: " + character);
            } else if (character == '"') {
                if (!cell.isEmpty()) throw failure("metrics archive CSV has an unexpected quote");
                quoted = true;
            } else if (character == ',') { row.add(cell.toString()); cell.setLength(0); }
            else if (character == '\r' || character == '\n') {
                if (character == '\r' && index + 1 < source.length()
                        && source.charAt(index + 1) == '\n') index++;
                row.add(cell.toString()); cell.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty() && records.isEmpty())) records.add(row);
                row = new ArrayList<>();
            } else cell.append(character);
        }
        if (quoted) throw failure("metrics archive CSV has an unterminated quoted cell");
        if (!cell.isEmpty() || !row.isEmpty()) { row.add(cell.toString()); records.add(row); }
        return records;
    }

    private static long metricTimestamp(String value) {
        String text = value == null ? "" : value.trim();
        if (text.matches("^\\d+(?:\\.\\d+)?$")) {
            double number = Double.parseDouble(text);
            return (long) (number < 1e12 ? number * 1000 : number);
        }
        Long parsed = parseTimestamp(text.replace(' ', 'T') + (text.endsWith("Z") ? "" : "Z"));
        if (parsed == null) throw failure("metrics archive timestamp is invalid: " + value);
        return parsed;
    }

    private static void putMetric(ObjectNode row, String field, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) { row.putNull(field); return; }
        if (!METRIC_NUMBER.matcher(value).matches()) throw failure(
                "metrics archive contains a non-numeric metric value: " + raw);
        double number = Double.parseDouble(value);
        if (!Double.isFinite(number)) throw failure(
                "metrics archive contains a non-numeric metric value: " + raw);
        row.put(field, number);
    }

    private static String symbol(String asset) {
        String value = lower(asset);
        if (!CORE_CRYPTO_ASSETS.contains(value)) throw failure(
                "asset " + value + " is outside the required eight-asset crypto universe");
        return value.toUpperCase(Locale.ROOT) + "USDT";
    }

    private static String symbolOverride(String symbol) {
        String value = upper(symbol);
        if (!value.matches("^[A-Z0-9_]+$")) throw failure(
                "invalid Binance symbol override " + symbol);
        return value;
    }

    private static long intervalMilliseconds(String interval) {
        Matcher match = Pattern.compile("^(\\d+)(m|h|d)$", Pattern.CASE_INSENSITIVE)
                .matcher(interval == null ? "4h" : interval);
        if (!match.matches()) throw failure("unsupported Binance interval " + interval);
        return Long.parseLong(match.group(1)) * switch (match.group(2).toLowerCase(Locale.ROOT)) {
            case "m" -> 60_000L; case "h" -> 3_600_000L; default -> 86_400_000L;
        };
    }

    private static List<String> monthStrings(long start, long end) {
        List<String> values = new ArrayList<>();
        LocalDate cursor = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        LocalDate finish = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        while (!cursor.isAfter(finish)) { values.add(String.format("%04d-%02d", cursor.getYear(), cursor.getMonthValue())); cursor = cursor.plusMonths(1); }
        return values;
    }

    private static List<String> dayStrings(long start, long end) {
        List<String> values = new ArrayList<>();
        LocalDate cursor = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate finish = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate();
        while (!cursor.isAfter(finish)) { values.add(cursor.toString()); cursor = cursor.plusDays(1); }
        return values;
    }

    private static String checksumValue(byte[] body) {
        Matcher match = Pattern.compile("\\b([a-f0-9]{64})\\b", Pattern.CASE_INSENSITIVE)
                .matcher(new String(body, StandardCharsets.UTF_8));
        if (!match.find()) throw failure(
                "Binance archive CHECKSUM does not contain a SHA-256 digest");
        return match.group(1).toLowerCase(Locale.ROOT);
    }

    private static String responseTime(FetchResponse response) {
        String date = response.firstHeader("date");
        if (date != null) try {
            return iso(ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli());
        } catch (DateTimeParseException ignored) {
            Long parsed = parseTimestamp(date); if (parsed != null) return iso(parsed);
        }
        return iso(System.currentTimeMillis());
    }

    private static String normalizeInstant(String value) {
        Long parsed = parseTimestamp(value);
        if (parsed == null) throw failure("capturedAt must be a valid timestamp");
        return iso(parsed);
    }

    private record Vintage(long vintage, long time, String precision) {}
    private static Vintage vintage(String value) {
        long instant = requireTimestamp(value);
        return value.matches("^\\d{4}-\\d{2}-\\d{2}$")
                ? new Vintage(instant, instant + 86_399_999, "DATE_ONLY_END_OF_DAY_UTC")
                : new Vintage(instant, instant, "TIMESTAMP");
    }

    private static Long parseTimestamp(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value).toEpochMilli(); }
        catch (DateTimeParseException ignored) {
            try { return OffsetDateTime.parse(value).toInstant().toEpochMilli(); }
            catch (DateTimeParseException ignoredAgain) {
                try { return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(); }
                catch (DateTimeParseException invalid) { return null; }
            }
        }
    }

    private static long requireTimestamp(String value) {
        Long parsed = parseTimestamp(value);
        if (parsed == null) throw failure("timestamp is invalid: " + value);
        return parsed;
    }

    private static String latestCaptureTime(List<String> values) {
        return values.stream().filter(Objects::nonNull).map(PublicDataAdapters::parseTimestamp)
                .filter(Objects::nonNull).max(Long::compare).map(PublicDataAdapters::iso)
                .orElse(null);
    }

    private static String iso(long epochMillis) {
        return ISO_MILLIS.format(Instant.ofEpochMilli(epochMillis));
    }

    private static Map<String, String> params(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put(values[index], values[index + 1]);
        return result;
    }

    private static void optional(Map<String, String> target, String name, Object value) {
        if (value != null) target.put(name, String.valueOf(value));
    }

    private static String url(String endpoint, Map<String, String> params) {
        return endpoint + "?" + params.entrySet().stream().map(entry ->
                URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .reduce((left, right) -> left + "&" + right).orElse("");
    }

    private static Double positiveOrNull(JsonNode raw) {
        String value = raw == null || raw.isNull() ? "" : raw.asText().trim();
        if (value.isEmpty()) return null;
        double number;
        try { number = Double.parseDouble(value); }
        catch (NumberFormatException error) { throw failure(
                "funding response contains a non-numeric markPrice: " + value); }
        if (!Double.isFinite(number)) throw failure(
                "funding response contains a non-numeric markPrice: " + value);
        return number > 0 ? number : null;
    }

    private static void ensureUnique(List<ObjectNode> rows, String field, String message) {
        Set<String> values = new HashSet<>();
        for (ObjectNode row : rows) if (!values.add(row.path(field).asText())) throw failure(message);
    }

    private static boolean finite(ObjectNode row, String... fields) {
        for (String field : fields) if (!finite(row.get(field))) return false;
        return true;
    }

    private static boolean finite(JsonNode value) {
        if (value == null || value.isNull()) return false;
        try { return Double.isFinite(value.isNumber() ? value.asDouble()
                : Double.parseDouble(value.asText().trim())); }
        catch (NumberFormatException error) { return false; }
    }

    private static double number(String value) {
        try { return Double.parseDouble(value.trim()); }
        catch (RuntimeException error) { return Double.NaN; }
    }

    private static double number(JsonNode value) {
        if (value == null || value.isNull()) return Double.NaN;
        return value.isNumber() ? value.asDouble() : number(value.asText());
    }

    private static long longNumber(String value) {
        try { return new BigDecimal(value.trim()).longValue(); }
        catch (RuntimeException error) { return 0; }
    }

    private static long ceilDiv(long value, long divisor) {
        return -Math.floorDiv(-value, divisor);
    }

    private static void putNullable(ObjectNode node, String name, Object value) {
        if (value == null) node.putNull(name);
        else if (value instanceof Long number) node.put(name, number);
        else node.put(name, String.valueOf(value));
    }

    private static byte[] pretty(JsonNode value) throws IOException {
        return (JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String lower(String value) {
        return String.valueOf(value == null ? "" : value).toLowerCase(Locale.ROOT);
    }
    private static String upper(String value) {
        return String.valueOf(value == null ? "" : value).toUpperCase(Locale.ROOT);
    }

    private static List<ObjectNode> immutableRows(List<ObjectNode> rows) {
        return rows == null ? List.of() : rows.stream().map(ObjectNode::deepCopy).toList();
    }

    private static Map<String, List<String>> deepHeaders(Map<String, List<String>> headers) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }

    private static final class HttpStatusException extends IllegalArgumentException {
        final int status; final String url; final String observedAt; final byte[] body;
        HttpStatusException(int status, String url, String observedAt, byte[] body) {
            this(status, url, observedAt, body, false);
        }
        HttpStatusException(
                int status, String url, String observedAt, byte[] body, boolean archive) {
            super((archive ? "Binance Data Vision archive HTTP " : "public adapter HTTP ")
                    + status + ": " + url);
            this.status = status; this.url = url; this.observedAt = observedAt;
            this.body = body.clone();
        }
    }

    private static IllegalArgumentException failure(String message) {
        return new IllegalArgumentException(message);
    }
    private static IllegalArgumentException failure(String message, Throwable cause) {
        return new IllegalArgumentException(message, cause);
    }
}

package com.tradinganalytics.infrastructure.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PublicDataAdaptersTest {
    @TempDir Path temporary;

    @Test
    void datedArchiveParserMatchesNodeAndRejectsHostileZipStructures() throws Exception {
        String csv = "0,100,101,99,100.5,10,14399999,1000,3,4,5,0\n"
                + "14400000,101,102,100,101.5,10,28799999,1000,3,4,5,0\n";
        byte[] zip = storedZip("BTCUSDT_210924-4h-2021-01.csv", csv);
        var parsed = PublicDataAdapters.parseBinanceDatedKlineArchive(zip,
                new PublicDataAdapters.DatedArchiveOptions(
                        "btc", "BTCUSDT_210924", "4h", 0L, 14_400_000L));
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.expiryAt()).isEqualTo("2021-09-24T08:00:00.000Z");

        JsonNode oracle = datedOracle();
        assertThat(parsed.archiveMember()).isEqualTo(oracle.path("archive_member").asText());
        assertThat(parsed.expiryAt()).isEqualTo(oracle.path("expiry_at").asText());
        assertThat(parsed.rows().get(1).path("event_time").asLong())
                .isEqualTo(oracle.path("rows").get(1).path("event_time").asLong());
        assertThat(parsed.rows().get(0).path("close").asDouble())
                .isEqualTo(oracle.path("rows").get(0).path("close").asDouble());

        assertThatThrownBy(() -> PublicDataAdapters.parseZipArchive("not-a-zip".getBytes()))
                .hasMessageContaining("missing EOCD");
        assertThatThrownBy(() -> PublicDataAdapters.parseZipArchive(storedZip("../escape.csv", "x")))
                .hasMessageContaining("unsafe member path");
        assertThatThrownBy(() -> PublicDataAdapters.parseZipArchive(
                deflatedZip("bomb.csv", "x".repeat(64 * 1024), 1), 1024))
                .hasMessageContaining("decompression output exceeds the hard limit");

        byte[] badCrc = zip.clone();
        badCrc[30 + "BTCUSDT_210924-4h-2021-01.csv".getBytes(StandardCharsets.UTF_8).length] ^= 1;
        assertThatThrownBy(() -> PublicDataAdapters.parseZipArchive(badCrc))
                .hasMessageContaining("checksum/size mismatch");
        byte[] mismatchedName = zip.clone();
        mismatchedName[30] = 'X';
        assertThatThrownBy(() -> PublicDataAdapters.parseZipArchive(mismatchedName))
                .hasMessageContaining("central/local filename mismatch");
    }

    @Test
    void parsesAndAggregatesMetricsWithoutTurningMissingCellsIntoZero() {
        String header = "create_time,symbol,sum_open_interest,sum_open_interest_value,"
                + "count_toptrader_long_short_ratio,sum_toptrader_long_short_ratio,"
                + "count_long_short_ratio,sum_taker_long_short_vol_ratio";
        long start = 1_699_920_000_000L;
        StringBuilder csv = new StringBuilder(header).append('\n');
        for (int index = 0; index < 12; index++) {
            csv.append(start + index * 300_000L).append(",BTCUSDT,")
                    .append(index == 0 ? "\"\"" : 100 + index).append(",")
                    .append(200 + index).append(",1.1,")
                    .append(index == 0 ? "\"\"" : "1.2").append(",1.3,1.4\n");
        }
        var parsed = PublicDataAdapters.parseBinanceMetricsArchive(
                storedZip("BTCUSDT-metrics.csv", csv.toString()),
                new PublicDataAdapters.MetricsArchiveOptions(
                        "btc", "BTCUSDT", start, start + 3_300_000));
        assertThat(parsed.rows()).hasSize(12);
        assertThat(parsed.rows().get(0).path("open_interest").isNull()).isTrue();
        assertThat(parsed.rows().get(0).path("top_trader_position_long_short_ratio").isNull())
                .isTrue();

        var strict = PublicDataAdapters.aggregateBinanceMetricsRows(parsed.rows(),
                new PublicDataAdapters.MetricsAggregationOptions(
                        "1h", start, start, null, null));
        assertThat(strict.rows()).hasSize(1);
        assertThat(strict.rows().get(0).path("open_interest").asDouble()).isEqualTo(111);
        assertThat(strict.coverage().path("complete").asBoolean()).isFalse();
        var positioningOnly = PublicDataAdapters.aggregateBinanceMetricsRows(parsed.rows().stream()
                        .map(row -> { ObjectNode copy = row.deepCopy(); copy.put("open_interest", 100); return copy; })
                        .toList(),
                new PublicDataAdapters.MetricsAggregationOptions(
                        "1h", start, start, List.of("open_interest"), .95));
        assertThat(positioningOnly.coverage().path("complete").asBoolean()).isTrue();

        String badSymbol = header + "\n" + start + ",ETHUSDT,1,2,1,1,1,1\n";
        assertThatThrownBy(() -> PublicDataAdapters.parseBinanceMetricsArchive(
                storedZip("metrics.csv", badSymbol),
                new PublicDataAdapters.MetricsArchiveOptions("btc", "BTCUSDT", start, start)))
                .hasMessageContaining("symbol ETHUSDT does not match requested symbol BTCUSDT");
    }

    @Test
    void injectableHttpAdaptersPreservePitSemanticsAndFailClosed() {
        var captured = new PublicDataAdapters.HttpOptions(jsonClient("""
                [[0,"1","2","0.5","1.5","10",3,"15",7,"6","9","0"]]
                """), "2026-01-01T00:00:00Z", true, 0, 0);
        var ohlc = PublicDataAdapters.fetchBinanceOhlc(new PublicDataAdapters.OhlcOptions(
                "btc", null, null, null, "4h", 1000, false, captured));
        assertThat(ohlc.rows().get(0).path("availability_time").asLong()).isEqualTo(3);
        assertThat(ohlc.rows().get(0).path("quote_volume").asDouble()).isEqualTo(15);
        assertThat(ohlc.rows().get(0).path("trades").asDouble()).isEqualTo(7);
        assertThat(ohlc.pitTier()).isEqualTo("T3_REVISED_OR_PROXY");
        assertThat(ohlc.rows().get(0).path("pit_provenance").asText())
                .isEqualTo("RECONSTRUCTED_EXCHANGE_EVENT_LATEST_CAPTURE");

        var funding = PublicDataAdapters.fetchBinanceFundingEvents(
                new PublicDataAdapters.FundingOptions("eth", null, null, null, 1000,
                        new PublicDataAdapters.HttpOptions(jsonClient(
                                "[{\"symbol\":\"ETHUSDT\",\"fundingTime\":8,"
                                        + "\"fundingRate\":\"0\",\"markPrice\":\"3000\"}]"),
                                "2026-01-01T00:00:00Z", true, 0, 0)));
        assertThat(funding.rows().get(0).path("event_id").asText()).isEqualTo("ETHUSDT:8");
        assertThat(funding.rows().get(0).path("settlement_mark").asDouble()).isEqualTo(3000);
        assertThatThrownBy(() -> PublicDataAdapters.fetchBinanceFundingEvents(
                new PublicDataAdapters.FundingOptions("eth", null, null, null, 1000,
                        new PublicDataAdapters.HttpOptions(jsonClient("""
                                [{"symbol":"BTCUSDT","fundingTime":8,"fundingRate":"0"}]
                                """), "2026-01-01T00:00:00Z", true, 0, 0))))
                .hasMessageContaining("symbol BTCUSDT does not match requested symbol ETHUSDT");
        assertThatThrownBy(() -> PublicDataAdapters.fetchBinanceOhlc(
                new PublicDataAdapters.OhlcOptions("doge", null, null, null, "4h", 10,
                        false, captured))).hasMessageContaining("outside the required eight-asset");
        assertThatThrownBy(() -> PublicDataAdapters.fetchBinanceOhlc(
                new PublicDataAdapters.OhlcOptions("btc", null, null, null, "4h", 10,
                        false, new PublicDataAdapters.HttpOptions(
                                jsonClient("[]"), "2000-01-01T00:00:00Z", false, 0, 0))))
                .hasMessageContaining("fixture-only");
    }

    @Test
    void remainingHttpAdaptersAndBackfillFacadesUseTheInjectableTransport() throws Exception {
        PublicDataAdapters.InjectableHttpClient router = (uri, headers) -> {
            String url = uri.toString();
            String body;
            if (url.contains("exchangeInfo")) {
                body = "{\"symbols\":[{\"symbol\":\"BTCUSDT\",\"status\":\"TRADING\"}]}";
            } else if (url.contains("markPriceKlines")) {
                body = "[[0,\"1\",\"2\",\"0.5\",\"1.5\",\"0\",3]]";
            } else if (url.contains("openInterestHist")) {
                body = "[{\"timestamp\":8,\"sumOpenInterest\":\"1\","
                        + "\"sumOpenInterestValue\":\"2\"}]";
            } else if (url.contains("fundingRate")) {
                body = "[{\"symbol\":\"BTCUSDT\",\"fundingTime\":8,"
                        + "\"fundingRate\":\"0.001\",\"markPrice\":\"100\"}]";
            } else if (url.contains("alternative.me")) {
                body = "{\"data\":[{\"timestamp\":\"8\",\"value\":\"12\","
                        + "\"value_classification\":\"Extreme Fear\"}]}";
            } else if (url.contains("stlouisfed")) {
                body = "{\"observations\":[{\"date\":\"2026-01-01\","
                        + "\"realtime_start\":\"2026-01-02\","
                        + "\"realtime_end\":\"2026-02-01\",\"value\":\"1.2\"}]}";
            } else {
                body = "[[0,\"1\",\"2\",\"0.5\",\"1.5\",\"10\",3]]";
            }
            return new PublicDataAdapters.FetchResponse(200,
                    body.getBytes(StandardCharsets.UTF_8), Map.of());
        };
        var http = new PublicDataAdapters.HttpOptions(
                router, "2026-01-03T00:00:00Z", true, 0, 0);

        var exchange = PublicDataAdapters.fetchBinanceExchangeInfo(http);
        assertThat(exchange.rows()).hasSize(1);
        assertThat(exchange.request().path("params").isObject()).isTrue();
        assertThat(exchange.rows().get(0).path("availability_time").asText())
                .isEqualTo("2026-01-03T00:00:00.000Z");
        var mark = PublicDataAdapters.fetchBinanceMarkPriceOhlc(
                new PublicDataAdapters.OhlcOptions("btc", null, 0L, 0L,
                        "4h", 2, true, http));
        assertThat(mark.rows().get(0).path("series_role").asText()).isEqualTo("MARK");
        assertThat(mark.rows().get(0).path("mark_high").asDouble()).isEqualTo(2);
        var interest = PublicDataAdapters.fetchBinanceOpenInterest(
                new PublicDataAdapters.OpenInterestOptions("btc", "4h", 0L, 8L,
                        2, true, http));
        assertThat(interest.rows().get(0).path("open_interest_value").asDouble()).isEqualTo(2);
        var sentiment = PublicDataAdapters.fetchAlternativeSentiment(2, http);
        assertThat(sentiment.rows().get(0).path("classification").asText())
                .isEqualTo("Extreme Fear");
        var fred = PublicDataAdapters.fetchAlfredVintage(
                new PublicDataAdapters.AlfredOptions("DFF", "secret", null, null, http));
        assertThat(fred.request().path("params").path("api_key").asText()).isEqualTo("REDACTED");
        assertThat(fred.rows().get(0).path("availability_time").asLong())
                .isEqualTo(Instant.parse("2026-01-02T23:59:59.999Z").toEpochMilli());

        var markBackfill = PublicDataAdapters.backfillBinanceMarkPriceOhlc(
                new PublicDataAdapters.OhlcOptions("btc", null, 0L, 0L, "4h", 2, true, http),
                0L, 0L, 2, 2, 10, 0);
        assertThat(markBackfill.rows()).hasSize(1);
        assertThat(markBackfill.rawResponses()).hasSize(1);
        var fundingBackfill = PublicDataAdapters.backfillBinanceFunding(
                new PublicDataAdapters.FundingOptions("btc", null, 8L, 8L, 2, http),
                8L, 8L, 2, 2, 10, 0);
        assertThat(fundingBackfill.rows()).hasSize(1);
        var interestBackfill = PublicDataAdapters.backfillBinanceOpenInterest(
                new PublicDataAdapters.OpenInterestOptions("btc", "4h", 8L, 8L, 2, true, http),
                8L, 8L, 2, 2, 10, 0);
        assertThat(interestBackfill.rows()).hasSize(1);

        Path prospective = temporary.resolve("prospective.json");
        ObjectNode payload = JsonHashes.mapper().createObjectNode().put("value", 1);
        ObjectNode record = PublicDataAdapters.prospectiveCapture("oracle", "https://example.invalid",
                null, "2026-01-01T00:00:00Z", payload, null, prospective);
        assertThat(record.path("payload_sha256").asText())
                .isEqualTo(JsonHashes.canonicalSha256(payload));
        assertThat(prospective).exists();
        assertThatThrownBy(() -> PublicDataAdapters.prospectiveCapture(
                "oracle", "https://example.invalid", null, "2026-01-01T00:00:00Z",
                payload, null, prospective)).hasMessageContaining("prospective.json");
    }

    @Test
    void boundedPaginationAndBackfillResumeCliAreImmutable() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PublicDataAdapters.InjectableHttpClient client = (uri, headers) -> {
            int call = calls.getAndIncrement();
            String body = call == 0
                    ? "[[0,\"1\",\"2\",\"0.5\",\"1.5\",\"10\",14399999],"
                            + "[14400000,\"1\",\"2\",\"0.5\",\"1.5\",\"10\",28799999]]"
                    : "[[28800000,\"1\",\"2\",\"0.5\",\"1.5\",\"10\",43199999]]";
            return new PublicDataAdapters.FetchResponse(200,
                    body.getBytes(StandardCharsets.UTF_8), Map.of());
        };
        Path first = temporary.resolve("first.json");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int status = PublicDataAdaptersCommandAdapter.run(new String[] {
                "backfill", "--asset", "btc", "--start", "0", "--end", "28800000",
                "--page-size", "2", "--max-pages", "1", "--captured-at",
                "2026-01-01T00:00:00Z", "--out", first.toString()
        }, new PrintStream(stdout), new PrintStream(stderr), client);
        assertThat(status).as(stderr.toString()).isZero();
        JsonNode firstReceipt = JsonHashes.mapper().readTree(Files.readAllBytes(first));
        assertThat(firstReceipt.path("coverage").path("complete").asBoolean()).isFalse();
        assertThat(firstReceipt.path("rows")).hasSize(2);

        Path second = temporary.resolve("second.json");
        status = PublicDataAdaptersCommandAdapter.run(new String[] {
                "resume", first.toString(), "--asset", "btc", "--end", "28800000",
                "--page-size", "2", "--captured-at", "2026-01-01T00:00:00Z",
                "--out", second.toString()
        }, new PrintStream(stdout), new PrintStream(stderr), client);
        assertThat(status).as(stderr.toString()).isZero();
        JsonNode resumed = JsonHashes.mapper().readTree(Files.readAllBytes(second));
        assertThat(resumed.path("rows")).hasSize(3);
        assertThat(resumed.path("coverage").path("complete").asBoolean()).isTrue();
        assertThat(resumed.path("resumed_from").asText())
                .isEqualTo(firstReceipt.path("content_sha256").asText());

        ObjectNode tampered = (ObjectNode) firstReceipt.deepCopy();
        tampered.put("asset", "eth");
        Path bad = temporary.resolve("tampered.json");
        Files.write(bad, JsonHashes.mapper().writeValueAsBytes(tampered));
        status = PublicDataAdaptersCommandAdapter.run(new String[] {
                "resume", bad.toString(), "--out", temporary.resolve("bad-out.json").toString()
        }, new PrintStream(stdout), new PrintStream(stderr), client);
        assertThat(status).isEqualTo(1);
        assertThat(stderr.toString()).contains("content hash mismatch");
    }

    @Test
    void archiveFetchAndCheckpointResumeBindChecksumsAndRepairChangedRawBytes() throws Exception {
        String csv = "0,100,101,99,100.5,10,14399999,1000,3,4,5,0\n"
                + "14400000,101,102,100,101.5,10,28799999,1000,3,4,5,0\n";
        byte[] zip = storedZip("BTCUSDT_210924-4h-1970-01.csv", csv);
        String checksum = JsonHashes.sha256(zip) + "  fixture.zip\n";
        String capturedAt = "2026-08-28T00:00:00Z";

        AtomicInteger directCalls = new AtomicInteger();
        var directHttp = new PublicDataAdapters.HttpOptions(
                archiveClient(zip, checksum, directCalls, null), capturedAt, true, 0, 0);
        Path directRoot = temporary.resolve("direct-archive");
        var direct = PublicDataAdapters.fetchBinanceDatedKlineArchive(
                new PublicDataAdapters.ArchiveFetchOptions("btc", "BTCUSDT_210924", "4h",
                        "1970-01", 0L, 14_400_000L, directHttp, directRoot));
        assertThat(direct.rows()).hasSize(2);
        assertThat(direct.responseSha256()).containsExactly(
                JsonHashes.sha256(zip), JsonHashes.sha256(checksum));
        assertThat(direct.rawResponses()).allSatisfy(reference -> {
            assertThat(reference.body()).isNull();
            assertThat(directRoot.resolve(reference.path())).exists();
        });
        assertThat(direct.capturedAt()).isEqualTo("2026-08-28T00:00:00.000Z");
        assertThat(directCalls).hasValue(2);

        Path custody = temporary.resolve("archive-backfill");
        AtomicInteger firstCalls = new AtomicInteger();
        var first = PublicDataAdapters.backfillBinanceDatedKlineArchives(
                archiveBackfill(custody, archiveClient(zip, checksum, firstCalls, null),
                        capturedAt, 0, 14_400_000));
        assertThat(first.coverage().path("complete").asBoolean()).isTrue();
        assertThat(first.coverage().path("checkpoint_sha256").asText()).hasSize(64);
        assertThat(firstCalls).hasValue(2);

        AtomicInteger resumeCalls = new AtomicInteger();
        var resumed = PublicDataAdapters.backfillBinanceDatedKlineArchives(
                archiveBackfill(custody, archiveClient(zip, checksum, resumeCalls, null),
                        capturedAt, 0, 14_400_000));
        assertThat(resumed.rows()).hasSize(2);
        assertThat(resumeCalls).hasValue(0);

        Path checkpoint = custody.resolve("checkpoints/dated-btc-btcusdt_210924-4h.json");
        JsonNode checkpointValue = JsonHashes.mapper().readTree(Files.readAllBytes(checkpoint));
        JsonNode zipReference = java.util.stream.StreamSupport.stream(
                        checkpointValue.path("files").path("1970-01").path("raw").spliterator(), false)
                .filter(reference -> "ARCHIVE_ZIP".equals(reference.path("kind").asText()))
                .findFirst().orElseThrow();
        Path retainedZip = custody.resolve(zipReference.path("path").asText());
        Files.writeString(retainedZip, "tampered");
        AtomicInteger repairCalls = new AtomicInteger();
        var repaired = PublicDataAdapters.backfillBinanceDatedKlineArchives(
                archiveBackfill(custody, archiveClient(zip, checksum, repairCalls, null),
                        capturedAt, 0, 14_400_000));
        assertThat(repaired.coverage().path("complete").asBoolean()).isTrue();
        assertThat(repairCalls).hasValue(2);
        assertThat(JsonHashes.sha256(retainedZip)).isEqualTo(JsonHashes.sha256(zip));

        Path hardlink = custody.resolve("raw-archive-alias.bin");
        Files.createLink(hardlink, retainedZip);
        assertThatThrownBy(() -> PublicDataAdapters.backfillBinanceDatedKlineArchives(
                archiveBackfill(custody, archiveClient(zip, checksum, new AtomicInteger(), null),
                        capturedAt, 0, 14_400_000)))
                .hasMessageContaining("singly-linked");
        Files.delete(hardlink);

        String metricsHeader = "create_time,symbol,sum_open_interest,sum_open_interest_value,"
                + "count_toptrader_long_short_ratio,sum_toptrader_long_short_ratio,"
                + "count_long_short_ratio,sum_taker_long_short_vol_ratio";
        long metricTime = 1_699_920_000_000L;
        byte[] metricsZip = storedZip("BTCUSDT-metrics-2023-11-14.csv",
                metricsHeader + "\n" + metricTime + ",BTCUSDT,1,2,1.1,1.2,1.3,1.4\n");
        String metricsChecksum = JsonHashes.sha256(metricsZip) + "  metrics.zip\n";
        AtomicInteger metricsCalls = new AtomicInteger();
        var metrics = PublicDataAdapters.backfillBinanceMetricsArchives(
                new PublicDataAdapters.ArchiveBackfillOptions("btc", null, null,
                        metricTime, metricTime, 10,
                        new PublicDataAdapters.HttpOptions(archiveClient(metricsZip,
                                metricsChecksum, metricsCalls, null), capturedAt, true, 0, 0),
                        temporary.resolve("metrics-backfill"), null, null, 2, false));
        assertThat(metrics.rows()).hasSize(1);
        assertThat(metrics.coverage().path("complete").asBoolean()).isTrue();
        assertThat(metrics.coverage().path("duplicate_events").isBoolean()).isTrue();
        assertThat(metricsCalls).hasValue(2);
    }

    @Test
    void archive404CustodyIsBoundedCachedAndTamperForcesOneRefetch() throws Exception {
        long start = Instant.parse("2021-01-01T00:00:00Z").toEpochMilli();
        long end = Instant.parse("2021-02-01T00:00:00Z").toEpochMilli();
        String csv = start + ",100,101,99,100,1," + (start + 14_399_999)
                + ",10,1,1,1,0\n";
        byte[] zip = storedZip("BTCUSDT_210924-4h-2021-01.csv", csv);
        String checksum = JsonHashes.sha256(zip) + "  fixture.zip\n";
        Path custody = temporary.resolve("missing-archive");
        String capturedAt = Instant.now().toString();

        AtomicInteger firstCalls = new AtomicInteger();
        var first = PublicDataAdapters.backfillBinanceDatedKlineArchives(
                archiveBackfill(custody, archiveClient(zip, checksum, firstCalls, "2021-02"),
                        capturedAt, start, end));
        assertThat(first.coverage().path("complete").asBoolean()).isFalse();
        assertThat(first.coverage().path("missing_months")).containsExactly(
                JsonHashes.mapper().getNodeFactory().textNode("2021-02"));
        assertThat(firstCalls).hasValue(3);

        AtomicInteger cachedCalls = new AtomicInteger();
        PublicDataAdapters.backfillBinanceDatedKlineArchives(
                archiveBackfill(custody, archiveClient(zip, checksum, cachedCalls, "2021-02"),
                        capturedAt, start, end));
        assertThat(cachedCalls).hasValue(0);

        Path checkpoint = custody.resolve("checkpoints/dated-btc-btcusdt_210924-4h.json");
        JsonNode value = JsonHashes.mapper().readTree(Files.readAllBytes(checkpoint));
        String errorPath = value.path("files").path("2021-02").path("raw").get(0)
                .path("path").asText();
        Files.writeString(custody.resolve(errorPath), "tampered");
        AtomicInteger retryCalls = new AtomicInteger();
        PublicDataAdapters.backfillBinanceDatedKlineArchives(
                archiveBackfill(custody, archiveClient(zip, checksum, retryCalls, "2021-02"),
                        capturedAt, start, end));
        assertThat(retryCalls).hasValue(1);
    }

    private static PublicDataAdapters.InjectableHttpClient jsonClient(String json) {
        return (uri, headers) -> new PublicDataAdapters.FetchResponse(
                200, json.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private static PublicDataAdapters.ArchiveBackfillOptions archiveBackfill(
            Path root, PublicDataAdapters.InjectableHttpClient client, String capturedAt,
            long start, long end) {
        return new PublicDataAdapters.ArchiveBackfillOptions(
                "btc", "BTCUSDT_210924", "4h", start, end, 10,
                new PublicDataAdapters.HttpOptions(client, capturedAt, true, 0, 0),
                root, null, null, 2, false);
    }

    private static PublicDataAdapters.InjectableHttpClient archiveClient(
            byte[] zip, String checksum, AtomicInteger calls, String missingToken) {
        return (uri, headers) -> {
            calls.incrementAndGet();
            if (missingToken != null && uri.toString().contains(missingToken)) {
                return new PublicDataAdapters.FetchResponse(404,
                        "<Error><Code>NoSuchKey</Code></Error>".getBytes(StandardCharsets.UTF_8),
                        Map.of());
            }
            byte[] body = uri.toString().endsWith(".CHECKSUM")
                    ? checksum.getBytes(StandardCharsets.UTF_8) : zip;
            return new PublicDataAdapters.FetchResponse(200, body, Map.of());
        };
    }

    private static JsonNode datedOracle() throws IOException {
        try (InputStream input = Objects.requireNonNull(
                PublicDataAdaptersTest.class.getResourceAsStream(
                        "/oracles/public-data-dated-archive-v1.json"),
                "frozen dated-archive oracle is missing")) {
            return JsonHashes.mapper().readTree(input);
        }
    }

    private static byte[] storedZip(String name, String value) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        long crc = crc(data);
        ByteBuffer local = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        local.putInt(0x04034b50).putShort((short) 20).putShort((short) 0).putShort((short) 0);
        local.position(14); local.putInt((int) crc).putInt(data.length).putInt(data.length)
                .putShort((short) nameBytes.length).putShort((short) 0);
        ByteBuffer central = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
        central.putInt(0x02014b50).putShort((short) 20).putShort((short) 20)
                .putShort((short) 0).putShort((short) 0);
        central.position(16); central.putInt((int) crc).putInt(data.length).putInt(data.length)
                .putShort((short) nameBytes.length);
        ByteBuffer eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        eocd.putInt(0x06054b50); eocd.position(8); eocd.putShort((short) 1).putShort((short) 1)
                .putInt(46 + nameBytes.length).putInt(30 + nameBytes.length + data.length);
        return concat(local.array(), nameBytes, data, central.array(), nameBytes, eocd.array());
    }

    private static byte[] deflatedZip(String name, String value, int declaredSize) {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(raw); deflater.finish();
        byte[] buffer = new byte[raw.length + 64];
        int count = deflater.deflate(buffer); deflater.end();
        byte[] data = java.util.Arrays.copyOf(buffer, count);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        long crc = crc(raw);
        ByteBuffer local = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        local.putInt(0x04034b50).putShort((short) 20).putShort((short) 0).putShort((short) 8);
        local.position(14); local.putInt((int) crc).putInt(data.length).putInt(declaredSize)
                .putShort((short) nameBytes.length).putShort((short) 0);
        ByteBuffer central = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
        central.putInt(0x02014b50).putShort((short) 20).putShort((short) 20)
                .putShort((short) 0).putShort((short) 8);
        central.position(16); central.putInt((int) crc).putInt(data.length).putInt(declaredSize)
                .putShort((short) nameBytes.length);
        ByteBuffer eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        eocd.putInt(0x06054b50); eocd.position(8); eocd.putShort((short) 1).putShort((short) 1)
                .putInt(46 + nameBytes.length).putInt(30 + nameBytes.length + data.length);
        return concat(local.array(), nameBytes, data, central.array(), nameBytes, eocd.array());
    }

    private static long crc(byte[] bytes) { CRC32 crc = new CRC32(); crc.update(bytes); return crc.getValue(); }

    private static byte[] concat(byte[]... values) {
        int length = 0; for (byte[] value : values) length += value.length;
        byte[] output = new byte[length]; int cursor = 0;
        for (byte[] value : values) { System.arraycopy(value, 0, output, cursor, value.length); cursor += value.length; }
        return output;
    }
}

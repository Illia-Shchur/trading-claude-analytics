package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.marketdata.PublicDataAdapters;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Public dated-futures archive promotion contracts with tiny retained bytes.
 * The discovery fixture supplies a schema-valid, hash-only catalog; promotion
 * then proves that ZIP/CHECKSUM custody is physically reopened before the
 * historical contract becomes signal-history available.
 */
final class StrategyResearchDataV5DatedArchivePromotionBoundaryTest {
    private static final long FOUR_HOURS = 14_400_000L;
    private static final String SYMBOL = "BTCUSDT_260925";
    private static final String CAPTURED_AT = "2026-10-01T00:00:00.000Z";
    private static final long START = Instant.parse("2026-09-25T00:00:00Z").toEpochMilli();
    private static final long EXPIRY = Instant.parse("2026-09-25T08:00:00Z").toEpochMilli();

    @Test
    void publicPromotionBindsRetainedZipChecksumAndHistoricalRows(@TempDir Path root) throws Exception {
        ObjectNode catalog = discoveredCatalog();
        ObjectNode archive = completeArchive(root);
        ObjectNode options = object();
        options.set("catalog", catalog);
        options.set("archiveResult", archive);
        options.put("root", root.toString()).put("asset", "btc").put("symbol", SYMBOL);

        ObjectNode promoted = StrategyResearchDataV5.recordDatedArchiveIngestion(options);
        ObjectNode contract = findContract(promoted);
        assertThat(contract.path("history_status").asText()).isEqualTo("SIGNAL_HISTORY_AVAILABLE");
        assertThat(contract.path("archive_ingestion_status").asText()).isEqualTo("ARCHIVE_INGESTED");
        assertThat(contract.path("archive_coverage_complete").asBoolean()).isTrue();
        assertThat(contract.path("first_bar_at").asText()).isEqualTo("2026-09-25T00:00:00.000Z");
        assertThat(contract.path("last_bar_at").asText()).isEqualTo("2026-09-25T04:00:00.000Z");
        assertThat(contract.path("archive_raw_references")).hasSize(2);
        assertThat(promoted.path("limitations").toString()).doesNotContain("btc:ARCHIVE_DISCOVERED_NOT_INGESTED");
        byte[] zipBytes = Files.readAllBytes(root.resolve("archives/zip.bin"));
        try (ZipInputStream input = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            ZipEntry entry = input.getNextEntry();
            assertThat(entry).isNotNull();
            assertThat(entry.getName()).isEqualTo("BTCUSDT_260925-2026-09.csv");
            String csv = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(csv).contains(String.valueOf(START), String.valueOf(START + FOUR_HOURS));
        }
        PublicDataAdapters.ParsedArchive parsed = PublicDataAdapters.parseBinanceDatedKlineArchive(
                zipBytes, new PublicDataAdapters.DatedArchiveOptions("btc", SYMBOL, "4h", START, EXPIRY));
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(0).path("event_time").asLong()).isEqualTo(START);
        assertThat(parsed.rows().get(1).path("event_time").asLong()).isEqualTo(START + FOUR_HOURS);
        assertThat(parsed.expiryAt()).isEqualTo("2026-09-25T08:00:00.000Z");
        assertThat(Files.readString(root.resolve("archives/checksum.txt")))
                .contains(StrategyResearchDataV5.hash(zipBytes));
        ObjectNode validation = object();
        validation.set("catalog", promoted);
        validation.put("root", root.toString());
        assertThat(StrategyResearchDataV5.validateDatedFuturesCatalog(validation)).isTrue();
    }

    @Test
    void promotionRequiresCompleteCoverageAndBothRetainedArchiveKinds(@TempDir Path root) throws Exception {
        ObjectNode incomplete = completeArchive(root).set("coverage", object().put("complete", false));
        assertThatThrownBy(() -> StrategyResearchDataV5.recordDatedArchiveIngestion(
                promotionOptions(discoveredCatalog(), incomplete, root)))
                .hasMessageContaining("dated archive promotion requires complete coverage");

        ObjectNode missingChecksum = completeArchive(root);
        ((ObjectNode) missingChecksum.path("raw_responses").get(1).path("request")).put("kind", "ARCHIVE_ZIP");
        assertThatThrownBy(() -> StrategyResearchDataV5.recordDatedArchiveIngestion(
                promotionOptions(discoveredCatalog(), missingChecksum, root)))
                .hasMessageContaining("requires retained ZIP and CHECKSUM references");
    }

    @Test
    void promotionReopensEachPhysicalArchiveByteAndRejectsTampering(@TempDir Path root) throws Exception {
        ObjectNode archive = completeArchive(root);
        Path zip = root.resolve("archives/zip.bin");
        Files.writeString(zip, "changed-after-capture", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> StrategyResearchDataV5.recordDatedArchiveIngestion(
                promotionOptions(discoveredCatalog(), archive, root)))
                .hasMessageContaining("dated archive promotion bytes are missing or tampered: archives/zip.bin");
    }

    @Test
    void promotionRequiresTheRequestedHistoricalContract(@TempDir Path root) throws Exception {
        ObjectNode options = promotionOptions(discoveredCatalog(), completeArchive(root), root).put("symbol", "ETHUSDT_260925");
        assertThatThrownBy(() -> StrategyResearchDataV5.recordDatedArchiveIngestion(options))
                .hasMessageContaining("dated archive promotion target is not in catalog: btc/ETHUSDT_260925");
    }

    @Test
    void hashOnlyCatalogMustDiscloseThatItsListingBytesAreNotReopenable() {
        ObjectNode catalog = discoveredCatalog();
        ArrayNode limitations = array();
        for (JsonNode value : catalog.path("limitations")) {
            if (!"DATED_FUTURES_LISTING_BYTES_HASH_ONLY_UNVERIFIABLE".equals(value.asText())) {
                limitations.add(value);
            }
        }
        catalog.set("limitations", limitations);
        catalog.remove("content_sha256");
        catalog = StrategyResearchDataV5.withHash(catalog);
        ObjectNode request = object();
        request.set("catalog", catalog);
        assertThatThrownBy(() -> StrategyResearchDataV5.validateDatedFuturesCatalog(request))
                .hasMessageContaining("hash-only dated catalog must disclose unverifiable listing bytes");
    }

    @Test
    void ingestedCatalogRequiresBothPhysicalArchiveKinds(@TempDir Path root) throws Exception {
        ObjectNode catalog = StrategyResearchDataV5.recordDatedArchiveIngestion(
                promotionOptions(discoveredCatalog(), completeArchive(root), root));
        ObjectNode contract = (ObjectNode) catalog.path("contracts").get(0);
        ((ArrayNode) contract.path("archive_raw_references")).remove(1);
        catalog.remove("content_sha256");
        catalog = StrategyResearchDataV5.withHash(catalog);
        ObjectNode request = object();
        request.set("catalog", catalog);
        request.put("root", root.toString());
        assertThatThrownBy(() -> StrategyResearchDataV5.validateDatedFuturesCatalog(request))
                .hasMessageContaining("dated contract archive custody lacks ZIP and CHECKSUM bytes");
    }

    private static ObjectNode discoveredCatalog() {
        String endpoint = "https://s3-ap-northeast-1.amazonaws.com/data.binance.vision?delimiter=%2F&prefix=data%2Ffutures%2Fum%2Fmonthly%2Fklines%2FBTCUSDT_";
        String xml = "<ListBucketResult><IsTruncated>false</IsTruncated>"
                + "<Prefix>data/futures/um/monthly/klines/" + SYMBOL + "/</Prefix></ListBucketResult>";
        ObjectNode request = object().put("fixtureOnly", true).put("capturedAt", CAPTURED_AT)
                .put("startAt", iso(START))
                .put("endAt", iso(EXPIRY + FOUR_HOURS));
        request.set("assets", array().add("btc"));
        request.set("listingResponses", array().add(object().put("endpoint", endpoint).put("body", xml)));
        PublicDataAdapters.InjectableHttpClient transport = (uri, headers) -> {
            byte[] body = uri.toString().contains("limit=1")
                    ? kline(START, 100) : kline(EXPIRY - FOUR_HOURS, 101);
            return new PublicDataAdapters.FetchResponse(200, body,
                    Map.of("date", List.of("Thu, 01 Oct 2026 00:00:00 GMT")));
        };
        return StrategyResearchDataV5.discoverBinanceHistoricalDatedFutures(request, transport);
    }

    private static ObjectNode completeArchive(Path root) throws Exception {
        String csv = "open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_asset_volume,taker_buy_quote_asset_volume,ignore\n"
                + START + ",99,101,98,100,1," + (START + FOUR_HOURS - 1) + ",100,1,0.5,50,0\n"
                + (START + FOUR_HOURS) + ",100,102,99,101,1," + (START + 2 * FOUR_HOURS - 1) + ",101,1,0.5,50,0\n";
        byte[] zipBytes = zip("BTCUSDT_260925-2026-09.csv", csv.getBytes(StandardCharsets.UTF_8));
        byte[] checksumBytes = (StrategyResearchDataV5.hash(zipBytes) + "  archives/zip.bin\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("archives"));
        Files.write(root.resolve("archives/zip.bin"), zipBytes);
        Files.write(root.resolve("archives/checksum.txt"), checksumBytes);
        ArrayNode raw = array().add(rawResponse("archives/zip.bin", zipBytes, "ARCHIVE_ZIP"))
                .add(rawResponse("archives/checksum.txt", checksumBytes, "ARCHIVE_CHECKSUM"));
        ObjectNode archive = object();
        archive.set("coverage", object().put("complete", true));
        archive.set("raw_responses", raw);
        archive.set("rows", array().add(bar(START, 100)).add(bar(START + FOUR_HOURS, 101)));
        return archive;
    }

    private static ObjectNode rawResponse(String path, byte[] bytes, String kind) {
        ObjectNode raw = object().put("path", path).put("sha256", StrategyResearchDataV5.hash(bytes))
                .put("bytes", bytes.length);
        raw.set("request", object().put("kind", kind));
        return raw;
    }

    private static ObjectNode promotionOptions(ObjectNode catalog, ObjectNode archive, Path root) {
        ObjectNode options = object();
        options.set("catalog", catalog);
        options.set("archiveResult", archive);
        options.put("root", root.toString()).put("asset", "btc").put("symbol", SYMBOL);
        return options;
    }

    private static ObjectNode findContract(ObjectNode catalog) {
        for (JsonNode value : catalog.path("contracts")) {
            if (SYMBOL.equals(value.path("symbol").asText())) return (ObjectNode) value;
        }
        throw new AssertionError("missing dated contract " + SYMBOL);
    }

    private static byte[] kline(long event, double close) {
        ArrayNode row = array().add(event).add(String.valueOf(close - 1)).add(String.valueOf(close + 1))
                .add(String.valueOf(close - 2)).add(String.valueOf(close)).add("1")
                .add(event + FOUR_HOURS - 1);
        return array().add(row).toString().getBytes(StandardCharsets.UTF_8);
    }

    private static ObjectNode bar(long event, double close) {
        return object().put("event_time", iso(event)).put("close", close);
    }

    private static String iso(long millis) { return Instant.ofEpochMilli(millis).toString(); }

    private static byte[] zip(String name, byte[] bytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(bytes);
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }
    private static ArrayNode array() { return JsonHashes.mapper().createArrayNode(); }
}

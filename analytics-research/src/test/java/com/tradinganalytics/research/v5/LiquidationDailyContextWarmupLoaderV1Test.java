package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Isolated bundle tests: all nine scoped assets are synthetic, with no ignored frozen-data dependency. */
class LiquidationDailyContextWarmupLoaderV1Test {
    private static final List<String> ASSETS = List.of("BTC", "ETH", "SOL", "AAVE", "UNI", "BNB", "LINK", "ZEC", "TRX");
    private static final String SUPPLEMENTAL_HEADER = "open_time,symbol,open,high,low,close,base_volume\n";
    private static final LocalDate FIRST_DAY = LocalDate.of(2022, 4, 1);
    private static final int WARMUP_DAYS = 132;

    @Test
    void opensSyntheticNineAssetBundleAndCombinesSupplementalBarsOnlyForDailyContext(@TempDir Path directory) throws Exception {
        Fixture fixture = Fixture.create(directory.resolve("happy"));
        List<LiquidationStructureRouterV1.Observation> retained = retainedBtcBars();
        int retainedCount = retained.size();

        List<LiquidationStructureRouterV1.DailyPriceContext> rows = load(fixture, retained);

        List<LiquidationStructureRouterV1.DailyPriceContext> btc = forAsset(rows, "BTC");
        assertEquals(200, btc.size());
        assertEquals(FIRST_DAY, btc.get(0).utcDay());
        assertEquals(FIRST_DAY.plusDays(199), btc.get(199).utcDay());
        assertTrue(btc.subList(0, 14).stream().allMatch(row -> row.rsi14() == null));
        assertEquals(100.0, btc.get(14).rsi14(), 0.0, "the RSI seed becomes available after 14 daily changes");
        assertTrue(btc.subList(0, 199).stream().allMatch(row -> row.sma200() == null));
        assertEquals(199.5, btc.get(199).sma200(), 0.0);
        assertEquals(FIRST_DAY.plusDays(200).atStartOfDay(ZoneOffset.UTC).toInstant(), btc.get(199).availableAt());
        assertEquals(LiquidationDailyPriceContextV1.SERIES_ID, btc.get(199).seriesId());

        assertEquals(130, forAsset(rows, "SOL").size(), "the omitted leading 48 hours do not create rows");
        assertEquals(131, forAsset(rows, "ETH").size(), "the incomplete day is omitted rather than filled");
        assertEquals(retainedCount, retained.size(), "the supplemental stream must not mutate retained router features");
        assertEquals("3168", fixture.coverage.path("daily_price_context").path("by_asset").path("BTC")
                .path("hourly_rows").asText());
        assertEquals(48, fixture.coverage.path("daily_price_context").path("by_asset").path("SOL")
                .path("missing_hourly_rows").asInt());
        assertEquals(1, fixture.coverage.path("daily_price_context").path("by_asset").path("SOL")
                .path("missing_hourly_intervals").asInt());
        assertEquals(1, fixture.coverage.path("daily_price_context").path("by_asset").path("ETH")
                .path("internal_hourly_gaps").asInt());
        assertFalse(fixture.coverage.path("daily_price_context").path("historical_outcomes_computed").asBoolean());
        assertTrue(fixture.fileHashes.containsKey("context-warmup/normalized/klines_1h_BTC.csv"));
    }

    @Test
    void rejectsMappedNormalizedCsvWithoutAnExplicitFreezeReceipt(@TempDir Path directory) throws Exception {
        Fixture fixture = Fixture.create(directory.resolve("missing-receipt"));
        fixture.refreshBundle("normalized/klines_1h_BTC.csv");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> load(fixture, retainedBtcBars()));
        assertTrue(error.getMessage().contains("lacks an explicit nested freeze receipt"));
    }

    @Test
    void rejectsSupplementalBytesChangedAfterTheNestedFreeze(@TempDir Path directory) throws Exception {
        Fixture fixture = Fixture.create(directory.resolve("changed-bytes"));
        Path csv = fixture.contextRoot.resolve("normalized/klines_1h_BTC.csv");
        Files.writeString(csv, Files.readString(csv) + "tampered\n");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> load(fixture, retainedBtcBars()));
        assertTrue(error.getMessage().contains("source changed or disappeared after freeze"));
    }

    @Test
    void rejectsOuterManifestBindingThatDoesNotMatchNestedBytes(@TempDir Path directory) throws Exception {
        Fixture fixture = Fixture.create(directory.resolve("parent-binding"));
        ((ObjectNode) fixture.inputManifest.path("daily_context_warmup")).put("manifest_byte_sha256", "0".repeat(64));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> load(fixture, retainedBtcBars()));
        assertTrue(error.getMessage().contains("differs from the outer input binding"));
    }

    @Test
    void rejectsResealedManifestWithDifferentWarmupWindow(@TempDir Path directory) throws Exception {
        Fixture fixture = Fixture.create(directory.resolve("wrong-window"));
        fixture.contextManifest.with("warmup_window").put("start_inclusive", "2022-04-02");
        fixture.refreshBundle(null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> load(fixture, retainedBtcBars()));
        assertTrue(error.getMessage().contains("date windows"));
    }

    @Test
    void rejectsDuplicateSupplementalTimestampAndInvalidOhlcAfterValidResealing(@TempDir Path directory) throws Exception {
        Fixture duplicate = Fixture.create(directory.resolve("duplicate"));
        Path duplicateCsv = duplicate.contextRoot.resolve("normalized/klines_1h_BTC.csv");
        String duplicateText = Files.readString(duplicateCsv).stripTrailing();
        String lastRow = duplicateText.substring(duplicateText.lastIndexOf('\n') + 1);
        Files.writeString(duplicateCsv, duplicateText + "\n" + lastRow + "\n");
        duplicate.refreshBundle(null);
        IllegalArgumentException duplicateError = assertThrows(IllegalArgumentException.class,
                () -> load(duplicate, retainedBtcBars()));
        assertTrue(duplicateError.getMessage().contains("duplicated, or unsorted"));

        Fixture invalidOhlc = Fixture.create(directory.resolve("invalid-ohlc"));
        Path invalidCsv = invalidOhlc.contextRoot.resolve("normalized/klines_1h_BTC.csv");
        String[] lines = Files.readString(invalidCsv).split("\\n", 3);
        String[] first = lines[1].split(",", -1);
        first[3] = "99";
        Files.writeString(invalidCsv, lines[0] + "\n" + String.join(",", first) + "\n" + lines[2]);
        invalidOhlc.refreshBundle(null);
        IllegalArgumentException ohlcError = assertThrows(IllegalArgumentException.class,
                () -> load(invalidOhlc, retainedBtcBars()));
        assertTrue(ohlcError.getMessage().contains("bars are invalid"));
    }

    @Test
    void rejectsDetachedRetainedV004ArchiveIdentity(@TempDir Path directory) throws Exception {
        Fixture fixture = Fixture.create(directory.resolve("retained-binding"));
        ((ObjectNode) fixture.contextManifest.path("retained_v004")).put("archive_manifest_sha256", "0".repeat(64));
        fixture.refreshBundle(null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> load(fixture, retainedBtcBars()));
        assertTrue(error.getMessage().contains("detached from the retained v004 archive manifest"));
    }

    @Test
    void rejectsOutOfRangeAndWrongSymbolSupplementalBars(@TempDir Path directory) throws Exception {
        Fixture outOfRange = Fixture.create(directory.resolve("out-of-range"));
        Path outOfRangeCsv = outOfRange.contextRoot.resolve("normalized/klines_1h_BTC.csv");
        String[] rangeLines = Files.readString(outOfRangeCsv).split("\\n", 3);
        String[] rangeRow = rangeLines[1].split(",", -1);
        rangeRow[0] = "2022-08-11T00:00:00Z";
        Files.writeString(outOfRangeCsv, rangeLines[0] + "\n" + String.join(",", rangeRow) + "\n" + rangeLines[2]);
        outOfRange.refreshBundle(null);
        IllegalArgumentException rangeError = assertThrows(IllegalArgumentException.class,
                () -> load(outOfRange, retainedBtcBars()));
        assertTrue(rangeError.getMessage().contains("outside the hourly source bounds"));

        Fixture wrongSymbol = Fixture.create(directory.resolve("wrong-symbol"));
        Path wrongSymbolCsv = wrongSymbol.contextRoot.resolve("normalized/klines_1h_BTC.csv");
        String[] symbolLines = Files.readString(wrongSymbolCsv).split("\\n", 3);
        String[] symbolRow = symbolLines[1].split(",", -1);
        symbolRow[1] = "ETHUSDT";
        Files.writeString(wrongSymbolCsv, symbolLines[0] + "\n" + String.join(",", symbolRow) + "\n" + symbolLines[2]);
        wrongSymbol.refreshBundle(null);
        IllegalArgumentException symbolError = assertThrows(IllegalArgumentException.class,
                () -> load(wrongSymbol, retainedBtcBars()));
        assertTrue(symbolError.getMessage().contains("wrong symbol"));
    }

    private static List<LiquidationStructureRouterV1.DailyPriceContext> load(Fixture fixture,
            List<LiquidationStructureRouterV1.Observation> retained) {
        return LiquidationHourlyDevelopmentReplayV1.loadDailyContextWarmup(fixture.inputManifest, fixture.runRoot,
                ASSETS, retained, fixture.fileHashes, fixture.coverage, fixture.retainedRoot);
    }

    private static List<LiquidationStructureRouterV1.DailyPriceContext> forAsset(
            List<LiquidationStructureRouterV1.DailyPriceContext> rows, String asset) {
        return rows.stream().filter(row -> asset.equals(row.asset())).toList();
    }

    private static List<LiquidationStructureRouterV1.Observation> retainedBtcBars() {
        ArrayList<LiquidationStructureRouterV1.Observation> bars = new ArrayList<>();
        for (int day = WARMUP_DAYS; day < 200; day++) {
            Instant dayStart = FIRST_DAY.plusDays(day).atStartOfDay(ZoneOffset.UTC).toInstant();
            double close = 100.0 + day;
            for (int hour = 0; hour < 24; hour++) {
                Instant start = dayStart.plusSeconds(hour * 3600L);
                bars.add(new LiquidationStructureRouterV1.Bar("BTC", LiquidationStructureRouterV1.Timeframe.ONE_HOUR,
                        start, start.plusSeconds(3600), close, close, close, close, "retained-btc-fixture"));
            }
        }
        return bars;
    }

    private static final class Fixture {
        final Path runRoot;
        final Path retainedRoot;
        final Path contextRoot;
        final Path nestedFreezePath;
        final Path contextManifestPath;
        final ObjectNode inputManifest = JsonHashes.mapper().createObjectNode();
        final ObjectNode contextManifest = JsonHashes.mapper().createObjectNode();
        final ObjectNode nestedFreeze = JsonHashes.mapper().createObjectNode();
        final Map<String, String> fileHashes = new TreeMap<>();
        final ObjectNode coverage = JsonHashes.mapper().createObjectNode();

        static Fixture create(Path base) throws IOException {
            Files.createDirectories(base);
            Fixture fixture = new Fixture(base);
            fixture.build();
            fixture.refreshBundle(null);
            return fixture;
        }

        private Fixture(Path base) throws IOException {
            runRoot = Files.createDirectories(base.resolve("run"));
            retainedRoot = Files.createDirectories(base.resolve("retained-v004"));
            contextRoot = Files.createDirectories(runRoot.resolve("context-warmup"));
            nestedFreezePath = contextRoot.resolve("data-freeze.json");
            contextManifestPath = contextRoot.resolve("context-warmup-manifest.json");
        }

        void build() throws IOException {
            Path normalized = Files.createDirectories(runRoot.resolve("normalized"));
            Path retainedNormalized = Files.createDirectories(retainedRoot.resolve("normalized"));
            String retainedCsv = "open_time,symbol,open,high,low,close,base_volume\n";
            for (String asset : ASSETS) {
                Files.writeString(normalized.resolve("klines_1h_" + asset + ".csv"), retainedCsv);
                Files.writeString(retainedNormalized.resolve("klines_1h_" + asset + ".csv"), retainedCsv);
            }

            String archiveBytes = "synthetic retained archive manifest\n";
            Files.writeString(retainedRoot.resolve("archive-manifest.json"), archiveBytes);
            Files.writeString(retainedRoot.resolve("input-manifest.json"), "synthetic retained input manifest\n");
            ObjectNode retainedFreeze = JsonHashes.mapper().createObjectNode()
                    .put("schema", "liquidation-exploratory-data-freeze/1");
            retainedFreeze.putObject("files");
            retainedFreeze.put("content_sha256", JsonHashes.ownHash(retainedFreeze));
            writeJson(retainedRoot.resolve("data-freeze.json"), retainedFreeze);

            ObjectNode retained = contextManifest.putObject("retained_v004")
                    .put("archive_manifest_sha256", sha(retainedRoot.resolve("archive-manifest.json")))
                    .put("data_freeze_sha256", sha(retainedRoot.resolve("data-freeze.json")))
                    .put("data_freeze_content_sha256", retainedFreeze.path("content_sha256").asText())
                    .put("input_manifest_sha256", sha(retainedRoot.resolve("input-manifest.json")))
                    .put("window_start", "2022-08-11").put("window_end_exclusive", "2026-09-20");
            addAssets(retained.putArray("assets"));
            ObjectNode august = retained.putObject("august_archives");
            for (String asset : ASSETS) {
                String relative = "normalized/klines_1h_" + asset + ".csv";
                august.putObject(asset).put("normalized_v004_path", relative)
                        .put("normalized_v004_sha256", sha(retainedNormalized.resolve("klines_1h_" + asset + ".csv")));
            }

            contextManifest.put("schema", "liquidation-context-warmup/1");
            addAssets(contextManifest.putArray("assets"));
            contextManifest.putObject("warmup_window").put("start_inclusive", "2022-04-01").put("end_exclusive", "2022-08-11");
            contextManifest.putObject("retained_hourly_window").put("start_inclusive", "2022-08-11").put("end_exclusive", "2026-09-20");
            contextManifest.putObject("decision_window").put("start_inclusive", "2022-11-11").put("end_exclusive", "2026-07-15");
            contextManifest.put("outcome_calculation_performed", false);
            ObjectNode mapped = contextManifest.putObject("files");
            Path supplemental = Files.createDirectories(contextRoot.resolve("normalized"));
            for (String asset : ASSETS) {
                mapped.put(asset, "normalized/klines_1h_" + asset + ".csv");
                Files.writeString(supplemental.resolve("klines_1h_" + asset + ".csv"), supplementalCsv(asset));
            }
            Files.writeString(contextRoot.resolve("coverage.json"), "{\"fixture\":true}\n");
            Files.writeString(contextRoot.resolve("archive-manifest.json"), "synthetic supplemental archive manifest\n");

            inputManifest.put("root", "normalized");
            ObjectNode binding = inputManifest.putObject("daily_context_warmup")
                    .put("schema", "liquidation-context-warmup-input/1")
                    .put("freeze_path", "context-warmup/data-freeze.json")
                    .put("manifest_path", "context-warmup/context-warmup-manifest.json");
            binding.put("freeze_byte_sha256", "0".repeat(64)).put("manifest_byte_sha256", "0".repeat(64));
        }

        void refreshBundle(String omittedReceipt) throws IOException {
            writeJson(contextManifestPath, contextManifest);
            nestedFreeze.removeAll();
            nestedFreeze.put("schema", "liquidation-context-warmup-freeze/1");
            addAssets(nestedFreeze.putArray("assets"));
            ObjectNode receipts = nestedFreeze.putObject("files");
            for (Path file : bundleFiles()) {
                String relative = contextRoot.relativize(file).toString().replace('\\', '/');
                if (!relative.equals(omittedReceipt)) {
                    receipts.putObject(relative).put("bytes", Files.size(file)).put("sha256", sha(file));
                }
            }
            nestedFreeze.put("content_sha256", JsonHashes.ownHash(nestedFreeze));
            writeJson(nestedFreezePath, nestedFreeze);
            ((ObjectNode) inputManifest.path("daily_context_warmup"))
                    .put("freeze_byte_sha256", sha(nestedFreezePath))
                    .put("manifest_byte_sha256", sha(contextManifestPath));
        }

        private List<Path> bundleFiles() {
            ArrayList<Path> files = new ArrayList<>();
            files.add(contextManifestPath);
            files.add(contextRoot.resolve("coverage.json"));
            files.add(contextRoot.resolve("archive-manifest.json"));
            for (String asset : ASSETS) files.add(contextRoot.resolve("normalized/klines_1h_" + asset + ".csv"));
            return files;
        }

        private static String supplementalCsv(String asset) {
            StringBuilder csv = new StringBuilder(SUPPLEMENTAL_HEADER);
            int firstHour = "SOL".equals(asset) ? 48 : 0;
            int missingHour = "ETH".equals(asset) ? 9 * 24 + 12 : -1;
            for (int hour = firstHour; hour < WARMUP_DAYS * 24; hour++) {
                if (hour == missingHour) continue;
                int day = hour / 24;
                double price = 100.0 + day;
                Instant start = FIRST_DAY.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(hour * 3600L);
                csv.append(start).append(',').append(asset).append("USDT,")
                        .append(price).append(',').append(price + 1.0).append(',').append(price - 1.0).append(',')
                        .append(price).append(",1\n");
            }
            return csv.toString();
        }

        private static void addAssets(com.fasterxml.jackson.databind.node.ArrayNode array) {
            ASSETS.forEach(array::add);
        }

        private static void writeJson(Path path, ObjectNode node) throws IOException {
            Files.write(path, JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
        }

        private static String sha(Path path) { return JsonHashes.sha256(path); }
    }
}

package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deterministic archive and auxiliary-checkpoint custody boundaries.
 *
 * <p>The private archive seam is scoped to request inventory, physical-byte, and checksum
 * binding. It does not claim archive-parser or economic correctness, which stays with the public
 * replay/adapter tests.
 */
final class StrategyResearchDataV5ReplayArchiveBoundaryTest {
    private static final String H = "a".repeat(64);

    @Test
    void monthlyKlineArchiveInventoryAndChecksumsAreBound(@TempDir Path root) throws Exception {
        ObjectNode series = series("BINANCE_USDM_DATED_FUTURE", "klines", "2026-01-15T00:00:00Z", "2026-02-05T00:00:00Z");
        List<ObjectNode> raws = new ArrayList<>();
        for (String month : List.of("2026-01", "2026-02")) addArchivePair(root, raws, series, month, false);
        raws.add(raws.get(0).deepCopy());

        Map<String, byte[]> replayed = replayArchives(series, raws, root);
        assertThat(replayed).hasSize(4);
        assertThat(replayed.keySet()).allMatch(endpoint -> endpoint.contains("BTCUSD_20260101-1d-"));
    }

    @Test
    void dailyMetricsArchiveInventoryAndChecksumsAreBound(@TempDir Path root) throws Exception {
        ObjectNode series = series("BINANCE_USDM_PERPETUAL", "metrics_events", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z");
        List<ObjectNode> raws = new ArrayList<>();
        for (String day : List.of("2026-01-01", "2026-01-02")) addArchivePair(root, raws, series, day, true);

        Map<String, byte[]> replayed = replayArchives(series, raws, root);
        assertThat(replayed).hasSize(4);
        assertThat(replayed.keySet()).allMatch(endpoint -> endpoint.contains("BTCUSDT-metrics-"));
    }

    @Test
    void archiveRejectsMissingDuplicateAndMismatchedRequests(@TempDir Path root) throws Exception {
        ObjectNode series = series("BINANCE_USDM_DATED_FUTURE", "klines", "2026-01-15T00:00:00Z", "2026-01-31T00:00:00Z");
        List<ObjectNode> complete = new ArrayList<>();
        addArchivePair(root, complete, series, "2026-01", false);
        List<ObjectNode> missing = new ArrayList<>(complete.subList(0, 1));
        expectArchiveFailure(series, missing, root, "archive file inventory is incomplete or has extra responses");

        List<ObjectNode> duplicate = new ArrayList<>(complete);
        ObjectNode duplicateRaw = complete.get(0).deepCopy();
        duplicateRaw.put("path", "duplicate.bin");
        Files.write(root.resolve("duplicate.bin"), Files.readAllBytes(root.resolve(complete.get(0).path("path").asText())));
        duplicate.add(duplicateRaw);
        expectArchiveFailure(series, duplicate, root, "archive request is duplicated or ambiguous");

        List<ObjectNode> wrongRequest = complete.stream().map(ObjectNode::deepCopy).toList();
        ((ObjectNode) wrongRequest.get(0).path("request")).put("symbol", "ETHUSDT");
        expectArchiveFailure(series, wrongRequest, root, "archive request differs from the frozen series");
    }

    @Test
    void archiveRejectsChecksumWithoutTheZipDigest(@TempDir Path root) throws Exception {
        ObjectNode series = series("BINANCE_USDM_DATED_FUTURE", "klines", "2026-01-15T00:00:00Z", "2026-01-31T00:00:00Z");
        List<ObjectNode> raws = new ArrayList<>();
        addArchivePair(root, raws, series, "2026-01", false);
        ObjectNode checksum = raws.get(1);
        String token = series.path("symbol").asText() + "-1d-2026-01";
        byte[] changed = "b".repeat(64).concat("  " + token + ".zip\n").getBytes(StandardCharsets.UTF_8);
        String path = checksum.path("path").asText();
        Files.write(root.resolve(path), changed);
        String changedHash = StrategyResearchDataV5.hash(changed);
        checksum.put("byte_sha256", changedHash).put("sha256", changedHash).put("bytes", changed.length);
        ((ObjectNode) checksum.path("request")).put("response_sha256", changedHash);
        expectArchiveFailure(series, raws, root, "CHECKSUM binding differs");

        Path malformedRoot = Files.createTempDirectory(root, "malformed-checksum-");
        List<ObjectNode> malformed = new ArrayList<>();
        addArchivePair(malformedRoot, malformed, series, "2026-01", false);
        byte[] noDigest = "checksum unavailable\n".getBytes(StandardCharsets.UTF_8);
        ObjectNode malformedRef = malformed.get(1);
        Files.write(malformedRoot.resolve(malformedRef.path("path").asText()), noDigest);
        String noDigestHash = StrategyResearchDataV5.hash(noDigest);
        malformedRef.put("byte_sha256", noDigestHash).put("sha256", noDigestHash).put("bytes", noDigest.length);
        ((ObjectNode) malformedRef.path("request")).put("response_sha256", noDigestHash);
        expectArchiveFailure(series, malformed, malformedRoot, "checksum response has no SHA-256 digest");
    }

    @Test
    void auxiliaryCheckpointAcceptsCompleteArchivePrefixAndMissingDay(@TempDir Path root) throws Exception {
        ObjectNode series = metricsSeries("2026-08-23T00:00:00Z", "2026-08-23T04:00:00Z");
        ObjectNode complete = checkpoint(root, series, false);
        Object result = verifyAuxiliary(series, root);
        assertThat(recordInt(result, "verifiedRawCount")).isEqualTo(2);
        assertThat(recordInt(result, "savedCount")).isEqualTo(1);
        assertThat(recordInt(result, "remainingCount")).isEqualTo(0);
        assertThat(recordList(result, "missing")).isEmpty();
        assertThat(recordMap(result, "actual")).hasSize(2);
        assertThat(complete.path("content_sha256").asText()).isEqualTo(StrategyResearchDataV5.ownHash(complete));

        Path missingRoot = Files.createTempDirectory(root, "missing-day-");
        ObjectNode missing = checkpoint(missingRoot, series, true);
        Object missingResult = verifyAuxiliary(series, missingRoot);
        assertThat(recordInt(missingResult, "verifiedRawCount")).isEqualTo(1);
        assertThat(recordList(missingResult, "missing")).containsExactly("2026-08-23");
        assertThat(recordMap(missingResult, "actual")).isEmpty();
        assertThat(missing.path("files").path("2026-08-23").path("status").asInt()).isEqualTo(404);
    }

    @Test
    void auxiliaryCheckpointRejectsNonPrefixAndInvalidStatus(@TempDir Path root) throws Exception {
        ObjectNode series = metricsSeries("2026-08-23T00:00:00Z", "2026-08-24T04:00:00Z");
        ObjectNode checkpoint = checkpoint(root, series, false);
        ObjectNode files = (ObjectNode) checkpoint.path("files");
        ObjectNode later = files.path("2026-08-24").deepCopy();
        files.remove("2026-08-23");
        files.set("2026-08-24", later);
        checkpoint.remove("content_sha256");
        checkpoint = StrategyResearchDataV5.withHash(checkpoint);
        Files.write(root.resolve("checkpoints/metrics-btc-btcusdt.json"), pretty(checkpoint));
        expectAuxiliaryFailure(series, root, "not an exact chronological prefix");

        ObjectNode invalid = checkpoint(root, series, false);
        ((ObjectNode) invalid.path("files").path("2026-08-23")).put("status", 206);
        invalid.remove("content_sha256");
        invalid = StrategyResearchDataV5.withHash(invalid);
        Files.write(root.resolve("checkpoints/metrics-btc-btcusdt.json"), pretty(invalid));
        expectAuxiliaryFailure(series, root, "checkpoint status is invalid");

        Path incompleteRoot = Files.createTempDirectory(root, "incomplete-receipt-");
        ObjectNode incomplete = checkpoint(incompleteRoot, series, false);
        ObjectNode incompleteDay = (ObjectNode) incomplete.path("files").path("2026-08-23");
        incompleteDay.set("raw", JsonHashes.mapper().createArrayNode().add(incompleteDay.path("raw").get(0)));
        incomplete.remove("content_sha256");
        incomplete = StrategyResearchDataV5.withHash(incomplete);
        Files.write(incompleteRoot.resolve("checkpoints/metrics-btc-btcusdt.json"), pretty(incomplete));
        expectAuxiliaryFailure(series, incompleteRoot, "archive receipt is incomplete");

        Path hashRoot = Files.createTempDirectory(root, "archive-hash-");
        ObjectNode mismatchedHash = checkpoint(hashRoot, series, false);
        ((ObjectNode) mismatchedHash.path("files").path("2026-08-23")).put("archive_sha256", "b".repeat(64));
        mismatchedHash.remove("content_sha256");
        mismatchedHash = StrategyResearchDataV5.withHash(mismatchedHash);
        Files.write(hashRoot.resolve("checkpoints/metrics-btc-btcusdt.json"), pretty(mismatchedHash));
        expectAuxiliaryFailure(series, hashRoot, "CHECKSUM binding differs");
    }

    private static Map<String, byte[]> replayArchives(ObjectNode series, List<ObjectNode> raws, Path root) {
        try {
            Class<?> custodyType = nested("ReplayReceiptCustody");
            Constructor<?> constructor = custodyType.getDeclaredConstructor(List.class, List.class);
            constructor.setAccessible(true);
            Object custody = constructor.newInstance(List.of(), raws);
            Method method = StrategyResearchDataV5.class.getDeclaredMethod("replayArchiveResponses", ObjectNode.class, custodyType, Path.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String, byte[]> result = (Map<String, byte[]>) method.invoke(null, series, custody, root);
            return result;
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("archive replay reflection", error);
        }
    }

    private static Object verifyAuxiliary(ObjectNode series, Path root) {
        try {
            Method method = StrategyResearchDataV5.class.getDeclaredMethod("verifyAuxiliaryMetricsCheckpoint", ObjectNode.class, Path.class);
            method.setAccessible(true);
            return method.invoke(null, series, root);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("auxiliary checkpoint reflection", error);
        }
    }

    private static void expectArchiveFailure(ObjectNode series, List<ObjectNode> raws, Path root, String message) {
        assertThatThrownBy(() -> replayArchives(series, raws, root)).hasMessageContaining(message);
    }

    private static void expectAuxiliaryFailure(ObjectNode series, Path root, String message) {
        assertThatThrownBy(() -> verifyAuxiliary(series, root)).hasMessageContaining(message);
    }

    private static void addArchivePair(Path root, List<ObjectNode> raws, ObjectNode series, String period, boolean metrics) throws Exception {
        String symbol = series.path("symbol").asText().toUpperCase();
        String interval = series.path("interval").asText();
        String token = metrics ? symbol + "-metrics-" + period : symbol + "-" + interval + "-" + period;
        String base = metrics ? "https://data.binance.vision/data/futures/um/daily/metrics/" + symbol + "/" + token
                : "https://data.binance.vision/data/futures/um/monthly/klines/" + symbol + "/" + interval + "/" + token;
        byte[] zip = archiveBytes(token);
        byte[] checksum = (StrategyResearchDataV5.hash(zip) + "  " + token + ".zip\n").getBytes(StandardCharsets.UTF_8);
        raws.add(raw(root, "archive-" + token + ".zip", zip, "ARCHIVE_ZIP", base + ".zip", symbol, period, metrics));
        raws.add(raw(root, "archive-" + token + ".checksum", checksum, "ARCHIVE_CHECKSUM", base + ".zip.CHECKSUM", symbol, period, metrics));
    }

    private static ObjectNode raw(Path root, String path, byte[] bytes, String kind, String endpoint,
            String symbol, String period, boolean metrics) throws Exception {
        Files.write(root.resolve(path), bytes);
        String digest = StrategyResearchDataV5.hash(bytes);
        ObjectNode request = JsonHashes.mapper().createObjectNode();
        request.put("endpoint", endpoint).put("kind", kind).put("symbol", symbol).put(metrics ? "day" : "month", period);
        request.put("response_sha256", digest);
        return JsonHashes.mapper().createObjectNode().put("kind", kind).put("path", path).put("byte_sha256", digest)
                .put("sha256", digest).put("bytes", bytes.length).set("request", request);
    }

    private static byte[] archiveBytes(String token) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(token + ".csv"));
            zip.write("event_time,close\n1700000000000,100\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static ObjectNode series(String instrument, String type, String start, String end) {
        String symbol = "BINANCE_USDM_DATED_FUTURE".equals(instrument) ? "BTCUSD_20260101" : "BTCUSDT";
        return JsonHashes.mapper().createObjectNode().put("asset", "btc").put("venue", "BINANCE")
                .put("instrument", instrument).put("symbol", symbol).put("interval", "1d")
                .put("series_type", type).put("start_at", start).put("end_at", end);
    }

    private static ObjectNode metricsSeries(String start, String end) {
        ObjectNode series = series("BINANCE_USDM_PERPETUAL", "metrics_events", start, end);
        series.put("expected_event_count", 1).put("expected_step_ms", 14_400_000L).put("required", false);
        return series;
    }

    private static ObjectNode checkpoint(Path root, ObjectNode series, boolean missing) throws Exception {
        List<String> files = days(series);
        ObjectNode identity = JsonHashes.mapper().createObjectNode().put("kind", "METRICS-btc-BTCUSDT")
                .put("asset", "btc").put("symbol", "BTCUSDT")
                .put("start", epoch(series.path("start_at").asText())).put("end", epoch(series.path("end_at").asText()));
        ArrayNode names = identity.putArray("files");
        files.forEach(names::add);
        ObjectNode result = JsonHashes.mapper().createObjectNode().put("key", StrategyResearchDataV5.hash(identity));
        ObjectNode savedFiles = result.putObject("files");
        String symbol = "BTCUSDT";
        if (missing) {
            String day = files.get(0);
            String token = symbol + "-metrics-" + day;
            String base = "https://data.binance.vision/data/futures/um/daily/metrics/" + symbol + "/" + token;
            byte[] body = "404".getBytes(StandardCharsets.UTF_8);
            ObjectNode reference = raw(root, "aux-missing.bin", body, "HTTP_ERROR", base + ".zip", symbol, day, true);
            ((ObjectNode) reference.path("request")).put("status", 404);
            ObjectNode saved = JsonHashes.mapper().createObjectNode().put("file", day).put("status", 404).put("status_code", 404)
                    .put("checked_at", "2026-08-24T20:30:00.000Z").put("recheck_after_ms", 2_592_000_000L);
            saved.set("raw", JsonHashes.mapper().createArrayNode().add(reference));
            savedFiles.set(day, saved);
        } else {
            for (String day : files) {
                String token = symbol + "-metrics-" + day;
                String base = "https://data.binance.vision/data/futures/um/daily/metrics/" + symbol + "/" + token;
                byte[] zip = metricsZip(symbol, day, epoch(series.path("start_at").asText()), epoch(series.path("end_at").asText()));
                byte[] checksum = (StrategyResearchDataV5.hash(zip) + "  " + token + ".zip\n").getBytes(StandardCharsets.UTF_8);
                ObjectNode zipRef = raw(root, "aux-" + day + ".zip", zip, "ARCHIVE_ZIP", base + ".zip", symbol, day, true);
                ObjectNode checksumRef = raw(root, "aux-" + day + ".checksum", checksum, "ARCHIVE_CHECKSUM", base + ".zip.CHECKSUM", symbol, day, true);
                ObjectNode saved = JsonHashes.mapper().createObjectNode().put("file", day).put("status", 200)
                        .put("captured_at", "2026-08-24T20:30:00.000Z").put("archive_sha256", StrategyResearchDataV5.hash(zip))
                        .put("checksum_sha256", StrategyResearchDataV5.hash(checksum));
                saved.set("raw", JsonHashes.mapper().createArrayNode().add(zipRef).add(checksumRef));
                savedFiles.set(day, saved);
            }
        }
        ObjectNode hashed = StrategyResearchDataV5.withHash(result);
        Path path = root.resolve("checkpoints/metrics-btc-btcusdt.json");
        Files.createDirectories(path.getParent());
        Files.write(path, pretty(hashed));
        return hashed;
    }

    private static byte[] metricsZip(String symbol, String day, long start, long end) throws Exception {
        String header = "create_time,symbol,sum_open_interest,sum_open_interest_value,count_toptrader_long_short_ratio,sum_toptrader_long_short_ratio,count_long_short_ratio,sum_taker_long_short_vol_ratio\n";
        long dayStart = epoch(day + "T00:00:00Z");
        long event = Math.max(start, dayStart);
        String csv = header + event + "," + symbol + ",1,2,1.1,1.2,1.3,1.4\n";
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(symbol + "-metrics-" + day + ".csv"));
            zip.write(csv.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static List<String> days(ObjectNode series) {
        LocalDate start = Instant.ofEpochMilli(epoch(series.path("start_at").asText())).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate end = Instant.ofEpochMilli(epoch(series.path("end_at").asText())).atZone(ZoneOffset.UTC).toLocalDate();
        List<String> values = new ArrayList<>();
        while (!start.isAfter(end)) { values.add(start.toString()); start = start.plusDays(1); }
        return values;
    }

    private static long epoch(String value) { return Instant.parse(value).toEpochMilli(); }

    private static byte[] pretty(ObjectNode value) throws Exception { return JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(value); }

    private static Class<?> nested(String name) throws ClassNotFoundException {
        return Class.forName(StrategyResearchDataV5.class.getName() + "$" + name);
    }

    private static int recordInt(Object record, String name) throws Exception {
        return ((Number) record.getClass().getDeclaredMethod(name).invoke(record)).intValue();
    }

    @SuppressWarnings("unchecked")
    private static List<String> recordList(Object record, String name) throws Exception {
        return (List<String>) record.getClass().getDeclaredMethod(name).invoke(record);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, byte[]> recordMap(Object record, String name) throws Exception {
        return (Map<String, byte[]>) record.getClass().getDeclaredMethod(name).invoke(record);
    }
}

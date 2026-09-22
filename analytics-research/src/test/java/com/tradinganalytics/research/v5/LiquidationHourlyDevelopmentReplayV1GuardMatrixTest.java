package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Focused synthetic checks for byte-freeze and malformed-input guards. */
class LiquidationHourlyDevelopmentReplayV1GuardMatrixTest {
    private static final String DIGEST_A = "a".repeat(64);

    @Test
    void policyFreezeRejectsExposedBytesAndEveryMaterialPolicyDrift(@TempDir Path directory) throws Exception {
        Path freezePath = directory.resolve("policy-freeze.json");
        writePolicyFreeze(freezePath, DIGEST_A, true);
        assertThrows(IllegalArgumentException.class, () -> verifyPolicy(policyShape(), DIGEST_A, freezePath));

        writePolicyFreeze(freezePath, "b".repeat(64), false);
        assertThrows(IllegalArgumentException.class, () -> verifyPolicy(policyShape(), DIGEST_A, freezePath));
        writePolicyFreeze(freezePath, DIGEST_A, false);

        List<Consumer<ObjectNode>> mutations = List.of(
                policy -> policy.put("schema", "unexpected"),
                policy -> policy.put("id", "liquidation-exploratory-v002"),
                policy -> policy.put("evidence_phase", "CANDIDATE"),
                policy -> policy.put("promotion_allowed", true),
                policy -> ((ObjectNode) policy.path("statistics")).put("minimum_groups_to_run", 1),
                policy -> ((ObjectNode) policy.path("statistics")).put("reference_minimum_groups", 31),
                policy -> ((ObjectNode) policy.path("execution")).put("funding", "0"));
        for (Consumer<ObjectNode> mutation : mutations) {
            ObjectNode policy = policyShape();
            mutation.accept(policy);
            assertThrows(IllegalArgumentException.class, () -> verifyPolicy(policy, DIGEST_A, freezePath));
        }

        ObjectNode policy = policyShape();
        policy.withArray("variants").removeAll().add("CORE_ONE_ENTRY").add("STAGED_NO_MACRO");
        assertThrows(IllegalArgumentException.class, () -> verifyPolicy(policy, DIGEST_A, freezePath));
    }

    @Test
    void acceptsOnlyTheFrozenPolicyBoundToItsPrecommitBytes(@TempDir Path directory) throws Exception {
        Path repository = findRepositoryRoot();
        Path policyPath = repository.resolve("docs/research/liquidation-exploratory-v003/exploratory-policy.json");
        Path moduleDocs = Path.of("docs").toAbsolutePath().normalize();
        boolean createdDocsLink = false;
        try {
            if (!Files.exists(moduleDocs, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createSymbolicLink(moduleDocs, repository.resolve("docs"));
                    createdDocsLink = true;
                } catch (java.nio.file.FileAlreadyExistsException racedWithExistingPath) {
                    // Do not replace a path created by another test or process.
                } catch (UnsupportedOperationException | IOException unavailable) {
                    Assumptions.assumeTrue(false, "cannot create the temporary module-local docs link: " + unavailable);
                }
            }
            Path parentPath = moduleDocs.resolve("research/liquidation-daily-stress-v002/frozen-precommit.json");
            Assumptions.assumeTrue(Files.isRegularFile(parentPath), "the existing docs path lacks the frozen parent precommit");

            byte[] frozenBytes = Files.readAllBytes(policyPath);
            ObjectNode policy = readObject(policyPath);
            String digest = JsonHashes.sha256(frozenBytes);
            Path freezePath = directory.resolve("bound-policy-freeze.json");
            writePolicyFreeze(freezePath, digest, false);
            verifyPolicy(policy, digest, freezePath);

            ((ObjectNode) policy.path("parent_precommit")).put("byte_sha256", "0".repeat(64));
            byte[] detachedBytes = JsonHashes.mapper().writeValueAsBytes(policy);
            digest = JsonHashes.sha256(detachedBytes);
            writePolicyFreeze(freezePath, digest, false);
            String detachedDigest = digest;
            assertThrows(IllegalArgumentException.class, () -> verifyPolicy(policy, detachedDigest, freezePath));
        } finally {
            if (createdDocsLink) Files.deleteIfExists(moduleDocs);
        }
    }

    @Test
    void dataFreezeRequiresCanonicalManifestAndMatchingRegularFileReceipts(@TempDir Path directory) throws Exception {
        LiquidationHourlyDevelopmentReplayV1.Input input = minimalInput(directory);
        ObjectNode valid = dataFreeze(input, directory);
        verifyDataFreeze(input, valid, directory);

        ObjectNode wrongSchema = valid.deepCopy();
        wrongSchema.put("schema", "unexpected");
        reseal(wrongSchema);
        assertThrows(IllegalArgumentException.class, () -> verifyDataFreeze(input, wrongSchema, directory));
        assertThrows(IllegalArgumentException.class, () -> verifyDataFreeze(input, valid, null));

        ObjectNode nonObjectFiles = valid.deepCopy();
        nonObjectFiles.put("files", "not-an-object");
        reseal(nonObjectFiles);
        assertThrows(IllegalArgumentException.class, () -> verifyDataFreeze(input, nonObjectFiles, directory));

        ObjectNode malformedReceipt = valid.deepCopy();
        ((ObjectNode) malformedReceipt.path("files").path("normalized/source.csv")).put("sha256", "INVALID");
        reseal(malformedReceipt);
        assertThrows(IllegalArgumentException.class, () -> verifyDataFreeze(input, malformedReceipt, directory));

        ObjectNode staleHash = valid.deepCopy();
        ((ObjectNode) staleHash.path("files").path("normalized/source.csv")).put("bytes", -1);
        reseal(staleHash);
        assertThrows(IllegalArgumentException.class, () -> verifyDataFreeze(input, staleHash, directory));

        ObjectNode staleContentHash = valid.deepCopy();
        staleContentHash.put("content_sha256", DIGEST_A);
        assertThrows(IllegalArgumentException.class, () -> verifyDataFreeze(input, staleContentHash, directory));

        ObjectNode missingManifest = valid.deepCopy();
        missingManifest.with("files").remove("input-manifest.json");
        reseal(missingManifest);
        assertThrows(IllegalArgumentException.class, () -> verifyDataFreeze(input, missingManifest, directory));

        ObjectNode missingLoadedSource = valid.deepCopy();
        missingLoadedSource.with("files").remove("coverage.json");
        reseal(missingLoadedSource);
        assertThrows(IllegalArgumentException.class, () -> verifyDataFreeze(input, missingLoadedSource, directory));

        ObjectNode traversal = valid.deepCopy();
        traversal.with("files").set("../escape.csv", JsonHashes.mapper().createObjectNode()
                .put("bytes", 0).put("sha256", DIGEST_A));
        reseal(traversal);
        assertThrows(IllegalArgumentException.class, () -> verifyDataFreeze(input, traversal, directory));

        Files.writeString(directory.resolve("normalized/source.csv"), "tampered\n");
        assertThrows(IllegalArgumentException.class, () -> verifyDataFreeze(input, valid, directory));
    }

    @Test
    void dataFreezeRejectsSymlinkedSourceEvenWhenTargetBytesMatch(@TempDir Path directory) throws Exception {
        LiquidationHourlyDevelopmentReplayV1.Input input = minimalInput(directory);
        Path external = directory.resolveSibling(directory.getFileName() + "-external.csv");
        Files.writeString(external, "outside\n");
        Path link = directory.resolve("normalized/link.csv");
        try {
            Files.createSymbolicLink(link, external);
        } catch (UnsupportedOperationException | IOException unavailable) {
            Assumptions.assumeTrue(false, "test filesystem does not support symbolic links");
        }

        ObjectNode freeze = dataFreeze(input, directory);
        freeze.with("files").set("normalized/link.csv", receipt(external));
        reseal(freeze);
        assertThrows(IllegalArgumentException.class, () -> verifyDataFreeze(input, freeze, directory));
    }

    @Test
    void preflightRejectsRootTraversalMissingMappingsAndMalformedCsv(@TempDir Path directory) throws Exception {
        Path escaped = writeSmallInputFixture(directory.resolve("escaped"));
        ObjectNode manifest = readObject(escaped);
        manifest.put("root", "../outside");
        writeObject(escaped, manifest);
        assertThrows(IllegalArgumentException.class, () -> preflight(escaped));

        Path missingMap = writeSmallInputFixture(directory.resolve("missing-map"));
        manifest = readObject(missingMap);
        manifest.with("files").remove("daily_liquidations");
        writeObject(missingMap, manifest);
        assertThrows(IllegalArgumentException.class, () -> preflight(missingMap));

        Path missingAssetMap = writeSmallInputFixture(directory.resolve("missing-asset-map"));
        manifest = readObject(missingAssetMap);
        ((ObjectNode) manifest.path("files").path("hourly_bars")).remove("BTC");
        writeObject(missingAssetMap, manifest);
        assertThrows(IllegalArgumentException.class, () -> preflight(missingAssetMap));

        Path missingColumn = writeSmallInputFixture(directory.resolve("missing-column"));
        Path daily = directory.resolve("missing-column/normalized/daily_liquidations_v002.csv");
        Files.writeString(daily, Files.readString(daily).replaceFirst("^asset,", "assetname,"));
        assertThrows(IllegalArgumentException.class, () -> preflight(missingColumn));

        Path wrongAsset = writeSmallInputFixture(directory.resolve("wrong-asset"));
        daily = directory.resolve("wrong-asset/normalized/daily_liquidations_v002.csv");
        String csv = Files.readString(daily);
        int firstRowEnd = csv.indexOf('\n') + 1;
        Files.writeString(daily, csv.substring(0, firstRowEnd) + csv.substring(firstRowEnd).replaceFirst("^BTC,", "DOGE,"));
        assertThrows(IllegalArgumentException.class, () -> preflight(wrongAsset));

        Path wrongWidth = writeSmallInputFixture(directory.resolve("wrong-width"));
        daily = directory.resolve("wrong-width/normalized/daily_liquidations_v002.csv");
        csv = Files.readString(daily);
        firstRowEnd = csv.indexOf('\n') + 1;
        int rowEnd = csv.indexOf('\n', firstRowEnd);
        String firstDataRow = csv.substring(firstRowEnd, rowEnd);
        Files.writeString(daily, csv.substring(0, firstRowEnd)
                + firstDataRow.substring(0, firstDataRow.lastIndexOf(',')) + csv.substring(rowEnd));
        assertThrows(IllegalArgumentException.class, () -> preflight(wrongWidth));

        Path nonfinitePrice = writeSmallInputFixture(directory.resolve("nonfinite-price"));
        Path btcBars = directory.resolve("nonfinite-price/normalized/klines_1h_BTC.csv");
        String bars = Files.readString(btcBars);
        String nonfiniteBars = bars.replaceFirst("BTCUSDT,100\\.0,", "BTCUSDT,NaN,");
        assertTrue(!nonfiniteBars.equals(bars), "the synthetic replacement must corrupt the first BTC open price");
        assertTrue(nonfiniteBars.contains("BTCUSDT,NaN,"), "the synthetic CSV must contain the nonfinite open price");
        Files.writeString(btcBars, nonfiniteBars);
        assertThrows(IllegalArgumentException.class, () -> preflight(nonfinitePrice));
    }

    @Test
    void loaderRejectsDailyRowsThatBreakTheFrozenSymbolDateOrValueContract(@TempDir Path directory) throws Exception {
        assertCsvMutationRejected(directory.resolve("daily-midday"), "normalized/daily_liquidations_v002.csv",
                csv -> csv.replaceFirst("2022-08-11T00:00:00Z", "2022-08-11T00:30:00Z"));
        assertCsvMutationRejected(directory.resolve("daily-symbol"), "normalized/daily_liquidations_v002.csv",
                csv -> csv.replaceFirst("BTCUSDT_PERP\\.A", "BTCUSDC_PERP.A"));
        assertCsvMutationRejected(directory.resolve("daily-duplicate"), "normalized/daily_liquidations_v002.csv",
                LiquidationHourlyDevelopmentReplayV1GuardMatrixTest::duplicateFirstDataRow);
        assertCsvMutationRejected(directory.resolve("daily-negative"), "normalized/daily_liquidations_v002.csv",
                csv -> csv.replaceFirst(",100\\.0,0\\n", ",-1,0\\n"));
    }

    @Test
    void loaderRejectsHourlySymbolAlignmentOhlcOrderAndNonpositivePrices(@TempDir Path directory) throws Exception {
        String bars = "normalized/klines_1h_BTC.csv";
        assertCsvMutationRejected(directory.resolve("hourly-symbol"), bars,
                csv -> csv.replaceFirst("BTCUSDT,", "BTCUSDZ,"));
        assertCsvMutationRejected(directory.resolve("hourly-unaligned"), bars,
                csv -> csv.replaceFirst("2022-08-11T00:00:00Z", "2022-08-11T00:01:00Z"));
        assertCsvMutationRejected(directory.resolve("hourly-ohcl"), bars,
                csv -> csv.replaceFirst("BTCUSDT,100\\.0,101\\.0,99\\.0,100\\.5", "BTCUSDT,100.0,100.0,99.0,100.5"));
        assertCsvMutationRejected(directory.resolve("hourly-duplicate"), bars,
                LiquidationHourlyDevelopmentReplayV1GuardMatrixTest::duplicateFirstDataRow);
        assertCsvMutationRejected(directory.resolve("hourly-zero-open"), bars,
                csv -> csv.replaceFirst("BTCUSDT,100\\.0,", "BTCUSDT,0,"));
    }

    @Test
    void loaderRejectsOpenInterestSymbolAlignmentAndNonpositiveSnapshotValues(@TempDir Path directory) throws Exception {
        String oi = "normalized/oi_5m_BTC.csv";
        assertCsvMutationRejected(directory.resolve("oi-symbol"), oi,
                csv -> csv.replaceFirst("BTCUSDT,", "BTCUSDZ,"));
        assertCsvMutationRejected(directory.resolve("oi-unaligned"), oi,
                csv -> csv.replaceFirst("2022-08-11T03:50:00Z", "2022-08-11T03:51:00Z"));
        assertCsvMutationRejected(directory.resolve("oi-zero"), oi,
                csv -> csv.replaceFirst(",100\\n", ",0\\n"));
    }

    @Test
    void sourcePathAndCsvAbsenceFailClosedWhileBlankRowsAreIgnored(@TempDir Path directory) throws Exception {
        Path missing = writeSmallInputFixture(directory.resolve("missing-file"));
        ObjectNode manifest = readObject(missing);
        manifest.with("files").put("sp500", "absent.csv");
        writeObject(missing, manifest);
        assertThrows(IllegalArgumentException.class, () -> preflight(missing));

        Path escaped = writeSmallInputFixture(directory.resolve("escaped-file"));
        manifest = readObject(escaped);
        manifest.with("files").put("sp500", "../fred-sp500.csv");
        writeObject(escaped, manifest);
        assertThrows(IllegalArgumentException.class, () -> preflight(escaped));

        Path empty = writeSmallInputFixture(directory.resolve("empty-header"));
        Files.writeString(directory.resolve("empty-header/normalized/daily_liquidations_v002.csv"), "");
        assertThrows(IllegalArgumentException.class, () -> preflight(empty));

        Path blankRows = writeSmallInputFixture(directory.resolve("blank-rows"));
        Path daily = directory.resolve("blank-rows/normalized/daily_liquidations_v002.csv");
        String rows = Files.readString(daily);
        int firstRowEnd = rows.indexOf('\n') + 1;
        Files.writeString(daily, rows.substring(0, firstRowEnd) + "\n" + rows.substring(firstRowEnd));
        ObjectNode result = preflight(blankRows);
        assertEquals("INPUT_COVERAGE_LIMITED", result.path("status").asText());
        assertEquals(91, result.path("series").path("executor_daily_inputs").path("BTC").path("daily_rows").asInt());

        Path missingMacro = writeSmallInputFixture(directory.resolve("missing-macro"));
        Path sp500 = directory.resolve("missing-macro/normalized/fred-sp500.csv");
        Files.writeString(sp500, "observation_date,SP500\n2022-08-11,\n");
        ObjectNode noMacro = preflight(missingMacro);
        assertTrue(noMacro.path("blockers").toString().contains("no S&P 500 macro rows were loaded"));
    }

    @Test
    void preflightReportsMissingCommonBarsAndHourlyGapsWithoutFillingThem(@TempDir Path directory) throws Exception {
        Path noBars = writeSmallInputFixture(directory.resolve("no-bars"));
        for (String asset : List.of("BTC", "ETH", "SOL", "AAVE")) {
            Files.writeString(directory.resolve("no-bars/normalized/klines_1h_" + asset + ".csv"),
                    "open_time,symbol,open,high,low,close,base_volume\n");
        }
        ObjectNode empty = preflight(noBars);
        assertTrue(empty.path("blockers").toString().contains("no common hourly endpoint exists"));
        assertTrue(empty.path("blockers").toString().contains("no complete H4 aggregates for BTC"));

        Path gap = writeSmallInputFixture(directory.resolve("one-hour-gap"));
        Path btc = directory.resolve("one-hour-gap/normalized/klines_1h_BTC.csv");
        String csv = Files.readString(btc);
        StringBuilder gappedBars = new StringBuilder();
        for (String line : csv.split("\\n")) {
            if (line.startsWith("2022-08-11T01:00:00Z,") || line.startsWith("2022-08-11T04:00:00Z,")) continue;
            gappedBars.append(line).append('\n');
        }
        assertTrue(!gappedBars.toString().equals(csv), "the synthetic hourly rows must leave two incomplete H4 buckets");
        Files.writeString(btc, gappedBars.toString());
        ObjectNode withGap = preflight(gap);
        assertTrue(withGap.path("blockers").toString().contains("hourly price gaps for BTC"));
        assertTrue(withGap.path("blockers").toString().contains("no complete H4 aggregates for BTC"));
    }

    @Test
    void requiredPathAndHashRejectMissingBlankOrNoncanonicalArguments(@TempDir Path directory) throws Exception {
        ObjectNode options = JsonHashes.mapper().createObjectNode();
        assertThrows(IllegalArgumentException.class, () -> requiredPath(options, "input"));
        options.put("input", "  ");
        assertThrows(IllegalArgumentException.class, () -> requiredPath(options, "input"));
        options.put("input", directory.toString());
        assertEquals(directory.toAbsolutePath().normalize(), requiredPath(options, "input"));

        assertThrows(IllegalArgumentException.class, () -> requiredHash(options, "sha"));
        options.put("sha", DIGEST_A.toUpperCase(java.util.Locale.ROOT));
        assertThrows(IllegalArgumentException.class, () -> requiredHash(options, "sha"));
        options.put("sha", DIGEST_A);
        assertEquals(DIGEST_A, requiredHash(options, "sha"));
    }

    private static ObjectNode policyShape() {
        ObjectNode policy = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-exploratory-policy/1")
                .put("id", "liquidation-exploratory-v003").put("evidence_phase", "DEVELOPMENT").put("promotion_allowed", false);
        policy.putObject("statistics").put("minimum_groups_to_run", 0).put("reference_minimum_groups", 30);
        policy.putObject("execution").put("funding", "0.01");
        policy.putArray("variants").add("CORE_ONE_ENTRY").add("STAGED_NO_MACRO").add("STAGED_MACRO");
        return policy;
    }

    private static void writePolicyFreeze(Path path, String policyHash, boolean outcomesViewed) throws IOException {
        ObjectNode freeze = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-exploratory-freeze/1")
                .put("outcomes_viewed", outcomesViewed);
        freeze.putObject("files").put("exploratory-policy.json", policyHash);
        writeObject(path, freeze);
    }

    private static LiquidationHourlyDevelopmentReplayV1.Input minimalInput(Path root) throws IOException {
        Files.createDirectories(root.resolve("normalized"));
        Files.writeString(root.resolve("input-manifest.json"), "{\"synthetic\":true}\n");
        Files.writeString(root.resolve("coverage.json"), "{}\n");
        Files.writeString(root.resolve("normalized/source.csv"), "synthetic\n");
        TreeMap<String, String> hashes = new TreeMap<>();
        for (String relative : List.of("input-manifest.json", "coverage.json", "normalized/source.csv")) {
            hashes.put(relative, JsonHashes.sha256(root.resolve(relative)));
        }
        return new LiquidationHourlyDevelopmentReplayV1.Input(List.of(), Map.of(), new TreeMap<>(), Map.of(), Map.of(),
                Map.of(), JsonHashes.mapper().createObjectNode(), hashes, List.of(), 0, 0, 0, 0, 0,
                hashes.get("input-manifest.json"), root.resolve("input-manifest.json"));
    }

    private static ObjectNode dataFreeze(LiquidationHourlyDevelopmentReplayV1.Input input, Path root) throws IOException {
        ObjectNode freeze = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-exploratory-input-freeze/1");
        ObjectNode files = freeze.putObject("files");
        for (String relative : input.fileHashes.keySet()) files.set(relative, receipt(root.resolve(relative)));
        reseal(freeze);
        return freeze;
    }

    private static ObjectNode receipt(Path path) throws IOException {
        return JsonHashes.mapper().createObjectNode().put("bytes", Files.size(path)).put("sha256", JsonHashes.sha256(path));
    }

    private static void reseal(ObjectNode freeze) { freeze.put("content_sha256", JsonHashes.ownHash(freeze)); }

    private static void verifyPolicy(ObjectNode policy, String digest, Path freezePath) {
        invoke("verifyPolicy", new Class<?>[] {ObjectNode.class, String.class, Path.class}, policy, digest, freezePath);
    }

    private static void verifyDataFreeze(LiquidationHourlyDevelopmentReplayV1.Input input, ObjectNode freeze, Path root) {
        invoke("verifyDataFreeze", new Class<?>[] {LiquidationHourlyDevelopmentReplayV1.Input.class, ObjectNode.class, Path.class},
                input, freeze, root);
    }

    private static Path requiredPath(ObjectNode options, String key) {
        return (Path) invoke("requiredPath", new Class<?>[] {ObjectNode.class, String.class}, options, key);
    }

    private static String requiredHash(ObjectNode options, String key) {
        return (String) invoke("requiredHash", new Class<?>[] {ObjectNode.class, String.class}, options, key);
    }

    private static Object invoke(String name, Class<?>[] types, Object... args) {
        try {
            Method method = LiquidationHourlyDevelopmentReplayV1.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error fatal) throw fatal;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException error) {
            throw new RuntimeException(error);
        }
    }

    private static ObjectNode preflightOptions(Path manifest) {
        return JsonHashes.mapper().createObjectNode().put("input", manifest.toString());
    }

    private static void assertCsvMutationRejected(Path directory, String relativeCsv, UnaryOperator<String> mutation)
            throws Exception {
        Path manifest = writeSmallInputFixture(directory);
        Path csv = directory.resolve(relativeCsv);
        String original = Files.readString(csv);
        String changed = mutation.apply(original);
        assertTrue(!changed.equals(original), "synthetic source mutation must change " + relativeCsv);
        Files.writeString(csv, changed);
        assertThrows(IllegalArgumentException.class, () -> preflight(manifest), relativeCsv);
    }

    private static String duplicateFirstDataRow(String csv) {
        int headerEnd = csv.indexOf('\n') + 1;
        int firstDataEnd = csv.indexOf('\n', headerEnd) + 1;
        String firstDataRow = csv.substring(headerEnd, firstDataEnd);
        return csv.substring(0, firstDataEnd) + firstDataRow + csv.substring(firstDataEnd);
    }

    private static ObjectNode preflight(Path manifest) {
        return LiquidationHourlyDevelopmentReplayV1.preflight(preflightOptions(manifest));
    }

    private static Path writeSmallInputFixture(Path directory) throws Exception {
        Method writer = LiquidationHourlyDevelopmentReplayV1Test.class.getDeclaredMethod("writeSmallInputFixture", Path.class);
        writer.setAccessible(true);
        try { return (Path) writer.invoke(null, directory); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception exception) throw exception;
            throw error;
        }
    }

    private static Path findRepositoryRoot() {
        for (Path path = Path.of("").toAbsolutePath().normalize(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("pom.xml"))
                    && Files.isRegularFile(path.resolve("docs/research/liquidation-exploratory-v003/exploratory-policy.json"))) return path;
        }
        throw new IllegalStateException("frozen policy fixture is not available from this test environment");
    }

    private static ObjectNode readObject(Path path) throws IOException {
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(path));
    }

    private static void writeObject(Path path, ObjectNode value) throws IOException {
        Files.write(path, JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }
}

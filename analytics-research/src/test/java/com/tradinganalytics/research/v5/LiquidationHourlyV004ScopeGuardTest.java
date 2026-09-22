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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fail-closed guards for the frozen v004 nine-asset scope and its v003 lineage. */
class LiquidationHourlyV004ScopeGuardTest {
    private static final List<String> LEGACY_ASSETS = List.of("BTC", "ETH", "SOL", "AAVE");
    private static final List<String> V004_ASSETS = List.of("BTC", "ETH", "SOL", "AAVE", "UNI", "BNB", "LINK", "ZEC", "TRX");

    @Test
    void acceptsOnlyExactLegacyFourOrFrozenV004NineAssetManifestOrders() {
        assertEquals(LEGACY_ASSETS, manifestAssetOrder(JsonHashes.mapper().createObjectNode()));
        assertEquals(LEGACY_ASSETS, manifestAssetOrder(manifestAssets(LEGACY_ASSETS)));
        assertEquals(V004_ASSETS, manifestAssetOrder(manifestAssets(V004_ASSETS)));

        assertManifestRejected(List.of("BTC", "ETH", "SOL", "AAVE", "UNI"));
        assertManifestRejected(List.of("BTC", "ETH", "SOL", "SOL", "UNI", "BNB", "LINK", "ZEC", "TRX"));
        assertManifestRejected(List.of("ETH", "BTC", "SOL", "AAVE", "UNI", "BNB", "LINK", "ZEC", "TRX"));
        assertManifestRejected(List.of("BTC", "ETH", "SOL", "AAVE", "UNI", "BNB", "LINK", "ZEC", "DOGE"));

        ObjectNode nonArray = JsonHashes.mapper().createObjectNode().put("assets", "BTC,ETH,SOL,AAVE");
        IllegalArgumentException wrongContainer = assertThrows(IllegalArgumentException.class, () -> manifestAssetOrder(nonArray));
        assertEquals("input manifest assets must be an ordered array", wrongContainer.getMessage());

        ObjectNode nonTextSymbol = JsonHashes.mapper().createObjectNode();
        nonTextSymbol.putArray("assets").add("BTC").add(7).add("SOL").add("AAVE");
        IllegalArgumentException wrongSymbolType = assertThrows(IllegalArgumentException.class, () -> manifestAssetOrder(nonTextSymbol));
        assertEquals("input manifest assets must contain only symbols", wrongSymbolType.getMessage());
    }

    @Test
    void acceptsFrozenV003LegacyPolicyAndV004NineAssetPolicy(@TempDir Path directory) throws Exception {
        Path repository = findRepositoryRoot();
        withRepositoryDocsVisible(repository, () -> {
            verifyFrozenPolicy(repository.resolve("docs/research/liquidation-exploratory-v003/exploratory-policy.json"),
                    directory.resolve("v003-freeze.json"));
            verifyFrozenPolicy(repository.resolve("docs/research/liquidation-exploratory-v004/exploratory-policy.json"),
                    directory.resolve("v004-freeze.json"));
        });
    }

    @Test
    void replayRejectsPolicyInputAssetOrderAndTieOrderMismatchBeforeSimulation() throws Exception {
        Path repository = findRepositoryRoot();
        ObjectNode v004 = readObject(repository.resolve("docs/research/liquidation-exploratory-v004/exploratory-policy.json"));
        LiquidationHourlyDevelopmentReplayV1.Input legacyInput = emptyInput(LEGACY_ASSETS);
        LiquidationHourlyDevelopmentReplayV1.Input nineAssetInput = emptyInput(V004_ASSETS);

        IllegalArgumentException inputMismatch = assertThrows(IllegalArgumentException.class, () -> replay(legacyInput, v004),
                "a nine-asset frozen policy must not run on legacy-four input");
        assertEquals("policy asset order must exactly match the normalized input manifest asset order", inputMismatch.getMessage());

        ObjectNode wrongTieOrder = v004.deepCopy();
        ((ObjectNode) wrongTieOrder.path("account")).putArray("asset_tie_order")
                .add("ETH").add("BTC").add("SOL").add("AAVE").add("UNI").add("BNB").add("LINK").add("ZEC").add("TRX");
        IllegalArgumentException tieOrderMismatch = assertThrows(IllegalArgumentException.class,
                () -> replay(nineAssetInput, wrongTieOrder),
                "portfolio tie order must remain identical to the policy's frozen asset order");
        assertEquals("policy account asset tie order must equal the frozen asset order", tieOrderMismatch.getMessage());
    }

    @Test
    void nineAssetLoaderRequiresPerAssetCoverageToReconcileWithLoadedHourlyRows(@TempDir Path directory) throws Exception {
        LiquidationHourlyDevelopmentReplayV1.Input valid = loadInput(writeNineAssetInputFixture(directory.resolve("valid-nine"), 8, 0));
        assertEquals(V004_ASSETS, valid.assets);
        assertTrue(valid.preflightBlockers.stream().noneMatch(message -> message.contains("nine-asset hourly row count does not reconcile")));

        record CoverageCase(String name, long expectedRows, long missingRows) {}
        for (CoverageCase invalid : List.of(
                new CoverageCase("negative-expected", -1, 0),
                new CoverageCase("negative-missing", 8, -1),
                new CoverageCase("wrong-retained-count", 9, 0))) {
            LiquidationHourlyDevelopmentReplayV1.Input input = loadInput(writeNineAssetInputFixture(
                    directory.resolve(invalid.name()), invalid.expectedRows(), invalid.missingRows()));
            assertTrue(input.preflightBlockers.contains(
                    "nine-asset hourly row count does not reconcile to retained per-asset coverage for UNI"), invalid.name());
        }
    }

    @Test
    void legacyFourLoaderStillReportsEmptyHourlySeriesAtItsLegacyBoundary(@TempDir Path directory) throws Exception {
        Path manifest = writeSmallInputFixture(directory);
        Files.writeString(directory.resolve("normalized/klines_1h_BTC.csv"),
                "open_time,symbol,open,high,low,close,base_volume\n");

        LiquidationHourlyDevelopmentReplayV1.Input input = loadInput(manifest);

        assertTrue(input.preflightBlockers.contains("no hourly price bars for BTC"));
        assertTrue(input.preflightBlockers.stream().noneMatch(message -> message.contains("nine-asset hourly row count")));
    }

    @Test
    void hourlyAccountAcceptsNineFrozenAssetsAndSerializesLegacyFourInCanonicalPositionOrder() {
        ObjectNode allNine = accountRequest(V004_ASSETS);
        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(allNine);
        assertEquals(V004_ASSETS, positionAssetOrder(result));

        ArrayList<String> reversedLegacy = new ArrayList<>(LEGACY_ASSETS);
        java.util.Collections.reverse(reversedLegacy);
        ObjectNode legacy = accountRequest(reversedLegacy);
        assertEquals(LEGACY_ASSETS, positionAssetOrder(LiquidationPortfolioAccountingV1.replayFixture(legacy)));
    }

    @Test
    void unsupportedAccountAssetIsRejectedBeforeOrderingAndItsDefensiveRankSortsLast() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioAccountingV1.replayFixture(accountRequest(List.of("DOGE"))));
        assertEquals("position assets must be distinct frozen symbols", rejected.getMessage());
        assertEquals(Integer.MAX_VALUE, accountingAssetRank("DOGE"));
    }

    @Test
    void rejectsV004WhenAnyPredecessorPolicyFreezeOrResultsReceiptDiffers(@TempDir Path directory) throws Exception {
        Path repository = findRepositoryRoot();
        Path policyPath = repository.resolve("docs/research/liquidation-exploratory-v004/exploratory-policy.json");
        ObjectNode frozen = readObject(policyPath);
        List<String> predecessorReceipts = List.of("policy_byte_sha256", "freeze_byte_sha256", "compact_results_byte_sha256");
        withRepositoryDocsVisible(repository, () -> {
            for (String receipt : predecessorReceipts) {
                ObjectNode drifted = frozen.deepCopy();
                ((ObjectNode) drifted.path("predecessor_experiment")).put(receipt, "0".repeat(64));
                assertPolicyRejectedWithSelfConsistentFreeze(drifted, directory.resolve(receipt + ".json"),
                        "v004 predecessor binding does not match exposed v003 policy, freeze and compact results");
            }

            ObjectNode wrongPredecessorId = frozen.deepCopy();
            ((ObjectNode) wrongPredecessorId.path("predecessor_experiment")).put("id", "unknown-predecessor");
            assertPolicyRejectedWithSelfConsistentFreeze(wrongPredecessorId, directory.resolve("predecessor-id.json"),
                    "v004 predecessor binding does not match exposed v003 policy, freeze and compact results");

            ObjectNode unexposedPredecessor = frozen.deepCopy();
            ((ObjectNode) unexposedPredecessor.path("predecessor_experiment")).put("outcomes_exposed", false);
            assertPolicyRejectedWithSelfConsistentFreeze(unexposedPredecessor, directory.resolve("predecessor-exposure.json"),
                    "v004 predecessor binding does not match exposed v003 policy, freeze and compact results");
        });
    }

    @Test
    void rejectsNondiagnosticOrRuleChangingV004AuditPolicy(@TempDir Path directory) throws Exception {
        Path repository = findRepositoryRoot();
        ObjectNode frozen = readObject(repository.resolve("docs/research/liquidation-exploratory-v004/exploratory-policy.json"));
        List<java.util.function.Consumer<ObjectNode>> mutations = List.of(
                policy -> ((ObjectNode) policy.path("entry_rule_audit")).put("diagnostic_only", false),
                policy -> ((ObjectNode) policy.path("entry_rule_audit")).put("rule_changes", true),
                policy -> ((ObjectNode) policy.path("entry_rule_audit")).put("outcome_optimization", true));
        withRepositoryDocsVisible(repository, () -> {
            int index = 0;
            for (var mutation : mutations) {
                ObjectNode drifted = frozen.deepCopy();
                mutation.accept(drifted);
                assertPolicyRejectedWithSelfConsistentFreeze(drifted, directory.resolve("audit-" + index++ + ".json"),
                        "v004 entry-rule audit must be diagnostic-only and cannot change or optimize the frozen rules");
            }

            ObjectNode missingDiagnosticAttestation = frozen.deepCopy();
            ((ObjectNode) missingDiagnosticAttestation.path("entry_rule_audit")).remove("diagnostic_only");
            assertPolicyRejectedWithSelfConsistentFreeze(missingDiagnosticAttestation, directory.resolve("audit-missing.json"),
                    "v004 entry-rule audit must be diagnostic-only and cannot change or optimize the frozen rules");

            ObjectNode missingRuleChangeAttestation = frozen.deepCopy();
            ((ObjectNode) missingRuleChangeAttestation.path("entry_rule_audit")).remove("rule_changes");
            assertPolicyRejectedWithSelfConsistentFreeze(missingRuleChangeAttestation, directory.resolve("audit-rules-missing.json"),
                    "v004 entry-rule audit must be diagnostic-only and cannot change or optimize the frozen rules");

            ObjectNode missingOptimizationAttestation = frozen.deepCopy();
            ((ObjectNode) missingOptimizationAttestation.path("entry_rule_audit")).remove("outcome_optimization");
            assertPolicyRejectedWithSelfConsistentFreeze(missingOptimizationAttestation, directory.resolve("audit-optimization-missing.json"),
                    "v004 entry-rule audit must be diagnostic-only and cannot change or optimize the frozen rules");
        });
    }

    @Test
    void rejectsMalformedPolicyAssetArraysBeforeCheckingParentLineage(@TempDir Path directory) throws Exception {
        Path repository = findRepositoryRoot();
        ObjectNode frozenLegacy = readObject(repository.resolve("docs/research/liquidation-exploratory-v003/exploratory-policy.json"));
        List<java.util.function.Consumer<ObjectNode>> mutations = List.of(
                policy -> policy.put("assets", "BTC,ETH,SOL,AAVE"),
                policy -> policy.withArray("assets").removeAll().add("BTC").add(7).add("SOL").add("AAVE"),
                policy -> policy.withArray("assets").removeAll().add("ETH").add("BTC").add("SOL").add("AAVE"));
        for (int index = 0; index < mutations.size(); index++) {
            ObjectNode changed = frozenLegacy.deepCopy();
            mutations.get(index).accept(changed);
            assertPolicyRejectedWithSelfConsistentFreeze(changed, directory.resolve("invalid-assets-" + index + ".json"),
                    "frozen policy does not match a supported exploratory-only asset boundary");
        }
    }

    private static void verifyFrozenPolicy(Path policyPath, Path freezePath) {
        ObjectNode policy = readObject(policyPath);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(policyPath);
        } catch (IOException error) {
            throw new RuntimeException(error);
        }
        String digest = JsonHashes.sha256(bytes);
        writePolicyFreeze(freezePath, digest);
        invoke("verifyPolicy", new Class<?>[] {ObjectNode.class, String.class, Path.class}, policy, digest, freezePath);
    }

    private static void assertPolicyRejectedWithSelfConsistentFreeze(ObjectNode policy, Path freezePath, String expectedMessage) {
        try {
            byte[] bytes = JsonHashes.mapper().writeValueAsBytes(policy);
            String digest = JsonHashes.sha256(bytes);
            writePolicyFreeze(freezePath, digest);
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> invoke("verifyPolicy", new Class<?>[] {ObjectNode.class, String.class, Path.class}, policy, digest, freezePath));
            assertEquals(expectedMessage, rejected.getMessage());
        } catch (IOException error) {
            throw new RuntimeException(error);
        }
    }

    private static void writePolicyFreeze(Path freezePath, String digest) {
        ObjectNode freeze = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-exploratory-freeze/1")
                .put("outcomes_viewed", false);
        freeze.putObject("files").put("exploratory-policy.json", digest);
        try {
            Files.write(freezePath, JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(freeze));
        } catch (IOException error) {
            throw new RuntimeException(error);
        }
    }

    private static ObjectNode manifestAssets(List<String> assets) {
        ObjectNode manifest = JsonHashes.mapper().createObjectNode();
        var values = manifest.putArray("assets");
        assets.forEach(values::add);
        return manifest;
    }

    private static void assertManifestRejected(List<String> assets) {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> manifestAssetOrder(manifestAssets(assets)));
        assertEquals("input manifest assets must equal the exact frozen legacy-four or v004 nine-asset order", rejected.getMessage());
    }

    private static List<String> manifestAssetOrder(ObjectNode manifest) {
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) invoke("manifestAssetOrder", new Class<?>[] {ObjectNode.class}, manifest);
        return result;
    }

    private static LiquidationHourlyDevelopmentReplayV1.Input emptyInput(List<String> assets) {
        return new LiquidationHourlyDevelopmentReplayV1.Input(assets, List.of(), Map.of(), new TreeMap<>(), Map.of(), Map.of(),
                Map.of(), JsonHashes.mapper().createObjectNode(), Map.of(), List.of(), 0, 0, 0, 0, 0, "", null);
    }

    private static ObjectNode accountRequest(List<String> assets) {
        ObjectNode request = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-shared-account-fixture/1")
                .put("initial_equity_usdt", 20_000).put("reserved_costs_usdt", 0);
        var positions = request.putArray("positions");
        for (String asset : assets) {
            positions.addObject().put("asset", asset).put("direction", "LONG").put("common_stop", 90)
                    .putNull("recovery_target").put("lot_size", 0.001).put("minimum_notional", 5)
                    .put("taker_fee_rate", 0).put("slippage_rate", 0).put("liquidation_fee_rate", 0)
                    .put("initial_mark_price", 100);
        }
        request.putArray("events");
        return request;
    }

    private static List<String> positionAssetOrder(ObjectNode result) {
        ArrayList<String> assets = new ArrayList<>();
        result.path("positions").forEach(row -> assets.add(row.path("asset").asText()));
        return List.copyOf(assets);
    }

    private static int accountingAssetRank(String asset) {
        return (Integer) invokeOn(LiquidationPortfolioAccountingV1.class, "assetRank", new Class<?>[] {String.class}, asset);
    }

    private static LiquidationHourlyDevelopmentReplayV1.Input loadInput(Path manifest) {
        return (LiquidationHourlyDevelopmentReplayV1.Input) invoke("loadInput", new Class<?>[] {Path.class}, manifest);
    }

    private static Path writeSmallInputFixture(Path directory) {
        return (Path) invokeOn(LiquidationHourlyDevelopmentReplayV1Test.class, "writeSmallInputFixture",
                new Class<?>[] {Path.class}, directory);
    }

    private static Path writeNineAssetInputFixture(Path directory, long uniExpectedRows, long uniMissingRows) {
        Path manifestPath = writeSmallInputFixture(directory);
        Path normalized = directory.resolve("normalized");
        ObjectNode manifest = readObject(manifestPath);
        var assetArray = manifest.putArray("assets");
        V004_ASSETS.forEach(assetArray::add);

        try {
            Path dailyPath = normalized.resolve("daily_liquidations_v002.csv");
            String daily = Files.readString(dailyPath);
            List<String> solDailyRows = daily.lines().filter(line -> line.startsWith("SOL,")).toList();
            StringBuilder expandedDaily = new StringBuilder(daily);
            for (String asset : V004_ASSETS.subList(4, V004_ASSETS.size())) {
                for (String row : solDailyRows) {
                    expandedDaily.append(row.replaceFirst("^SOL,", asset + ",")
                            .replace("SOLUSDT_PERP.A", asset + "USDT_PERP.A")).append('\n');
                }
                for (String type : List.of("klines_1h", "oi_5m")) {
                    String sourceName = (type.equals("klines_1h") ? "klines_1h_SOL" : "oi_5m_SOL") + ".csv";
                    String destinationName = (type.equals("klines_1h") ? "klines_1h_" : "oi_5m_") + asset + ".csv";
                    String sourceContents = Files.readString(normalized.resolve(sourceName));
                    Files.writeString(normalized.resolve(destinationName), sourceContents.replace("SOLUSDT", asset + "USDT"));
                    ObjectNode files = (ObjectNode) manifest.path("files");
                    ((ObjectNode) files.path(type.equals("klines_1h") ? "hourly_bars" : "oi_5m"))
                            .put(asset, destinationName);
                }
            }
            Files.writeString(dailyPath, expandedDaily.toString());

            ObjectNode coverage = JsonHashes.mapper().createObjectNode();
            ObjectNode datasets = coverage.putObject("datasets");
            for (String asset : V004_ASSETS) {
                long expectedRows = asset.equals("UNI") ? uniExpectedRows : 8;
                long missingRows = asset.equals("UNI") ? uniMissingRows : 0;
                datasets.putObject(asset).putObject("klines_1h")
                        .put("expected_rows", expectedRows).put("missing_rows", missingRows);
            }
            Files.write(directory.resolve("coverage.json"), JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(coverage));
            Files.write(manifestPath, JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            return manifestPath;
        } catch (IOException error) {
            throw new RuntimeException(error);
        }
    }

    private static ObjectNode replay(LiquidationHourlyDevelopmentReplayV1.Input input, ObjectNode policy) {
        return (ObjectNode) invoke("replay", new Class<?>[] {LiquidationHourlyDevelopmentReplayV1.Input.class, ObjectNode.class}, input, policy);
    }

    private static Object invoke(String methodName, Class<?>[] parameterTypes, Object... args) {
        return invokeOn(LiquidationHourlyDevelopmentReplayV1.class, methodName, parameterTypes, args);
    }

    private static Object invokeOn(Class<?> owner, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = owner.getDeclaredMethod(methodName, parameterTypes);
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

    private static ObjectNode readObject(Path path) {
        try {
            return (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(path));
        } catch (IOException error) {
            throw new RuntimeException(error);
        }
    }

    private static Path findRepositoryRoot() {
        for (Path path = Path.of("").toAbsolutePath().normalize(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("pom.xml"))
                    && Files.isRegularFile(path.resolve("docs/research/liquidation-exploratory-v004/exploratory-policy.json"))) return path;
        }
        throw new IllegalStateException("frozen v004 policy fixture is not available from this test environment");
    }

    private static void withRepositoryDocsVisible(Path repository, Runnable action) {
        Path docs = Path.of("docs").toAbsolutePath().normalize();
        boolean createdLink = false;
        try {
            if (!Files.exists(docs, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createSymbolicLink(docs, repository.resolve("docs"));
                    createdLink = true;
                } catch (java.nio.file.FileAlreadyExistsException racedWithExistingPath) {
                    // Leave a path created by another test or process alone.
                } catch (UnsupportedOperationException | IOException unavailable) {
                    throw new AssertionError("cannot expose the repository's frozen docs to policy verification", unavailable);
                }
            }
            if (!Files.isRegularFile(docs.resolve("research/liquidation-daily-stress-v002/frozen-precommit.json"))) {
                throw new AssertionError("current working directory does not expose the frozen predecessor precommit: " + docs);
            }
            action.run();
        } finally {
            if (createdLink) {
                try { Files.deleteIfExists(docs); }
                catch (IOException error) { throw new RuntimeException(error); }
            }
        }
    }
}

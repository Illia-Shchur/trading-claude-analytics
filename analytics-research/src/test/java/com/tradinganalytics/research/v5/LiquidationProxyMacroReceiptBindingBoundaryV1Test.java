package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A proxy S&P row is usable only through its reopened public-archive assumption receipt and window. */
class LiquidationProxyMacroReceiptBindingBoundaryV1Test {
    private static final long SOURCE = Instant.parse("2022-08-11T00:00:00Z").toEpochMilli();
    private static final long DECISION = Instant.parse("2022-11-11T00:00:00Z").toEpochMilli();
    private static final long CLOSE = Instant.parse("2024-01-02T21:00:00Z").toEpochMilli();
    private static final long AVAILABLE = Instant.parse("2024-01-03T21:00:00Z").toEpochMilli();
    private static final long END = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli();

    @TempDir Path temporary;

    @Test
    void proxyMacroCloseRequiresPublicArchiveScopeDeclaredAvailabilityAndHalfOpenPartitionWindow() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("proxy-macro")).toRealPath();
        ObjectNode inputs = JsonHashes.mapper().createObjectNode();
        addPartition(root, inputs, "feature", "daily", "COINALYZE_PROXY", List.of(daily()), SOURCE, SOURCE + day(), null);
        addPartition(root, inputs, "feature", "price", "PUBLIC_ARCHIVE", List.of(price()), SOURCE + day(), SOURCE + 2 * day(), null);
        addPartition(root, inputs, "feature", "macro", "PUBLIC_ARCHIVE", List.of(macro()), CLOSE, CLOSE + day(), null);
        addPartition(root, inputs, "label", "labels", "DERIVED_OUTCOME_LABEL", List.of(
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("episode_id", "macro-receipt")
                        .put("decision_time", DECISION).put("label", "UNINSPECTED")), DECISION, DECISION + 60_000L, null);
        addPartition(root, inputs, "execution", "execution", "PUBLIC_ARCHIVE", List.of(
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("open_time", DECISION)
                        .put("open", 100).put("high", 101).put("low", 99).put("close", 100).put("base_volume", 10)),
                DECISION, DECISION + 60_000L, null);
        addPartition(root, inputs, "mark", "mark", "PUBLIC_ARCHIVE", List.of(
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("timestamp", DECISION)
                        .put("open", 100).put("high", 101).put("low", 99).put("close", 100)), DECISION, DECISION + 60_000L, null);
        addPartition(root, inputs, "funding", "funding", "PUBLIC_ARCHIVE", List.of(
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("settlement_time", DECISION + 7)
                        .put("event_id", "proxy-macro-funding").put("funding_rate", 0).put("mark_price", 100)
                        .put("funding_interval_hours", 8)), DECISION, DECISION + 8L * 3_600_000L, null);

        ObjectNode metadata = JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("tier_index", 1)
                .put("effective_from", SOURCE).put("effective_until", END).put("lot_size", 0.001)
                .put("minimum_notional", 5).put("taker_fee_rate", 0.0005).put("slippage_rate", 0.0001)
                .put("liquidation_fee_rate", 0.01).put("tier_notional_cap", 1_000_000_000)
                .put("maintenance_margin_rate", 0.005).put("maintenance_deduction", 0).put("terminal_tier", true);
        ObjectNode assumption = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-metadata-assumption/1")
                .put("version", 1).put("source_class", "HISTORICAL_APPROXIMATION")
                .put("assumption_description", "Present contract fields are disclosed as a historical approximation.")
                .put("historical_availability_proven", false).put("historical_revision_proven", false)
                .put("authoritative", false);
        addPartition(root, inputs, "metadata", "metadata", "PUBLIC_ARCHIVE", List.of(metadata), SOURCE, END, assumption);

        ObjectNode options = JsonHashes.mapper().createObjectNode().put("root", root.toString());
        options.set("profile", LiquidationDailyStressProfileV1.frozenContract());
        options.set("inputs", inputs);
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildDevelopment(options);
        ObjectNode rebuiltManifest = LiquidationV2PhysicalDataV1.buildDevelopment(options);
        assertTrue(JsonHashes.canonicalSha256(manifest).equals(JsonHashes.canonicalSha256(rebuiltManifest)),
                "reopening an identical proxy fixture reuses only matching immutable normalized, receipt, staging, and Parquet artifacts");
        options.set("manifest", manifest);
        var session = LiquidationV2PhysicalDataV1.openVerifiedDevelopment(options);

        assertTrue(LiquidationV2PhysicalDataV1.macroAvailabilityReceiptBound(session, CLOSE),
                "the source, normalized row scope, next-session basis, and in-range close jointly bind the modeled input");
        assertFalse(LiquidationV2PhysicalDataV1.macroAvailabilityReceiptBound(session, CLOSE - 1));
        assertFalse(LiquidationV2PhysicalDataV1.macroAvailabilityReceiptBound(session, CLOSE + day()),
                "a close at the partition's exclusive end is outside its receipt-bound window");
    }

    @Test
    void reopenedProxyManifestRejectsIndependentDerivedStateAndPhysicalByteMutations() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("proxy-reopen-mutations")).toRealPath();
        ObjectNode inputs = JsonHashes.mapper().createObjectNode();
        addPartition(root, inputs, "feature", "daily", "COINALYZE_PROXY", List.of(daily()), SOURCE, SOURCE + day(), null);
        addPartition(root, inputs, "feature", "price", "PUBLIC_ARCHIVE", List.of(price()), SOURCE + day(), SOURCE + 2 * day(), null);
        addPartition(root, inputs, "feature", "macro", "PUBLIC_ARCHIVE", List.of(macro()), CLOSE, CLOSE + day(), null);
        addPartition(root, inputs, "label", "labels", "DERIVED_OUTCOME_LABEL", List.of(
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("episode_id", "macro-receipt")
                        .put("decision_time", DECISION).put("label", "UNINSPECTED")), DECISION, DECISION + 60_000L, null);
        addPartition(root, inputs, "execution", "execution", "PUBLIC_ARCHIVE", List.of(
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("open_time", DECISION)
                        .put("open", 100).put("high", 101).put("low", 99).put("close", 100).put("base_volume", 10)),
                DECISION, DECISION + 60_000L, null);
        addPartition(root, inputs, "mark", "mark", "PUBLIC_ARCHIVE", List.of(
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("timestamp", DECISION)
                        .put("open", 100).put("high", 101).put("low", 99).put("close", 100)),
                DECISION, DECISION + 60_000L, null);
        addPartition(root, inputs, "funding", "funding", "PUBLIC_ARCHIVE", List.of(
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("settlement_time", DECISION + 7)
                        .put("event_id", "proxy-macro-funding").put("funding_rate", 0).put("mark_price", 100)
                        .put("funding_interval_hours", 8)), DECISION, DECISION + 8L * 3_600_000L, null);
        ObjectNode metadata = JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("tier_index", 1)
                .put("effective_from", SOURCE).put("effective_until", END).put("lot_size", 0.001)
                .put("minimum_notional", 5).put("taker_fee_rate", 0.0005).put("slippage_rate", 0.0001)
                .put("liquidation_fee_rate", 0.01).put("tier_notional_cap", 1_000_000_000)
                .put("maintenance_margin_rate", 0.005).put("maintenance_deduction", 0).put("terminal_tier", true);
        ObjectNode assumption = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-metadata-assumption/1")
                .put("version", 1).put("source_class", "HISTORICAL_APPROXIMATION")
                .put("assumption_description", "Present contract fields are disclosed as a historical approximation.")
                .put("historical_availability_proven", false).put("historical_revision_proven", false)
                .put("authoritative", false);
        addPartition(root, inputs, "metadata", "metadata", "PUBLIC_ARCHIVE", List.of(metadata), SOURCE, END, assumption);

        ObjectNode options = JsonHashes.mapper().createObjectNode().put("root", root.toString());
        options.set("profile", LiquidationDailyStressProfileV1.frozenContract());
        options.set("inputs", inputs);
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildDevelopment(options);
        ObjectNode accepted = options.deepCopy();
        accepted.set("manifest", manifest.deepCopy());
        assertTrue(LiquidationV2PhysicalDataV1.verifyDevelopment(accepted).path("partition_count").asInt() >= 7,
                "every negative vector starts from an accepted, fully reopened physical manifest");

        rejectReopenedManifest(options, manifest, "metadata assumption content changed", candidate ->
                ((ObjectNode) candidate.path("artifacts").path("metadata").path("partitions").get(0))
                        .put("assumption_content_sha256", JsonHashes.sha256("changed-assumption-content")));
        rejectReopenedManifest(options, manifest, "v002 Parquet partition bytes changed", candidate ->
                firstPartition(candidate, "feature").put("bytes", firstPartition(candidate, "feature").path("bytes").asLong() + 1));
        rejectReopenedManifest(options, manifest, "v002 Parquet partition fails canonical source reopen", candidate ->
                firstPartition(candidate, "feature").put("rows_sha256", JsonHashes.sha256("different-canonical-rows")));
        rejectReopenedManifest(options, manifest, "v002 Parquet partition fails canonical source reopen", candidate ->
                firstPartition(candidate, "feature").put("schema_sha256", JsonHashes.sha256("different-parquet-schema")));
        rejectReopenedManifest(options, manifest, "v002 physical partition paths are not distinct", candidate -> {
            ArrayNode features = (ArrayNode) candidate.path("artifacts").path("feature").path("partitions");
            ((ObjectNode) features.get(1)).put("normalization_receipt_path", features.get(0).path("normalization_receipt_path").asText());
        });

        for (String readiness : List.of("minimum_contiguous_hydration_proven", "coverage_inventory_complete",
                "all_source_transformations_recomputed", "development_replay_permitted")) {
            boolean baseline = manifest.path(readiness).asBoolean();
            rejectReopenedManifest(options, manifest, "v002 replay readiness is not derived from reopened coverage and source transforms",
                    candidate -> candidate.put(readiness, !baseline));
        }

        ObjectNode macroEntry = (ObjectNode) manifest.path("artifacts").path("feature").path("partitions").get(2);
        Path source = root.resolve(macroEntry.path("source_path").asText());
        byte[] originalSource = Files.readAllBytes(source);
        try {
            Files.writeString(source, "tampered raw source bytes\n");
            ObjectNode optionsWithManifest = options.deepCopy();
            optionsWithManifest.set("manifest", manifest.deepCopy());
            IllegalArgumentException sourceError = assertThrows(IllegalArgumentException.class,
                    () -> LiquidationV2PhysicalDataV1.verifyDevelopment(optionsWithManifest));
            assertTrue(sourceError.getMessage().contains("feature source, normalized, or normalization receipt bytes changed"),
                    sourceError.getMessage());
        } finally {
            Files.write(source, originalSource);
        }
    }

    @Test
    void publicResumableRunnerRejectsProxyModeAndScopeBeforeWritingEconomicOutput() throws Exception {
        List<String> expected = List.of("proxy replay cannot use synthetic_smoke mode",
                "proxy replay warmup must match the frozen physical source",
                "proxy physical coverage is not complete enough for a development replay");
        Set<String> manifestHashes = new HashSet<>();
        for (int index = 0; index < expected.size(); index++) {
            Path root = Files.createDirectories(temporary.resolve("proxy-run-preflight-" + index)).toRealPath();
            Path checkedInRoot = checkedInProjectRoot();
            Path projectSnapshot = copyCheckedInFreezeInputs(checkedInRoot, root.resolve("project-snapshot"));
            ObjectNode freeze = freezeProxyFixture(root, projectSnapshot, index);
            assertTrue(manifestHashes.add(freeze.path("manifest_sha256").asText()),
                    "independent fixture data roots bind distinct physical manifests");
            JsonNode plan = freeze.path("plan");
            ObjectNode runOptions = JsonHashes.mapper().createObjectNode()
                    .put("out", root.resolve("must-not-be-written.json").toString())
                    .put("replay_start", plan.path("decision_start").asText())
                    .put("replay_end_exclusive", plan.path("execution_end_exclusive").asText());
            runOptions.set("freeze", freeze.deepCopy());
            if (index == 0) {
                runOptions.put("synthetic_smoke", true);
            } else if (index == 1) {
                runOptions.put("feature_warmup_start", Instant.parse(plan.path("source_start").asText())
                        .plusSeconds(86_400).toString());
            } else {
                runOptions.put("feature_warmup_start", plan.path("source_start").asText());
            }
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> LiquidationPortfolioReplayV1.runResumable(runOptions));
            assertTrue(error.getMessage().contains(expected.get(index)),
                    "expected proxy preflight [" + expected.get(index) + "], got [" + error.getMessage() + "]");
            assertFalse(Files.exists(root.resolve("must-not-be-written.json")),
                    "preflight rejection cannot publish replay economics");
            Path eventDirectory = projectSnapshot.resolve(".research-run/.liquidation-v2-replay-budget-v1")
                    .resolve(JsonHashes.sha256(freeze.path("precommit").path("precommit_id").asText()))
                    .resolve("events");
            assertTrue(Files.isDirectory(eventDirectory), "budget custody belongs to the isolated project snapshot");
            long reservations = 0, aborts = 0, priorConsumed = 0;
            JsonNode abort = null;
            List<JsonNode> durableEvents = new ArrayList<>();
            try (var events = Files.list(eventDirectory)) {
                for (Path eventPath : events.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                    JsonNode event = JsonHashes.mapper().readTree(eventPath.toFile());
                    if (event.path("event_type").isTextual()) durableEvents.add(event);
                }
            }
            durableEvents.sort(Comparator.comparingInt(event -> event.path("sequence").asInt(-1)));
            for (JsonNode event : durableEvents) {
                long consumed = event.path("consumed_compute_millis").asLong(-1);
                assertTrue(consumed >= priorConsumed, "durable family compute usage is monotone across setup rejection");
                priorConsumed = consumed;
                if ("RESERVATION".equals(event.path("event_type").asText())) {
                    reservations++;
                    assertEquals("INITIAL_PHYSICAL_OPEN_AND_ENGINE_SETUP", event.path("segment_id").asText());
                    assertEquals(7_200_000L, event.path("reserved_millis").asLong());
                } else if ("ABORT".equals(event.path("event_type").asText())) {
                    aborts++;
                    abort = event.deepCopy();
                } else {
                    assertFalse("CHECKPOINT".equals(event.path("event_type").asText()),
                            "failed preflight cannot publish a checkpoint or economic output");
                }
            }
            assertEquals(1, reservations, "setup work is protected by exactly one durable reservation");
            assertEquals(1, aborts, "a failed setup consumes the unfinished reservation conservatively");
            assertTrue(abort != null);
            assertEquals(7_200_000L, abort.path("charged_millis").asLong());
            assertEquals(7_200_000L, abort.path("consumed_compute_millis").asLong());
            assertEquals(7_200_000L, priorConsumed);
        }
    }

    private static ObjectNode firstPartition(ObjectNode manifest, String role) {
        return (ObjectNode) manifest.path("artifacts").path(role).path("partitions").get(0);
    }

    private ObjectNode freezeProxyFixture(Path root, Path projectRoot, int fixtureId) throws Exception {
        ObjectNode inputs = JsonHashes.mapper().createObjectNode();
        String suffix = "-" + fixtureId;
        addPartition(root, inputs, "feature", "daily" + suffix, "COINALYZE_PROXY", List.of(daily()), SOURCE, SOURCE + day(), null);
        addPartition(root, inputs, "feature", "price" + suffix, "PUBLIC_ARCHIVE", List.of(price()), SOURCE + day(), SOURCE + 2 * day(), null);
        addPartition(root, inputs, "feature", "macro" + suffix, "PUBLIC_ARCHIVE", List.of(macro()), CLOSE, CLOSE + day(), null);
        addPartition(root, inputs, "label", "labels" + suffix, "DERIVED_OUTCOME_LABEL", List.of(
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("episode_id", "proxy-preflight")
                        .put("decision_time", DECISION).put("label", "UNINSPECTED")), DECISION, DECISION + 60_000L, null);
        addPartition(root, inputs, "execution", "execution" + suffix, "PUBLIC_ARCHIVE", List.of(
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("open_time", DECISION)
                        .put("open", 100).put("high", 101).put("low", 99).put("close", 100).put("base_volume", 10)),
                DECISION, DECISION + 60_000L, null);
        addPartition(root, inputs, "mark", "mark" + suffix, "PUBLIC_ARCHIVE", List.of(
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("timestamp", DECISION)
                        .put("open", 100).put("high", 101).put("low", 99).put("close", 100)),
                DECISION, DECISION + 60_000L, null);
        addPartition(root, inputs, "funding", "funding" + suffix, "PUBLIC_ARCHIVE", List.of(
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("settlement_time", DECISION + 7)
                        .put("event_id", "proxy-preflight-funding").put("funding_rate", 0).put("mark_price", 100)
                        .put("funding_interval_hours", 8)), DECISION, DECISION + 8L * 3_600_000L, null);
        ObjectNode metadata = JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("tier_index", 1)
                .put("effective_from", SOURCE).put("effective_until", END).put("lot_size", 0.001)
                .put("minimum_notional", 5).put("taker_fee_rate", 0.0005).put("slippage_rate", 0.0001)
                .put("liquidation_fee_rate", 0.01).put("tier_notional_cap", 1_000_000_000)
                .put("maintenance_margin_rate", 0.005).put("maintenance_deduction", 0).put("terminal_tier", true);
        ObjectNode assumption = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-metadata-assumption/1")
                .put("version", 1).put("source_class", "HISTORICAL_APPROXIMATION")
                .put("assumption_description", "Present contract fields are disclosed as a historical approximation.")
                .put("historical_availability_proven", false).put("historical_revision_proven", false)
                .put("authoritative", false);
        addPartition(root, inputs, "metadata", "metadata" + suffix, "PUBLIC_ARCHIVE", List.of(metadata), SOURCE, END, assumption);

        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        ObjectNode physicalOptions = JsonHashes.mapper().createObjectNode().put("root", root.toString());
        physicalOptions.set("profile", profile.deepCopy());
        physicalOptions.set("inputs", inputs);
        physicalOptions.set("manifest", LiquidationV2PhysicalDataV1.buildDevelopment(physicalOptions));
        ObjectNode freezeOptions = JsonHashes.mapper().createObjectNode().put("project_root", projectRoot.toString());
        freezeOptions.set("profile", profile.deepCopy());
        freezeOptions.set("physical_options", physicalOptions);
        return LiquidationPortfolioReplayV1.freeze(freezeOptions);
    }

    private static Path checkedInProjectRoot() throws Exception {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (cursor != null && !Files.isRegularFile(cursor.resolve(
                "docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) cursor = cursor.getParent();
        if (cursor == null) throw new AssertionError("cannot locate checked-in frozen research project root");
        return cursor.toRealPath();
    }

    private static Path copyCheckedInFreezeInputs(Path checkedInRoot, Path snapshot) throws Exception {
        List<String> requiredFiles = List.of(
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationPortfolioReplayV1.java",
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationPortfolioAccountingV1.java",
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationStagedPerpetualLifecycleV1.java",
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationStructureRouterV1.java",
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationV2PhysicalDataV1.java",
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationV2ReplayEvidenceV1.java",
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationV2EvidenceMathV1.java",
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationV2StagedStatisticsV1.java",
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationV2StagedCandidateInventoryV1.java",
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationV2ReplayCheckpointV1.java",
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationV2FamilyExposureAttemptV1.java",
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationV2StressPolicyV1.java",
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationInputQualificationV1.java",
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationDailyStressProfileV1.java",
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationChronologicalPolicyV1.java",
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/StrategyResearchV5CommandAdapter.java",
                "analytics-infrastructure/src/main/java/com/tradinganalytics/infrastructure/security/JsonHashes.java",
                "analytics-infrastructure/src/main/java/com/tradinganalytics/infrastructure/security/PathConfinement.java",
                "analytics-infrastructure/src/main/java/com/tradinganalytics/infrastructure/marketdata/CoinalyzeDailyData.java",
                "analytics-market-data/src/main/java/com/tradinganalytics/marketdata/research/ResearchData.java",
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/StrategyStatisticalV5.java",
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/StrategyResearchAuthoritativeV5.java",
                "pom.xml", "analytics-research/pom.xml", "analytics-infrastructure/pom.xml",
                "analytics-market-data/pom.xml", "mvnw", ".mvn/wrapper/maven-wrapper.properties",
                "docs/research/liquidation-daily-stress-v002/frozen-precommit.json",
                "docs/research/liquidation-structure-v001/frozen-precommit.json",
                "docs/research/liquidation-structure-v001/FREEZE-MANIFEST.json",
                "docs/research/liquidation-structure-v001/FEASIBILITY.md");
        for (String relative : requiredFiles) {
            Path source = checkedInRoot.resolve(relative);
            if (!Files.isRegularFile(source)) throw new AssertionError("missing checked-in freeze input: " + relative);
            Path target = snapshot.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
        }
        return Files.createDirectories(snapshot).toRealPath();
    }

    private static void rejectReopenedManifest(ObjectNode options, ObjectNode baseline, String message,
            Consumer<ObjectNode> mutation) {
        ObjectNode candidate = baseline.deepCopy();
        mutation.accept(candidate);
        candidate.put("content_sha256", JsonHashes.ownHash(candidate));
        ObjectNode changedOptions = options.deepCopy();
        changedOptions.set("manifest", candidate);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.verifyDevelopment(changedOptions));
        assertTrue(error.getMessage().contains(message), "expected [" + message + "], got [" + error.getMessage() + "]");
    }

    private static ObjectNode daily() {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT_PERP.A")
                .put("series_id", "daily_liquidation_usd").put("timeframe", "1d").put("side", "LONG")
                .put("event_time", SOURCE).put("availability_time", SOURCE + 172_800_000L).put("value", 100);
    }

    private static ObjectNode price() {
        long time = SOURCE + day();
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT")
                .put("series_id", "price_ohlc").put("timeframe", "4h").put("event_time", time)
                .put("availability_time", time + 14_400_000L).put("open", 100).put("high", 101)
                .put("low", 99).put("close", 100).put("base_volume", 10);
    }

    private static ObjectNode macro() {
        return JsonHashes.mapper().createObjectNode().put("asset", "SP500").put("symbol", "SP500")
                .put("series_id", "sp500_close").put("timeframe", "1d").put("event_time", CLOSE)
                .put("availability_time", AVAILABLE).put("close", 4_700);
    }

    private static void addPartition(Path root, ObjectNode inputs, String role, String name, String sourceClass,
            List<ObjectNode> rows, long start, long end, ObjectNode assumptionTemplate) throws Exception {
        String normalizedPath = "normalized/" + name + ".jsonl";
        String sourcePath = "raw/" + name + ".jsonl";
        String receiptPath = "receipts/" + name + ".json";
        byte[] normalized = LiquidationV2PhysicalDataV1.canonicalRoleRowsJsonl(role, rows);
        byte[] source = normalized.clone();
        write(root, normalizedPath, normalized);
        write(root, sourcePath, source);
        ObjectNode receipt = LiquidationV2PhysicalDataV1.createRoleJsonlCopyReceipt(role, normalizedPath,
                normalized, sourcePath, source, Instant.ofEpochMilli(start).toString(),
                Instant.ofEpochMilli(end).toString(), sourceClass);
        byte[] receiptBytes = JsonHashes.canonicalBytes(receipt);
        write(root, receiptPath, receiptBytes);
        ObjectNode partition = JsonHashes.mapper().createObjectNode().put("path", normalizedPath)
                .put("sha256", JsonHashes.sha256(normalized)).put("source_path", sourcePath)
                .put("source_sha256", JsonHashes.sha256(source)).put("normalization_receipt_path", receiptPath)
                .put("normalization_receipt_sha256", JsonHashes.sha256(receiptBytes))
                .put("window_start", Instant.ofEpochMilli(start).toString())
                .put("window_end_exclusive", Instant.ofEpochMilli(end).toString());
        if (assumptionTemplate != null) {
            ObjectNode assumption = assumptionTemplate.deepCopy().put("source_path", sourcePath)
                    .put("source_byte_sha256", JsonHashes.sha256(source))
                    .put("window_start", Instant.ofEpochMilli(start).toString())
                    .put("window_end_exclusive", Instant.ofEpochMilli(end).toString());
            assumption.put("content_sha256", JsonHashes.ownHash(assumption));
            String assumptionPath = "receipts/" + name + "-assumption.json";
            byte[] bytes = JsonHashes.canonicalBytes(assumption);
            write(root, assumptionPath, bytes);
            partition.put("assumption_path", assumptionPath).put("assumption_sha256", JsonHashes.sha256(bytes));
        }
        if (!inputs.has(role)) inputs.putObject(role).putArray("partitions");
        ((ArrayNode) inputs.path(role).path("partitions")).add(partition);
    }

    private static void write(Path root, String relative, byte[] bytes) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    private static long day() { return 86_400_000L; }
}

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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public resumable entry-point checks for the durable family-budget custody root. */
class LiquidationPortfolioReplayResearchRootCustodyMatrixV1Test {
    private static final long INITIAL_SETUP_RESERVATION_MILLIS = 7_200_000L;
    private static final long SOURCE = Instant.parse("2022-08-11T00:00:00Z").toEpochMilli();
    private static final long DECISION = Instant.parse("2022-11-11T00:00:00Z").toEpochMilli();
    private static final long CLOSE = Instant.parse("2024-01-02T21:00:00Z").toEpochMilli();
    private static final long AVAILABLE = Instant.parse("2024-01-03T21:00:00Z").toEpochMilli();
    private static final long END = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli();
    @TempDir Path temporary;

    @Test
    void durableResearchRootRejectsSymlinkAndOrdinaryFileBeforeOpeningAnyBudgetChain() throws Exception {
        Path symlinkCase = caseRoot("symlink-root");
        Path physical = Files.createDirectories(symlinkCase.resolve("physical"));
        Path checkedInRoot = checkedInProjectRoot();
        Path projectSnapshot = copyCheckedInFreezeInputs(checkedInRoot, symlinkCase.resolve("project-snapshot"));
        ObjectNode freeze = freezeProxyFixture(physical, projectSnapshot, "symlink-root");
        Path target = Files.createDirectories(symlinkCase.resolve("redirect-target"));
        Files.createSymbolicLink(projectSnapshot.resolve(".research-run"), target);
        IllegalArgumentException symlink = assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioReplayV1.runResumable(options(physical, freeze)));
        assertEquals("durable research budget root must not be a symlink", symlink.getMessage());
        assertFalse(Files.exists(target.resolve(".liquidation-v2-replay-budget-v1")),
                "rejected symlink custody must not initialize the redirected target");
        assertFalse(Files.exists(physical.resolve("replay.json")));

        Path fileCase = caseRoot("ordinary-file-root");
        Path fileProject = copyCheckedInFreezeInputs(checkedInRoot, fileCase.resolve("project-snapshot"));
        Path filePhysical = Files.createDirectories(fileCase.resolve("physical"));
        ObjectNode fileFreeze = freezeProxyFixture(filePhysical, fileProject, "ordinary-file-root");
        Files.writeString(fileProject.resolve(".research-run"), "not a custody directory");
        IllegalArgumentException regularFile = assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioReplayV1.runResumable(options(filePhysical, fileFreeze)));
        assertEquals("durable research budget root must be a non-symlink directory", regularFile.getMessage());
        assertFalse(Files.exists(filePhysical.resolve("replay.json")));
    }

    @Test
    void existingRealResearchDirectoryIsAcceptedAndLaterSetupFailureIsDurablyCharged() throws Exception {
        Path root = caseRoot("accepted-real-root");
        Path project = copyCheckedInFreezeInputs(checkedInProjectRoot(), root.resolve("project-snapshot"));
        Path physical = Files.createDirectories(root.resolve("physical"));
        ObjectNode freeze = freezeProxyFixture(physical, project, "accepted-real-root");
        Path custody = Files.createDirectory(project.resolve(".research-run"));
        assertFalse(Files.isSymbolicLink(custody));

        IllegalArgumentException laterSetupFailure = assertThrows(IllegalArgumentException.class,
                () -> LiquidationPortfolioReplayV1.runResumable(options(physical, freeze)));
        assertEquals("proxy physical coverage is not complete enough for a development replay", laterSetupFailure.getMessage());
        assertFalse(Files.exists(physical.resolve("replay.json")), "pre-outcome setup failure cannot publish economics");

        String researchRunId = freeze.path("precommit").path("precommit_id").asText();
        Path ledger = custody.resolve(".liquidation-v2-replay-budget-v1").resolve(sha(researchRunId));
        Path eventDirectory = ledger.resolve("events");
        assertTrue(Files.isDirectory(eventDirectory), "the real custody directory was accepted and opened");
        List<JsonNode> events = new ArrayList<>();
        try (var stream = Files.list(eventDirectory)) {
            for (Path event : stream.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                JsonNode row = JsonHashes.mapper().readTree(event.toFile());
                if (row.path("event_type").isTextual()) events.add(row);
            }
        }
        events.sort(Comparator.comparingInt(row -> row.path("sequence").asInt(-1)));
        assertEquals(List.of("GENESIS", "RUN_START", "RESERVATION", "ABORT"),
                events.stream().map(row -> row.path("event_type").asText()).toList());
        assertEquals("INITIAL_PHYSICAL_OPEN_AND_ENGINE_SETUP", events.get(2).path("segment_id").asText());
        assertEquals(INITIAL_SETUP_RESERVATION_MILLIS, events.get(2).path("reserved_millis").asLong());
        assertEquals(INITIAL_SETUP_RESERVATION_MILLIS, events.get(3).path("charged_millis").asLong());
        assertEquals(INITIAL_SETUP_RESERVATION_MILLIS, events.get(3).path("consumed_compute_millis").asLong());
    }

    private Path caseRoot(String name) throws Exception {
        return Files.createDirectories(temporary.toRealPath().resolve(name));
    }

    private static ObjectNode options(Path physical, ObjectNode freeze) {
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("out", physical.resolve("replay.json").toString());
        options.set("freeze", freeze);
        ObjectNode plan = (ObjectNode) freeze.path("plan");
        options.set("replay_request", JsonHashes.mapper().createObjectNode()
                .put("replay_start", plan.path("decision_start").asText())
                .put("replay_end_exclusive", plan.path("execution_end_exclusive").asText())
                .put("feature_warmup_start", plan.path("source_start").asText()));
        return options;
    }

    private static ObjectNode freezeProxyFixture(Path physicalRoot, Path projectRoot, String id) throws Exception {
        ObjectNode inputs = JsonHashes.mapper().createObjectNode();
        addPartition(physicalRoot, inputs, "feature", "daily-" + id, "COINALYZE_PROXY", List.of(daily()), SOURCE, SOURCE + day(), null);
        addPartition(physicalRoot, inputs, "feature", "price-" + id, "PUBLIC_ARCHIVE", List.of(price()), SOURCE + day(), SOURCE + 2 * day(), null);
        addPartition(physicalRoot, inputs, "feature", "macro-" + id, "PUBLIC_ARCHIVE", List.of(macro()), CLOSE, CLOSE + day(), null);
        addPartition(physicalRoot, inputs, "label", "labels-" + id, "DERIVED_OUTCOME_LABEL", List.of(
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("episode_id", id)
                        .put("decision_time", DECISION).put("label", "UNINSPECTED")), DECISION, DECISION + 60_000L, null);
        addPartition(physicalRoot, inputs, "execution", "execution-" + id, "PUBLIC_ARCHIVE", List.of(
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("open_time", DECISION)
                        .put("open", 100).put("high", 101).put("low", 99).put("close", 100).put("base_volume", 10)),
                DECISION, DECISION + 60_000L, null);
        addPartition(physicalRoot, inputs, "mark", "mark-" + id, "PUBLIC_ARCHIVE", List.of(
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("timestamp", DECISION)
                        .put("open", 100).put("high", 101).put("low", 99).put("close", 100)),
                DECISION, DECISION + 60_000L, null);
        addPartition(physicalRoot, inputs, "funding", "funding-" + id, "PUBLIC_ARCHIVE", List.of(
                JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("settlement_time", DECISION + 7)
                        .put("event_id", id + "-funding").put("funding_rate", 0).put("mark_price", 100)
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
        addPartition(physicalRoot, inputs, "metadata", "metadata-" + id, "PUBLIC_ARCHIVE", List.of(metadata), SOURCE, END, assumption);

        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        ObjectNode physicalOptions = JsonHashes.mapper().createObjectNode().put("root", physicalRoot.toString());
        physicalOptions.set("profile", profile.deepCopy());
        physicalOptions.set("inputs", inputs);
        physicalOptions.set("manifest", LiquidationV2PhysicalDataV1.buildDevelopment(physicalOptions));
        ObjectNode freezeOptions = JsonHashes.mapper().createObjectNode().put("project_root", projectRoot.toString());
        freezeOptions.set("profile", profile);
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
        List<String> files = List.of(
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
        for (String relative : files) {
            Path source = checkedInRoot.resolve(relative);
            if (!Files.isRegularFile(source)) throw new AssertionError("missing checked-in freeze input: " + relative);
            Path destination = snapshot.resolve(relative);
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination);
        }
        return Files.createDirectories(snapshot).toRealPath();
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
        ObjectNode receipt = LiquidationV2PhysicalDataV1.createRoleJsonlCopyReceipt(role, normalizedPath, normalized,
                sourcePath, source, Instant.ofEpochMilli(start).toString(), Instant.ofEpochMilli(end).toString(), sourceClass);
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

    private static String sha(String value) { return JsonHashes.sha256(value); }
}

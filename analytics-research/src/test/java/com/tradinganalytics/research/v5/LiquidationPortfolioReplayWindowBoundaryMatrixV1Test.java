package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public replay-envelope boundary matrix using isolated synthetic-only budget roots. */
class LiquidationPortfolioReplayWindowBoundaryMatrixV1Test {
    @TempDir Path temporary;
    private final AtomicInteger runSequence = new AtomicInteger();

    @Test
    void publicRunnerRejectsEveryIndependentSyntheticWindowBoundaryBeforePublishingResults() throws Exception {
        Path physicalRoot = Files.createDirectories(temporary.resolve("physical"));
        ObjectNode freeze = buildSmallFreeze(physicalRoot);
        ObjectNode valid = JsonHashes.mapper().createObjectNode().put("synthetic_smoke", true)
                .put("feature_warmup_start", "2023-10-01T00:00:00Z")
                .put("replay_start", "2024-01-03T00:00:00Z")
                .put("decision_end_exclusive", "2024-01-04T00:00:00Z")
                .put("execution_end_exclusive", "2024-03-05T00:00:00Z");
        Path immutableSourceRoot = Path.of(freeze.path("physical_root").asText());
        String inputDigestBefore = immutableTreeDigest(immutableSourceRoot);

        expectPublicWindowFailure(freeze, with(valid, "synthetic_smoke", false),
                "synthetic replay requires explicit synthetic_smoke=true");
        expectPublicWindowFailure(freeze, with(valid, "feature_warmup_start", "2023-10-01T00:01:00Z"));
        expectPublicWindowFailure(freeze, with(valid, "replay_start", "2024-01-03T00:01:00Z"));
        expectPublicWindowFailure(freeze, with(valid, "decision_end_exclusive", "2024-01-04T00:01:00Z"));
        expectPublicWindowFailure(freeze, with(valid, "execution_end_exclusive", "2024-03-05T00:01:00Z"));

        // These requests isolate the ordered duration and frozen-scope checks while keeping
        // earlier alignment/source-order predicates valid.
        expectPublicWindowFailure(freeze, with(valid, "feature_warmup_start", "2023-12-01T00:00:00Z"));
        expectPublicWindowFailure(freeze, with(valid, "feature_warmup_start", "2022-08-10T00:00:00Z"));
        expectPublicWindowFailure(freeze, with(valid, "feature_warmup_start", "2024-01-03T00:00:00Z"));
        expectPublicWindowFailure(freeze, with(valid, "decision_end_exclusive", "2024-01-03T00:00:00Z"));
        expectPublicWindowFailure(freeze, with(valid, "decision_end_exclusive", "2024-01-12T00:00:00Z"));

        ObjectNode decisionPastFrozenEnd = valid.deepCopy();
        decisionPastFrozenEnd.put("feature_warmup_start", "2026-04-01T00:00:00Z")
                .put("replay_start", "2026-07-14T00:00:00Z")
                .put("decision_end_exclusive", "2026-07-16T00:00:00Z")
                .put("execution_end_exclusive", "2026-07-16T00:00:00Z");
        expectPublicWindowFailure(freeze, decisionPastFrozenEnd);

        expectPublicWindowFailure(freeze, with(valid, "execution_end_exclusive", "2024-01-03T00:00:00Z"));

        ObjectNode executionPastFrozenEnd = valid.deepCopy();
        executionPastFrozenEnd.put("feature_warmup_start", "2026-04-01T00:00:00Z")
                .put("replay_start", "2026-07-08T00:00:00Z")
                .put("decision_end_exclusive", "2026-07-15T00:00:00Z")
                .put("execution_end_exclusive", "2026-09-21T00:00:00Z");
        expectPublicWindowFailure(freeze, executionPastFrozenEnd);

        assertEquals(inputDigestBefore, immutableTreeDigest(immutableSourceRoot),
                "failed runs may not mutate the original immutable fixture partitions");
    }

    @Test
    void publicRunnerAcceptsEmptyButWellBoundedSyntheticDecisionWindow() throws Exception {
        Path sourceRoot = Files.createDirectories(temporary.resolve("positive-source"));
        ObjectNode sourceFreeze = buildSmallFreeze(sourceRoot);
        Path isolatedRoot = temporary.resolve("positive-clone");
        cloneTree(sourceRoot, isolatedRoot);
        ObjectNode freeze = sourceFreeze.deepCopy();
        freeze.put("physical_root", isolatedRoot.toString());
        freeze.put("content_sha256", JsonHashes.ownHash(freeze));

        ObjectNode request = JsonHashes.mapper().createObjectNode().put("synthetic_smoke", true)
                .put("feature_warmup_start", "2023-10-01T00:00:00Z")
                .put("replay_start", "2024-01-03T00:00:00Z")
                .put("decision_end_exclusive", "2024-01-04T00:00:00Z")
                .put("execution_end_exclusive", "2024-01-04T00:00:00Z");
        Path output = isolatedRoot.resolve("positive-replay.json");
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("out", output.toString());
        options.set("freeze", freeze);
        options.set("replay_request", request);

        ObjectNode replay = LiquidationPortfolioReplayV1.run(options);

        assertEquals("liquidation-v2-replay-result/1", replay.path("schema").asText());
        assertEquals("SYNTHETIC_DEVELOPMENT_ONLY", replay.path("status").asText());
        assertEquals(JsonHashes.ownHash(replay), replay.path("content_sha256").asText());
        assertTrue(replay.path("opportunities").isArray());
        assertEquals(0, replay.path("opportunities").size());
        assertEquals(4, replay.path("evaluated_candidates").size());
        for (JsonNode candidate : replay.path("evaluated_candidates")) {
            assertTrue(candidate.path("executed").asBoolean());
            assertEquals(0, candidate.path("opportunity_count").asInt());
        }
        assertEquals(JsonHashes.canonicalSha256(replay),
                JsonHashes.canonicalSha256(JsonHashes.mapper().readTree(output.toFile())));
    }

    @Test
    void publicRunnerAcceptsLegacyTopLevelWindowAndNormalizesDecisionAndExecutionEnd() throws Exception {
        Path sourceRoot = Files.createDirectories(temporary.resolve("legacy-request-source"));
        ObjectNode sourceFreeze = buildSmallFreeze(sourceRoot);
        Path isolatedRoot = temporary.resolve("legacy-request-clone");
        cloneTree(sourceRoot, isolatedRoot);
        ObjectNode freeze = sourceFreeze.deepCopy();
        freeze.put("physical_root", isolatedRoot.toString());
        freeze.put("content_sha256", JsonHashes.ownHash(freeze));

        Path output = isolatedRoot.resolve("legacy-window-replay.json");
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("out", output.toString())
                .put("synthetic_smoke", true)
                .put("feature_warmup_start", "2023-10-01T00:00:00Z")
                .put("replay_start", "2024-01-03T00:00:00Z")
                .put("replay_end_exclusive", "2024-01-04T00:00:00Z");
        options.set("freeze", freeze);

        ObjectNode replay = LiquidationPortfolioReplayV1.run(options);

        assertEquals("2024-01-04T00:00:00Z", replay.path("execution_request").path("decision_end_exclusive").asText());
        assertEquals("2024-01-04T00:00:00Z", replay.path("execution_request").path("execution_end_exclusive").asText());
        assertEquals("2024-01-04T00:00:00Z", replay.path("replay_end_exclusive").asText());
        assertEquals(JsonHashes.ownHash(replay), replay.path("content_sha256").asText());
        assertEquals(JsonHashes.canonicalSha256(replay),
                JsonHashes.canonicalSha256(JsonHashes.mapper().readTree(output.toFile())));
    }

    static ObjectNode buildSmallFreeze(Path root) throws Exception {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        Map<String, ObjectNode> rows = new LinkedHashMap<>();
        rows.put("feature", JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT")
                .put("series_id", "price_ohlc").put("timeframe", "4h").put("event_time", 1_680_307_200_000L)
                .put("availability_time", 1_680_321_600_000L).put("open", 99).put("high", 101).put("low", 98).put("close", 100));
        rows.put("label", JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("episode_id", "synthetic-window-fixture")
                .put("decision_time", 1_680_000_000_000L).put("label", "UNINSPECTED"));
        rows.put("execution", JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("open_time", 1_680_000_000_000L)
                .put("open", 100).put("high", 101).put("low", 99).put("close", 100).put("base_volume", 50));
        rows.put("mark", JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("timestamp", 1_680_000_000_000L)
                .put("open", 100).put("high", 101).put("low", 99).put("close", 100));
        rows.put("funding", JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("settlement_time", 1_680_000_000_000L)
                .put("event_id", "synthetic-funding-window").put("funding_rate", 0).put("mark_price", 100));
        rows.put("metadata", JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("tier_index", "1")
                .put("effective_from", 1_679_900_000_000L).put("effective_until", 1_680_100_000_000L)
                .put("lot_size", 0.001).put("minimum_notional", 5).put("taker_fee_rate", 0.0005)
                .put("slippage_rate", 0).put("liquidation_fee_rate", 0).put("tier_notional_cap", 100_000)
                .put("maintenance_margin_rate", 0.005).put("maintenance_deduction", 0).put("terminal_tier", true));
        ObjectNode inputs = JsonHashes.mapper().createObjectNode();
        for (Map.Entry<String, ObjectNode> entry : rows.entrySet()) {
            String relative = "inputs/" + entry.getKey() + ".jsonl";
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            byte[] bytes = (JsonHashes.canonicalString(entry.getValue()) + "\n").getBytes(StandardCharsets.UTF_8);
            Files.write(file, bytes);
            inputs.putObject(entry.getKey()).put("path", relative).put("sha256", JsonHashes.sha256(bytes));
        }
        ObjectNode build = JsonHashes.mapper().createObjectNode().put("root", root.toString());
        build.set("profile", profile.deepCopy());
        build.set("inputs", inputs);
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildSyntheticDevelopment(build);
        ObjectNode physical = JsonHashes.mapper().createObjectNode().put("root", root.toString());
        physical.set("profile", profile.deepCopy());
        physical.set("manifest", manifest);
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        while (projectRoot != null && !Files.isRegularFile(projectRoot.resolve(
                "docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) projectRoot = projectRoot.getParent();
        if (projectRoot == null) throw new AssertionError("cannot locate frozen research project root");
        ObjectNode freezeOptions = JsonHashes.mapper().createObjectNode().put("project_root", projectRoot.toString());
        freezeOptions.set("profile", profile.deepCopy());
        freezeOptions.set("physical_options", physical);
        return LiquidationPortfolioReplayV1.freeze(freezeOptions);
    }

    private void expectPublicWindowFailure(ObjectNode sourceFreeze, ObjectNode request) throws Exception {
        expectPublicWindowFailure(sourceFreeze, request,
                "synthetic smoke needs UTC-day-aligned feature warmup of at least 92 days, a decision window of at most seven days, and valid decision/execution windows within the frozen decision/execution scope");
    }

    private void expectPublicWindowFailure(ObjectNode sourceFreeze, ObjectNode request, String message) throws Exception {
        Path source = Path.of(sourceFreeze.path("physical_root").asText());
        // Separate roots are confined to these synthetic temp fixtures. This isolates the
        // conservative failure debit from adjacent test cases and makes no statement about
        // the shared durable research-family budget used for real source modes.
        Path physicalRoot = temporary.resolve("physical-clone-" + runSequence.incrementAndGet());
        try {
            cloneTree(source, physicalRoot);
            ObjectNode freeze = sourceFreeze.deepCopy();
            freeze.put("physical_root", physicalRoot.toString());
            freeze.put("content_sha256", JsonHashes.ownHash(freeze));
            Path out = physicalRoot.resolve("should-not-exist-replay.json");
            ObjectNode options = JsonHashes.mapper().createObjectNode().put("out", out.toString());
            options.set("freeze", freeze);
            options.set("replay_request", request);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> LiquidationPortfolioReplayV1.runResumable(options));
            assertEquals(message, failure.getMessage());
            assertFalse(Files.exists(out), "invalid replay windows must fail before output publication");
        } finally {
            deleteTree(physicalRoot);
        }
    }

    static void cloneTree(Path source, Path target) throws Exception {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path clone = target.resolve(source.relativize(path));
                if (Files.isSymbolicLink(path)) throw new AssertionError("fixture physical root contains a symlink");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(clone);
                else Files.copy(path, clone);
            }
        }
    }

    private static String immutableTreeDigest(Path root) throws Exception {
        ArrayNode entries = JsonHashes.mapper().createArrayNode();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                entries.addObject().put("path", root.relativize(path).toString())
                        .put("sha256", JsonHashes.sha256(Files.readAllBytes(path)));
            }
        }
        return JsonHashes.canonicalSha256(entries);
    }

    static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static ObjectNode with(ObjectNode source, String field, String value) {
        ObjectNode copy = source.deepCopy();
        copy.put(field, value);
        return copy;
    }

    private static ObjectNode with(ObjectNode source, String field, boolean value) {
        ObjectNode copy = source.deepCopy();
        copy.put(field, value);
        return copy;
    }
}

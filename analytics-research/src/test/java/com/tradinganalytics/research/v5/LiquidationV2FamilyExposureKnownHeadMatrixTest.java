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
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises canonical V5 HEAD and v002 journal reconciliation without replacing unknown history. */
class LiquidationV2FamilyExposureKnownHeadMatrixTest {
    @TempDir Path temporary;

    @Test
    void knownCanonicalHeadRetainsPriorKAndRetriesAreIdempotent() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("known-head"));
        ObjectNode prior = canonicalHead("prior-data", List.of(new HeadEntry(JsonHashes.sha256("prior-behavior"), "prior-data")), null);
        initialize(root, prior);

        ObjectNode first = LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(freeze(), root);
        ObjectNode headAfterFirst = StrategyStatisticalV5.readExposureHeadFile(root.resolve("exposure-head.json"));
        ObjectNode state = LiquidationV2FamilyExposureAttemptV1.reopenFamilyExposureAttempts(root);
        assertEquals("KNOWN_FROM_CANONICAL_V5_HEAD", state.path("historical_cumulative_k_status").asText());
        assertEquals(10, state.path("historical_cumulative_k").asInt(), "one prior behavior plus nine frozen attempts");
        assertEquals(9, first.path("attempts").size());
        assertTrue(first.path("attempts").get(0).path("status").asText().contains("CANONICAL_HEAD"));
        ObjectNode journalAfterFirst = readObject(root.resolve("liquidation-v2-known-exposure-attempts.json"));
        assertEquals(9, journalAfterFirst.path("entries").size());

        ObjectNode second = LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(freeze(), root);
        ObjectNode headAfterSecond = StrategyStatisticalV5.readExposureHeadFile(root.resolve("exposure-head.json"));
        ObjectNode journalAfterSecond = readObject(root.resolve("liquidation-v2-known-exposure-attempts.json"));
        assertEquals(headAfterFirst.path("content_sha256").asText(), headAfterSecond.path("content_sha256").asText());
        assertEquals(journalAfterFirst.path("content_sha256").asText(), journalAfterSecond.path("content_sha256").asText(),
                "the exact-identity journal is unchanged on a repeated freeze");
        assertEquals(10, second.path("historical_cumulative_k").asInt());
        for (JsonNode attempt : second.path("attempts")) assertEquals("IDEMPOTENT_CANONICAL_HEAD_IDENTITY_JOURNAL_IDEMPOTENT", attempt.path("status").asText());
        assertTrue(Files.exists(root.resolve("liquidation-v2-known-exposure-attempts.json")),
                "the exact-identity journal accompanies the reduced legacy HEAD for auditable idempotency");
    }

    @Test
    void journalPrefixReconciliationChecksBothHeadEntriesAndFixedAttemptPairs() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("journal-to-head"));
        ObjectNode frozen = freeze();
        ObjectNode journalOnly = LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(frozen, root);
        assertEquals("UNKNOWN_NO_VERIFIED_CANONICAL_HEAD", journalOnly.path("historical_cumulative_k_status").asText());
        ObjectNode journal = readObject(root.resolve("liquidation-v2-known-exposure-attempts.json"));
        ObjectNode firstJournalEntry = (ObjectNode) journal.path("entries").get(0);
        String firstBehavior = firstJournalEntry.path("behavior_sha256").asText();
        String firstDataset = firstJournalEntry.path("dataset_sha256").asText();

        // The known HEAD entry carries the behavior under another dataset. Its fixed pair
        // separately proves the journal's behavior/dataset pair, exercising both lookup paths.
        ObjectNode seeded = canonicalHead("seed-data",
                List.of(new HeadEntry(firstBehavior, JsonHashes.sha256("other-dataset"))),
                List.of(new HeadEntry(firstBehavior, firstDataset)));
        initialize(root, seeded);
        ObjectNode receipt = LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(frozen, root);
        ObjectNode reopened = LiquidationV2FamilyExposureAttemptV1.reopenFamilyExposureAttempts(root);
        ObjectNode finalHead = StrategyStatisticalV5.readExposureHeadFile(root.resolve("exposure-head.json"));

        assertEquals("KNOWN_FROM_CANONICAL_V5_HEAD", reopened.path("historical_cumulative_k_status").asText());
        assertEquals(9, reopened.path("historical_cumulative_k").asInt());
        assertEquals(9, reopened.path("known_v002_attempt_count").asInt(), "reconciliation retains the separate known-attempt journal");
        assertEquals(10, finalHead.path("fixed_attempt_pairs").size(), "one seeded fixed pair plus nine new behavior attempts");
        assertEquals(9, receipt.path("attempts").size());
        for (JsonNode attempt : receipt.path("attempts")) assertEquals("IDEMPOTENT_CANONICAL_HEAD_IDENTITY_JOURNAL_IDEMPOTENT", attempt.path("status").asText());
    }

    @Test
    void standaloneStressMatrixRecorderIsExplicitAndSyntheticRunsNeverTouchCustody() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("stress-only"));
        ObjectNode receipt = LiquidationV2FamilyExposureAttemptV1.recordStressScenarioAttemptsBeforeOutcomes(freeze(), root);
        assertEquals(5, receipt.path("attempts").size());
        assertEquals(5, LiquidationV2FamilyExposureAttemptV1.reopenFamilyExposureAttempts(root)
                .path("known_v002_attempt_count").asInt());

        Path syntheticRoot = temporary.resolve("synthetic-stress-only");
        ObjectNode skipped = LiquidationV2FamilyExposureAttemptV1.recordStressScenarioAttemptsBeforeOutcomes(
                freeze("SYNTHETIC_DEVELOPMENT_ONLY"), syntheticRoot);
        assertEquals("SYNTHETIC_FIXTURE_NOT_RECORDED", skipped.path("status").asText());
        assertFalse(Files.exists(syntheticRoot));
    }

    @Test
    void eachPhysicalFreezeEnvelopeFieldIsValidatedBeforeCustodyIsCreated() throws Exception {
        String envelope = "exposure must be recorded from a valid pre-outcome physical freeze";
        List<Consumer<ObjectNode>> mutations = List.of(
                f -> f.put("schema", "other"), f -> f.put("version", 2), f -> f.put("status", "AFTER_OUTCOMES"),
                f -> f.put("outcomes_examined", true), f -> f.put("source_mode", true),
                f -> f.put("dataset_root_sha256", 42), f -> f.putNull("candidate_inventory"),
                f -> f.put("precommit", "not-an-object"));
        for (int index = 0; index < mutations.size(); index++) {
            ObjectNode changed = freeze();
            mutations.get(index).accept(changed);
            reseal(changed);
            Path absentRoot = temporary.resolve("bad-freeze-" + index);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(changed, absentRoot));
            assertTrue(error.getMessage().contains(envelope), error.getMessage());
            assertFalse(Files.exists(absentRoot));
        }
        ObjectNode badDigest = freeze().put("dataset_root_sha256", "upper-case-not-a-hash");
        reseal(badDigest);
        IllegalArgumentException digestError = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(
                        badDigest, temporary.resolve("bad-freeze-digest")));
        assertTrue(digestError.getMessage().contains("dataset root SHA-256 must be lowercase SHA-256 hex"));
    }

    @Test
    void symlinkCustodyAndHeadFilesAreRejectedBeforeAnyAppend() throws Exception {
        Path actual = Files.createDirectories(temporary.resolve("actual-root"));
        Path alias = temporary.resolve("root-alias");
        Files.createSymbolicLink(alias, actual);
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(
                freeze(), alias));
        assertFalse(Files.exists(actual.resolve("liquidation-v2-known-exposure-attempts.json")));

        Path headRoot = Files.createDirectories(temporary.resolve("head-symlink"));
        Path outside = temporary.resolve("outside-head.json");
        Files.writeString(outside, "{}");
        Files.createSymbolicLink(headRoot.resolve("exposure-head.json"), outside);
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(
                freeze(), headRoot));
    }

    private static ObjectNode canonicalHead(String dataset, List<HeadEntry> entries, List<HeadEntry> fixedPairs) {
        String datasetSha = JsonHashes.sha256(dataset);
        ObjectNode args = JsonHashes.mapper().createObjectNode().put("hypothesisFamily", LiquidationV2FamilyExposureAttemptV1.FAMILY)
                .put("datasetSha256", datasetSha);
        ArrayNode rows = args.putArray("entries");
        for (HeadEntry entry : entries) rows.addObject().put("behavior_sha256", entry.behavior()).put("dataset_sha256", JsonHashes.sha256(entry.dataset()));
        if (fixedPairs != null) {
            ArrayNode pairs = args.putArray("fixedAttemptPairs");
            for (HeadEntry entry : fixedPairs) pairs.addObject().put("behavior_sha256", entry.behavior())
                    .put("dataset_sha256", JsonHashes.sha256(entry.dataset()));
        }
        return StrategyStatisticalV5.makeExposureHead(args);
    }

    private static void initialize(Path root, ObjectNode head) {
        StrategyStatisticalV5.initializeExposureHeadFile(JsonHashes.mapper().createObjectNode()
                .put("filePath", root.resolve("exposure-head.json").toString()).set("head", head));
    }

    private static ObjectNode freeze() throws Exception { return freeze("PROXY_DISCLOSED_DEVELOPMENT_ONLY"); }

    private static ObjectNode freeze(String source) throws Exception {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode().put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA)
                .put("version", 1).put("strategy_family", LiquidationV2FamilyExposureAttemptV1.FAMILY);
        ArrayNode candidates = inventory.putArray("current_candidates");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-routed-one-entry").put("variant", "ROUTED_REVERSAL_CONTINUATION");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-always-continuation-one-entry").put("variant", "ALWAYS_CONTINUATION_CONTROL");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-always-reversal-one-entry").put("variant", "ALWAYS_REVERSAL_CONTROL");
        candidates.addObject().put("candidate_id", "liquidation-v2-price-oi-only-event-diagnostic").put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        reseal(inventory);
        Path repository = repositoryRoot();
        ObjectNode precommit = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(repository.resolve(
                "docs/research/liquidation-daily-stress-v002/frozen-precommit.json")));
        ObjectNode freeze = JsonHashes.mapper().createObjectNode().put("schema", LiquidationV2FamilyExposureAttemptV1.FREEZE_SCHEMA)
                .put("version", 1).put("status", "FROZEN_BEFORE_OUTCOME_READ").put("outcomes_examined", false)
                .put("source_mode", source).put("dataset_root_sha256", JsonHashes.sha256("known-head-matrix-dataset"));
        freeze.set("candidate_inventory", inventory);
        freeze.set("precommit", precommit);
        freeze.put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        reseal(freeze);
        return freeze;
    }

    private static ObjectNode readObject(Path path) throws Exception {
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(path));
    }

    private static void reseal(ObjectNode value) {
        value.remove("content_sha256");
        value.put("content_sha256", JsonHashes.ownHash(value));
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve(
                "docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) cursor = cursor.getParent();
        if (cursor == null) throw new IllegalStateException("repository root unavailable to exposure HEAD test");
        return cursor;
    }

    private record HeadEntry(String behavior, String dataset) { }
}

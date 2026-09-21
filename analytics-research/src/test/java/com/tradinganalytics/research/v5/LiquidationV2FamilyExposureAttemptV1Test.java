package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiquidationV2FamilyExposureAttemptV1Test {
    @TempDir Path temporary;

    @Test
    void syntheticFixtureNeverMutatesFamilyCustody() throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve("synthetic-custody"));
        ObjectNode receipt = LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(
                freeze("SYNTHETIC_DEVELOPMENT_ONLY"), root);

        assertEquals("SYNTHETIC_FIXTURE_NOT_RECORDED", receipt.path("status").asText());
        assertFalse(receipt.path("custody_mutated").asBoolean(true));
        assertFalse(Files.exists(root.resolve("liquidation-v2-known-exposure-attempts.json")));
        assertFalse(Files.exists(root.resolve("exposure-head.json")));
    }

    @Test
    void coreFreezeRecordsAllFourArmsAndFiveSensitivityAttemptsBeforeOutcomesIdempotently() throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve("core-custody"));
        ObjectNode frozen = freeze("PROXY_DISCLOSED_DEVELOPMENT_ONLY");

        ObjectNode first = LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(frozen, root);
        Path journalPath = root.resolve("liquidation-v2-known-exposure-attempts.json");
        Path lockPath = root.resolve("liquidation-v2-known-exposure-attempts.json.lock");
        Object fileKey = Files.readAttributes(lockPath, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        ObjectNode afterFirst = LiquidationV2FamilyExposureAttemptV1.reopenFamilyExposureAttempts(root);
        ObjectNode second = LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(frozen, root);
        ObjectNode afterSecond = LiquidationV2FamilyExposureAttemptV1.reopenFamilyExposureAttempts(root);

        assertEquals(9, first.path("attempts").size(), "four core modes and the exact five-scenario matrix are attempts");
        assertEquals(9, afterFirst.path("known_v002_attempt_count").asInt());
        assertEquals(afterFirst.path("known_attempt_journal_sha256").asText(),
                afterSecond.path("known_attempt_journal_sha256").asText(), "retry is idempotent by candidate and freeze");
        assertEquals(9, afterSecond.path("known_v002_attempt_count").asInt());
        assertEquals("UNKNOWN_NO_VERIFIED_CANONICAL_HEAD", afterSecond.path("historical_cumulative_k_status").asText());
        assertTrue(afterSecond.path("historical_cumulative_k").isNull(), "new attempts do not manufacture predecessor K");
        assertEquals(9, second.path("attempts").size());
        assertEquals(fileKey, Files.readAttributes(lockPath, java.nio.file.attribute.BasicFileAttributes.class).fileKey(),
                "the crash-restartable advisory lock keeps a stable inode");

        JsonNode journal = JsonHashes.mapper().readTree(Files.readString(journalPath));
        assertEquals(9, journal.path("entries").size());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L),
                java.util.stream.StreamSupport.stream(journal.path("entries").spliterator(), false)
                        .map(row -> row.path("sequence").asLong()).toList());
    }

    @Test
    void stagedPlanRecordsCandidateAndItsOwnFiveScenarioMatrix() throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve("staged-custody"));
        ObjectNode frozen = freeze("PROXY_DISCLOSED_DEVELOPMENT_ONLY");
        ObjectNode plan = stagedPlan(frozen);

        ObjectNode receipt = LiquidationV2FamilyExposureAttemptV1.recordBeforeOutcomes(plan,
                LiquidationV2StagedCandidateInventoryV1.NO_MACRO_CANDIDATE, frozen, root);
        ObjectNode state = LiquidationV2FamilyExposureAttemptV1.reopenFamilyExposureAttempts(root);

        assertEquals(6, receipt.path("attempts").size());
        assertEquals(6, state.path("known_v002_attempt_count").asInt());
        assertTrue(state.path("historical_cumulative_k").isNull());
        assertEquals(1, countCandidate(state.path("known_v002_attempts"),
                LiquidationV2StagedCandidateInventoryV1.NO_MACRO_CANDIDATE));
        for (String scenario : List.of("fee_slippage", "funding_carry", "adverse_execution_gap",
                "liquidity_capacity", "venue_outage_blackout")) {
            assertEquals(1, countCandidate(state.path("known_v002_attempts"),
                    LiquidationV2StagedCandidateInventoryV1.NO_MACRO_CANDIDATE + "-stress-" + scenario));
        }
    }

    @Test
    void missingOrTruncatedJournalBehindItsDurableAnchorFailsClosed() throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve("anchor-custody"));
        LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(
                freeze("PROXY_DISCLOSED_DEVELOPMENT_ONLY"), root);
        Path journal = root.resolve("liquidation-v2-known-exposure-attempts.json");
        Files.delete(journal);

        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2FamilyExposureAttemptV1.reopenFamilyExposureAttempts(root));
    }

    private static int countCandidate(JsonNode rows, String candidateId) {
        int count = 0;
        for (JsonNode row : rows) if (candidateId.equals(row.path("candidate_id").asText())) count++;
        return count;
    }

    private static ObjectNode stagedPlan(ObjectNode freeze) {
        ObjectNode plan = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2StagedCandidateInventoryV1.SCHEMA).put("version", 1)
                .put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY)
                .put("mode_id", LiquidationV2StagedCandidateInventoryV1.THREE_STAGE_NO_MACRO)
                .put("candidate_id", LiquidationV2StagedCandidateInventoryV1.NO_MACRO_CANDIDATE)
                .put("source_mode", freeze.path("source_mode").asText())
                .put("anchor_inventory_sha256", "a".repeat(64));
        plan.putObject("candidate").put("candidate_id", LiquidationV2StagedCandidateInventoryV1.NO_MACRO_CANDIDATE)
                .put("macro_gate_policy", "STRUCTURE_ONLY");
        plan.put("content_sha256", JsonHashes.ownHash(plan));
        return plan;
    }

    private static ObjectNode freeze(String sourceMode) throws Exception {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA).put("version", 1)
                .put("strategy_family", LiquidationV2FamilyExposureAttemptV1.FAMILY);
        ArrayNode candidates = inventory.putArray("current_candidates");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-routed-one-entry")
                .put("variant", "ROUTED_REVERSAL_CONTINUATION");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-always-continuation-one-entry")
                .put("variant", "ALWAYS_CONTINUATION_CONTROL");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-always-reversal-one-entry")
                .put("variant", "ALWAYS_REVERSAL_CONTROL");
        candidates.addObject().put("candidate_id", "liquidation-v2-price-oi-only-event-diagnostic")
                .put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        inventory.put("content_sha256", JsonHashes.ownHash(inventory));
        ObjectNode precommit = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(repositoryRoot()
                .resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json")));
        ObjectNode freeze = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2FamilyExposureAttemptV1.FREEZE_SCHEMA).put("version", 1)
                .put("status", "FROZEN_BEFORE_OUTCOME_READ").put("outcomes_examined", false)
                .put("source_mode", sourceMode).put("dataset_root_sha256", "b".repeat(64));
        freeze.set("candidate_inventory", inventory);
        freeze.set("precommit", precommit);
        freeze.put("content_sha256", JsonHashes.ownHash(freeze));
        return freeze;
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve(
                "docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) cursor = cursor.getParent();
        if (cursor == null) throw new IllegalStateException("repository root unavailable to family exposure test");
        return cursor;
    }
}

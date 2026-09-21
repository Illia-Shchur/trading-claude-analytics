package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiquidationV2FamilyExposureValidationTest {
    @TempDir Path temporary;

    @Test
    void malformedCoreCandidateInventoryAndDetachedInventoryHashFailBeforeCustodyMutation() throws Exception {
        Path acceptedRoot = temporary.resolve("accepted-core-baseline");
        ObjectNode accepted = LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(
                freeze(), acceptedRoot);
        assertEquals(9, accepted.path("attempts").size(), "four core arms plus the frozen five-scenario matrix");
        assertEquals(9, LiquidationV2FamilyExposureAttemptV1.reopenFamilyExposureAttempts(acceptedRoot)
                .path("known_v002_attempts").size());

        Path malformedRoot = temporary.resolve("malformed-inventory");
        ObjectNode malformed = freeze();
        ObjectNode inventory = (ObjectNode) malformed.path("candidate_inventory");
        ((ObjectNode) inventory.path("current_candidates").get(0)).put("variant", "UNFROZEN_VARIANT");
        rehash(inventory);
        malformed.put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        rehash(malformed);

        assertFailureContains(() -> LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(
                malformed, malformedRoot), "unknown or duplicate candidate/variant");
        assertFalse(Files.exists(malformedRoot));

        Path detachedRoot = temporary.resolve("detached-inventory-hash");
        ObjectNode detached = freeze();
        detached.put("candidate_inventory_sha256", "f".repeat(64));
        rehash(detached);
        assertFailureContains(() -> LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(
                detached, detachedRoot), "does not bind its exact candidate inventory");
        assertFalse(Files.exists(detachedRoot));
    }

    @Test
    void stagedAttemptRejectsWrongFamilyAndCallerSelectedCandidateBeforeWriting() throws Exception {
        ObjectNode freeze = freeze();
        Path acceptedRoot = temporary.resolve("accepted-staged-baseline");
        ObjectNode accepted = LiquidationV2FamilyExposureAttemptV1.recordBeforeOutcomes(
                stagedPlan(freeze), LiquidationV2StagedCandidateInventoryV1.NO_MACRO_CANDIDATE,
                freeze, acceptedRoot);
        assertEquals(6, accepted.path("attempts").size(), "one staged candidate plus its five frozen scenario attempts");
        assertEquals(6, LiquidationV2FamilyExposureAttemptV1.reopenFamilyExposureAttempts(acceptedRoot)
                .path("known_v002_attempts").size());

        Path familyRoot = temporary.resolve("wrong-family");
        ObjectNode wrongFamily = stagedPlan(freeze).put("strategy_family", "another-family");
        rehash(wrongFamily);
        assertFailureContains(() -> LiquidationV2FamilyExposureAttemptV1.recordBeforeOutcomes(
                wrongFamily, LiquidationV2StagedCandidateInventoryV1.NO_MACRO_CANDIDATE, freeze, familyRoot),
                "detached from its frozen physical source/family");
        assertFalse(Files.exists(familyRoot));

        Path candidateRoot = temporary.resolve("wrong-candidate");
        ObjectNode plan = stagedPlan(freeze);
        assertFailureContains(() -> LiquidationV2FamilyExposureAttemptV1.recordBeforeOutcomes(
                plan, LiquidationV2StagedCandidateInventoryV1.MACRO_CANDIDATE, freeze, candidateRoot),
                "one exact frozen candidate ID");
        assertFalse(Files.exists(candidateRoot));
    }

    @Test
    void journalMustBeARegularFileAndEveryEntryMustMatchItsOwnHashChain() throws Exception {
        Path nonRegularRoot = Files.createDirectories(temporary.resolve("non-regular"));
        Files.createDirectory(nonRegularRoot.resolve("liquidation-v2-known-exposure-attempts.json"));
        assertFailureContains(() -> LiquidationV2FamilyExposureAttemptV1.reopenFamilyExposureAttempts(nonRegularRoot),
                "not a regular non-symlink file");

        Path tamperedRoot = Files.createDirectories(temporary.resolve("bad-entry-chain"));
        LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(freeze(), tamperedRoot);
        Path journalPath = tamperedRoot.resolve("liquidation-v2-known-exposure-attempts.json");
        ObjectNode journal = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(journalPath));
        ((ObjectNode) journal.path("entries").get(0)).put("candidate_id", "rebound-after-freeze");
        rehash(journal); // valid outer hash must not conceal a broken immutable entry hash.
        Files.write(journalPath, JsonHashes.canonicalBytes(journal));

        assertFailureContains(() -> LiquidationV2FamilyExposureAttemptV1.reopenFamilyExposureAttempts(tamperedRoot),
                "hash chain is broken");
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
        rehash(plan);
        return plan;
    }

    private static ObjectNode freeze() throws Exception {
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
        rehash(inventory);
        Path repo = repositoryRoot();
        ObjectNode precommit = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(repo.resolve(
                "docs/research/liquidation-daily-stress-v002/frozen-precommit.json")));
        ObjectNode freeze = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2FamilyExposureAttemptV1.FREEZE_SCHEMA).put("version", 1)
                .put("status", "FROZEN_BEFORE_OUTCOME_READ").put("outcomes_examined", false)
                .put("source_mode", "PROXY_DISCLOSED_DEVELOPMENT_ONLY").put("dataset_root_sha256", "b".repeat(64));
        freeze.set("candidate_inventory", inventory);
        freeze.put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        freeze.set("precommit", precommit);
        rehash(freeze);
        return freeze;
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve(
                "docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) cursor = cursor.getParent();
        if (cursor == null) throw new IllegalStateException("repository root unavailable to exposure validation test");
        return cursor;
    }

    private static void rehash(ObjectNode object) {
        object.remove("content_sha256");
        object.put("content_sha256", JsonHashes.ownHash(object));
    }

    private static void assertFailureContains(org.junit.jupiter.api.function.Executable action, String message) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action);
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
    }
}

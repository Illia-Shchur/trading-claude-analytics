package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Mutates a valid persisted family journal one invariant at a time. */
class LiquidationV2FamilyExposureJournalMutationMatrixTest {
    @TempDir Path temporary;

    @Test
    void journalEnvelopeAndEntryHashesFailClosedAfterRehashingTheOuterDocument() throws Exception {
        Path schemaRoot = seededRoot("bad-schema");
        ObjectNode badSchema = readJournal(schemaRoot);
        badSchema.put("schema", "other-journal/1");
        reseal(badSchema);
        writeJournal(schemaRoot, badSchema);
        reject(schemaRoot, "v002 exposure journal schema/status/hash is invalid");

        Path statusRoot = seededRoot("bad-status");
        ObjectNode badStatus = readJournal(statusRoot);
        badStatus.put("cumulative_k_status", "KNOWN_FROM_NEW_ATTEMPTS");
        reseal(badStatus);
        writeJournal(statusRoot, badStatus);
        reject(statusRoot, "v002 exposure journal schema/status/hash is invalid");

        Path countRoot = seededRoot("bad-count");
        ObjectNode badCount = readJournal(countRoot);
        badCount.put("known_attempt_count", badCount.path("known_attempt_count").asInt() - 1);
        reseal(badCount);
        writeJournal(countRoot, badCount);
        reject(countRoot, "v002 exposure journal schema/status/hash is invalid");

        Path entryHashRoot = seededRoot("bad-entry-hash");
        ObjectNode badEntryHash = readJournal(entryHashRoot);
        ((ObjectNode) badEntryHash.path("entries").get(0)).put("behavior_sha256", "not-a-digest");
        reseal(badEntryHash);
        writeJournal(entryHashRoot, badEntryHash);
        reject(entryHashRoot, "v002 exposure attempt hash chain is broken");

        Path behaviorRoot = seededRoot("bad-behavior");
        ObjectNode badBehavior = readJournal(behaviorRoot);
        ((ObjectNode) badBehavior.path("entries").get(0)).put("behavior_sha256", "not-a-digest");
        rechain(badBehavior);
        writeJournal(behaviorRoot, badBehavior);
        reject(behaviorRoot, "attempt behavior SHA-256 must be lowercase SHA-256 hex");

        Path duplicateRoot = seededRoot("duplicate-attempt");
        ObjectNode duplicate = readJournal(duplicateRoot);
        JsonNode first = duplicate.path("entries").get(0);
        ObjectNode second = (ObjectNode) duplicate.path("entries").get(1);
        second.put("candidate_id", first.path("candidate_id").asText())
                .put("attempt_freeze_sha256", first.path("attempt_freeze_sha256").asText());
        rechain(duplicate);
        writeJournal(duplicateRoot, duplicate);
        reject(duplicateRoot, "v002 exposure journal has a duplicate candidate/freeze attempt");
    }

    @Test
    void durableAnchorDistinguishesTruncationTamperingAndInvalidEmptyTip() throws Exception {
        Path truncatedRoot = seededRoot("truncated");
        ObjectNode truncated = readJournal(truncatedRoot);
        ((ArrayNode) truncated.path("entries")).remove(truncated.path("entries").size() - 1);
        truncated.put("known_attempt_count", truncated.path("entries").size());
        reseal(truncated);
        writeJournal(truncatedRoot, truncated);
        reject(truncatedRoot, "v002 exposure journal is missing or truncated behind its durable anchor");

        Path wrongTipRoot = seededRoot("wrong-tip");
        Path anchorPath = anchorPath(wrongTipRoot);
        ObjectNode wrongTip = readObject(anchorPath);
        wrongTip.put("tip_sha256", "0".repeat(64));
        reseal(wrongTip);
        writeObject(anchorPath, wrongTip);
        reject(wrongTipRoot, "v002 exposure journal no longer contains the anchored attempt prefix");

        Path emptyAnchorRoot = Files.createDirectories(temporary.resolve("empty-anchor"));
        Path emptyAnchorPath = anchorPath(emptyAnchorRoot);
        ObjectNode malformedEmpty = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-family-exposure-attempt-journal-anchor/1")
                .put("version", 1).put("strategy_family", LiquidationV2FamilyExposureAttemptV1.FAMILY)
                .put("known_attempt_count", 0).put("tip_sha256", "1".repeat(64))
                .putNull("journal_content_sha256").put("historical_cumulative_k_status", "UNKNOWN_NO_VERIFIED_CANONICAL_HEAD");
        reseal(malformedEmpty);
        writeObject(emptyAnchorPath, malformedEmpty);
        reject(emptyAnchorRoot, "empty journal anchor has a non-genesis tip");

        Path anchorHashRoot = seededRoot("bad-anchor-schema");
        ObjectNode badAnchorSchema = readObject(anchorPath(anchorHashRoot));
        badAnchorSchema.put("schema", "untrusted-anchor/1");
        reseal(badAnchorSchema);
        writeObject(anchorPath(anchorHashRoot), badAnchorSchema);
        reject(anchorHashRoot, "v002 exposure journal anchor schema/hash is invalid");
    }

    @Test
    void validJournalRemainsReadableAndReportsUnknownHistoricalK() throws Exception {
        Path root = seededRoot("valid-baseline");
        ObjectNode state = assertDoesNotThrow(
                () -> LiquidationV2FamilyExposureAttemptV1.reopenFamilyExposureAttempts(root));
        assertTrue(state.path("historical_cumulative_k").isNull());
        assertTrue(state.path("historical_cumulative_k_status").asText().contains("UNKNOWN"));
        assertTrue(state.path("known_v002_attempt_count").asInt() > 0);
    }

    private Path seededRoot(String name) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(name));
        LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(freeze(), root);
        return root;
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
        reseal(inventory);
        Path root = repositoryRoot();
        ObjectNode precommit = readObject(root.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json"));
        ObjectNode freeze = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2FamilyExposureAttemptV1.FREEZE_SCHEMA).put("version", 1)
                .put("status", "FROZEN_BEFORE_OUTCOME_READ").put("outcomes_examined", false)
                .put("source_mode", "PROXY_DISCLOSED_DEVELOPMENT_ONLY")
                .put("dataset_root_sha256", JsonHashes.sha256("journal-mutation-matrix"));
        freeze.set("candidate_inventory", inventory);
        freeze.set("precommit", precommit);
        reseal(freeze);
        return freeze;
    }

    private static ObjectNode readJournal(Path root) throws Exception {
        return readObject(root.resolve("liquidation-v2-known-exposure-attempts.json"));
    }

    private static Path anchorPath(Path root) {
        return root.resolve("liquidation-v2-known-exposure-attempts.json.anchor.json");
    }

    private static ObjectNode readObject(Path path) throws Exception {
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(path));
    }

    private static void writeJournal(Path root, ObjectNode journal) throws Exception {
        writeObject(root.resolve("liquidation-v2-known-exposure-attempts.json"), journal);
    }

    private static void writeObject(Path path, ObjectNode value) throws Exception {
        Files.write(path, JsonHashes.canonicalBytes(value));
    }

    private static void rechain(ObjectNode journal) {
        ArrayNode entries = (ArrayNode) journal.path("entries");
        String previous = JsonHashes.sha256("LIQUIDATION_V2_FAMILY_EXPOSURE_GENESIS");
        for (int index = 0; index < entries.size(); index++) {
            ObjectNode entry = (ObjectNode) entries.get(index);
            entry.put("sequence", index + 1L).put("previous_sha256", previous);
            reseal(entry);
            previous = JsonHashes.canonicalSha256(entry);
        }
        journal.put("known_attempt_count", entries.size());
        reseal(journal);
    }

    private static void reseal(ObjectNode value) {
        value.remove("content_sha256");
        value.put("content_sha256", JsonHashes.ownHash(value));
    }

    private static void reject(Path root, String expectedMessage) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2FamilyExposureAttemptV1.reopenFamilyExposureAttempts(root));
        assertTrue(failure.getMessage().contains(expectedMessage),
                () -> "expected '" + expectedMessage + "' but got '" + failure.getMessage() + "'");
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve(
                "docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) cursor = cursor.getParent();
        if (cursor == null) throw new IllegalStateException("frozen precommit was not found from test cwd");
        return cursor;
    }
}

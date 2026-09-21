package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** Journal/anchor filesystem envelopes and committed-journal/lagging-anchor recovery cases. */
class LiquidationFamilyExposureJournalFilesystemMatrixV1Test {
    @TempDir Path temporary;

    @Test
    void validJournalAndAnchorReopenAndResealedFieldMutationsFailIndependently() throws Exception {
        Path accepted = seeded("accepted");
        assertTrue(LiquidationV2FamilyExposureAttemptV1.reopenFamilyExposureAttempts(accepted)
                .path("known_v002_attempt_count").asInt() > 0);

        rejectJournal("empty", path -> write(path, new byte[0]), "v002 exposure journal schema/status/hash is invalid");
        rejectJournal("array", path -> write(path, "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "v002 exposure journal schema/status/hash is invalid");
        rejectJournal("bad-json", path -> write(path, "{".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "cannot reopen v002 exposure journal");
        rejectJournal("version", path -> mutateJournal(path, node -> node.put("version", 2)),
                "v002 exposure journal schema/status/hash is invalid");
        rejectJournal("family", path -> mutateJournal(path, node -> node.put("strategy_family", "other-family")),
                "v002 exposure journal schema/status/hash is invalid");
        rejectJournal("known-total", path -> mutateJournal(path, node -> node.put("historical_cumulative_k", 9)),
                "v002 exposure journal schema/status/hash is invalid");
        rejectJournal("entries-type", path -> mutateJournal(path, node -> node.put("entries", "unexpected")),
                "v002 exposure journal schema/status/hash is invalid");
        rejectJournal("journal-directory", path -> {
            Files.delete(path);
            Files.createDirectory(path);
        }, "v002 exposure journal path is not a regular non-symlink file");
        rejectJournal("journal-symlink", path -> {
            Path saved = path.resolveSibling("journal-saved.json");
            Files.move(path, saved);
            Files.createSymbolicLink(path, saved);
        }, "v002 exposure journal path is not a regular non-symlink file");

        rejectAnchor("malformed-anchor-json", path -> write(path, "{".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "cannot reopen v002 exposure journal anchor");
        rejectAnchor("anchor-version", path -> mutateAnchor(path, node -> node.put("version", 2)),
                "v002 exposure journal anchor schema/hash is invalid");
        rejectAnchor("anchor-family", path -> mutateAnchor(path, node -> node.put("strategy_family", "other-family")),
                "v002 exposure journal anchor schema/hash is invalid");
        rejectAnchor("anchor-file-type", path -> {
            Files.delete(path);
            Files.createDirectory(path);
        }, "v002 exposure journal anchor is not a regular non-symlink file");
        rejectAnchor("anchor-symlink", path -> {
            Path saved = path.resolveSibling("anchor-saved.json");
            Files.move(path, saved);
            Files.createSymbolicLink(path, saved);
        }, "v002 exposure journal anchor is not a regular non-symlink file");
    }

    @Test
    void appendRepairsAnAnchorThatDurablyLagsAnAlreadyCommittedJournal() throws Exception {
        Path root = seeded("lagging-anchor");
        Path journalPath = journalPath(root);
        ObjectNode journal = readObject(journalPath);
        Path anchorPath = anchorPath(root);
        ObjectNode anchor = readObject(anchorPath);
        JsonNode first = journal.path("entries").get(0);
        anchor.put("known_attempt_count", 1).put("tip_sha256", JsonHashes.canonicalSha256(first));
        reseal(anchor);
        write(anchorPath, JsonHashes.canonicalBytes(anchor));

        LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(freezeWithDataset("lagging-anchor"), root);

        ObjectNode repaired = readObject(anchorPath);
        assertEquals(journal.path("entries").size(), repaired.path("known_attempt_count").asInt(),
                "the next locked append advances a lagging monotone witness over the intact committed journal");
        assertEquals(JsonHashes.canonicalSha256(journal.path("entries").get(journal.path("entries").size() - 1)),
                repaired.path("tip_sha256").asText());
        assertEquals(journal.path("entries").size(), LiquidationV2FamilyExposureAttemptV1
                .reopenFamilyExposureAttempts(root).path("known_v002_attempt_count").asInt());
    }

    @Test
    void appendRefusesJournalLockAndAtomicReplaceTemporarySymlinks() throws Exception {
        Path lockRoot = seeded("lock-symlink");
        Path lockPath = Path.of(journalPath(lockRoot) + ".lock");
        Path lockTarget = lockRoot.resolve("lock-target");
        Files.writeString(lockTarget, "outside lock target");
        Files.delete(lockPath);
        Files.createSymbolicLink(lockPath, lockTarget);
        IllegalArgumentException lockError = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(
                        freezeWithDataset("changed-lock-fixture"), lockRoot));
        assertTrue(lockError.getMessage().contains("v002 exposure journal cannot be a symbolic link"));

        Path temporaryRoot = seeded("temp-symlink");
        Path temporaryPath = Path.of(journalPath(temporaryRoot) + ".tmp");
        Path temporaryTarget = temporaryRoot.resolve("temporary-target");
        Files.writeString(temporaryTarget, "outside temporary target");
        Files.createSymbolicLink(temporaryPath, temporaryTarget);
        IllegalArgumentException temporaryError = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(
                        freezeWithDataset("changed-temp-fixture"), temporaryRoot));
        assertTrue(temporaryError.getMessage().contains("v002 journal temp file cannot be a symbolic link"));
        assertArrayEquals("outside lock target".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Files.readAllBytes(lockTarget), "a rejected lock symlink cannot modify its target");
        assertArrayEquals("outside temporary target".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Files.readAllBytes(temporaryTarget),
                "a rejected atomic-replacement symlink cannot modify its target");
    }

    private void rejectJournal(String id, FileMutation mutation, String message) throws Exception {
        Path root = seeded("journal-" + id);
        Path path = journalPath(root);
        byte[] original = Files.readAllBytes(path);
        mutation.apply(path);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2FamilyExposureAttemptV1.reopenFamilyExposureAttempts(root));
        assertTrue(error.getMessage().contains(message), "expected [" + message + "], got [" + error.getMessage() + "]");
        if ("journal-symlink".equals(id)) assertArrayEquals(original, Files.readAllBytes(root.resolve("journal-saved.json")),
                "reopening a rejected journal symlink must leave its target bytes unchanged");
    }

    private void rejectAnchor(String id, FileMutation mutation, String message) throws Exception {
        Path root = seeded("anchor-" + id);
        Path path = anchorPath(root);
        byte[] original = Files.readAllBytes(path);
        mutation.apply(path);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2FamilyExposureAttemptV1.reopenFamilyExposureAttempts(root));
        assertTrue(error.getMessage().contains(message), "expected [" + message + "], got [" + error.getMessage() + "]");
        if ("anchor-symlink".equals(id)) assertArrayEquals(original, Files.readAllBytes(root.resolve("anchor-saved.json")),
                "reopening a rejected anchor symlink must leave its target bytes unchanged");
    }

    private Path seeded(String name) throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve(name));
        LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(freezeWithDataset(name), root);
        return root;
    }

    private static void mutateJournal(Path path, Consumer<ObjectNode> edit) throws Exception {
        ObjectNode value = readObject(path);
        edit.accept(value);
        reseal(value);
        write(path, JsonHashes.canonicalBytes(value));
    }

    private static void mutateAnchor(Path path, Consumer<ObjectNode> edit) throws Exception {
        ObjectNode value = readObject(path);
        edit.accept(value);
        reseal(value);
        write(path, JsonHashes.canonicalBytes(value));
    }

    private static ObjectNode freezeWithDataset(String datasetId) throws Exception {
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
        ObjectNode precommit = readObject(repositoryRoot().resolve(
                "docs/research/liquidation-daily-stress-v002/frozen-precommit.json"));
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2FamilyExposureAttemptV1.FREEZE_SCHEMA).put("version", 1)
                .put("status", "FROZEN_BEFORE_OUTCOME_READ").put("outcomes_examined", false)
                .put("source_mode", "PROXY_DISCLOSED_DEVELOPMENT_ONLY")
                .put("dataset_root_sha256", JsonHashes.sha256(datasetId));
        result.set("candidate_inventory", inventory);
        result.set("precommit", precommit);
        reseal(result);
        return result;
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve(
                "docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) cursor = cursor.getParent();
        if (cursor == null) throw new IllegalStateException("frozen precommit was not found from test cwd");
        return cursor;
    }

    private static ObjectNode readObject(Path path) throws Exception {
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(path));
    }
    private static void write(Path path, byte[] bytes) throws Exception { Files.write(path, bytes); }
    private static void reseal(ObjectNode value) {
        value.remove("content_sha256");
        value.put("content_sha256", JsonHashes.ownHash(value));
    }
    private static Path journalPath(Path root) { return root.resolve("liquidation-v2-known-exposure-attempts.json"); }
    private static Path anchorPath(Path root) { return root.resolve("liquidation-v2-known-exposure-attempts.json.anchor.json"); }
    @FunctionalInterface private interface FileMutation { void apply(Path path) throws Exception; }
}

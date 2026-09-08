package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public replay-evidence checks using the same immutable bytes as the prospective writer. */
final class StrategyResearchV5ReplayEvidenceContractsTest {
    @TempDir Path temporary;

    @Test
    void verifyReplayEvidenceAcceptsWriterRoundTripAndBindsEveryPhysicalDigest() throws Exception {
        ReplayFixture fixture = replayFixture();
        assertThat(StrategyResearchV5.verifyReplayEvidenceV5(fixture.evidence())).isTrue();

        ObjectNode invalidFlag = fixture.evidence().deepCopy().put("invalid", true);
        assertThat(StrategyResearchV5.verifyReplayEvidenceV5(invalidFlag)).isFalse();
        ObjectNode wrongContent = fixture.evidence().deepCopy().put("content_sha256", hash("wrong-content"));
        assertThat(StrategyResearchV5.verifyReplayEvidenceV5(wrongContent)).isFalse();
        ObjectNode wrongBytes = fixture.evidence().deepCopy().put("byte_sha256", hash("wrong-bytes"));
        assertThat(StrategyResearchV5.verifyReplayEvidenceV5(wrongBytes)).isFalse();

        byte[] originalEntry = Files.readAllBytes(fixture.entryPath());
        try {
            Files.write(fixture.entryPath(), "tampered-entry".getBytes(StandardCharsets.UTF_8));
            assertThat(StrategyResearchV5.verifyReplayEvidenceV5(fixture.evidence())).isFalse();
        } finally {
            Files.write(fixture.entryPath(), originalEntry);
        }

        ObjectNode missingPath = fixture.evidence().deepCopy()
                .put("path", fixture.replayPath().resolve("missing-snapshot.json").toString());
        assertThat(StrategyResearchV5.verifyReplayEvidenceV5(missingPath)).isFalse();
    }

    @Test
    void verifyReplayEvidenceRejectsRegistryHeaderAndEntryLifecycleDrift() throws Exception {
        ReplayFixture fixture = replayFixture();

        assertRegistryFalse(fixture, value -> value.put("schema", "untrusted-registry"));
        assertRegistryFalse(fixture, value -> value.put("lineage_sha256", "not-a-digest"));
        assertRegistryFalse(fixture, value -> value.put("head_sha256", hash("wrong-head")));
        assertRegistryFalse(fixture, value -> value.put("current_head_sha256", hash("wrong-current")));
        assertRegistryFalse(fixture, value -> value.put("sequence", 0));
        assertRegistryFalse(fixture, value -> ((ArrayNode) value.path("entries")).removeAll());
        assertRegistryFalse(fixture, value -> ((ArrayNode) value.path("entry_refs")).remove(0));

        assertEntryFalse(fixture, entry -> entry.put("entry_sha256", "not-a-digest"));
        assertEntryFalse(fixture, entry -> entry.put("action", "UNKNOWN"));
        assertEntryFalse(fixture, entry -> entry.put("publication_payload_sha256", "not-a-digest"));
        assertEntryFalse(fixture, entry -> entry.put("previous_head_sha256", hash("wrong-previous")));
        assertEntryFalse(fixture, entry -> entry.put("sequence", 9));
    }

    @Test
    void verifyReplayEvidenceRejectsUnsafeOrUnboundEntryReferences() throws Exception {
        ReplayFixture fixture = replayFixture();
        assertRefFalse(fixture, ref -> ref.put("path", "../outside.json"));
        assertRefFalse(fixture, ref -> ref.put("path", "entries\\escape.json"));
        assertRefFalse(fixture, ref -> ref.put("sequence", 9));
        assertRefFalse(fixture, ref -> ref.put("entry_sha256", hash("wrong-entry")));
        assertRefFalse(fixture, ref -> ref.put("byte_sha256", "not-a-digest"));
        assertRefFalse(fixture, ref -> ref.put("path", "missing-entry.json"));

        // Keep the ref digest valid while changing only the in-memory entry.  This reaches
        // the reopened-byte equality guard rather than stopping at the ref hash check.
        ObjectNode changed = fixture.value().deepCopy();
        ObjectNode changedEntry = (ObjectNode) changed.path("entries").get(0);
        changedEntry.put("used_at", "2026-01-03T00:00:00.000Z");
        changedEntry.put("entry_sha256", StrategyResearchV5.ownHash(changedEntry, "entry_sha256"));
        ((ObjectNode) changed.path("entry_refs").get(0)).put(
                "entry_sha256", changedEntry.path("entry_sha256").asText());
        assertThat(StrategyResearchV5.verifyReplayEvidenceV5(writeSnapshot(fixture, changed))).isFalse();
    }

    private static void assertRegistryFalse(ReplayFixture fixture,
            java.util.function.Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode value = fixture.value().deepCopy();
        mutation.accept(value);
        value.put("content_sha256", StrategyResearchV5.ownHash(value));
        assertThat(StrategyResearchV5.verifyReplayEvidenceV5(writeSnapshot(fixture, value))).isFalse();
    }

    private static void assertEntryFalse(ReplayFixture fixture,
            java.util.function.Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode value = fixture.value().deepCopy();
        ObjectNode entry = (ObjectNode) value.path("entries").get(0);
        mutation.accept(entry);
        // Keep the entry/ref binding coherent so the targeted lifecycle or chain predicate runs.
        if (entry.path("entry_sha256").isTextual()
                && !"not-a-digest".equals(entry.path("entry_sha256").asText())) {
            entry.put("entry_sha256", StrategyResearchV5.ownHash(entry, "entry_sha256"));
        }
        ObjectNode ref = (ObjectNode) value.path("entry_refs").get(0);
        ref.put("entry_sha256", entry.path("entry_sha256").asText());
        byte[] originalEntry = Files.readAllBytes(fixture.entryPath());
        try {
            byte[] changedBytes = JsonHashes.mapper().writeValueAsBytes(entry);
            Files.write(fixture.entryPath(), changedBytes);
            ref.put("byte_sha256", hash(changedBytes));
            value.put("content_sha256", StrategyResearchV5.ownHash(value));
            assertThat(StrategyResearchV5.verifyReplayEvidenceV5(writeSnapshot(fixture, value))).isFalse();
        } finally {
            Files.write(fixture.entryPath(), originalEntry);
        }
    }

    private static void assertRefFalse(ReplayFixture fixture,
            java.util.function.Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode value = fixture.value().deepCopy();
        mutation.accept((ObjectNode) value.path("entry_refs").get(0));
        value.put("content_sha256", StrategyResearchV5.ownHash(value));
        assertThat(StrategyResearchV5.verifyReplayEvidenceV5(writeSnapshot(fixture, value))).isFalse();
    }

    private static ObjectNode writeSnapshot(ReplayFixture fixture, ObjectNode value) throws Exception {
        Files.write(fixture.snapshotPath(), JsonHashes.mapper().writeValueAsBytes(value));
        byte[] bytes = Files.readAllBytes(fixture.snapshotPath());
        return evidence(fixture.snapshotPath(), value, bytes);
    }

    private ReplayFixture replayFixture() throws Exception {
        Path replay = temporary.resolve("replay");
        Files.createDirectories(replay);
        String lineage = hash("replay-evidence-lineage");
        ObjectNode create = object().put("path", replay.toString()).put("lineage_sha256", lineage);
        ObjectNode initial = StrategyProspectiveV5.createReplayRegistry(create);
        ObjectNode reserve = object().put("path", replay.toString()).put("nonce", "contract-nonce")
                .put("expected_head_sha256", initial.path("head_sha256").asText())
                .put("publication_payload_sha256", hash("publication-payload"))
                .put("nowAt", "2026-01-02T00:00:00.000Z");
        StrategyProspectiveV5.reserveReplayNonce(reserve);

        ObjectNode value = StrategyProspectiveV5.readReplayRegistry(replay);
        Path snapshot = replay.resolve("snapshot.json");
        Files.write(snapshot, JsonHashes.mapper().writeValueAsBytes(value));
        Path entry = replay.resolve(value.path("entry_refs").get(0).path("path").asText()).normalize();
        return new ReplayFixture(replay, snapshot, entry, value,
                evidence(snapshot, value, Files.readAllBytes(snapshot)));
    }

    private static ObjectNode evidence(Path path, ObjectNode value, byte[] bytes) {
        return object().put("path", path.toString()).put("byte_sha256", hash(bytes))
                .put("content_sha256", value.path("content_sha256").asText()).set("value", value);
    }

    private static String hash(String value) {
        return StrategyResearchV5.hash(value);
    }

    private static String hash(byte[] value) {
        return StrategyResearchV5.hash(value);
    }

    private static ObjectNode object() {
        return JsonHashes.mapper().createObjectNode();
    }

    private record ReplayFixture(Path replayPath, Path snapshotPath, Path entryPath,
                                 ObjectNode value, ObjectNode evidence) { }
}

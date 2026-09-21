package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Filesystem and byte-envelope cases for reopening the immutable compute-budget chain. */
class LiquidationCheckpointLoadStateFilesystemBoundaryMatrixV1Test {
    @TempDir Path temporary;

    @Test
    void validReopenIgnoresLockFileAndUnsafeDirectoryEntriesAreRejected() throws Exception {
        Fixture accepted = fixture("lock-file");
        assertTrue(Files.isRegularFile(accepted.events().resolve(".append.lock")));
        assertDoesNotThrow(() -> {
            try (var reopened = LiquidationV2ReplayCheckpointV1.openNewRun(
                    accepted.scope(), accepted.binding(), () -> 1_000_000L)) {
                assertTrue(reopened.remainingComputeMillis() > 0);
            }
        }, "the coordination lock is storage metadata, not an event in the immutable sequence");

        Fixture nestedDirectory = fixture("nested-directory");
        Files.createDirectory(nestedDirectory.events().resolve("unexpected-directory"));
        assertFailure(nestedDirectory, "unexpected or unsafe file in budget event chain");

        Fixture linkedEvent = fixture("linked-event");
        Files.createSymbolicLink(linkedEvent.events().resolve("00000002.json"),
                linkedEvent.events().resolve("00000001.json"));
        assertFailure(linkedEvent, "unexpected or unsafe file in budget event chain");
    }

    @Test
    void eventParserRequiresBoundedCanonicalObjectRecordsAndContiguousSequenceNames() throws Exception {
        Fixture nonObject = fixture("non-object");
        Files.writeString(nonObject.events().resolve("00000001.json"), "[]");
        assertFailure(nonObject, "budget event must be a JSON object");

        Fixture nonCanonical = fixture("non-canonical");
        Path runStart = nonCanonical.events().resolve("00000001.json");
        Files.writeString(runStart, " " + JsonHashes.canonicalString(JsonHashes.mapper().readTree(runStart.toFile())));
        assertFailure(nonCanonical, "budget event is not stored in canonical immutable form");

        Fixture missingGenesis = fixture("missing-genesis");
        Files.delete(missingGenesis.events().resolve("00000000.json"));
        assertFailure(missingGenesis, "durable budget head exists while genesis event is missing; refusing recovery");

        Fixture reorderedRunStart = fixture("reordered-run-start");
        Files.move(reorderedRunStart.events().resolve("00000001.json"), reorderedRunStart.events().resolve("00000002.json"));
        assertFailure(reorderedRunStart, "budget event chain has a missing or reordered event at 00000001.json");

        Fixture oversized = fixture("oversized-event");
        Files.writeString(oversized.events().resolve("00000000.json"), "x".repeat(1_048_577));
        assertFailure(oversized, "budget event 00000000.json exceeds the bounded custody-file size");
    }

    @Test
    void completedCheckpointMustReferenceEveryFieldOfItsDurableReservation() throws Exception {
        for (String field : new String[] {"segment_id", "run_start_event_sha256", "binding_sha256", "reserved_millis"}) {
            Fixture fixture = fixture("checkpoint-reference-" + field);
            try (var session = LiquidationV2ReplayCheckpointV1.openNewRun(
                    fixture.scope(), fixture.binding(), () -> 1_000_000L)) {
                session.reserveSegment("verified-prefix", 1_000L).checkpoint(
                        Instant.parse("2024-01-01T00:00:00Z"), sha("result"), sha("ledger"), sha("curve"));
            }
            resealAfterMutation(fixture, 4, checkpoint -> {
                if ("reserved_millis".equals(field)) checkpoint.put(field, 999L);
                else checkpoint.put(field, sha("unrelated-" + field));
            });
            assertFailure(fixture, "checkpoint does not complete the current reservation");
        }
    }

    private Fixture fixture(String id) throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve(id));
        Path project = Files.createDirectories(root.resolve("project"));
        Path physical = Files.createDirectories(root.resolve("physical"));
        var scope = LiquidationV2ReplayCheckpointV1.FamilyScope.of("load-state-" + id,
                sha("precommit"), sha("profile"), sha("dataset"), root);
        var binding = LiquidationV2ReplayCheckpointV1.RunBinding.of(sha("freeze"), sha("request"), project,
                physical, sha("manifest"), sha("executor"), physical.resolve("replay.json"),
                physical.resolve("checkpoint.json"));
        try (var ignored = LiquidationV2ReplayCheckpointV1.openNewRun(scope, binding, () -> 1_000_000L)) { }
        return new Fixture(scope, binding, scope.ledgerDirectory().resolve("events"));
    }

    private static void assertFailure(Fixture fixture, String expected) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayCheckpointV1.openNewRun(fixture.scope(), fixture.binding(), () -> 2_000_000L));
        assertTrue(failure.getMessage().contains(expected),
                "expected [" + expected + "], got [" + failure.getMessage() + "]");
    }

    private static void resealAfterMutation(Fixture fixture, int changedSequence,
            java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> mutation) throws Exception {
        Path events = fixture.events();
        String predecessor = null;
        int sequence = 0;
        while (Files.exists(events.resolve(String.format(java.util.Locale.ROOT, "%08d.json", sequence)))) {
            Path path = events.resolve(String.format(java.util.Locale.ROOT, "%08d.json", sequence));
            var event = (com.fasterxml.jackson.databind.node.ObjectNode) JsonHashes.mapper().readTree(path.toFile());
            if (sequence == 0) event.putNull("previous_event_sha256");
            else event.put("previous_event_sha256", predecessor);
            if (sequence == changedSequence) mutation.accept(event);
            event.put("event_sha256", JsonHashes.ownHash(event, "event_sha256"));
            predecessor = event.path("event_sha256").asText();
            Files.write(path, JsonHashes.canonicalBytes(event));
            sequence++;
        }
        Path headPath = fixture.events().getParent().resolve("chain-head.json");
        var head = (com.fasterxml.jackson.databind.node.ObjectNode) JsonHashes.mapper().readTree(headPath.toFile());
        head.put("last_sequence", sequence - 1).put("last_event_sha256", predecessor);
        head.put("head_sha256", JsonHashes.ownHash(head, "head_sha256"));
        Files.write(headPath, JsonHashes.canonicalBytes(head));
    }

    private static String sha(String text) { return JsonHashes.sha256(text); }
    private record Fixture(LiquidationV2ReplayCheckpointV1.FamilyScope scope,
            LiquidationV2ReplayCheckpointV1.RunBinding binding, Path events) {}
}

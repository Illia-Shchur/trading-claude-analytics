package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public custody and lease boundary cases for the durable replay budget ledger. */
class LiquidationCheckpointCustodyBoundaryMatrixV1Test {
    @TempDir Path temporary;

    @Test
    void familyAndRunBindingsRejectUnsafeRootsTargetsAndMalformedIdentities() throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve("bindings"));
        Path project = Files.createDirectories(root.resolve("project"));
        Path physical = Files.createDirectories(root.resolve("physical"));
        assertThrows(IllegalArgumentException.class, () -> family(" ", root));
        assertThrows(IllegalArgumentException.class, () -> new LiquidationV2ReplayCheckpointV1.FamilyScope(
                "run", "bad", sha("profile"), sha("dataset"), root));

        Path alias = root.resolve("research-alias");
        Files.createSymbolicLink(alias, root);
        assertThrows(IllegalArgumentException.class, () -> family("alias-run", alias),
                "a research-root symlink cannot be used to create a second custody locator");

        assertThrows(IllegalArgumentException.class, () -> binding(root, project, physical,
                root.resolve("outside.json"), physical.resolve("checkpoint.json")),
                "immutable targets must be descendants of the verified physical root");
        assertThrows(IllegalArgumentException.class, () -> binding(root, project, physical,
                physical.resolve("same.json"), physical.resolve("./same.json")),
                "replay and checkpoint mirrors cannot alias one target");
        Files.createDirectory(physical.resolve("is-directory.json"));
        assertThrows(IllegalArgumentException.class, () -> binding(root, project, physical,
                physical.resolve("is-directory.json"), physical.resolve("checkpoint.json")),
                "an existing target must be a regular file");
        Path targetFile = Files.writeString(physical.resolve("real-result.json"), "immutable target");
        Files.createSymbolicLink(physical.resolve("linked-result.json"), targetFile);
        assertThrows(IllegalArgumentException.class, () -> binding(root, project, physical,
                physical.resolve("linked-result.json"), physical.resolve("checkpoint.json")),
                "an output target symlink cannot redirect persisted research artifacts");
    }

    @Test
    void publicLeaseLifecycleChecksReservationProjectionBoundaryAndClosedState() throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve("lease"));
        Path project = Files.createDirectories(root.resolve("project"));
        Path physical = Files.createDirectories(root.resolve("physical"));
        var family = family("lease-run", root);
        var binding = binding(root, project, physical, physical.resolve("replay.json"), physical.resolve("checkpoint.json"));
        AtomicLong clock = new AtomicLong(5_000_000_000L);
        var session = LiquidationV2ReplayCheckpointV1.openNewRun(family, binding, clock::get);

        assertThrows(IllegalArgumentException.class, () -> session.reserveSegment(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> session.reserveSegment("zero", 0));
        assertThrows(IllegalArgumentException.class, () -> session.checkProjection(-1));
        var segment = session.reserveSegment("first", 10);
        assertEquals(10L, segment.reservedMillis());
        assertEquals("first", segment.segmentId());
        assertThrows(IllegalArgumentException.class, () -> session.reserveSegment("second", 1));
        session.checkProjection(10);
        segment.ensureWithinReservation();
        clock.addAndGet(10_000_000L);
        segment.ensureWithinReservation();
        var checkpoint = segment.checkpoint(Instant.parse("2024-01-01T00:00:00Z"), sha("result"), sha("ledger"), sha("curve"));
        assertEquals(checkpoint, session.latestCheckpoint());
        assertEquals(0L, session.reservedComputeMillis());
        assertThrows(IllegalArgumentException.class, segment::ensureWithinReservation);
        assertThrows(IllegalArgumentException.class, () -> segment.checkpoint(
                checkpoint.boundaryExclusive(), sha("other"), sha("ledger"), sha("curve")));

        var backwards = session.reserveSegment("backwards", 5);
        assertThrows(IllegalArgumentException.class, () -> backwards.checkpoint(
                Instant.parse("2023-12-31T23:59:59Z"), sha("result2"), sha("ledger2"), sha("curve2")),
                "a later durable prefix cannot move the exclusive boundary backward");
        backwards.abort();
        session.close();
        session.close();
        assertThrows(IllegalArgumentException.class, session::remainingComputeMillis);
    }

    @Test
    void resumeRefusesMissingChainAndCannotIgnoreUnsafeEventDirectory() throws Exception {
        Path root = Files.createDirectories(temporary.toRealPath().resolve("missing-chain"));
        Path project = Files.createDirectories(root.resolve("project"));
        Path physical = Files.createDirectories(root.resolve("physical"));
        var family = family("missing-run", root);
        var binding = binding(root, project, physical, physical.resolve("replay.json"), physical.resolve("checkpoint.json"));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayCheckpointV1.resume(
                family, binding, () -> 1_000_000L, 1));

        var opened = LiquidationV2ReplayCheckpointV1.openNewRun(family, binding, () -> 1_000_000L);
        opened.close();
        Path events = family.ledgerDirectory().resolve("events");
        Path saved = events.resolveSibling("events-saved");
        Files.move(events, saved);
        Files.createSymbolicLink(events, saved);
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayCheckpointV1.openNewRun(
                family, binding, () -> 1_000_000L),
                "unsafe event storage cannot be traversed while opening a family session");
        assertTrue(Files.isSymbolicLink(events));
        assertFalse(Files.isRegularFile(events));
    }

    private static LiquidationV2ReplayCheckpointV1.FamilyScope family(String run, Path root) {
        return LiquidationV2ReplayCheckpointV1.FamilyScope.of(run, sha("precommit"), sha("profile"), sha("dataset"), root);
    }

    private static LiquidationV2ReplayCheckpointV1.RunBinding binding(Path root, Path project, Path physical,
            Path output, Path checkpoint) {
        return LiquidationV2ReplayCheckpointV1.RunBinding.of(sha("freeze"), sha("request"), project, physical,
                sha("manifest"), sha("executor"), output, checkpoint);
    }

    private static String sha(String value) { return JsonHashes.sha256(value); }
}

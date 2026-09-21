package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Extra custody and boundary cases for the hash-chained family compute ledger. */
class LiquidationReplayCheckpointBoundaryV1Test {
    @TempDir Path temporary;
    private Path physical;
    private Path project;
    private LiquidationV2ReplayCheckpointV1.FamilyScope family;
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);

    @BeforeEach
    void setUp() throws Exception {
        temporary = temporary.toRealPath();
        physical = Files.createDirectories(temporary.resolve("physical"));
        project = Files.createDirectories(temporary.resolve("project"));
        family = LiquidationV2ReplayCheckpointV1.FamilyScope.of("checkpoint-boundary-test",
                sha("precommit"), sha("profile"), sha("manifest"), temporary);
    }

    @Test
    void checkpointJsonAndRunTargetsRejectMalformedOrEscapingIdentities() {
        LiquidationV2ReplayCheckpointV1.Checkpoint valid = new LiquidationV2ReplayCheckpointV1.Checkpoint(
                Instant.parse("2024-01-01T00:00:00Z"), sha("result"), sha("ledger"), sha("curve"));
        assertEquals(valid, LiquidationV2ReplayCheckpointV1.checkpointFromJson(valid.toJson()));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayCheckpointV1.checkpointFromJson(null));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayCheckpointV1.checkpointFromJson(JsonHashes.mapper().createObjectNode()));

        ObjectNode badTime = valid.toJson().put("boundary_exclusive", "not-an-instant");
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayCheckpointV1.checkpointFromJson(badTime));
        ObjectNode badDigest = valid.toJson().put("ledger_sha256", "ABC");
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayCheckpointV1.checkpointFromJson(badDigest));
        assertThrows(IllegalArgumentException.class, () -> new LiquidationV2ReplayCheckpointV1.Checkpoint(
                valid.boundaryExclusive(), "", valid.ledgerSha256(), valid.accountCurveSha256()));

        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayCheckpointV1.FamilyScope.of(
                " ", sha("precommit"), sha("profile"), sha("manifest"), temporary));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayCheckpointV1.FamilyScope.of(
                "bad-hash", "ABC", sha("profile"), sha("manifest"), temporary));

        assertThrows(IllegalArgumentException.class, () -> binding(physical.resolve("../escape.json"), physical.resolve("checkpoint.json")));
        assertThrows(IllegalArgumentException.class, () -> binding(physical.resolve("same.json"), physical.resolve("same.json")));
        assertThrows(IllegalArgumentException.class, () -> binding(physical, physical.resolve("checkpoint.json")));
    }

    @Test
    void symlinkedRootsAndTargetAncestorsAreNotAcceptedAsCustodyDirectories() throws Exception {
        Path link = temporary.resolve("physical-link");
        Files.createSymbolicLink(link, physical);
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayCheckpointV1.FamilyScope.of(
                "symlink-root", sha("precommit"), sha("profile"), sha("manifest"), link));
        assertThrows(IllegalArgumentException.class,
                () -> binding(link.resolve("result.json"), physical.resolve("checkpoint.json")));
    }

    @Test
    void malformedDurableHeadFailsClosedWithoutModifyingTheEventChain() throws Exception {
        LiquidationV2ReplayCheckpointV1.RunBinding binding = binding(
                physical.resolve("result.json"), physical.resolve("checkpoint.json"));
        LiquidationV2ReplayCheckpointV1.Session session = LiquidationV2ReplayCheckpointV1.openNewRun(
                family, binding, clock::get);
        session.close();

        Path ledger = family.ledgerDirectory();
        Path events = ledger.resolve("events");
        byte[] before = Files.readAllBytes(events.resolve("00000000.json"));
        Files.writeString(ledger.resolve("chain-head.json"), "[]");
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayCheckpointV1.openNewRun(
                family, binding("other.json"), clock::get));
        assertTrue(Files.exists(events.resolve("00000001.json")), "the committed run-start event remains intact after rejection");
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(events.resolve("00000000.json"))));
    }

    private LiquidationV2ReplayCheckpointV1.RunBinding binding(Path output, Path checkpoint) {
        return LiquidationV2ReplayCheckpointV1.RunBinding.of(sha("freeze"), sha("request"), project, physical,
                sha("manifest"), sha("executor"), output, checkpoint);
    }

    private LiquidationV2ReplayCheckpointV1.RunBinding binding(String outputName) {
        return binding(physical.resolve(outputName), physical.resolve(outputName + ".checkpoint.json"));
    }

    private static String sha(String value) { return JsonHashes.sha256(value); }
}

package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiquidationV2ReplayCheckpointV1Test {
    @TempDir Path temporary;
    private Path project;
    private Path physical;
    private LiquidationV2ReplayCheckpointV1.FamilyScope family;
    private final AtomicLong clockNanos = new AtomicLong(5_000_000_000L);

    @BeforeEach
    void setUp() throws Exception {
        // macOS exposes the temporary directory through /var, which is a symlink to
        // /private/var. The checkpoint custody policy correctly rejects symlinked
        // ancestors, so bind the fixture to the canonical temp root it actually owns.
        temporary = temporary.toRealPath();
        project = Files.createDirectories(temporary.resolve("project"));
        physical = Files.createDirectories(temporary.resolve("physical"));
        family = LiquidationV2ReplayCheckpointV1.FamilyScope.of("liquidation-v2-family-2026-09",
                sha("frozen-precommit"), sha("frozen-profile"), sha("dataset-manifest"), temporary);
        clockNanos.set(5_000_000_000L);
    }

    @Test
    void verifiedPrefixReconstructionConsumesRemainingFamilyBudgetAndProjectionRetainsCheckpoint() {
        LiquidationV2ReplayCheckpointV1.RunBinding binding = binding("candidate-a", "result-a.json");
        LiquidationV2ReplayCheckpointV1.Session first = LiquidationV2ReplayCheckpointV1.openNewRun(
                family, binding, clockNanos::get);
        LiquidationV2ReplayCheckpointV1.Segment initial = first.reserveSegment("first-prefix", 86_340_000L);
        advanceMillis(86_340_000L);
        LiquidationV2ReplayCheckpointV1.Checkpoint saved = initial.checkpoint(
                Instant.parse("2024-04-01T00:00:00Z"), sha("prefix-result"), sha("prefix-ledger"), sha("prefix-curve"));
        assertEquals(86_340_000L, first.consumedComputeMillis());
        assertEquals(60_000L, first.remainingComputeMillis());
        first.close();

        LiquidationV2ReplayCheckpointV1.ResumeSession resumed = LiquidationV2ReplayCheckpointV1.resume(
                family, binding, clockNanos::get, 60_000L);
        assertEquals(saved, resumed.expectedPrefix());
        assertTrue(resumed.session().containsVerifiedCheckpoint(saved),
                "a checkpoint mirror can be recovered from its exact committed hash-chain event");
        assertFalse(resumed.session().containsVerifiedCheckpoint(new LiquidationV2ReplayCheckpointV1.Checkpoint(
                saved.boundaryExclusive(), sha("other-result"), saved.ledgerSha256(), saved.accountCurveSha256())),
                "same-boundary but altered prefix digests are not members of the durable chain");
        assertEquals(60_000L, resumed.session().remainingComputeMillis(),
                "opening the reconstruction lease does not pre-charge work that has not happened");
        advanceMillis(12_345L);
        LiquidationV2ReplayCheckpointV1.Checkpoint recomputed = new LiquidationV2ReplayCheckpointV1.Checkpoint(
                Instant.parse("2024-04-01T00:00:00Z"), sha("prefix-result"), sha("prefix-ledger"), sha("prefix-curve"));
        assertEquals(saved, resumed.prefixReconstruction().verifyReconstructedPrefix(recomputed));
        assertEquals(86_352_345L, resumed.session().consumedComputeMillis());
        assertEquals(47_655L, resumed.session().remainingComputeMillis(),
                "prefix reconstruction itself counts against the prior family consumption");
        assertEquals(LiquidationV2ReplayCheckpointV1.COMPUTE_INCOMPLETE,
                assertThrows(LiquidationV2ReplayCheckpointV1.ComputeIncompleteException.class,
                        () -> resumed.session().checkProjection(47_656L)).status());
        assertEquals(saved, resumed.session().latestCheckpoint(), "an over-budget projection keeps the last verified prefix");

        LiquidationV2ReplayCheckpointV1.RunBinding otherMode = binding("candidate-b", "result-b.json");
        LiquidationV2ReplayCheckpointV1.Session next = LiquidationV2ReplayCheckpointV1.openNewRun(
                family, otherMode, clockNanos::get);
        assertFalse(next.containsVerifiedCheckpoint(saved),
                "a checkpoint from an earlier candidate run is not a member of the current run binding");
        assertEquals(86_352_345L, next.consumedComputeMillis(),
                "candidate mode and output path changes reuse the stable family ledger");
        assertEquals(47_655L, next.remainingComputeMillis());
        next.close();
    }

    @Test
    void crashDebitsTheWholeDurableReservationBeforeAnyResumeWork() {
        LiquidationV2ReplayCheckpointV1.RunBinding binding = binding("candidate-a", "result-a.json");
        LiquidationV2ReplayCheckpointV1.Session interrupted = LiquidationV2ReplayCheckpointV1.openNewRun(
                family, binding, clockNanos::get);
        LiquidationV2ReplayCheckpointV1.Segment abandoned = interrupted.reserveSegment("decode-and-replay", 2_000L);
        advanceMillis(250L);
        assertEquals(2_000L, abandoned.reservedMillis());
        // Deliberately do not close/abort: model process death while the persisted lease is open.

        LiquidationV2ReplayCheckpointV1.ResumeSession resumed = LiquidationV2ReplayCheckpointV1.resume(
                family, binding, clockNanos::get, 1_000L);
        assertEquals(2_000L, resumed.session().consumedComputeMillis(),
                "recovery debits the entire unfinished reservation, not caller-reported elapsed time");
        assertEquals(1_000L, resumed.session().reservedComputeMillis());
        assertThrows(IllegalArgumentException.class,
                () -> resumed.prefixReconstruction().verifyReconstructedPrefix(
                        new LiquidationV2ReplayCheckpointV1.Checkpoint(Instant.parse("2024-01-01T00:00:00Z"),
                                sha("forged"), sha("forged-ledger"), sha("forged-curve"))));
        advanceMillis(400L);
        resumed.prefixReconstruction().checkpoint(Instant.parse("2024-01-01T00:00:00Z"),
                sha("verified-from-start"), sha("verified-ledger"), sha("verified-curve"));
        assertEquals(2_400L, resumed.session().consumedComputeMillis());
        assertEquals(LiquidationV2ReplayCheckpointV1.COMPUTE_BUDGET_MILLIS - 2_400L,
                resumed.session().remainingComputeMillis());
    }

    @Test
    void crashBetweenImmutableEventAndHeadRecoversExactlyOneTailAndCleansOnlyKnownTemps() throws Exception {
        LiquidationV2ReplayCheckpointV1.RunBinding binding = binding("candidate-a", "event-before-head.json");
        LiquidationV2ReplayCheckpointV1.Session interrupted = LiquidationV2ReplayCheckpointV1.openNewRun(
                family, binding, clockNanos::get);
        Path ledger = family.ledgerDirectory();
        Path events = ledger.resolve("events");
        byte[] predecessorHead = Files.readAllBytes(ledger.resolve("chain-head.json"));

        interrupted.reserveSegment("durable-reservation", 2_000L);
        Files.write(ledger.resolve("chain-head.json"), predecessorHead,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE);
        Path eventTemporary = events.resolve("pending-" + java.util.UUID.randomUUID() + ".tmp");
        Path headTemporary = ledger.resolve("pending-head-" + java.util.UUID.randomUUID() + ".tmp");
        Files.writeString(eventTemporary, "partial event bytes");
        Files.writeString(headTemporary, "partial head bytes");
        // Deliberately abandon the open reservation, with the final event installed but the
        // durable high-water head still on its exact predecessor.

        LiquidationV2ReplayCheckpointV1.Session recovered = LiquidationV2ReplayCheckpointV1.openNewRun(
                family, binding("candidate-b", "after-crash.json"), clockNanos::get);
        assertEquals(2_000L, recovered.consumedComputeMillis(),
                "recovery advances the head over one verified append, then conservatively debits its open lease");
        assertFalse(Files.exists(eventTemporary), "only a recognized uncommitted event temp is removed");
        assertFalse(Files.exists(headTemporary), "only a recognized uncommitted head temp is removed");
        recovered.close();

        LiquidationV2ReplayCheckpointV1.FamilyScope badTempFamily = LiquidationV2ReplayCheckpointV1.FamilyScope.of(
                "liquidation-v2-bad-temp", family.precommitSha256(), family.profileSha256(),
                family.datasetManifestSha256(), temporary);
        LiquidationV2ReplayCheckpointV1.Session badTempSession = LiquidationV2ReplayCheckpointV1.openNewRun(
                badTempFamily, binding("candidate-a", "bad-temp.json"), clockNanos::get);
        Path badTemp = badTempFamily.ledgerDirectory().resolve("events/pending-not-a-uuid.tmp");
        Files.writeString(badTemp, "unrecognized");
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayCheckpointV1.openNewRun(
                badTempFamily, binding("candidate-b", "bad-temp-next.json"), clockNanos::get),
                "unrecognized temp names are corruption, not disposable crash debris");
        assertNotNull(badTempSession.toJson());
    }

    @Test
    void explicitGenesisReceiptRecoversOnlyTheInitialGenesisWriteWindow() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("genesis-recovery"));
        LiquidationV2ReplayCheckpointV1.FamilyScope genesisFamily = LiquidationV2ReplayCheckpointV1.FamilyScope.of(
                "liquidation-v2-genesis-recovery", family.precommitSha256(), family.profileSha256(),
                family.datasetManifestSha256(), root);
        LiquidationV2ReplayCheckpointV1.Session initialized = LiquidationV2ReplayCheckpointV1.openNewRun(
                genesisFamily, binding("candidate-a", "genesis-first.json"), clockNanos::get);
        initialized.close();

        Path ledger = genesisFamily.ledgerDirectory();
        Path events = ledger.resolve("events");
        Files.delete(events.resolve("00000001.json"));
        Files.delete(events.resolve("00000000.json"));
        Files.delete(ledger.resolve("chain-head.json"));
        assertTrue(Files.exists(ledger.resolve("chain-genesis-init.json")),
                "the initial event has a separately durable receipt before the chain head exists");

        LiquidationV2ReplayCheckpointV1.Session recovered = LiquidationV2ReplayCheckpointV1.openNewRun(
                genesisFamily, binding("candidate-b", "genesis-after-crash.json"), clockNanos::get);
        assertTrue(Files.exists(events.resolve("00000000.json")), "the hash-bound genesis intent is replayed");
        assertTrue(Files.exists(ledger.resolve("chain-head.json")), "recovered genesis receives a durable head");
        assertEquals(0L, recovered.consumedComputeMillis());
        recovered.close();
    }

    @Test
    void resumeBindsExactFreezeRequestManifestExecutorAndTargets() {
        LiquidationV2ReplayCheckpointV1.RunBinding binding = binding("candidate-a", "result-a.json");
        LiquidationV2ReplayCheckpointV1.Session session = LiquidationV2ReplayCheckpointV1.openNewRun(
                family, binding, clockNanos::get);
        LiquidationV2ReplayCheckpointV1.Checkpoint saved = session.reserveSegment("prefix", 1_000L)
                .checkpoint(Instant.parse("2024-01-01T00:00:00Z"), sha("result"), sha("ledger"), sha("curve"));
        session.close();

        LiquidationV2ReplayCheckpointV1.ResumeSession prefixAttempt = LiquidationV2ReplayCheckpointV1.resume(
                family, binding, clockNanos::get, 500L);
        LiquidationV2ReplayCheckpointV1.Checkpoint altered = new LiquidationV2ReplayCheckpointV1.Checkpoint(
                saved.boundaryExclusive().plusSeconds(1), sha("result"), saved.ledgerSha256(), saved.accountCurveSha256());
        assertThrows(IllegalArgumentException.class,
                () -> prefixAttempt.prefixReconstruction().checkpoint(altered.boundaryExclusive(), altered.resultSha256(),
                        altered.ledgerSha256(), altered.accountCurveSha256()),
                "a resumed segment cannot overwrite its saved prefix through the ordinary checkpoint path");
        assertThrows(IllegalArgumentException.class,
                () -> prefixAttempt.prefixReconstruction().verifyReconstructedPrefix(altered),
                "resume requires actual recomputed exclusive boundary and all prefix digests to match");
        assertEquals(saved, prefixAttempt.session().latestCheckpoint());
        prefixAttempt.prefixReconstruction().abort();
        prefixAttempt.session().close();

        LiquidationV2ReplayCheckpointV1.RunBinding changedFreeze = new LiquidationV2ReplayCheckpointV1.RunBinding(
                sha("different-freeze"), binding.requestSha256(), binding.projectRoot(), binding.physicalRoot(),
                binding.manifestSha256(), binding.executorIdentitySha256(), binding.outputTarget(), binding.checkpointTarget());
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayCheckpointV1.resume(
                family, changedFreeze, clockNanos::get, 1_000L));

        LiquidationV2ReplayCheckpointV1.RunBinding changedOutput = binding("candidate-a", "result-other.json");
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayCheckpointV1.resume(
                family, changedOutput, clockNanos::get, 1_000L));

        LiquidationV2ReplayCheckpointV1.FamilyScope changedDataset = LiquidationV2ReplayCheckpointV1.FamilyScope.of(
                family.researchRunId(), family.precommitSha256(), family.profileSha256(), sha("changed-dataset"), temporary);
        assertEquals(family.ledgerDirectory(), changedDataset.ledgerDirectory(),
                "the persistent locator stays stable under the shared run id");
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayCheckpointV1.openNewRun(
                changedDataset, binding("candidate-b", "changed-dataset.json"), clockNanos::get),
                "a dataset change cannot silently create a fresh family budget chain");
    }

    @Test
    void tamperedOrMissingChainMemberAndStaleSessionFailClosed() throws Exception {
        LiquidationV2ReplayCheckpointV1.RunBinding binding = binding("candidate-a", "result-a.json");
        LiquidationV2ReplayCheckpointV1.Session session = LiquidationV2ReplayCheckpointV1.openNewRun(
                family, binding, clockNanos::get);
        LiquidationV2ReplayCheckpointV1.Segment segment = session.reserveSegment("prefix", 1_000L);
        segment.checkpoint(Instant.parse("2024-01-01T00:00:00Z"), sha("result"), sha("ledger"), sha("curve"));
        Path events = family.ledgerDirectory().resolve("events");
        Path eventToTamper = events.resolve("00000001.json");
        byte[] original = Files.readAllBytes(eventToTamper);
        Files.writeString(eventToTamper, "{}", java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayCheckpointV1.resume(
                family, binding, clockNanos::get, 1_000L));
        Files.write(eventToTamper, original);
        session.close();

        Path missingRoot = Files.createDirectories(temporary.resolve("missing-chain"));
        LiquidationV2ReplayCheckpointV1.FamilyScope missingFamily = LiquidationV2ReplayCheckpointV1.FamilyScope.of(
                family.researchRunId(), family.precommitSha256(), family.profileSha256(), family.datasetManifestSha256(), missingRoot);
        LiquidationV2ReplayCheckpointV1.Session missingSession = LiquidationV2ReplayCheckpointV1.openNewRun(
                missingFamily, binding("candidate-a", "missing-result.json"), clockNanos::get);
        Path missingEvent = missingFamily.ledgerDirectory().resolve("events/00000000.json");
        Files.delete(missingEvent);
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayCheckpointV1.openNewRun(
                missingFamily, binding("candidate-b", "missing-result-b.json"), clockNanos::get));
        // The intentionally damaged chain is no longer referenced by a live session.
        assertNotNull(missingSession.toJson());

        Path truncatedRoot = Files.createDirectories(temporary.resolve("truncated-chain"));
        LiquidationV2ReplayCheckpointV1.FamilyScope truncatedFamily = LiquidationV2ReplayCheckpointV1.FamilyScope.of(
                family.researchRunId(), family.precommitSha256(), family.profileSha256(), family.datasetManifestSha256(), truncatedRoot);
        LiquidationV2ReplayCheckpointV1.Session complete = LiquidationV2ReplayCheckpointV1.openNewRun(
                truncatedFamily, binding("candidate-a", "truncated-result.json"), clockNanos::get);
        complete.reserveSegment("prefix", 1_000L).checkpoint(Instant.parse("2024-01-01T00:00:00Z"),
                sha("result"), sha("ledger"), sha("curve"));
        complete.close();
        Files.delete(truncatedFamily.ledgerDirectory().resolve("events/00000003.json"));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2ReplayCheckpointV1.resume(
                truncatedFamily, binding("candidate-a", "truncated-result.json"), clockNanos::get, 1_000L),
                "durable high-water head detects deletion of the latest immutable checkpoint event");
    }

    @Test
    void monotonicClockAndReservationBoundsRejectRewindAndOverbudgetLease() {
        LiquidationV2ReplayCheckpointV1.RunBinding binding = binding("candidate-a", "result-a.json");
        LiquidationV2ReplayCheckpointV1.Session session = LiquidationV2ReplayCheckpointV1.openNewRun(
                family, binding, clockNanos::get);
        LiquidationV2ReplayCheckpointV1.Segment segment = session.reserveSegment("short", 1_000L);
        assertThrows(IllegalArgumentException.class,
                () -> session.reserveSegment("concurrent", 1L));
        clockNanos.set(4_000_000_000L);
        assertThrows(IllegalArgumentException.class, segment::elapsedMillis);
    }

    @Test
    void staleSessionCannotAppendAfterAnotherRunAdvancesTheFamilyChain() {
        LiquidationV2ReplayCheckpointV1.Session stale = LiquidationV2ReplayCheckpointV1.openNewRun(
                family, binding("candidate-a", "stale-a.json"), clockNanos::get);
        LiquidationV2ReplayCheckpointV1.Session current = LiquidationV2ReplayCheckpointV1.openNewRun(
                family, binding("candidate-b", "stale-b.json"), clockNanos::get);
        assertThrows(IllegalArgumentException.class, () -> stale.reserveSegment("stale-work", 1_000L));
        current.close();
        stale.close();
    }

    @Test
    void orderlyOverrunIsChargedAtMeasuredDurationAndStopsAtTheFixedCap() {
        LiquidationV2ReplayCheckpointV1.Session session = LiquidationV2ReplayCheckpointV1.openNewRun(
                family, binding("candidate-a", "overrun.json"), clockNanos::get);
        LiquidationV2ReplayCheckpointV1.Segment segment = session.reserveSegment("bounded", 100L);
        advanceMillis(150L);
        assertThrows(LiquidationV2ReplayCheckpointV1.ComputeIncompleteException.class,
                () -> segment.checkpoint(Instant.parse("2024-01-01T00:00:00Z"), sha("result"), sha("ledger"), sha("curve")));
        segment.abort();
        assertEquals(150L, session.consumedComputeMillis(), "clean overrun reports measured duration instead of only the lease");
        session.close();

        LiquidationV2ReplayCheckpointV1.Session lastMinute = LiquidationV2ReplayCheckpointV1.openNewRun(
                family, binding("candidate-b", "last-minute.json"), clockNanos::get);
        long remaining = LiquidationV2ReplayCheckpointV1.COMPUTE_BUDGET_MILLIS - 150L;
        LiquidationV2ReplayCheckpointV1.Segment full = lastMinute.reserveSegment("cap", remaining);
        advanceMillis(remaining + 1L);
        assertThrows(LiquidationV2ReplayCheckpointV1.ComputeIncompleteException.class,
                () -> full.checkpoint(Instant.parse("2024-01-01T00:00:00Z"), sha("result"), sha("ledger"), sha("curve")));
        full.abort();
        assertEquals(LiquidationV2ReplayCheckpointV1.COMPUTE_BUDGET_MILLIS + 1L,
                lastMinute.consumedComputeMillis());
        assertEquals(0L, lastMinute.remainingComputeMillis());
        assertEquals(LiquidationV2ReplayCheckpointV1.COMPUTE_INCOMPLETE,
                assertThrows(LiquidationV2ReplayCheckpointV1.ComputeIncompleteException.class,
                        () -> lastMinute.reserveSegment("after-cap", 1L)).status());
        lastMinute.close();
    }

    private LiquidationV2ReplayCheckpointV1.RunBinding binding(String mode, String outputName) {
        return LiquidationV2ReplayCheckpointV1.RunBinding.of(sha("freeze-" + mode), sha("request-" + mode),
                project, physical, sha("manifest"), sha("executor-" + mode), physical.resolve(outputName),
                physical.resolve(outputName + ".checkpoint.json"));
    }

    private void advanceMillis(long millis) {
        clockNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    private static String sha(String value) { return JsonHashes.sha256(value); }
}

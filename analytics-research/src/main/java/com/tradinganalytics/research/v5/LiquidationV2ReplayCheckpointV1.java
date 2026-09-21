package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Durable cumulative compute-budget custody for restartable liquidation v002 research.
 *
 * <p>The family ledger is keyed only by the stable research run, precommit, profile, dataset,
 * and research root. Candidate/executor/request/output identity is bound separately to each
 * run and each checkpoint so changing a run target cannot reset family consumption.</p>
 */
public final class LiquidationV2ReplayCheckpointV1 {
    public static final String FAMILY_SCHEMA = "liquidation-v2-replay-budget-family/1";
    public static final String EVENT_SCHEMA = "liquidation-v2-replay-budget-event/1";
    public static final String CHECKPOINT_SCHEMA = "liquidation-v2-replay-checkpoint/1";
    public static final long COMPUTE_BUDGET_MILLIS = 86_400_000L;
    public static final String COMPUTE_INCOMPLETE = "COMPUTE_INCOMPLETE";

    private static final String LEDGER_DIRECTORY = ".liquidation-v2-replay-budget-v1";
    private static final String EVENTS_DIRECTORY = "events";
    private static final String HEAD_FILE = "chain-head.json";
    private static final String HEAD_SCHEMA = "liquidation-v2-replay-budget-chain-head/1";
    private static final String GENESIS_INIT_FILE = "chain-genesis-init.json";
    private static final String GENESIS_INIT_SCHEMA = "liquidation-v2-replay-budget-genesis-init/1";
    private static final String LOCK_FILE = ".append.lock";
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final int MAX_EVENTS = 1_000_000;
    private static final int MAX_EVENT_BYTES = 1_048_576;
    private static final int MAX_RECEIPT_BYTES = 65_536;
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private LiquidationV2ReplayCheckpointV1() {}

    /** Stable scope shared by every candidate mode and executor in one authorized research run. */
    public record FamilyScope(String researchRunId, String precommitSha256, String profileSha256,
            String datasetManifestSha256, Path researchRoot) {
        public FamilyScope {
            if (researchRunId == null || researchRunId.isBlank()) throw failure("research_run_id must be explicit and non-empty");
            requireHash(precommitSha256, "precommit_sha256");
            requireHash(profileSha256, "profile_sha256");
            requireHash(datasetManifestSha256, "dataset_manifest_sha256");
            researchRoot = canonicalExistingDirectory(researchRoot, "research_root");
        }

        public static FamilyScope of(String researchRunId, String precommitSha256, String profileSha256,
                String datasetManifestSha256, Path researchRoot) {
            return new FamilyScope(researchRunId, precommitSha256, profileSha256, datasetManifestSha256, researchRoot);
        }

        public ObjectNode toJson() {
            ObjectNode row = JsonHashes.mapper().createObjectNode().put("schema", FAMILY_SCHEMA)
                    .put("version", 1).put("research_run_id", researchRunId)
                    .put("precommit_sha256", precommitSha256).put("profile_sha256", profileSha256)
                    .put("dataset_manifest_sha256", datasetManifestSha256)
                    .put("research_root", researchRoot.toString())
                    .put("compute_budget_millis", COMPUTE_BUDGET_MILLIS)
                    .put("candidate_inventory_scopes_budget", false)
                    .put("executor_identity_scopes_budget", false)
                    .put("output_path_scopes_budget", false);
            row.put("scope_sha256", JsonHashes.ownHash(row));
            return row;
        }

        public String scopeSha256() { return toJson().path("scope_sha256").asText(); }

        public Path ledgerDirectory() {
            // The locator stays stable if a caller presents a changed precommit/profile/dataset
            // under the same explicit research-run ID; the stored genesis then refuses the scope.
            return researchRoot.resolve(LEDGER_DIRECTORY).resolve(JsonHashes.sha256(researchRunId)).normalize();
        }
    }

    /** Exact candidate/executor/request and immutable target binding for one resumable run. */
    public record RunBinding(String freezeSha256, String requestSha256, Path projectRoot, Path physicalRoot,
            String manifestSha256, String executorIdentitySha256, Path outputTarget, Path checkpointTarget) {
        public RunBinding {
            requireHash(freezeSha256, "freeze_sha256");
            requireHash(requestSha256, "request_sha256");
            projectRoot = canonicalExistingDirectory(projectRoot, "project_root");
            physicalRoot = canonicalExistingDirectory(physicalRoot, "physical_root");
            requireHash(manifestSha256, "manifest_sha256");
            requireHash(executorIdentitySha256, "executor_identity_sha256");
            outputTarget = canonicalTarget(outputTarget, "output_target");
            checkpointTarget = canonicalTarget(checkpointTarget, "checkpoint_target");
            if (!outputTarget.startsWith(physicalRoot) || !checkpointTarget.startsWith(physicalRoot)) {
                throw failure("output and checkpoint targets must remain beneath the bound physical root");
            }
            if (outputTarget.equals(checkpointTarget)) throw failure("output and checkpoint targets must be distinct");
        }

        public static RunBinding of(String freezeSha256, String requestSha256, Path projectRoot, Path physicalRoot,
                String manifestSha256, String executorIdentitySha256, Path outputTarget, Path checkpointTarget) {
            return new RunBinding(freezeSha256, requestSha256, projectRoot, physicalRoot, manifestSha256,
                    executorIdentitySha256, outputTarget, checkpointTarget);
        }

        public ObjectNode toJson() {
            ObjectNode row = JsonHashes.mapper().createObjectNode().put("freeze_sha256", freezeSha256)
                    .put("request_sha256", requestSha256).put("project_root", projectRoot.toString())
                    .put("physical_root", physicalRoot.toString()).put("manifest_sha256", manifestSha256)
                    .put("executor_identity_sha256", executorIdentitySha256)
                    .put("output_target", outputTarget.toString()).put("checkpoint_target", checkpointTarget.toString());
            row.put("binding_sha256", JsonHashes.ownHash(row));
            return row;
        }

        public String bindingSha256() { return toJson().path("binding_sha256").asText(); }
    }

    /** The verified exclusive event-time boundary and canonical prefix digests. */
    public record Checkpoint(Instant boundaryExclusive, String resultSha256, String ledgerSha256,
            String accountCurveSha256) {
        public Checkpoint {
            Objects.requireNonNull(boundaryExclusive, "boundaryExclusive");
            requireHash(resultSha256, "prefix result SHA-256");
            requireHash(ledgerSha256, "prefix ledger SHA-256");
            requireHash(accountCurveSha256, "prefix account-curve SHA-256");
        }

        public ObjectNode toJson() {
            return JsonHashes.mapper().createObjectNode().put("schema", CHECKPOINT_SCHEMA)
                    .put("boundary_exclusive", boundaryExclusive.toString())
                    .put("result_sha256", resultSha256).put("ledger_sha256", ledgerSha256)
                    .put("account_curve_sha256", accountCurveSha256);
        }
    }

    /** Raised when a bounded projection/reservation cannot fit; the prior checkpoint stays durable. */
    public static final class ComputeIncompleteException extends IllegalStateException {
        private final long consumedComputeMillis;
        private final long remainingComputeMillis;

        private ComputeIncompleteException(String message, long consumedComputeMillis, long remainingComputeMillis) {
            super(message);
            this.consumedComputeMillis = consumedComputeMillis;
            this.remainingComputeMillis = remainingComputeMillis;
        }

        public String status() { return COMPUTE_INCOMPLETE; }
        public long consumedComputeMillis() { return consumedComputeMillis; }
        public long remainingComputeMillis() { return remainingComputeMillis; }
    }

    /**
     * Reopening returns the last committed prefix and an already-persisted reservation for
     * deterministic prefix reconstruction. Verify it before reserving any continuation work.
     */
    public record ResumeSession(Session session, Checkpoint expectedPrefix, Segment prefixReconstruction) {}

    /** Start a new candidate/executor run while retaining the existing family budget ledger. */
    public static Session openNewRun(FamilyScope scope, RunBinding binding, LongSupplier monotonicNanos) {
        Objects.requireNonNull(scope, "scope"); Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        Path ledger = scope.ledgerDirectory();
        boolean wasPresent = Files.exists(ledger, LinkOption.NOFOLLOW_LINKS);
        ensureLedgerDirectories(scope, !wasPresent);
        return withLock(ledger.resolve(EVENTS_DIRECTORY), () -> {
            State state = loadState(scope);
            if (state.pending != null) {
                appendRecoveryDebit(scope, state, "OPEN_NEW_RUN_AFTER_UNFINISHED_SEGMENT");
                state = loadState(scope);
            }
            if (state.events.isEmpty()) {
                if (wasPresent) throw failure("initialized family budget ledger is missing its event chain; refusing reset");
                ObjectNode genesis = event(scope, state, "GENESIS");
                genesis.set("family_scope", scope.toJson());
                genesis.put("consumed_compute_millis", 0L);
                append(scope, state, genesis);
                state = loadState(scope);
            }
            ObjectNode start = event(scope, state, "RUN_START");
            start.set("run_binding", binding.toJson());
            start.put("binding_sha256", binding.bindingSha256()).put("consumed_compute_millis", state.consumed);
            String startSha = append(scope, state, start);
            State updated = loadState(scope);
            return new Session(scope, binding, monotonicNanos, ledger.resolve(EVENTS_DIRECTORY),
                    updated, startSha);
        });
    }

    /**
     * Resume exactly the latest run and immediately persist a lease for reconstructing its
     * deterministic prefix. Any unclosed prior lease is fully debited before this lease is made.
     */
    public static ResumeSession resume(FamilyScope scope, RunBinding binding, LongSupplier monotonicNanos,
            long prefixReconstructionReservationMillis) {
        Objects.requireNonNull(scope, "scope"); Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        Path ledger = scope.ledgerDirectory();
        if (!Files.exists(ledger, LinkOption.NOFOLLOW_LINKS)) throw failure("family budget chain is missing; refusing resume");
        verifyLedgerDirectories(scope);
        Session session = withLock(ledger.resolve(EVENTS_DIRECTORY), () -> {
            State state = loadState(scope);
            if (state.events.isEmpty()) throw failure("family budget chain is empty; refusing resume");
            if (state.pending != null) {
                appendRecoveryDebit(scope, state, "PROCESS_INTERRUPTED_WITH_OPEN_RESERVATION");
                state = loadState(scope);
            }
            if (state.runBinding == null || !state.runBindingSha.equals(binding.bindingSha256())) {
                throw failure("resume freeze/request/executor/root/output/checkpoint binding differs from the latest run");
            }
            return new Session(scope, binding, monotonicNanos, ledger.resolve(EVENTS_DIRECTORY), state,
                    state.runStartSha);
        });
        Checkpoint expected = session.latestCheckpoint();
        Segment reconstruction = session.reservePrefixReconstruction(prefixReconstructionReservationMillis);
        return new ResumeSession(session, expected, reconstruction);
    }

    /** Validates/reopens a checkpoint JSON view without changing the family ledger. */
    public static Checkpoint checkpointFromJson(JsonNode value) {
        if (value == null || !value.isObject() || !CHECKPOINT_SCHEMA.equals(value.path("schema").asText())) {
            throw failure("checkpoint schema is missing or unsupported");
        }
        try {
            return new Checkpoint(Instant.parse(value.path("boundary_exclusive").asText()),
                    value.path("result_sha256").asText(), value.path("ledger_sha256").asText(),
                    value.path("account_curve_sha256").asText());
        } catch (RuntimeException error) {
            throw failure("checkpoint fields are invalid: " + error.getMessage());
        }
    }

    public static final class Session implements AutoCloseable {
        private final FamilyScope scope;
        private final RunBinding binding;
        private final LongSupplier monotonicNanos;
        private final Path eventsDirectory;
        private final String runStartSha;
        private long sequence;
        private long consumed;
        private String headSha;
        private Checkpoint checkpoint;
        private Segment active;
        private boolean closed;

        private Session(FamilyScope scope, RunBinding binding, LongSupplier monotonicNanos,
                Path eventsDirectory, State state, String runStartSha) {
            this.scope = scope; this.binding = binding; this.monotonicNanos = monotonicNanos;
            this.eventsDirectory = eventsDirectory; this.runStartSha = runStartSha;
            this.sequence = state.events.size(); this.consumed = state.consumed;
            this.headSha = state.headSha; this.checkpoint = state.checkpoint;
        }

        public synchronized long consumedComputeMillis() { return consumed; }

        /** Budget remaining after completed work and live measured time, not a promise about a reservation. */
        public synchronized long remainingComputeMillis() {
            ensureOpen();
            long live = active == null ? 0L : active.elapsedMillis();
            return Math.max(0L, COMPUTE_BUDGET_MILLIS - Math.addExact(consumed, live));
        }

        private ComputeIncompleteException incomplete(String message) {
            return new ComputeIncompleteException(COMPUTE_INCOMPLETE + ": " + message,
                    consumed, remainingComputeMillis());
        }

        public synchronized Checkpoint latestCheckpoint() { return checkpoint; }

        /**
         * Reopens the complete durable chain and confirms this exact checkpoint was committed
         * by the current run binding. A missing or malformed chain is an error, never a miss.
         */
        public synchronized boolean containsVerifiedCheckpoint(Checkpoint candidate) {
            ensureOpen();
            Objects.requireNonNull(candidate, "candidate");
            String candidateSha = JsonHashes.canonicalSha256(candidate.toJson());
            return withLock(eventsDirectory, () -> {
                State actual = loadState(scope);
                if (!Objects.equals(actual.runStartSha, runStartSha)
                        || !Objects.equals(actual.runBindingSha, binding.bindingSha256())) return false;
                for (JsonNode event : actual.events) {
                    if (!"CHECKPOINT".equals(event.path("event_type").asText())
                            || !runStartSha.equals(event.path("run_start_event_sha256").asText())
                            || !binding.bindingSha256().equals(event.path("binding_sha256").asText())) continue;
                    if (candidateSha.equals(JsonHashes.canonicalSha256(event.path("checkpoint")))) return true;
                }
                return false;
            });
        }

        public synchronized long reservedComputeMillis() { return active == null ? 0L : active.reservedMillis; }

        public synchronized ObjectNode toJson() {
            ensureOpen();
            ObjectNode row = JsonHashes.mapper().createObjectNode()
                    .put("schema", "liquidation-v2-replay-budget-session/1")
                    .put("status", COMPUTE_INCOMPLETE.equals(status()) ? COMPUTE_INCOMPLETE : "BUDGET_AVAILABLE")
                    .put("family_scope_sha256", scope.scopeSha256())
                    .put("run_binding_sha256", binding.bindingSha256())
                    .put("run_start_event_sha256", runStartSha)
                    .put("head_event_sha256", headSha).put("next_event_sequence", sequence)
                    .put("compute_budget_millis", COMPUTE_BUDGET_MILLIS)
                    .put("consumed_compute_millis", consumed)
                    .put("remaining_compute_millis", remainingComputeMillis())
                    .put("reserved_compute_millis", reservedComputeMillis())
                    .put("custody_limit", "LOCAL_HASH_CHAIN_FAILS_CLOSED_IF_PRESENT_CHAIN_IS_MISSING_OR_INCONSISTENT");
            if (checkpoint != null) row.set("latest_checkpoint", checkpoint.toJson()); else row.putNull("latest_checkpoint");
            row.put("content_sha256", JsonHashes.ownHash(row));
            return row;
        }

        private String status() { return remainingComputeMillis() == 0 ? COMPUTE_INCOMPLETE : "BUDGET_AVAILABLE"; }

        /** Fail with COMPUTE_INCOMPLETE before work when a projected segment exceeds the remaining budget. */
        public synchronized void checkProjection(long projectedMillis) {
            ensureOpen();
            if (projectedMillis < 0) throw failure("projected compute duration must be nonnegative");
            long elapsed = active == null ? 0L : active.elapsedMillis();
            long withinBudget = Math.max(0L, COMPUTE_BUDGET_MILLIS - Math.addExact(consumed, elapsed));
            if (projectedMillis > withinBudget || (active != null
                    && projectedMillis > Math.max(0L, active.reservedMillis - elapsed))) {
                throw incomplete("projected compute exceeds the remaining reserved research budget");
            }
        }

        /** Persist an immutable hash-chained reservation before a bounded compute segment begins. */
        public synchronized Segment reserveSegment(String segmentId, long reservedMillis) {
            return reserveSegment(segmentId, reservedMillis, false);
        }

        private synchronized Segment reservePrefixReconstruction(long reservedMillis) {
            return reserveSegment("PREFIX_RECONSTRUCTION", reservedMillis, true);
        }

        private synchronized Segment reserveSegment(String segmentId, long reservedMillis,
                boolean reconstructionRequired) {
            ensureOpen();
            if (active != null) throw failure("a compute segment reservation is already active");
            if (segmentId == null || segmentId.isBlank()) throw failure("segment_id must be non-empty");
            if (reservedMillis <= 0) throw failure("reserved compute duration must be positive");
            long remaining = COMPUTE_BUDGET_MILLIS - consumed;
            if (reservedMillis > remaining) throw incomplete("segment reservation exceeds remaining research budget");
            ObjectNode reservation = event(scope, stateView(), "RESERVATION")
                    .put("run_start_event_sha256", runStartSha).put("binding_sha256", binding.bindingSha256())
                    .put("segment_id", segmentId).put("reserved_millis", reservedMillis)
                    .put("consumed_compute_millis", consumed);
            String reservationSha = appendExpected(reservation);
            active = new Segment(this, segmentId, reservedMillis, reservationSha,
                    monotonicNanos.getAsLong(), reconstructionRequired);
            return active;
        }

        private State stateView() {
            State state = new State(); state.events = new ArrayList<>(); state.consumed = consumed;
            state.headSha = headSha; state.runStartSha = runStartSha; state.runBindingSha = binding.bindingSha256();
            state.checkpoint = checkpoint; state.sequence = sequence; return state;
        }

        private String appendExpected(ObjectNode value) {
            return withLock(eventsDirectory, () -> {
                State actual = loadState(scope);
                if (actual.events.size() != sequence || !Objects.equals(actual.headSha, headSha)
                        || actual.consumed != consumed || !Objects.equals(actual.runStartSha, runStartSha)
                        || !Objects.equals(actual.runBindingSha, binding.bindingSha256())) {
                    throw failure("stale session predecessor; reopen the latest checkpoint before appending");
                }
                String sha = append(scope, actual, value);
                State updated = loadState(scope);
                sequence = updated.events.size(); consumed = updated.consumed;
                headSha = updated.headSha; checkpoint = updated.checkpoint;
                return sha;
            });
        }

        private synchronized void finish(Segment segment, String eventType, Checkpoint nextCheckpoint) {
            ensureOpen();
            if (active != segment) throw failure("compute segment is not the active reservation");
            long elapsed = segment.elapsedMillis();
            if (elapsed > segment.reservedMillis) throw incomplete("segment exceeded its durable compute reservation");
            if (eventType.equals("CHECKPOINT")) {
                if (checkpoint != null && nextCheckpoint.boundaryExclusive().isBefore(checkpoint.boundaryExclusive())) {
                    throw failure("checkpoint boundary cannot move backward");
                }
            }
            ObjectNode event = event(scope, stateView(), eventType)
                    .put("run_start_event_sha256", runStartSha).put("binding_sha256", binding.bindingSha256())
                    .put("segment_id", segment.segmentId).put("reservation_event_sha256", segment.reservationSha)
                    .put("reserved_millis", segment.reservedMillis).put("measured_elapsed_millis", elapsed)
                    .put("consumed_compute_millis", Math.addExact(consumed, elapsed));
            if (nextCheckpoint != null) event.set("checkpoint", nextCheckpoint.toJson());
            appendExpected(event);
            active = null;
        }

        private synchronized void recoverAbandonedReservation() {
            ensureOpen();
            if (active == null) return;
            Segment abandoned = active;
            active = null;
            long elapsed = abandoned.elapsedMillis();
            long chargedMillis = Math.max(abandoned.reservedMillis, elapsed);
            ObjectNode event = event(scope, stateView(), "ABORT")
                    .put("run_start_event_sha256", runStartSha).put("binding_sha256", binding.bindingSha256())
                    .put("segment_id", abandoned.segmentId).put("reservation_event_sha256", abandoned.reservationSha)
                    .put("reserved_millis", abandoned.reservedMillis).put("observed_elapsed_millis", elapsed)
                    .put("charged_millis", chargedMillis)
                    .put("consumed_compute_millis", Math.addExact(consumed, chargedMillis));
            appendExpected(event);
        }

        private void ensureOpen() {
            if (closed) throw failure("checkpoint session is closed");
        }

        @Override public synchronized void close() {
            if (closed) return;
            if (active != null) recoverAbandonedReservation();
            closed = true;
        }
    }

    /** A measured segment whose reservation is durable before its work starts. */
    public static final class Segment {
        private final Session session;
        private final String segmentId;
        private final long reservedMillis;
        private final String reservationSha;
        private final long startedAtNanos;
        private final boolean reconstructionRequired;
        private boolean finished;
        private boolean prefixVerified;

        private Segment(Session session, String segmentId, long reservedMillis, String reservationSha,
                long startedAtNanos, boolean reconstructionRequired) {
            this.session = session; this.segmentId = segmentId; this.reservedMillis = reservedMillis;
            this.reservationSha = reservationSha; this.startedAtNanos = startedAtNanos;
            this.reconstructionRequired = reconstructionRequired;
        }

        public synchronized long reservedMillis() { return reservedMillis; }
        public synchronized String segmentId() { return segmentId; }
        public synchronized long elapsedMillis() { return elapsedMillisAt(session.monotonicNanos.getAsLong(), startedAtNanos); }

        /** Cooperative poll for long-running replay loops; callers must stop when this throws. */
        public synchronized void ensureWithinReservation() {
            ensureActive();
            if (elapsedMillis() > reservedMillis) throw session.incomplete("segment exceeded its durable compute reservation");
            session.checkProjection(0L);
        }

        public synchronized Checkpoint checkpoint(Instant boundaryExclusive, String resultSha256,
                String ledgerSha256, String accountCurveSha256) {
            ensureActive();
            if (reconstructionRequired && session.latestCheckpoint() != null && !prefixVerified) {
                throw failure("resume must compare the recomputed prefix before writing a checkpoint");
            }
            Checkpoint next = new Checkpoint(boundaryExclusive, resultSha256, ledgerSha256, accountCurveSha256);
            session.finish(this, "CHECKPOINT", next);
            prefixVerified = true;
            finished = true;
            return next;
        }

        /** Reopens and compares the reconstructed prefix before allowing continuation. */
        public synchronized Checkpoint verifyReconstructedPrefix(Checkpoint recomputedPrefix) {
            ensureActive();
            Objects.requireNonNull(recomputedPrefix, "recomputedPrefix");
            Checkpoint saved = session.latestCheckpoint();
            if (saved == null || !saved.equals(recomputedPrefix)) throw failure("recomputed prefix does not match the saved checkpoint");
            session.finish(this, "CHECKPOINT", recomputedPrefix);
            prefixVerified = true;
            finished = true;
            return recomputedPrefix;
        }

        /** A cleanly abandoned bounded segment is charged in full and leaves the last prefix checkpoint intact. */
        public synchronized void abort() {
            ensureActive();
            session.recoverAbandonedReservation();
            finished = true;
        }

        private void ensureActive() {
            if (finished) throw failure("compute segment has already been completed");
        }
    }

    private static final class State {
        List<ObjectNode> events = new ArrayList<>();
        long sequence;
        long consumed;
        String headSha;
        String runStartSha;
        String runBindingSha;
        RunBinding runBinding;
        Checkpoint checkpoint;
        Reservation pending;
    }

    private record Reservation(String eventSha, String runStartSha, String bindingSha, String segmentId,
            long reservedMillis, long consumedBefore) {}

    @FunctionalInterface private interface LockedAction<T> { T run() throws IOException; }

    private static void ensureLedgerDirectories(FamilyScope scope, boolean allowCreate) {
        Path root = scope.researchRoot(); Path ledger = scope.ledgerDirectory();
        ensureNoSymlink(root);
        Path cursor = root;
        Path relative = root.relativize(ledger);
        for (Path part : relative) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(cursor) || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    throw failure("budget custody path contains a symlink or non-directory: " + cursor);
                }
            } else {
                if (!allowCreate) throw failure("family budget custody directory is missing");
                try { Files.createDirectory(cursor); }
                catch (IOException error) {
                    if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) throw failure("cannot create budget custody directory: " + error.getMessage());
                }
            }
        }
        Path events = ledger.resolve(EVENTS_DIRECTORY);
        boolean eventsPresent = Files.exists(events, LinkOption.NOFOLLOW_LINKS);
        if (eventsPresent && (Files.isSymbolicLink(events) || !Files.isDirectory(events, LinkOption.NOFOLLOW_LINKS))) {
            throw failure("budget event path contains a symlink or non-directory");
        }
        if (!eventsPresent) {
            if (!allowCreate) throw failure("family budget event chain is missing");
            try { Files.createDirectory(events); }
            catch (IOException error) {
                if (!Files.isDirectory(events, LinkOption.NOFOLLOW_LINKS)) throw failure("cannot create budget event directory: " + error.getMessage());
            }
        }
    }

    private static void verifyLedgerDirectories(FamilyScope scope) {
        Path ledger = scope.ledgerDirectory();
        if (!Files.isDirectory(ledger, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(ledger)
                || !Files.isDirectory(ledger.resolve(EVENTS_DIRECTORY), LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(ledger.resolve(EVENTS_DIRECTORY))) {
            throw failure("family budget chain directory is missing or unsafe");
        }
        ensureNoSymlink(ledger.resolve(EVENTS_DIRECTORY));
    }

    private static void ensureNoSymlink(Path path) {
        Path absolute = path.toAbsolutePath().normalize(); Path cursor = absolute.getRoot();
        for (Path part : absolute) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor)) {
                throw failure("symbolic link is not permitted in budget custody path: " + cursor);
            }
        }
    }

    private static State loadState(FamilyScope scope) throws IOException {
        Path eventsDir = scope.ledgerDirectory().resolve(EVENTS_DIRECTORY);
        if (!Files.isDirectory(eventsDir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(eventsDir)) {
            throw failure("family budget event chain is missing or unsafe");
        }
        cleanupRecognizedPendingFiles(scope);
        recoverGenesisIntent(scope);
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(eventsDir)) {
            for (Path file : stream) {
                if (file.getFileName().toString().equals(LOCK_FILE)) continue;
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw failure("unexpected or unsafe file in budget event chain: " + file.getFileName());
                }
                files.add(file);
            }
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        if (files.size() > MAX_EVENTS) throw failure("budget event chain exceeds the supported bound");
        State state = new State();
        String priorSha = null;
        long consumed = 0;
        Reservation pending = null;
        String runStartSha = null, runBindingSha = null;
        RunBinding runBinding = null;
        Checkpoint checkpoint = null;
        for (int index = 0; index < files.size(); index++) {
            Path file = files.get(index);
            String expectedName = eventFileName(index);
            if (!expectedName.equals(file.getFileName().toString())) throw failure("budget event chain has a missing or reordered event at " + expectedName);
            byte[] bytes = readBounded(file, MAX_EVENT_BYTES, "budget event " + expectedName);
            JsonNode parsed = JsonHashes.parse(bytes, "budget event " + expectedName);
            if (!(parsed instanceof ObjectNode event)) throw failure("budget event must be a JSON object");
            if (!java.util.Arrays.equals(bytes, JsonHashes.canonicalBytes(event))) {
                throw failure("budget event is not stored in canonical immutable form: " + expectedName);
            }
            if (!EVENT_SCHEMA.equals(event.path("schema").asText()) || event.path("sequence").asInt(-1) != index
                    || !scope.scopeSha256().equals(event.path("scope_sha256").asText())) {
                throw failure("budget event schema, sequence, or family scope is invalid");
            }
            String declaredSha = event.path("event_sha256").asText(); requireHash(declaredSha, "event_sha256");
            if (!declaredSha.equals(JsonHashes.ownHash(event, "event_sha256"))) throw failure("budget event hash mismatch at " + expectedName);
            if (index == 0) {
                if (!"GENESIS".equals(event.path("event_type").asText()) || !event.path("previous_event_sha256").isNull()
                        || !JsonHashes.canonicalSha256(scope.toJson()).equals(JsonHashes.canonicalSha256(event.path("family_scope")))
                        || event.path("consumed_compute_millis").asLong(-1) != 0) {
                    throw failure("budget family genesis is missing or mismatched");
                }
            } else if (!Objects.equals(priorSha, event.path("previous_event_sha256").asText())) {
                throw failure("budget event predecessor hash mismatch at " + expectedName);
            }
            String type = event.path("event_type").asText();
            switch (type) {
                case "GENESIS" -> {
                    if (index != 0) throw failure("budget genesis appears after event zero");
                }
                case "RUN_START" -> {
                    if (index == 0 || pending != null) throw failure("run start cannot follow an open reservation");
                    ObjectNode bindingJson = object(event, "run_binding");
                    runBinding = bindingFromJson(bindingJson);
                    runBindingSha = event.path("binding_sha256").asText();
                    if (!runBindingSha.equals(runBinding.bindingSha256())
                            || !runBindingSha.equals(bindingJson.path("binding_sha256").asText())
                            || event.path("consumed_compute_millis").asLong(-1) != consumed) {
                        throw failure("run start binding or cumulative compute is invalid");
                    }
                    runStartSha = declaredSha;
                    checkpoint = null;
                }
                case "RESERVATION" -> {
                    if (runStartSha == null || pending != null || !runStartSha.equals(event.path("run_start_event_sha256").asText())
                            || !runBindingSha.equals(event.path("binding_sha256").asText())) {
                        throw failure("segment reservation has no active run or duplicates an open reservation");
                    }
                    String segmentId = event.path("segment_id").asText();
                    long reserved = event.path("reserved_millis").asLong(-1);
                    if (segmentId.isBlank() || reserved <= 0 || reserved > COMPUTE_BUDGET_MILLIS - consumed
                            || event.path("consumed_compute_millis").asLong(-1) != consumed) {
                        throw failure("segment reservation duration or cumulative compute is invalid");
                    }
                    pending = new Reservation(declaredSha, runStartSha, runBindingSha, segmentId, reserved, consumed);
                }
                case "CHECKPOINT" -> {
                    if (pending == null || !reservationMatches(event, pending) || !runStartSha.equals(pending.runStartSha())) {
                        throw failure("checkpoint does not complete the current reservation");
                    }
                    long elapsed = event.path("measured_elapsed_millis").asLong(-1);
                    if (elapsed < 0 || elapsed > pending.reservedMillis()
                            || event.path("consumed_compute_millis").asLong(-1) != Math.addExact(consumed, elapsed)) {
                        throw failure("checkpoint elapsed compute is invalid");
                    }
                    consumed = Math.addExact(consumed, elapsed);
                    if (consumed > COMPUTE_BUDGET_MILLIS) throw failure("checkpoint exceeds the fixed family compute budget");
                    checkpoint = checkpointFromJson(event.path("checkpoint"));
                    pending = null;
                }
                case "RECOVERY_DEBIT", "ABORT" -> {
                    if (pending == null || !reservationMatches(event, pending)) throw failure("recovery debit does not match an open reservation");
                    long charged = "ABORT".equals(type)
                            ? event.path("charged_millis").asLong(-1) : pending.reservedMillis();
                    long observed = "ABORT".equals(type)
                            ? event.path("observed_elapsed_millis").asLong(-1) : pending.reservedMillis();
                    if (charged < pending.reservedMillis() || observed < 0 || charged < observed
                            || event.path("consumed_compute_millis").asLong(-1)
                                    != Math.addExact(consumed, charged)) {
                        throw failure("recovery debit/abort duration or cumulative compute is invalid");
                    }
                    consumed = Math.addExact(consumed, charged);
                    if ("RECOVERY_DEBIT".equals(type) && consumed > COMPUTE_BUDGET_MILLIS) {
                        throw failure("recovered reservation exceeds the fixed family compute budget");
                    }
                    pending = null;
                }
                default -> throw failure("unsupported budget event type: " + type);
            }
            if (index > 0 && event.path("consumed_compute_millis").asLong(-1) != consumed
                    && !"RESERVATION".equals(type)) {
                throw failure("event consumed duration does not reconcile at " + expectedName);
            }
            priorSha = declaredSha;
            state.events.add(event.deepCopy());
        }
        state.sequence = state.events.size(); state.consumed = consumed; state.headSha = priorSha;
        state.runStartSha = runStartSha; state.runBindingSha = runBindingSha; state.runBinding = runBinding;
        state.checkpoint = checkpoint; state.pending = pending;
        if (!state.events.isEmpty()) validateGenesisReceipt(scope, state.events.get(0));
        validateDurableHead(scope, state);
        return state;
    }

    private static boolean reservationMatches(JsonNode event, Reservation pending) {
        return pending.eventSha().equals(event.path("reservation_event_sha256").asText())
                && pending.segmentId().equals(event.path("segment_id").asText())
                && pending.runStartSha().equals(event.path("run_start_event_sha256").asText())
                && pending.bindingSha().equals(event.path("binding_sha256").asText())
                && pending.reservedMillis() == event.path("reserved_millis").asLong(-1);
    }

    private static void appendRecoveryDebit(FamilyScope scope, State state, String reason) throws IOException {
        Reservation pending = state.pending;
        if (pending == null) return;
        ObjectNode event = event(scope, state, "RECOVERY_DEBIT")
                .put("run_start_event_sha256", pending.runStartSha()).put("binding_sha256", pending.bindingSha())
                .put("segment_id", pending.segmentId()).put("reservation_event_sha256", pending.eventSha())
                .put("reserved_millis", pending.reservedMillis()).put("recovery_reason", reason)
                .put("consumed_compute_millis", Math.addExact(state.consumed, pending.reservedMillis()));
        append(scope, state, event);
    }

    private static String append(FamilyScope scope, State state, ObjectNode event) throws IOException {
        if (state.events.size() >= MAX_EVENTS) throw failure("budget event chain exceeds the supported bound");
        event.put("schema", EVENT_SCHEMA).put("sequence", state.events.size()).put("scope_sha256", scope.scopeSha256());
        if (state.headSha == null) event.putNull("previous_event_sha256");
        else event.put("previous_event_sha256", state.headSha);
        event.put("event_sha256", JsonHashes.ownHash(event, "event_sha256"));
        if ("GENESIS".equals(event.path("event_type").asText())) writeGenesisReceipt(scope, event);
        Path directory = scope.ledgerDirectory().resolve(EVENTS_DIRECTORY);
        Path target = directory.resolve(eventFileName(state.events.size()));
        writeImmutableEvent(directory, target, JsonHashes.canonicalBytes(event));
        writeDurableHead(scope, event.path("sequence").asInt(), event.path("event_sha256").asText());
        return event.path("event_sha256").asText();
    }

    private static void validateDurableHead(FamilyScope scope, State state) throws IOException {
        Path headPath = scope.ledgerDirectory().resolve(HEAD_FILE);
        boolean exists = Files.exists(headPath, LinkOption.NOFOLLOW_LINKS);
        if (state.events.isEmpty()) {
            if (exists) throw failure("budget chain head exists without event files; refusing reset");
            return;
        }
        int tailSequence = state.events.size() - 1;
        if (!exists) {
            if (state.events.size() == 1) {
                validateGenesisReceipt(scope, state.events.get(0));
                writeDurableHead(scope, 0, state.headSha);
                return;
            }
            throw failure("durable budget chain head is missing or unsafe; refusing truncation/reset");
        }
        if (Files.isSymbolicLink(headPath) || !Files.isRegularFile(headPath, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("durable budget chain head is missing or unsafe; refusing truncation/reset");
        }
        byte[] bytes = readBounded(headPath, MAX_RECEIPT_BYTES, "budget chain head");
        JsonNode parsed = JsonHashes.parse(bytes, "budget chain head");
        if (!(parsed instanceof ObjectNode head) || !java.util.Arrays.equals(bytes, JsonHashes.canonicalBytes(head))
                || !HEAD_SCHEMA.equals(head.path("schema").asText())
                || !scope.scopeSha256().equals(head.path("scope_sha256").asText())
                || !head.path("head_sha256").asText().equals(JsonHashes.ownHash(head, "head_sha256"))) {
            throw failure("durable budget chain high-water head is invalid");
        }
        int headedSequence = head.path("last_sequence").asInt(-1);
        String headedSha = head.path("last_event_sha256").asText();
        if (headedSequence == tailSequence && Objects.equals(headedSha, state.headSha)) return;
        if (headedSequence == tailSequence - 1 && headedSequence >= 0
                && Objects.equals(headedSha, state.events.get(headedSequence).path("event_sha256").asText())) {
            // The final immutable event was installed before a process died while advancing the
            // mutable high-water file. loadState has already validated the complete event chain,
            // so this exact one-event extension is safe to finish under the append lock.
            writeDurableHead(scope, tailSequence, state.headSha);
            return;
        }
        throw failure("durable budget chain high-water head does not match the immutable event tail");
    }

    private static void cleanupRecognizedPendingFiles(FamilyScope scope) throws IOException {
        Path ledger = scope.ledgerDirectory();
        cleanupPendingDirectory(ledger, "pending-head-");
        cleanupPendingDirectory(ledger.resolve(EVENTS_DIRECTORY), "pending-");
    }

    private static void cleanupPendingDirectory(Path directory, String prefix) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            int cleaned = 0;
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (!name.startsWith(prefix) || !name.endsWith(".tmp")) continue;
                String uuid = name.substring(prefix.length(), name.length() - ".tmp".length());
                try {
                    if (!UUID.fromString(uuid).toString().equals(uuid)) throw new IllegalArgumentException("not canonical UUID");
                } catch (IllegalArgumentException invalid) {
                    throw failure("unrecognized temporary file in budget custody path: " + name);
                }
                if (++cleaned > 64) throw failure("too many interrupted temporary writes in budget custody path");
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw failure("interrupted temporary write is unsafe: " + name);
                }
                Files.delete(file);
            }
        }
    }

    private static void writeGenesisReceipt(FamilyScope scope, ObjectNode genesis) throws IOException {
        ObjectNode receipt = JsonHashes.mapper().createObjectNode().put("schema", GENESIS_INIT_SCHEMA)
                .put("scope_sha256", scope.scopeSha256());
        receipt.set("genesis_event", genesis.deepCopy());
        receipt.put("genesis_event_sha256", genesis.path("event_sha256").asText());
        receipt.put("init_receipt_sha256", JsonHashes.ownHash(receipt, "init_receipt_sha256"));
        Path path = scope.ledgerDirectory().resolve(GENESIS_INIT_FILE);
        byte[] bytes = JsonHashes.canonicalBytes(receipt);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !java.util.Arrays.equals(bytes, readBounded(path, MAX_RECEIPT_BYTES, "budget genesis receipt"))) {
                throw failure("immutable budget genesis receipt differs from the planned genesis event");
            }
            return;
        }
        writeImmutableRootFile(scope.ledgerDirectory(), path, bytes);
    }

    private static ObjectNode validateGenesisReceipt(FamilyScope scope, ObjectNode committedGenesis) throws IOException {
        Path path = scope.ledgerDirectory().resolve(GENESIS_INIT_FILE);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("immutable budget genesis initialization receipt is missing or unsafe");
        }
        byte[] bytes = readBounded(path, MAX_RECEIPT_BYTES, "budget genesis receipt");
        JsonNode parsed = JsonHashes.parse(bytes, "budget genesis receipt");
        if (!(parsed instanceof ObjectNode receipt) || !java.util.Arrays.equals(bytes, JsonHashes.canonicalBytes(receipt))
                || !GENESIS_INIT_SCHEMA.equals(receipt.path("schema").asText())
                || !scope.scopeSha256().equals(receipt.path("scope_sha256").asText())
                || !receipt.path("init_receipt_sha256").asText().equals(JsonHashes.ownHash(receipt, "init_receipt_sha256"))) {
            throw failure("immutable budget genesis initialization receipt is invalid");
        }
        JsonNode declared = receipt.path("genesis_event");
        if (!(declared instanceof ObjectNode genesis)
                || !GENESIS_INIT_SCHEMA.equals(receipt.path("schema").asText())
                || !EVENT_SCHEMA.equals(genesis.path("schema").asText())
                || genesis.path("sequence").asInt(-1) != 0
                || !"GENESIS".equals(genesis.path("event_type").asText())
                || !genesis.path("previous_event_sha256").isNull()
                || !scope.scopeSha256().equals(genesis.path("scope_sha256").asText())
                || !JsonHashes.canonicalSha256(scope.toJson()).equals(JsonHashes.canonicalSha256(genesis.path("family_scope")))
                || genesis.path("consumed_compute_millis").asLong(-1) != 0
                || !receipt.path("genesis_event_sha256").asText().equals(genesis.path("event_sha256").asText())
                || !genesis.path("event_sha256").asText().equals(JsonHashes.ownHash(genesis, "event_sha256"))
                || (committedGenesis != null
                    && !JsonHashes.canonicalSha256(genesis).equals(JsonHashes.canonicalSha256(committedGenesis)))) {
            throw failure("immutable budget genesis receipt does not bind the exact chain genesis");
        }
        return genesis;
    }

    private static void recoverGenesisIntent(FamilyScope scope) throws IOException {
        Path eventPath = scope.ledgerDirectory().resolve(EVENTS_DIRECTORY).resolve(eventFileName(0));
        Path headPath = scope.ledgerDirectory().resolve(HEAD_FILE);
        if (Files.exists(eventPath, LinkOption.NOFOLLOW_LINKS)) return;
        Path receiptPath = scope.ledgerDirectory().resolve(GENESIS_INIT_FILE);
        if (!Files.exists(receiptPath, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.exists(headPath, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("durable budget head exists while genesis event is missing; refusing recovery");
        }
        ObjectNode genesis = validateGenesisReceipt(scope, null);
        writeImmutableEvent(scope.ledgerDirectory().resolve(EVENTS_DIRECTORY), eventPath,
                JsonHashes.canonicalBytes(genesis));
        writeDurableHead(scope, 0, genesis.path("event_sha256").asText());
    }

    private static void writeImmutableRootFile(Path directory, Path target, byte[] bytes) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw failure("immutable budget receipt already exists");
        Path temporary = directory.resolve("pending-head-" + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException error) {
                throw failure("budget custody root does not support atomic immutable receipt writes");
            }
            forceDirectory(directory);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] readBounded(Path path, int maxBytes, String label) throws IOException {
        long size = Files.size(path);
        if (size < 0 || size > maxBytes) throw failure(label + " exceeds the bounded custody-file size");
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > maxBytes) throw failure(label + " grew beyond the bounded custody-file size");
        return bytes;
    }

    private static void writeDurableHead(FamilyScope scope, int sequence, String eventSha) throws IOException {
        ObjectNode head = JsonHashes.mapper().createObjectNode().put("schema", HEAD_SCHEMA)
                .put("scope_sha256", scope.scopeSha256()).put("last_sequence", sequence)
                .put("last_event_sha256", eventSha);
        head.put("head_sha256", JsonHashes.ownHash(head, "head_sha256"));
        byte[] bytes = JsonHashes.canonicalBytes(head);
        Path directory = scope.ledgerDirectory(); Path target = directory.resolve(HEAD_FILE);
        if (Files.isSymbolicLink(target)) throw failure("durable budget chain head must not be a symlink");
        Path temporary = directory.resolve("pending-head-" + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) {
                throw failure("budget custody root does not support atomic chain-head replacement");
            }
            forceDirectory(directory);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeImmutableEvent(Path directory, Path target, byte[] bytes) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw failure("budget event already exists; stale predecessor refused");
        Path temporary = directory.resolve("pending-" + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException error) {
                throw failure("budget event store does not support atomic immutable writes");
            }
            forceDirectory(directory);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel directoryChannel = FileChannel.open(directory, StandardOpenOption.READ)) {
            directoryChannel.force(true);
        }
    }

    private static ObjectNode event(FamilyScope scope, State state, String type) {
        ObjectNode event = JsonHashes.mapper().createObjectNode().put("schema", EVENT_SCHEMA)
                .put("sequence", state.events.size()).put("event_type", type).put("scope_sha256", scope.scopeSha256());
        if (state.headSha == null) event.putNull("previous_event_sha256"); else event.put("previous_event_sha256", state.headSha);
        return event;
    }

    private static String eventFileName(int sequence) { return String.format(java.util.Locale.ROOT, "%08d.json", sequence); }

    private static ObjectNode object(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!(value instanceof ObjectNode object)) throw failure("budget event " + field + " must be an object");
        return object;
    }

    private static RunBinding bindingFromJson(ObjectNode row) {
        if (!row.hasNonNull("binding_sha256") || !row.path("binding_sha256").asText().equals(JsonHashes.ownHash(row, "binding_sha256"))) {
            throw failure("run binding self-hash is invalid");
        }
        return RunBinding.of(row.path("freeze_sha256").asText(), row.path("request_sha256").asText(),
                Path.of(row.path("project_root").asText()), Path.of(row.path("physical_root").asText()),
                row.path("manifest_sha256").asText(), row.path("executor_identity_sha256").asText(),
                Path.of(row.path("output_target").asText()), Path.of(row.path("checkpoint_target").asText()));
    }

    private static <T> T withLock(Path eventsDirectory, LockedAction<T> action) {
        Path normalized = eventsDirectory.toAbsolutePath().normalize();
        ReentrantLock local = JVM_LOCKS.computeIfAbsent(normalized, ignored -> new ReentrantLock());
        local.lock();
        try {
            Path lockPath = normalized.resolve(LOCK_FILE);
            if (Files.isSymbolicLink(lockPath)) throw failure("budget append lock must not be a symlink");
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                return action.run();
            } catch (IOException error) {
                throw failure("cannot read or append the family budget chain: " + error.getMessage());
            }
        } finally { local.unlock(); }
    }

    private static long elapsedMillisAt(long nowNanos, long startNanos) {
        long delta = nowNanos - startNanos;
        if (delta < 0) throw failure("monotonic compute clock moved backward or overflowed");
        long whole = delta / NANOS_PER_MILLI;
        return delta % NANOS_PER_MILLI == 0 ? whole : Math.addExact(whole, 1L);
    }

    private static Path canonicalExistingDirectory(Path path, String label) {
        Objects.requireNonNull(path, label);
        Path absolute = path.toAbsolutePath().normalize();
        ensureNoSymlink(absolute);
        try {
            Path real = absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) throw failure(label + " must be an existing directory");
            return real;
        } catch (IOException error) { throw failure(label + " cannot be reopened: " + error.getMessage()); }
    }

    private static Path canonicalTarget(Path path, String label) {
        Objects.requireNonNull(path, label);
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.getParent() == null) throw failure(label + " must name a file below a directory");
        ensureNoSymlink(absolute.getParent());
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(absolute) || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(label + " must not be a symlink or non-file");
            }
        }
        return absolute;
    }

    private static void requireHash(String value, String label) {
        if (!JsonHashes.isSha256(value)) throw failure(label + " must be a lowercase SHA-256");
    }

    private static IllegalArgumentException failure(String message) { return new IllegalArgumentException(message); }
}

package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.build.BuildIdentityService;
import com.tradinganalytics.infrastructure.repository.RepositoryLayout;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.PathConfinement;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Additive bounded execution profile and repetition runner for the successor
 * operating-characteristics evaluator.
 *
 * <p>The runner owns scheduling and artifact custody. It deliberately does not
 * own generation, lifecycle, matching, portfolio arithmetic, or statistics;
 * one worker calls the optimized repetition entry point for exactly one frozen
 * slot. The old serial commands and the old inline ledgers remain separate.</p>
 */
public final class StrategyOperatingCharacteristicsParallelV1 {
    public static final String PROFILE_SCHEMA =
            "strategy-evaluator-operating-characteristics-execution-profile/1";
    public static final String PLAN_SCHEMA =
            "strategy-evaluator-operating-characteristics-parallel-plan/1";
    public static final String PREFLIGHT_SCHEMA =
            "strategy-evaluator-operating-characteristics-parallel-preflight/1";
    public static final String LEDGER_SCHEMA =
            "strategy-evaluator-operating-characteristics-parallel-ledger/1";
    public static final String RESULT_SCHEMA =
            "strategy-evaluator-operating-characteristics-parallel-result/1";
    private static final String SLOT_RESULT_SCHEMA =
            "strategy-evaluator-operating-characteristics-parallel-slot-result/1";
    private static final String WORKER_FAILURE_SCHEMA =
            "strategy-evaluator-operating-characteristics-parallel-worker-failure/1";
    private static final String FIXED_EVALUATOR =
            "StrategyFixedBaselineV5+TradeLifecycleV5+StrategyResearchImprovementV1.disposition";
    /** Additive corrected worker identity; frozen V5 is never rewritten. */
    static final String CORRECTED_EVALUATOR =
            StrategyFixedBaselineCorrectedV1.EVALUATOR_ID;
    static final String CORRECTED_PLAN_SCHEMA =
            "strategy-evaluator-operating-characteristics-corrected-parallel-plan/1";
    /** Outer coordinator result; the fixed evaluator schema is reserved for raw worker results. */
    static final String CORRECTED_RESULT_SCHEMA =
            "strategy-evaluator-operating-characteristics-corrected-parallel-result/1";
    private static final String CORRECTED_SLOT_RESULT_SCHEMA =
            "strategy-evaluator-operating-characteristics-corrected-parallel-slot-result/1";
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final long DEFAULT_POLL_MILLIS = 250L;
    private static final long WORKER_CPU = 2L;
    private static final long OS_MEMORY_RESERVE = 6L * GIB;
    private static final int MAX_RETRY = 1;
    private static final String OPTIMIZED_METHOD = "evaluateOptimizedReplication";
    private static final Set<ProcessHandle> ACTIVE_PROCESSES = ConcurrentHashMap.newKeySet();
    private static final Pattern MAC_PLATFORM_UUID = Pattern.compile(
            "\\\"IOPlatformUUID\\\"\\s*=\\s*\\\"([0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})\\\"");

    private StrategyOperatingCharacteristicsParallelV1() { }

    /** A frozen work item. Ordering is part of the reproducibility contract. */
    public record Slot(String planSha256, String mode, String scenario, double effectSize,
                       int replication, long seed, int ordinal) {
        public Slot {
            requireSha(planSha256, "slot plan_sha256");
            if (mode == null || mode.isBlank() || scenario == null || scenario.isBlank()) {
                throw new IllegalArgumentException("slot mode and scenario are required");
            }
            if (replication < 0) throw new IllegalArgumentException("slot replication must be non-negative");
            if (!Double.isFinite(effectSize)) throw new IllegalArgumentException("slot effect_size must be finite");
            if (ordinal < 0) throw new IllegalArgumentException("slot ordinal must be non-negative");
        }

        public String effectKey() {
            return java.math.BigDecimal.valueOf(effectSize).stripTrailingZeros().toPlainString();
        }

        public String id() {
            return mode + "|" + scenario + "|" + effectKey() + "|" + replication + "|" + seed;
        }

        public ObjectNode toJson() {
            return JsonHashes.mapper().createObjectNode()
                    .put("slot_id", id()).put("ordinal", ordinal).put("plan_sha256", planSha256)
                    .put("mode", mode).put("scenario", scenario).put("effect_size", effectSize)
                    .put("replication", replication).put("seed", seed);
        }
    }

    /** The only execution seam used by scheduler tests and bounded callers. */
    @FunctionalInterface
    public interface SlotExecutor {
        ObjectNode evaluate(Slot slot, Path scratchDirectory) throws Exception;
    }

    /**
     * A typed resource observation used by the coordinator's admission and
     * monitoring checks.  This is package-private on purpose: tests can supply
     * deterministic observations without putting probe values in command JSON,
     * while public production entry points always use {@link #LIVE_RESOURCE_PROBE}.
     */
    record ResourceObservation(long availableProcessors, long totalMemoryBytes,
                               long availableFreeSpaceBytes, long coordinatorRssBytes,
                               long aggregateWorkerRssBytes) { }

    /**
     * Run-owned resource evidence.  Qualification must consume this object
     * from the completed result; command-line callers cannot provide measured
     * wall time, RSS, or disk values.  Every field that can be sampled is
     * fail-closed when a platform probe returns an unknown value.
     */
    private static final class RunMeasurement {
        private final long startedNanos = System.nanoTime();
        private final ObjectNode plan;
        private final ObjectNode profile;
        private final Path ledgerPath;
        private final String mode;
        private final int workers;
        private final String runId;
        private final Set<String> launchedSlots = ConcurrentHashMap.newKeySet();
        private final Set<String> completedSlots = ConcurrentHashMap.newKeySet();
        private long maxAggregateRssBytes = -1L;
        private long maxCoordinatorRssBytes = -1L;
        private long maxManagedDiskBytes = -1L;
        private boolean complete = true;

        private RunMeasurement(ObjectNode plan, ObjectNode profile, Path ledgerPath, String mode,
                int workers, String runId) {
            this.plan = plan;
            this.profile = profile;
            this.ledgerPath = ledgerPath;
            this.mode = mode;
            this.workers = workers;
            this.runId = runId;
        }

        private void recordCompletion(String slotId) {
            if (slotId != null && !slotId.isBlank()) completedSlots.add(slotId);
        }

        private void recordLaunch(String slotId) {
            if (slotId != null && !slotId.isBlank()) launchedSlots.add(slotId);
        }

        private void sample(ResourceProbe probe, Path artifactRoot, Path scratchRoot, Path logsRoot) {
            ResourceObservation observation;
            try {
                observation = probe.sample(ledgerPath);
            } catch (RuntimeException error) {
                complete = false;
                return;
            }
            long coordinator = observation == null ? -1L : observation.coordinatorRssBytes();
            long aggregateWorkers = observation == null ? -1L : observation.aggregateWorkerRssBytes();
            long disk = managedBytes(ledgerPath, artifactRoot, scratchRoot, logsRoot);
            if (coordinator < 0L || aggregateWorkers < 0L || disk < 0L) {
                complete = false;
                return;
            }
            long aggregate;
            try {
                aggregate = Math.addExact(coordinator, aggregateWorkers);
            } catch (ArithmeticException overflow) {
                complete = false;
                return;
            }
            maxCoordinatorRssBytes = Math.max(maxCoordinatorRssBytes, coordinator);
            maxAggregateRssBytes = Math.max(maxAggregateRssBytes, aggregate);
            maxManagedDiskBytes = Math.max(maxManagedDiskBytes, disk);
        }

        private ObjectNode toJson(ObjectNode ledger) {
            ObjectNode result = JsonHashes.mapper().createObjectNode()
                    .put("schema", "strategy-evaluator-resource-measurement/1")
                    .put("version", 1)
                    .put("plan_sha256", plan.path("content_sha256").asText())
                    .put("profile_sha256", profile.path("content_sha256").asText())
                    .put("ledger_content_sha256", ledger.path("content_sha256").asText())
                    .put("executor_identity_sha256", plan.path("executor_identity_sha256").asText())
                    .put("executor_source_sha256", plan.path("executor_build_input_fingerprint").asText())
                    .put("host_identity_sha256", measurementHostFingerprint(profile))
                    .put("run_id", runId)
                    .put("workers", workers)
                    .put("launched_slot_count", launchedSlots.size())
                    .put("completed_slot_count", completedSlots.size())
                    .put("fresh_full_wave", "FULL".equals(mode)
                            && launchedSlots.size() == 4 * profile.path("effective_workers").asInt(0)
                            && completedSlots.size() == launchedSlots.size())
                    .put("measured_wall_millis", Math.max(1L,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)))
                    .put("measured_max_aggregate_rss_bytes", maxAggregateRssBytes)
                    .put("measured_max_coordinator_rss_bytes", maxCoordinatorRssBytes)
                    .put("measured_max_disk_bytes", maxManagedDiskBytes)
                    .put("resource_probe_complete", complete && maxAggregateRssBytes >= 0L
                            && maxCoordinatorRssBytes >= 0L && maxManagedDiskBytes >= 0L)
                    .put("content_sha256", "");
            result.put("content_sha256", JsonHashes.ownHash(result));
            return result;
        }
    }

    /** Supplies one resource observation for the requested filesystem root. */
    @FunctionalInterface
    interface ResourceProbe {
        ResourceObservation sample(Path resourceRoot);
    }

    private static final ResourceProbe LIVE_RESOURCE_PROBE = resourceRoot ->
            new ResourceObservation(Runtime.getRuntime().availableProcessors(), detectedMemoryBytes(),
                    detectedFreeSpaceBytes(resourceRoot), coordinatorRssBytes(),
                    ProcessSlotExecutor.aggregateProcessRssBytes());

    /** Production executors return custody paths so the coordinator never retains raw rows. */
    private interface DurableSlotExecutor extends SlotExecutor {
        Path evaluateArtifact(Slot slot, Path scratchDirectory) throws Exception;

        @Override default ObjectNode evaluate(Slot slot, Path scratchDirectory) throws Exception {
            return (ObjectNode) readObject(evaluateArtifact(slot, scratchDirectory), "worker artifact").path("row").deepCopy();
        }
    }

    /** Computes the additive target profile and records local admission limits. */
    public static ObjectNode executionProfile(ObjectNode options) {
        return executionProfile(options, LIVE_RESOURCE_PROBE);
    }

    /** Package-private deterministic seam for scheduler tests. */
    static ObjectNode executionProfile(ObjectNode options, ResourceProbe resourceProbe) {
        Objects.requireNonNull(resourceProbe, "resourceProbe");
        ObjectNode input = options == null ? JsonHashes.mapper().createObjectNode() : options;
        ResourceObservation observation = Objects.requireNonNull(
                resourceProbe.sample(resourceRoot(input)), "resourceProbe observation");
        long cpus = observation.availableProcessors();
        long memory = observation.totalMemoryBytes();
        long free = observation.availableFreeSpaceBytes();
        int requested = integerOr(input, "requested_workers", 8);
        int targetWorkers = 8;
        int cpuAllowance = 24;
        long aggregate = 26L * GIB;
        long coordinator = 2L * GIB;
        long workerRss = 3L * GIB;
        long workerHeap = 2L * GIB;
        long disk = 128L * GIB;
        boolean targetQualified = cpus >= 28 && memory >= 32L * GIB;
        int localCap = integerOr(input, "local_worker_cap", 2);
        long usableMemory = memory > OS_MEMORY_RESERVE + coordinator
                ? memory - OS_MEMORY_RESERVE - coordinator : 0L;
        int memoryWorkers = workerRss > 0
                ? (int) Math.min(Integer.MAX_VALUE, usableMemory / workerRss) : 0;
        int cpuWorkers = cpus > 0 ? (int) Math.min(Integer.MAX_VALUE, cpus / WORKER_CPU) : 0;
        int effective = Math.max(0, Math.min(Math.min(Math.min(Math.min(requested, targetWorkers),
                Math.max(0, cpuAllowance / (int) WORKER_CPU)), memoryWorkers), cpuWorkers));
        if (!targetQualified) effective = Math.min(effective, Math.max(0, localCap));
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", PROFILE_SCHEMA).put("version", 1)
                .put("status", effective > 0 ? "ADMISSIBLE" : "BLOCKED_RESOURCE")
                .put("target_cpu_ceiling", 28).put("target_memory_bytes", 32L * GIB)
                .put("target_wall_minutes", 48L * 60L)
                .put("max_wall_minutes", 48L * 60L)
                .put("max_aggregate_rss_bytes", aggregate)
                .put("coordinator_rss_reservation_bytes", coordinator)
                .put("worker_rss_bytes", workerRss).put("worker_heap_bytes", workerHeap)
                .put("worker_cpu", WORKER_CPU)
                .put("max_workers", targetWorkers).put("max_cpu", cpuAllowance)
                .put("max_disk_bytes", disk).put("requested_workers", requested)
                .put("available_cpus", Math.max(0L, cpus)).put("available_memory_bytes", Math.max(0L, memory))
                .put("available_free_space_bytes", Math.max(0L, free))
                .put("target_qualified", targetQualified)
                .put("local_conservative_cap", localCap)
                .put("effective_workers", effective)
                .put("resource_probe_complete", cpus > 0 && memory > 0 && free > 0)
                .put("qualified_for_confirmation", false)
                .put("claim_scope", "DEVELOPMENT_EXECUTION_PROFILE_ONLY")
                .put("same_packaged_executor_required", true)
                .put("fixed_process_pool", true).put("bounded_submission_queue", true)
                .put("retry_limit_per_slot", MAX_RETRY)
                .put("content_sha256", "");
        result.put("profile_envelope_sha256", profileEnvelopeHash(result));
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    /**
     * Validates an additive plan and returns a machine-readable resource gate.
     * This command never runs the evaluator and never claims measured speedup.
     */
    public static ObjectNode preflight(ObjectNode options) {
        ObjectNode input = options == null ? JsonHashes.mapper().createObjectNode() : options;
        ObjectNode plan = readObjectOption(input, "plan", "parallel plan");
        validatePlan(plan);
        boolean corrected = CORRECTED_PLAN_SCHEMA.equals(plan.path("schema").asText());
        String requestedMode = mode(input, plan);
        if (!input.path("internal_test_seam").asBoolean(false)) {
            if ("FULL".equals(requestedMode)) validateFullGeometry(plan);
            else validatePrefixGeometry(plan);
            requireStatisticalBinding(plan, requestedMode);
        }
        ObjectNode profile = input.has("profile")
                ? readObjectOption(input, "profile", "execution profile") : executionProfile(input);
        validateProfile(profile);
        if (!input.path("internal_test_seam").asBoolean(false)) requireProfileBinding(plan, profile);
        if (!input.path("internal_test_seam").asBoolean(false) && "FULL".equals(requestedMode)
                && profile.path("qualified_for_confirmation").asBoolean(false)) {
            validateQualificationAgainstPlan(profile, plan);
        }
        List<Slot> slots = slots(plan, requestedMode);
        long required = longOr(profile, "max_disk_bytes", 128L * GIB);
        long free = longOr(profile, "available_free_space_bytes", -1L);
        boolean diskPass = free > 0 && required <= free;
        boolean developmentWave = corrected && plan.path("development_wave").asBoolean(false);
        boolean fullReady = developmentWave
                ? profile.path("target_qualified").asBoolean(false)
                : profile.path("target_qualified").asBoolean(false)
                        && profile.path("qualified_for_confirmation").asBoolean(false);
        boolean pass = profile.path("effective_workers").asInt(0) > 0 && diskPass
                && profile.path("resource_probe_complete").asBoolean(false)
                && (!"FULL".equals(mode(input, plan)) || fullReady);
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", PREFLIGHT_SCHEMA).put("version", 1)
                .put("status", pass ? "READY_PRE_OUTCOME" : "BLOCKED_RESOURCE")
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("profile_sha256", profile.path("content_sha256").asText())
                .put("fixed_evaluator", corrected ? CORRECTED_EVALUATOR : FIXED_EVALUATOR).put("shared_physical_evaluator", true)
                .put("toy_statistic", false).put("outcomes_opened", false)
                .put("promotion_eligible", false).put("activation_authorized", false)
                .put("mode", requestedMode).put("planned_slots", slots.size())
                .put("disk_headroom_pass", diskPass).put("resource_probe_complete", profile.path("resource_probe_complete").asBoolean(false))
                .put("measured", false).put("performance_claim", "NONE")
                .put("full_confirmation_gate", "REQUIRES_APPLICABLE_RESOURCE_QUALIFICATION");
        if (corrected) result.put("accounting_version", StrategyFixedBaselinePortfolioCorrectionV1.ACCOUNTING_VERSION)
                .put("development_wave", developmentWave);
        result.set("profile", profile.deepCopy());
        result.set("build_identity", BuildIdentityService.describe(StrategyOperatingCharacteristicsParallelV1.class));
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    /** Runs using the packaged worker-process adapter. */
    public static ObjectNode run(ObjectNode options) {
        return run(options, options != null && options.path("corrected_accounting").asBoolean(false));
    }

    /** Additive corrected coordinator route selected only by the versioned wrapper. */
    static ObjectNode runCorrected(ObjectNode options) {
        ObjectNode input = options == null ? JsonHashes.mapper().createObjectNode() : options.deepCopy();
        input.put("corrected_accounting", true);
        return run(input, true);
    }

    private static ObjectNode run(ObjectNode options, boolean corrected) {
        ObjectNode input = options == null ? JsonHashes.mapper().createObjectNode() : options;
        if (input.path("test_probe_override").asBoolean(false)) {
            throw new IllegalArgumentException("production parallel run rejects test_probe_override");
        }
        ObjectNode plan = readObjectOption(input, "plan", "parallel plan");
        String requestedMode = mode(input, plan);
        validatePackagedPlan(plan, requestedMode, corrected);
        validateBoundInputs(input, plan, true);
        ObjectNode baseline = readObjectOption(input, "baseline", "baseline");
        ObjectNode controls = readObjectOption(input, "controls", "controls");
        ObjectNode experiment = readObjectOption(input, "experiment", "experiment");
        ObjectNode profile = input.has("profile")
                ? readObjectOption(input, "profile", "execution profile") : executionProfile(input);
        if (!input.has("profile") && corrected) {
            profile = StrategyOperatingCharacteristicsCorrectedParallelV1.executionProfile(input);
        }
        validateProfile(profile);
        requireProfileBinding(plan, profile);
        if ("FULL".equals(requestedMode) && profile.path("qualified_for_confirmation").asBoolean(false)) {
            validateQualificationAgainstPlan(profile, plan);
        }
        validateLiveProfile(profile, input, requestedMode, LIVE_RESOURCE_PROBE);
        ObjectNode frozen = input.deepCopy();
        frozen.set("plan", plan.deepCopy());
        frozen.set("baseline", baseline.deepCopy());
        frozen.set("controls", controls.deepCopy());
        frozen.set("experiment", experiment.deepCopy());
        frozen.set("profile", profile.deepCopy());
        return runWithExecutorInternal(frozen,
                new ProcessSlotExecutor(frozen, plan, baseline, controls, experiment, profile, corrected,
                        corrected ? frozen.path("run_id").asText("") : null),
                LIVE_RESOURCE_PROBE, corrected);
    }

    /**
     * Runs the durable scheduler against a supplied slot executor. Tests use a
     * bounded fake here; production uses {@link #run(ObjectNode)} and therefore
     * never shares mutable evaluator state between repetitions.
     */
    public static ObjectNode runWithExecutor(ObjectNode options, SlotExecutor executor) {
        return runWithExecutor(options, executor, LIVE_RESOURCE_PROBE);
    }

    /** Package-private deterministic seam for scheduler tests. */
    static ObjectNode runWithExecutor(ObjectNode options, SlotExecutor executor, ResourceProbe resourceProbe) {
        return runWithExecutorInternal(options, executor, resourceProbe, false);
    }

    /** Package-private corrected scheduler seam for self-contained integration tests. */
    static ObjectNode runCorrectedWithExecutorForTest(ObjectNode options, SlotExecutor executor,
            ResourceProbe resourceProbe) {
        return runWithExecutorInternal(options, executor, resourceProbe, true);
    }

    private static ObjectNode runWithExecutorInternal(ObjectNode options, SlotExecutor executor,
            ResourceProbe resourceProbe, boolean corrected) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(resourceProbe, "resourceProbe");
        ObjectNode input = options == null ? JsonHashes.mapper().createObjectNode() : options;
        ObjectNode plan = readObjectOption(input, "plan", "parallel plan");
        validatePlan(plan);
        if (input.has("baseline") && input.has("controls") && input.has("experiment")) validateBoundInputs(input, plan, false);
        String mode = mode(input, plan);
        List<Slot> planned = slots(plan, mode);
        Path ledgerPath = requiredPath(input, "ledger").toAbsolutePath().normalize();
        Path ledgerParent = ledgerPath.getParent();
        try {
            if (ledgerParent != null) Files.createDirectories(ledgerParent);
        } catch (IOException error) {
            throw new IllegalArgumentException("parallel runner cannot create ledger directory", error);
        }
        try (FileChannel channel = FileChannel.open(ledgerPath.resolveSibling(ledgerPath.getFileName() + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
            ObjectNode existingLedger = Files.exists(ledgerPath) ? readObject(ledgerPath, "parallel ledger") : null;
            ObjectNode profile = input.has("profile")
                    ? readObjectOption(input, "profile", "execution profile")
                    : existingLedger != null && existingLedger.path("execution_profile").isObject()
                            ? (ObjectNode) existingLedger.path("execution_profile").deepCopy()
                            : executionProfile(input, resourceProbe);
            validateProfile(profile);
            if (profile.path("effective_workers").asInt(0) <= 0) {
                throw new IllegalArgumentException("parallel run has no admitted workers");
            }
            ObjectNode gateOptions = input.deepCopy();
            gateOptions.set("plan", plan.deepCopy()); gateOptions.set("profile", profile.deepCopy());
            gateOptions.put("internal_test_seam", !(executor instanceof DurableSlotExecutor));
            ObjectNode gate = preflight(gateOptions);
            if (!"READY_PRE_OUTCOME".equals(gate.path("status").asText())) {
                throw new IllegalArgumentException("parallel run is blocked by execution preflight: "
                        + gate.path("status").asText());
            }
            ObjectNode ledger = existingLedger != null ? existingLedger : newLedger(plan, profile, mode, planned);
            boolean packagedStrict = executor instanceof DurableSlotExecutor;
            validateLedger(ledger, plan, profile, mode, planned, ledgerPath, packagedStrict);
            if (corrected && executor instanceof ProcessSlotExecutor processExecutor) {
                processExecutor.bindRunId(ledger.path("run_id").asText(""));
            }
            Path root = ledgerPath.getParent() == null ? Path.of(".") : ledgerPath.getParent();
            Path artifactRoot = root.resolve(ledgerPath.getFileName() + ".results");
            Path scratchRoot = root.resolve(ledgerPath.getFileName() + ".scratch");
            Path logsRoot = root.resolve(ledgerPath.getFileName() + ".logs");
            Files.createDirectories(artifactRoot); Files.createDirectories(scratchRoot); Files.createDirectories(logsRoot);

            long declaredWallMillis = Math.max(1L, longOr(profile, "max_wall_minutes", 48L * 60L) * 60_000L);
            long wallMillis = longOr(input, "max_wall_millis", declaredWallMillis);
            if (wallMillis <= 0 || wallMillis > declaredWallMillis) {
                throw new IllegalArgumentException("max_wall_millis cannot extend the frozen execution profile");
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(wallMillis);
            int workers = profile.path("effective_workers").asInt(0);
            if (workers <= 0) throw new IllegalArgumentException("parallel run has no admitted workers");
            int requestedWorkers = integerOr(input, "workers", workers);
            if (requestedWorkers <= 0 || requestedWorkers > workers) {
                throw new IllegalArgumentException("workers cannot exceed the frozen effective worker admission");
            }
            workers = requestedWorkers;
            RunMeasurement measurement = corrected
                    ? new RunMeasurement(plan, profile, ledgerPath, mode, workers,
                            ledger.path("run_id").asText("")) : null;
            if (corrected && (measurement.runId == null || measurement.runId.isBlank())) {
                throw new IllegalArgumentException("corrected ledger has no immutable run identity");
            }
            AtomicBoolean stop = new AtomicBoolean(false);
            String stopReason = "";
            Map<String, ObjectNode> terminal = terminalBySlot(ledger);
            Map<String, Integer> retries = attemptCounts(ledger);
            ArrayList<Slot> pending = new ArrayList<>();
            for (Slot slot : planned) if (!terminal.containsKey(slot.id())) {
                Path existingArtifact = artifactRoot.resolve(safe(slot.id()) + ".json");
                if (Files.exists(existingArtifact)) {
                    ObjectNode artifact = readObject(existingArtifact, "orphan slot artifact");
                    verifyArtifact(artifact, existingArtifact, slot, plan, packagedStrict,
                            corrected ? ledger.path("run_id").asText("") : null);
                    ObjectNode reference = artifactReference(ledgerPath, existingArtifact, artifact, slot,
                            artifact.path("attempt").asInt(0));
                    String status = artifact.path("status").asText();
                    recordAttempt(ledger, slot, artifact.path("attempt").asInt(0), status, "", reference);
                    ledger.withArray("terminal").add(reference);
                    terminal.put(slot.id(), reference);
                    continue;
                }
                if (retries.getOrDefault(slot.id(), 0) >= MAX_RETRY + 1) {
                    ledger.withArray("terminal").add(JsonHashes.mapper().createObjectNode().put("slot_id", slot.id())
                            .put("status", "INCOMPLETE").put("attempt", retries.get(slot.id()))
                            .put("error", "RETRY_LIMIT_EXHAUSTED"));
                } else pending.add(slot);
            }
            persistLedger(ledgerPath, ledger);
            ArrayDeque<Slot> queue = new ArrayDeque<>(pending);
            ExecutorService pool = Executors.newFixedThreadPool(workers, runnable -> {
                Thread thread = new Thread(runnable, "strategy-oc-parallel-worker");
                thread.setDaemon(true); return thread;
            });
            CompletionService<AttemptOutcome> completions = new ExecutorCompletionService<>(pool);
            Map<Future<AttemptOutcome>, Slot> active = new LinkedHashMap<>();
            try {
                while ((!queue.isEmpty() || !active.isEmpty()) && !stop.get()) {
                    String violation = monitorWithMeasurement(input, profile, deadline, ledgerPath, artifactRoot,
                            scratchRoot, logsRoot, resourceProbe, measurement);
                    if (violation != null) { stop.set(true); stopReason = violation; break; }
                    while (!queue.isEmpty() && active.size() < workers && !stop.get()) {
                        Slot slot = queue.removeFirst();
                        int attempt = retries.getOrDefault(slot.id(), 0) + 1;
                        reserve(ledger, slot, attempt, ledgerPath);
                        retries.put(slot.id(), attempt);
                        Future<AttemptOutcome> future = completions.submit(() -> executeAttempt(
                                executor, slot, attempt, scratchRoot, artifactRoot, logsRoot, corrected,
                                corrected ? ledger.path("run_id").asText("") : null));
                        if (measurement != null) measurement.recordLaunch(slot.id());
                        active.put(future, slot);
                    }
                    if (active.isEmpty()) continue;
                    Future<AttemptOutcome> completed = completions.poll(DEFAULT_POLL_MILLIS, TimeUnit.MILLISECONDS);
                    if (completed == null) continue;
                    Slot slot = active.remove(completed);
                    AttemptOutcome outcome;
                    try { outcome = completed.get(); }
                    catch (ExecutionException error) {
                        String message = cause(error);
                        outcome = isResourceMessage(error.getCause()) || message.startsWith("CANCELLED")
                                ? AttemptOutcome.resource(slot, retries.get(slot.id()), message)
                                : AttemptOutcome.failed(slot, retries.get(slot.id()), message);
                    }
                    if (outcome.resourceViolation() != null) {
                        stop.set(true); stopReason = outcome.resourceViolation();
                        recordAttempt(ledger, slot, outcome.attempt(), "ABORTED", outcome.resourceViolation(), null);
                        persistLedger(ledgerPath, ledger);
                    } else if (outcome.retryableFailure() && retries.get(slot.id()) <= MAX_RETRY) {
                        recordAttempt(ledger, slot, retries.get(slot.id()), "FAILED", outcome.error(), null);
                        queue.addFirst(slot);
                        persistLedger(ledgerPath, ledger);
                    } else {
                        try {
                            finishAttempt(ledger, slot, outcome, ledgerPath, artifactRoot, scratchRoot, logsRoot,
                                    profile, deadline, plan, packagedStrict, input, resourceProbe,
                                    corrected ? ledger.path("run_id").asText("") : null);
                            if (measurement != null && outcome.artifact() != null) {
                                measurement.recordCompletion(slot.id());
                            }
                        } catch (ResourceViolation resource) {
                            stop.set(true); stopReason = resource.getMessage();
                            recordAttempt(ledger, slot, outcome.attempt(), "ABORTED", stopReason, null);
                            persistLedger(ledgerPath, ledger);
                        }
                    }
                    if (stop.get()) break;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); stop.set(true); stopReason = "CANCELLED";
            } finally {
                if (stop.get()) for (Map.Entry<Future<AttemptOutcome>, Slot> entry : active.entrySet()) {
                    entry.getKey().cancel(true);
                    recordAttempt(ledger, entry.getValue(), retries.getOrDefault(entry.getValue().id(), 1),
                            "ABORTED", stopReason, null);
                }
                pool.shutdownNow();
                try { pool.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (stop.get()) {
                for (Slot slot : queue) recordAttempt(ledger, slot, 0, "UNLAUNCHED", stopReason, null);
                persistLedger(ledgerPath, ledger);
            }
            if (!stop.get()) {
                String finalViolation = monitorWithMeasurement(input, profile, deadline, ledgerPath, artifactRoot,
                        scratchRoot, logsRoot, resourceProbe, measurement);
                if (finalViolation != null) { stop.set(true); stopReason = finalViolation; }
            }
            String finalStageViolation = monitorWithMeasurement(input, profile, deadline, ledgerPath, artifactRoot,
                    scratchRoot, logsRoot, resourceProbe, measurement);
            if (finalStageViolation != null) {
                stop.set(true);
                stopReason = finalStageViolation;
            }
            if (measurement != null) {
                measurement.sample(resourceProbe, artifactRoot, scratchRoot, logsRoot);
            }
            ObjectNode result = resultFromLedger(plan, profile, mode, planned, ledger, ledgerPath, stopReason, deadline,
                    packagedStrict, resourceProbe, corrected, measurement, workers);
            String out = input.path("out").asText("");
            if (!out.isBlank()) writeAtomic(Path.of(out).toAbsolutePath().normalize(), result, true);
            return result;
        } catch (IOException error) {
            throw new IllegalArgumentException("parallel runner cannot acquire or write its ledger", error);
        }
    }

    /** Internal process-boundary worker command. */
    public static ObjectNode worker(ObjectNode options) {
        Path payloadPath = requiredPath(options, "payload");
        ObjectNode payload = readObject(payloadPath, "worker payload");
        ObjectNode plan = readObjectOption(payload, "plan", "worker plan");
        boolean corrected = payload.path("corrected_accounting").asBoolean(false);
        boolean planCorrected = CORRECTED_PLAN_SCHEMA
                .equals(plan.path("schema").asText());
        if (corrected != planCorrected) {
            throw new IllegalArgumentException("parallel worker corrected mode does not match the versioned plan");
        }
        String runId = payload.path("run_id").asText("");
        if (corrected && !runId.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("corrected worker has no coordinator run identity");
        }
        ObjectNode identity = BuildIdentityService.describe(StrategyOperatingCharacteristicsParallelV1.class);
        String expected = payload.path("executor_identity_sha256").asText("");
        String actual = identity.path("executable").path("sha256").asText("");
        if (!expected.isBlank() && (!SHA256.matcher(expected).matches() || !expected.equals(actual))) {
            throw new IllegalArgumentException("worker packaged executor identity mismatch");
        }
        String expectedSource = payload.path("executor_source_fingerprint").asText("");
        String actualSource = identity.path("compiled").path("input_fingerprint").asText("");
        if (!expectedSource.isBlank() && (!expectedSource.equals(actualSource))) {
            throw new IllegalArgumentException("worker packaged source identity mismatch");
        }
        String expectedProfile = payload.path("profile_sha256").asText("");
        if (!SHA256.matcher(expectedProfile).matches()
                || !expectedProfile.equals(plan.path("execution_profile_sha256").asText(""))) {
            throw new IllegalArgumentException("worker profile does not match frozen execution plan");
        }
        Slot slot = slotFrom(payload.path("slot"));
        ObjectNode baseline = readObjectOption(payload, "baseline", "worker baseline");
        ObjectNode controls = readObjectOption(payload, "controls", "worker controls");
        ObjectNode experiment = readObjectOption(payload, "experiment", "worker experiment");
        validateBoundInputs(payload, plan, true);
        validatePackagedPlan(plan, slot.mode(), corrected);
        verifyWorkerDependencies(payload);
        int episodes = payload.path("episodes_per_replication").asInt(-1);
        int boundEpisodes = episodesForMode(plan, slot.mode());
        if (episodes != boundEpisodes) {
            throw new IllegalArgumentException("worker episode geometry differs from the frozen plan");
        }
        long workerRss = payload.path("worker_rss_bytes").asLong(-1L);
        long workerHeap = payload.path("worker_heap_bytes").asLong(-1L);
        int workerCpu = payload.path("worker_cpu").asInt(-1);
        long maxWallMillis = payload.path("max_wall_millis").asLong(-1L);
        if (workerRss != 3L * GIB || workerHeap != 2L * GIB || workerCpu != WORKER_CPU
                || maxWallMillis <= 0 || maxWallMillis > 48L * 60L * 60L * 1000L) {
            throw new IllegalArgumentException("worker resource envelope differs from the frozen execution profile");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWallMillis);
        long maxRss = workerRss;
        Path output = requiredPath(options, "out");
        ObjectNode row;
        try {
            row = invokeOptimized(baseline, controls, experiment, plan, slot, episodes, deadline, maxRss, corrected);
            validateRow(slot, row);
        } catch (RuntimeException error) {
            if (!isResourceMessage(error)) throw error;
            ObjectNode failure = workerFailure(slot, plan, identity, payload, error.getMessage());
            try {
                writeAtomic(output.toAbsolutePath().normalize(), failure, false);
            } catch (IOException writeError) {
                throw new ResourceViolation(error.getMessage(), writeError);
            }
            throw new ResourceViolation(error.getMessage());
        }
        ObjectNode artifact = slotArtifact(slot, payload.path("attempt").asInt(1), row, identity, corrected, runId);
        try {
            writeAtomic(output.toAbsolutePath().normalize(), artifact, false);
        } catch (IOException error) {
            throw new IllegalArgumentException("worker cannot publish temporary artifact", error);
        }
        return JsonHashes.mapper().createObjectNode().put("schema", SLOT_RESULT_SCHEMA)
                .put("status", "PUBLISHED_TEMPORARY").put("slot_id", slot.id())
                .put("artifact", output.toAbsolutePath().normalize().toString())
                .put("content_sha256", artifact.path("content_sha256").asText());
    }

    private static ObjectNode invokeOptimized(ObjectNode baseline, ObjectNode controls, ObjectNode experiment,
            ObjectNode plan, Slot slot, int episodes, long deadline, long maxRss, boolean corrected) {
        return corrected ? StrategyOperatingCharacteristicsSuccessorV1.evaluateCorrectedReplication(
                baseline, controls, experiment, plan, slot.mode(), slot.scenario(),
                slot.effectSize(), slot.replication(), slot.seed(), episodes, deadline, maxRss)
                : StrategyOperatingCharacteristicsSuccessorV1.evaluateOptimizedReplication(
                        baseline, controls, experiment, plan, slot.mode(), slot.scenario(),
                        slot.effectSize(), slot.replication(), slot.seed(), episodes, deadline, maxRss);
    }

    private static void verifyWorkerDependencies(ObjectNode payload) {
        Path root = RepositoryLayout.locate();
        JsonNode receipts = payload.path("supporting_receipts");
        if (!receipts.isArray() || receipts.size() != 2) {
            throw new IllegalArgumentException("worker supporting dependency receipts are incomplete");
        }
        JsonNode dependencies = payload.path("supporting_dependencies");
        if (!dependencies.isArray() || dependencies.size() != receipts.size()) {
            throw new IllegalArgumentException("worker supporting dependency payload is incomplete");
        }
        for (JsonNode node : receipts) {
            String relative = node.path("relative_path").asText("");
            JsonNode dependency = null;
            for (JsonNode candidate : dependencies) {
                if (relative.equals(candidate.path("relative_path").asText())) {
                    dependency = candidate;
                    break;
                }
            }
            if (dependency == null) throw new IllegalArgumentException("worker dependency payload lacks " + relative);
            byte[] captured;
            try {
                captured = Base64.getDecoder().decode(dependency.path("data_base64").asText(""));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("worker dependency payload is not base64", error);
            }
            if (!JsonHashes.sha256(captured).equals(node.path("byte_sha256").asText())
                    || captured.length != node.path("bytes").asLong(-1)) {
                throw new IllegalArgumentException("worker supporting dependency payload receipt mismatch");
            }
            Path path = PathConfinement.resolve(root, relative, "worker supporting dependency",
                    PathConfinement.ExpectedType.FILE).absolute();
            byte[] bytes = PathConfinement.readSinglyLinkedFile(path, "worker supporting dependency");
            if (!JsonHashes.sha256(bytes).equals(node.path("byte_sha256").asText())
                    || bytes.length != node.path("bytes").asLong(-1)) {
                throw new IllegalArgumentException("worker supporting dependency receipt mismatch");
            }
            String content = node.path("content_sha256").asText("");
            if (!content.isBlank()) {
                try {
                    JsonNode value = JsonHashes.mapper().readTree(bytes);
                    if (!value.isObject() || !content.equals(JsonHashes.ownHash(value))) {
                        throw new IllegalArgumentException("worker supporting dependency content receipt mismatch");
                    }
                } catch (IOException error) {
                    throw new IllegalArgumentException("worker supporting dependency is not valid JSON", error);
                }
            }
        }
    }

    private static AttemptOutcome executeAttempt(SlotExecutor executor, Slot slot, int attempt,
            Path scratchRoot, Path artifactRoot, Path logsRoot, boolean corrected, String runId) {
        Path scratch = scratchRoot.resolve(safe(slot.id()) + "-a" + attempt);
        Path log = logsRoot.resolve(safe(slot.id()) + "-a" + attempt + ".log");
        try {
            Files.createDirectories(scratch);
            Path target = artifactRoot.resolve(safe(slot.id()) + ".json");
            if (Files.exists(target)) {
                return AttemptOutcome.complete(slot, attempt, target);
            }
            if (executor instanceof DurableSlotExecutor durable) {
                Path workerArtifact = durable.evaluateArtifact(slot, scratch);
                try {
                    Files.move(workerArtifact, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(workerArtifact, target);
                } catch (java.nio.file.FileAlreadyExistsException race) {
                    Files.deleteIfExists(workerArtifact);
                }
                return AttemptOutcome.complete(slot, attempt, target);
            }
            ObjectNode row = executor.evaluate(slot, scratch);
            if (row == null) throw new IllegalArgumentException("worker returned null row");
            validateRow(slot, row);
            ObjectNode identity = BuildIdentityService.describe(StrategyOperatingCharacteristicsParallelV1.class);
            ObjectNode artifact = slotArtifact(slot, attempt, row, identity, corrected, runId);
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + System.nanoTime());
            writeAtomic(temporary, artifact, false);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.FileAlreadyExistsException race) {
                Files.deleteIfExists(temporary);
                return AttemptOutcome.complete(slot, attempt, target);
            }
            Files.writeString(log, "COMPLETE " + slot.id() + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return AttemptOutcome.complete(slot, attempt, target);
        } catch (Exception error) {
            try { Files.writeString(log, error.toString() + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND); } catch (IOException ignored) { }
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            return isResourceMessage(error) || message.startsWith("CANCELLED")
                    ? AttemptOutcome.resource(slot, attempt, message)
                    : AttemptOutcome.failed(slot, attempt, message);
        }
    }

    private static ObjectNode slotArtifact(Slot slot, int attempt, ObjectNode row, ObjectNode identity) {
        return slotArtifact(slot, attempt, row, identity, false);
    }

    private static ObjectNode slotArtifact(Slot slot, int attempt, ObjectNode row, ObjectNode identity,
            boolean corrected) {
        return slotArtifact(slot, attempt, row, identity, corrected, null);
    }

    private static ObjectNode slotArtifact(Slot slot, int attempt, ObjectNode row, ObjectNode identity,
            boolean corrected, String runId) {
        String transportStatus = "COMPLETE".equals(row.path("status").asText()) ? "COMPLETE" : "COMPUTE_INCOMPLETE";
        ObjectNode artifact = JsonHashes.mapper().createObjectNode()
                .put("schema", corrected ? CORRECTED_SLOT_RESULT_SCHEMA : SLOT_RESULT_SCHEMA).put("version", 1)
                .put("status", transportStatus)
                .put("slot_id", slot.id()).put("plan_sha256", slot.planSha256()).put("mode", slot.mode())
                .put("scenario", slot.scenario()).put("effect_size", slot.effectSize())
                .put("replication", slot.replication()).put("seed", slot.seed()).put("attempt", attempt)
                .put("fixed_evaluator", corrected ? CORRECTED_EVALUATOR : FIXED_EVALUATOR)
                .put("promotion_eligible", false)
                .put("activation_authorized", false).put("content_sha256", "");
        artifact.set("row", row.deepCopy());
        if (corrected) {
            artifact.put("accounting_version", StrategyFixedBaselinePortfolioCorrectionV1.ACCOUNTING_VERSION);
            if (runId != null && !runId.isBlank()) artifact.put("run_id", runId);
        }
        JsonNode raw = row.path("raw_evaluator_result");
        if ("COMPLETE".equals(transportStatus) && raw.isObject()) {
            artifact.put("portable_economic_sha256", portableEconomicSha256((ObjectNode) raw));
        }
        artifact.set("executor_identity", identity.deepCopy());
        artifact.put("content_sha256", JsonHashes.ownHash(artifact));
        return artifact;
    }

    private static void finishAttempt(ObjectNode ledger, Slot slot, AttemptOutcome outcome,
            Path ledgerPath, Path artifactRoot, Path scratchRoot, Path logsRoot, ObjectNode profile, long deadline,
            ObjectNode plan, boolean packagedStrict, ObjectNode options, ResourceProbe resourceProbe,
            String runId) throws IOException {
        checkBudget(options, profile, deadline, ledgerPath, artifactRoot, scratchRoot, logsRoot, resourceProbe);
        if (outcome.artifact() != null) {
            ObjectNode artifact = readObject(outcome.artifact(), "slot artifact");
            verifyArtifact(artifact, outcome.artifact(), slot, plan, packagedStrict, runId);
            ObjectNode reference = artifactReference(ledgerPath, outcome.artifact(), artifact, slot, outcome.attempt());
            recordAttempt(ledger, slot, outcome.attempt(), reference.path("status").asText(), "", reference);
            ledger.withArray("terminal").add(reference);
        } else {
            recordAttempt(ledger, slot, outcome.attempt(), "FAILED", outcome.error(), null);
            ObjectNode terminal = JsonHashes.mapper().createObjectNode().put("slot_id", slot.id())
                    .put("status", "FAILED").put("attempt", outcome.attempt()).put("error", outcome.error());
            ledger.withArray("terminal").add(terminal);
        }
        checkBudget(options, profile, deadline, ledgerPath, artifactRoot, scratchRoot, logsRoot, resourceProbe);
        persistLedger(ledgerPath, ledger);
    }

    private static void checkBudget(ObjectNode options, ObjectNode profile, long deadline, Path ledgerPath,
            Path artifactRoot, Path scratchRoot, Path logsRoot, ResourceProbe resourceProbe) {
        String violation = monitor(options, profile, deadline, ledgerPath, artifactRoot, scratchRoot, logsRoot,
                resourceProbe);
        if (violation != null) throw new ResourceViolation(violation);
    }

    private static ObjectNode artifactReference(Path ledgerPath, Path artifact, ObjectNode value, Slot slot,
            int attempt)
            throws IOException {
        return JsonHashes.mapper().createObjectNode().put("slot_id", slot.id())
                .put("status", value.path("status").asText("COMPLETE"))
                .put("attempt", attempt)
                .put("relative_path", ledgerPath.getParent() == null
                        ? ledgerPath.getFileName() + ".results/" + artifact.getFileName()
                        : ledgerPath.getParent().relativize(artifact).toString())
                .put("content_sha256", value.path("content_sha256").asText())
                .put("byte_sha256", JsonHashes.sha256(artifact))
                .put("bytes", Files.size(artifact));
    }

    private static void reserve(ObjectNode ledger, Slot slot, int attempt, Path ledgerPath) throws IOException {
        ObjectNode reservation = JsonHashes.mapper().createObjectNode().put("slot_id", slot.id())
                .put("status", "STARTED").put("attempt", attempt).put("ordinal", slot.ordinal())
                .put("reserved_at", System.currentTimeMillis());
        ledger.withArray("attempts").add(reservation);
        persistLedger(ledgerPath, ledger);
    }

    private static void recordAttempt(ObjectNode ledger, Slot slot, int attempt, String status,
            String error, ObjectNode reference) {
        ObjectNode record = JsonHashes.mapper().createObjectNode().put("slot_id", slot.id())
                .put("status", status).put("attempt", attempt).put("ordinal", slot.ordinal());
        if (error != null && !error.isBlank()) record.put("error", error);
        if (reference != null) record.set("result_ref", reference.deepCopy());
        ledger.withArray("attempts").add(record);
    }

    private static ObjectNode resultFromLedger(ObjectNode plan, ObjectNode profile, String mode,
            List<Slot> planned, ObjectNode ledger, Path ledgerPath, String stopReason, long deadline,
            boolean packagedStrict, ResourceProbe resourceProbe, boolean corrected,
            RunMeasurement measurement, int workers) {
        Map<String, ObjectNode> terminal = terminalBySlot(ledger);
        ArrayNode refs = JsonHashes.mapper().createArrayNode();
        ArrayNode missing = JsonHashes.mapper().createArrayNode();
        for (Slot slot : planned) {
            ObjectNode value = terminal.get(slot.id());
            if (value == null || !"COMPLETE".equals(value.path("status").asText())) missing.add(slot.toJson());
            else refs.add(value.deepCopy());
        }
        boolean aborted = stopReason != null && !stopReason.isBlank();
        ObjectNode result = JsonHashes.mapper().createObjectNode().put("schema", corrected
                ? "strategy-evaluator-operating-characteristics-corrected-parallel-result/1" : RESULT_SCHEMA).put("version", 1)
                .put("status", missing.isEmpty() && !aborted ? "COMPLETE" : "COMPUTE_INCOMPLETE")
                .put("mode", mode).put("plan_sha256", plan.path("content_sha256").asText())
                .put("profile_sha256", profile.path("content_sha256").asText())
                .put("fixed_evaluator", corrected ? CORRECTED_EVALUATOR : FIXED_EVALUATOR)
                .put("shared_physical_evaluator", true)
                .put("toy_statistic", false).put("outcomes_opened", true)
                .put("promotion_eligible", false).put("activation_authorized", false)
                .put("evidence_phase", "DEVELOPMENT").put("measured", false)
                .put("performance_claim", "NONE").put("planned_slots", planned.size())
                .put("completed_slots", refs.size()).put("missing_slots", missing.size())
                .put("requested_workers", workers)
                .put("effective_workers", profile.path("effective_workers").asInt(0))
                .put("ledger_content_sha256", ledger.path("content_sha256").asText())
                .put("retry_limit_per_slot", MAX_RETRY)
                .put("stop_reason", stopReason == null ? "" : stopReason);
        result.set("result_refs", refs); result.set("missing", missing);
        if (corrected) result.put("accounting_version", StrategyFixedBaselinePortfolioCorrectionV1.ACCOUNTING_VERSION);
        result.set("build_identity", BuildIdentityService.describe(StrategyOperatingCharacteristicsParallelV1.class));
        if (corrected && measurement != null) result.set("resource_measurement", measurement.toJson(ledger));
        if ("FULL".equals(mode) && missing.isEmpty() && !aborted) {
            ObjectNode summaries = fullCellSummaries(plan, planned, refs, ledgerPath, profile, deadline, packagedStrict,
                    resourceProbe);
            boolean adequate = true;
            for (JsonNode summary : summaries.path("cells")) if (!summary.path("adequate").asBoolean(false)) adequate = false;
            boolean measured = adequate && summaries.path("cell_count").asInt(0) == 4;
            result.set("cell_summaries", summaries);
            result.put("measured", measured);
            result.put("performance_claim", measured ? "CONDITIONAL_FIXED_BASELINE_DIAGNOSTIC" : "NONE");
        }
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    private static ObjectNode fullCellSummaries(ObjectNode plan, List<Slot> planned, ArrayNode refs,
            Path ledgerPath, ObjectNode profile, long deadline, boolean packagedStrict, ResourceProbe resourceProbe) {
        Map<String, ObjectNode> byCell = new LinkedHashMap<>();
        for (Slot slot : planned) {
            enforceCoordinatorBudget(profile, deadline, resourceProbe);
            String key = slot.scenario() + ":" + slot.effectKey();
            byCell.computeIfAbsent(key, ignored -> JsonHashes.mapper().createObjectNode()
                    .put("scenario", slot.scenario()).put("effect_size", slot.effectSize())
                    .put("planned", 0).put("complete", 0).put("decision_count", 0)
                    .put("adequate", true));
            byCell.get(key).put("planned", byCell.get(key).path("planned").asInt() + 1);
        }
        Map<String, ObjectNode> refsBySlot = new HashMap<>();
        for (JsonNode ref : refs) refsBySlot.put(ref.path("slot_id").asText(), (ObjectNode) ref);
        for (Slot slot : planned) {
            String key = slot.scenario() + ":" + slot.effectKey();
            ObjectNode summary = byCell.get(key);
            JsonNode ref = refsBySlot.get(slot.id());
            if (ref == null) { summary.put("adequate", false); continue; }
            Path artifact = ledgerPath.getParent() == null
                    ? Path.of(ref.path("relative_path").asText())
                    : ledgerPath.getParent().resolve(ref.path("relative_path").asText());
            ObjectNode value = readObject(artifact, "slot artifact");
            verifyArtifact(value, artifact, slot, plan, packagedStrict);
            ObjectNode row = (ObjectNode) value.path("row");
            summary.put("complete", summary.path("complete").asInt() + 1);
            if (row.path("decision").asBoolean(false)) summary.put("decision_count", summary.path("decision_count").asInt() + 1);
            int independent = row.path("independent_units").asInt(row.path("metrics").path("independent_market_episode_count").asInt(0));
            int paired = row.path("metrics").path("paired_tested_cluster_count").asInt(0);
            boolean pairedInsufficient = row.path("metrics").path("paired_analysis_insufficient").asBoolean(true);
            if (!"COMPLETE".equals(row.path("status").asText()) || independent < 30 || paired < 30 || pairedInsufficient) summary.put("adequate", false);
        }
        ArrayNode cells = JsonHashes.mapper().createArrayNode();
        for (ObjectNode summary : byCell.values()) {
            int n = summary.path("complete").asInt(); int successes = summary.path("decision_count").asInt();
            double[] interval = wilson(successes, n);
            summary.put("decision_rate", n == 0 ? 0D : successes / (double) n)
                    .put("wilson_95_lower", interval[0]).put("wilson_95_upper", interval[1]);
            cells.add(summary);
        }
        ObjectNode result = JsonHashes.mapper().createObjectNode().put("cell_count", cells.size());
        result.set("cells", cells); return result;
    }

    private static void enforceCoordinatorBudget(ObjectNode profile, long deadline, ResourceProbe resourceProbe) {
        if (System.nanoTime() >= deadline) throw new ResourceViolation("RESOURCE_WALL_DEADLINE_EXCEEDED");
        ResourceObservation observation = Objects.requireNonNull(resourceProbe.sample(Path.of(".")),
                "resourceProbe observation");
        long rss = observation.coordinatorRssBytes();
        if (rss < 0) throw new ResourceViolation("RESOURCE_COORDINATOR_RSS_UNAVAILABLE");
        long limit = profile.path("coordinator_rss_reservation_bytes").asLong(0);
        if (limit > 0 && rss > limit) throw new ResourceViolation("RESOURCE_COORDINATOR_RSS_BUDGET_EXCEEDED");
    }

    private static double[] wilson(int successes, int observations) {
        if (observations <= 0) return new double[] {Double.NaN, Double.NaN};
        double z = 1.959963984540054;
        double p = successes / (double) observations;
        double denominator = 1D + z * z / observations;
        double center = (p + z * z / (2D * observations)) / denominator;
        double margin = z * Math.sqrt((p * (1D - p) + z * z / (4D * observations)) / observations) / denominator;
        return new double[] {Math.max(0D, center - margin), Math.min(1D, center + margin)};
    }

    private static ObjectNode newLedger(ObjectNode plan, ObjectNode profile, String mode, List<Slot> slots) {
        boolean corrected = CORRECTED_PLAN_SCHEMA
                .equals(plan.path("schema").asText());
        ArrayNode inventory = JsonHashes.mapper().createArrayNode(); slots.forEach(slot -> inventory.add(slot.toJson()));
        ObjectNode ledger = JsonHashes.mapper().createObjectNode().put("schema", LEDGER_SCHEMA).put("version", 1)
                .put("status", "OPEN").put("plan_sha256", plan.path("content_sha256").asText())
                .put("profile_sha256", profile.path("content_sha256").asText()).put("mode", mode)
                .put("fixed_evaluator", corrected ? CORRECTED_EVALUATOR : FIXED_EVALUATOR)
                .put("compact_references_only", true)
                .put("retain_abandoned_reservations", true).put("retry_limit_per_slot", MAX_RETRY)
                .put("content_sha256", "");
        ledger.set("slot_inventory", inventory); ledger.putArray("attempts"); ledger.putArray("terminal");
        ledger.set("execution_profile", profile.deepCopy());
        if (corrected) {
            ledger.put("accounting_version", StrategyFixedBaselinePortfolioCorrectionV1.ACCOUNTING_VERSION)
                    .put("run_id", UUID.randomUUID().toString());
        }
        ledger.put("content_sha256", JsonHashes.ownHash(ledger));
        return ledger;
    }

    private static void validateLedger(ObjectNode ledger, ObjectNode plan, ObjectNode profile, String mode, List<Slot> slots,
            Path ledgerPath, boolean packagedStrict) {
        if (!LEDGER_SCHEMA.equals(ledger.path("schema").asText()) || ledger.path("version").asInt(-1) != 1
                || !ledger.path("content_sha256").asText().equals(JsonHashes.ownHash(ledger))
                || !plan.path("content_sha256").asText().equals(ledger.path("plan_sha256").asText())
                || !profile.path("content_sha256").asText().equals(ledger.path("profile_sha256").asText())
                || !mode.equals(ledger.path("mode").asText()) || !ledger.path("compact_references_only").asBoolean(false)
                || !(CORRECTED_PLAN_SCHEMA
                        .equals(plan.path("schema").asText()) ? CORRECTED_EVALUATOR : FIXED_EVALUATOR)
                        .equals(ledger.path("fixed_evaluator").asText())
                || !ledger.path("execution_profile").isObject()
                || !profile.path("content_sha256").asText().equals(ledger.path("execution_profile").path("content_sha256").asText())) {
            throw new IllegalArgumentException("parallel ledger is not a self-bound compact ledger");
        }
        if (CORRECTED_PLAN_SCHEMA.equals(plan.path("schema").asText())
                && !ledger.path("run_id").asText("").matches(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("corrected ledger has no immutable run identity");
        }
        Set<String> expected = new LinkedHashSet<>(); slots.forEach(slot -> expected.add(slot.id()));
        Set<String> actual = new LinkedHashSet<>();
        for (JsonNode node : ledger.path("slot_inventory")) actual.add(node.path("slot_id").asText());
        if (!expected.equals(actual)) throw new IllegalArgumentException("parallel ledger slot inventory differs from plan");
        Set<String> terminals = new HashSet<>();
        Map<String, Slot> slotsById = new HashMap<>(); slots.forEach(slot -> slotsById.put(slot.id(), slot));
        for (JsonNode node : ledger.path("terminal")) {
            String id = node.path("slot_id").asText();
            if (!expected.contains(id) || !terminals.add(id)) throw new IllegalArgumentException("duplicate or unknown terminal slot: " + id);
            if ("COMPLETE".equals(node.path("status").asText())
                    || "COMPUTE_INCOMPLETE".equals(node.path("status").asText())) {
                if (!SHA256.matcher(node.path("content_sha256").asText()).matches()
                        || !SHA256.matcher(node.path("byte_sha256").asText()).matches()) {
                    throw new IllegalArgumentException("parallel ledger terminal reference is incomplete");
                }
                String relative = node.path("relative_path").asText("");
                Path base = ledgerPath.getParent() == null ? Path.of(".").toAbsolutePath().normalize()
                        : ledgerPath.getParent().toAbsolutePath().normalize();
                Path artifact;
                try {
                    Path relativePath = Path.of(relative);
                    if (relative.isBlank() || relativePath.isAbsolute()) {
                        throw new IllegalArgumentException("parallel ledger result must be relative");
                    }
                    artifact = base.resolve(relativePath).normalize();
                    if (!artifact.startsWith(base)) {
                        throw new IllegalArgumentException("parallel ledger result escapes the ledger root");
                    }
                    if (!Files.isRegularFile(artifact)) {
                        throw new IllegalArgumentException("parallel ledger result is missing: " + artifact);
                    }
                    if (packagedStrict) PathConfinement.validateSinglyLinkedFile(artifact, "parallel ledger result");
                } catch (RuntimeException error) {
                    throw new IllegalArgumentException("parallel ledger result reference is missing or escapes the ledger root", error);
                }
                try {
                    if (Files.size(artifact) != node.path("bytes").asLong(-1)
                            || !JsonHashes.sha256(artifact).equals(node.path("byte_sha256").asText())) {
                        throw new IllegalArgumentException("parallel ledger result byte hash mismatch");
                    }
                } catch (IOException error) {
                    throw new IllegalArgumentException("cannot inspect parallel result artifact", error);
                }
                ObjectNode stored = readObject(artifact, "parallel result artifact");
                verifyArtifact(stored, artifact, slotsById.get(id), plan, packagedStrict,
                        CORRECTED_PLAN_SCHEMA.equals(plan.path("schema").asText())
                                ? ledger.path("run_id").asText("") : null);
                if (!stored.path("content_sha256").asText().equals(node.path("content_sha256").asText())
                        || !stored.path("status").asText().equals(node.path("status").asText())
                        || stored.path("attempt").asInt(-1) != node.path("attempt").asInt(-2)) {
                    throw new IllegalArgumentException("parallel ledger result content hash mismatch");
                }
            }
        }
    }

    private static Map<String, ObjectNode> terminalBySlot(ObjectNode ledger) {
        Map<String, ObjectNode> result = new HashMap<>();
        for (JsonNode node : ledger.path("terminal")) if (node.isObject()
                && Set.of("COMPLETE", "COMPUTE_INCOMPLETE", "FAILED", "INCOMPLETE")
                        .contains(node.path("status").asText())) {
            result.put(node.path("slot_id").asText(), (ObjectNode) node.deepCopy());
        }
        return result;
    }

    private static Map<String, Integer> attemptCounts(ObjectNode ledger) {
        Map<String, Integer> result = new HashMap<>();
        for (JsonNode node : ledger.path("attempts")) {
            String slot = node.path("slot_id").asText(); if (slot.isBlank()) continue;
            result.merge(slot, Math.max(0, node.path("attempt").asInt(0)), Math::max);
        }
        return result;
    }

    private static void verifyArtifact(ObjectNode artifact, Path path, Slot slot, ObjectNode plan,
            boolean packagedStrict) {
        verifyArtifact(artifact, path, slot, plan, packagedStrict, null);
    }

    private static void verifyArtifact(ObjectNode artifact, Path path, Slot slot, ObjectNode plan,
            boolean packagedStrict, String expectedRunId) {
        boolean corrected = CORRECTED_PLAN_SCHEMA
                .equals(plan.path("schema").asText());
        if (!(corrected ? CORRECTED_SLOT_RESULT_SCHEMA : SLOT_RESULT_SCHEMA).equals(artifact.path("schema").asText())
                || !artifact.path("content_sha256").asText().equals(JsonHashes.ownHash(artifact))
                || !slot.id().equals(artifact.path("slot_id").asText())
                || !slot.planSha256().equals(artifact.path("plan_sha256").asText())
                || !slot.mode().equals(artifact.path("mode").asText())
                || !slot.scenario().equals(artifact.path("scenario").asText())
                || Double.compare(slot.effectSize(), artifact.path("effect_size").asDouble(Double.NaN)) != 0
                || slot.replication() != artifact.path("replication").asInt(-1)
                || slot.seed() != artifact.path("seed").asLong(Long.MIN_VALUE)
                || artifact.path("attempt").asInt(0) < 1
                || artifact.path("attempt").asInt(0) > MAX_RETRY + 1
                || !artifact.path("row").isObject()
                || (packagedStrict && !(corrected ? CORRECTED_EVALUATOR : FIXED_EVALUATOR)
                        .equals(artifact.path("fixed_evaluator").asText()))
                || (corrected && !StrategyFixedBaselinePortfolioCorrectionV1.ACCOUNTING_VERSION
                        .equals(artifact.path("accounting_version").asText()))
                || (corrected && expectedRunId != null && !expectedRunId.isBlank()
                        && !expectedRunId.equals(artifact.path("run_id").asText()))) {
            throw new IllegalArgumentException("corrupt slot artifact: " + path);
        }
        String status = artifact.path("status").asText();
        if (!"COMPLETE".equals(status) && !"COMPUTE_INCOMPLETE".equals(status)) {
            throw new IllegalArgumentException("corrupt slot artifact status: " + path);
        }
        ObjectNode row = (ObjectNode) artifact.path("row");
        validateRow(slot, row);
        if (row.has("content_sha256")) {
            requireOwnHash(row, "worker row");
        }
        if ("COMPLETE".equals(status) && !"COMPLETE".equals(row.path("status").asText())) {
            throw new IllegalArgumentException("incomplete worker row was published as COMPLETE: " + path);
        }
        if ("COMPUTE_INCOMPLETE".equals(status) && "COMPLETE".equals(row.path("status").asText())) {
            throw new IllegalArgumentException("complete worker row was published as incomplete: " + path);
        }
        JsonNode identity = artifact.path("executor_identity");
        if (!identity.isObject()) throw new IllegalArgumentException("slot artifact has no executor identity: " + path);
        if (packagedStrict) {
            String expected = plan.path("executor_identity_sha256").asText("");
            String actual = identity.path("executable").path("sha256").asText("");
            if (!SHA256.matcher(expected).matches() || !expected.equals(actual)) {
                throw new IllegalArgumentException("slot artifact executor identity differs from frozen plan: " + path);
            }
            String source = plan.path("executor_build_input_fingerprint").asText("");
            String actualSource = identity.path("compiled").path("input_fingerprint").asText("");
            if (!SHA256.matcher(source).matches() || !source.equals(actualSource)) {
                throw new IllegalArgumentException("slot artifact source identity differs from frozen plan: " + path);
            }
        }
        if (row.path("raw_evaluator_result").isObject()) {
            ObjectNode raw = (ObjectNode) row.path("raw_evaluator_result");
            requireOwnHash(raw, "raw evaluator result");
            validateRawReceipts(artifact, row, raw, slot, plan, path,
                    packagedStrict && "COMPLETE".equals(status));
        } else if (packagedStrict && "COMPLETE".equals(status)) {
            throw new IllegalArgumentException("packaged worker row has no bound raw evaluator result: " + path);
        }
    }

    /**
     * Checks the producer's durable raw-result custody bindings.  A compact row
     * is useful for scheduling and summaries, but it is never authoritative for
     * a packaged worker: every compact projection used by the coordinator must
     * still agree with the immutable evaluator result and its receipt.
     */
    private static void validateRawReceipts(ObjectNode artifact, ObjectNode row, ObjectNode raw,
            Slot slot, ObjectNode plan, Path path, boolean packagedStrict) {
        String rawContent = raw.path("content_sha256").asText("");
        String rawCanonical = JsonHashes.canonicalSha256(raw);
        String rawBytes = serializedJsonSha256(raw);
        checkRawReceipt(row, "raw_evaluator_result_content_sha256", rawContent, path, packagedStrict);
        checkRawReceipt(row, "raw_evaluator_result_canonical_sha256", rawCanonical, path, packagedStrict);
        checkRawReceipt(row, "raw_evaluator_result_byte_sha256", rawBytes, path, packagedStrict);
        String portable = artifact.path("portable_economic_sha256").asText("");
        if (packagedStrict && !SHA256.matcher(portable).matches()) {
            throw new IllegalArgumentException("packaged slot has no portable economic digest: " + path);
        }
        if (!portable.isBlank() && !portableEconomicSha256(raw).equals(portable)) {
            throw new IllegalArgumentException("portable economic digest mismatch: " + path);
        }
        if (!packagedStrict) return;

        String physical = raw.path("physical_input_sha256").asText("");
        String expectedPhysical = expectedSyntheticInputSha256(plan, slot);
        if (!SHA256.matcher(physical).matches() || !physical.equals(expectedPhysical)) {
            throw new IllegalArgumentException("raw evaluator physical input descriptor differs from frozen slot: " + path);
        }
        if (!plan.path("baseline_sha256").asText("").equals(raw.path("baseline_sha256").asText(""))
                || !plan.path("control_spec_sha256").asText("").equals(raw.path("control_spec_sha256").asText(""))
                || !plan.path("experiment_sha256").asText("").equals(raw.path("experiment_sha256").asText(""))) {
            throw new IllegalArgumentException("raw evaluator inputs differ from the frozen plan: " + path);
        }

        ObjectNode receipt = object(row, "evaluator_receipt");
        requireOwnHash(receipt, "evaluator receipt");
        boolean corrected = CORRECTED_SLOT_RESULT_SCHEMA.equals(artifact.path("schema").asText());
        if (corrected) {
            if (!StrategyFixedBaselineCorrectedV1.RESULT_SCHEMA.equals(raw.path("schema").asText())
                    || !CORRECTED_EVALUATOR.equals(raw.path("evaluator_identity").asText())
                    || !CORRECTED_EVALUATOR.equals(raw.path("corrected_evaluator_identity").asText())
                    || !StrategyFixedBaselinePortfolioCorrectionV1.ACCOUNTING_VERSION
                            .equals(raw.path("accounting_version").asText())
                    || !StrategyFixedBaselinePortfolioCorrectionV1.ACCOUNTING_VERSION
                            .equals(raw.path("evaluator").path("accounting_version").asText())
                    || !SHA256.matcher(raw.path("corrected_economic_semantic_sha256").asText()).matches()
                    || !raw.path("corrected_portfolio_metrics").isObject()
                    || !raw.path("correction_receipt").isObject()
                    || !raw.path("portfolio").path("corrected_metrics").isObject()
                    || !raw.path("metrics").path("corrected_portfolio").isObject()
                    || !raw.path("corrected_portfolio_metrics")
                            .equals(raw.path("portfolio").path("corrected_metrics"))
                    || !raw.path("metrics").path("corrected_portfolio")
                            .equals(raw.path("corrected_portfolio_metrics"))) {
                throw new IllegalArgumentException("corrected raw evaluator identity or accounting binding mismatch: " + path);
            }
            validateCorrectedRawBindings(raw, plan, path);
            validateCorrectedQualificationPortfolio(row,
                    "FULL".equals(artifact.path("mode").asText()));
        }
        if (!(corrected ? StrategyOperatingCharacteristicsSuccessorV1.CORRECTED_RECEIPT_SCHEMA
                : "strategy-evaluator-operating-characteristics-successor-evaluator-receipt/1")
                .equals(receipt.path("schema").asText())
                || receipt.path("version").asInt(-1) != 1
                || !"SHARED_FIXED_EVALUATOR_IN_PROCESS".equals(receipt.path("origin").asText())) {
            throw new IllegalArgumentException("evaluator receipt contract mismatch: " + path);
        }
        requireSha(receipt.path("result_content_sha256").asText(), "evaluator receipt result_content_sha256");
        requireSha(receipt.path("economic_semantic_sha256").asText(), "evaluator receipt economic_semantic_sha256");
        requireSha(receipt.path("executor_identity_sha256").asText(), "evaluator receipt executor_identity_sha256");
        requireSha(receipt.path("source_input_sha256").asText(), "evaluator receipt source_input_sha256");
        String rawEconomic = corrected ? raw.path("corrected_economic_semantic_sha256").asText("")
                : raw.path("economic_semantic_sha256").asText("");
        if (!SHA256.matcher(rawEconomic).matches()
                || !rawContent.equals(receipt.path("result_content_sha256").asText())
                || !rawContent.equals(row.path("raw_evaluator_result_content_sha256").asText())
                || !raw.path("schema").asText().equals(receipt.path("result_schema").asText())
                || !raw.path("status").asText().equals(receipt.path("status").asText())
                || !rawEconomic.equals(receipt.path("economic_semantic_sha256").asText())
                || !rawEconomic.equals(row.path("economic_semantic_sha256").asText())
                || !physical.equals(row.path("generator_input_sha256").asText())
                || !physical.equals(receipt.path("source_input_sha256").asText())) {
            throw new IllegalArgumentException("evaluator receipt result binding mismatch: " + path);
        }
        String artifactExecutor = artifact.path("executor_identity").path("executable").path("sha256").asText("");
        if (!SHA256.matcher(artifactExecutor).matches()
                || !artifactExecutor.equals(raw.path("executor_identity_sha256").asText())
                || !artifactExecutor.equals(receipt.path("executor_identity_sha256").asText())) {
            throw new IllegalArgumentException("evaluator receipt executor binding mismatch: " + path);
        }
        if (!raw.path("metrics").isObject()
                || !raw.path("metrics").equals(receipt.path("metrics"))
                || !raw.path("metrics").equals(row.path("metrics"))
                || !raw.path("portfolio").equals(row.path("portfolio_summary"))
                || !raw.path("disposition").equals(row.path("disposition"))
                || !raw.path("status").asText().equals(row.path("status").asText())
                || raw.path("event_count").asInt(-1) != row.path("event_count").asInt(-2)
                || raw.path("matched_control_count").asInt(-1) != row.path("paired_count").asInt(-2)
                || raw.path("independent_market_episode_count").asInt(-1) != row.path("independent_units").asInt(-2)
                || ("ELIGIBLE".equals(raw.path("disposition").path("primary_reason").asText())
                        != row.path("decision").asBoolean(false))) {
            throw new IllegalArgumentException("compact worker projection differs from raw evaluator result: " + path);
        }
    }

    /**
     * Recomputes every corrected-result provenance binding that is part of the
     * production evaluator contract.  Shape-only SHA checks are insufficient:
     * a rehashed result can still name another correction algorithm or source
     * result and then be accepted on resume.
     */
    static void validateCorrectedRawForResume(ObjectNode raw, ObjectNode row, ObjectNode plan,
            String mode, String scenario, double effect, int replication, long seed, Path path) {
        String physical = raw.path("physical_input_sha256").asText("");
        String expectedPhysical = expectedSyntheticInputSha256(plan,
                new Slot(plan.path("content_sha256").asText(), mode, scenario, effect, replication, seed, 0));
        if (!SHA256.matcher(physical).matches() || !physical.equals(expectedPhysical)
                || !plan.path("baseline_sha256").asText("").equals(raw.path("baseline_sha256").asText(""))
                || !plan.path("control_spec_sha256").asText("").equals(raw.path("control_spec_sha256").asText(""))
                || !plan.path("experiment_sha256").asText("").equals(raw.path("experiment_sha256").asText(""))) {
            throw new IllegalArgumentException("successor raw evaluator input differs from frozen slot or plan: " + path);
        }
        if (!StrategyFixedBaselineCorrectedV1.RESULT_SCHEMA.equals(raw.path("schema").asText())
                || !CORRECTED_EVALUATOR.equals(raw.path("evaluator_identity").asText())
                || !CORRECTED_EVALUATOR.equals(raw.path("corrected_evaluator_identity").asText())
                || !StrategyFixedBaselinePortfolioCorrectionV1.ACCOUNTING_VERSION
                        .equals(raw.path("accounting_version").asText())
                || !SHA256.matcher(raw.path("executor_identity_sha256").asText()).matches()
                || !raw.path("executor_identity_sha256").asText()
                        .equals(plan.path("executor_identity_sha256").asText())
                || !SHA256.matcher(raw.path("corrected_executor_identity_sha256").asText()).matches()
                || !raw.path("corrected_executor_identity_sha256").asText()
                        .equals(plan.path("executor_identity_sha256").asText())
                || !raw.path("content_sha256").asText().equals(JsonHashes.ownHash(raw))
                || !raw.path("corrected_portfolio_metrics").isObject()
                || !raw.path("portfolio").path("corrected_metrics").isObject()
                || !raw.path("metrics").path("corrected_portfolio").isObject()
                || !raw.path("corrected_portfolio_metrics")
                        .equals(raw.path("portfolio").path("corrected_metrics"))
                || !raw.path("corrected_portfolio_metrics")
                        .equals(raw.path("metrics").path("corrected_portfolio"))
                || !raw.path("corrected_economic_semantic_sha256").asText()
                        .equals(StrategyFixedBaselineCorrectedV1.correctedEconomicSha256ForValidation(raw))
                || !row.path("generator_input_sha256").asText().equals(physical)
                || !row.path("raw_evaluator_result_content_sha256").asText()
                        .equals(raw.path("content_sha256").asText())
                || !row.path("portfolio_summary").equals(raw.path("portfolio"))
                || !row.path("metrics").equals(raw.path("metrics"))) {
            throw new IllegalArgumentException("successor raw evaluator result is not bound to its compact row: " + path);
        }
        validateCorrectedRawBindings(raw, plan, path);
        validateCorrectedQualificationPortfolio(row, "FULL".equals(mode));
    }

    private static void validateCorrectedRawBindings(ObjectNode raw, ObjectNode plan, Path path) {
        String algorithm = StrategyFixedBaselineCorrectedV1.algorithmFingerprintForValidation();
        ObjectNode binding = object(raw, "corrected_input_binding");
        if (!algorithm.equals(raw.path("algorithm_fingerprint").asText())
                || !raw.path("corrected_input_binding_sha256").asText()
                        .equals(JsonHashes.canonicalSha256(binding))
                || !StrategyFixedBaselineCorrectedV1.EVALUATOR_ID
                        .equals(binding.path("corrected_evaluator_identity").asText())
                || !StrategyFixedBaselinePortfolioCorrectionV1.ACCOUNTING_VERSION
                        .equals(binding.path("accounting_version").asText())
                || !algorithm.equals(binding.path("algorithm_fingerprint").asText())
                || !raw.path("source_result_content_sha256").asText()
                        .equals(binding.path("source_result_content_sha256").asText())
                || !raw.path("source_evaluator_identity").asText()
                        .equals(binding.path("source_evaluator_identity").asText())
                || !raw.path("source_executor_identity_sha256").asText()
                        .equals(binding.path("source_executor_identity_sha256").asText())
                || !raw.path("baseline_sha256").asText().equals(binding.path("baseline_sha256").asText())
                || !raw.path("control_spec_sha256").asText().equals(binding.path("control_spec_sha256").asText())
                || !raw.path("experiment_sha256").asText().equals(binding.path("experiment_sha256").asText())
                || !raw.path("physical_input_sha256").asText().equals(binding.path("physical_input_sha256").asText())) {
            throw new IllegalArgumentException("corrected raw evaluator input binding mismatch: " + path);
        }
        ObjectNode receipt = object(raw, "correction_receipt");
        requireOwnHash(receipt, "corrected evaluator receipt");
        if (!"strategy-fixed-baseline-corrected-evaluator-receipt/1".equals(receipt.path("schema").asText())
                || receipt.path("version").asInt(-1) != 1
                || !StrategyFixedBaselineCorrectedV1.EVALUATOR_ID.equals(receipt.path("evaluator_identity").asText())
                || !StrategyFixedBaselinePortfolioCorrectionV1.ACCOUNTING_VERSION
                        .equals(receipt.path("accounting_version").asText())
                || !algorithm.equals(receipt.path("algorithm_fingerprint").asText())
                || !raw.path("source_result_content_sha256").asText()
                        .equals(receipt.path("source_result_content_sha256").asText())
                || !raw.path("source_executor_identity_sha256").asText()
                        .equals(receipt.path("source_executor_identity_sha256").asText())
                || !raw.path("portfolio").path("event_book").path("content_sha256").asText()
                        .equals(receipt.path("event_book_correction_sha256").asText())
                || !raw.path("portfolio").path("control_book").path("content_sha256").asText()
                        .equals(receipt.path("control_book_correction_sha256").asText())) {
            throw new IllegalArgumentException("corrected raw evaluator correction receipt mismatch: " + path);
        }
    }

    private static String expectedSyntheticInputSha256(ObjectNode plan, Slot slot) {
        ObjectNode syntheticInput = JsonHashes.mapper().createObjectNode()
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("mode", slot.mode())
                .put("replication", slot.replication())
                .put("cell", slot.scenario())
                .put("effect_size", slot.effectSize())
                .put("seed", slot.seed())
                .put("episodes", episodesForMode(plan, slot.mode()));
        return JsonHashes.canonicalSha256(syntheticInput);
    }


    private static void checkRawReceipt(ObjectNode row, String field, String expected, Path path, boolean required) {
        String actual = row.path(field).asText("");
        if (required && !SHA256.matcher(actual).matches()) {
            throw new IllegalArgumentException("packaged worker row has no valid " + field + ": " + path);
        }
        if (!actual.isBlank() && !expected.equals(actual)) {
            throw new IllegalArgumentException("raw evaluator " + field + " mismatch: " + path);
        }
    }

    private static void validateRow(Slot slot, ObjectNode row) {
        if (!row.isObject()) throw new IllegalArgumentException("worker row is not an object");
        String status = row.path("status").asText("");
        String error = row.path("error").asText("");
        if (status.startsWith("RESOURCE_") || error.startsWith("RESOURCE_")) {
            throw new ResourceViolation("RESOURCE_INCOMPLETE_ROW");
        }
        if (row.has("cell") && !slot.scenario().equals(row.path("cell").asText())) throw new IllegalArgumentException("worker row scenario differs from slot");
        if (row.has("effect_size") && Double.compare(slot.effectSize(), row.path("effect_size").asDouble(Double.NaN)) != 0) throw new IllegalArgumentException("worker row effect differs from slot");
        if (row.has("replication") && slot.replication() != row.path("replication").asInt(-1)) throw new IllegalArgumentException("worker row replication differs from slot");
        if (row.has("seed") && slot.seed() != row.path("seed").asLong(Long.MIN_VALUE)) throw new IllegalArgumentException("worker row seed differs from slot");
    }

    private static List<Slot> slots(ObjectNode plan, String mode) {
        String planHash = plan.path("content_sha256").asText();
        ArrayList<Slot> result = new ArrayList<>();
        JsonNode explicit = plan.path("slots");
        if ("FULL".equals(mode) && explicit.isArray() && explicit.size() > 0) {
            throw new IllegalArgumentException("FULL parallel runs must derive slots from the frozen statistical cell seeds");
        }
        if (explicit.isArray() && explicit.size() > 0) {
            Set<Long> fullSeeds = new HashSet<>();
            for (JsonNode cell : plan.path("cells")) for (JsonNode seed : cell.path("seeds")) {
                if (seed.isIntegralNumber()) fullSeeds.add(seed.asLong());
            }
            int ordinal = 0;
            for (JsonNode node : explicit) {
                if (!mode.equals(node.path("mode").asText(mode))) continue;
                if ("PREFIX".equals(mode) && fullSeeds.contains(node.path("seed").asLong(Long.MIN_VALUE))) {
                    throw new IllegalArgumentException("explicit prefix slot overlaps a frozen full seed");
                }
                result.add(new Slot(planHash, mode, node.path("scenario").asText(), node.path("effect_size").asDouble(),
                        node.path("replication").asInt(-1), node.path("seed").asLong(Long.MIN_VALUE), ordinal++));
            }
        } else {
            JsonNode cellInventory = plan.path("development_wave").asBoolean(false)
                    && (("FULL".equals(mode) && plan.path("development_seed_cells").isArray())
                        || ("PREFIX".equals(mode) && plan.path("development_prefix_seed_cells").isArray()))
                    ? plan.path("development_seed_cells") : plan.path("cells");
            if ("PREFIX".equals(mode) && plan.path("development_wave").asBoolean(false)
                    && plan.path("development_prefix_seed_cells").isArray()) {
                cellInventory = plan.path("development_prefix_seed_cells");
            }
            for (JsonNode cell : cellInventory) {
                String scenario = cell.path("scenario").asText(cell.path("name").asText(""));
                double effect = cell.path("effect_size").asDouble(cell.path("effect").asDouble(Double.NaN));
                JsonNode seeds;
                if ("PREFIX".equals(mode)) {
                    if (!cell.path("prefix_seeds").isArray() || cell.path("prefix_seeds").size() == 0) {
                        throw new IllegalArgumentException("PREFIX parallel plan must declare disjoint prefix_seeds");
                    }
                    Set<Long> fullSeeds = new HashSet<>();
                    for (JsonNode full : plan.path("cells")) for (JsonNode seed : full.path("seeds")) {
                        if (seed.isIntegralNumber()) fullSeeds.add(seed.asLong());
                    }
                    for (JsonNode prefix : cell.path("prefix_seeds")) if (fullSeeds.contains(prefix.asLong())) {
                        throw new IllegalArgumentException("prefix seed overlaps a frozen full seed");
                    }
                    seeds = cell.path("prefix_seeds");
                } else {
                    seeds = plan.path("development_wave").asBoolean(false)
                            && "FULL".equals(mode) && cell.has("development_seeds")
                            ? cell.path("development_seeds") : cell.path("seeds");
                }
                if (!seeds.isArray()) throw new IllegalArgumentException("parallel plan cell has no seeds: " + scenario);
                for (int repetition = 0; repetition < seeds.size(); repetition++)
                    result.add(new Slot(planHash, mode, scenario, effect, repetition, seeds.get(repetition).asLong(), result.size()));
            }
        }
        result.sort(Comparator.comparing(Slot::mode).thenComparing(Slot::scenario)
                .thenComparingDouble(Slot::effectSize).thenComparingInt(Slot::replication).thenComparingLong(Slot::seed));
        ArrayList<Slot> ordered = new ArrayList<>();
        for (int index = 0; index < result.size(); index++) {
            Slot slot = result.get(index); ordered.add(new Slot(slot.planSha256(), slot.mode(), slot.scenario(), slot.effectSize(), slot.replication(), slot.seed(), index));
        }
        Set<String> unique = new HashSet<>(); for (Slot slot : ordered) if (!unique.add(slot.id())) throw new IllegalArgumentException("duplicate parallel slot: " + slot.id());
        return List.copyOf(ordered);
    }

    private static Slot slotFrom(JsonNode value) {
        return new Slot(value.path("plan_sha256").asText(), value.path("mode").asText(), value.path("scenario").asText(),
                value.path("effect_size").asDouble(Double.NaN), value.path("replication").asInt(-1),
                value.path("seed").asLong(Long.MIN_VALUE), value.path("ordinal").asInt(-1));
    }

    private static int episodesForMode(ObjectNode plan, String mode) {
        if ("PREFIX".equals(mode)) {
            // Keep this fallback identical to SuccessorV1.expectedInputSha:
            // PREFIX is the bounded ten-episode diagnostic when the retained
            // preflight does not carry an explicit episode count.
            return plan.path("resource_preflight").path("episodes_per_replication").asInt(10);
        }
        return plan.path("episodes_per_replication").asInt(450);
    }

    private static void validatePlan(ObjectNode plan) {
        if (!plan.path("schema").asText().contains("operating-characteristics")
                || !plan.path("content_sha256").asText().equals(JsonHashes.ownHash(plan))
                || !plan.path("frozen_before_outcomes").asBoolean(false)
                || plan.path("outcomes_opened").asBoolean(true)
                || plan.path("promotion_eligible").asBoolean(true)
                || plan.path("activation_authorized").asBoolean(true)) {
            throw new IllegalArgumentException("parallel plan is not frozen before outcomes");
        }
        if (!plan.has("slots") && !plan.path("cells").isArray()) throw new IllegalArgumentException("parallel plan has no frozen slot inventory");
        String mode = plan.path("mode").asText("FULL");
        if ("FULL".equals(mode) && !plan.path("cells").isArray()) {
            throw new IllegalArgumentException("FULL parallel plan must bind the statistical cell seed arrays");
        }
        if ("FULL".equals(mode) && plan.has("replications") && plan.path("replications").asInt(-1) != 75) {
            throw new IllegalArgumentException("FULL parallel plan must preserve 75 repetitions per cell");
        }
        if ("FULL".equals(mode) && plan.has("cell_count") && plan.path("cell_count").asInt(-1) != 4) {
            throw new IllegalArgumentException("FULL parallel plan must preserve four statistical cells");
        }
        if (plan.path("episodes_per_replication").asInt(1) <= 0) throw new IllegalArgumentException("parallel plan episodes are invalid");
    }

    /** Validates the retained frozen declaration before a corrected plan derives from it. */
    static void validateBasePlanForCorrectedBuilder(ObjectNode plan) {
        validatePlan(plan);
        if (!PLAN_SCHEMA.equals(plan.path("schema").asText())) {
            throw new IllegalArgumentException("corrected development builder requires the retained parallel plan schema");
        }
        validateFullGeometry(plan);
        requireStatisticalBinding(plan, "FULL");
        validateDependencyBindings(plan);
    }

    /** Structural corrected-plan checks usable by preflight without opening a process. */
    static void validateCorrectedPlanForPreflight(ObjectNode plan) {
        validatePlan(plan);
        if (!CORRECTED_PLAN_SCHEMA.equals(plan.path("schema").asText())
                || !CORRECTED_EVALUATOR.equals(plan.path("corrected_evaluator_identity").asText(
                        plan.path("fixed_evaluator").asText("")))
                || !StrategyFixedBaselinePortfolioCorrectionV1.ACCOUNTING_VERSION
                        .equals(plan.path("accounting_version").asText())) {
            throw new IllegalArgumentException("corrected parallel plan accounting binding is invalid");
        }
        validateFullGeometry(plan);
        requireStatisticalBinding(plan, "FULL");
        validateDependencyBindings(plan);
        for (String field : List.of("baseline_sha256", "control_spec_sha256", "experiment_sha256",
                "executor_source_sha256", "executor_build_input_fingerprint", "executor_identity_sha256",
                "execution_profile_sha256")) {
            if (!SHA256.matcher(plan.path(field).asText("")).matches()) {
                throw new IllegalArgumentException("corrected parallel plan lacks " + field);
            }
        }
    }

    private static void validateFullGeometry(ObjectNode plan) {
        if (!plan.path("cells").isArray() || plan.path("cells").size() != 4
                || plan.path("cell_count").asInt(-1) != 4
                || plan.path("replications").asInt(-1) != 75
                || plan.path("episodes_per_replication").asInt(-1) != 450
                || plan.path("cluster_count").asInt(-1) != 288
                || plan.path("paired_cluster_count").asInt(-1) != 162
                || plan.path("lifecycle_series_per_replication").asInt(-1) != 900
                || plan.path("horizon_minutes").asInt(-1) != 14_400) {
            throw new IllegalArgumentException("FULL parallel plan does not preserve the frozen statistical geometry");
        }
        Set<String> cells = new LinkedHashSet<>();
        Set<Long> fullSeeds = new HashSet<>();
        Set<String> expected = Set.of("NO_EDGE:0", "PLANTED_EDGE:0", "PLANTED_EDGE:0.02", "PLANTED_EDGE:0.04");
        for (JsonNode cell : plan.path("cells")) {
            if (!cell.path("scenario").isTextual() || !cell.path("effect_size").isNumber()
                    || !cell.path("seeds").isArray() || cell.path("seeds").size() != 75) {
                throw new IllegalArgumentException("FULL parallel cell seed inventory is incomplete");
            }
            String key = cell.path("scenario").asText() + ":"
                    + java.math.BigDecimal.valueOf(cell.path("effect_size").asDouble()).stripTrailingZeros().toPlainString();
            if (!expected.contains(key) || !cells.add(key)) throw new IllegalArgumentException("FULL parallel cells differ from frozen four-cell geometry");
            for (JsonNode seed : cell.path("seeds")) {
                if (!seed.isIntegralNumber() || !fullSeeds.add(seed.asLong())) {
                    throw new IllegalArgumentException("FULL parallel seeds are not unique and integral");
                }
            }
        }
        if (!cells.equals(expected)) throw new IllegalArgumentException("FULL parallel cells differ from frozen four-cell geometry");
    }

    private static void validatePrefixGeometry(ObjectNode plan) {
        JsonNode cells = plan.path("cells");
        if (cells.isArray() && cells.size() > 0) {
            Set<Long> fullSeeds = new HashSet<>();
            for (JsonNode cell : cells) for (JsonNode seed : cell.path("seeds")) if (seed.isIntegralNumber()) fullSeeds.add(seed.asLong());
            for (JsonNode cell : cells) {
                if (!cell.path("prefix_seeds").isArray() || cell.path("prefix_seeds").size() == 0) {
                    throw new IllegalArgumentException("PREFIX parallel plan has no declared prefix seed inventory");
                }
                Set<Long> local = new HashSet<>();
                for (JsonNode seed : cell.path("prefix_seeds")) {
                    if (!seed.isIntegralNumber() || !local.add(seed.asLong()) || fullSeeds.contains(seed.asLong())) {
                        throw new IllegalArgumentException("PREFIX seeds overlap or are not unique");
                    }
                }
            }
        }
    }

    private static void requireStatisticalBinding(ObjectNode plan, String requestedMode) {
        String hash = plan.path("statistical_plan_sha256").asText(
                plan.path("binding_statistical_plan_sha256").asText(""));
        if (!SHA256.matcher(hash).matches()) {
            throw new IllegalArgumentException("parallel plan has no immutable statistical-plan hash binding");
        }
        JsonNode bound = plan.path("statistical_plan").isObject()
                ? plan.path("statistical_plan") : plan.path("statistical_plan_projection");
        if (!bound.isObject()) {
            throw new IllegalArgumentException("parallel plan has no immutable statistical-plan projection");
        }
        if (!hash.equals(JsonHashes.ownHash(bound))) {
            throw new IllegalArgumentException("parallel statistical-plan binding hash mismatch");
        }
        for (String field : List.of("cell_count", "replications", "episodes_per_replication",
                "cluster_count", "paired_cluster_count", "lifecycle_series_per_replication", "horizon_minutes")) {
            if ("PREFIX".equals(requestedMode) && "episodes_per_replication".equals(field)) continue;
            if (!bound.has(field) || !bound.path(field).equals(plan.path(field))) {
                throw new IllegalArgumentException("parallel statistical-plan geometry differs at " + field);
            }
        }
        if (!bound.path("cells").isArray() || !bound.path("cells").equals(plan.path("cells"))) {
            throw new IllegalArgumentException("parallel statistical-plan cells differ from bound plan");
        }
    }

    private static void validateBoundInputs(ObjectNode options, ObjectNode plan) {
        validateBoundInputs(options, plan, false);
    }

    private static void validateBoundInputs(ObjectNode options, ObjectNode plan, boolean strict) {
        ObjectNode baseline = readObjectOption(options, "baseline", "baseline");
        ObjectNode controls = readObjectOption(options, "controls", "controls");
        ObjectNode experiment = readObjectOption(options, "experiment", "experiment");
        requireOwnHash(baseline, "baseline"); requireOwnHash(controls, "controls"); requireOwnHash(experiment, "experiment");
        requireBinding(plan, "baseline_sha256", "binding_baseline_sha256", baseline.path("content_sha256").asText(), strict);
        requireBinding(plan, "control_spec_sha256", "binding_control_spec_sha256", controls.path("content_sha256").asText(), strict);
        requireBinding(plan, "experiment_sha256", "binding_experiment_sha256", experiment.path("content_sha256").asText(), strict);
        if (strict && !experiment.has("baseline_sha256") && !experiment.has("binding_baseline_sha256")) {
            throw new IllegalArgumentException("experiment has no baseline binding");
        }
        if (strict && !experiment.has("controls_sha256") && !experiment.has("binding_control_spec_sha256")) {
            throw new IllegalArgumentException("experiment has no control binding");
        }
        if (experiment.has("baseline_sha256") && !baseline.path("content_sha256").asText().equals(experiment.path("baseline_sha256").asText()))
            throw new IllegalArgumentException("experiment does not bind supplied baseline");
        if (experiment.has("controls_sha256") && !controls.path("content_sha256").asText().equals(experiment.path("controls_sha256").asText()))
            throw new IllegalArgumentException("experiment does not bind supplied controls");
    }

    private static void requireOwnHash(ObjectNode value, String label) {
        if (!SHA256.matcher(value.path("content_sha256").asText()).matches()
                || !value.path("content_sha256").asText().equals(JsonHashes.ownHash(value))) {
            throw new IllegalArgumentException(label + " content hash is invalid");
        }
    }

    private static String serializedJsonSha256(JsonNode value) {
        try {
            return JsonHashes.sha256(JsonHashes.mapper().writeValueAsBytes(value));
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot serialize JSON receipt", error);
        }
    }

    /**
     * Portable counterpart of the frozen evaluator's economic digest.  The
     * legacy digest remains authoritative for the fixed evaluator; this
     * additive digest only removes the two private worker-root policy paths so
     * equivalent packaged workers can compare their economic output.
     */
    private static String portableEconomicSha256(ObjectNode raw) {
        if (StrategyFixedBaselineCorrectedV1.RESULT_SCHEMA.equals(raw.path("schema").asText())) {
            String expected = StrategyFixedBaselineCorrectedV1.correctedEconomicSha256ForValidation(raw);
            if (!expected.equals(raw.path("corrected_economic_semantic_sha256").asText())) {
                throw new IllegalArgumentException("corrected evaluator economic digest is not recomputed from its output");
            }
            return expected;
        }
        ObjectNode copy = raw.deepCopy();
        copy.remove("content_sha256"); copy.remove("semantic_sha256"); copy.remove("economic_semantic_sha256");
        copy.remove("build_identity"); copy.remove("exposure_head_sha256"); copy.remove("exposure_head_path");
        copy.remove("executor_identity_sha256"); copy.remove("attempt_identity_sha256");
        copy.remove("legacy_exposure_migration");
        copy.remove("portfolio_policy_path");
        copy.remove("lifecycle_timing_policy_path");
        stripCustodyPaths(copy);
        return JsonHashes.canonicalSha256(copy);
    }

    private static void stripCustodyPaths(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove("path"); object.remove("physical_root_reference");
            List<String> names = new ArrayList<>(); object.fieldNames().forEachRemaining(names::add);
            for (String name : names) stripCustodyPaths(object.get(name));
        } else if (node instanceof ArrayNode array) {
            array.forEach(StrategyOperatingCharacteristicsParallelV1::stripCustodyPaths);
        }
    }

    static String portableEconomicSha256ForTest(ObjectNode raw) {
        return portableEconomicSha256(raw);
    }

    private static void requireBindingIfPresent(ObjectNode plan, String field, String actual) {
        if (plan.has(field) && !actual.equals(plan.path(field).asText()))
            throw new IllegalArgumentException("parallel plan " + field + " does not bind supplied input");
    }

    private static void requireBinding(ObjectNode plan, String primary, String alias, String actual, boolean strict) {
        boolean present = plan.has(primary) || plan.has(alias);
        if (strict && !present) throw new IllegalArgumentException("parallel plan has no " + primary + " binding");
        if (plan.has(primary) && !actual.equals(plan.path(primary).asText()))
            throw new IllegalArgumentException("parallel plan " + primary + " does not bind supplied input");
        if (plan.has(alias) && !actual.equals(plan.path(alias).asText()))
            throw new IllegalArgumentException("parallel plan " + alias + " does not bind supplied input");
    }

    private static void validatePackagedPlan(ObjectNode plan) {
        validatePackagedPlan(plan, plan.path("mode").asText("FULL"), false);
    }

    private static void validatePackagedPlan(ObjectNode plan, String requestedMode) {
        validatePackagedPlan(plan, requestedMode, false);
    }

    private static void validatePackagedPlan(ObjectNode plan, String requestedMode, boolean corrected) {
        validatePlan(plan);
        boolean planCorrected = CORRECTED_PLAN_SCHEMA
                .equals(plan.path("schema").asText());
        if (corrected != planCorrected) {
            throw new IllegalArgumentException("parallel packaged executor mode does not match the versioned plan");
        }
        if (corrected && !CORRECTED_EVALUATOR.equals(plan.path("corrected_evaluator_identity").asText(
                plan.path("fixed_evaluator").asText("")))) {
            throw new IllegalArgumentException("parallel plan is not bound to the corrected evaluator");
        }
        if ("FULL".equals(requestedMode)) validateFullGeometry(plan);
        else validatePrefixGeometry(plan);
        ObjectNode identity = BuildIdentityService.describe(StrategyOperatingCharacteristicsParallelV1.class);
        String expected = plan.path("executor_identity_sha256").asText("");
        String actual = identity.path("executable").path("sha256").asText("");
        if (!"JAR".equals(identity.path("executable").path("kind").asText()) || !SHA256.matcher(actual).matches()
                || !SHA256.matcher(expected).matches() || !expected.equals(actual)) {
            throw new IllegalArgumentException("parallel plan executor identity does not match packaged JAR");
        }
        String source = plan.path("executor_build_input_fingerprint").asText("");
        String actualSource = identity.path("compiled").path("input_fingerprint").asText("");
        if (!SHA256.matcher(source).matches() || !source.equals(actualSource)) {
            throw new IllegalArgumentException("parallel plan source identity mismatch");
        }
        String profile = plan.path("execution_profile_sha256").asText(
                plan.path("binding_execution_profile_sha256").asText(""));
        if (!SHA256.matcher(profile).matches()) {
            throw new IllegalArgumentException("parallel plan has no frozen execution-profile binding");
        }
        requireStatisticalBinding(plan, requestedMode);
        validateDependencyBindings(plan);
    }

    private static void requireProfileBinding(ObjectNode plan, ObjectNode profile) {
        String expected = plan.path("execution_profile_sha256").asText(
                plan.path("binding_execution_profile_sha256").asText(""));
        if (!SHA256.matcher(expected).matches() || !expected.equals(profile.path("content_sha256").asText())) {
            throw new IllegalArgumentException("execution profile differs from the frozen parallel plan");
        }
    }

    private static void validateDependencyBindings(ObjectNode plan) {
        JsonNode dependencies = plan.path("supporting_dependency_receipts");
        if (!dependencies.isArray() || dependencies.size() != 2) {
            throw new IllegalArgumentException("parallel plan has no complete frozen worker dependency receipts");
        }
        Set<String> paths = new HashSet<>();
        for (JsonNode dependency : dependencies) {
            String relative = dependency.path("relative_path").asText("");
            if (!paths.add(relative)
                    || !relative.equals("strategy-research/experiments/fk-deleveraging-baseline-v002/portfolio-policy-v001.json")
                            && !relative.equals("strategy-research/experiments/fk-deleveraging-baseline-v002/lifecycle-timing-v001.json")
                    || dependency.path("bytes").asLong(0) <= 0
                    || !SHA256.matcher(dependency.path("byte_sha256").asText()).matches()
                    || !SHA256.matcher(dependency.path("content_sha256").asText()).matches()) {
                throw new IllegalArgumentException("parallel plan worker dependency receipt is incomplete");
            }
        }
    }

    private static String profileEnvelopeHash(ObjectNode profile) {
        ObjectNode envelope = profile.deepCopy();
        envelope.remove("content_sha256");
        envelope.remove("profile_envelope_sha256");
        envelope.remove("qualification_receipt");
        return JsonHashes.ownHash(envelope);
    }

    private static void validateProfile(ObjectNode profile) {
        if (!PROFILE_SCHEMA.equals(profile.path("schema").asText())
                || profile.path("version").asInt(-1) != 1
                || !Set.of("ADMISSIBLE", "BLOCKED_RESOURCE").contains(profile.path("status").asText())
                || !profile.path("content_sha256").asText().equals(JsonHashes.ownHash(profile))
                || profile.path("target_cpu_ceiling").asInt(-1) != 28
                || profile.path("target_memory_bytes").asLong(-1) != 32L * GIB
                || profile.path("target_wall_minutes").asLong(-1) != 48L * 60L
                || profile.path("max_wall_minutes").asLong(-1) != 48L * 60L
                || profile.path("max_aggregate_rss_bytes").asLong(-1) != 26L * GIB
                || profile.path("coordinator_rss_reservation_bytes").asLong(-1) != 2L * GIB
                || profile.path("worker_rss_bytes").asLong(-1) != 3L * GIB
                || profile.path("worker_heap_bytes").asLong(-1) != 2L * GIB
                || profile.path("worker_cpu").asLong(-1) != WORKER_CPU
                || profile.path("max_workers").asInt(-1) != 8
                || profile.path("max_cpu").asInt(-1) != 24
                || profile.path("max_disk_bytes").asLong(-1) != 128L * GIB
                || profile.path("retry_limit_per_slot").asInt(-1) != MAX_RETRY
                || !profile.path("same_packaged_executor_required").asBoolean(false)
                || !profile.path("fixed_process_pool").asBoolean(false)
                || !profile.path("bounded_submission_queue").asBoolean(false)
                || profile.path("max_workers").asInt(0) <= 0 || profile.path("max_disk_bytes").asLong(0) <= 0
                || profile.path("effective_workers").asInt(-1) < 0
                || profile.path("effective_workers").asInt(0) > profile.path("max_workers").asInt(0)
                || profile.path("worker_heap_bytes").asLong(0) <= 0
                || profile.path("worker_rss_bytes").asLong(0) <= 0
                || profile.path("available_cpus").asLong(-1) < 0
                || profile.path("available_memory_bytes").asLong(-1) < 0
                || profile.path("available_free_space_bytes").asLong(-1) < 0
                || profile.path("max_aggregate_rss_bytes").asLong(0)
                        < profile.path("coordinator_rss_reservation_bytes").asLong(Long.MAX_VALUE)) {
            throw new IllegalArgumentException("execution profile is not self-bound");
        }
        int requestedWorkers = profile.path("requested_workers").asInt(-1);
        int localCap = profile.path("local_conservative_cap").asInt(-1);
        long availableCpu = profile.path("available_cpus").asLong(-1);
        long availableMemory = profile.path("available_memory_bytes").asLong(-1);
        long availableFree = profile.path("available_free_space_bytes").asLong(-1);
        boolean expectedTarget = availableCpu >= 28 && availableMemory >= 32L * GIB;
        long usableMemory = availableMemory > OS_MEMORY_RESERVE + 2L * GIB
                ? availableMemory - OS_MEMORY_RESERVE - 2L * GIB : 0L;
        int expectedMemoryWorkers = (int) Math.min(Integer.MAX_VALUE, usableMemory / (3L * GIB));
        int expectedCpuWorkers = (int) Math.min(Integer.MAX_VALUE, availableCpu / WORKER_CPU);
        int expectedWorkers = Math.max(0, Math.min(Math.min(Math.min(Math.min(requestedWorkers, 8), 12),
                expectedMemoryWorkers), expectedCpuWorkers));
        if (!expectedTarget) expectedWorkers = Math.min(expectedWorkers, Math.max(0, localCap));
        if (requestedWorkers <= 0 || requestedWorkers > 8 || localCap < 0 || localCap > 8
                || profile.path("target_qualified").asBoolean(false) != expectedTarget
                || profile.path("resource_probe_complete").asBoolean(false) != (availableCpu > 0 && availableMemory > 0 && availableFree > 0)
                || profile.path("effective_workers").asInt(-1) != expectedWorkers
                || (profile.path("status").asText().equals("ADMISSIBLE") != (expectedWorkers > 0))
                || (profile.path("qualified_for_confirmation").asBoolean(false)
                        && (!profile.path("target_qualified").asBoolean(false) || expectedWorkers <= 0))) {
            throw new IllegalArgumentException("execution profile admission fields are inconsistent");
        }
        String envelope = profile.path("profile_envelope_sha256").asText("");
        if (!SHA256.matcher(envelope).matches() || !envelope.equals(profileEnvelopeHash(profile))) {
            throw new IllegalArgumentException("execution profile envelope is not self-bound");
        }
        if (profile.path("qualified_for_confirmation").asBoolean(false)) {
            validateQualificationReceipt(profile);
        }
    }

    private static void validateQualificationReceipt(ObjectNode profile) {
        if (!profile.path("target_qualified").asBoolean(false)
                || profile.path("available_cpus").asLong(0) < 28
                || profile.path("available_memory_bytes").asLong(0) < 32L * GIB) {
            throw new IllegalArgumentException("qualification receipt is not bound to qualified hardware");
        }
        JsonNode value = profile.path("qualification_receipt");
        if (!value.isObject()) throw new IllegalArgumentException("qualified execution profile has no receipt");
        ObjectNode receipt = (ObjectNode) value;
        boolean correctedReceipt = CORRECTED_EVALUATOR.equals(profile.path("fixed_evaluator").asText());
        if (!"strategy-evaluator-operating-characteristics-qualification/1".equals(receipt.path("schema").asText())
                || receipt.path("version").asInt(-1) != 1
                || !"QUALIFIED".equals(receipt.path("status").asText())
                || !"FULL".equals(receipt.path("mode").asText())
                || !"DEVELOPMENT".equals(receipt.path("evidence_phase").asText())
                || !receipt.path("measured").asBoolean(false)
                || !receipt.path("heldout_seed_disjoint").asBoolean(false)
                || !receipt.path("development_seed_disjoint").asBoolean(false)
                || !SHA256.matcher(receipt.path("content_sha256").asText()).matches()
                || !receipt.path("content_sha256").asText().equals(JsonHashes.ownHash(receipt))
                || !receipt.path("execution_profile_envelope_sha256").asText()
                        .equals(profile.path("profile_envelope_sha256").asText())) {
            throw new IllegalArgumentException("qualification receipt does not prove a measured full development envelope");
        }
        if (correctedReceipt
                && (!CORRECTED_EVALUATOR.equals(receipt.path("corrected_evaluator_identity").asText())
                        || !StrategyFixedBaselinePortfolioCorrectionV1.ACCOUNTING_VERSION
                                .equals(receipt.path("accounting_version").asText())
                        || !receipt.path("serial_parallel_equivalent").asBoolean(false)
                        || receipt.path("serial_completed_raw_worker_refs").isMissingNode()
                        || receipt.path("serial_completed_slot_count").asInt(-1)
                                != receipt.path("serial_completed_raw_worker_refs").size()
                        || receipt.path("serial_completed_slot_count").asInt(-1)
                                != receipt.path("completed_slot_count").asInt(-2)
                        || !SHA256.matcher(receipt.path("parallel_resource_measurement_sha256").asText()).matches()
                        || !SHA256.matcher(receipt.path("serial_resource_measurement_sha256").asText()).matches()
                        || !SHA256.matcher(receipt.path("parallel_result_byte_sha256").asText()).matches()
                        || !SHA256.matcher(receipt.path("parallel_ledger_byte_sha256").asText()).matches()
                        || !SHA256.matcher(receipt.path("parallel_ledger_content_sha256").asText()).matches()
                        || !SHA256.matcher(receipt.path("serial_result_byte_sha256").asText()).matches()
                        || !SHA256.matcher(receipt.path("serial_ledger_byte_sha256").asText()).matches()
                        || !SHA256.matcher(receipt.path("serial_ledger_content_sha256").asText()).matches()
                        || receipt.path("parallel_result_relative_path").asText("").isBlank()
                        || receipt.path("parallel_ledger_relative_path").asText("").isBlank()
                        || receipt.path("serial_result_relative_path").asText("").isBlank()
                        || receipt.path("serial_ledger_relative_path").asText("").isBlank())) {
            throw new IllegalArgumentException("corrected qualification receipt lacks serial/parallel identity bindings");
        }
        ObjectNode geometry = object(receipt, "full_geometry");
        requireOwnHash(geometry, "qualification full geometry");
        if (geometry.path("cell_count").asInt(-1) != 4 || geometry.path("replications").asInt(-1) != 75
                || geometry.path("episodes_per_replication").asInt(-1) != 450
                || geometry.path("cluster_count").asInt(-1) != 288
                || geometry.path("paired_cluster_count").asInt(-1) != 162
                || geometry.path("lifecycle_series_per_replication").asInt(-1) != 900
                || geometry.path("horizon_minutes").asInt(-1) != 14_400) {
            throw new IllegalArgumentException("qualification receipt geometry is not the frozen FULL geometry");
        }
        ObjectNode developmentGeometry = object(receipt, "development_plan_geometry");
        requireOwnHash(developmentGeometry, "qualification development geometry");
        if (!sameFullGeometry(developmentGeometry)) {
            throw new IllegalArgumentException("qualification development plan geometry is not the frozen FULL geometry");
        }
        ObjectNode resource = object(receipt, "resource_envelope");
        requireOwnHash(resource, "qualification resource envelope");
        if (correctedReceipt
                && (!resource.path("parallel_resource_measurement_sha256").asText()
                        .equals(receipt.path("parallel_resource_measurement_sha256").asText())
                        || !resource.path("serial_resource_measurement_sha256").asText()
                                .equals(receipt.path("serial_resource_measurement_sha256").asText()))) {
            throw new IllegalArgumentException("corrected qualification resource envelope measurement binding is invalid");
        }
        if (!resourceEnvelopeMatches(resource, profile)) {
            throw new IllegalArgumentException("qualification receipt resource envelope differs from execution profile");
        }
        if (resource.path("available_cpus").asLong(-1) != profile.path("available_cpus").asLong(-2)
                || resource.path("available_memory_bytes").asLong(-1) != profile.path("available_memory_bytes").asLong(-2)
                || resource.path("measured_max_aggregate_rss_bytes").asLong(-1) <= 0
                || resource.path("measured_max_aggregate_rss_bytes").asLong(Long.MAX_VALUE)
                        > profile.path("max_aggregate_rss_bytes").asLong(0)
                || resource.path("measured_max_disk_bytes").asLong(-1) <= 0
                || resource.path("measured_max_disk_bytes").asLong(Long.MAX_VALUE)
                        > profile.path("max_disk_bytes").asLong(0)
                || resource.path("measured_wall_millis").asLong(-1) <= 0
                || resource.path("measured_wall_millis").asLong(Long.MAX_VALUE)
                        > profile.path("max_wall_minutes").asLong(0) * 60_000L) {
            throw new IllegalArgumentException("qualification receipt resource measurements exceed the frozen envelope");
        }
        for (String field : List.of("executor_identity_sha256", "executor_source_sha256", "statistical_plan_sha256")) {
            if (!SHA256.matcher(receipt.path(field).asText()).matches()) {
                throw new IllegalArgumentException("qualification receipt lacks " + field);
            }
        }
        if (!SHA256.matcher(receipt.path("development_plan_sha256").asText()).matches()) {
            throw new IllegalArgumentException("qualification receipt lacks the development plan binding");
        }
        JsonNode refs = receipt.path("completed_raw_worker_refs");
        int completed = receipt.path("completed_slot_count").asInt(-1);
        int expectedWave = correctedReceipt ? 4 * profile.path("effective_workers").asInt(0)
                : profile.path("effective_workers").asInt(1);
        if (!refs.isArray() || completed != expectedWave || refs.size() != completed) {
            throw new IllegalArgumentException("qualification receipt lacks a complete development worker wave");
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode ref : refs) {
            if (!ref.isObject() || !ids.add(ref.path("slot_id").asText())
                    || ref.path("slot_id").asText().isBlank()
                    || !"FULL".equals(ref.path("mode").asText())
                    || !SHA256.matcher(ref.path("plan_sha256").asText()).matches()
                    || !ref.path("scenario").isTextual()
                    || !ref.path("effect_size").isNumber()
                    || !ref.path("replication").isIntegralNumber()
                    || !ref.path("seed").isIntegralNumber()
                    || ref.path("relative_path").asText().isBlank()
                    || ref.path("bytes").asLong(0) <= 0
                    || !SHA256.matcher(ref.path("content_sha256").asText()).matches()
                    || !SHA256.matcher(ref.path("byte_sha256").asText()).matches()) {
                throw new IllegalArgumentException("qualification receipt has an invalid raw worker reference");
            }
        }
        if (correctedReceipt) {
            Set<String> serialIds = new HashSet<>();
            for (JsonNode ref : receipt.path("serial_completed_raw_worker_refs")) {
                if (!ref.isObject() || !serialIds.add(ref.path("slot_id").asText())
                        || !"FULL".equals(ref.path("mode").asText())
                        || !SHA256.matcher(ref.path("plan_sha256").asText()).matches()
                        || ref.path("bytes").asLong(0) <= 0
                        || !SHA256.matcher(ref.path("content_sha256").asText()).matches()
                        || !SHA256.matcher(ref.path("byte_sha256").asText()).matches()) {
                    throw new IllegalArgumentException("corrected qualification receipt has an invalid serial worker reference");
                }
            }
            if (serialIds.size() != expectedWave) {
                throw new IllegalArgumentException("corrected qualification receipt lacks a complete serial worker wave");
            }
        }
    }

    private static void validateQualificationAgainstPlan(ObjectNode profile, ObjectNode plan) {
        ObjectNode receipt = object(profile, "qualification_receipt");
        String planSource = plan.path("executor_build_input_fingerprint").asText("");
        if (!receipt.path("executor_identity_sha256").asText().equals(plan.path("executor_identity_sha256").asText())
                || !receipt.path("executor_source_sha256").asText().equals(planSource)
                || !receipt.path("statistical_plan_sha256").asText().equals(plan.path("statistical_plan_sha256").asText())) {
            throw new IllegalArgumentException("qualification receipt is bound to a different executor or statistical plan");
        }
        validateFullGeometry(plan);
        if (CORRECTED_PLAN_SCHEMA.equals(plan.path("schema").asText())) {
            validateCorrectedQualificationRefs(receipt, plan);
            return;
        }
        Set<Long> heldoutSeeds = new HashSet<>();
        for (JsonNode cell : plan.path("cells")) for (JsonNode seed : cell.path("seeds")) {
            if (seed.isIntegralNumber()) heldoutSeeds.add(seed.asLong());
        }
        Set<String> developmentSlots = new HashSet<>();
        String developmentPlan = receipt.path("development_plan_sha256").asText();
        for (JsonNode ref : receipt.path("completed_raw_worker_refs")) {
            String scenario = ref.path("scenario").asText("");
            double effect = ref.path("effect_size").asDouble(Double.NaN);
            int replication = ref.path("replication").asInt(-1);
            long seed = ref.path("seed").asLong(Long.MIN_VALUE);
            if (!heldoutSeeds.add(seed) || !Double.isFinite(effect) || replication < 0) {
                throw new IllegalArgumentException("qualification development seeds overlap the frozen FULL inventory");
            }
            Slot developmentSlot = new Slot(developmentPlan, "FULL", scenario,
                    effect, replication, seed, developmentSlots.size());
            if (!developmentSlots.add(developmentSlot.id())
                    || !developmentSlot.id().equals(ref.path("slot_id").asText())
                    || !developmentPlan.equals(ref.path("plan_sha256").asText())) {
                throw new IllegalArgumentException("qualification development slot identity is invalid");
            }
            Path artifact = PathConfinement.resolve(RepositoryLayout.locate(), ref.path("relative_path").asText(),
                    "qualification raw worker reference", PathConfinement.ExpectedType.FILE).absolute();
            ObjectNode value = readObject(artifact, "qualification raw worker artifact");
            try {
                if (Files.size(artifact) != ref.path("bytes").asLong(-1)
                        || !JsonHashes.sha256(artifact).equals(ref.path("byte_sha256").asText())
                        || !value.path("content_sha256").asText().equals(ref.path("content_sha256").asText())) {
                    throw new IllegalArgumentException("qualification raw worker reference hash mismatch");
                }
            } catch (IOException error) {
                throw new IllegalArgumentException("cannot inspect qualification raw worker artifact", error);
            }
            verifyArtifact(value, artifact, developmentSlot, plan, true);
            if (!"COMPLETE".equals(value.path("status").asText())) {
                throw new IllegalArgumentException("qualification raw worker artifact is incomplete");
            }
            ObjectNode row = (ObjectNode) value.path("row");
            if (row.path("event_count").asInt(-1) != 450
                    || row.path("paired_count").asInt(-1) != 450
                    || row.path("independent_units").asInt(-1) != 288
                    || !"COMPLETE".equals(row.path("status").asText())) {
                throw new IllegalArgumentException("qualification raw worker artifact does not prove FULL generator geometry");
            }
            if (CORRECTED_PLAN_SCHEMA.equals(plan.path("schema").asText())) {
                validateCorrectedQualificationPortfolio(row);
            } else {
                validateQualificationPortfolio(row);
            }
        }
    }

    private static void validateCorrectedQualificationRefs(ObjectNode receipt, ObjectNode plan) {
        String developmentPlan = receipt.path("development_plan_sha256").asText("");
        if (!developmentPlan.equals(plan.path("content_sha256").asText())) {
            throw new IllegalArgumentException("corrected qualification receipt is bound to a different development plan");
        }
        List<Slot> expected = slots(plan, "FULL");
        validateCorrectedQualificationRefSet(receipt.path("completed_raw_worker_refs"), expected,
                "parallel");
        validateCorrectedQualificationRefSet(receipt.path("serial_completed_raw_worker_refs"), expected,
                "serial");
    }

    private static void validateCorrectedQualificationRefSet(JsonNode refs, List<Slot> expected,
            String label) {
        if (!refs.isArray() || refs.size() != expected.size()) {
            throw new IllegalArgumentException("corrected qualification receipt lacks the complete " + label + " worker wave");
        }
        Map<String, Slot> expectedById = new HashMap<>();
        for (Slot slot : expected) expectedById.put(slot.id(), slot);
        Set<String> seen = new HashSet<>();
        for (JsonNode ref : refs) {
            String id = ref.path("slot_id").asText("");
            Slot slot = expectedById.get(id);
            if (!ref.isObject() || slot == null || !seen.add(id)
                    || !"FULL".equals(ref.path("mode").asText())
                    || !slot.planSha256().equals(ref.path("plan_sha256").asText())
                    || !slot.scenario().equals(ref.path("scenario").asText())
                    || Double.compare(slot.effectSize(), ref.path("effect_size").asDouble(Double.NaN)) != 0
                    || slot.replication() != ref.path("replication").asInt(-1)
                    || slot.seed() != ref.path("seed").asLong(Long.MIN_VALUE)
                    || ref.path("relative_path").asText("").isBlank()) {
                throw new IllegalArgumentException("corrected qualification " + label + " worker reference identity is invalid");
            }
        }
        if (seen.size() != expected.size()) {
            throw new IllegalArgumentException("corrected qualification " + label + " worker wave is incomplete");
        }
    }

    /** Reopens both corrected books and checks their independent terminal invariants. */
    private static void validateCorrectedQualificationPortfolio(ObjectNode row) {
        validateCorrectedQualificationPortfolio(row, true);
    }

    private static void validateCorrectedQualificationPortfolio(ObjectNode row, boolean requireFullEvidence) {
        JsonNode rawNode = row.path("raw_evaluator_result");
        if (requireFullEvidence && rawNode.isObject()) {
            validateCorrectedFullGeneratorEvidence((ObjectNode) rawNode);
        }
        JsonNode portfolio = row.path("portfolio_summary");
        if (!portfolio.isObject()
                || !StrategyFixedBaselinePortfolioCorrectionV1.ACCOUNTING_VERSION
                        .equals(portfolio.path("accounting_version").asText())
                || !portfolio.path("event_book").isObject()
                || !portfolio.path("control_book").isObject()) {
            throw new IllegalArgumentException("corrected qualification portfolio books are incomplete");
        }
        validateCorrectedBook((ObjectNode) portfolio.path("event_book"));
        validateCorrectedBook((ObjectNode) portfolio.path("control_book"));
        verifyCorrectionProjection((ObjectNode) portfolio.path("event_book"), "event_book");
        verifyCorrectionProjection((ObjectNode) portfolio.path("control_book"), "control_book");
        ObjectNode recomputedPortfolio = StrategyFixedBaselineCorrectedV1.correctedPortfolioForValidation(portfolio);
        for (String field : List.of("starting_equity_usdt", "ending_equity_usdt", "net_pnl_usdt",
                "gross_pnl_usdt", "fees_usdt", "slippage_usdt", "capacity_debit_usdt",
                "realized_pnl_usdt", "max_drawdown_usdt", "final_marked_holdings_usdt",
                "final_active_position_count", "final_curve_equity_usdt")) {
            if (!closeEnough(portfolio.path(field).asDouble(Double.NaN),
                    recomputedPortfolio.path(field).asDouble(Double.NaN))) {
                throw new IllegalArgumentException("corrected portfolio aggregate differs from an independent correction replay");
            }
        }
        if (!JsonHashes.canonicalSha256(portfolio.path("combined_equity_curve"))
                .equals(JsonHashes.canonicalSha256(recomputedPortfolio.path("combined_equity_curve")))
                || !JsonHashes.canonicalSha256(portfolio.path("corrected_metrics"))
                        .equals(JsonHashes.canonicalSha256(recomputedPortfolio.path("corrected_metrics")))) {
            throw new IllegalArgumentException("corrected portfolio aggregate projection differs from an independent correction replay");
        }
        double eventStart = portfolio.path("event_book").path("starting_equity_usdt").asDouble(Double.NaN);
        double controlStart = portfolio.path("control_book").path("starting_equity_usdt").asDouble(Double.NaN);
        double eventNet = portfolio.path("event_book").path("net_pnl_usdt").asDouble(Double.NaN);
        double controlNet = portfolio.path("control_book").path("net_pnl_usdt").asDouble(Double.NaN);
        double combinedStart = portfolio.path("starting_equity_usdt").asDouble(Double.NaN);
        double combinedEnd = portfolio.path("ending_equity_usdt").asDouble(Double.NaN);
        double combinedNet = portfolio.path("net_pnl_usdt").asDouble(Double.NaN);
        if (!closeEnough(combinedStart, eventStart + controlStart)
                || !closeEnough(combinedNet, eventNet + controlNet)
                || !closeEnough(combinedEnd, combinedStart + combinedNet)
                || !portfolio.path("ending_minus_starting_equals_net").asBoolean(false)
                || !portfolio.path("final_curve_equity_reconciles_cash_plus_marked_holdings").asBoolean(false)) {
            throw new IllegalArgumentException("corrected qualification portfolio aggregate does not reconcile");
        }
    }

    /**
     * A copied counter cannot certify the frozen FULL workload.  Require the
     * evaluator's retained setup/control inventories, independent cluster
     * projection, and matching metrics alongside the compact row counters.
     */
    private static void validateCorrectedFullGeneratorEvidence(ObjectNode raw) {
        if (raw.path("event_count").asInt(-1) != 450
                || raw.path("matched_control_count").asInt(-1) != 450
                || raw.path("admitted_event_count").asInt(-1) != 450
                || raw.path("trade_count").asInt(-1) != 900
                || raw.path("market_episode_count").asInt(-1) != 450
                || raw.path("independent_market_episode_count").asInt(-1) != 288
                || !raw.path("setup_events").isArray() || raw.path("setup_events").size() != 450
                || !raw.path("control_selections").isArray() || raw.path("control_selections").size() != 450
                || !raw.path("independent_market_episodes").isArray()
                || raw.path("independent_market_episodes").size() != 288
                || !raw.path("attempts").isArray() || raw.path("attempts").size() != 450
                || !raw.path("matching_attrition").isObject()) {
            throw new IllegalArgumentException("corrected raw evaluator does not prove the frozen FULL generator geometry");
        }
        JsonNode portfolio = raw.path("portfolio");
        if (!portfolio.isObject()
                || !portfolio.path("event_book").path("trades").isArray()
                || portfolio.path("event_book").path("trades").size() != 450
                || !portfolio.path("control_book").path("trades").isArray()
                || portfolio.path("control_book").path("trades").size() != 450) {
            throw new IllegalArgumentException("corrected raw evaluator does not retain the complete event/control trade books");
        }
        Set<String> eventTradeIds = tradeIds(portfolio.path("event_book").path("trades"), "event book");
        Set<String> controlTradeIds = tradeIds(portfolio.path("control_book").path("trades"), "control book");
        Set<String> attemptIds = new HashSet<>();
        Set<String> usedEventTradeIds = new HashSet<>();
        Set<String> usedControlTradeIds = new HashSet<>();
        for (JsonNode attempt : raw.path("attempts")) {
            if (!attempt.isObject() || !"COMPLETE".equals(attempt.path("status").asText())
                    || !attempt.path("event_trade").isObject() || !attempt.path("control_trade").isObject()) {
                throw new IllegalArgumentException("corrected raw evaluator does not prove 450 complete paired lifecycles");
            }
            String id = attempt.path("event_id").asText("");
            String eventId = tradeId(attempt.path("event_trade"));
            String controlId = tradeId(attempt.path("control_trade"));
            if (id.isBlank() || !attemptIds.add(id) || !eventTradeIds.contains(eventId)
                    || !controlTradeIds.contains(controlId) || !usedEventTradeIds.add(eventId)
                    || !usedControlTradeIds.add(controlId)) {
                throw new IllegalArgumentException("corrected raw evaluator lifecycle trade binding is incomplete");
            }
        }
        JsonNode metrics = raw.path("metrics");
        if (!metrics.isObject()
                || metrics.path("event_tested_cluster_count").asInt(-1) != 288
                || metrics.path("paired_tested_cluster_count").asInt(-1) != 288
                || metrics.path("independent_market_episode_count").asInt(-1) != 288
                || metrics.path("paired_count").asInt(-1) != 450) {
            throw new IllegalArgumentException("corrected raw evaluator does not prove FULL cluster/PIT geometry");
        }
        JsonNode attrition = raw.path("matching_attrition");
        JsonNode sequential = attrition.path("sequential_attrition");
        if (!"strategy-matching-attrition/1".equals(attrition.path("schema").asText())
                || !attrition.path("outcome_blind").asBoolean(false)
                || sequential.path("feature_rows_to_setup_events").asInt(-1) != 450
                || sequential.path("setup_events_to_admitted_events").asInt(-1) != 450
                || sequential.path("admitted_events_to_control_selections").asInt(-1) != 450
                || sequential.path("control_selections_to_matched_controls").asInt(-1) != 450
                || sequential.path("complete_pairs_to_paired_clusters").asInt(-1) != 288
                || sequential.path("admitted_events_to_event_clusters").asInt(-1) != 288
                || attrition.path("marginal_attrition").path("merged_scheduled_lifecycle_clusters").asInt(-1) != 288) {
            throw new IllegalArgumentException("corrected raw evaluator does not prove FULL matching/PIT attrition geometry");
        }
        validateFullSourceClusterGeometry(raw.path("independent_market_episodes"));
        requireUniqueEvidenceIds(raw.path("setup_events"), "setup_events");
        requireUniqueEvidenceIds(raw.path("control_selections"), "control_selections");
        requireUniqueEvidenceIds(raw.path("independent_market_episodes"), "independent_market_episodes");
    }

    /**
     * The statistical uncertainty unit is the 288 merged lifecycle clusters.
     * The frozen plan's 162 value describes the separate physical geometry:
     * 162 double-source clusters plus 126 singleton clusters produce the 450
     * event series.  Keep both facts explicit so one cannot be substituted for
     * the other in a qualification artifact.
     */
    private static void validateFullSourceClusterGeometry(JsonNode clusters) {
        int singleton = 0;
        int doubleSource = 0;
        int sourceCount = 0;
        Set<String> sourceIds = new HashSet<>();
        for (JsonNode cluster : clusters) {
            JsonNode sources = cluster.path("source_episode_ids");
            if (!sources.isArray() || sources.size() < 1 || sources.size() > 2) {
                throw new IllegalArgumentException("corrected raw cluster source geometry is invalid");
            }
            for (JsonNode source : sources) {
                if (!source.isTextual() || source.asText().isBlank() || !sourceIds.add(source.asText())) {
                    throw new IllegalArgumentException("corrected raw cluster source IDs are not unique");
                }
            }
            sourceCount += sources.size();
            if (sources.size() == 1) singleton++;
            else doubleSource++;
        }
        if (singleton != 126 || doubleSource != 162 || sourceCount != 450) {
            throw new IllegalArgumentException("corrected raw cluster source geometry is not 126 singleton plus 162 double-source clusters");
        }
    }

    private static Set<String> tradeIds(JsonNode trades, String label) {
        Set<String> ids = new HashSet<>();
        for (JsonNode trade : trades) {
            String id = tradeId(trade);
            if (id.isBlank() || !ids.add(id)) {
                throw new IllegalArgumentException("corrected raw " + label + " trade inventory is not unique");
            }
            if (!trade.path("lifecycle").path("exits").isArray()
                    || trade.path("lifecycle").path("exits").size() != 1) {
                throw new IllegalArgumentException("corrected raw " + label + " has an incomplete lifecycle trade");
            }
        }
        return ids;
    }

    private static String tradeId(JsonNode trade) {
        return trade.path("episode_id").asText(trade.path("id").asText(trade.path("event_id").asText("")));
    }

    private static void requireUniqueEvidenceIds(JsonNode values, String label) {
        Set<String> ids = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isObject()) throw new IllegalArgumentException("corrected raw " + label + " contains a non-object row");
            String id = value.path("id").asText(value.path("event_id").asText(
                    value.path("episode_id").asText(value.path("market_episode_id").asText(""))));
            if (id.isBlank() || !ids.add(id)) {
                throw new IllegalArgumentException("corrected raw " + label + " inventory is not unique");
            }
        }
    }

    private static void verifyCorrectionProjection(ObjectNode book, String label) {
        ObjectNode recomputed = StrategyFixedBaselinePortfolioCorrectionV1.correctLegacyBook(book);
        for (String field : List.of("starting_equity_usdt", "ending_equity_usdt", "net_pnl_usdt",
                "gross_pnl_usdt", "fees_usdt", "slippage_usdt", "capacity_debit_usdt",
                "realized_pnl_usdt", "max_drawdown_usdt", "final_marked_holdings_usdt",
                "final_active_position_count")) {
            if (!closeEnough(book.path(field).asDouble(Double.NaN), recomputed.path(field).asDouble(Double.NaN))) {
                throw new IllegalArgumentException("corrected " + label + " differs from an independent correction replay");
            }
        }
        if (!JsonHashes.canonicalSha256(book.path("trades")).equals(JsonHashes.canonicalSha256(recomputed.path("trades")))
                || !JsonHashes.canonicalSha256(book.path("equity_curve"))
                        .equals(JsonHashes.canonicalSha256(recomputed.path("equity_curve")))) {
            throw new IllegalArgumentException("corrected " + label + " trace differs from an independent correction replay");
        }
    }

    private static void validateCorrectedBook(ObjectNode book) {
        double finalMarked = book.path("final_marked_holdings_usdt").asDouble(Double.NaN);
        if (!StrategyFixedBaselinePortfolioCorrectionV1.ACCOUNTING_VERSION
                .equals(book.path("accounting_version").asText())
                || !book.path("trades").isArray()
                || !book.path("equity_curve").isArray()
                || book.path("final_active_position_count").asInt(-1) != 0
                || !Double.isFinite(finalMarked) || Math.abs(finalMarked) > 1e-8
                || !book.path("ending_minus_starting_equals_net").asBoolean(false)
                || !book.path("sum_check").asBoolean(false)) {
            throw new IllegalArgumentException("corrected qualification book terminal invariant failed");
        }
        double start = book.path("starting_equity_usdt").asDouble(Double.NaN);
        double end = book.path("ending_equity_usdt").asDouble(Double.NaN);
        double net = book.path("net_pnl_usdt").asDouble(Double.NaN);
        if (!Double.isFinite(start) || !Double.isFinite(end) || !Double.isFinite(net)
                || book.path("trade_count").asInt(-1) != book.path("trades").size()
                || !closeEnough(end, start + net)) {
            throw new IllegalArgumentException("corrected qualification book equity does not reconcile");
        }
        Set<String> exited = new HashSet<>();
        for (JsonNode trade : book.path("trades")) {
            if (!trade.isObject()) throw new IllegalArgumentException("corrected qualification trade is not an object");
            String id = trade.path("episode_id").asText(trade.path("signal_id").asText(""));
            ObjectNode lifecycle = object(trade, "lifecycle");
            JsonNode exits = lifecycle.path("exits");
            if (id.isBlank() || !exits.isArray() || exits.size() != 1 || !exited.add(id)) {
                throw new IllegalArgumentException("corrected qualification book has an active or duplicate trade");
            }
            Instant entry = parseQualificationInstant(lifecycle.path("entry_time").asText(""));
            JsonNode exitNode = exits.get(0);
            Instant exit = parseQualificationInstant(exitAvailabilityText(exitNode));
            if (!entry.isBefore(exit)) throw new IllegalArgumentException("corrected qualification lifecycle ordering is invalid");
            JsonNode marks = trade.path("portfolio_mark_points");
            if (!marks.isArray()) throw new IllegalArgumentException("corrected qualification marks are not an array");
            for (JsonNode mark : marks) {
                Instant markTime = parseQualificationInstant(mark.path("time").asText(""));
                if (markTime.isBefore(entry) || !markTime.isBefore(exit)) {
                    throw new IllegalArgumentException("corrected qualification mark is outside its active interval");
                }
            }
        }
        if (exited.size() != book.path("trade_count").asInt(-1)) {
            throw new IllegalArgumentException("corrected qualification book trade count does not reconcile");
        }
        JsonNode curve = book.path("equity_curve");
        if (curve.isEmpty()) {
            if (book.path("trade_count").asInt(-1) != 0 || !closeEnough(start, end)) {
                throw new IllegalArgumentException("empty corrected book does not remain at starting capital");
            }
            return;
        }
        Instant priorTime = null;
        for (JsonNode point : curve) {
            if (!point.isObject()) throw new IllegalArgumentException("corrected qualification curve point is not an object");
            Instant time = parseQualificationInstant(point.path("event_time").asText(""));
            if (priorTime != null && time.isBefore(priorTime)) {
                throw new IllegalArgumentException("corrected qualification curve is not chronological");
            }
            priorTime = time;
            double pointCash = point.path("cash_usdt").asDouble(Double.NaN);
            double pointMarked = point.path("marked_holdings_usdt").asDouble(Double.NaN);
            double pointEquity = point.path("equity_usdt").asDouble(Double.NaN);
            if (!Double.isFinite(pointCash) || !Double.isFinite(pointMarked) || !Double.isFinite(pointEquity)
                    || point.path("active_position_count").asInt(-1) < 0
                    || !closeEnough(pointEquity, pointCash + pointMarked)) {
                throw new IllegalArgumentException("corrected qualification curve does not reconcile cash and marks");
            }
        }
        JsonNode last = curve.get(curve.size() - 1);
        double lastMarked = last.path("marked_holdings_usdt").asDouble(Double.NaN);
        double lastEquity = last.path("equity_usdt").asDouble(Double.NaN);
        double lastCash = last.path("cash_usdt").asDouble(Double.NaN);
        if (!"EXIT".equals(last.path("event_type").asText())
                || last.path("active_position_count").asInt(-1) != 0
                || !Double.isFinite(lastMarked) || !Double.isFinite(lastEquity) || !Double.isFinite(lastCash)
                || Math.abs(lastMarked) > 1e-8
                || !closeEnough(lastEquity, end)
                || !closeEnough(lastEquity, lastCash + lastMarked)) {
            throw new IllegalArgumentException("corrected qualification book curve retains residual holdings");
        }
    }

    /**
     * A qualification receipt cannot certify a portfolio trace that leaves a
     * closed position active or applies a mark at/after its exit. This guard is
     * deliberately external to the frozen evaluator; it only decides whether
     * a development run is eligible to qualify the execution envelope.
     */
    private static void validateQualificationPortfolio(ObjectNode row) {
        JsonNode portfolio = row.path("portfolio_summary");
        if (!portfolio.isObject() || !portfolio.path("trades").isArray()
                || !portfolio.path("equity_curve").isArray() || portfolio.path("equity_curve").isEmpty()) {
            throw new IllegalArgumentException("qualification portfolio trace is incomplete");
        }
        Set<String> exited = new HashSet<>();
        for (JsonNode trade : portfolio.path("trades")) {
            String id = trade.path("episode_id").asText(trade.path("id").asText(""));
            ObjectNode lifecycle = object(trade, "lifecycle");
            JsonNode exits = lifecycle.path("exits");
            if (id.isBlank() || !exits.isArray() || exits.isEmpty()) {
                throw new IllegalArgumentException("qualification portfolio has an active or unexited trade");
            }
            Instant entry = parseQualificationInstant(lifecycle.path("entry_time").asText(""));
            JsonNode exitNode = exits.get(exits.size() - 1);
            Instant exit = parseQualificationInstant(exitAvailabilityText(exitNode));
            if (!exited.add(id) || !entry.isBefore(exit)) {
                throw new IllegalArgumentException("qualification portfolio lifecycle ordering is invalid");
            }
            for (JsonNode mark : trade.path("portfolio_mark_points")) {
                Instant markTime = parseQualificationInstant(mark.path("time").asText(""));
                if (markTime.isBefore(entry) || !markTime.isBefore(exit)) {
                    throw new IllegalArgumentException("qualification portfolio marks a position outside its active interval");
                }
            }
        }
        JsonNode curve = portfolio.path("equity_curve");
        JsonNode last = curve.get(curve.size() - 1);
        double curveEquity = last.path("equity_usdt").asDouble(Double.NaN);
        double curveCash = last.path("cash_usdt").asDouble(Double.NaN);
        double curveHoldings = last.path("holdings_at_entry_cost_usdt").asDouble(Double.NaN);
        double declaredEnding = portfolio.path("ending_equity_usdt").asDouble(Double.NaN);
        if (!Double.isFinite(curveEquity) || !Double.isFinite(curveCash) || !Double.isFinite(curveHoldings)
                || !Double.isFinite(declaredEnding)
                || !closeEnough(curveEquity, declaredEnding)
                || !closeEnough(curveEquity, curveCash + curveHoldings)
                || !portfolio.path("ending_minus_starting_equals_net").asBoolean(false)
                || !portfolio.path("sum_check").asBoolean(false)) {
            throw new IllegalArgumentException("qualification portfolio equity curve does not reconcile");
        }
        String lastType = last.path("event_type").asText("");
        if (!"EXIT".equals(lastType) || exited.size() != portfolio.path("trade_count").asInt(-1)) {
            throw new IllegalArgumentException("qualification portfolio retains residual active positions");
        }
    }

    private static String exitAvailabilityText(JsonNode exit) {
        String availability = exit.path("availability_time").asText("");
        return availability.isBlank() ? exit.path("time").asText("") : availability;
    }

    private static Instant parseQualificationInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("qualification portfolio has an invalid timestamp", error);
        }
    }

    private static boolean closeEnough(double left, double right) {
        return Math.abs(left - right) <= 1e-6 * Math.max(1D, Math.max(Math.abs(left), Math.abs(right)));
    }

    static void validateQualificationPortfolioForTest(ObjectNode row) {
        validateQualificationPortfolio(row);
    }

    static void validateQualificationProfileForTest(ObjectNode profile) {
        validateProfile(profile);
    }

    /** Production qualification always uses the live coordinator probe. */
    static ResourceProbe liveResourceProbeForQualification() {
        return LIVE_RESOURCE_PROBE;
    }

    static void validateQualificationAgainstPlanForTest(ObjectNode profile, ObjectNode plan) {
        validateQualificationAgainstPlan(profile, plan);
    }

    /**
     * Reopens a completed corrected DEVELOPMENT result and all of its durable
     * worker artifacts.  The returned references are the only input accepted
     * by the qualification receipt builder; caller supplied summary rows are
     * never trusted.
     */
    static ArrayNode validateCorrectedDevelopmentArtifacts(Path resultPath, Path ledgerPath,
            ObjectNode plan, ObjectNode profile) {
        return validateCorrectedDevelopmentArtifacts(resultPath, ledgerPath, plan, profile, true);
    }

    /** Package seam for custody portability tests; raw evaluator bindings stay strict. */
    static ArrayNode validateCorrectedDevelopmentArtifactsForTest(Path resultPath, Path ledgerPath,
            ObjectNode plan, ObjectNode profile) {
        return validateCorrectedDevelopmentArtifacts(resultPath, ledgerPath, plan, profile, false);
    }

    /**
     * Package seam for the PREFIX integration contract.  PREFIX artifacts
     * still require a corrected accounting replay, but they must not be
     * mistaken for the retained FULL geometry.  This forwards to the exact
     * production artifact validator so the test cannot certify a weaker copy.
     */
    static void validateCorrectedArtifactForTest(ObjectNode artifact, Path path, Slot slot,
            ObjectNode plan, String expectedRunId) {
        // This seam only relaxes filesystem custody for portable tests.  The
        // corrected raw-result schema, evaluator receipt, and accounting
        // replay remain under the same strict semantic validator as a
        // packaged worker artifact.
        verifyArtifact(artifact, path, slot, plan, true, expectedRunId);
    }

    private static ArrayNode validateCorrectedDevelopmentArtifacts(Path resultPath, Path ledgerPath,
            ObjectNode plan, ObjectNode profile, boolean enforceCustody) {
        validateCorrectedPlanForPreflight(plan);
        if (!profile.path("content_sha256").asText().equals(plan.path("execution_profile_sha256").asText())) {
            throw new IllegalArgumentException("corrected development result profile differs from plan");
        }
        ObjectNode ledger = readObject(ledgerPath.toAbsolutePath().normalize(), "corrected development ledger");
        List<Slot> ledgerSlots = slots(plan, "FULL");
        validateLedger(ledger, plan, profile, "FULL", ledgerSlots,
                ledgerPath.toAbsolutePath().normalize(), enforceCustody);
        String runId = ledger.path("run_id").asText("");
        ObjectNode result = readObject(resultPath.toAbsolutePath().normalize(), "corrected development result");
        if (!CORRECTED_RESULT_SCHEMA.equals(result.path("schema").asText())
                || !"FULL".equals(result.path("mode").asText())
                || !"COMPLETE".equals(result.path("status").asText())
                || !result.path("plan_sha256").asText().equals(plan.path("content_sha256").asText())
                || !result.path("profile_sha256").asText().equals(profile.path("content_sha256").asText())
                || !CORRECTED_EVALUATOR.equals(result.path("fixed_evaluator").asText())
                || !StrategyFixedBaselinePortfolioCorrectionV1.ACCOUNTING_VERSION
                        .equals(result.path("accounting_version").asText())
                || result.path("missing_slots").asInt(-1) != 0
                || result.path("planned_slots").asInt(-1) != result.path("completed_slots").asInt(-2)
                || !result.path("result_refs").isArray()
                || !result.path("content_sha256").asText().equals(JsonHashes.ownHash(result))) {
            throw new IllegalArgumentException("corrected development result is incomplete or unbound");
        }
        List<Slot> planned = slots(plan, "FULL");
        if (plan.path("development_worker_count").asInt(-1) != profile.path("effective_workers").asInt(-2)
                || result.path("planned_slots").asInt(-1) != planned.size()
                || planned.size() != 4 * profile.path("effective_workers").asInt(0)) {
            throw new IllegalArgumentException("corrected development result does not contain one full wave per cell");
        }
        Map<String, Slot> slotsById = new HashMap<>();
        for (Slot slot : planned) slotsById.put(slot.id(), slot);
        Set<String> seen = new HashSet<>();
        ArrayNode refs = JsonHashes.mapper().createArrayNode();
        Path ledgerRoot = ledgerPath.toAbsolutePath().normalize().getParent();
        if (ledgerRoot == null) ledgerRoot = Path.of(".").toAbsolutePath().normalize();
        for (JsonNode rawRef : result.path("result_refs")) {
            String id = rawRef.path("slot_id").asText("");
            Slot slot = slotsById.get(id);
            if (slot == null || !seen.add(id) || !"COMPLETE".equals(rawRef.path("status").asText())) {
                throw new IllegalArgumentException("corrected development result contains an unknown or duplicate slot");
            }
            Path artifact = PathConfinement.resolve(ledgerRoot, rawRef.path("relative_path").asText(""),
                    "corrected development worker artifact", PathConfinement.ExpectedType.FILE).absolute();
            ObjectNode value = readObject(artifact, "corrected development worker artifact");
            verifyArtifact(value, artifact, slot, plan, true, runId);
            try {
                if (rawRef.path("bytes").asLong(-1) != Files.size(artifact)
                        || !rawRef.path("byte_sha256").asText().equals(JsonHashes.sha256(artifact))
                        || !rawRef.path("content_sha256").asText().equals(value.path("content_sha256").asText())) {
                    throw new IllegalArgumentException("corrected development result reference hash mismatch");
                }
            } catch (IOException error) {
                throw new IllegalArgumentException("cannot inspect corrected development artifact", error);
            }
            ObjectNode row = (ObjectNode) value.path("row");
            if (!"COMPLETE".equals(row.path("status").asText())
                    || row.path("event_count").asInt(-1) != 450
                    || row.path("paired_count").asInt(-1) != 450
                    || row.path("independent_units").asInt(-1) != 288) {
                throw new IllegalArgumentException("corrected development artifact does not prove full generator geometry");
            }
            validateCorrectedQualificationPortfolio(row);
            String portable = value.path("portable_economic_sha256").asText("");
            if (!SHA256.matcher(portable).matches()) {
                throw new IllegalArgumentException("corrected development artifact has no economic digest");
            }
            ObjectNode enriched = (ObjectNode) rawRef.deepCopy();
            enriched.put("scenario", slot.scenario()).put("effect_size", slot.effectSize())
                    .put("replication", slot.replication()).put("seed", slot.seed())
                    .put("mode", slot.mode()).put("plan_sha256", slot.planSha256())
                    .put("portable_economic_sha256", portable);
            refs.add(enriched);
        }
        if (seen.size() != planned.size()) throw new IllegalArgumentException("corrected development result omits worker artifacts");
        return refs;
    }

    /**
     * Reopens the coordinator-owned measurement for one complete run.  This
     * is deliberately separate from the caller's resource evidence so a
     * positive number in a CLI JSON file can never mint a qualification.
     */
    static ObjectNode validateCorrectedRunMeasurement(Path resultPath, Path ledgerPath,
            ObjectNode plan, ObjectNode profile, int expectedWorkers) {
        return validateCorrectedRunMeasurement(resultPath, ledgerPath, plan, profile, expectedWorkers, true);
    }

    /** Package seam for self-contained custody portability tests. */
    static ObjectNode validateCorrectedRunMeasurementForTest(Path resultPath, Path ledgerPath,
            ObjectNode plan, ObjectNode profile, int expectedWorkers) {
        return validateCorrectedRunMeasurement(resultPath, ledgerPath, plan, profile, expectedWorkers, false);
    }

    private static ObjectNode validateCorrectedRunMeasurement(Path resultPath, Path ledgerPath,
            ObjectNode plan, ObjectNode profile, int expectedWorkers, boolean enforceCustody) {
        ObjectNode result = readObject(resultPath.toAbsolutePath().normalize(), "corrected run result");
        ObjectNode ledger = readObject(ledgerPath.toAbsolutePath().normalize(), "corrected run ledger");
        List<Slot> planned = slots(plan, "FULL");
        validateLedger(ledger, plan, profile, "FULL", planned, ledgerPath.toAbsolutePath().normalize(), enforceCustody);
        if (!CORRECTED_RESULT_SCHEMA.equals(result.path("schema").asText())
                || !"COMPLETE".equals(result.path("status").asText())
                || !result.path("content_sha256").asText().equals(JsonHashes.ownHash(result))
                || !result.path("plan_sha256").asText().equals(plan.path("content_sha256").asText())
                || !result.path("profile_sha256").asText().equals(profile.path("content_sha256").asText())
                || !result.path("ledger_content_sha256").asText().equals(ledger.path("content_sha256").asText())
                || result.path("effective_workers").asInt(-1) != profile.path("effective_workers").asInt(-2)
                || result.path("requested_workers").asInt(-1) != expectedWorkers) {
            throw new IllegalArgumentException("corrected run result is not bound to its ledger and worker count");
        }
        JsonNode measurementNode = result.path("resource_measurement");
        if (!measurementNode.isObject()) {
            throw new IllegalArgumentException("corrected run has no coordinator-owned resource measurement");
        }
        ObjectNode measurement = (ObjectNode) measurementNode;
        if (!"strategy-evaluator-resource-measurement/1".equals(measurement.path("schema").asText())
                || measurement.path("version").asInt(-1) != 1
                || !measurement.path("content_sha256").asText().equals(JsonHashes.ownHash(measurement))
                || !measurement.path("plan_sha256").asText().equals(plan.path("content_sha256").asText())
                || !measurement.path("profile_sha256").asText().equals(profile.path("content_sha256").asText())
                || !measurement.path("ledger_content_sha256").asText().equals(ledger.path("content_sha256").asText())
                || !measurement.path("executor_identity_sha256").asText()
                        .equals(plan.path("executor_identity_sha256").asText())
                || !measurement.path("executor_source_sha256").asText()
                        .equals(plan.path("executor_build_input_fingerprint").asText())
                || !SHA256.matcher(measurement.path("host_identity_sha256").asText()).matches()
                || !measurement.path("host_identity_sha256").asText().equals(measurementHostFingerprint(profile))
                || !measurement.path("run_id").asText().equals(ledger.path("run_id").asText())
                || measurement.path("workers").asInt(-1) != expectedWorkers
                || measurement.path("launched_slot_count").asInt(-1) != planned.size()
                || measurement.path("completed_slot_count").asInt(-1) != planned.size()
                || !measurement.path("fresh_full_wave").asBoolean(false)
                || !measurement.path("resource_probe_complete").asBoolean(false)) {
            throw new IllegalArgumentException("corrected run resource measurement is incomplete or unbound");
        }
        long wall = measurement.path("measured_wall_millis").asLong(-1L);
        long aggregate = measurement.path("measured_max_aggregate_rss_bytes").asLong(-1L);
        long coordinator = measurement.path("measured_max_coordinator_rss_bytes").asLong(-1L);
        long disk = measurement.path("measured_max_disk_bytes").asLong(-1L);
        if (wall <= 0L || aggregate <= 0L || coordinator <= 0L || disk <= 0L
                || wall > profile.path("max_wall_minutes").asLong(0L) * 60_000L
                || aggregate > profile.path("max_aggregate_rss_bytes").asLong(0L)
                || coordinator > profile.path("coordinator_rss_reservation_bytes").asLong(0L)
                || disk > profile.path("max_disk_bytes").asLong(0L)) {
            throw new IllegalArgumentException("corrected run resource measurement exceeds its bound envelope");
        }
        return measurement.deepCopy();
    }

    private static ObjectNode object(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) throw new IllegalArgumentException("missing object: " + field);
        return (ObjectNode) value;
    }

    private static boolean resourceEnvelopeMatches(ObjectNode resource, ObjectNode profile) {
        return resource.path("max_wall_minutes").asLong(-1) == profile.path("max_wall_minutes").asLong(-2)
                && resource.path("max_aggregate_rss_bytes").asLong(-1) == profile.path("max_aggregate_rss_bytes").asLong(-2)
                && resource.path("coordinator_rss_reservation_bytes").asLong(-1) == profile.path("coordinator_rss_reservation_bytes").asLong(-2)
                && resource.path("worker_rss_bytes").asLong(-1) == profile.path("worker_rss_bytes").asLong(-2)
                && resource.path("worker_heap_bytes").asLong(-1) == profile.path("worker_heap_bytes").asLong(-2)
                && resource.path("worker_cpu").asInt(-1) == profile.path("worker_cpu").asInt(-2)
                && resource.path("max_workers").asInt(-1) == profile.path("max_workers").asInt(-2)
                && resource.path("max_cpu").asInt(-1) == profile.path("max_cpu").asInt(-2)
                && resource.path("max_disk_bytes").asLong(-1) == profile.path("max_disk_bytes").asLong(-2);
    }

    private static boolean sameFullGeometry(ObjectNode geometry) {
        return geometry.path("cell_count").asInt(-1) == 4
                && geometry.path("replications").asInt(-1) == 75
                && geometry.path("episodes_per_replication").asInt(-1) == 450
                && geometry.path("cluster_count").asInt(-1) == 288
                && geometry.path("paired_cluster_count").asInt(-1) == 162
                && geometry.path("lifecycle_series_per_replication").asInt(-1) == 900
                && geometry.path("horizon_minutes").asInt(-1) == 14_400;
    }

    private static void validateLiveProfile(ObjectNode profile, ObjectNode options, String requestedMode,
            ResourceProbe resourceProbe) {
        if (options.path("test_probe_override").asBoolean(false)) {
            throw new IllegalArgumentException("production parallel run rejects test_probe_override");
        }
        ResourceObservation observation = Objects.requireNonNull(resourceProbe.sample(resourceRoot(options)),
                "resourceProbe observation");
        long actualCpu = observation.availableProcessors();
        long actualMemory = observation.totalMemoryBytes();
        if (actualCpu <= 0 || actualMemory <= 0) throw new IllegalArgumentException("live resource probe is unavailable");
        long usable = actualMemory > OS_MEMORY_RESERVE + profile.path("coordinator_rss_reservation_bytes").asLong(0)
                ? actualMemory - OS_MEMORY_RESERVE - profile.path("coordinator_rss_reservation_bytes").asLong(0) : 0L;
        long memoryWorkers = profile.path("worker_rss_bytes").asLong(0) > 0
                ? usable / profile.path("worker_rss_bytes").asLong() : 0L;
        long cpuWorkers = actualCpu / Math.max(1L, profile.path("worker_cpu").asLong(WORKER_CPU));
        long liveCap = Math.min(Math.min(profile.path("max_workers").asLong(0),
                profile.path("max_cpu").asLong(0) / Math.max(1L, profile.path("worker_cpu").asLong(WORKER_CPU))),
                Math.min(memoryWorkers, cpuWorkers));
        if (profile.path("effective_workers").asLong(0) <= 0 || profile.path("effective_workers").asLong(0) > liveCap) {
            throw new IllegalArgumentException("execution profile exceeds current host admission");
        }
        if ("FULL".equals(requestedMode)
                && (actualCpu < profile.path("target_cpu_ceiling").asLong(28)
                        || actualMemory < profile.path("target_memory_bytes").asLong(32L * GIB))) {
            throw new IllegalArgumentException("FULL production run requires the qualified target hardware");
        }
        long actualFree = observation.availableFreeSpaceBytes();
        if (actualFree <= 0 || actualFree < profile.path("max_disk_bytes").asLong(Long.MAX_VALUE)) {
            throw new IllegalArgumentException("current host lacks the frozen disk headroom");
        }
    }

    private static String mode(ObjectNode options, ObjectNode plan) {
        String value = options.path("mode").asText(plan.path("mode").asText("FULL"));
        if (!"FULL".equals(value) && !"PREFIX".equals(value)) throw new IllegalArgumentException("parallel mode must be FULL or PREFIX");
        return value;
    }

    private static String monitor(ObjectNode options, ObjectNode profile, long deadline, Path ledger,
            Path artifactRoot, Path scratchRoot, Path logsRoot, ResourceProbe resourceProbe) {
        if (System.nanoTime() >= deadline) return "RESOURCE_WALL_DEADLINE_EXCEEDED";
        String cancel = options.path("cancel_file").asText("");
        if (!cancel.isBlank() && Files.exists(Path.of(cancel))) return "CANCELLED";
        ResourceObservation observation = Objects.requireNonNull(resourceProbe.sample(ledger),
                "resourceProbe observation");
        long available = observation.availableFreeSpaceBytes();
        long required = profile.path("max_disk_bytes").asLong(-1);
        if (available < 0) return "RESOURCE_DISK_UNAVAILABLE";
        long used = managedBytes(ledger, artifactRoot, scratchRoot, logsRoot);
        if (used < 0) return "RESOURCE_DISK_USAGE_UNAVAILABLE";
        if (required > 0 && used > required) return "RESOURCE_DISK_BUDGET_EXCEEDED";
        long remaining = required > used ? required - used : 0L;
        if (required > 0 && available < remaining) return "RESOURCE_DISK_HEADROOM_EXCEEDED";
        long coordinator = observation.coordinatorRssBytes();
        if (coordinator < 0) return "RESOURCE_COORDINATOR_RSS_UNAVAILABLE";
        long coordinatorLimit = profile.path("coordinator_rss_reservation_bytes").asLong(0);
        if (coordinatorLimit > 0 && coordinator > coordinatorLimit) return "RESOURCE_COORDINATOR_RSS_BUDGET_EXCEEDED";
        long workers = observation.aggregateWorkerRssBytes();
        if (workers < 0 && !ACTIVE_PROCESSES.isEmpty()) return "RESOURCE_RSS_UNAVAILABLE";
        long aggregateLimit = profile.path("max_aggregate_rss_bytes").asLong(0);
        if (aggregateLimit > 0 && workers >= 0 && coordinator + workers > aggregateLimit) {
            return "RESOURCE_AGGREGATE_RSS_BUDGET_EXCEEDED";
        }
        return null;
    }

    private static String monitorWithMeasurement(ObjectNode options, ObjectNode profile, long deadline,
            Path ledger, Path artifactRoot, Path scratchRoot, Path logsRoot, ResourceProbe resourceProbe,
            RunMeasurement measurement) {
        String violation = monitor(options, profile, deadline, ledger, artifactRoot, scratchRoot, logsRoot,
                resourceProbe);
        if (measurement != null) measurement.sample(resourceProbe, artifactRoot, scratchRoot, logsRoot);
        return violation;
    }

    private static long managedBytes(Path... roots) {
        long total = 0L;
        for (Path root : roots) {
            long value = treeBytes(root);
            if (value < 0) return -1L;
            if (Long.MAX_VALUE - total < value) return Long.MAX_VALUE;
            total += value;
        }
        return total;
    }

    private static long coordinatorRssBytes() {
        return ProcessSlotExecutor.processRssBytes(ProcessHandle.current().pid());
    }

    private static String measurementHostFingerprint(ObjectNode profile) {
        String machineId = machineIdentity();
        ObjectNode host = JsonHashes.mapper().createObjectNode()
                .put("os_name", System.getProperty("os.name", ""))
                .put("os_arch", System.getProperty("os.arch", ""))
                .put("java_version", System.getProperty("java.version", ""))
                .put("available_cpus", profile.path("available_cpus").asLong(-1L))
                .put("available_memory_bytes", profile.path("available_memory_bytes").asLong(-1L));
        if (machineId.isBlank()) return "";
        host.put("machine_id_sha256", JsonHashes.sha256(machineId));
        return JsonHashes.canonicalSha256(host);
    }

    /** Package seam shared by corrected profile construction and deterministic tests. */
    static String hostFingerprintForProfile(ObjectNode profile) {
        return measurementHostFingerprint(profile);
    }

    private static String machineIdentity() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            try {
                Process process = new ProcessBuilder("reg", "query",
                        "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid")
                        .redirectErrorStream(true).start();
                if (!process.waitFor(2L, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return "";
                }
                if (process.exitValue() != 0) return "";
                String output;
                try (InputStream stream = process.getInputStream()) {
                    output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
                for (String line : output.split("\\R")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("MachineGuid")) {
                        String[] columns = trimmed.split("\\s+");
                        if (columns.length >= 3 && !columns[2].isBlank()) return columns[2];
                    }
                }
            } catch (IOException | InterruptedException error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            }
            return "";
        }
        if (os.contains("mac")) return macMachineIdentity();
        for (String candidate : List.of("/etc/machine-id", "/var/lib/dbus/machine-id")) {
            try {
                String value = Files.readString(Path.of(candidate)).trim();
                if (!value.isBlank()) return value;
            } catch (IOException | RuntimeException ignored) { }
        }
        return "";
    }

    private static String macMachineIdentity() {
        try {
            Process process = new ProcessBuilder("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(2L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            try (InputStream stream = process.getInputStream()) {
                if (process.exitValue() != 0) return "";
                return parseMacMachineIdentity(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return "";
        }
    }

    /** Parses the bounded ioreg response; malformed or missing identity fails closed. */
    static String parseMacMachineIdentity(String output) {
        if (output == null) return "";
        Matcher matcher = MAC_PLATFORM_UUID.matcher(output);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static long treeBytes(Path root) {
        if (root == null || !Files.exists(root)) return -1L;
        final long[] total = {0L};
        final boolean[] failed = {false};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    try {
                        long size = Files.size(file);
                        if (Long.MAX_VALUE - total[0] < size) total[0] = Long.MAX_VALUE;
                        else total[0] += size;
                        return FileVisitResult.CONTINUE;
                    } catch (NoSuchFileException vanished) {
                        // Atomic worker cleanup may remove a file after the walker sees it.
                        return FileVisitResult.CONTINUE;
                    } catch (IOException | RuntimeException error) {
                        failed[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    if (error instanceof NoSuchFileException) {
                        // A concurrently removed entry is not a disk-probe failure.
                        return FileVisitResult.CONTINUE;
                    }
                    failed[0] = true;
                    return FileVisitResult.TERMINATE;
                }
            });
        } catch (IOException | RuntimeException error) {
            return -1L;
        }
        return failed[0] ? -1L : total[0];
    }

    private static long detectedMemoryBytes() {
        try {
            java.lang.management.OperatingSystemMXBean raw = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (raw instanceof com.sun.management.OperatingSystemMXBean bean) return bean.getTotalMemorySize();
        } catch (RuntimeException ignored) { }
        return -1L;
    }

    private static Path resourceRoot(ObjectNode options) {
        String root = options.path("resource_root").asText("");
        return root.isBlank() ? Path.of(".") : Path.of(root);
    }

    private static long detectedFreeSpaceBytes(Path root) {
        try { FileStore store = Files.getFileStore(root.toAbsolutePath().normalize()); return store.getUsableSpace(); }
        catch (IOException | RuntimeException ignored) { return -1L; }
    }

    private static long longOr(ObjectNode value, String field, long fallback) { return value.has(field) ? value.path(field).asLong(fallback) : fallback; }
    private static int integerOr(ObjectNode value, String field, int fallback) { return value.has(field) ? value.path(field).asInt(fallback) : fallback; }
    private static void requireSha(String value, String label) { if (value == null || !SHA256.matcher(value).matches()) throw new IllegalArgumentException(label + " must be SHA-256"); }

    private static Path requiredPath(ObjectNode options, String field) {
        String value = options.path(field).asText(""); if (value.isBlank()) throw new IllegalArgumentException("--" + field + " is required"); return Path.of(value);
    }

    private static ObjectNode readObjectOption(ObjectNode options, String key, String label) {
        JsonNode value = options.get(key);
        if (value == null) throw new IllegalArgumentException(key + " is required");
        if (value.isObject()) return (ObjectNode) value;
        if (!value.isTextual()) throw new IllegalArgumentException(key + " must be an object or JSON path");
        return readObject(Path.of(value.asText()), label);
    }

    private static ObjectNode readObject(Path path, String label) {
        try (InputStream input = Files.newInputStream(path)) {
            JsonNode value = JsonHashes.mapper().readTree(input);
            if (!value.isObject()) throw new IllegalArgumentException(label + " must be an object: " + path);
            return (ObjectNode) value;
        } catch (IOException error) { throw new IllegalArgumentException("cannot read " + label + ": " + path, error); }
    }

    private static void writeAtomic(Path path, ObjectNode value, boolean replace) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent(); if (parent != null) Files.createDirectories(parent);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + System.nanoTime());
        Files.write(temporary, JsonHashes.mapper().writeValueAsBytes(value), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            if (replace) Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            if (replace) Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary, path);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void persistLedger(Path path, ObjectNode ledger) throws IOException {
        ledger.put("content_sha256", JsonHashes.ownHash(ledger)); writeAtomic(path, ledger, true);
    }

    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9_.-]", "_"); }
    private static String cause(ExecutionException error) { Throwable value = error.getCause() == null ? error : error.getCause(); return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage(); }

    private record AttemptOutcome(Slot slot, int attempt, Path artifact,
                                  String error, String resourceViolation, boolean retryableFailure) {
        static AttemptOutcome complete(Slot slot, int attempt, Path path) { return new AttemptOutcome(slot, attempt, path, "", null, false); }
        static AttemptOutcome failed(Slot slot, int attempt, String error) { return new AttemptOutcome(slot, attempt, null, error, null, true); }
        static AttemptOutcome resource(Slot slot, int attempt, String reason) { return new AttemptOutcome(slot, attempt, null, reason, reason, false); }
    }

    private static final class ResourceViolation extends IllegalArgumentException {
        ResourceViolation(String message) { super(message); }
        ResourceViolation(String message, Throwable cause) { super(message, cause); }
    }

    private static ObjectNode workerFailure(Slot slot, ObjectNode plan, ObjectNode identity,
            ObjectNode payload, String reason) {
        ObjectNode failure = JsonHashes.mapper().createObjectNode()
                .put("schema", WORKER_FAILURE_SCHEMA).put("version", 1)
                .put("status", "RESOURCE_ABORT").put("slot_id", slot.id())
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("profile_sha256", payload.path("profile_sha256").asText())
                .put("executor_identity_sha256", identity.path("executable").path("sha256").asText())
                .put("executor_source_fingerprint", identity.path("compiled").path("input_fingerprint").asText())
                .put("attempt", payload.path("attempt").asInt(1))
                .put("reason", reason == null || reason.isBlank() ? "RESOURCE_WORKER_FAILURE" : reason)
                .put("content_sha256", "");
        failure.put("content_sha256", JsonHashes.ownHash(failure));
        return failure;
    }

    private static boolean isResourceMessage(Throwable error) {
        for (Throwable value = error; value != null; value = value.getCause()) {
            String message = value.getMessage();
            if (value instanceof ResourceViolation || message != null
                    && (message.startsWith("RESOURCE_") || message.contains("RESOURCE_"))) return true;
        }
        return false;
    }

    private static final class ProcessSlotExecutor implements DurableSlotExecutor {
        private final ObjectNode options;
        private final ObjectNode plan;
        private final ObjectNode baseline;
        private final ObjectNode controls;
        private final ObjectNode experiment;
        private final ObjectNode profile;
        private final ArrayNode supportingDependencies;
        private final long workerWallMillis;
        private final boolean corrected;
        private String runId;

        ProcessSlotExecutor(ObjectNode options, ObjectNode plan, ObjectNode baseline,
                ObjectNode controls, ObjectNode experiment, ObjectNode profile, boolean corrected,
                String runId) {
            this.options = options.deepCopy();
            this.plan = plan.deepCopy();
            this.baseline = baseline.deepCopy();
            this.controls = controls.deepCopy();
            this.experiment = experiment.deepCopy();
            this.profile = profile.deepCopy();
            this.corrected = corrected;
            this.runId = runId;
            this.supportingDependencies = freezeWorkerDependencies(plan);
            long declaredWall = profile.path("max_wall_minutes").asLong(48L * 60L) * 60_000L;
            long requestedWall = options.path("max_worker_wall_millis").asLong(declaredWall);
            if (requestedWall <= 0 || requestedWall > declaredWall) {
                throw new IllegalArgumentException("max_worker_wall_millis cannot extend the frozen execution profile");
            }
            this.workerWallMillis = requestedWall;
        }

        @Override public Path evaluateArtifact(Slot slot, Path scratch) throws Exception {
            ObjectNode payload = JsonHashes.mapper().createObjectNode();
            payload.set("slot", slot.toJson());
            payload.set("plan", plan.deepCopy());
            payload.set("baseline", baseline.deepCopy());
            payload.set("controls", controls.deepCopy());
            payload.set("experiment", experiment.deepCopy());
            payload.put("corrected_accounting", corrected);
            if (corrected) payload.put("run_id", runId == null ? "" : runId);
            ObjectNode identity = BuildIdentityService.describe(StrategyOperatingCharacteristicsParallelV1.class);
            if (!"JAR".equals(identity.path("executable").path("kind").asText())
                    || !SHA256.matcher(identity.path("executable").path("sha256").asText()).matches()) {
                throw new IllegalArgumentException("parallel run requires a packaged executor JAR");
            }
            payload.put("executor_identity_sha256", identity.path("executable").path("sha256").asText());
            payload.put("profile_sha256", profile.path("content_sha256").asText());
            String sourceFingerprint = identity.path("compiled").path("input_fingerprint").asText("");
            if (sourceFingerprint.isBlank() || "null".equals(sourceFingerprint)) {
                throw new IllegalArgumentException("parallel run requires packaged source identity");
            }
            payload.put("executor_source_fingerprint", sourceFingerprint);
            payload.put("attempt", attemptFromScratch(scratch));
            payload.put("episodes_per_replication", episodesForMode(plan, slot.mode()));
            payload.put("worker_rss_bytes", profile.path("worker_rss_bytes").asLong(3L * GIB));
            payload.put("worker_heap_bytes", profile.path("worker_heap_bytes").asLong(2L * GIB));
            payload.put("worker_cpu", profile.path("worker_cpu").asInt((int) WORKER_CPU));
            payload.set("supporting_dependencies", supportingDependencies.deepCopy());
            Path workerRoot = prepareWorkerRepository(scratch, payload);
            payload.put("max_wall_millis", workerWallMillis);
            Path payloadPath = scratch.resolve("payload.json"); Path output = scratch.resolve("worker-result.json");
            writeAtomic(payloadPath, payload, false);
            Path jar = Path.of(identity.path("executable").path("code_source").asText());
            String java = options.path("java").asText(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            List<String> command = List.of(java, "-XX:+ExitOnOutOfMemoryError",
                    "-Xmx" + Math.max(64L, payload.path("worker_heap_bytes").asLong() / (1024L * 1024L)) + "m",
                    "-XX:ActiveProcessorCount=" + profile.path("worker_cpu").asInt((int) WORKER_CPU),
                    "-Djava.io.tmpdir=" + scratch.resolve("tmp"), "-jar", jar.toString(),
                    "strategy-research-v5", "operating-characteristics-parallel-worker", "--internal",
                    "--payload", payloadPath.toString(), "--out", output.toString());
            Files.createDirectories(scratch.resolve("tmp"));
            ProcessBuilder builder = new ProcessBuilder(command).directory(workerRoot.toFile())
                    .redirectErrorStream(true).redirectOutput(scratch.resolve("worker.stdout.log").toFile());
            builder.environment().put(RepositoryLayout.ROOT_ENVIRONMENT_VARIABLE, workerRoot.toString());
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(workerWallMillis);
            Process process = null;
            ProcessHandle processHandle = null;
            try {
                process = builder.start();
                processHandle = process.toHandle();
                ACTIVE_PROCESSES.add(processHandle);
                while (process.isAlive()) {
                    if (System.nanoTime() >= deadline) { terminate(process); throw new IllegalStateException("RESOURCE_WALL_DEADLINE_EXCEEDED"); }
                    long rss = processTreeRssBytes(process.toHandle());
                    if (rss < 0) { terminate(process); throw new IllegalStateException("RESOURCE_RSS_UNAVAILABLE"); }
                    if (rss > payload.path("worker_rss_bytes").asLong(3L * GIB)) {
                        terminate(process); throw new IllegalStateException("RESOURCE_RSS_BUDGET_EXCEEDED");
                    }
                    long free = detectedFreeSpaceBytes(scratch);
                    if (free < 0) { terminate(process); throw new IllegalStateException("RESOURCE_DISK_UNAVAILABLE"); }
                    long scratchUsage = treeBytes(scratch);
                    if (scratchUsage < 0) { terminate(process); throw new IllegalStateException("RESOURCE_DISK_USAGE_UNAVAILABLE"); }
                    if (scratchUsage > profile.path("max_disk_bytes").asLong(128L * GIB)) {
                        terminate(process); throw new IllegalStateException("RESOURCE_DISK_BUDGET_EXCEEDED");
                    }
                    Thread.sleep(DEFAULT_POLL_MILLIS);
                }
                process.waitFor(5, TimeUnit.SECONDS);
                int exitCode = process.exitValue();
                // HotSpot's ExitOnOutOfMemoryError contract is exit code 3.
                // Treat it as a run-wide resource violation: retrying the same
                // heap bound across other seeds cannot make the envelope safe.
                if (exitCode == 3) {
                    throw new ResourceViolation("RESOURCE_WORKER_OOM");
                }
                if (exitCode != 0) {
                    if (Files.isRegularFile(output)) {
                        ObjectNode failure = readObject(output, "worker failure envelope");
                        if (WORKER_FAILURE_SCHEMA.equals(failure.path("schema").asText())
                                && "RESOURCE_ABORT".equals(failure.path("status").asText())
                                && slot.id().equals(failure.path("slot_id").asText())
                                && plan.path("content_sha256").asText().equals(failure.path("plan_sha256").asText())
                                && profile.path("content_sha256").asText().equals(failure.path("profile_sha256").asText())
                                && payload.path("executor_identity_sha256").asText()
                                        .equals(failure.path("executor_identity_sha256").asText())
                                && payload.path("executor_source_fingerprint").asText()
                                        .equals(failure.path("executor_source_fingerprint").asText())
                                && failure.path("content_sha256").asText().equals(JsonHashes.ownHash(failure))) {
                            throw new ResourceViolation(failure.path("reason").asText("RESOURCE_WORKER_FAILURE"));
                        }
                    }
                    throw new IllegalStateException("worker exited " + process.exitValue());
                }
                return output;
            } catch (InterruptedException interrupted) {
                terminate(process); Thread.currentThread().interrupt(); throw new IllegalStateException("CANCELLED", interrupted);
            } finally {
                if (process != null && process.isAlive()) terminate(process);
                if (processHandle != null) ACTIVE_PROCESSES.remove(processHandle);
            }
        }

        private void bindRunId(String value) {
            this.runId = value;
        }

        private static int attemptFromScratch(Path scratch) {
            String name = scratch.getFileName().toString();
            int marker = name.lastIndexOf("-a");
            if (marker < 0) return 1;
            try { return Integer.parseInt(name.substring(marker + 2)); }
            catch (NumberFormatException ignored) { return 1; }
        }

        private static ArrayNode freezeWorkerDependencies(ObjectNode plan) {
            try {
                Path sourceRoot = RepositoryLayout.locate();
                ArrayNode dependencies = JsonHashes.mapper().createArrayNode();
                for (String relative : List.of(
                        "strategy-research/experiments/fk-deleveraging-baseline-v002/portfolio-policy-v001.json",
                        "strategy-research/experiments/fk-deleveraging-baseline-v002/lifecycle-timing-v001.json")) {
                    Path source = PathConfinement.resolve(sourceRoot, relative, "frozen worker dependency",
                            PathConfinement.ExpectedType.FILE).absolute();
                    byte[] bytes = PathConfinement.readSinglyLinkedFile(source, "frozen worker dependency");
                    ObjectNode dependency = dependencies.addObject().put("relative_path", relative)
                            .put("byte_sha256", JsonHashes.sha256(bytes)).put("bytes", bytes.length)
                            .put("data_base64", Base64.getEncoder().encodeToString(bytes));
                    JsonNode json = JsonHashes.mapper().readTree(bytes);
                    if (!json.isObject()) throw new IllegalArgumentException("frozen worker dependency is not an object: " + relative);
                    dependency.put("content_sha256", JsonHashes.ownHash(json));
                }
                validateFrozenDependencies(plan, dependencies);
                return dependencies;
            } catch (IOException error) {
                throw new IllegalArgumentException("cannot freeze worker dependencies before reservation", error);
            }
        }

        private static void validateFrozenDependencies(ObjectNode plan, ArrayNode dependencies) {
            validateDependencyBindings(plan);
            for (JsonNode expected : plan.path("supporting_dependency_receipts")) {
                JsonNode actual = null;
                for (JsonNode candidate : dependencies) {
                    if (expected.path("relative_path").asText().equals(candidate.path("relative_path").asText())) {
                        actual = candidate;
                        break;
                    }
                }
                if (actual == null
                        || expected.path("bytes").asLong(-1) != actual.path("bytes").asLong(-2)
                        || !expected.path("byte_sha256").asText().equals(actual.path("byte_sha256").asText())
                        || !expected.path("content_sha256").asText().equals(actual.path("content_sha256").asText())) {
                    throw new IllegalArgumentException("frozen worker dependency differs from the plan receipt: "
                            + expected.path("relative_path").asText());
                }
            }
        }

        private static Path prepareWorkerRepository(Path scratch, ObjectNode payload) throws IOException {
            Path workerRoot = scratch.resolve("repository");
            Files.createDirectories(workerRoot);
            // RepositoryLayout deliberately requires only these two markers. The
            // evaluator's frozen policy receipts below are the only repository
            // data read by the worker, so each scratch tree stays bounded.
            Files.writeString(workerRoot.resolve("pom.xml"), "<project/>\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.createDirectories(workerRoot.resolve("schemas"));
            ArrayNode receipts = payload.putArray("supporting_receipts");
            JsonNode dependencies = payload.path("supporting_dependencies");
            if (!dependencies.isArray() || dependencies.size() != 2) {
                throw new IOException("frozen worker dependency payload is incomplete");
            }
            for (JsonNode dependency : dependencies) {
                String relative = dependency.path("relative_path").asText("");
                byte[] bytes;
                try {
                    bytes = Base64.getDecoder().decode(dependency.path("data_base64").asText(""));
                } catch (IllegalArgumentException error) {
                    throw new IOException("frozen worker dependency payload is not base64", error);
                }
                if (!JsonHashes.sha256(bytes).equals(dependency.path("byte_sha256").asText())
                        || bytes.length != dependency.path("bytes").asLong(-1)) {
                    throw new IOException("frozen worker dependency payload hash mismatch: " + relative);
                }
                Path target = workerRoot.resolve(relative).normalize();
                Files.createDirectories(target.getParent());
                if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    PathConfinement.validateSinglyLinkedFile(target, "private worker dependency");
                    byte[] existing = PathConfinement.readSinglyLinkedFile(target, "private worker dependency");
                    if (!JsonHashes.sha256(existing).equals(JsonHashes.sha256(bytes))) {
                        throw new IOException("private worker dependency changed during retry: " + relative);
                    }
                } else {
                    Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                }
                PathConfinement.validateSinglyLinkedFile(target, "private worker dependency");
                ObjectNode receipt = receipts.addObject().put("relative_path", relative)
                        .put("byte_sha256", JsonHashes.sha256(bytes)).put("bytes", bytes.length);
                receipt.put("content_sha256", dependency.path("content_sha256").asText());
            }
            return workerRoot;
        }

        private static void terminate(Process process) {
            try {
                List<ProcessHandle> descendants = process.toHandle().descendants().toList();
                descendants.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                descendants.forEach(handle -> { try { handle.onExit().get(5, TimeUnit.SECONDS); } catch (Exception ignored) { } });
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ignored) { }
        }

        private static long processTreeRssBytes(ProcessHandle root) {
            if (!root.isAlive()) return 0L;
            long total = processRssBytes(root.pid());
            if (total < 0) return root.isAlive() ? -1L : 0L;
            for (ProcessHandle child : root.descendants().toList()) {
                if (!child.isAlive()) continue;
                long value = processRssBytes(child.pid());
                if (value < 0) return child.isAlive() ? -1L : 0L;
                if (Long.MAX_VALUE - total < value) return Long.MAX_VALUE;
                total += value;
            }
            return total;
        }

        static long aggregateProcessRssBytes() {
            long total = 0L;
            for (ProcessHandle process : ACTIVE_PROCESSES) {
                long value = processTreeRssBytes(process);
                if (value < 0) return -1L;
                if (Long.MAX_VALUE - total < value) return Long.MAX_VALUE;
                total += value;
            }
            return total;
        }

        static long processRssBytes(long pid) {
            return StrategyProcessResourcesV1.processRssBytes(pid);
        }
    }
}

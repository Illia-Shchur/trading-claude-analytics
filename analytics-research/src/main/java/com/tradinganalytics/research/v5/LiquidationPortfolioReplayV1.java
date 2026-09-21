package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.marketdata.CoinalyzeDailyData;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.PathConfinement;
import com.tradinganalytics.marketdata.research.ResearchData;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

/** Physical, feature-causal v002 replay over a routed account and two matched control accounts. */
public final class LiquidationPortfolioReplayV1 {
    public static final String FREEZE_SCHEMA = "liquidation-v2-replay-freeze/1";
    public static final String REPLAY_SCHEMA = "liquidation-v2-replay-result/1";
    private static final List<String> ASSETS = List.of("BTC", "ETH", "SOL", "AAVE");
    private static final List<String> CANDIDATES = List.of("liquidation-v2-core-routed-one-entry",
            "liquidation-v2-core-always-continuation-one-entry", "liquidation-v2-core-always-reversal-one-entry",
            "liquidation-v2-price-oi-only-event-diagnostic");
    private static final List<String> SOURCE_FILES = List.of(
            "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationPortfolioReplayV1.java",
            "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationPortfolioAccountingV1.java",
            "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationStagedPerpetualLifecycleV1.java",
            "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationStructureRouterV1.java",
            "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationV2PhysicalDataV1.java",
            "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationV2ReplayEvidenceV1.java",
            "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationV2EvidenceMathV1.java",
            "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationV2StagedStatisticsV1.java",
            "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationV2StagedCandidateInventoryV1.java",
            "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationV2ReplayCheckpointV1.java",
            "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationV2FamilyExposureAttemptV1.java",
            "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationV2StressPolicyV1.java",
            "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationInputQualificationV1.java",
            "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationDailyStressProfileV1.java",
            "analytics-research/src/main/java/com/tradinganalytics/research/v5/LiquidationChronologicalPolicyV1.java",
            "analytics-research/src/main/java/com/tradinganalytics/research/v5/StrategyResearchV5CommandAdapter.java",
            "analytics-infrastructure/src/main/java/com/tradinganalytics/infrastructure/security/JsonHashes.java",
            "analytics-infrastructure/src/main/java/com/tradinganalytics/infrastructure/security/PathConfinement.java",
            "analytics-infrastructure/src/main/java/com/tradinganalytics/infrastructure/marketdata/CoinalyzeDailyData.java",
            "analytics-market-data/src/main/java/com/tradinganalytics/marketdata/research/ResearchData.java",
            "analytics-research/src/main/java/com/tradinganalytics/research/v5/StrategyStatisticalV5.java",
            "analytics-research/src/main/java/com/tradinganalytics/research/v5/StrategyResearchAuthoritativeV5.java",
            "pom.xml", "analytics-research/pom.xml", "analytics-infrastructure/pom.xml",
            "analytics-market-data/pom.xml", "mvnw", ".mvn/wrapper/maven-wrapper.properties");
    private static final String CORE_ADDITION_MACRO_POLICY =
            LiquidationStructureRouterV1.MacroGatePolicy.REQUIRE_MACRO_CONFIRMATION.name();
    private static final long MINUTE = 60_000L;
    private static final long DAY = 86_400_000L;
    private static final long CHECKPOINT_SEGMENT_RESERVATION_MILLIS = 2L * 60L * 60L * 1_000L;
    private static final int ROUTED = 0, CONTINUATION = 1, REVERSAL = 2;
    private static final List<Integer> TRADED_ACCOUNTS = List.of(ROUTED, CONTINUATION, REVERSAL);

    private LiquidationPortfolioReplayV1() {}

    /** Freeze exact source, profile, precommit, candidate inventory and physical manifest before outcomes. */
    public static ObjectNode freeze(ObjectNode options) {
        ObjectNode profile = object(options, "profile");
        LiquidationDailyStressProfileV1.validate(profile);
        ObjectNode physicalOptions = object(options, "physical_options");
        ObjectNode manifest = object(physicalOptions, "manifest");
        Path projectRoot = Path.of(text(options, "project_root")).toAbsolutePath().normalize();
        Path dataRoot = Path.of(text(physicalOptions, "root")).toAbsolutePath().normalize();
        ObjectNode verification = verifyPhysical(profile, physicalOptions);
        ObjectNode precommit = readFrozenPrecommit(projectRoot);
        if (!profile.path("precommit_content_sha256").asText().equals(precommit.path("content_sha256").asText())) {
            throw fail("profile does not bind the checked-in frozen precommit");
        }
        ObjectNode freeze = JsonHashes.mapper().createObjectNode().put("schema", FREEZE_SCHEMA).put("version", 1)
                .put("status", "FROZEN_BEFORE_OUTCOME_READ").put("outcomes_examined", false)
                .put("project_root", projectRoot.toString()).put("physical_root", dataRoot.toString())
                .put("profile_sha256", profile.path("content_sha256").asText())
                .put("precommit_sha256", precommit.path("content_sha256").asText())
                .put("precommit_byte_sha256", hashFile(projectRoot.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json")))
                .put("manifest_sha256", manifest.path("content_sha256").asText())
                .put("dataset_root_sha256", manifest.path("dataset_root_sha256").asText())
                .put("plan_sha256", manifest.path("plan_sha256").asText())
                .put("source_mode", manifest.path("source_mode").asText())
                .put("minimum_contiguous_hydration_proven", verification.path("minimum_contiguous_hydration_proven").asBoolean(false))
                .put("coverage_inventory_complete", verification.path("coverage_inventory_complete").asBoolean(false))
                .put("development_replay_permitted", verification.path("development_replay_permitted").asBoolean(false))
                .put("historical_availability_proven", false).put("historical_revision_proven", false)
                .put("authoritative_wfo_permitted", false).put("sealed_confirmation_permitted", false)
                .put("prospective_live_permitted", false).put("trade_authorization_permitted", false);
        freeze.set("profile", profile.deepCopy());
        freeze.set("precommit", precommit);
        freeze.set("physical_manifest", manifest.deepCopy());
        freeze.set("plan", manifest.path("plan").deepCopy());
        freeze.set("executor_identity", executorIdentity(projectRoot));
        freeze.set("candidate_inventory", candidateInventory(profile));
        freeze.set("parent_lineage", readParentLineage(projectRoot));
        freeze.set("role_artifact_hashes", roleArtifactHashes(manifest));
        freeze.put("content_sha256", JsonHashes.ownHash(freeze));
        return freeze;
    }

    /** Reopen all evidence and stream the full proxy envelope or an explicitly bounded synthetic smoke. */
    public static ObjectNode run(ObjectNode options) {
        ObjectNode receipt = runResumable(options);
        if (!"COMPLETE".equals(receipt.path("status").asText())) return receipt;
        return readObjectFile(Path.of(text(options, "out")), "completed replay output");
    }

    /**
     * Budgeted, restartable public runner. Prefixes are rebuilt from the original envelope,
     * compared against durable digests, then continued on the same in-memory Engine instances.
     */
    public static ObjectNode runResumable(ObjectNode options) {
        ObjectNode freeze = object(options, "freeze");
        ObjectNode request = options.has("replay_request") ? object(options, "replay_request") : requestFromRunOptions(options);
        ObjectNode stagedPlan = options.path("staged_plan").isObject() ? object(options, "staged_plan") : null;
        StagedConfig stagedConfig = stagedPlan == null ? null : StagedConfig.fromPlan(stagedPlan);
        if (!FREEZE_SCHEMA.equals(freeze.path("schema").asText())
                || !JsonHashes.ownHash(freeze).equals(freeze.path("content_sha256").asText())) {
            throw fail("freeze schema or outer content hash is invalid");
        }
        Path frozenPhysicalPath = Path.of(text(freeze, "physical_root")).toAbsolutePath().normalize();
        Path physicalRoot = canonicalExistingDirectory(frozenPhysicalPath, "frozen physical root");
        Path output = canonicalTargetWithinRoot(physicalRoot, frozenPhysicalPath,
                Path.of(text(options, "out")), "replay output");
        Path evidenceOutput = options.hasNonNull("evidence_out")
                ? canonicalTargetWithinRoot(physicalRoot, frozenPhysicalPath, Path.of(text(options, "evidence_out")), "evidence output")
                : null;
        Path checkpointFile = options.hasNonNull("checkpoint_out")
                ? Path.of(text(options, "checkpoint_out")).toAbsolutePath().normalize()
                : output.resolveSibling(output.getFileName() + ".checkpoint.json");
        checkpointFile = canonicalTargetWithinRoot(physicalRoot, frozenPhysicalPath, checkpointFile, "checkpoint output");
        Path operationalFile = options.hasNonNull("operational_out")
                ? Path.of(text(options, "operational_out")).toAbsolutePath().normalize() : checkpointFile;
        operationalFile = canonicalTargetWithinRoot(physicalRoot, frozenPhysicalPath, operationalFile, "operational receipt");
        ObjectNode scopeJson = freeze.path("precommit").deepCopy();
        String runId = scopeJson.path("precommit_id").asText("liquidation-daily-stress-v002");
        boolean syntheticFreeze = "SYNTHETIC_DEVELOPMENT_ONLY".equals(freeze.path("source_mode").asText());
        Path projectRoot = canonicalExistingDirectory(Path.of(text(freeze, "project_root")), "project root");
        Path budgetRoot = syntheticFreeze ? physicalRoot : durableResearchRoot(projectRoot);
        ObjectNode boundRequest = boundRunRequest(request, options, stagedPlan, evidenceOutput);
        LiquidationV2ReplayCheckpointV1.FamilyScope scope = LiquidationV2ReplayCheckpointV1.FamilyScope.of(
                runId, text(freeze, "precommit_sha256"), text(freeze, "profile_sha256"),
                text(freeze, "manifest_sha256"), budgetRoot);
        LiquidationV2ReplayCheckpointV1.RunBinding binding = LiquidationV2ReplayCheckpointV1.RunBinding.of(
                text(freeze, "content_sha256"), JsonHashes.canonicalSha256(boundRequest),
                projectRoot, physicalRoot, text(freeze, "manifest_sha256"),
                text(object(freeze, "executor_identity"), "content_sha256"), output, checkpointFile);

        boolean resume = options.path("resume").asBoolean(false);
        LiquidationV2ReplayCheckpointV1.Session session;
        LiquidationV2ReplayCheckpointV1.ResumeSession resumed = null;
        if (resume) {
            long reservation = Math.min(CHECKPOINT_SEGMENT_RESERVATION_MILLIS,
                    LiquidationV2ReplayCheckpointV1.COMPUTE_BUDGET_MILLIS);
            resumed = LiquidationV2ReplayCheckpointV1.resume(scope, binding, System::nanoTime, reservation);
            session = resumed.session();
        } else {
            session = LiquidationV2ReplayCheckpointV1.openNewRun(scope, binding, System::nanoTime);
        }

        try (session) {
            ObjectNode exposureRefs = JsonHashes.mapper().createObjectNode();
            List<PendingScenarioArtifact> scenarioArtifacts = new ArrayList<>();
            try {
            ReplaySetup setup;
            List<Engine> engines;
            StagedPredecessor stagedPredecessor = null;
            Instant currentBoundary;
            if (resume) {
                setup = openReplaySetup(freeze, request, stagedConfig);
                verifyOperationalCheckpoint(checkpointFile, binding, session, session.latestCheckpoint());
                if (options.has("expected_replay")) {
                    verifyExpectedReplayBeforeOutcomes(options.path("expected_replay"), freeze, stagedPlan);
                }
                if (stagedConfig != null) stagedPredecessor = reopenStagedPredecessor(
                        freeze, stagedPlan, request, options, scenarioArtifacts);
                exposureRefs = preOutcomeExposureRefs(freeze, stagedPlan);
                engines = resumableEngines(setup, freeze, stagedConfig);
                LiquidationV2ReplayCheckpointV1.Checkpoint expected = resumed.expectedPrefix();
                if (expected == null) {
                    currentBoundary = setup.featureFrom();
                    ObjectNode initialPrefix = resumablePrefix(engines, currentBoundary);
                    LiquidationV2ReplayCheckpointV1.Checkpoint actual = prefixCheckpoint(initialPrefix, currentBoundary);
                    resumed.prefixReconstruction().checkpoint(currentBoundary, actual.resultSha256(),
                            actual.ledgerSha256(), actual.accountCurveSha256());
                } else {
                    currentBoundary = expected.boundaryExclusive();
                    ObjectNode reconstructed = resumablePrefix(engines, currentBoundary);
                    LiquidationV2ReplayCheckpointV1.Checkpoint actual = prefixCheckpoint(reconstructed, currentBoundary);
                    resumed.prefixReconstruction().verifyReconstructedPrefix(actual);
                    if (!actual.equals(expected)) throw fail("reconstructed prefix differs from durable checkpoint");
                }
            } else {
                LiquidationV2ReplayCheckpointV1.Segment initialization = session.reserveSegment(
                        "INITIAL_PHYSICAL_OPEN_AND_ENGINE_SETUP", CHECKPOINT_SEGMENT_RESERVATION_MILLIS);
                try {
                    setup = openReplaySetup(freeze, request, stagedConfig);
                    if (options.has("expected_replay")) {
                        verifyExpectedReplayBeforeOutcomes(options.path("expected_replay"), freeze, stagedPlan);
                    }
                    if (stagedConfig != null) stagedPredecessor = reopenStagedPredecessor(
                            freeze, stagedPlan, request, options, scenarioArtifacts);
                    exposureRefs = preOutcomeExposureRefs(freeze, stagedPlan);
                    engines = resumableEngines(setup, freeze, stagedConfig);
                    currentBoundary = setup.featureFrom();
                    initialization.ensureWithinReservation();
                    ObjectNode initialPrefix = resumablePrefix(engines, currentBoundary);
                    LiquidationV2ReplayCheckpointV1.Checkpoint checkpoint = prefixCheckpoint(initialPrefix, currentBoundary);
                    initialization.checkpoint(currentBoundary, checkpoint.resultSha256(),
                            checkpoint.ledgerSha256(), checkpoint.accountCurveSha256());
                    persistOperationalCheckpoint(checkpointFile, physicalRoot, binding, session, checkpoint);
                } catch (RuntimeException error) {
                    if (session.reservedComputeMillis() > 0) initialization.abort();
                    throw error;
                }
            }

            if (resume) persistOperationalCheckpoint(checkpointFile, physicalRoot, binding,
                    session, session.latestCheckpoint());
            Instant stopAfter = options.hasNonNull("stop_after_boundary_exclusive")
                    ? Instant.parse(text(options, "stop_after_boundary_exclusive")) : setup.executionEnd();
            if (!dayAligned(stopAfter) || stopAfter.isBefore(currentBoundary) || stopAfter.isAfter(setup.executionEnd())) {
                throw fail("stop_after_boundary_exclusive must be a UTC midnight between the saved prefix and execution end");
            }
            if (currentBoundary.equals(stopAfter) && currentBoundary.isBefore(setup.executionEnd())) {
                return runOperationalReceipt("RESUMABLE", null, binding, session, operationalFile,
                        physicalRoot, exposureRefs);
            }

            for (Instant boundary = currentBoundary.plus(Duration.ofDays(1)); !boundary.isAfter(setup.executionEnd());
                    boundary = boundary.plus(Duration.ofDays(1))) {
                if (boundary.isAfter(stopAfter)) break;
                LiquidationV2ReplayCheckpointV1.Segment segment = session.reserveSegment(
                        "REPLAY_THROUGH_" + boundary.toString(), CHECKPOINT_SEGMENT_RESERVATION_MILLIS);
                try {
                    ObjectNode prefix = resumablePrefix(engines, boundary);
                    segment.ensureWithinReservation();
                    LiquidationV2ReplayCheckpointV1.Checkpoint checkpoint = prefixCheckpoint(prefix, boundary);
                    segment.checkpoint(boundary, checkpoint.resultSha256(), checkpoint.ledgerSha256(),
                            checkpoint.accountCurveSha256());
                    persistOperationalCheckpoint(checkpointFile, physicalRoot, binding, session, checkpoint);
                } catch (RuntimeException error) {
                    if (session.reservedComputeMillis() > 0) segment.abort();
                    throw error;
                }
                currentBoundary = boundary;
                if (currentBoundary.equals(stopAfter) && currentBoundary.isBefore(setup.executionEnd())) {
                    return runOperationalReceipt("RESUMABLE", null, binding, session, operationalFile,
                            physicalRoot, exposureRefs);
                }
            }

            if (currentBoundary.isBefore(setup.executionEnd())) {
                throw fail("bounded replay stopped before the requested checkpoint boundary");
            }
            LiquidationV2ReplayCheckpointV1.Checkpoint checkpoint = session.latestCheckpoint();
            if (checkpoint == null || !checkpoint.boundaryExclusive().equals(setup.executionEnd())) {
                throw fail("final replay boundary is not bound by a durable prefix checkpoint");
            }
            LiquidationV2ReplayCheckpointV1.Segment finalization = session.reserveSegment(
                    "FINALIZE_AND_ASSEMBLE_REPLAY", CHECKPOINT_SEGMENT_RESERVATION_MILLIS);
            ObjectNode replay;
            byte[] replayBytes;
            byte[] evidenceBytes = null;
            try {
                List<ObjectNode> completed = engines.stream().map(Engine::run).toList();
                replay = completed.get(0);
                List<ObjectNode> scenarioRuns = completed.subList(1, completed.size());
                replay.set("pre_outcome_exposure_attempts", exposureRefs.deepCopy());
                if (stagedConfig == null) {
                    replay.set("stress_evaluation", stressEvaluation(replay, scenarioRuns, setup.stressPolicy(),
                            freeze.path("precommit"), physicalRoot, scenarioArtifacts));
                } else {
                    replay.set("stress_evaluation", stressEvaluation(replay, scenarioRuns, setup.stressPolicy(),
                            freeze.path("precommit"), physicalRoot, scenarioArtifacts));
                    replay.put("candidate_id", stagedConfig.candidateId());
                    replay.put("mode_id", stagedConfig.modeId());
                }
            replay.put("content_sha256", JsonHashes.ownHash(replay));
            if (stagedConfig == null) verifyReplayOutput(replay, freeze);
            else verifyStagedReplayOutput(replay, freeze, stagedPlan);
            if (options.path("expected_replay").isObject()
                    && !JsonHashes.canonicalSha256(replay).equals(
                            JsonHashes.canonicalSha256(options.path("expected_replay")))) {
                throw fail(stagedConfig == null
                        ? "supplied replay differs from deterministic physical runner output; caller-authored outcomes are not accepted"
                        : "supplied staged replay differs from deterministic physical runner output");
            }
            replayBytes = JsonHashes.canonicalBytes(replay);
            if (options.hasNonNull("evidence_out")) {
                ObjectNode evidence = stagedConfig == null ? buildEvidence(freeze, replay, false)
                        : buildStagedEvidence(freeze, stagedPlan, stagedPredecessor, replay);
                evidenceBytes = JsonHashes.canonicalBytes(evidence);
            }
                // All deterministic work and serialization stays under the lease. The durable
                // completion checkpoint is committed before immutable publication, so an
                // overrun cannot leave a seemingly complete replay/evidence artifact behind.
                finalization.ensureWithinReservation();
                // Checkpoint remains the nonterminal prefix. A restart reconstructs that exact
                // state, then repeats deterministic terminalization/assembly under a new lease.
                finalization.checkpoint(checkpoint.boundaryExclusive(), checkpoint.resultSha256(),
                        checkpoint.ledgerSha256(), checkpoint.accountCurveSha256());
                for (PendingScenarioArtifact artifact : scenarioArtifacts) {
                    writeImmutable(physicalRoot, artifact.path(), artifact.bytes());
                }
                writeImmutable(physicalRoot, output, replayBytes);
                if (evidenceBytes != null) {
                    writeImmutable(physicalRoot, evidenceOutput, evidenceBytes);
                }
            } catch (RuntimeException error) {
                if (session.reservedComputeMillis() > 0) finalization.abort();
                throw error;
            }
            persistOperationalCheckpoint(checkpointFile, physicalRoot, binding, session, checkpoint);
            return runOperationalReceipt("COMPLETE", replay, binding, session, operationalFile,
                    physicalRoot, exposureRefs);
            } catch (LiquidationV2ReplayCheckpointV1.ComputeIncompleteException incomplete) {
                LiquidationV2ReplayCheckpointV1.Checkpoint latest = session.latestCheckpoint();
                if (latest != null) persistOperationalCheckpoint(checkpointFile, physicalRoot, binding, session, latest);
                return runOperationalReceipt(LiquidationV2ReplayCheckpointV1.COMPUTE_INCOMPLETE,
                        null, binding, session, operationalFile, physicalRoot, exposureRefs);
            }
        }
    }

    private static List<Engine> coreStressEngines(ReplaySetup setup, ObjectNode freeze) {
        List<Engine> engines = new ArrayList<>();
        engines.add(new Engine(setup.profile(), setup.physical(), freeze, setup.featureFrom(), setup.decisionFrom(),
                setup.decisionEnd(), setup.executionEnd(), setup.synthetic(), null, null));
        for (String scenarioId : List.of("fee_slippage", "funding_carry", "adverse_execution_gap",
                "liquidity_capacity", "venue_outage_blackout")) {
            engines.add(new Engine(setup.profile(), setup.physical(), freeze, setup.featureFrom(), setup.decisionFrom(),
                    setup.decisionEnd(), setup.executionEnd(), setup.synthetic(), setup.stressPolicy(), scenarioId));
        }
        return List.copyOf(engines);
    }

    private static List<Engine> resumableEngines(ReplaySetup setup, ObjectNode freeze, StagedConfig staged) {
        if (staged == null) return coreStressEngines(setup, freeze);
        List<Engine> engines = new ArrayList<>();
        engines.add(new Engine(setup.profile(), setup.physical(), freeze, setup.featureFrom(), setup.decisionFrom(),
                setup.decisionEnd(), setup.executionEnd(), setup.synthetic(), null, null, staged));
        for (String scenarioId : List.of("fee_slippage", "funding_carry", "adverse_execution_gap",
                "liquidity_capacity", "venue_outage_blackout")) {
            engines.add(new Engine(setup.profile(), setup.physical(), freeze, setup.featureFrom(), setup.decisionFrom(),
                    setup.decisionEnd(), setup.executionEnd(), setup.synthetic(), setup.stressPolicy(), scenarioId, staged));
        }
        return List.copyOf(engines);
    }

    private static ObjectNode boundRunRequest(ObjectNode request, ObjectNode options, ObjectNode stagedPlan,
            Path evidenceOutput) {
        ObjectNode bound = JsonHashes.mapper().createObjectNode();
        bound.set("replay_request", request.deepCopy());
        if (evidenceOutput != null) bound.put("evidence_output_target", evidenceOutput.toString());
        if (stagedPlan != null) {
            bound.set("staged_plan", stagedPlan.deepCopy());
            for (String field : List.of("core_replay", "core_evidence", "core_candidate_inventory",
                    "predecessor_plan", "predecessor_replay", "predecessor_evidence", "expected_replay")) {
                if (options.has(field)) bound.set(field, options.path(field).deepCopy());
            }
            if (options.path("replay").isObject() && !options.has("expected_replay")) {
                bound.set("expected_replay", options.path("replay").deepCopy());
            }
        }
        bound.put("content_sha256", JsonHashes.ownHash(bound));
        return bound;
    }

    private static ObjectNode buildStagedEvidence(ObjectNode freeze, ObjectNode plan,
            StagedPredecessor predecessor, ObjectNode replay) {
        if (predecessor == null || predecessor.replay() == null) throw fail("staged evidence requires the verified predecessor replay");
        ObjectNode familyState = "SYNTHETIC_DEVELOPMENT_ONLY".equals(freeze.path("source_mode").asText())
                ? null : LiquidationV2FamilyExposureAttemptV1.reopenFamilyExposureAttempts();
        boolean noMacro = "THREE_STAGE_NO_MACRO".equals(plan.path("mode_id").asText());
        LiquidationV2StagedStatisticsV1.Evaluation evaluation = LiquidationV2StagedStatisticsV1.recompute(
                freeze, plan, predecessor.replay(), replay, noMacro ? null : predecessor.plan(),
                noMacro ? null : predecessor.evidence(), familyState);
        return LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(plan, replay, evaluation);
    }

    private static ObjectNode resumablePrefix(List<Engine> engines, Instant boundary) {
        ObjectNode prefix = JsonHashes.mapper().createObjectNode().put("boundary_exclusive", boundary.toString());
        ArrayNode rows = prefix.putArray("engines");
        for (int index = 0; index < engines.size(); index++) {
            Engine engine = engines.get(index);
            ObjectNode replay = engine.snapshotAt(boundary);
            rows.addObject().put("engine_index", index).put("result_sha256", replay.path("content_sha256").asText())
                    .put("ledger_sha256", replay.path("ledger_sha256").asText())
                    .put("account_curve_sha256", replay.path("account_curve_sha256").asText())
                    .put("continuation_state_sha256", engine.continuationStateSha256());
        }
        return prefix;
    }

    private static LiquidationV2ReplayCheckpointV1.Checkpoint prefixCheckpoint(ObjectNode prefix, Instant boundary) {
        ArrayNode ledgers = JsonHashes.mapper().createArrayNode(), curves = JsonHashes.mapper().createArrayNode();
        for (JsonNode engine : prefix.path("engines")) {
            ledgers.add(engine.path("ledger_sha256").asText());
            curves.add(engine.path("account_curve_sha256").asText());
        }
        return new LiquidationV2ReplayCheckpointV1.Checkpoint(boundary,
                JsonHashes.canonicalSha256(prefix), JsonHashes.canonicalSha256(ledgers), JsonHashes.canonicalSha256(curves));
    }

    private static void verifyOperationalCheckpoint(Path checkpointFile,
            LiquidationV2ReplayCheckpointV1.RunBinding binding,
            LiquidationV2ReplayCheckpointV1.Session session,
            LiquidationV2ReplayCheckpointV1.Checkpoint expected) {
        if (expected == null) return;
        if (!Files.exists(checkpointFile, LinkOption.NOFOLLOW_LINKS)) return; // chain is authoritative; regenerate mirror after prefix verification
        try {
            JsonNode checkpoint = JsonHashes.mapper().readTree(Files.readAllBytes(checkpointFile));
            LiquidationV2ReplayCheckpointV1.Checkpoint mirrored = checkpoint.has("checkpoint")
                    ? LiquidationV2ReplayCheckpointV1.checkpointFromJson(checkpoint.path("checkpoint")) : null;
            if (!"liquidation-v2-replay-operational-checkpoint/1".equals(checkpoint.path("schema").asText())
                    || !JsonHashes.ownHash(checkpoint).equals(checkpoint.path("content_sha256").asText())
                    || !binding.bindingSha256().equals(checkpoint.path("run_binding_sha256").asText())
                    || mirrored == null || !session.containsVerifiedCheckpoint(mirrored)) {
                throw fail("operational checkpoint file does not match durable family checkpoint");
            }
        } catch (IOException error) {
            throw fail("cannot reopen operational checkpoint: " + error.getMessage());
        }
    }

    private static void persistOperationalCheckpoint(Path target, Path physicalRoot,
            LiquidationV2ReplayCheckpointV1.RunBinding binding,
            LiquidationV2ReplayCheckpointV1.Session session,
            LiquidationV2ReplayCheckpointV1.Checkpoint checkpoint) {
        if (checkpoint == null) throw fail("cannot persist an absent replay checkpoint");
        ObjectNode value = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-replay-operational-checkpoint/1")
                .put("run_binding_sha256", binding.bindingSha256());
        value.set("checkpoint", checkpoint.toJson());
        value.set("budget_session", session.toJson());
        value.put("content_sha256", JsonHashes.ownHash(value));
        writeOperationalJson(physicalRoot, target, value);
    }

    private static ObjectNode runOperationalReceipt(String status, ObjectNode replay,
            LiquidationV2ReplayCheckpointV1.RunBinding binding,
            LiquidationV2ReplayCheckpointV1.Session session, Path operationalFile,
            Path physicalRoot, ObjectNode exposureRefs) {
        ObjectNode receipt = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-replay-operational-receipt/1")
                .put("status", status).put("run_binding_sha256", binding.bindingSha256())
                .put("checkpoint_path", binding.checkpointTarget().toString())
                .put("operational_checkpoint_path", binding.checkpointTarget().toString())
                .put("operational_receipt_path", operationalFile.resolveSibling(operationalFile.getFileName() + ".receipt.json").toString());
        receipt.set("budget_session", session.toJson());
        receipt.set("pre_outcome_exposure_attempts", exposureRefs.deepCopy());
        if (replay != null) receipt.put("replay_result_sha256", replay.path("content_sha256").asText())
                .put("replay_output_path", binding.outputTarget().toString());
        else receipt.putNull("replay_result_sha256").putNull("replay_output_path");
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        writeOperationalJson(physicalRoot, operationalFile.resolveSibling(operationalFile.getFileName() + ".receipt.json"), receipt);
        return receipt;
    }

    private static ObjectNode readObjectFile(Path path, String label) {
        try {
            JsonNode value = JsonHashes.mapper().readTree(Files.readAllBytes(path));
            if (value == null || !value.isObject()) throw fail(label + " must be a JSON object");
            return (ObjectNode) value;
        } catch (IOException error) {
            throw fail("cannot reopen " + label + ": " + error.getMessage());
        }
    }

    private static void writeOperationalJson(Path root, Path target, ObjectNode value) {
        Path base = PathConfinement.requireRealDirectory(root.toAbsolutePath().normalize(), "frozen physical root");
        Path absolute = target.toAbsolutePath().normalize();
        if (!absolute.startsWith(base) || absolute.equals(base)) throw fail("operational checkpoint must remain beneath the frozen physical root");
        Path parent = absolute.getParent();
        try {
            Files.createDirectories(parent);
            if (Files.isSymbolicLink(parent) || !parent.toRealPath().startsWith(base)) throw fail("operational checkpoint parent is unsafe");
            if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(absolute) || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS))) {
                throw fail("operational checkpoint target is not a regular file");
            }
            Path temp = Files.createTempFile(parent, ".liquidation-checkpoint-", ".tmp");
            try {
                Files.write(temp, JsonHashes.canonicalBytes(value), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                try { Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(temp); }
        } catch (IOException error) {
            throw fail("cannot atomically persist operational checkpoint: " + error.getMessage());
        }
    }

    private static Path canonicalExistingDirectory(Path path, String label) {
        try {
            Path absolute = path.toAbsolutePath().normalize();
            // Resolve OS aliases such as /var -> /private/var before storing the family
            // binding. Individual custody entries remain checked with NOFOLLOW_LINKS.
            Path real = absolute.toRealPath();
            if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) throw fail(label + " must be an existing directory");
            return real;
        } catch (IOException error) {
            throw fail("cannot canonicalize " + label + ": " + error.getMessage());
        }
    }

    private static Path durableResearchRoot(Path projectRoot) {
        Path root = projectRoot.resolve(".research-run");
        if (Files.isSymbolicLink(root)) {
            throw fail("durable research budget root must not be a symlink");
        }
        try {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(root);
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw fail("durable research budget root must be a non-symlink directory");
            }
            return root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException error) {
            if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
                try { return root.toRealPath(LinkOption.NOFOLLOW_LINKS); }
                catch (IOException ignored) { /* preserve original failure below */ }
            }
            throw fail("cannot initialize durable research budget root: " + error.getMessage());
        }
    }

    private static Path canonicalTargetWithinRoot(Path root, Path frozenRootAlias, Path requested, String label) {
        Path absolute = requested.toAbsolutePath().normalize();
        Path alias = frozenRootAlias.toAbsolutePath().normalize();
        Path relative;
        if (absolute.startsWith(alias)) relative = alias.relativize(absolute);
        else if (absolute.startsWith(root)) relative = root.relativize(absolute);
        else {
            try {
                Path parent = absolute.getParent();
                if (parent == null || !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) throw fail(label + " must remain under the frozen physical root");
                Path realParent = parent.toRealPath(LinkOption.NOFOLLOW_LINKS);
                if (!realParent.startsWith(root)) throw fail(label + " must remain under the frozen physical root");
                return realParent.resolve(absolute.getFileName()).normalize();
            } catch (IOException error) {
                throw fail("cannot canonicalize " + label + ": " + error.getMessage());
            }
        }
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw fail(label + " must remain below the frozen physical root");
        Path cursor = root;
        for (Path part : relative) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor)) {
                throw fail(label + " path must not traverse symlinks");
            }
        }
        return target;
    }

    /** Executes one separately frozen three-stage candidate over the complete predecessor anchor inventory. */
    public static ObjectNode runStaged(ObjectNode options) {
        if (!options.path("staged_plan").isObject()) throw fail("runStaged requires its separately frozen staged_plan");
        ObjectNode receipt = runResumable(options);
        if (!"COMPLETE".equals(receipt.path("status").asText())) return receipt;
        return readObjectFile(Path.of(text(options, "out")), "completed staged replay output");
    }

    /** Reopens and reruns staged execution before deriving any advancement evidence. */
    public static ObjectNode evaluateStaged(ObjectNode options) {
        ObjectNode freeze = object(options, "freeze");
        if (!options.path("staged_plan").isObject()) throw fail("evaluateStaged requires its separately frozen staged_plan");
        ObjectNode suppliedReplay = object(options, "replay");
        String requestedReplayHash = suppliedReplay.path("content_sha256").asText("invalid");
        String suffix = JsonHashes.isSha256(requestedReplayHash) ? requestedReplayHash : JsonHashes.canonicalSha256(suppliedReplay);
        Path physicalRoot = canonicalExistingDirectory(Path.of(text(freeze, "physical_root")), "frozen physical root");
        Path replayOutput = physicalRoot.resolve("recomputed-evaluations").resolve("staged-replay-" + suffix + ".json");
        ObjectNode budgetOptions = options.deepCopy();
        budgetOptions.set("replay_request", object(suppliedReplay, "execution_request").deepCopy());
        budgetOptions.set("expected_replay", suppliedReplay.deepCopy());
        budgetOptions.put("out", replayOutput.toString());
        budgetOptions.put("checkpoint_out", replayOutput.resolveSibling(replayOutput.getFileName() + ".checkpoint.json").toString());
        budgetOptions.put("evidence_out", text(options, "out"));
        ObjectNode receipt = runResumable(budgetOptions);
        if (!"COMPLETE".equals(receipt.path("status").asText())) return receipt;
        return readObjectFile(Path.of(text(options, "out")), "deterministically evaluated staged evidence");
    }

    /** Freezes macro-last from reopened predecessor artifacts while charging statistics to the family budget. */
    public static ObjectNode freezeStagedMacro(ObjectNode options) {
        ObjectNode freeze = object(options, "freeze");
        if (!FREEZE_SCHEMA.equals(freeze.path("schema").asText()) || freeze.path("version").asInt(-1) != 1
                || !JsonHashes.ownHash(freeze).equals(freeze.path("content_sha256").asText())) {
            throw fail("freeze schema or outer content hash is invalid");
        }
        ObjectNode noMacroPlan = object(options, "no_macro_plan");
        ObjectNode coreReplay = object(options, "core_replay");
        ObjectNode coreEvidence = object(options, "core_evidence");
        ObjectNode noMacroReplay = object(options, "no_macro_replay");
        ObjectNode noMacroEvidence = object(options, "no_macro_evidence");
        if (!options.path("out").isTextual() || options.path("out").asText().isBlank()) {
            throw fail("staged macro plan requires an immutable --out path beneath the frozen physical root");
        }
        Path frozenPhysicalPath = Path.of(text(freeze, "physical_root")).toAbsolutePath().normalize();
        Path physicalRoot = canonicalExistingDirectory(frozenPhysicalPath, "frozen physical root");
        Path output = canonicalTargetWithinRoot(physicalRoot, frozenPhysicalPath,
                Path.of(text(options, "out")), "staged macro plan output");
        Path checkpointFile = options.hasNonNull("checkpoint_out")
                ? Path.of(text(options, "checkpoint_out")).toAbsolutePath().normalize()
                : output.resolveSibling(output.getFileName() + ".checkpoint.json");
        checkpointFile = canonicalTargetWithinRoot(physicalRoot, frozenPhysicalPath, checkpointFile,
                "staged macro plan checkpoint");
        Path projectRoot = canonicalExistingDirectory(Path.of(text(freeze, "project_root")), "project root");
        boolean synthetic = "SYNTHETIC_DEVELOPMENT_ONLY".equals(freeze.path("source_mode").asText());
        Path budgetRoot = synthetic ? physicalRoot : durableResearchRoot(projectRoot);
        ObjectNode replayRequest = object(noMacroReplay, "execution_request");
        ObjectNode boundInputs = JsonHashes.mapper().createObjectNode()
                .put("operation", "FREEZE_STAGED_MACRO_FROM_RECOMPUTED_NO_MACRO_EVIDENCE");
        boundInputs.set("staged_plan", noMacroPlan.deepCopy());
        boundInputs.set("replay_request", replayRequest.deepCopy());
        boundInputs.set("core_replay", coreReplay.deepCopy());
        boundInputs.set("core_evidence", coreEvidence.deepCopy());
        boundInputs.set("no_macro_replay", noMacroReplay.deepCopy());
        boundInputs.set("no_macro_evidence", noMacroEvidence.deepCopy());
        boundInputs.put("content_sha256", JsonHashes.ownHash(boundInputs));
        LiquidationV2ReplayCheckpointV1.FamilyScope scope = LiquidationV2ReplayCheckpointV1.FamilyScope.of(
                freeze.path("precommit").path("precommit_id").asText("liquidation-daily-stress-v002"),
                text(freeze, "precommit_sha256"), text(freeze, "profile_sha256"), text(freeze, "manifest_sha256"), budgetRoot);
        LiquidationV2ReplayCheckpointV1.RunBinding binding = LiquidationV2ReplayCheckpointV1.RunBinding.of(
                text(freeze, "content_sha256"), JsonHashes.canonicalSha256(boundInputs), projectRoot, physicalRoot,
                text(freeze, "manifest_sha256"), text(object(freeze, "executor_identity"), "content_sha256"),
                output, checkpointFile);
        try (LiquidationV2ReplayCheckpointV1.Session session = LiquidationV2ReplayCheckpointV1.openNewRun(
                scope, binding, System::nanoTime)) {
            try {
                LiquidationV2ReplayCheckpointV1.Segment segment = null;
                try {
                    segment = session.reserveSegment(
                            "STAGED_MACRO_PLAN_EVIDENCE_RECOMPUTE", CHECKPOINT_SEGMENT_RESERVATION_MILLIS);
                    verifyFreeze(freeze);
                    ObjectNode inventory = candidateInventory(object(freeze, "profile"));
                    LiquidationV2StagedCandidateInventoryV1.validateNoMacroPlan(
                            noMacroPlan, coreReplay, coreEvidence, inventory);
                    verifyReplayOutput(coreReplay, freeze);
                    verifyStagedReplayOutput(noMacroReplay, freeze, noMacroPlan);
                    requireSameReplayWindow(replayRequest, object(coreReplay, "execution_request"), freeze);
                    ObjectNode familyState = synthetic ? null
                            : LiquidationV2FamilyExposureAttemptV1.reopenFamilyExposureAttempts();
                    LiquidationV2StagedStatisticsV1.Evaluation evaluation = LiquidationV2StagedStatisticsV1.recompute(
                            freeze, noMacroPlan, coreReplay, noMacroReplay, null, null, familyState);
                    ObjectNode expectedEvidence = LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(
                            noMacroPlan, noMacroReplay, evaluation);
                    if (!JsonHashes.canonicalSha256(expectedEvidence).equals(JsonHashes.canonicalSha256(noMacroEvidence))) {
                        throw fail("staged macro plan evidence differs from budgeted predecessor statistics recomputation");
                    }
                    ObjectNode macroPlan = LiquidationV2StagedCandidateInventoryV1.freezeMacro(
                            noMacroPlan, noMacroReplay, noMacroEvidence, evaluation);
                    byte[] macroPlanBytes = JsonHashes.canonicalBytes(macroPlan);
                    String executionEnd = text(normalizedReplayWindow(replayRequest, freeze), "execution_end_exclusive");
                    segment.ensureWithinReservation();
                    segment.checkpoint(Instant.parse(executionEnd), macroPlan.path("content_sha256").asText(),
                            text(noMacroReplay, "ledger_sha256"), text(noMacroReplay, "account_curve_sha256"));
                    writeImmutable(physicalRoot, output, macroPlanBytes);
                    return macroPlan;
                } catch (RuntimeException error) {
                    if (segment != null && session.reservedComputeMillis() > 0) segment.abort();
                    throw error;
                }
            } catch (LiquidationV2ReplayCheckpointV1.ComputeIncompleteException incomplete) {
                return runOperationalReceipt(LiquidationV2ReplayCheckpointV1.COMPUTE_INCOMPLETE,
                        null, binding, session, checkpointFile, physicalRoot, JsonHashes.mapper().createObjectNode());
            }
        }
    }

    /** Run once and derive evidence from the in-process runner output, without accepting caller-authored PnL. */
    public static ObjectNode runAndEvaluate(ObjectNode options) {
        Path replayPath = Path.of(text(options, "replay_out")).toAbsolutePath().normalize();
        Path evidencePath = Path.of(text(options, "evidence_out")).toAbsolutePath().normalize();
        ObjectNode budgetOptions = options.deepCopy();
        budgetOptions.put("out", replayPath.toString()).put("evidence_out", evidencePath.toString());
        ObjectNode receipt = runResumable(budgetOptions);
        if (!"COMPLETE".equals(receipt.path("status").asText())) return receipt;
        ObjectNode replay = readObjectFile(replayPath, "replay output");
        ObjectNode evidence = readObjectFile(evidencePath, "evidence output");
        Path root = Path.of(text(object(options, "freeze"), "physical_root"));
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-run-evaluate-receipt/1").put("version", 1)
                .put("status", evidence.path("status").asText())
                .put("replay_path", root.relativize(replayPath).toString())
                .put("evidence_path", root.relativize(evidencePath).toString())
                .put("replay_sha256", replay.path("content_sha256").asText())
                .put("evidence_sha256", evidence.path("content_sha256").asText())
                .put("single_runner_pass", true).put("authoritative", false);
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    private static ObjectNode requestFromRunOptions(ObjectNode options) {
        ObjectNode request = JsonHashes.mapper().createObjectNode();
        for (String field : List.of("synthetic_smoke", "feature_warmup_start", "replay_start",
                "decision_end_exclusive", "execution_end_exclusive", "replay_end_exclusive")) {
            if (options.has(field)) request.set(field, options.path(field).deepCopy());
        }
        return request;
    }

    private static ObjectNode executeFrozenReplay(ObjectNode freeze, ObjectNode request, StagedConfig staged,
            List<PendingScenarioArtifact> pendingArtifacts) {
        ReplaySetup setup = openReplaySetup(freeze, request, staged);
        ObjectNode replay = new Engine(setup.profile(), setup.physical(), freeze, setup.featureFrom(),
                setup.decisionFrom(), setup.decisionEnd(), setup.executionEnd(), setup.synthetic(),
                null, null, staged).run();
        List<ObjectNode> scenarioRuns = new ArrayList<>();
        for (String scenarioId : List.of("fee_slippage", "funding_carry", "adverse_execution_gap",
                "liquidity_capacity", "venue_outage_blackout")) {
            scenarioRuns.add(new Engine(setup.profile(), setup.physical(), freeze, setup.featureFrom(),
                    setup.decisionFrom(), setup.decisionEnd(), setup.executionEnd(), setup.synthetic(),
                    setup.stressPolicy(), scenarioId, staged).run());
        }
        replay.set("stress_evaluation", stressEvaluation(replay, scenarioRuns, setup.stressPolicy(),
                freeze.path("precommit"), Path.of(freeze.path("physical_root").asText()).toAbsolutePath().normalize(), pendingArtifacts));
        replay.put("content_sha256", JsonHashes.ownHash(replay));
        return replay;
    }

    private record ReplaySetup(ObjectNode profile,
            LiquidationV2PhysicalDataV1.VerifiedDevelopmentSession physical,
            Instant featureFrom, Instant decisionFrom, Instant decisionEnd, Instant executionEnd,
            boolean synthetic, LiquidationV2StressPolicyV1.Policy stressPolicy) {}

    private record PendingScenarioArtifact(Path path, byte[] bytes) {
        private PendingScenarioArtifact { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    private static ReplaySetup openReplaySetup(ObjectNode freeze, ObjectNode request, StagedConfig staged) {
        verifyFreeze(freeze);
        ObjectNode profile = (ObjectNode) freeze.path("profile");
        ObjectNode manifest = (ObjectNode) freeze.path("physical_manifest");
        ObjectNode physicalOptions = JsonHashes.mapper().createObjectNode().put("root", freeze.path("physical_root").asText());
        physicalOptions.set("profile", profile.deepCopy());
        physicalOptions.set("manifest", manifest.deepCopy());
        LiquidationV2PhysicalDataV1.VerifiedDevelopmentSession physical =
                LiquidationV2PhysicalDataV1.openVerifiedDevelopment(physicalOptions);
        boolean synthetic = "SYNTHETIC_DEVELOPMENT_ONLY".equals(manifest.path("source_mode").asText());
        ObjectNode plan = (ObjectNode) manifest.path("plan");
        Instant featureFrom = Instant.parse(plan.path("source_start").asText());
        Instant decisionFrom = Instant.parse(plan.path("decision_start").asText());
        Instant allowedDecisionEnd = Instant.parse(plan.path("decision_end_exclusive").asText());
        Instant to = Instant.parse(plan.path("execution_end_exclusive").asText());
        Instant decisionEnd = allowedDecisionEnd;
        if (synthetic) {
            if (!request.path("synthetic_smoke").asBoolean(false)) throw fail("synthetic replay requires explicit synthetic_smoke=true");
            decisionFrom = Instant.parse(text(request, "replay_start"));
            featureFrom = request.hasNonNull("feature_warmup_start")
                    ? Instant.parse(text(request, "feature_warmup_start")) : Instant.parse(plan.path("source_start").asText());
            // The frozen seven-day limit controls only new stage-one opportunities. Open
            // positions keep their original lifecycle clock and need minute coverage through
            // their eventual exit, so staged smoke requests may extend execution separately.
            Instant legacyEnd = request.hasNonNull("replay_end_exclusive")
                    ? Instant.parse(text(request, "replay_end_exclusive"))
                    : Instant.parse(text(request, "execution_end_exclusive"));
            decisionEnd = request.hasNonNull("decision_end_exclusive")
                    ? Instant.parse(text(request, "decision_end_exclusive")) : legacyEnd;
            to = request.hasNonNull("execution_end_exclusive")
                    ? Instant.parse(text(request, "execution_end_exclusive")) : legacyEnd;
            if (!dayAligned(featureFrom) || !dayAligned(decisionFrom) || !dayAligned(decisionEnd) || !dayAligned(to)
                    || !featureFrom.isBefore(decisionFrom) || !decisionFrom.isBefore(decisionEnd)
                    || java.time.Duration.between(decisionFrom, decisionEnd).compareTo(java.time.Duration.ofDays(7)) > 0
                    || java.time.Duration.between(featureFrom, decisionFrom).toDays() < 92
                    || featureFrom.isBefore(Instant.parse(plan.path("source_start").asText()))
                    || decisionFrom.isBefore(Instant.parse(plan.path("decision_start").asText()))
                    || decisionEnd.isAfter(allowedDecisionEnd)
                    || to.isBefore(decisionEnd)
                    || to.isAfter(Instant.parse(plan.path("execution_end_exclusive").asText()))) {
                throw fail("synthetic smoke needs UTC-day-aligned feature warmup of at least 92 days, a decision window of at most seven days, and valid decision/execution windows within the frozen decision/execution scope");
            }
        } else {
            if (request.path("synthetic_smoke").asBoolean(false)) {
                throw fail("proxy replay cannot use synthetic_smoke mode");
            }
            String requestedWarmup = request.path("feature_warmup_start").asText("");
            if (!requestedWarmup.isBlank() && !featureFrom.toString().equals(requestedWarmup)) {
                throw fail("proxy replay warmup must match the frozen physical source start");
            }
            ObjectNode assessment = LiquidationInputQualificationV1.assessPhysicalDevelopment(physicalOptions);
            LiquidationInputQualificationV1.verifyPhysicalDevelopmentAssessment(physicalOptions, assessment);
            if (!assessment.path("development_replay_permitted").asBoolean(false)) {
                throw fail("proxy physical coverage is not complete enough for a development replay");
            }
            if (!decisionFrom.toString().equals(text(request, "replay_start"))
                    || !to.toString().equals(text(request, "replay_end_exclusive"))) {
                throw fail("proxy replay must use the frozen decision and complete execution envelope");
            }
        }
        if (!synthetic && (!decisionFrom.isBefore(allowedDecisionEnd) || !to.isAfter(allowedDecisionEnd))) {
            throw fail("proxy replay decision and execution envelopes must remain within their distinct frozen bounds");
        }
        if (staged != null && (!staged.sourceMode().equals(manifest.path("source_mode").asText())
                || !staged.sourceMode().equals(freeze.path("source_mode").asText()))) {
            throw fail("staged plan source mode differs from the reopened frozen source");
        }
        LiquidationV2StressPolicyV1.Policy stressPolicy = LiquidationV2StressPolicyV1.reopen((ObjectNode) freeze.path("precommit"));
        return new ReplaySetup(profile, physical, featureFrom, decisionFrom, decisionEnd, to,
                synthetic, stressPolicy);
    }

    private static ObjectNode stressEvaluation(ObjectNode baseline, List<ObjectNode> scenarioRuns,
            LiquidationV2StressPolicyV1.Policy policy, JsonNode precommit, Path physicalRoot,
            List<PendingScenarioArtifact> pendingArtifacts) {
        JsonNode required = precommit.path("experiment").path("acceptance").path("stress").path("required_scenarios");
        if (!required.isArray() || required.size() != scenarioRuns.size()) throw fail("runner stress inventory differs from frozen five-scenario policy");
        ObjectNode evaluation = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-stress-evaluation/1")
                .put("status", "RUNNER_EXECUTED_DEVELOPMENT_ONLY")
                .put("base_ledger_sha256", baseline.path("ledger_sha256").asText())
                .put("frozen_scenario_policy_sha256", JsonHashes.canonicalSha256(required))
                .put("stress_policy_sha256", policy.contentSha256())
                .put("all_scenarios_rerun_from_physical_observations", true)
                .put("no_posthoc_pnl_adjustment", true);
        ArrayNode results = evaluation.putArray("scenario_results");
        for (int index = 0; index < scenarioRuns.size(); index++) {
            JsonNode requiredScenario = required.get(index);
            ObjectNode run = scenarioRuns.get(index);
            String scenarioId = requiredScenario.path("id").asText();
            ObjectNode execution = object(run, "scenario_execution");
            if (!scenarioId.equals(execution.path("scenario_id").asText())
                    || !policy.contentSha256().equals(execution.path("stress_policy_sha256").asText())) {
                throw fail("scenario runner output is not bound to its frozen stress policy");
            }
            String artifactName = scenarioId + "-" + run.path("content_sha256").asText() + ".json";
            Path artifactPath = physicalRoot.resolve("scenario-runs").resolve(artifactName);
            byte[] artifactBytes = JsonHashes.canonicalBytes(run);
            if (pendingArtifacts == null) writeImmutable(physicalRoot, artifactPath, artifactBytes);
            else pendingArtifacts.add(new PendingScenarioArtifact(artifactPath, artifactBytes));
            ObjectNode row = results.addObject().put("scenario_id", scenarioId)
                    .put("runner_result_sha256", JsonHashes.sha256(artifactBytes))
                    .put("scenario_run_content_sha256", run.path("content_sha256").asText())
                    .put("runner_ledger_sha256", run.path("ledger_sha256").asText())
                    .put("runner_event_stream_sha256", run.path("event_stream_sha256").asText())
                    .put("transform_count", execution.path("transform_count").asLong())
                    .put("transform_chain_sha256", execution.path("transform_chain_sha256").asText())
                    .put("affected_position_count", execution.path("affected_position_count").asInt())
                    .put("outage_cancelled_entry_attempt_count", execution.path("outage_cancelled_entry_attempt_count").asInt())
                    .put("outage_deferred_exit_trigger_count", execution.path("outage_deferred_exit_trigger_count").asInt())
                    .put("outage_blocked_stop_update_count", execution.path("outage_blocked_stop_update_count").asInt());
            row.putObject("scenario_run_artifact").put("relative_path", physicalRoot.relativize(artifactPath).toString())
                    .put("sha256", JsonHashes.sha256(artifactBytes)).put("schema", REPLAY_SCHEMA);
            ArrayNode byCandidate = row.putArray("by_candidate");
            ObjectNode candidateStats = row.putObject("candidate_stats");
            ObjectNode routedStats = null;
            String selectedCandidate = baseline.path("candidate_id").asText(CANDIDATES.get(ROUTED));
            List<String> candidateIds = baseline.path("candidate_id").isTextual()
                    ? List.of(selectedCandidate) : CANDIDATES.subList(0, 3);
            for (String candidate : candidateIds) {
                ObjectNode stats = scenarioCandidateStats(run, candidate);
                byCandidate.add(stats);
                candidateStats.set(candidate, stats.deepCopy());
                if (selectedCandidate.equals(candidate)) routedStats = stats;
            }
            if (routedStats == null) throw fail("scenario runner omitted the routed candidate under test");
            row.put("completed_observations", routedStats.path("completed_observations").asInt())
                    .put("expectancy_r", routedStats.path("expectancy_r").asDouble())
                    .put("unresolved_count", routedStats.path("unresolved_count").asInt())
                    .put("coverage_blocked_count", routedStats.path("coverage_blocked_count").asInt())
                    .put("affected_position_count", routedStats.path("affected_position_count").asInt())
                    .put("minimum_observations", requiredScenario.path("minimum_observations").asInt())
                    .put("minimum_expectancy_r", requiredScenario.path("minimum_expectancy_r").asDouble())
                    .put("development_only", true);
            boolean blocked = routedStats.path("open_unresolved_count").asInt() > 0
                    || routedStats.path("coverage_blocked_count").asInt() > 0;
            boolean minimumsPass = routedStats.path("completed_observations").asInt()
                    >= requiredScenario.path("minimum_observations").asInt()
                    && routedStats.path("expectancy_r").asDouble()
                    >= requiredScenario.path("minimum_expectancy_r").asDouble();
            row.put("candidate_gate_status", blocked ? "BLOCKED_UNRESOLVED_OR_COVERAGE"
                    : minimumsPass ? "CANDIDATE_THRESHOLDS_MET_DEVELOPMENT_ONLY" : "INSUFFICIENT_OR_BELOW_THRESHOLD");
            if ("liquidity_capacity".equals(scenarioId)) {
                row.put("capacity_policy_matches_existing_account_limit", true)
                        .put("no_incremental_capacity_tightening", true);
            }
        }
        evaluation.put("content_sha256", JsonHashes.ownHash(evaluation));
        return evaluation;
    }

    private static ObjectNode scenarioCandidateStats(ObjectNode run, String candidateId) {
        int closed = 0, unresolved = 0, blocked = 0;
        double totalR = 0.0;
        for (JsonNode opportunity : run.path("opportunities")) {
            if (!candidateId.equals(opportunity.path("candidate_id").asText())) continue;
            String state = opportunity.path("outcome_state").asText();
            if ("CLOSED_TRADE".equals(state)) {
                double risk = opportunity.path("reference_risk_usdt").asDouble(Double.NaN);
                double pnl = opportunity.path("net_pnl_usdt").asDouble(Double.NaN);
                if (!Double.isFinite(risk) || risk <= 0.0 || !Double.isFinite(pnl)) {
                    throw fail("executed stress runner produced a closed trade without finite positive reference risk and PnL");
                }
                closed++;
                totalR += pnl / risk;
            } else if ("OPEN_UNRESOLVED".equals(state)) unresolved++;
            else if ("COVERAGE_BLOCKED".equals(state)) blocked++;
        }
        int affected = run.path("scenario_execution").path("affected_positions_by_candidate").path(candidateId).asInt();
        return JsonHashes.mapper().createObjectNode().put("candidate_id", candidateId)
                .put("completed_observations", closed).put("expectancy_r", closed == 0 ? 0.0 : totalR / closed)
                .put("open_unresolved_count", unresolved).put("coverage_blocked_count", blocked)
                .put("unresolved_count", unresolved + blocked).put("affected_position_count", affected);
    }

    /** Public evidence path: reopen the freeze, inputs, and v001 lineage before evaluating outcomes. */
    public static ObjectNode evaluate(ObjectNode options) {
        ObjectNode freeze = object(options, "freeze");
        ObjectNode suppliedReplay = object(options, "replay");
        ObjectNode request = object(suppliedReplay, "execution_request");
        Path physicalRoot = Path.of(text(freeze, "physical_root")).toAbsolutePath().normalize();
        String requestedReplayHash = suppliedReplay.path("content_sha256").asText("invalid");
        String suffix = JsonHashes.isSha256(requestedReplayHash) ? requestedReplayHash : JsonHashes.canonicalSha256(suppliedReplay);
        Path replayOutput = physicalRoot.resolve("recomputed-evaluations").resolve("replay-" + suffix + ".json");
        ObjectNode budgetOptions = options.deepCopy();
        budgetOptions.set("replay_request", request.deepCopy());
        budgetOptions.set("expected_replay", suppliedReplay.deepCopy());
        budgetOptions.put("out", replayOutput.toString());
        budgetOptions.put("checkpoint_out", replayOutput.resolveSibling(replayOutput.getFileName() + ".checkpoint.json").toString());
        budgetOptions.put("evidence_out", text(options, "out"));
        ObjectNode receipt = runResumable(budgetOptions);
        if (!"COMPLETE".equals(receipt.path("status").asText())) throw fail("evidence evaluation did not complete its bounded runner replay");
        return readObjectFile(Path.of(text(options, "out")), "deterministically evaluated evidence");
    }

    private static ObjectNode buildEvidence(ObjectNode freeze, ObjectNode replay) {
        return buildEvidence(freeze, replay, true);
    }

    private static ObjectNode buildEvidence(ObjectNode freeze, ObjectNode replay, boolean reopenScenarioArtifacts) {
        verifyFreeze(freeze);
        verifyReplayOutput(replay, freeze);
        if (reopenScenarioArtifacts) {
            LiquidationV2ReplayEvidenceV1.validateScenarioRunArtifacts(replay,
                    Path.of(freeze.path("physical_root").asText()).toAbsolutePath().normalize());
        }
        return LiquidationV2ReplayEvidenceV1.build(replay, evidenceInputs(freeze));
    }

    private static LiquidationV2ReplayEvidenceV1.FreezeInputs evidenceInputs(ObjectNode freeze) {
        ObjectNode lineage = object(freeze, "parent_lineage");
        return new LiquidationV2ReplayEvidenceV1.FreezeInputs(object(freeze, "physical_manifest"),
                object(freeze, "precommit"), object(freeze, "profile"), object(freeze, "executor_identity"),
                object(freeze, "candidate_inventory"), null, object(lineage, "parent_precommit"),
                object(lineage, "parent_freeze_manifest"), text(lineage, "parent_freeze_manifest_bytes"),
                text(lineage, "parent_feasibility_markdown"));
    }

    /** Reject caller-supplied artifacts inside the active initialization lease, before outcomes run. */
    private static void verifyExpectedReplayBeforeOutcomes(JsonNode expected, ObjectNode freeze, ObjectNode stagedPlan) {
        if (!expected.isObject()) throw fail("expected replay must be a JSON object");
        ObjectNode replay = (ObjectNode) expected;
        if (stagedPlan == null) {
            verifyReplayOutput(replay, freeze);
            LiquidationV2ReplayEvidenceV1.validateReplayStructure(replay, evidenceInputs(freeze));
        } else {
            verifyStagedReplayOutput(replay, freeze, stagedPlan);
        }
    }

    /**
     * Persist the frozen family attempts before the runner reads outcome rows. The replay binds only
     * stable attempt identities; custody-head status/cumulative K remain in the separate durable
     * receipt because they can legitimately advance between an original run and deterministic re-evaluation.
     */
    private static ObjectNode preOutcomeExposureRefs(ObjectNode freeze, ObjectNode stagedPlan) {
        ObjectNode core = LiquidationV2FamilyExposureAttemptV1.recordCurrentFreezeBeforeOutcomes(freeze);
        ObjectNode staged = stagedPlan == null ? null : LiquidationV2FamilyExposureAttemptV1.recordBeforeOutcomes(
                stagedPlan, text(stagedPlan, "candidate_id"), freeze);
        ArrayNode attempts = JsonHashes.mapper().createArrayNode();
        appendStableAttemptRefs(attempts, core);
        if (staged != null) appendStableAttemptRefs(attempts, staged);
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-pre-outcome-exposure-refs/1")
                .put("physical_freeze_sha256", text(freeze, "content_sha256"))
                .put("source_mode", text(freeze, "source_mode"))
                .put("recording_status", "PRE_OUTCOME_ATTEMPTS_DURABLY_RECORDED_OR_SYNTHETICALLY_SKIPPED")
                .put("attempt_count", attempts.size());
        result.set("attempts", attempts);
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    private static StagedPredecessor reopenStagedPredecessor(ObjectNode freeze, ObjectNode plan,
            ObjectNode stagedRequest, ObjectNode options, List<PendingScenarioArtifact> pendingArtifacts) {
        ObjectNode inventory = candidateInventory(object(freeze, "profile"));
        if (options.has("core_candidate_inventory")
                && !JsonHashes.canonicalSha256(inventory).equals(
                        JsonHashes.canonicalSha256(object(options, "core_candidate_inventory")))) {
            throw fail("staged predecessor candidate inventory differs from the current frozen inventory");
        }
        ObjectNode suppliedCoreReplay = object(options, "core_replay");
        ObjectNode suppliedCoreEvidence = object(options, "core_evidence");
        ObjectNode corePlan = null;
        if ("THREE_STAGE_NO_MACRO".equals(plan.path("mode_id").asText())) {
            LiquidationV2StagedCandidateInventoryV1.validateNoMacroPlan(plan, suppliedCoreReplay,
                    suppliedCoreEvidence, inventory);
            StagedPredecessor core = reexecuteCorePredecessor(freeze, stagedRequest,
                    suppliedCoreReplay, suppliedCoreEvidence, pendingArtifacts);
            LiquidationV2StagedCandidateInventoryV1.validateNoMacroPlan(plan, core.replay(),
                    core.evidence(), inventory);
            return core;
        }

        if (!"THREE_STAGE_MACRO".equals(plan.path("mode_id").asText())) {
            throw fail("staged runner does not recognize the frozen candidate mode");
        }
        corePlan = object(options, "predecessor_plan");
        ObjectNode suppliedNoMacroReplay = object(options, "predecessor_replay");
        ObjectNode suppliedNoMacroEvidence = object(options, "predecessor_evidence");
        LiquidationV2StagedCandidateInventoryV1.validateNoMacroPlan(corePlan, suppliedCoreReplay,
                suppliedCoreEvidence, inventory);
        StagedPredecessor core = reexecuteCorePredecessor(freeze, stagedRequest,
                suppliedCoreReplay, suppliedCoreEvidence, pendingArtifacts);
        LiquidationV2StagedCandidateInventoryV1.validateNoMacroPlan(corePlan, core.replay(),
                core.evidence(), inventory);

        verifyStagedReplayOutput(suppliedNoMacroReplay, freeze, corePlan);
        requireSameReplayWindow(stagedRequest, object(suppliedNoMacroReplay, "execution_request"), freeze);
        StagedConfig noMacro = StagedConfig.fromPlan(corePlan);
        ObjectNode noMacroRefs = preOutcomeExposureRefs(freeze, corePlan);
        ObjectNode actualNoMacroReplay = executeFrozenReplay(freeze,
                object(suppliedNoMacroReplay, "execution_request"), noMacro, pendingArtifacts);
        actualNoMacroReplay.set("pre_outcome_exposure_attempts", noMacroRefs);
        actualNoMacroReplay.put("content_sha256", JsonHashes.ownHash(actualNoMacroReplay));
        verifyStagedReplayOutput(actualNoMacroReplay, freeze, corePlan);
        if (!JsonHashes.canonicalSha256(actualNoMacroReplay).equals(JsonHashes.canonicalSha256(suppliedNoMacroReplay))) {
            throw fail("macro-last predecessor replay differs from deterministic physical no-macro runner output");
        }
        ObjectNode familyState = "SYNTHETIC_DEVELOPMENT_ONLY".equals(freeze.path("source_mode").asText())
                ? null : LiquidationV2FamilyExposureAttemptV1.reopenFamilyExposureAttempts();
        LiquidationV2StagedStatisticsV1.Evaluation evaluation = LiquidationV2StagedStatisticsV1.recompute(
                freeze, corePlan, core.replay(), actualNoMacroReplay, null, null, familyState);
        ObjectNode actualNoMacroEvidence = LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(
                corePlan, actualNoMacroReplay, evaluation);
        if (!JsonHashes.canonicalSha256(actualNoMacroEvidence).equals(JsonHashes.canonicalSha256(suppliedNoMacroEvidence))) {
            throw fail("macro-last predecessor evidence differs from recomputed physical runner statistics");
        }
        LiquidationV2StagedCandidateInventoryV1.validateMacroPlan(plan, corePlan,
                actualNoMacroReplay, actualNoMacroEvidence, evaluation);
        return new StagedPredecessor(corePlan, actualNoMacroReplay, actualNoMacroEvidence);
    }

    private static StagedPredecessor reexecuteCorePredecessor(ObjectNode freeze, ObjectNode stagedRequest,
            ObjectNode suppliedReplay, ObjectNode suppliedEvidence,
            List<PendingScenarioArtifact> pendingArtifacts) {
        verifyReplayOutput(suppliedReplay, freeze);
        requireSameReplayWindow(stagedRequest, object(suppliedReplay, "execution_request"), freeze);
        ObjectNode stableRefs = preOutcomeExposureRefs(freeze, null);
        ObjectNode actualReplay = executeFrozenReplay(freeze, object(suppliedReplay, "execution_request"), null, pendingArtifacts);
        actualReplay.set("pre_outcome_exposure_attempts", stableRefs);
        actualReplay.put("content_sha256", JsonHashes.ownHash(actualReplay));
        verifyReplayOutput(actualReplay, freeze);
        if (!JsonHashes.canonicalSha256(actualReplay).equals(JsonHashes.canonicalSha256(suppliedReplay))) {
            throw fail("staged core predecessor replay differs from deterministic physical runner output");
        }
        ObjectNode actualEvidence = buildEvidence(freeze, actualReplay, false);
        if (!JsonHashes.canonicalSha256(actualEvidence).equals(JsonHashes.canonicalSha256(suppliedEvidence))) {
            throw fail("staged core predecessor evidence differs from deterministic physical evaluation");
        }
        return new StagedPredecessor(null, actualReplay, actualEvidence);
    }

    private static void requireSameReplayWindow(ObjectNode stagedRequest, ObjectNode predecessorRequest,
            ObjectNode freeze) {
        ObjectNode staged = normalizedReplayWindow(stagedRequest, freeze);
        ObjectNode predecessor = normalizedReplayWindow(predecessorRequest, freeze);
        if (!JsonHashes.canonicalSha256(staged).equals(JsonHashes.canonicalSha256(predecessor))) {
            throw fail("staged candidate and its predecessor must use the same frozen decision and lifecycle execution windows");
        }
    }

    private static ObjectNode normalizedReplayWindow(ObjectNode request, ObjectNode freeze) {
        ObjectNode plan = object(freeze, "plan");
        boolean synthetic = "SYNTHETIC_DEVELOPMENT_ONLY".equals(freeze.path("source_mode").asText());
        String featureWarmup = request.path("feature_warmup_start").asText("");
        String replayStart = request.path("replay_start").asText("");
        String replayEnd = request.path("replay_end_exclusive").asText("");
        String executionEnd = request.path("execution_end_exclusive").asText("");
        String decisionEnd = request.path("decision_end_exclusive").asText("");
        if (synthetic) {
            if (featureWarmup.isBlank()) featureWarmup = plan.path("source_start").asText();
            if (replayEnd.isBlank()) replayEnd = executionEnd;
            if (executionEnd.isBlank()) executionEnd = replayEnd;
            if (replayEnd.isBlank()) replayEnd = executionEnd;
            if (decisionEnd.isBlank()) decisionEnd = replayEnd;
        } else {
            featureWarmup = plan.path("source_start").asText();
            replayStart = plan.path("decision_start").asText();
            decisionEnd = plan.path("decision_end_exclusive").asText();
            executionEnd = plan.path("execution_end_exclusive").asText();
        }
        return JsonHashes.mapper().createObjectNode()
                .put("synthetic_smoke", synthetic)
                .put("feature_warmup_start", featureWarmup)
                .put("replay_start", replayStart)
                .put("decision_end_exclusive", decisionEnd)
                .put("execution_end_exclusive", executionEnd);
    }

    private static void appendStableAttemptRefs(ArrayNode destination, ObjectNode receipt) {
        if (!JsonHashes.ownHash(receipt).equals(receipt.path("content_sha256").asText())) {
            throw fail("pre-outcome exposure-attempt receipt failed its own content hash");
        }
        for (JsonNode attemptNode : receipt.path("attempts")) {
            ObjectNode attempt = (ObjectNode) attemptNode;
            ObjectNode reference = JsonHashes.mapper().createObjectNode()
                    .put("candidate_id", text(attempt, "candidate_id"))
                    .put("attempt_freeze_sha256", text(attempt, "attempt_freeze_sha256"))
                    .put("physical_freeze_sha256", text(attempt, "physical_freeze_sha256"))
                    .put("behavior_sha256", text(attempt, "behavior_sha256"));
            destination.add(reference);
        }
    }

    private static void verifyReplayOutput(ObjectNode replay, ObjectNode freeze) {
        ObjectNode manifest = object(freeze, "physical_manifest");
        if (!REPLAY_SCHEMA.equals(replay.path("schema").asText()) || replay.path("version").asInt(-1) != 1
                || !JsonHashes.ownHash(replay).equals(replay.path("content_sha256").asText())
                || !manifest.path("content_sha256").asText().equals(replay.path("manifest_sha256").asText())
                || !manifest.path("dataset_root_sha256").asText().equals(replay.path("dataset_root_sha256").asText())
                || replay.path("authoritative").asBoolean(true) || replay.path("wfo_permitted").asBoolean(true)
                || replay.path("sealed_confirmation_permitted").asBoolean(true)
                || replay.path("prospective_live_permitted").asBoolean(true)
                || replay.path("trade_authorization_permitted").asBoolean(true)) {
            throw fail("replay output is not self-hashed and bound to the frozen development inputs");
        }
        ObjectNode executionRequest = object(replay, "execution_request");
        if (executionRequest.path("synthetic_smoke").asBoolean()
                        != "SYNTHETIC_DEVELOPMENT_ONLY".equals(manifest.path("source_mode").asText())
                || !text(replay, "feature_warmup_start").equals(text(executionRequest, "feature_warmup_start"))
                || !text(replay, "replay_start").equals(text(executionRequest, "replay_start"))
                || !text(replay, "replay_end_exclusive").equals(text(executionRequest, "replay_end_exclusive"))
                || !text(replay, "decision_end_exclusive").equals(text(executionRequest, "decision_end_exclusive"))
                || !text(replay, "replay_end_exclusive").equals(text(executionRequest, "execution_end_exclusive"))
                || !text(replay, "default_stage_add_macro_gate_policy").equals(CORE_ADDITION_MACRO_POLICY)) {
            throw fail("replay request, disclosed macro policy or frozen source mode does not match its results");
        }
        ObjectNode ledger = object(replay, "ledger");
        if (!JsonHashes.ownHash(ledger).equals(ledger.path("content_sha256").asText())
                || !ledger.path("content_sha256").asText().equals(replay.path("ledger_sha256").asText())) {
            throw fail("replay ledger is not bound by its own content hash");
        }
        JsonNode identityRows = replay.path("event_stream_identity");
        if (!identityRows.isArray()) throw fail("event stream identity rows are absent");
        ArrayNode identities = (ArrayNode) identityRows;
        long eventCount = 0;
        for (JsonNode identity : identities) {
            long count = identity.path("event_count").asLong(-1);
            if (count < 0) throw fail("event stream identity has an invalid count");
            eventCount += count;
        }
        if (!JsonHashes.canonicalSha256(identities).equals(replay.path("event_stream_sha256").asText())
                || eventCount != replay.path("event_count").asLong(-1)) {
            throw fail("event stream identity/count changed after replay");
        }
        JsonNode accounts = ledger.path("accounts");
        if (!accounts.isArray() || accounts.size() != TRADED_ACCOUNTS.size()) throw fail("ledger must retain the routed and two control accounts");
        JsonNode routedCurve = accounts.path(ROUTED).path("account_curve");
        if (!JsonHashes.canonicalSha256(routedCurve).equals(replay.path("account_curve_sha256").asText())
                || !JsonHashes.canonicalSha256(routedCurve).equals(JsonHashes.canonicalSha256(replay.path("account_curve")))) {
            throw fail("replay account curve differs from the frozen routed ledger path");
        }
        ObjectNode path = object(replay, "account_path_summary");
        if (!JsonHashes.ownHash(path).equals(path.path("content_sha256").asText())
                || !ledger.path("content_sha256").asText().equals(path.path("ledger_sha256").asText())) {
            throw fail("adverse account-path summary is not bound to the replay ledger");
        }
        verifyEvaluatedCandidates(replay, freeze);
    }

    private static void verifyStagedReplayOutput(ObjectNode replay, ObjectNode freeze, ObjectNode plan) {
        verifyFreeze(freeze);
        StagedConfig staged = StagedConfig.fromPlan(plan);
        ObjectNode ledger = object(replay, "ledger");
        if (!REPLAY_SCHEMA.equals(replay.path("schema").asText()) || replay.path("version").asInt(-1) != 1
                || !JsonHashes.ownHash(replay).equals(replay.path("content_sha256").asText())
                || !JsonHashes.ownHash(ledger).equals(ledger.path("content_sha256").asText())
                || !ledger.path("content_sha256").asText().equals(replay.path("ledger_sha256").asText())
                || !plan.path("content_sha256").asText().equals(replay.path("plan_sha256").asText())
                || !plan.path("anchor_inventory_sha256").asText().equals(replay.path("anchor_inventory_sha256").asText())
                || !staged.candidateId().equals(replay.path("candidate_id").asText())
                || !staged.modeId().equals(replay.path("mode_id").asText())
                || !staged.macroGatePolicy().name().equals(replay.path("macro_gate_policy").asText())
                || !staged.sourceMode().equals(replay.path("source_mode").asText())
                || !freeze.path("manifest_sha256").asText().equals(replay.path("manifest_sha256").asText())
                || !replay.path("opportunities").isArray() || !replay.path("stage_attempts").isArray()
                || !replay.path("evaluated_candidates").isArray() || replay.path("evaluated_candidates").size() != 1
                || !staged.candidateId().equals(replay.path("evaluated_candidates").path(0).path("candidate_id").asText())
                || !ledger.path("accounts").isArray() || ledger.path("accounts").size() != 1
                || !replay.path("execution_request").isObject()) {
            throw fail("staged replay is not self-hashed or is detached from its frozen candidate, plan, source, or account");
        }
        if (!JsonHashes.canonicalSha256(plan).equals(JsonHashes.canonicalSha256(replay.path("staged_plan")))) {
            throw fail("staged replay does not retain the exact frozen plan");
        }
        LiquidationV2StagedCandidateInventoryV1.validateAnchoredOutcomes(plan, staged.modeId(), (ArrayNode) replay.path("opportunities"));
        JsonNode attempts = replay.path("stage_attempts");
        Set<String> attemptIds = new HashSet<>();
        for (JsonNode row : attempts) {
            if (!staged.candidateId().equals(row.path("candidate_id").asText())
                    || !row.path("stage").isIntegralNumber() || row.path("stage").asInt() < 2
                    || row.path("attempt_id").asText().isBlank() || !attemptIds.add(row.path("attempt_id").asText())) {
                throw fail("staged replay has malformed or duplicate addition-attempt rows");
            }
        }
    }

    private static void verifyEvaluatedCandidates(ObjectNode replay, ObjectNode freeze) {
        JsonNode frozen = freeze.path("candidate_inventory").path("current_candidates");
        JsonNode evaluated = replay.path("evaluated_candidates");
        if (!frozen.isArray() || !evaluated.isArray() || frozen.size() != evaluated.size()) {
            throw fail("replay must enumerate every frozen candidate, including the diagnostic arm");
        }
        Map<String, Long> opportunityCounts = new HashMap<>();
        for (JsonNode row : replay.path("opportunities")) {
            opportunityCounts.merge(row.path("candidate_id").asText(), 1L, Long::sum);
        }
        String scope = "SYNTHETIC_DEVELOPMENT_ONLY".equals(replay.path("source_mode").asText())
                ? "SYNTHETIC_FIXTURE_EXECUTED" : "PROXY_RETROSPECTIVE_DIAGNOSTIC_EXECUTED";
        for (int index = 0; index < frozen.size(); index++) {
            JsonNode expected = frozen.get(index), actual = evaluated.get(index);
            String id = expected.path("candidate_id").asText();
            if (!actual.path("candidate_id").asText().equals(id)
                    || !actual.path("variant").asText().equals(expected.path("variant").asText())
                    || actual.path("audit_only").asBoolean() != expected.path("audit_only").asBoolean()
                    || !actual.path("execution_scope").asText().equals(scope)
                    || actual.path("opportunity_count").asLong(-1) != opportunityCounts.getOrDefault(id, 0L)) {
                throw fail("replay evaluated-candidate inventory differs from frozen candidate exposure");
            }
        }
    }

    private static final class Engine {
        private final ObjectNode profile, freeze, manifest;
        private final LiquidationV2PhysicalDataV1.VerifiedDevelopmentSession physical;
        private final Instant featureFrom, decisionFrom, decisionEnd, to;
        private final boolean synthetic;
        private final String macroAvailabilityBasis;
        private final LiquidationV2StressPolicyV1.Policy stressPolicy;
        private final String stressScenarioId;
        private final StagedConfig staged;
        private final LiquidationStructureRouterV1.Router routed;
        private final PriorityQueue<Scheduled> timeline = new PriorityQueue<>(SCHEDULE_ORDER);
        private final PriorityQueue<Feature> featureBuffer = new PriorityQueue<>(FEATURE_ORDER);
        private final LiquidationStructureRouterV1.Router diagnostic = new LiquidationStructureRouterV1.Router(
                LiquidationStructureRouterV1.Variant.PRICE_OI_ONLY_EVENT_DIAGNOSTIC);
        private final List<LiquidationPortfolioAccountingV1.AccountSession> accounts = new ArrayList<>();
        private final Map<String, ObjectNode> opportunities = new LinkedHashMap<>();
        private final List<ObjectNode> diagnostics = new ArrayList<>(), routeAudit = new ArrayList<>(), gaps = new ArrayList<>();
        private final List<ObjectNode> stageAttemptRows = new ArrayList<>();
        private final Map<String, ObjectNode> stageAttemptByIntent = new HashMap<>();
        private final Map<String, Pending> pending = new HashMap<>();
        private final Map<String, Trade> controlTrades = new HashMap<>();
        private final Map<String, ObjectNode> stagedAnchorByPair = new HashMap<>();
        private final Map<String, String> stagedPairBySetup = new HashMap<>();
        private final Map<Long, List<ObjectNode>> stagedAnchorsByDecision = new TreeMap<>();
        private final Set<String> injectedStageOneIntentIds = new HashSet<>();
        private final Map<String, TreeMap<Instant, LiquidationStructureRouterV1.Bar>> h4 = new HashMap<>();
        private final Map<String, List<ObjectNode>> metadata;
        private final Map<String, ObjectNode> initialMetadata = new HashMap<>();
        private final Map<String, ObjectNode> metadataEffectiveAtDecisionStart = new HashMap<>();
        private final Set<Integer> frozenAccounts = new HashSet<>();
        private final Map<Integer, Set<String>> frozenAssets = new HashMap<>();
        private final Map<String, ObjectNode> activeExecution = new HashMap<>(), activeMarks = new HashMap<>();
        private final Map<String, ObjectNode> previousCompletedExecution = new HashMap<>();
        private long featuresRead, minutePairsRead, fundingRowsRead, metadataRowsRead;
        private long stressTransformCount;
        private String stressTransformChain = "0".repeat(64);
        private final ArrayNode stressTransformRows = JsonHashes.mapper().createArrayNode();
        private int stressAffectedPositions, stressBlockedEntries;
        private int outageDeferredExitTriggers, outageBlockedStopUpdates;
        private final Set<String> stressAffectedPositionIds = new HashSet<>();
        private final Set<String> outageExposureIds = new HashSet<>();
        private final Map<String, Integer> stressBlockedByCandidate = new HashMap<>();
        private final Set<String> qualifiedStressEventIds = new HashSet<>();
        private final Set<String> outageDecisionKeys = new HashSet<>();
        private final List<ObjectNode> qualifiedStressEventInventory = new ArrayList<>(), outageIntervals = new ArrayList<>();
        private final List<ObjectNode> outageDecisions = new ArrayList<>(), outagePositionExposures = new ArrayList<>();
        private LocalDate nextFeatureDate;
        private boolean initialized;
        private boolean terminalized;

        Engine(ObjectNode profile, LiquidationV2PhysicalDataV1.VerifiedDevelopmentSession physical,
                ObjectNode freeze, Instant featureFrom, Instant decisionFrom, Instant decisionEnd,
                Instant to, boolean synthetic, LiquidationV2StressPolicyV1.Policy stressPolicy,
                String stressScenarioId) {
            this(profile, physical, freeze, featureFrom, decisionFrom, decisionEnd, to, synthetic,
                    stressPolicy, stressScenarioId, null);
        }

        Engine(ObjectNode profile, LiquidationV2PhysicalDataV1.VerifiedDevelopmentSession physical,
                ObjectNode freeze, Instant featureFrom, Instant decisionFrom, Instant decisionEnd,
                Instant to, boolean synthetic, LiquidationV2StressPolicyV1.Policy stressPolicy,
                String stressScenarioId, StagedConfig staged) {
            this.profile = profile.deepCopy(); this.physical = physical; this.freeze = freeze.deepCopy();
            this.manifest = physical.manifest(); this.featureFrom = featureFrom; this.decisionFrom = decisionFrom;
            this.decisionEnd = decisionEnd; this.to = to; this.synthetic = synthetic;
            this.stressPolicy = stressPolicy; this.stressScenarioId = stressScenarioId;
            this.staged = staged;
            this.routed = staged == null
                    ? new LiquidationStructureRouterV1.Router(LiquidationStructureRouterV1.Variant.ROUTED_REVERSAL_CONTINUATION,
                            LiquidationStructureRouterV1.MacroGatePolicy.REQUIRE_MACRO_CONFIRMATION)
                    : LiquidationStructureRouterV1.Router.anchoredStaged(staged.macroGatePolicy());
            if ((stressPolicy == null) != (stressScenarioId == null)) throw fail("stress scenario requires its reopened frozen policy");
            if (stressPolicy != null) stressPolicy.validate();
            this.macroAvailabilityBasis = macroAvailabilityBasis(this.manifest, synthetic, this.freeze.path("precommit"));
            List<ObjectNode> metadataRows = LiquidationV2PhysicalDataV1.readRoleRows(physical, "metadata",
                    featureFrom.toString(), to.toString(), ASSETS);
            metadataRowsRead += metadataRows.size();
            this.metadata = groupMetadata(metadataRows);
            if ("fee_slippage".equals(stressScenarioId)) scaleMetadataCosts();
            for (String asset : ASSETS) {
                List<ObjectNode> intervals = metadata.getOrDefault(asset, List.of());
                ObjectNode current = intervals.stream().filter(row -> epoch(row, "effective_from") <= decisionFrom.toEpochMilli()
                        && decisionFrom.toEpochMilli() < epoch(row, "effective_until")).findFirst().orElse(null);
                // The account needs a valid flat-position shell before the first effective
                // metadata row. These conservative placeholder values are never executable:
                // metadataCovers() blocks all fills until a timestamp-effective row arrives.
                initialMetadata.put(asset, current == null ? placeholderMetadata(asset) : current);
                if (current != null) metadataEffectiveAtDecisionStart.put(asset, current);
                h4.put(asset, new TreeMap<>());
            }
            ObjectNode request = accountRequest(initialMetadata);
            for (int i = 0; i < (staged == null ? 3 : 1); i++) {
                accounts.add(LiquidationPortfolioAccountingV1.startStreamingSession(request.deepCopy(), null));
            }
            if (staged != null) for (ObjectNode anchor : staged.anchors()) {
                String pairId = text(anchor, "pair_id");
                if (stagedAnchorByPair.putIfAbsent(pairId, anchor.deepCopy()) != null) {
                    throw fail("staged plan contains duplicate anchor pair identities");
                }
                long time = Instant.parse(text(anchor, "decision_time")).toEpochMilli();
                stagedAnchorsByDecision.computeIfAbsent(time, ignored -> new ArrayList<>()).add(anchor.deepCopy());
            }
            stagedAnchorsByDecision.values().forEach(rows -> rows.sort(Comparator.<ObjectNode>comparingInt(row -> assetRank(row.path("asset").asText()))
                    .thenComparing(row -> row.path("pair_id").asText())));
        }

        private void scaleMetadataCosts() {
            for (String asset : ASSETS) for (ObjectNode interval : metadata.getOrDefault(asset, List.of())) {
                double fee = number(interval, "taker_fee_rate"), slip = number(interval, "slippage_rate");
                ObjectNode transform = stressPolicy.scaleTradingCostRates(fee, slip).toJson();
                recordStressTransform(transform);
                interval.put("taker_fee_rate", transform.path("stressed_fee_rate").asDouble());
                interval.put("slippage_rate", transform.path("stressed_slippage_rate").asDouble());
            }
        }

        private void recordStressTransform(ObjectNode transform) {
            if (stressPolicy == null) return;
            if (!transform.path("source_policy_sha256").asText().equals(stressPolicy.contentSha256())
                    || !"POLICY_TRANSFORM_ONLY_NOT_EXECUTED".equals(transform.path("status").asText())) {
                throw fail("runner stress transform is not bound to its frozen policy");
            }
            stressTransformChain = JsonHashes.sha256(stressTransformChain + "\n" + JsonHashes.canonicalSha256(transform));
            stressTransformCount++;
            stressTransformRows.add(transform.deepCopy());
        }

        ObjectNode run() {
            advanceTo(to);
            if (!terminalized) {
                terminalizeOpenOutcomes();
                terminalized = true;
            }
            return result();
        }

        /** Advance causal state to an exclusive UTC-midnight boundary without closing unresolved rows. */
        ObjectNode snapshotAt(Instant boundaryExclusive) {
            advanceTo(boundaryExclusive);
            if (terminalized) throw fail("a terminalized replay cannot be used as a resumable prefix");
            return result();
        }

        private void advanceTo(Instant boundaryExclusive) {
            if (boundaryExclusive.isBefore(featureFrom) || boundaryExclusive.isAfter(to)
                    || !dayAligned(boundaryExclusive)) {
                throw fail("replay checkpoint boundary must be a UTC midnight within the frozen execution envelope");
            }
            if (!initialized) {
                seedCurrentMetadata();
                nextFeatureDate = featureFrom.atZone(ZoneOffset.UTC).toLocalDate();
                initialized = true;
            }
            LocalDate stop = boundaryExclusive.atZone(ZoneOffset.UTC).toLocalDate();
            if (stop.isBefore(nextFeatureDate)) throw fail("replay engine cannot advance backward from its current prefix");
            LocalDate last = to.atZone(ZoneOffset.UTC).toLocalDate();
            while (nextFeatureDate.isBefore(stop) && nextFeatureDate.isBefore(last)) {
                LocalDate date = nextFeatureDate;
                long start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), end = start + DAY;
                loadFeatureDay(start, end, featureBuffer);
                long executionStart = Math.max(start, decisionFrom.toEpochMilli());
                long executionEnd = Math.min(end, boundaryExclusive.toEpochMilli());
                if (executionStart < executionEnd) {
                    loadMinuteDay(executionStart, executionEnd, timeline);
                    loadFundingDay(executionStart, executionEnd, timeline);
                    loadMetadataDay(executionStart, executionEnd, timeline);
                }
                while (!featureBuffer.isEmpty() && featureBuffer.peek().time <= end) {
                    Feature feature = featureBuffer.poll();
                    timeline.add(Scheduled.feature(feature.time, feature.observation));
                }
                runTimeline(timeline, Math.min(end, boundaryExclusive.toEpochMilli()));
                nextFeatureDate = nextFeatureDate.plusDays(1);
            }
        }

        private void terminalizeOpenOutcomes() {
            for (ObjectNode row : opportunities.values()) {
                String state = row.path("outcome_state").asText();
                if ("PENDING_EXECUTION".equals(state)) {
                    row.put("outcome_state", "COVERAGE_BLOCKED").putNull("outcome_available_time");
                    reason(row, "NO_ELIGIBLE_OPEN_WITHIN_EXECUTION_ENVELOPE");
                } else if ("OPEN_TRADE".equals(state)) {
                    row.put("outcome_state", "OPEN_UNRESOLVED").putNull("outcome_available_time");
                    reason(row, "OPEN_EXPOSURE_AT_EXECUTION_ENVELOPE_END");
                }
            }
            for (ObjectNode attempt : stageAttemptRows) if ("PENDING_EXECUTION".equals(attempt.path("outcome_state").asText())) {
                attempt.put("outcome_state", "COVERAGE_BLOCKED").putNull("outcome_available_time");
                reason(attempt, "NO_ELIGIBLE_OPEN_WITHIN_EXECUTION_ENVELOPE");
            }
        }

        private void seedCurrentMetadata() {
            for (String asset : ASSETS) {
                ObjectNode row = metadataEffectiveAtDecisionStart.get(asset);
                if (row == null) continue;
                ObjectNode event = accountEvent("METADATA", asset, decisionFrom.toEpochMilli(), row);
                for (LiquidationPortfolioAccountingV1.AccountSession account : accounts) account.accept(event.deepCopy());
            }
        }

        private void loadFeatureDay(long start, long end, PriorityQueue<Feature> buffer) {
            List<ObjectNode> rows = LiquidationV2PhysicalDataV1.readRoleRows(physical, "feature",
                    Instant.ofEpochMilli(start).toString(), Instant.ofEpochMilli(end).toString(), List.of());
            Map<String, DailySidePair> daily = new TreeMap<>();
            for (ObjectNode row : rows) {
                featuresRead++;
                String series = row.path("series_id").asText(), asset = row.path("asset").asText();
                long event = epoch(row, "event_time"), available = epoch(row, "availability_time");
                if ("daily_liquidation_usd".equals(series)) {
                    DailySidePair pair = daily.computeIfAbsent(asset + "|" + event, ignored -> new DailySidePair(asset, event, available));
                    pair.available = Math.max(pair.available, available);
                    if ("LONG".equals(row.path("side").asText())) pair.longUsd = number(row, "value");
                    else if ("SHORT".equals(row.path("side").asText())) pair.shortUsd = number(row, "value");
                    else throw fail("daily stress series must retain LONG/SHORT side identity");
                } else if ("open_interest_base".equals(series)) {
                    buffer.add(feature(new LiquidationStructureRouterV1.OpenInterest(asset, Instant.ofEpochMilli(event),
                            Instant.ofEpochMilli(available), number(row, "value"), series)));
                } else if ("price_ohlc".equals(series)) {
                    if (row.path("open").isNull() || row.path("high").isNull() || row.path("low").isNull() || row.path("close").isNull()) continue;
                    LiquidationStructureRouterV1.Timeframe timeframe = switch (row.path("timeframe").asText()) {
                        case "4h" -> LiquidationStructureRouterV1.Timeframe.FOUR_HOUR;
                        case "1h" -> LiquidationStructureRouterV1.Timeframe.ONE_HOUR;
                        default -> null;
                    };
                    if (timeframe != null) buffer.add(feature(new LiquidationStructureRouterV1.Bar(asset, timeframe,
                            Instant.ofEpochMilli(event), Instant.ofEpochMilli(available), number(row, "open"),
                            number(row, "high"), number(row, "low"), number(row, "close"), series)));
                } else if ("sp500_close".equals(series)) {
                    boolean receiptBound = LiquidationV2PhysicalDataV1.macroAvailabilityReceiptBound(physical, event);
                    buffer.add(feature(new LiquidationStructureRouterV1.MacroClose(Instant.ofEpochMilli(event),
                            Instant.ofEpochMilli(available), number(row, "close"),
                            receiptBound && !"UNKNOWN".equals(macroAvailabilityBasis), series)));
                }
            }
            for (DailySidePair pair : daily.values()) {
                if (Double.isNaN(pair.longUsd) || Double.isNaN(pair.shortUsd)) {
                    routeAudit.add(JsonHashes.mapper().createObjectNode().put("type", "INCOMPLETE_DAILY_SIDE_PAIR")
                            .put("asset", pair.asset).put("event_time", Instant.ofEpochMilli(pair.event).toString())
                            .put("policy", "DO_NOT_ZERO_FILL"));
                    continue;
                }
                var observation = new LiquidationStructureRouterV1.DailyLiquidation(pair.asset,
                        Instant.ofEpochMilli(pair.event).atZone(ZoneOffset.UTC).toLocalDate(),
                        pair.longUsd, pair.shortUsd, Instant.ofEpochMilli(pair.available), "daily_liquidation_usd");
                buffer.add(feature(observation));
            }
        }

        private void loadMinuteDay(long start, long end, PriorityQueue<Scheduled> timeline) {
            List<ObjectNode> execution = LiquidationV2PhysicalDataV1.readRoleRows(physical, "execution",
                    Instant.ofEpochMilli(start).toString(), Instant.ofEpochMilli(end).toString(), ASSETS);
            List<ObjectNode> marks = LiquidationV2PhysicalDataV1.readRoleRows(physical, "mark",
                    Instant.ofEpochMilli(start).toString(), Instant.ofEpochMilli(end).toString(), ASSETS);
            Map<String, ObjectNode> trades = keyed(execution, "open_time"), markRows = keyed(marks, "timestamp");
            minutePairsRead += Math.min(execution.size(), marks.size());
            activeExecution.clear(); activeMarks.clear();
            previousCompletedExecution.forEach((asset, row) -> {
                if (epoch(row, "open_time") == start - MINUTE) activeExecution.put(key(asset, start - MINUTE), row);
            });
            execution.forEach(row -> activeExecution.put(key(row.path("asset").asText(), epoch(row, "open_time")), row));
            marks.forEach(row -> activeMarks.put(key(row.path("asset").asText(), epoch(row, "timestamp")), row));
            for (String asset : ASSETS) for (long time = start; time < end; time += MINUTE) {
                ObjectNode trade = trades.get(key(asset, time)), mark = markRows.get(key(asset, time));
                if (trade == null || mark == null) {
                    timeline.add(Scheduled.gap(time, asset, trade == null ? "MISSING_EXECUTION_MINUTE" : "MISSING_MARK_MINUTE"));
                    continue;
                }
                timeline.add(Scheduled.account(time, accountEvent("MARK", asset, time,
                        JsonHashes.mapper().createObjectNode().put("price", number(mark, "open")))));
                timeline.add(Scheduled.account(time, barEvent("BAR_PRE", asset, time, trade, mark)));
                timeline.add(Scheduled.account(time + MINUTE, barEvent("BAR_POST", asset, time + MINUTE, trade, mark)));
            }
            for (ObjectNode row : execution) {
                long time = epoch(row, "open_time");
                if (time < end) {
                    String asset = row.path("asset").asText();
                    ObjectNode previous = previousCompletedExecution.get(asset);
                    if (previous == null || epoch(previous, "open_time") < time) previousCompletedExecution.put(asset, row.deepCopy());
                }
            }
        }

        private void loadFundingDay(long start, long end, PriorityQueue<Scheduled> timeline) {
            List<ObjectNode> rows = LiquidationV2PhysicalDataV1.readRoleRows(physical, "funding",
                    Instant.ofEpochMilli(start).toString(), Instant.ofEpochMilli(end).toString(), ASSETS);
            fundingRowsRead += rows.size();
            for (ObjectNode row : rows) timeline.add(Scheduled.account(epoch(row, "settlement_time"),
                    accountEvent("FUNDING", row.path("asset").asText(), epoch(row, "settlement_time"), row)));
        }

        private void loadMetadataDay(long start, long end, PriorityQueue<Scheduled> timeline) {
            for (String asset : ASSETS) for (ObjectNode group : metadata.getOrDefault(asset, List.of())) {
                long at = epoch(group, "effective_from");
                if (at > decisionFrom.toEpochMilli() && at >= start && at < end) {
                    timeline.add(Scheduled.account(at, accountEvent("METADATA", asset, at, group)));
                }
            }
        }

        private void runTimeline(PriorityQueue<Scheduled> timeline, long boundary) {
            while (!timeline.isEmpty() && timeline.peek().time < boundary) {
                long at = timeline.peek().time;
                List<Scheduled> bucket = new ArrayList<>();
                while (!timeline.isEmpty() && timeline.peek().time == at) bucket.add(timeline.poll());
                bucket.sort(SCHEDULE_ORDER);
                boolean outageScenario = stressPolicy != null && "venue_outage_blackout".equals(stressScenarioId);
                // In the outage scenario, first expose all market features available at this timestamp, then
                // settle funding. That makes a blackout beginning exactly at its qualified availability
                // inclusive. The settlement itself keeps its actual timestamp; this is only the declared
                // deterministic tie rule for equal-time feature and funding events.
                for (Scheduled item : bucket) if (item.event != null && accountOrder(item.event) < 4
                        && !(outageScenario && "FUNDING".equals(item.event.path("type").asText()))) {
                    applyAccount(item.event, at);
                }
                for (Scheduled item : bucket) if (item.kind == Kind.GAP) handleGap(item);
                for (Scheduled item : bucket) if (item.kind == Kind.CALLBACK) acknowledgeNoFill(item);
                List<Scheduled> featuresAtTime = bucket.stream().filter(item -> item.kind == Kind.FEATURE).toList();
                List<ObjectNode> stops = new ArrayList<>();
                for (Scheduled item : featuresAtTime) processFeature(item.observation, at, stops, timeline);
                if (outageScenario) for (Scheduled item : bucket) if (item.event != null
                        && "FUNDING".equals(item.event.path("type").asText())) applyAccount(item.event, at);
                List<Scheduled> high = new ArrayList<>(bucket.stream().filter(item -> item.event != null && accountOrder(item.event) >= 4).toList());
                for (ObjectNode stop : stops) high.add(Scheduled.account(at, stop));
                high.sort(SCHEDULE_ORDER);
                for (Scheduled item : high) applyAccount(item.event, at);
                if (staged != null) injectStagedAnchorsAt(at, stops, timeline);
                for (Scheduled item : bucket) if (item.kind == Kind.ATTEMPT) executeAttempt(item, at);
            }
        }

        private void injectStagedAnchorsAt(long at, List<ObjectNode> stops, PriorityQueue<Scheduled> timeline) {
            List<ObjectNode> anchors = stagedAnchorsByDecision.remove(at);
            if (anchors == null) return;
            for (ObjectNode anchor : anchors) {
                String pairId = text(anchor, "pair_id"), asset = text(anchor, "asset");
                Instant decision = Instant.ofEpochMilli(at);
                ObjectNode intent = object(anchor, "initial_intent");
                ObjectNode opportunity = createOpportunity(staged.candidateId(), pairId, asset, decision,
                        text(intent, "variant"), text(intent, "branch"), text(intent, "direction"));
                opportunity.set("initial_intent", intent.deepCopy());
                opportunity.put("initial_intent_sha256", text(anchor, "initial_intent_sha256"))
                        .put("selection_source", text(anchor, "selection_source"));
                opportunity.putArray("stage_risk_fractions").add(0.01).add(0.015).add(0.025);
                opportunity.set("predecessor_anchor", anchor.deepCopy());
                LiquidationStructureRouterV1.RouteResult injected = routed.acceptFrozenInitialAnchor(intent);
                if (injected.intents().size() == 1) {
                    LiquidationStructureRouterV1.ConfirmedIntent confirmed = injected.intents().get(0);
                    if (!confirmed.pairId().equals(pairId) || !confirmed.intentId().equals(text(anchor, "intent_id"))) {
                        throw fail("router injected an anchor with changed identity");
                    }
                    injectedStageOneIntentIds.add(confirmed.intentId());
                    stagedPairBySetup.put(confirmed.setupId(), pairId);
                    processRouteResult(injected, at, stops, timeline);
                } else {
                    processRouteResult(injected, at, stops, timeline);
                    String reason = injected.rejections().isEmpty() ? "FROZEN_ANCHOR_REJECTED_WITHOUT_REASON"
                            : injected.rejections().get(0).reasonCode();
                    resolveNoTrade(opportunity, decision, reason);
                }
            }
        }

        private void processFeature(LiquidationStructureRouterV1.Observation observation, long at,
                List<ObjectNode> stopEvents, PriorityQueue<Scheduled> timeline) {
            LiquidationStructureRouterV1.RouteResult result = routed.accept(observation);
            processRouteResult(result, at, stopEvents, timeline);
            if (observation instanceof LiquidationStructureRouterV1.Bar bar
                    && bar.timeframe() == LiquidationStructureRouterV1.Timeframe.FOUR_HOUR) {
                TreeMap<Instant, LiquidationStructureRouterV1.Bar> bars = h4.get(bar.asset());
                bars.put(bar.eventTime(), bar);
                addControlTrails(bar, bars, stopEvents, at);
            }
            LiquidationStructureRouterV1.RouteResult diagnosticResult = diagnostic.accept(observation);
            for (LiquidationStructureRouterV1.ConfirmedIntent intent : diagnosticResult.intents()) {
                ObjectNode row = intentAudit(intent).put("status", "DIAGNOSTIC_EVENT_ONLY"); diagnostics.add(row);
                diagnostic.onNoFill(new LiquidationStructureRouterV1.NoFillAck(intent.intentId(), intent.setupId(),
                        intent.asset(), intent.stage(), intent.requestedExecutionAfter(), "DIAGNOSTIC_NOT_TRADED"));
            }
            for (LiquidationStructureRouterV1.RejectedOpportunity rejected : diagnosticResult.rejections()) {
                diagnostics.add(rejectionAudit(rejected).put("status", "DIAGNOSTIC_REJECTION_ONLY"));
            }
        }

        private void acknowledgeNoFill(Scheduled item) {
            LiquidationStructureRouterV1.ConfirmedIntent intent = item.intent;
            LiquidationStructureRouterV1.NoFillAck acknowledgement = new LiquidationStructureRouterV1.NoFillAck(
                    intent.intentId(), intent.setupId(), intent.asset(), intent.stage(),
                    Instant.ofEpochMilli(item.time), item.reason);
            if (item.account == ROUTED) routed.onNoFill(acknowledgement);
            else if (item.account == 3) diagnostic.onNoFill(acknowledgement);
        }

        private void processRouteResult(LiquidationStructureRouterV1.RouteResult result, long at,
                List<ObjectNode> stops, PriorityQueue<Scheduled> timeline) {
            for (LiquidationStructureRouterV1.QualifiedDailyStressEvent qualified : result.qualifiedDailyStressEvents()) {
                if (!qualified.availableAt().isAfter(Instant.ofEpochMilli(at))) {
                    if (!qualifiedStressEventIds.add(qualified.eventId())) continue;
                    ObjectNode event = JsonHashes.mapper().createObjectNode().put("event_id", qualified.eventId())
                            .put("asset", qualified.asset()).put("bucket_start", qualified.bucketStart().toString())
                            .put("bucket_event_time", qualified.bucketEventTime().toString())
                            .put("available_at", qualified.availableAt().toString())
                            .put("shock_direction", qualified.shockDirection().name());
                    event.set("source_evidence", sourceEvidenceJson(qualified.sourceEvidence()));
                    qualifiedStressEventInventory.add(event.deepCopy());
                    if (stressPolicy != null && "venue_outage_blackout".equals(stressScenarioId)) {
                        ObjectNode interval = event.deepCopy();
                        interval.put("interval_start_inclusive", qualified.availableAt().toString())
                                .put("interval_end_exclusive", qualified.availableAt().plus(stressPolicy.venueOutage().intervalAfterEvent()).toString())
                                .put("window_rule", stressPolicy.venueOutage().windowRule());
                        outageIntervals.add(interval);
                    }
                } else {
                    throw fail("router emitted a qualified stress event before its modeled availability");
                }
            }
            for (LiquidationStructureRouterV1.RejectedOpportunity rejection : result.rejections()) {
                routeAudit.add(rejectionAudit(rejection));
                if (staged != null && rejection.stage() > 1) retainStageRejection(rejection);
            }
            for (LiquidationStructureRouterV1.PendingCancellation cancellation : result.pendingCancellations()) {
                Pending instruction = pending.remove(cancellation.intentId());
                if (instruction == null) {
                    routeAudit.add(JsonHashes.mapper().createObjectNode()
                            .put("intent_id", cancellation.intentId()).put("setup_id", cancellation.setupId())
                            .put("asset", cancellation.asset()).put("stage", cancellation.stage())
                            .put("status", "PENDING_CANCELLATION_WITHOUT_REPLAY_ATTEMPT")
                            .put("reason_code", cancellation.reasonCode())
                            .put("cancellation_time", cancellation.cancellationTime().toString()));
                    continue;
                }
                if (instruction.intent.stage() > 1) {
                    ObjectNode stageAttempt = stageAttemptByIntent.get(cancellation.intentId());
                    if (stageAttempt != null && "PENDING_EXECUTION".equals(stageAttempt.path("outcome_state").asText())) {
                        resolveStageAttempt(stageAttempt, cancellation.cancellationTime(), cancellation.reasonCode());
                    }
                } else {
                    ObjectNode opportunity = opportunities.get(opportunityKey(instruction.candidate, instruction.anchorPairId()));
                    if (opportunity != null && "PENDING_EXECUTION".equals(opportunity.path("outcome_state").asText())) {
                        resolveNoTrade(opportunity, cancellation.cancellationTime(), cancellation.reasonCode());
                    }
                }
                LiquidationStructureRouterV1.NoFillAck acknowledgement = new LiquidationStructureRouterV1.NoFillAck(
                        cancellation.intentId(), cancellation.setupId(), cancellation.asset(), cancellation.stage(),
                        cancellation.cancellationTime(), cancellation.reasonCode());
                routed.onNoFill(acknowledgement);
                ObjectNode audit = JsonHashes.mapper().createObjectNode().put("intent_id", cancellation.intentId())
                        .put("setup_id", cancellation.setupId()).put("asset", cancellation.asset())
                        .put("stage", cancellation.stage()).put("status", "PENDING_INTENT_CANCELLED")
                        .put("reason_code", cancellation.reasonCode()).put("cancellation_time", cancellation.cancellationTime().toString());
                audit.set("source_evidence", sourceEvidenceJson(cancellation.sourceEvidence()));
                routeAudit.add(audit);
            }
            for (LiquidationStructureRouterV1.StopUpdateIntent update : result.stopUpdates()) {
                var account = accounts.get(ROUTED);
                if (!account.hasOpenPosition(update.asset()) || !update.setupId().equals(account.activeSetupId(update.asset()))) continue;
                long sourceClose = update.sourceEvidence().stream().mapToLong(value -> value.eventTime().toEpochMilli()).max().orElse(at);
                stops.add(accountEvent("STOP_UPDATE", update.asset(), update.activationTime().toEpochMilli(),
                        JsonHashes.mapper().createObjectNode().put("target_account", ROUTED).put("setup_id", update.setupId())
                                .put("source_bar_close_time", sourceClose).put("source_available_at", update.activationTime().toEpochMilli())
                                .put("stop", update.proposedStop())));
            }
            for (LiquidationStructureRouterV1.ConfirmedIntent intent : result.intents()) {
                if (intent.stage() == 1 && !insideDecisionWindow(intent.decisionTime())) {
                    routeAudit.add(intentAudit(intent).put("status", "WARMUP_OR_POST_WINDOW_INTENT_SUPPRESSED"));
                    timeline.add(Scheduled.callback(nextOpenAfter(intent, at), intent, ROUTED,
                            "OUTSIDE_FROZEN_DECISION_WINDOW"));
                    continue;
                }
                if (intent.stage() > 1) {
                    if (staged == null) {
                        routeAudit.add(intentAudit(intent).put("status", "CORE_ONE_ENTRY_MODE_ADDITION_SUPPRESSED"));
                        routed.onNoFill(new LiquidationStructureRouterV1.NoFillAck(intent.intentId(), intent.setupId(),
                                intent.asset(), intent.stage(), Instant.ofEpochMilli(at), "CORE_ONE_ENTRY_MODE"));
                    } else {
                        String anchorPair = stagedPairBySetup.get(intent.setupId());
                        if (anchorPair == null) {
                            routed.onNoFill(new LiquidationStructureRouterV1.NoFillAck(intent.intentId(), intent.setupId(),
                                    intent.asset(), intent.stage(), Instant.ofEpochMilli(at), "STAGED_SETUP_HAS_NO_FILLED_ANCHOR"));
                            routeAudit.add(intentAudit(intent).put("status", "STAGED_ADDITION_WITHOUT_ANCHOR_SUPPRESSED"));
                            continue;
                        }
                        ObjectNode attempt = stageAttemptJson(intent, anchorPair);
                        if (stageAttemptByIntent.putIfAbsent(intent.intentId(), attempt) != null) {
                            throw fail("staged router repeated one addition intent identity");
                        }
                        stageAttemptRows.add(attempt);
                        pending.put(intent.intentId(), new Pending(ROUTED, staged.candidateId(), intent, true, anchorPair));
                        timeline.add(Scheduled.attempt(nextOpenAfter(intent, at), ROUTED, staged.candidateId(), intent));
                    }
                    continue;
                }
                if (staged != null && !injectedStageOneIntentIds.remove(intent.intentId())) {
                    routeAudit.add(intentAudit(intent).put("status", "NON_ANCHOR_STAGE_ONE_INTENT_SUPPRESSED"));
                    routed.onNoFill(new LiquidationStructureRouterV1.NoFillAck(intent.intentId(), intent.setupId(),
                            intent.asset(), intent.stage(), Instant.ofEpochMilli(at), "NOT_IN_FROZEN_ANCHOR_INVENTORY"));
                    continue;
                }
                long requestedFill = nextOpenAfter(intent, at);
                ObjectNode initialIntent = staged == null ? frozenAnchorIntentJson(intent, result)
                        : object(stagedAnchorByPair.get(intent.pairId()), "initial_intent").deepCopy();
                ObjectNode signalAudit = intentAudit(intent).put("status", "CONFIRMED_STAGE_ONE_INTENT")
                        .put("candidate_id", candidateId(ROUTED))
                        .put("initial_intent_sha256", JsonHashes.canonicalSha256(initialIntent));
                signalAudit.set("initial_intent", initialIntent.deepCopy());
                routeAudit.add(signalAudit);
                ObjectNode baseline = createOpportunity(candidateId(ROUTED), intent.pairId(), intent.asset(),
                        intent.decisionTime(), intent.variant().name(), intent.branch().name(), intent.direction().name());
                baseline.put("setup_id", intent.setupId()).put("outcome_state", "PENDING_EXECUTION")
                        .put("initial_intent_sha256", JsonHashes.canonicalSha256(initialIntent));
                baseline.set("initial_intent", initialIntent.deepCopy());
                baseline.putArray("stage_risk_fractions").add(0.01).add(0.015).add(0.025);
                pending.put(intent.intentId(), new Pending(ROUTED, candidateId(ROUTED), intent, true, intent.pairId()));
                timeline.add(Scheduled.attempt(requestedFill, ROUTED, candidateId(ROUTED), intent));
                if (staged != null) continue;
                for (LiquidationStructureRouterV1.PairedDecisionContext context : result.pairedDecisionContexts()) {
                    if (!context.routedAnchor().intentId().equals(intent.intentId())) continue;
                    var paired = LiquidationStructureRouterV1.derivePairedControlArms(context);
                    for (var arm : paired.arms()) {
                        int accountIndex = arm.variant() == LiquidationStructureRouterV1.Variant.ALWAYS_CONTINUATION_CONTROL
                                ? CONTINUATION : REVERSAL;
                        String candidate = CANDIDATES.get(accountIndex);
                        if (arm.rejection() != null) {
                            ObjectNode row = createOpportunity(candidate, paired.pairId(), intent.asset(), intent.decisionTime(),
                                    arm.variant().name(), arm.forcedBranch().name(), arm.direction().name());
                            resolveNoTrade(row, intent.decisionTime(), arm.rejection().reasonCode());
                        } else {
                            var control = arm.intent();
                            ObjectNode controlInitialIntent = confirmedIntentJson(control);
                            ObjectNode row = createOpportunity(candidate, paired.pairId(), intent.asset(), intent.decisionTime(),
                                    arm.variant().name(), arm.forcedBranch().name(), arm.direction().name());
                            row.put("setup_id", control.setupId()).put("initial_intent_sha256",
                                    JsonHashes.canonicalSha256(controlInitialIntent));
                            row.set("initial_intent", controlInitialIntent.deepCopy());
                            if (isFrozen(accountIndex) || accounts.get(accountIndex).hasOpenPosition(intent.asset())
                                    || hasPending(accountIndex, intent.asset())) {
                                resolveNoTrade(row, intent.decisionTime(), "CONTROL_OCCUPIED_OR_ACCOUNT_UNRESOLVED_AT_ANCHOR");
                            } else {
                                row.put("outcome_state", "PENDING_EXECUTION");
                                pending.put(control.intentId(), new Pending(accountIndex, candidate, control, false, control.pairId()));
                                timeline.add(Scheduled.attempt(nextOpenAfter(control, at), accountIndex, candidate, control));
                            }
                        }
                    }
                }
            }
        }

        private boolean scenarioBlocksOutage(LiquidationStructureRouterV1.ConfirmedIntent intent,
                long requestedFillEpochMillis, String candidate, String pairId) {
            if (!"venue_outage_blackout".equals(stressScenarioId)) return false;
            boolean blocked = false;
            Instant requestedFill = Instant.ofEpochMilli(requestedFillEpochMillis);
            for (ObjectNode interval : outageIntervals) {
                Instant intervalStart = Instant.parse(text(interval, "interval_start_inclusive"));
                ObjectNode transform = stressPolicy.outageDecision(text(interval, "event_id"), intervalStart, requestedFill).toJson();
                boolean eventBlocked = transform.path("blocked").asBoolean(false);
                String decisionKey = candidate + "|" + intent.intentId() + "|" + text(interval, "event_id") + "|" + requestedFill;
                if (outageDecisionKeys.add(decisionKey)) {
                    recordStressTransform(transform);
                    ObjectNode audit = JsonHashes.mapper().createObjectNode().put("candidate_id", candidate)
                            .put("pair_id", pairId).put("intent_id", intent.intentId())
                            .put("qualifying_event_id", text(interval, "event_id"))
                            .put("policy_transform_sha256", JsonHashes.canonicalSha256(transform))
                            .put("blocked", eventBlocked);
                    audit.set("policy_transform", transform);
                    outageDecisions.add(audit);
                }
                blocked |= eventBlocked;
            }
            return blocked;
        }

        private boolean isVenueBlackout(long at) {
            Instant time = Instant.ofEpochMilli(at);
            for (ObjectNode interval : outageIntervals) {
                Instant start = Instant.parse(text(interval, "interval_start_inclusive"));
                Instant end = Instant.parse(text(interval, "interval_end_exclusive"));
                if (!time.isBefore(start) && time.isBefore(end)) return true;
            }
            return false;
        }

        private ObjectNode activeVenueOutage(long at) {
            Instant time = Instant.ofEpochMilli(at);
            for (ObjectNode interval : outageIntervals) {
                Instant start = Instant.parse(text(interval, "interval_start_inclusive"));
                Instant end = Instant.parse(text(interval, "interval_end_exclusive"));
                if (!time.isBefore(start) && time.isBefore(end)) return interval;
            }
            return null;
        }

        private void executeAttempt(Scheduled item, long at) {
            Pending instruction = pending.remove(item.intent.intentId());
            if (instruction == null) return;
            int stage = instruction.intent.stage();
            ObjectNode opportunity = opportunities.get(opportunityKey(instruction.candidate, instruction.anchorPairId()));
            ObjectNode stageAttempt = stage > 1 ? stageAttemptByIntent.get(instruction.intent.intentId()) : null;
            if (opportunity == null) return;
            if (stage == 1 && !"PENDING_EXECUTION".equals(opportunity.path("outcome_state").asText())) return;
            if (stage > 1 && (staged == null || stageAttempt == null
                    || !"PENDING_EXECUTION".equals(stageAttempt.path("outcome_state").asText())
                    || !"OPEN_TRADE".equals(opportunity.path("outcome_state").asText()))) return;
            int index = instruction.account; String asset = instruction.intent.asset();
            boolean hasPosition = accounts.get(index).hasOpenPosition(asset);
            if (isFrozen(index) || (stage == 1 && hasPosition) || (stage > 1 && !hasPosition)) {
                resolveAttemptFailure(opportunity, stageAttempt, stage, Instant.ofEpochMilli(at),
                        "ASSET_OR_PORTFOLIO_UNRESOLVED_AT_EXECUTION", false);
                noFill(instruction, at, "ASSET_OR_PORTFOLIO_UNRESOLVED_AT_EXECUTION"); return;
            }
            if (scenarioBlocksOutage(instruction.intent, at, instruction.candidate, instruction.intent.pairId())) {
                resolveAttemptFailure(opportunity, stageAttempt, stage, Instant.ofEpochMilli(at),
                        "FROZEN_VENUE_OUTAGE_BLACKOUT", false);
                stressBlockedEntries++;
                stressBlockedByCandidate.merge(instruction.candidate, 1, Integer::sum);
                noFill(instruction, at, "FROZEN_VENUE_OUTAGE_BLACKOUT"); return;
            }
            ObjectNode trade = activeExecution.get(key(asset, at)), mark = activeMarks.get(key(asset, at));
            ObjectNode prior = activeExecution.get(key(asset, at - MINUTE));
            if (trade == null || mark == null || prior == null) {
                String missing = trade == null ? "MISSING_EXACT_NEXT_MINUTE_EXECUTION_OPEN"
                        : mark == null ? "MISSING_EXACT_NEXT_MINUTE_MARK_OPEN" : "MISSING_PRIOR_MINUTE_CAPACITY";
                resolveAttemptFailure(opportunity, stageAttempt, stage, Instant.ofEpochMilli(at), missing, true);
                noFill(instruction, at, missing); return;
            }
            if (!metadataCovers(asset, at)) {
                resolveAttemptFailure(opportunity, stageAttempt, stage, Instant.ofEpochMilli(at), "NO_EFFECTIVE_METADATA_AT_FILL", true);
                noFill(instruction, at, "NO_EFFECTIVE_METADATA_AT_FILL"); return;
            }
            if (Math.abs(number(trade, "open") - instruction.intent.zoneCenter()) > instruction.intent.maxChaseDistance()) {
                resolveAttemptFailure(opportunity, stageAttempt, stage, Instant.ofEpochMilli(at),
                        "MAXIMUM_CHASE_DISTANCE_EXCEEDED", false);
                noFill(instruction, at, "MAXIMUM_CHASE_DISTANCE_EXCEEDED"); return;
            }
            if ("liquidity_capacity".equals(stressScenarioId)) {
                recordStressTransform(stressPolicy.capacityLimit(number(prior, "base_volume")).toJson());
            }
            ObjectNode fields = JsonHashes.mapper().createObjectNode().put("stage", stage).put("setup_id", instruction.intent.setupId())
                    .put("intent_id", instruction.intent.intentId())
                    .put("decision_time", instruction.intent.decisionTime().toEpochMilli()).put("price", number(trade, "open"))
                    .put("mark_price", number(mark, "open")).put("decision_mark", instruction.intent.confirmationClose())
                    .put("previous_completed_minute_base_volume", number(prior, "base_volume"))
                    .put("mode", instruction.intent.branch().name()).put("direction", instruction.intent.direction().name())
                    .put("common_stop", instruction.intent.initialStop());
            if (staged != null && stage == 1) {
                BigDecimal referenceEquity = accounts.get(index).snapshot().path("mark_to_market_equity_usdt").decimalValue();
                fields.put("full_position_reference_risk_usdt", referenceEquity.multiply(new BigDecimal("0.05")));
            }
            if (instruction.intent.reversalTarget() == null) fields.putNull("recovery_target");
            else fields.put("recovery_target", instruction.intent.reversalTarget());
            ObjectNode record = accounts.get(index).accept(accountEvent("ADD", asset, at, fields));
            if (!"ADD_FILLED".equals(record.path("type").asText())) {
                String reason = record.path("reason").asText("ACCOUNT_REJECTED");
                resolveAttemptFailure(opportunity, stageAttempt, stage, Instant.ofEpochMilli(at), reason, false);
                noFill(instruction, at, reason); return;
            }
            ObjectNode filledPosition = accountPosition(accounts.get(index).snapshot(), asset);
            if (stage == 1) {
                BigDecimal referenceEquity = filledPosition.path("reference_equity_usdt").decimalValue();
                BigDecimal referenceRisk = staged == null
                        ? stageOneRisk(accounts.get(index), asset) : referenceEquity.multiply(new BigDecimal("0.05"));
                opportunity.put("outcome_state", "OPEN_TRADE").put("first_fill_time", Instant.ofEpochMilli(at).toString())
                        .put("fill_price", record.path("price").asDouble()).put("filled_quantity", record.path("quantity").asDouble())
                        .put("reference_risk_usdt", referenceRisk.doubleValue())
                        .put("position_episode_id", instruction.candidate + "|" + instruction.intent.setupId()
                                + "|" + Instant.ofEpochMilli(at))
                        .put("first_fill_reference_equity_usdt", referenceEquity.doubleValue())
                        .put("full_position_reference_risk_usdt", referenceEquity.multiply(new BigDecimal("0.05")).doubleValue())
                        .put("full_position_reference_risk_basis", "FULL_POSITION_5_PERCENT_OF_FIRST_FILL_REFERENCE_EQUITY");
                if (staged != null) opportunity.putArray("stage_risk_fractions").add(0.01).add(0.015).add(0.025);
            } else {
                stageAttempt.put("outcome_state", "STAGE_FILLED").put("outcome_available_time", Instant.ofEpochMilli(at).toString())
                        .put("fill_time", Instant.ofEpochMilli(at).toString()).put("fill_price", record.path("price").asDouble())
                        .put("filled_quantity", record.path("quantity").asDouble())
                        .put("planned_tranche_risk_usdt", accountStageRisk(accounts.get(index), asset, stage));
            }
            if (instruction.routed) {
                // The account may have ratcheted the common stop after this intent was emitted.
                // A stage-add acknowledgement must advance the router with the actual stop now
                // active in the filled position, rather than the intent's older signal stop.
                double activeStop = stage == 1 ? instruction.intent.initialStop() : number(filledPosition, "common_stop");
                routed.onFill(new LiquidationStructureRouterV1.FillAck(instruction.intent.intentId(),
                        instruction.intent.setupId(), asset, stage, Instant.ofEpochMilli(at), record.path("price").asDouble(),
                        activeStop));
            }
            else controlTrades.put(index + "|" + asset, new Trade(instruction.intent, instruction.candidate, instruction.intent.initialStop(), at));
        }

        private void resolveAttemptFailure(ObjectNode opportunity, ObjectNode stageAttempt, int stage,
                Instant at, String reasonCode, boolean coverageBlocked) {
            if (stage == 1) {
                if (coverageBlocked) resolveBlocked(opportunity, at, reasonCode);
                else resolveNoTrade(opportunity, at, reasonCode);
            } else if (stageAttempt != null) {
                if (coverageBlocked) {
                    stageAttempt.put("outcome_state", "COVERAGE_BLOCKED").putNull("outcome_available_time");
                    reason(stageAttempt, reasonCode);
                } else resolveStageAttempt(stageAttempt, at, reasonCode);
            }
        }

        private void noFill(Pending instruction, long at, String reason) {
            if (instruction.routed) routed.onNoFill(new LiquidationStructureRouterV1.NoFillAck(instruction.intent.intentId(),
                    instruction.intent.setupId(), instruction.intent.asset(), instruction.intent.stage(), Instant.ofEpochMilli(at), reason));
        }

        private void applyAccount(ObjectNode event, long at) {
            String asset = event.path("asset").asText();
            int targetAccount = event.path("target_account").asInt(-1);
            long outageClock = outageClock(event, at);
            ObjectNode activeOutage = "venue_outage_blackout".equals(stressScenarioId)
                    ? activeVenueOutage(outageClock) : null;
            for (int index : accountIndices()) {
                if (targetAccount >= 0 && targetAccount != index) continue;
                if (frozenAccounts.contains(index) || frozenAssets.getOrDefault(index, Set.of()).contains(asset)) continue;
                ObjectNode accountEvent = event.deepCopy(); accountEvent.remove("target_account");
                if (activeOutage != null) accountEvent.put("venue_execution_blocked", true);
                String accountEventType = accountEvent.path("type").asText();
                if (activeOutage != null && ("BAR_PRE".equals(accountEventType) || "BAR_POST".equals(accountEventType)
                        || "FUNDING".equals(accountEventType)) && accounts.get(index).hasOpenPosition(asset)) {
                    if (activeOutage != null) {
                        String setupId = accounts.get(index).activeSetupId(asset);
                        String positionId = candidateId(index) + "|" + asset + "|" + setupId;
                        String exposureId = positionId + "|" + text(activeOutage, "event_id");
                        if (outageExposureIds.add(exposureId)) {
                            ObjectNode exposure = JsonHashes.mapper().createObjectNode().put("candidate_id", candidateId(index))
                                    .put("asset", asset).put("setup_id", setupId).put("qualified_event_id", text(activeOutage, "event_id"))
                                    .put("event_time", outageClock).put("interval_start_inclusive", text(activeOutage, "interval_start_inclusive"))
                                    .put("interval_end_exclusive", text(activeOutage, "interval_end_exclusive"));
                            outagePositionExposures.add(exposure);
                        }
                    }
                }
                if ("adverse_execution_gap".equals(stressScenarioId)
                        && ("BAR_PRE".equals(accountEvent.path("type").asText())
                                || "BAR_POST".equals(accountEvent.path("type").asText()))) {
                    accountEvent.put("adverse_gap_r", stressPolicy.adverseExecutionGap().debitR());
                }
                ObjectNode positionBefore = null;
                if ("funding_carry".equals(stressScenarioId) && "FUNDING".equals(accountEvent.path("type").asText())) {
                    positionBefore = accountPosition(accounts.get(index).snapshot(), asset);
                    BigDecimal quantity = positionBefore.path("quantity").decimalValue();
                    if (quantity.signum() > 0) {
                        var side = "LONG".equals(positionBefore.path("direction").asText())
                                ? LiquidationV2StressPolicyV1.PositionSide.LONG : LiquidationV2StressPolicyV1.PositionSide.SHORT;
                        ObjectNode transform = stressPolicy.stressFunding(quantity.doubleValue(), number(accountEvent, "mark_price"),
                                number(accountEvent, "funding_rate"), side).toJson();
                        recordStressTransform(transform);
                        if (transform.path("is_debit").asBoolean()) {
                            double multiplier = transform.path("debit_multiplier").asDouble();
                            accountEvent.put("funding_rate", number(accountEvent, "funding_rate") * multiplier);
                            stressAffectedPositionIds.add(candidateId(index) + "|" + positionBefore.path("active_setup_id").asText());
                        }
                    }
                }
                ObjectNode record = accounts.get(index).accept(accountEvent);
                if (record.path("execution_gap_debit_usdt").isNumber()
                        && record.path("execution_gap_debit_usdt").asDouble() > 0.0) {
                    ObjectNode position = positionBefore == null ? accountPosition(accounts.get(index).snapshot(), asset) : positionBefore;
                    BigDecimal referenceRisk = record.path("execution_gap_reference_risk_usdt").decimalValue();
                    ObjectNode transform = stressPolicy.adverseGapDebit(referenceRisk.doubleValue()).toJson();
                    recordStressTransform(transform);
                    stressAffectedPositionIds.add(candidateId(index) + "|" + position.path("active_setup_id").asText());
                }
                if ("fee_slippage".equals(stressScenarioId) && "ADD_FILLED".equals(record.path("type").asText())) {
                    ObjectNode position = accountPosition(accounts.get(index).snapshot(), asset);
                    stressAffectedPositionIds.add(candidateId(index) + "|" + position.path("active_setup_id").asText());
                }
                if ("liquidity_capacity".equals(stressScenarioId) && "ADD_FILLED".equals(record.path("type").asText())
                        && "PRIOR_MINUTE_VOLUME_1_PERCENT".equals(record.path("risk_limited_by").asText())) {
                    ObjectNode position = accountPosition(accounts.get(index).snapshot(), asset);
                    stressAffectedPositionIds.add(candidateId(index) + "|" + position.path("active_setup_id").asText());
                }
                if (record.has("exit_reason") || record.path("liquidated").asBoolean(false)) {
                    closeOpportunity(index, asset, at, event, record);
                }
                if (activeOutage != null && accounts.get(index).hasOpenPosition(asset)) {
                    String type = record.path("type").asText();
                    String positionId = candidateId(index) + "|" + asset + "|" + accounts.get(index).activeSetupId(asset);
                    if (type.endsWith("_TRIGGER_LATCHED_DURING_VENUE_BLACKOUT")
                            || record.path("liquidation_trigger_latched_during_venue_blackout").asBoolean(false)) {
                        stressAffectedPositionIds.add(positionId);
                        outageDeferredExitTriggers++;
                    } else if ("STOP_UPDATE_BLOCKED_VENUE_BLACKOUT".equals(type)) {
                        stressAffectedPositionIds.add(positionId);
                        outageBlockedStopUpdates++;
                    }
                }
                if ("STOP_UPDATED".equals(record.path("type").asText()) && index != ROUTED) {
                    Trade trade = controlTrades.get(index + "|" + asset);
                    if (trade != null && trade.proposedStopAt == at) trade.activeStop = trade.proposedStop;
                }
                if ("STOP_UPDATED".equals(record.path("type").asText()) && index == ROUTED) {
                    String setup = accounts.get(index).activeSetupId(asset);
                    routed.onStopUpdate(new LiquidationStructureRouterV1.StopAck(setup, asset, Instant.ofEpochMilli(at),
                            record.path("new_stop").asDouble()));
                }
            }
        }

        private long outageClock(ObjectNode event, long scheduledTime) {
            String type = event.path("type").asText();
            if ("BAR_PRE".equals(type) || "BAR_POST".equals(type)) {
                return event.has("bar_start_time") ? event.path("bar_start_time").asLong() : scheduledTime;
            }
            return event.has("time") ? event.path("time").asLong() : scheduledTime;
        }

        private void closeOpportunity(int index, String asset, long at, ObjectNode sourceEvent, ObjectNode record) {
            String setup = accounts.get(index).activeSetupId(asset);
            if (index == ROUTED) routed.onClose(new LiquidationStructureRouterV1.CloseAck(setup, asset,
                    Instant.ofEpochMilli(at), closeReason(record)));
            String candidate = candidateId(index);
            ObjectNode opportunity = opportunities.values().stream().filter(row -> candidate.equals(row.path("candidate_id").asText())
                    && setup.equals(row.path("setup_id").asText()) && "OPEN_TRADE".equals(row.path("outcome_state").asText()))
                    .findFirst().orElse(null);
            if (opportunity != null) {
                ObjectNode position = accountPosition(accounts.get(index).snapshot(), asset);
                BigDecimal pnl = position.path("realized_gross_pnl_usdt").decimalValue()
                        .add(position.path("funding_pnl_usdt").decimalValue())
                        .subtract(position.path("entry_costs_usdt").decimalValue())
                        .subtract(position.path("exit_costs_usdt").decimalValue());
                Instant knownAt = Instant.ofEpochMilli(at);
                String eventType = sourceEvent.path("type").asText();
                long lowerBound = "BAR_POST".equals(eventType) && sourceEvent.has("bar_start_time")
                        ? epoch(sourceEvent, "bar_start_time") : at;
                double modeledPrice = record.has("exit_price") ? record.path("exit_price").asDouble()
                        : record.path("price").asDouble(Double.NaN);
                String exitPriceBasis = record.path("outage_deferred_exit").asBoolean(false)
                        ? record.path("exit_price_basis").asText("MODELED_FIRST_PERMITTED_MINUTE_OPEN_AFTER_BLACKOUT")
                        : "BAR_PRE".equals(eventType) ? "MODELED_MINUTE_OPEN_GAP_FILL"
                                : "BAR_POST".equals(eventType) ? "MODELED_STOP_OR_TARGET_FROM_COMPLETED_1M_OHLC"
                                        : "MODELED_SETTLEMENT_OR_EXPLICIT_EXECUTION_PRICE";
                opportunity.put("outcome_state", "CLOSED_TRADE").put("outcome_available_time", knownAt.toString())
                        .put("exit_time", knownAt.toString()).put("exit_known_at", knownAt.toString())
                        .put("exit_time_semantics", "KNOWN_AT_OR_COMPLETED_BAR_TIME; NOT_TICK_FILL_TIME")
                        .put("exit_fill_time_lower_bound", Instant.ofEpochMilli(lowerBound).toString())
                        .put("exit_fill_time_upper_bound", knownAt.toString())
                        .put("exit_fill_time_precision", "MINUTE_OPEN_EXACT_OR_COMPLETED_1M_OHLC_INTERVAL")
                        .put("exit_price_basis", exitPriceBasis)
                        .put("net_pnl_usdt", pnl)
                        .put("exit_reason", record.path("exit_reason").asText(record.path("liquidation_reason").asText("LIQUIDATION")));
                if (record.path("outage_deferred_exit").asBoolean(false)) {
                    opportunity.put("outage_trigger_reason", record.path("outage_trigger_reason").asText())
                            .put("outage_trigger_status", record.path("outage_trigger_status").asText())
                            .put("outage_trigger_observed_time", record.path("outage_trigger_observed_time").asLong())
                            .put("outage_execution_resume_time", at)
                            .put("outage_execution_delay_ms", Math.max(0L, at - record.path("outage_trigger_observed_time").asLong()));
                }
                for (String deadlineField : List.of("deadline_order_time", "deadline_fill_time",
                        "deadline_execution_overrun_ms")) {
                    if (record.hasNonNull(deadlineField)) opportunity.set(deadlineField, record.path(deadlineField).deepCopy());
                }
                if (Double.isFinite(modeledPrice)) opportunity.put("modeled_exit_price_usdt", modeledPrice);
                if (staged != null) opportunity.set("tranche_attribution", stagedTrancheAttribution(position,
                        pnl, opportunity.path("full_position_reference_risk_usdt").decimalValue()));
            }
            if (index != ROUTED) controlTrades.remove(index + "|" + asset);
        }

        private void handleGap(Scheduled gap) {
            for (int index : accountIndices()) if (!frozenAccounts.contains(index) && accounts.get(index).hasOpenPosition(gap.asset)) {
                frozenAssets.computeIfAbsent(index, ignored -> new HashSet<>()).add(gap.asset);
                gaps.add(JsonHashes.mapper().createObjectNode().put("candidate_id", candidateId(index)).put("asset", gap.asset)
                        .put("time", Instant.ofEpochMilli(gap.time).toString()).put("gap", gap.reason)
                        .put("effect", "OPEN_EXPOSURE_RETAINED_UNRESOLVED_NO_FABRICATED_FILL_OR_EXIT"));
                String setup = accounts.get(index).activeSetupId(gap.asset);
                for (ObjectNode row : opportunities.values()) if (candidateId(index).equals(row.path("candidate_id").asText())
                        && setup.equals(row.path("setup_id").asText()) && "OPEN_TRADE".equals(row.path("outcome_state").asText())) {
                    row.put("outcome_state", "OPEN_UNRESOLVED").putNull("outcome_available_time"); reason(row, gap.reason);
                }
            }
        }

        private void addControlTrails(LiquidationStructureRouterV1.Bar bar,
                TreeMap<Instant, LiquidationStructureRouterV1.Bar> history, List<ObjectNode> stops, long at) {
            List<LiquidationStructureRouterV1.Bar> last = lastThree(history);
            if (last.size() != 3) return;
            for (int index : List.of(CONTINUATION, REVERSAL)) {
                Trade trade = controlTrades.get(index + "|" + bar.asset());
                if (trade == null || trade.intent.branch() != LiquidationStructureRouterV1.Branch.CONTINUATION
                        || !bar.eventTime().isAfter(Instant.ofEpochMilli(trade.fillTime))) continue;
                boolean longSide = trade.intent.direction() == LiquidationStructureRouterV1.Direction.LONG;
                double next = longSide ? last.stream().mapToDouble(LiquidationStructureRouterV1.Bar::low).min().orElseThrow()
                        - LiquidationStructureRouterV1.STOP_BUFFER_ATR * trade.intent.preEventAtr()
                        : last.stream().mapToDouble(LiquidationStructureRouterV1.Bar::high).max().orElseThrow()
                        + LiquidationStructureRouterV1.STOP_BUFFER_ATR * trade.intent.preEventAtr();
                if (longSide ? next <= trade.activeStop : next >= trade.activeStop) continue;
                long close = last.stream().mapToLong(value -> value.eventTime().toEpochMilli()).max().orElse(at);
                ObjectNode fields = JsonHashes.mapper().createObjectNode().put("setup_id", trade.intent.setupId())
                        .put("source_bar_close_time", close).put("source_available_at", bar.availableAt().toEpochMilli()).put("stop", next);
                fields.put("target_account", index);
                stops.add(accountEvent("STOP_UPDATE", bar.asset(), bar.availableAt().toEpochMilli(), fields));
                trade.proposedStop = next; trade.proposedStopAt = bar.availableAt().toEpochMilli();
            }
        }

        private List<Integer> accountIndices() { return staged == null ? TRADED_ACCOUNTS : List.of(ROUTED); }
        private String candidateId(int account) { return staged == null ? CANDIDATES.get(account) : staged.candidateId(); }
        private boolean isFrozen(int account) { return frozenAccounts.contains(account) || !frozenAssets.getOrDefault(account, Set.of()).isEmpty(); }
        private boolean hasPending(int account, String asset) { return pending.values().stream().anyMatch(row -> row.account == account && row.intent.asset().equals(asset)); }

        private boolean insideDecisionWindow(Instant decision) {
            return !decision.isBefore(decisionFrom) && decision.isBefore(decisionEnd);
        }

        private ArrayNode jsonArray(List<ObjectNode> rows) {
            ArrayNode out = JsonHashes.mapper().createArrayNode();
            rows.forEach(row -> out.add(row.deepCopy()));
            return out;
        }

        private ObjectNode stageAttemptJson(LiquidationStructureRouterV1.ConfirmedIntent intent, String anchorPair) {
            ObjectNode row = JsonHashes.mapper().createObjectNode()
                    .put("attempt_id", staged.candidateId() + "|" + intent.intentId())
                    .put("candidate_id", staged.candidateId()).put("pair_id", anchorPair)
                    .put("intent_id", intent.intentId()).put("setup_id", intent.setupId())
                    .put("asset", intent.asset()).put("stage", intent.stage())
                    .put("decision_time", intent.decisionTime().toString())
                    .put("requested_execution_after", intent.requestedExecutionAfter().toString())
                    .put("outcome_state", "PENDING_EXECUTION").putNull("outcome_available_time")
                    .putNull("fill_time").putNull("filled_quantity").putNull("fill_price");
            row.putArray("reason_codes");
            return row;
        }

        private void retainStageRejection(LiquidationStructureRouterV1.RejectedOpportunity rejection) {
            String anchorPair = stagedPairBySetup.get(rejection.setupId());
            if (anchorPair == null) {
                routeAudit.add(rejectionAudit(rejection).put("status", "STAGE_REJECTION_WITHOUT_FILLED_ANCHOR"));
                return;
            }
            ObjectNode row = JsonHashes.mapper().createObjectNode()
                    .put("attempt_id", staged.candidateId() + "|" + rejection.opportunityId())
                    .put("candidate_id", staged.candidateId()).put("pair_id", anchorPair)
                    .put("intent_id", rejection.opportunityId()).put("setup_id", rejection.setupId())
                    .put("asset", rejection.asset()).put("stage", rejection.stage())
                    .put("decision_time", rejection.decisionTime().toString())
                    .put("outcome_state", "PENDING_EXECUTION").putNull("outcome_available_time")
                    .putNull("fill_time").putNull("filled_quantity").putNull("fill_price");
            row.putArray("reason_codes");
            stageAttemptRows.add(row);
            resolveStageAttempt(row, rejection.decisionTime(), rejection.reasonCode());
        }

        private void resolveStageAttempt(ObjectNode row, Instant time, String reasonCode) {
            row.put("outcome_state", "RESOLVED_NO_TRADE").put("outcome_available_time", time.toString())
                    .putNull("fill_time").putNull("filled_quantity").putNull("fill_price");
            reason(row, reasonCode);
        }

        private BigDecimal accountStageRisk(LiquidationPortfolioAccountingV1.AccountSession account, String asset, int stage) {
            for (JsonNode fill : accountPosition(account.snapshot(), asset).path("fills")) {
                if (fill.path("stage").asInt(-1) == stage) return fill.path("planned_tranche_risk_usdt").decimalValue();
            }
            return BigDecimal.ZERO;
        }

        private ObjectNode stagedTrancheAttribution(ObjectNode position, BigDecimal aggregateNet, BigDecimal fullRisk) {
            JsonNode fills = position.path("fills"), fundings = position.path("funding_events"), exits = position.path("exits");
            if (!fills.isArray() || fills.isEmpty() || !exits.isArray() || exits.isEmpty()) {
                throw fail("closed staged position lacks retained fill or exit attribution rows");
            }
            BigDecimal totalExitQuantity = BigDecimal.ZERO;
            for (JsonNode exit : exits) totalExitQuantity = totalExitQuantity.add(exit.path("quantity").decimalValue());
            if (totalExitQuantity.signum() <= 0) throw fail("staged exit attribution requires positive exited quantity");
            ArrayNode tranches = JsonHashes.mapper().createArrayNode();
            BigDecimal allocated = BigDecimal.ZERO;
            for (JsonNode fill : fills) {
                BigDecimal quantity = fill.path("quantity").decimalValue();
                BigDecimal entryNotional = fill.path("notional_usdt").decimalValue();
                BigDecimal entryPrice = fill.path("price").decimalValue();
                boolean longSide = "LONG".equals(fill.path("direction").asText(position.path("direction").asText()));
                BigDecimal sign = longSide ? BigDecimal.ONE : BigDecimal.ONE.negate();
                BigDecimal grossExit = BigDecimal.ZERO, exitCosts = BigDecimal.ZERO, fundingPnl = BigDecimal.ZERO;
                for (JsonNode exit : exits) {
                    BigDecimal exitQuantity = exit.path("quantity").decimalValue();
                    BigDecimal exitPrice = exit.path("price").decimalValue();
                    BigDecimal fee = exit.path("exit_costs_usdt").decimalValue();
                    if (fee.signum() == 0) fee = exit.path("liquidation_fee_usdt").decimalValue();
                    grossExit = grossExit.add(exitPrice.multiply(quantity).subtract(entryNotional).multiply(sign));
                    exitCosts = exitCosts.add(fee.multiply(quantity).divide(totalExitQuantity, java.math.MathContext.DECIMAL128));
                    if (exitQuantity.signum() <= 0) throw fail("staged exit attribution has a nonpositive closed quantity");
                }
                long fillTime = fill.path("time").asLong(Long.MAX_VALUE);
                for (JsonNode settlement : fundings) {
                    // Funding is ordered before an ADD at the same timestamp. A tranche is
                    // exposed only when it was already open strictly before settlement time.
                    if (fillTime >= settlement.path("settlement_time").asLong(Long.MIN_VALUE)) continue;
                    BigDecimal chargedQuantity = settlement.path("charged_quantity_at_settlement").decimalValue();
                    if (chargedQuantity.signum() > 0) fundingPnl = fundingPnl.add(settlement.path("amount_usdt").decimalValue()
                            .multiply(quantity).divide(chargedQuantity, java.math.MathContext.DECIMAL128));
                }
                BigDecimal entryCosts = fill.path("entry_fee_usdt").decimalValue().add(fill.path("slippage_cost_usdt").decimalValue());
                BigDecimal trancheNet = grossExit.add(fundingPnl).subtract(entryCosts).subtract(exitCosts);
                allocated = allocated.add(trancheNet);
                tranches.addObject().put("stage", fill.path("stage").asInt())
                        .put("fill_time", Instant.ofEpochMilli(fillTime).toString())
                        .put("quantity", quantity).put("entry_price", entryPrice).put("entry_notional_usdt", entryNotional)
                        .put("entry_costs_usdt", entryCosts).put("gross_exit_pnl_usdt", grossExit)
                        .put("funding_pnl_usdt", fundingPnl).put("exit_costs_usdt", exitCosts)
                        .put("net_pnl_usdt", trancheNet)
                        .put("net_r_full_position_5pct", trancheNet.divide(fullRisk, java.math.MathContext.DECIMAL128));
            }
            if (allocated.subtract(aggregateNet).abs().compareTo(new BigDecimal("0.0000001")) > 0) {
                throw fail("staged tranche dollars do not reconcile to aggregate position net PnL");
            }
            ObjectNode result = JsonHashes.mapper().createObjectNode()
                    .put("schema", "liquidation-v2-tranche-attribution/1")
                    .put("allocation_basis", "EXIT_COSTS_BY_CLOSED_QUANTITY;FUNDING_BY_ACTUAL_QUANTITY_OPEN_AT_SETTLEMENT")
                    .put("full_position_reference_risk_usdt", fullRisk)
                    .put("aggregate_position_net_pnl_usdt", aggregateNet)
                    .put("allocated_tranche_net_pnl_usdt", allocated).put("reconciled", true);
            result.set("tranches", tranches);
            return result;
        }

        /** Digest continuation state that is not fully represented by realized economics alone. */
        String continuationStateSha256() {
            ObjectNode state = JsonHashes.mapper().createObjectNode()
                    .put("initialized", initialized).put("terminalized", terminalized)
                    .put("next_feature_date", nextFeatureDate == null ? "" : nextFeatureDate.toString())
                    .put("features_read", featuresRead).put("minute_pairs_read", minutePairsRead)
                    .put("funding_rows_read", fundingRowsRead).put("metadata_tier_rows_read", metadataRowsRead)
                    .put("stress_transform_count", stressTransformCount)
                    .put("stress_transform_chain_sha256", stressTransformChain);
            ArrayNode scheduled = state.putArray("queued_timeline");
            timeline.stream().sorted(SCHEDULE_ORDER).forEach(item -> scheduled.add(scheduledIdentity(item)));
            ArrayNode buffered = state.putArray("buffered_features");
            featureBuffer.stream().sorted(FEATURE_ORDER).forEach(feature -> {
                ObjectNode row = JsonHashes.mapper().createObjectNode().put("available_at", feature.time);
                row.set("observation", observationIdentity(feature.observation));
                buffered.add(row);
            });
            ObjectNode pendingRows = state.putObject("pending_replay_intents");
            pending.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                Pending value = entry.getValue();
                ObjectNode row = pendingRows.putObject(entry.getKey()).put("account", value.account())
                        .put("candidate_id", value.candidate()).put("routed", value.routed())
                        .put("anchor_pair_id", value.anchorPairId());
                row.set("intent", intentAudit(value.intent()));
            });
            appendObjectMap(state.putObject("active_execution"), activeExecution);
            appendObjectMap(state.putObject("active_marks"), activeMarks);
            appendObjectMap(state.putObject("previous_completed_execution"), previousCompletedExecution);
            appendObjectMap(state.putObject("metadata_effective_at_start"), metadataEffectiveAtDecisionStart);
            ArrayNode stressEvents = state.putArray("qualified_stress_events");
            qualifiedStressEventInventory.forEach(row -> stressEvents.add(row.deepCopy()));
            ArrayNode outageWindows = state.putArray("outage_intervals");
            outageIntervals.forEach(row -> outageWindows.add(row.deepCopy()));
            ObjectNode current = result();
            state.put("partial_result_sha256", current.path("content_sha256").asText());
            state.put("ledger_sha256", current.path("ledger_sha256").asText());
            state.put("account_curve_sha256", current.path("account_curve_sha256").asText());
            return JsonHashes.canonicalSha256(state);
        }

        private ObjectNode scheduledIdentity(Scheduled item) {
            ObjectNode row = JsonHashes.mapper().createObjectNode().put("time", item.time())
                    .put("kind", item.kind().name()).put("asset", item.asset())
                    .put("account", item.account()).put("candidate", item.candidate())
                    .put("reason", item.reason());
            if (item.event() != null) row.set("event", item.event().deepCopy());
            if (item.observation() != null) row.set("observation", observationIdentity(item.observation()));
            if (item.intent() != null) row.set("intent", intentAudit(item.intent()));
            return row;
        }

        private ObjectNode observationIdentity(LiquidationStructureRouterV1.Observation observation) {
            ObjectNode row = JsonHashes.mapper().createObjectNode().put("asset", observation.asset())
                    .put("event_time", observation.eventTime().toString())
                    .put("available_at", observation.availableAt().toString()).put("series_id", observation.seriesId());
            if (observation instanceof LiquidationStructureRouterV1.DailyLiquidation daily) {
                row.put("type", "DAILY_LIQUIDATION").put("bucket_start", daily.bucketStart().toString())
                        .put("long_liquidations_usd", daily.longLiquidationsUsd())
                        .put("short_liquidations_usd", daily.shortLiquidationsUsd());
            } else if (observation instanceof LiquidationStructureRouterV1.Bar bar) {
                row.put("type", "BAR").put("timeframe", bar.timeframe().name())
                        .put("start_time", bar.startTime().toString()).put("open", bar.open())
                        .put("high", bar.high()).put("low", bar.low()).put("close", bar.close());
            } else if (observation instanceof LiquidationStructureRouterV1.OpenInterest oi) {
                row.put("type", "OPEN_INTEREST").put("observed_at", oi.observedAt().toString())
                        .put("base_quantity", oi.baseQuantity());
            } else if (observation instanceof LiquidationStructureRouterV1.MacroClose macro) {
                row.put("type", "MACRO_CLOSE").put("close_time", macro.closeTime().toString())
                        .put("close", macro.close()).put("provenance_available", macro.provenanceAvailable());
            }
            return row;
        }

        private void appendObjectMap(ObjectNode destination, Map<String, ObjectNode> source) {
            source.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> destination.set(entry.getKey(), entry.getValue().deepCopy()));
        }

        private ObjectNode createOpportunity(String candidate, String pair, String asset, Instant decision, String variant) {
            return createOpportunity(candidate, pair, asset, decision, variant, null, null);
        }

        private ObjectNode createOpportunity(String candidate, String pair, String asset, Instant decision,
                String variant, String branch, String direction) {
            String key = opportunityKey(candidate, pair);
            ObjectNode existing = opportunities.get(key); if (existing != null) return existing;
            ObjectNode row = JsonHashes.mapper().createObjectNode().put("opportunity_id", candidate + "|" + pair)
                    .put("pair_id", pair).put("candidate_id", candidate).put("variant", variant).put("asset", asset)
                    .put("decision_time", decision.toString()).put("stage", 1).put("outcome_state", "PENDING_EXECUTION");
            if (branch != null) row.put("branch", branch);
            if (direction != null) row.put("direction", direction);
            row.putNull("outcome_available_time").putNull("first_fill_time").putNull("exit_time").putNull("net_pnl_usdt")
                    .put("reference_risk_usdt", 0).putArray("reason_codes"); opportunities.put(key, row); return row;
        }
        private void resolveNoTrade(ObjectNode row, Instant time, String why) {
            row.put("outcome_state", "RESOLVED_NO_TRADE").put("outcome_available_time", time.toString())
                    .put("net_pnl_usdt", 0).put("reference_risk_usdt", 0);
            reason(row, why);
        }
        private void resolveBlocked(ObjectNode row, Instant time, String why) {
            row.put("outcome_state", "COVERAGE_BLOCKED").putNull("outcome_available_time")
                    .putNull("net_pnl_usdt").put("reference_risk_usdt", 0);
            reason(row, why);
        }

        private ObjectNode result() {
            ArrayNode opps = JsonHashes.mapper().createArrayNode();
            opportunities.values().stream().sorted(Comparator.comparing((ObjectNode row) -> row.path("decision_time").asText())
                    .thenComparingInt(row -> ASSETS.indexOf(row.path("asset").asText())).thenComparing(row -> row.path("candidate_id").asText()))
                    .forEach(row -> opps.add(row.deepCopy()));
            ArrayNode diag = JsonHashes.mapper().createArrayNode(); diagnostics.forEach(row -> diag.add(row.deepCopy()));
            ArrayNode audit = JsonHashes.mapper().createArrayNode(); routeAudit.forEach(row -> audit.add(row.deepCopy()));
            ArrayNode gapRows = JsonHashes.mapper().createArrayNode(); gaps.forEach(row -> gapRows.add(row.deepCopy()));
            ArrayNode qualifiedEvents = JsonHashes.mapper().createArrayNode();
            qualifiedStressEventInventory.forEach(row -> qualifiedEvents.add(row.deepCopy()));
            ArrayNode identities = JsonHashes.mapper().createArrayNode(), routeCurve = JsonHashes.mapper().createArrayNode();
            ObjectNode ledger = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-account-ledger-set/1")
                    .put("status", synthetic ? "SYNTHETIC_DEVELOPMENT_ONLY" : "PROXY_DISCLOSED_DEVELOPMENT_ONLY")
                    .put("authoritative", false).put("risk_netting_credit", false);
            ArrayNode accountRows = ledger.putArray("accounts"); long eventCount = 0;
            for (int index = 0; index < accounts.size(); index++) {
                ObjectNode snapshot = accounts.get(index).snapshot();
                ObjectNode row = accountRows.addObject().put("candidate_id", candidateId(index)); row.set("account", snapshot);
                row.set("account_curve", snapshot.path("marked_equity_curve").deepCopy());
                identities.addObject().put("candidate_id", candidateId(index)).put("event_count", snapshot.path("event_count").asLong())
                        .put("event_stream_sha256", snapshot.path("event_stream_sha256").asText());
                eventCount += snapshot.path("event_count").asLong();
                if (index == ROUTED) routeCurve.addAll((ArrayNode) snapshot.path("marked_equity_curve").deepCopy());
            }
            ledger.set("opportunities", opps.deepCopy()); ledger.set("diagnostic_events", diag.deepCopy());
            ledger.set("route_audit", audit.deepCopy()); ledger.set("coverage_gaps", gapRows.deepCopy());
            if (staged != null) ledger.set("stage_attempts", jsonArray(stageAttemptRows));
            ledger.put("event_count", eventCount).put("event_stream_sha256", JsonHashes.canonicalSha256(identities))
                    .put("features_read", featuresRead).put("minute_pairs_read", minutePairsRead)
                    .put("funding_rows_read", fundingRowsRead).put("metadata_tier_rows_read", metadataRowsRead)
                    .put("feature_warmup_start", featureFrom.toString()).put("replay_start", decisionFrom.toString())
                    .put("decision_end_exclusive", decisionEnd.toString()).put("replay_end_exclusive", to.toString());
            ledger.put("content_sha256", JsonHashes.ownHash(ledger));
            ObjectNode routedSnapshot = (ObjectNode) accountRows.get(ROUTED).path("account");
            ObjectNode accountPath = JsonHashes.mapper().createObjectNode()
                    .put("schema", "liquidation-v2-account-path-summary/1")
                    .put("ledger_sha256", ledger.path("content_sha256").asText())
                    .put("drawdown_basis", "ADVERSE_MARK_OHLC_EXTREMUM_BEFORE_EXIT_PATH")
                    .put("maximum_adverse_mark_drawdown_fraction", decimalNode(routedSnapshot.path("maximum_event_path_drawdown_fraction")))
                    .put("starting_equity_usdt", new BigDecimal(freeze.path("precommit").path("user_constraints")
                            .path("starting_equity_usdt").asText("20000")))
                    .put("ending_marked_equity_usdt", decimalNode(routedSnapshot.path("mark_to_market_equity_usdt")));
            accountPath.put("content_sha256", JsonHashes.ownHash(accountPath));
            ObjectNode out = JsonHashes.mapper().createObjectNode().put("schema", REPLAY_SCHEMA).put("version", 1)
                    .put("status", synthetic ? "SYNTHETIC_DEVELOPMENT_ONLY" : "PROXY_DISCLOSED_DEVELOPMENT_ONLY")
                    .put("evidence_tier", "DEVELOPMENT_ONLY").put("authoritative", false)
                    .put("historical_availability_proven", false).put("historical_revision_proven", false)
                    .put("wfo_permitted", false).put("sealed_confirmation_permitted", false)
                    .put("prospective_live_permitted", false).put("trade_authorization_permitted", false)
                    .put("source_mode", manifest.path("source_mode").asText()).put("manifest_sha256", manifest.path("content_sha256").asText())
                    .put("dataset_root_sha256", manifest.path("dataset_root_sha256").asText())
                    .put("feature_warmup_start", featureFrom.toString()).put("replay_start", decisionFrom.toString())
                    .put("decision_end_exclusive", decisionEnd.toString()).put("replay_end_exclusive", to.toString())
                    .put("macro_availability_basis", macroAvailabilityBasis)
                    .put("default_stage_add_macro_gate_policy", CORE_ADDITION_MACRO_POLICY)
                    .put("current_reserved_candidate_count", freeze.path("candidate_inventory").path("reserved_candidate_ids").size())
                    .put("account_curve_scope", "SAMPLED_DAILY_ACCOUNT_EQUITY")
                    .put("event_count", eventCount).put("event_stream_sha256", JsonHashes.canonicalSha256(identities));
            out.putObject("execution_request").put("synthetic_smoke", synthetic)
                    .put("feature_warmup_start", featureFrom.toString()).put("replay_start", decisionFrom.toString())
                    .put("decision_end_exclusive", decisionEnd.toString())
                    .put("execution_end_exclusive", to.toString()).put("replay_end_exclusive", to.toString());
            ObjectNode freezeRef = out.putObject("freeze");
            for (String field : List.of("manifest_sha256", "precommit_sha256", "profile_sha256", "source_mode")) freezeRef.set(field, freeze.path(field).deepCopy());
            freezeRef.put("executor_sha256", freeze.path("executor_identity").path("content_sha256").asText())
                    .put("candidate_inventory_sha256", freeze.path("candidate_inventory").path("content_sha256").asText())
                    .putNull("prior_family_inventory_sha256");
            ObjectNode parentLineage = (ObjectNode) freeze.path("parent_lineage");
            freezeRef.put("parent_precommit_sha256", parentLineage.path("parent_precommit").path("content_sha256").asText())
                    .put("parent_freeze_manifest_byte_sha256", parentLineage.path("parent_freeze_manifest_byte_sha256").asText())
                    .put("parent_feasibility_byte_sha256", parentLineage.path("parent_feasibility_byte_sha256").asText());
            out.set("opportunities", opps); out.set("diagnostic_events", diag); out.set("route_audit", audit); out.set("coverage_gaps", gapRows);
            if (staged != null) {
                out.put("mode_id", staged.modeId()).put("candidate_id", staged.candidateId())
                        .put("plan_sha256", staged.planSha256()).put("anchor_inventory_sha256", staged.anchorInventorySha256())
                        .put("macro_gate_policy", staged.macroGatePolicy().name())
                        .put("position_outcome_granularity", "ONE_OUTCOME_PER_FIRST_FILL_EPISODE");
                out.set("staged_plan", staged.plan().deepCopy());
                out.set("stage_attempts", jsonArray(stageAttemptRows));
            }
            out.set("qualified_daily_stress_events", qualifiedEvents);
            ArrayNode evaluated = out.putArray("evaluated_candidates");
            String executionScope = synthetic ? "SYNTHETIC_FIXTURE_EXECUTED" : "PROXY_RETROSPECTIVE_DIAGNOSTIC_EXECUTED";
            JsonNode evaluatedInventory = staged == null ? freeze.path("candidate_inventory").path("current_candidates")
                    : JsonHashes.mapper().createArrayNode().add(LiquidationV2StagedCandidateInventoryV1.candidate(staged.plan(), staged.modeId()));
            for (JsonNode candidate : evaluatedInventory) {
                ObjectNode row = ((ObjectNode) candidate).deepCopy().put("execution_scope", executionScope);
                String candidateId = candidate.path("candidate_id").asText();
                long count = opportunities.values().stream().filter(value -> candidateId.equals(value.path("candidate_id").asText())).count();
                row.put("opportunity_count", count).put("executed", true);
                evaluated.add(row);
            }
            out.set("account_curve", routeCurve); out.put("account_curve_sha256", JsonHashes.canonicalSha256(routeCurve));
            out.set("account_path_summary", accountPath);
            out.putArray("stress_results");
            out.put("stress_suite_sha256", JsonHashes.canonicalSha256(freeze.path("precommit")
                    .path("experiment").path("acceptance").path("stress").path("required_scenarios")));
            if (stressScenarioId != null) {
                ObjectNode scenarioExecution = out.putObject("scenario_execution").put("schema", "liquidation-v2-stress-scenario-run/1")
                        .put("scenario_id", stressScenarioId).put("stress_policy_sha256", stressPolicy.contentSha256())
                        .put("transform_count", stressTransformCount).put("transform_chain_sha256", stressTransformChain)
                        .put("affected_position_count", stressAffectedPositionIds.size())
                        .put("outage_deferred_exit_trigger_count", outageDeferredExitTriggers)
                        .put("outage_blocked_stop_update_count", outageBlockedStopUpdates)
                        .put("outage_cancelled_entry_attempt_count", stressBlockedEntries)
                        .put("blocked_entry_count", stressBlockedEntries)
                        .put("physical_observation_stream_sha256", out.path("event_stream_sha256").asText())
                        .put("source_mode", manifest.path("source_mode").asText())
                        .put("development_only", true).put("outcome_source", "FRESH_SCENARIO_ACCOUNT_AND_ROUTER_REPLAY");
                ObjectNode affected = scenarioExecution.putObject("affected_positions_by_candidate");
                ObjectNode blockedByCandidate = scenarioExecution.putObject("blocked_entries_by_candidate");
                for (String candidate : CANDIDATES.subList(0, 3)) {
                    long count = stressAffectedPositionIds.stream().filter(id -> id.startsWith(candidate + "|")).count();
                    affected.put(candidate, Math.toIntExact(count));
                    blockedByCandidate.put(candidate, stressBlockedByCandidate.getOrDefault(candidate, 0));
                }
                scenarioExecution.set("qualified_daily_stress_events", qualifiedEvents.deepCopy());
                scenarioExecution.set("transforms", stressTransformRows.deepCopy());
                ArrayNode intervals = scenarioExecution.putArray("outage_intervals");
                outageIntervals.forEach(row -> intervals.add(row.deepCopy()));
                ArrayNode decisions = scenarioExecution.putArray("outage_decisions");
                outageDecisions.forEach(row -> decisions.add(row.deepCopy()));
                ArrayNode exposures = scenarioExecution.putArray("outage_position_exposures");
                outagePositionExposures.forEach(row -> exposures.add(row.deepCopy()));
            }
            out.put("ledger_sha256", ledger.path("content_sha256").asText()); out.set("event_stream_identity", identities); out.set("ledger", ledger);
            out.put("content_sha256", JsonHashes.ownHash(out)); return out;
        }

        private ObjectNode accountEvent(String type, String asset, long time, ObjectNode fields) {
            ObjectNode event = JsonHashes.mapper().createObjectNode().put("type", type).put("asset", asset).put("time", time);
            fields.fields().forEachRemaining(field -> event.set(field.getKey(), field.getValue().deepCopy())); return event;
        }
        private ObjectNode barEvent(String type, String asset, long time, ObjectNode trade, ObjectNode mark) {
            ObjectNode fields = JsonHashes.mapper().createObjectNode().put("bar_start_time", epoch(trade, "open_time"));
            for (String field : List.of("open", "high", "low", "close")) {
                fields.put("trade_" + field, number(trade, field)); fields.put("mark_" + field, number(mark, field));
            }
            return accountEvent(type, asset, time, fields);
        }
        private boolean metadataCovers(String asset, long time) { return metadata.getOrDefault(asset, List.of()).stream()
                .anyMatch(row -> epoch(row, "effective_from") <= time && time < epoch(row, "effective_until")); }
        private ObjectNode minute(String role, String asset, long time) { return ("execution".equals(role) ? activeExecution : activeMarks).get(key(asset, time)); }
        private BigDecimal stageOneRisk(LiquidationPortfolioAccountingV1.AccountSession account, String asset) {
            ObjectNode position = accountPosition(account.snapshot(), asset);
            for (JsonNode fill : position.path("fills")) if (fill.path("stage").asInt() == 1) return fill.path("planned_tranche_risk_usdt").decimalValue();
            return BigDecimal.ZERO;
        }
    }

    private enum Kind { ACCOUNT, FEATURE, ATTEMPT, GAP, CALLBACK }
    private record Scheduled(long time, Kind kind, String asset, ObjectNode event,
            LiquidationStructureRouterV1.Observation observation, LiquidationStructureRouterV1.ConfirmedIntent intent,
            int account, String candidate, String reason) {
        static Scheduled account(long time, ObjectNode event) { return new Scheduled(time, Kind.ACCOUNT, event.path("asset").asText(), event, null, null, -1, "", ""); }
        static Scheduled feature(long time, LiquidationStructureRouterV1.Observation obs) { return new Scheduled(time, Kind.FEATURE, obs.asset(), null, obs, null, -1, "", ""); }
        static Scheduled attempt(long time, int account, String candidate, LiquidationStructureRouterV1.ConfirmedIntent intent) { return new Scheduled(time, Kind.ATTEMPT, intent.asset(), null, null, intent, account, candidate, ""); }
        static Scheduled gap(long time, String asset, String reason) { return new Scheduled(time, Kind.GAP, asset, null, null, null, -1, "", reason); }
        static Scheduled callback(long time, LiquidationStructureRouterV1.ConfirmedIntent intent, int account, String reason) { return new Scheduled(time, Kind.CALLBACK, intent.asset(), null, null, intent, account, "", reason); }
    }
    private record Feature(long time, LiquidationStructureRouterV1.Observation observation) {}
    private record Pending(int account, String candidate, LiquidationStructureRouterV1.ConfirmedIntent intent,
            boolean routed, String anchorPairId) {}
    private record StagedPredecessor(ObjectNode plan, ObjectNode replay, ObjectNode evidence) {}
    private record StagedConfig(String modeId, String candidateId,
            LiquidationStructureRouterV1.MacroGatePolicy macroGatePolicy, String planSha256,
            String anchorInventorySha256, String sourceMode, ObjectNode plan, List<ObjectNode> anchors) {
        private StagedConfig {
            Objects.requireNonNull(modeId); Objects.requireNonNull(candidateId); Objects.requireNonNull(macroGatePolicy);
            Objects.requireNonNull(planSha256); Objects.requireNonNull(anchorInventorySha256); Objects.requireNonNull(sourceMode);
            plan = plan.deepCopy();
            anchors = anchors.stream().map(ObjectNode::deepCopy).toList();
        }
        private static StagedConfig fromPlan(ObjectNode value) {
            if (!"liquidation-v2-staged-candidate-plan/1".equals(value.path("schema").asText())
                    || !JsonHashes.ownHash(value).equals(value.path("content_sha256").asText())) {
                throw fail("staged candidate plan schema or self-hash is invalid");
            }
            String mode = text(value, "mode_id"), candidate = text(value, "candidate_id");
            LiquidationStructureRouterV1.MacroGatePolicy policy;
            try { policy = LiquidationStructureRouterV1.MacroGatePolicy.valueOf(text(value, "macro_gate_policy")); }
            catch (IllegalArgumentException error) { throw fail("staged candidate macro policy is invalid"); }
            if (!("THREE_STAGE_NO_MACRO".equals(mode) && policy == LiquidationStructureRouterV1.MacroGatePolicy.STRUCTURE_ONLY)
                    && !("THREE_STAGE_MACRO".equals(mode) && policy == LiquidationStructureRouterV1.MacroGatePolicy.REQUIRE_MACRO_CONFIRMATION)) {
                throw fail("staged candidate mode and macro policy disagree");
            }
            JsonNode anchorsNode = value.path("decision_anchors");
            if (!anchorsNode.isArray()) throw fail("staged candidate plan must include the complete decision_anchors array");
            ArrayNode anchors = (ArrayNode) anchorsNode;
            String anchorHash = text(value, "anchor_inventory_sha256");
            if (!JsonHashes.canonicalSha256(anchors).equals(anchorHash)) throw fail("staged decision anchor inventory hash is invalid");
            List<ObjectNode> rows = new ArrayList<>();
            String previousOrder = null;
            for (JsonNode anchorNode : anchors) {
                if (!(anchorNode instanceof ObjectNode anchor)) throw fail("staged decision anchor must be an object");
                ObjectNode intent = object(anchor, "initial_intent");
                if (!JsonHashes.ownHash(intent).equals(intent.path("content_sha256").asText())
                        || !JsonHashes.canonicalSha256(intent).equals(text(anchor, "initial_intent_sha256"))
                        || intent.path("stage").asInt(-1) != 1
                        || !text(anchor, "pair_id").equals(text(intent, "pair_id"))
                        || !text(anchor, "asset").equals(text(intent, "asset"))
                        || !text(anchor, "decision_time").equals(text(intent, "decision_time"))
                        || !intent.path("setup_seed").isObject()) {
                    throw fail("staged decision anchor intent or setup seed is invalid");
                }
                String order = text(anchor, "decision_time") + "|" + String.format(Locale.ROOT, "%02d", assetRank(text(anchor, "asset")))
                        + "|" + text(anchor, "pair_id");
                if (previousOrder != null && order.compareTo(previousOrder) < 0) throw fail("staged decision anchors are not in frozen chronological asset order");
                previousOrder = order;
                rows.add(anchor.deepCopy());
            }
            return new StagedConfig(mode, candidate, policy, value.path("content_sha256").asText(), anchorHash,
                    text(value, "source_mode"), value, rows);
        }
    }
    private static final class Trade {
        final LiquidationStructureRouterV1.ConfirmedIntent intent; final String candidate; final long fillTime; double activeStop, proposedStop; long proposedStopAt = Long.MIN_VALUE;
        Trade(LiquidationStructureRouterV1.ConfirmedIntent intent, String candidate, double stop, long fillTime) { this.intent = intent; this.candidate = candidate; this.activeStop = stop; this.proposedStop = stop; this.fillTime = fillTime; }
    }
    private static final class DailySidePair {
        final String asset; final long event; long available; double longUsd = Double.NaN, shortUsd = Double.NaN;
        DailySidePair(String asset, long event, long available) { this.asset = asset; this.event = event; this.available = available; }
    }
    private static final Comparator<Feature> FEATURE_ORDER = Comparator.comparingLong(Feature::time)
            .thenComparing(Feature::observation, LiquidationPortfolioReplayV1::compareObservation);
    private static final Comparator<Scheduled> SCHEDULE_ORDER = Comparator.comparingLong(Scheduled::time)
            .thenComparingInt(LiquidationPortfolioReplayV1::phase).thenComparingInt(row -> assetRank(row.asset))
            .thenComparingLong(row -> row.event == null ? Long.MIN_VALUE : row.event.path("decision_time").asLong(row.time))
            .thenComparingInt(row -> row.event == null ? 0 : row.event.path("stage").asInt(0));
    private static int phase(Scheduled row) { return switch (row.kind) { case FEATURE -> 4; case GAP -> 5; case CALLBACK -> 6; case ATTEMPT -> 7; case ACCOUNT -> accountOrder(row.event); }; }
    private static int accountOrder(ObjectNode row) { return switch (row.path("type").asText()) {
        case "BAR_POST" -> 0; case "METADATA" -> 1; case "MARK" -> 2; case "FUNDING" -> 3; case "STOP_UPDATE" -> 4; case "BAR_PRE" -> 5; case "EXIT" -> 6; case "ADD" -> 7; default -> throw fail("unsupported replay account event"); }; }
    private static Feature feature(LiquidationStructureRouterV1.Observation obs) {
        Instant available = obs instanceof LiquidationStructureRouterV1.DailyLiquidation daily ? daily.modeledAvailableAt() : obs.availableAt();
        return new Feature(available.toEpochMilli(), obs);
    }
    private static int compareObservation(LiquidationStructureRouterV1.Observation a, LiquidationStructureRouterV1.Observation b) {
        int cmp = a.eventTime().compareTo(b.eventTime()); if (cmp != 0) return cmp;
        cmp = Integer.compare(observationPriority(a), observationPriority(b)); if (cmp != 0) return cmp;
        cmp = a.asset().compareTo(b.asset()); return cmp != 0 ? cmp : a.seriesId().compareTo(b.seriesId());
    }
    private static int observationPriority(LiquidationStructureRouterV1.Observation obs) {
        if (obs instanceof LiquidationStructureRouterV1.DailyLiquidation) return 0;
        if (obs instanceof LiquidationStructureRouterV1.MacroClose) return 1;
        if (obs instanceof LiquidationStructureRouterV1.OpenInterest) return 2;
        return ((LiquidationStructureRouterV1.Bar) obs).timeframe() == LiquidationStructureRouterV1.Timeframe.FOUR_HOUR ? 3 : 4;
    }

    private static Map<String, List<ObjectNode>> groupMetadata(List<ObjectNode> rows) {
        Map<String, TreeMap<String, MetadataGroup>> groups = new HashMap<>();
        for (ObjectNode row : rows) {
            String asset = row.path("asset").asText(), interval = epoch(row, "effective_from") + "|" + epoch(row, "effective_until");
            groups.computeIfAbsent(asset, ignored -> new TreeMap<>()).computeIfAbsent(interval,
                    ignored -> new MetadataGroup(asset, epoch(row, "effective_from"), epoch(row, "effective_until"))).add(row);
        }
        Map<String, List<ObjectNode>> result = new HashMap<>();
        for (String asset : ASSETS) result.put(asset, groups.getOrDefault(asset, new TreeMap<>()).values().stream().map(MetadataGroup::toJson).toList());
        return Map.copyOf(result);
    }

    private static String macroAvailabilityBasis(ObjectNode manifest, boolean synthetic, JsonNode precommit) {
        String frozenRule = "";
        for (JsonNode input : precommit.path("required_inputs")) {
            if ("sp500".equals(input.path("input_id").asText())) {
                frozenRule = input.path("availability").path("rule").asText("");
                break;
            }
        }
        if (!frozenRule.contains("one additional US session lag")) return "UNKNOWN";
        JsonNode feature = manifest.path("artifacts").path("feature");
        if (synthetic) {
            if (containsText(feature.path("series_scope"), "sp500_close")
                    && LiquidationV2PhysicalDataV1.SYNTHETIC_MACRO_AVAILABILITY_BASIS
                            .equals(feature.path("availability_basis").asText())
                    && isSha256(feature.path("input_byte_sha256").asText())) {
                return "SYNTHETIC_FIXTURE_DECLARED_NEXT_SESSION_ASSUMPTION;NO_EXTERNAL_PIT_PROOF";
            }
            return "UNKNOWN";
        }
        JsonNode partitions = feature.path("partitions");
        if (!partitions.isArray()) return "UNKNOWN";
        String requiredClass = synthetic ? "SYNTHETIC_FIXTURE" : "PUBLIC_ARCHIVE";
        boolean boundSourceAndNormalization = false;
        for (JsonNode partition : partitions) {
            if (!requiredClass.equals(partition.path("source_class").asText())) continue;
            if (!containsText(partition.path("series_scope"), "sp500_close")
                    || !LiquidationV2PhysicalDataV1.MACRO_AVAILABILITY_BASIS
                            .equals(partition.path("availability_basis").asText())) continue;
            if (partition.path("normalization_receipt_path").asText().isBlank()
                    || !isSha256(partition.path("normalization_receipt_byte_sha256").asText())
                    || !isSha256(partition.path("normalizer_sha256").asText())
                    || !isSha256(partition.path("source_byte_sha256").asText())
                    || !isSha256(partition.path("normalized_byte_sha256").asText())) continue;
            boundSourceAndNormalization = true;
            break;
        }
        return boundSourceAndNormalization ? "MODELED_DISCLOSED_NEXT_COMPLETED_NYSE_SESSION;NOT_HISTORICAL_PIT_PROOF" : "UNKNOWN";
    }

    private static boolean isSha256(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static boolean containsText(JsonNode values, String expected) {
        if (!values.isArray()) return false;
        for (JsonNode value : values) if (expected.equals(value.asText())) return true;
        return false;
    }

    private static final class MetadataGroup {
        final String asset; final long from, until; final TreeMap<Integer, ObjectNode> tiers = new TreeMap<>();
        MetadataGroup(String asset, long from, long until) { this.asset = asset; this.from = from; this.until = until; }
        void add(ObjectNode row) { if (tiers.putIfAbsent(row.path("tier_index").asInt(), row.deepCopy()) != null) throw fail("duplicate tier in metadata interval"); }
        ObjectNode toJson() {
            if (tiers.isEmpty()) throw fail("metadata interval is empty");
            ObjectNode first = tiers.firstEntry().getValue(); ObjectNode out = JsonHashes.mapper().createObjectNode().put("asset", asset)
                    .put("effective_from", from).put("effective_until", until).put("lot_size", number(first, "lot_size"))
                    .put("minimum_notional", number(first, "minimum_notional")).put("taker_fee_rate", number(first, "taker_fee_rate"))
                    .put("slippage_rate", number(first, "slippage_rate")).put("liquidation_fee_rate", number(first, "liquidation_fee_rate"));
            ArrayNode values = out.putArray("maintenance_tiers");
            tiers.values().forEach(row -> values.addObject().put("effective_from", from).put("effective_until", until)
                    .put("tier_notional_cap", number(row, "tier_notional_cap")).put("maintenance_margin_rate", number(row, "maintenance_margin_rate"))
                    .put("maintenance_deduction", number(row, "maintenance_deduction")));
            return out;
        }
    }

    private static ObjectNode accountRequest(Map<String, ObjectNode> metadata) {
        ObjectNode request = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-public-replay-account/1")
                .put("initial_equity_usdt", 20_000).put("reserved_costs_usdt", 0);
        ArrayNode positions = request.putArray("positions");
        for (String asset : ASSETS) {
            ObjectNode m = metadata.get(asset), p = positions.addObject().put("asset", asset).put("direction", "UNCONFIGURED")
                    .putNull("common_stop").putNull("recovery_target").put("initial_mark_price", 1)
                    .put("lot_size", number(m, "lot_size")).put("minimum_notional", number(m, "minimum_notional"))
                    .put("taker_fee_rate", number(m, "taker_fee_rate")).put("slippage_rate", number(m, "slippage_rate"))
                    .put("liquidation_fee_rate", number(m, "liquidation_fee_rate"));
            p.set("maintenance_tiers", m.path("maintenance_tiers").deepCopy());
        }
        request.putArray("events"); return request;
    }
    private static ObjectNode placeholderMetadata(String asset) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("asset", asset)
                .put("effective_from", Long.MIN_VALUE).put("effective_until", Long.MAX_VALUE)
                .put("lot_size", 0.01).put("minimum_notional", 5.0).put("taker_fee_rate", 0.0)
                .put("slippage_rate", 0.0).put("liquidation_fee_rate", 0.0);
        row.putArray("maintenance_tiers").addObject().put("effective_from", Long.MIN_VALUE)
                .put("effective_until", Long.MAX_VALUE).put("tier_notional_cap", 1_000_000_000_000.0)
                .put("maintenance_margin_rate", 0.01).put("maintenance_deduction", 0.0);
        return row;
    }
    private static Map<String, ObjectNode> keyed(List<ObjectNode> rows, String timeField) {
        Map<String, ObjectNode> result = new HashMap<>();
        for (ObjectNode row : rows) if (result.putIfAbsent(key(row.path("asset").asText(), epoch(row, timeField)), row) != null) throw fail("duplicate physical asset/time row");
        return result;
    }
    private static ObjectNode barEvent(String type, String asset, long time, ObjectNode trade, ObjectNode mark) {
        ObjectNode fields = JsonHashes.mapper().createObjectNode().put("bar_start_time", epoch(trade, "open_time"));
        for (String field : List.of("open", "high", "low", "close")) { fields.put("trade_" + field, number(trade, field)); fields.put("mark_" + field, number(mark, field)); }
        return event(type, asset, time, fields);
    }
    private static ObjectNode event(String type, String asset, long time, ObjectNode fields) {
        ObjectNode out = JsonHashes.mapper().createObjectNode().put("type", type).put("asset", asset).put("time", time);
        fields.fields().forEachRemaining(entry -> out.set(entry.getKey(), entry.getValue().deepCopy())); return out;
    }

    private static ObjectNode intentAudit(LiquidationStructureRouterV1.ConfirmedIntent intent) {
        ObjectNode out = JsonHashes.mapper().createObjectNode().put("intent_id", intent.intentId()).put("setup_id", intent.setupId())
                .put("pair_id", intent.pairId()).put("asset", intent.asset()).put("variant", intent.variant().name())
                .put("branch", intent.branch().name()).put("direction", intent.direction().name()).put("stage", intent.stage())
                .put("decision_time", intent.decisionTime().toString()).put("requested_execution_after", intent.requestedExecutionAfter().toString())
                .put("initial_stop", intent.initialStop()).put("diagnostic_only", intent.diagnosticOnly());
        return out;
    }
    private static ObjectNode confirmedIntentJson(LiquidationStructureRouterV1.ConfirmedIntent intent) {
        ObjectNode out = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-confirmed-entry-intent/1")
                .put("intent_id", intent.intentId()).put("setup_id", intent.setupId()).put("pair_id", intent.pairId())
                .put("asset", intent.asset()).put("variant", intent.variant().name()).put("branch", intent.branch().name())
                .put("routed_branch", intent.routedBranch().name()).put("direction", intent.direction().name())
                .put("shock_direction", intent.shockDirection().name()).put("stage", intent.stage())
                .put("decision_time", intent.decisionTime().toString())
                .put("requested_execution_after", intent.requestedExecutionAfter().toString())
                .put("confirmation_bar_start", intent.confirmationBarStart().toString())
                .put("confirmation_close", intent.confirmationClose())
                .put("zone_center", intent.zoneCenter()).put("zone_lower", intent.zoneLower()).put("zone_upper", intent.zoneUpper())
                .put("pre_event_atr", intent.preEventAtr()).put("initial_stop", intent.initialStop())
                .put("max_chase_distance", intent.maxChaseDistance())
                .put("tranche_risk_fraction", intent.trancheRiskFraction()).put("proposed_leverage", intent.proposedLeverage())
                .put("maximum_holding_days", intent.maximumHoldingDays()).put("macro_state", intent.macroState().name())
                .put("macro_eligible", intent.macroEligible()).put("diagnostic_only", intent.diagnosticOnly());
        if (intent.pivotPrice() == null) out.putNull("pivot_price"); else out.put("pivot_price", intent.pivotPrice());
        if (intent.pivotTime() == null) out.putNull("pivot_time"); else out.put("pivot_time", intent.pivotTime().toString());
        if (intent.pivotConfirmedAt() == null) out.putNull("pivot_confirmed_at"); else out.put("pivot_confirmed_at", intent.pivotConfirmedAt().toString());
        if (intent.reversalTarget() == null) out.putNull("reversal_target"); else out.put("reversal_target", intent.reversalTarget());
        ArrayNode reasons = out.putArray("rejection_reasons"); intent.rejectionReasons().forEach(reasons::add);
        out.set("source_evidence", sourceEvidenceJson(intent.sourceEvidence()));
        out.put("content_sha256", JsonHashes.ownHash(out));
        return out;
    }
    private static ObjectNode frozenAnchorIntentJson(LiquidationStructureRouterV1.ConfirmedIntent intent,
            LiquidationStructureRouterV1.RouteResult routeResult) {
        LiquidationStructureRouterV1.StageOneAnchorContext context = routeResult.stageOneAnchorContexts().stream()
                .filter(candidate -> candidate.intent().intentId().equals(intent.intentId()))
                .findFirst().orElseThrow(() -> fail("routed stage-one intent omitted its immutable setup seed"));
        if (!context.intent().equals(intent)) throw fail("router setup seed refers to a different stage-one intent");
        return LiquidationStructureRouterV1.stageOneAnchorJson(context);
    }
    private static ObjectNode rejectionAudit(LiquidationStructureRouterV1.RejectedOpportunity rejection) {
        return JsonHashes.mapper().createObjectNode().put("opportunity_id", rejection.opportunityId()).put("pair_id", rejection.pairId())
                .put("setup_id", rejection.setupId()).put("asset", rejection.asset()).put("variant", rejection.variant().name())
                .put("forced_branch", rejection.forcedBranch().name()).put("direction", rejection.direction().name())
                .put("stage", rejection.stage()).put("decision_time", rejection.decisionTime().toString())
                .put("reason_code", rejection.reasonCode()).put("diagnostic_only", rejection.diagnosticOnly());
    }
    private static ArrayNode sourceEvidenceJson(List<LiquidationStructureRouterV1.SourceEvidence> sourceEvidence) {
        ArrayNode rows = JsonHashes.mapper().createArrayNode();
        for (LiquidationStructureRouterV1.SourceEvidence source : sourceEvidence) {
            rows.addObject().put("role", source.role()).put("asset", source.asset()).put("series_id", source.seriesId())
                    .put("event_time", source.eventTime().toString()).put("available_at", source.availableAt().toString());
        }
        return rows;
    }

    private static ObjectNode verifyPhysical(ObjectNode profile, ObjectNode options) {
        ObjectNode manifest = object(options, "manifest");
        return manifest.path("artifacts").path("feature").path("partitions").isArray()
                ? LiquidationV2PhysicalDataV1.verifyDevelopment(options) : LiquidationV2PhysicalDataV1.verifySyntheticDevelopment(options);
    }
    private static void verifyFreeze(ObjectNode freeze) {
        if (!FREEZE_SCHEMA.equals(freeze.path("schema").asText()) || freeze.path("version").asInt(-1) != 1
                || !"FROZEN_BEFORE_OUTCOME_READ".equals(freeze.path("status").asText()) || freeze.path("outcomes_examined").asBoolean(true)
                || !JsonHashes.ownHash(freeze).equals(freeze.path("content_sha256").asText())) throw fail("freeze hash/status is invalid");
        ObjectNode profile = object(freeze, "profile"); LiquidationDailyStressProfileV1.validate(profile);
        ObjectNode precommit = object(freeze, "precommit");
        if (!JsonHashes.ownHash(precommit).equals(precommit.path("content_sha256").asText())
                || !freeze.path("precommit_sha256").asText().equals(precommit.path("content_sha256").asText())
                || !profile.path("precommit_content_sha256").asText().equals(precommit.path("content_sha256").asText())) throw fail("frozen precommit reference is inconsistent");
        ObjectNode manifest = object(freeze, "physical_manifest");
        if (!JsonHashes.ownHash(manifest).equals(manifest.path("content_sha256").asText())
                || !freeze.path("manifest_sha256").asText().equals(manifest.path("content_sha256").asText())
                || !freeze.path("dataset_root_sha256").asText().equals(manifest.path("dataset_root_sha256").asText())) throw fail("physical manifest hash is inconsistent");
        ObjectNode physicalOptions = JsonHashes.mapper().createObjectNode().put("root", text(freeze, "physical_root"));
        physicalOptions.set("profile", profile.deepCopy()); physicalOptions.set("manifest", manifest.deepCopy());
        ObjectNode verification = verifyPhysical(profile, physicalOptions);
        if (!verification.path("manifest_sha256").asText().equals(freeze.path("manifest_sha256").asText())) throw fail("physical inputs changed after freeze");
        ObjectNode inventory = candidateInventory(profile);
        if (!JsonHashes.canonicalSha256(inventory).equals(JsonHashes.canonicalSha256(freeze.path("candidate_inventory")))) throw fail("candidate inventory changed after freeze");
        Path projectRoot = Path.of(text(freeze, "project_root")).toAbsolutePath().normalize();
        ObjectNode currentExecutor = executorIdentity(projectRoot);
        if (!JsonHashes.canonicalSha256(currentExecutor).equals(JsonHashes.canonicalSha256(freeze.path("executor_identity")))) throw fail("executor source files changed after freeze");
        ObjectNode currentPrecommit = readFrozenPrecommit(projectRoot);
        if (!JsonHashes.canonicalSha256(currentPrecommit).equals(JsonHashes.canonicalSha256(precommit))
                || !hashFile(projectRoot.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))
                        .equals(freeze.path("precommit_byte_sha256").asText())) {
            throw fail("checked-in frozen precommit bytes changed after replay freeze");
        }
        ObjectNode currentParentLineage = readParentLineage(projectRoot);
        if (!JsonHashes.canonicalSha256(currentParentLineage).equals(JsonHashes.canonicalSha256(freeze.path("parent_lineage")))) {
            throw fail("v001 family lineage bytes changed after replay freeze");
        }
    }
    private static ObjectNode executorIdentity(Path root) {
        ObjectNode identity = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-executor-identity/1")
                .put("version", 1).put("capability", "liquidation-v2-physical-parquet-v1")
                .put("default_stage_add_macro_gate_policy", CORE_ADDITION_MACRO_POLICY)
                .put("java_version", System.getProperty("java.version"))
                .put("java_runtime_version", System.getProperty("java.runtime.version"))
                .put("java_vm_name", System.getProperty("java.vm.name"));
        ArrayNode files = identity.putArray("sources");
        for (String relative : SOURCE_FILES) {
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) throw fail("executor source file is missing or unsafe: " + relative);
            files.addObject().put("path", relative).put("byte_sha256", hashFile(file));
        }
        ArrayNode bytecode = identity.putArray("loaded_class_resources");
        List<Class<?>> identityRoots = List.of(LiquidationPortfolioReplayV1.class,
                LiquidationPortfolioAccountingV1.class, LiquidationStagedPerpetualLifecycleV1.class,
                LiquidationStructureRouterV1.class, LiquidationV2PhysicalDataV1.class,
                LiquidationV2ReplayEvidenceV1.class, LiquidationV2EvidenceMathV1.class,
                LiquidationV2StagedStatisticsV1.class,
                LiquidationV2StressPolicyV1.class, LiquidationInputQualificationV1.class,
                LiquidationV2StagedCandidateInventoryV1.class, LiquidationV2ReplayCheckpointV1.class,
                LiquidationV2FamilyExposureAttemptV1.class,
                LiquidationDailyStressProfileV1.class, LiquidationChronologicalPolicyV1.class,
                StrategyStatisticalV5.class,
                StrategyResearchAuthoritativeV5.class, CoinalyzeDailyData.class,
                StrategyResearchV5CommandAdapter.class, JsonHashes.class, PathConfinement.class,
                ResearchData.class);
        TreeMap<String, Class<?>> declared = new TreeMap<>();
        for (Class<?> type : identityRoots) collectDeclaredClasses(type, declared);
        TreeMap<String, ObjectNode> resources = new TreeMap<>();
        declared.forEach((name, type) -> resources.put(name, classBytecodeIdentity(type)));
        for (Class<?> type : identityRoots) addDirectoryCompanions(type, resources);
        resources.values().forEach(bytecode::add);
        identity.put("content_sha256", JsonHashes.ownHash(identity)); return identity;
    }

    private static void collectDeclaredClasses(Class<?> type, Map<String, Class<?>> collected) {
        if (collected.putIfAbsent(type.getName(), type) != null) return;
        Class<?>[] nested = type.getDeclaredClasses();
        java.util.Arrays.sort(nested, Comparator.comparing(Class::getName));
        for (Class<?> child : nested) collectDeclaredClasses(child, collected);
    }

    /** Directory classpaths do not have a JAR hash, so bind synthetic `$1` companions too. */
    private static void addDirectoryCompanions(Class<?> rootType, Map<String, ObjectNode> resources) {
        var source = rootType.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) throw fail("loaded executor class has no code-source identity: " + rootType.getName());
        try {
            Path sourcePath = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
            if (!Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) return;
            String packagePath = rootType.getPackageName().replace('.', '/');
            Path packageRoot = sourcePath.resolve(packagePath).normalize();
            if (!packageRoot.startsWith(sourcePath) || !Files.isDirectory(packageRoot, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(packageRoot)) throw fail("executor class package directory is unsafe: " + rootType.getName());
            String outerPrefix = rootType.getSimpleName() + "$";
            try (var entries = Files.list(packageRoot)) {
                for (Path file : entries.sorted().toList()) {
                    String filename = file.getFileName().toString();
                    if (!filename.startsWith(outerPrefix) || !filename.endsWith(".class")) continue;
                    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        throw fail("executor companion class is unsafe: " + file);
                    }
                    String className = rootType.getName() + filename.substring(rootType.getSimpleName().length(), filename.length() - 6);
                    String resource = "/" + className.replace('.', '/') + ".class";
                    if (!resources.containsKey(className)) {
                        resources.put(className, classBytecodeIdentity(className, resource, Files.readAllBytes(file), source.getLocation()));
                    }
                }
            }
        } catch (java.net.URISyntaxException | IOException error) {
            throw fail("cannot enumerate loaded executor companion classes for " + rootType.getName() + ": " + error.getMessage());
        }
    }

    private static ObjectNode classBytecodeIdentity(Class<?> type) {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        final byte[] bytecode;
        try (var input = type.getResourceAsStream(resource)) {
            if (input == null) throw fail("loaded executor class resource is unavailable: " + type.getName());
            bytecode = input.readAllBytes();
        } catch (IOException error) {
            throw fail("cannot hash loaded executor class bytes for " + type.getName() + ": " + error.getMessage());
        }
        var source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) throw fail("loaded executor class has no code-source identity: " + type.getName());
        return classBytecodeIdentity(type.getName(), resource, bytecode, source.getLocation());
    }

    private static ObjectNode classBytecodeIdentity(String className, String resource, byte[] bytecode,
            java.net.URL codeSource) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("class_name", className)
                .put("resource", resource).put("class_bytecode_sha256", JsonHashes.sha256(bytecode))
                .put("code_source", codeSource.toExternalForm());
        try {
            Path sourcePath = Path.of(codeSource.toURI()).toAbsolutePath().normalize();
            if (Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(sourcePath)) {
                row.put("code_source_artifact_sha256", hashFile(sourcePath));
            } else if (!Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                throw fail("loaded executor class code-source is neither a regular artifact nor a directory: " + className);
            }
        } catch (java.net.URISyntaxException error) {
            throw fail("loaded executor class code-source is invalid: " + className);
        }
        return row;
    }
    private static ObjectNode candidateInventory(ObjectNode profile) {
        return LiquidationV2ReplayEvidenceV1.frozenCandidateInventory(profile);
    }
    private static ObjectNode readParentLineage(Path root) {
        String precommitPath = "docs/research/liquidation-structure-v001/frozen-precommit.json";
        String manifestPath = "docs/research/liquidation-structure-v001/FREEZE-MANIFEST.json";
        String feasibilityPath = "docs/research/liquidation-structure-v001/FEASIBILITY.md";
        byte[] precommitBytes = readBytes(root, precommitPath);
        byte[] manifestBytes = readBytes(root, manifestPath);
        byte[] feasibilityBytes = readBytes(root, feasibilityPath);
        try {
            JsonNode precommitNode = JsonHashes.mapper().readTree(precommitBytes);
            JsonNode manifestNode = JsonHashes.mapper().readTree(manifestBytes);
            if (!(precommitNode instanceof ObjectNode precommit) || !(manifestNode instanceof ObjectNode manifest)) {
                throw fail("v001 parent lineage JSON documents must be objects");
            }
            String precommitText = new String(precommitBytes, java.nio.charset.StandardCharsets.UTF_8);
            String manifestText = new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8);
            String feasibilityText = new String(feasibilityBytes, java.nio.charset.StandardCharsets.UTF_8);
            if (!java.util.Arrays.equals(precommitText.getBytes(java.nio.charset.StandardCharsets.UTF_8), precommitBytes)
                    || !java.util.Arrays.equals(manifestText.getBytes(java.nio.charset.StandardCharsets.UTF_8), manifestBytes)
                    || !java.util.Arrays.equals(feasibilityText.getBytes(java.nio.charset.StandardCharsets.UTF_8), feasibilityBytes)) {
                throw fail("v001 parent lineage includes non-UTF-8 document bytes");
            }
            ObjectNode lineage = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-parent-lineage-inputs/1")
                    .put("parent_precommit_path", precommitPath).put("parent_precommit_byte_sha256", JsonHashes.sha256(precommitBytes))
                    .put("parent_freeze_manifest_path", manifestPath).put("parent_freeze_manifest_byte_sha256", JsonHashes.sha256(manifestBytes))
                    .put("parent_feasibility_path", feasibilityPath).put("parent_feasibility_byte_sha256", JsonHashes.sha256(feasibilityBytes));
            lineage.set("parent_precommit", precommit.deepCopy());
            lineage.set("parent_freeze_manifest", manifest.deepCopy());
            lineage.put("parent_freeze_manifest_bytes", manifestText).put("parent_feasibility_markdown", feasibilityText);
            lineage.put("content_sha256", JsonHashes.ownHash(lineage));
            return lineage;
        } catch (IOException error) {
            throw fail("cannot reopen v001 parent lineage: " + error.getMessage());
        }
    }
    private static byte[] readBytes(Path root, String relative) {
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw fail("required lineage document is absent or unsafe: " + relative);
        }
        try { return Files.readAllBytes(file); }
        catch (IOException error) { throw fail("cannot read " + relative + ": " + error.getMessage()); }
    }
    private static ObjectNode readFrozenPrecommit(Path root) {
        Path file = root.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) throw fail("frozen precommit file is absent or symlinked");
        try {
            JsonNode parsed = JsonHashes.mapper().readTree(Files.readAllBytes(file));
            if (!(parsed instanceof ObjectNode object) || !"strategy-precommit/1".equals(object.path("schema").asText())
                    || !"liquidation-daily-stress-v002".equals(object.path("precommit_id").asText())
                    || !"FROZEN".equals(object.path("status").asText()) || !JsonHashes.ownHash(object).equals(object.path("content_sha256").asText())) throw fail("frozen precommit is invalid");
            return object;
        } catch (IOException error) { throw fail("cannot read frozen precommit: " + error.getMessage()); }
    }
    private static ObjectNode roleArtifactHashes(ObjectNode manifest) {
        ObjectNode result = JsonHashes.mapper().createObjectNode();
        for (String role : List.of("feature", "label", "execution", "mark", "funding", "metadata")) {
            JsonNode artifact = manifest.path("artifacts").path(role);
            if (artifact.path("partitions").isArray()) {
                ArrayNode partitions = result.putArray(role);
                for (JsonNode part : artifact.path("partitions")) partitions.addObject().put("path", part.path("path").asText())
                        .put("sha256", part.path("sha256").asText()).put("rows_sha256", part.path("rows_sha256").asText());
            } else result.putObject(role).put("path", artifact.path("path").asText()).put("sha256", artifact.path("sha256").asText());
        }
        return result;
    }

    private static ObjectNode accountPosition(ObjectNode snapshot, String asset) {
        for (JsonNode position : snapshot.path("positions")) if (asset.equals(position.path("asset").asText())) return (ObjectNode) position;
        throw fail("account snapshot is missing " + asset);
    }
    private static LiquidationStructureRouterV1.CloseReason closeReason(ObjectNode row) {
        String reason = row.path("outage_trigger_reason").asText(row.path("exit_reason").asText(row.path("liquidation_reason").asText("")));
        return switch (reason) { case "STOP", "STOP_GAP" -> LiquidationStructureRouterV1.CloseReason.STOP;
            case "RECOVERY_TARGET", "RECOVERY_TARGET_GAP" -> LiquidationStructureRouterV1.CloseReason.TARGET;
            case "SIXTY_DAY_LIFECYCLE" -> LiquidationStructureRouterV1.CloseReason.TIMEOUT;
            case "LIQUIDATION", "FUNDING_MARGIN_DEBIT_MAINTENANCE_BREACH" -> LiquidationStructureRouterV1.CloseReason.LIQUIDATION;
            default -> LiquidationStructureRouterV1.CloseReason.OTHER; };
    }
    private static List<LiquidationStructureRouterV1.Bar> lastThree(TreeMap<Instant, LiquidationStructureRouterV1.Bar> rows) {
        if (rows.size() < 3) return List.of();
        List<LiquidationStructureRouterV1.Bar> result = new ArrayList<>(rows.descendingMap().values()).subList(0, 3); java.util.Collections.reverse(result);
        for (int i = 1; i < result.size(); i++) if (!result.get(i).eventTime().equals(result.get(i - 1).eventTime().plusSeconds(14_400))) return List.of();
        return List.copyOf(result);
    }
    private static void reason(ObjectNode row, String value) { ArrayNode values = (ArrayNode) row.path("reason_codes"); if (!contains(values, value)) values.add(value); }
    private static String opportunityKey(String candidate, String pair) { return candidate + "\u0000" + pair; }
    private static String key(String asset, long time) { return asset + "|" + time; }
    private static int assetRank(String asset) { return ASSETS.indexOf(asset); }
    private static boolean dayAligned(Instant value) { return value.equals(value.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()); }
    private static long nextOpenAfter(LiquidationStructureRouterV1.ConfirmedIntent intent, long knownAt) {
        long latest = Math.max(intent.requestedExecutionAfter().toEpochMilli(), knownAt);
        for (LiquidationStructureRouterV1.SourceEvidence source : intent.sourceEvidence()) latest = Math.max(latest, source.availableAt().toEpochMilli());
        return Math.floorDiv(latest, MINUTE) * MINUTE + MINUTE;
    }
    private static long epoch(ObjectNode row, String field) { JsonNode value = row.get(field); if (value == null || !value.isIntegralNumber()) throw fail(field + " must be an integral epoch millisecond"); return value.asLong(); }
    private static BigDecimal decimalNode(JsonNode value) {
        if (value == null || value.isNull()) throw fail("decimal value is absent");
        try { return value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText()); }
        catch (NumberFormatException error) { throw fail("decimal value is invalid"); }
    }
    private static double number(ObjectNode row, String field) { JsonNode value = row.get(field); if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())) throw fail(field + " must be finite numeric data"); return value.asDouble(); }
    private static String text(ObjectNode row, String field) { JsonNode value = row.get(field); if (value == null || !value.isTextual() || value.asText().isBlank()) throw fail(field + " is required"); return value.asText(); }
    private static ObjectNode object(ObjectNode row, String field) { JsonNode value = row.get(field); if (value == null || !value.isObject()) throw fail(field + " must be a JSON object"); return (ObjectNode) value; }
    private static boolean contains(JsonNode array, String value) { if (!array.isArray()) return false; for (JsonNode row : array) if (value.equals(row.asText())) return true; return false; }
    private static IllegalArgumentException fail(String message) { return new IllegalArgumentException(message); }
    private static String hashFile(Path file) { try { return JsonHashes.sha256(Files.readAllBytes(file)); } catch (IOException error) { throw fail("cannot hash " + file + ": " + error.getMessage()); } }
    private static void writeImmutable(Path root, Path output, byte[] bytes) {
        Path requestedBase = root.toAbsolutePath().normalize(), requestedTarget = output.toAbsolutePath().normalize();
        Path base = PathConfinement.requireRealDirectory(requestedBase, "frozen physical root");
        if (!requestedTarget.startsWith(requestedBase) || requestedTarget.equals(requestedBase)) {
            throw fail("output must remain beneath the frozen physical root");
        }
        Path target = base.resolve(requestedBase.relativize(requestedTarget)).normalize();
        if (!target.startsWith(base) || target.equals(base)) throw fail("output must remain beneath the frozen physical root");
        String relative = PathConfinement.repositoryRelativePath(
                base.relativize(target).toString().replace('\\', '/'), "liquidation v002 replay");
        try {
            Path parent = target.getParent(), cursor = base;
            for (Path part : base.relativize(parent)) {
                cursor = cursor.resolve(part);
                if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(cursor) || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                        throw fail("output path parent is unsafe");
                    }
                } else {
                    Files.createDirectory(cursor);
                }
                Path real = cursor.toRealPath();
                if (!real.startsWith(base)) throw fail("output path parent resolves outside the frozen physical root");
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                PathConfinement.resolve(base, relative, "liquidation v002 replay", PathConfinement.ExpectedType.FILE);
                if (!JsonHashes.sha256(target).equals(JsonHashes.sha256(bytes))) throw fail("immutable replay output already exists with different bytes");
            } else {
                Path realParent = parent.toRealPath();
                if (!realParent.startsWith(base)) throw fail("output path parent resolves outside the frozen physical root");
                Files.write(target, bytes, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
            }
            PathConfinement.resolve(base, relative, "liquidation v002 replay", PathConfinement.ExpectedType.FILE);
            PathConfinement.validateSinglyLinkedFile(target, "liquidation v002 replay");
        } catch (IOException error) { throw fail("cannot write replay result: " + error.getMessage()); }
    }
}

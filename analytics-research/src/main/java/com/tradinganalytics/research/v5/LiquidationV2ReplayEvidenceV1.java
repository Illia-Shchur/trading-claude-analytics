package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Hash-bound, outcome-inventory and chronological-fold adapter for v002 public replay results. */
public final class LiquidationV2ReplayEvidenceV1 {
    public static final String INPUT_SCHEMA = "liquidation-v2-replay-result/1";
    public static final String EVIDENCE_SCHEMA = "liquidation-v2-replay-evidence/1";
    public static final String CANDIDATE_INVENTORY_SCHEMA = "liquidation-v2-candidate-inventory/1";
    public static final String EXECUTOR_IDENTITY_SCHEMA = "liquidation-v2-executor-identity/1";
    public static final int MINIMUM_INDEPENDENT_EPISODES = 30;
    public static final int BLOCK_DAYS = 67;
    public static final Duration DESCRIPTIVE_CLUSTER_WINDOW = Duration.ofHours(72);
    private static final String FAMILY = "liquidation-structure";
    private static final String SYNTHETIC_MODE = "SYNTHETIC_DEVELOPMENT_ONLY";
    private static final String PROXY_MODE = "PROXY_DISCLOSED_DEVELOPMENT_ONLY";
    private static final List<CandidateSpec> CANDIDATES = List.of(
            new CandidateSpec("liquidation-v2-core-routed-one-entry",
                    "ROUTED_REVERSAL_CONTINUATION", false),
            new CandidateSpec("liquidation-v2-core-always-continuation-one-entry",
                    "ALWAYS_CONTINUATION_CONTROL", false),
            new CandidateSpec("liquidation-v2-core-always-reversal-one-entry",
                    "ALWAYS_REVERSAL_CONTROL", false),
            new CandidateSpec("liquidation-v2-price-oi-only-event-diagnostic",
                    "PRICE_OI_ONLY_EVENT_DIAGNOSTIC", true));
    private static final List<String> CORE_VARIANTS = List.of("ROUTED_REVERSAL_CONTINUATION",
            "ALWAYS_CONTINUATION_CONTROL", "ALWAYS_REVERSAL_CONTROL");
    private static final List<String> REQUIRED_STRESSES = List.of("fee_slippage", "funding_carry",
            "adverse_execution_gap", "liquidity_capacity", "venue_outage_blackout");
    private static final Set<String> TRADED_VARIANTS = Set.of(
            "ROUTED_REVERSAL_CONTINUATION", "ALWAYS_CONTINUATION_CONTROL", "ALWAYS_REVERSAL_CONTROL");
    private static final Set<String> ASSETS = Set.of("BTC", "ETH", "SOL", "AAVE");

    private LiquidationV2ReplayEvidenceV1() {}

    /** Actual frozen objects are supplied so replay references can be checked against their bytes. */
    public record FreezeInputs(ObjectNode manifest, ObjectNode precommit, ObjectNode profile,
            ObjectNode executorIdentity, ObjectNode candidateInventory, ObjectNode priorFamilyInventory,
            ObjectNode parentPrecommit, ObjectNode parentFreezeManifest, String parentFreezeManifestBytes,
            String parentFeasibilityMarkdown) {
        public FreezeInputs(ObjectNode manifest, ObjectNode precommit, ObjectNode profile,
                ObjectNode executorIdentity, ObjectNode candidateInventory) {
            this(manifest, precommit, profile, executorIdentity, candidateInventory, null, null, null, null, null);
        }
        public FreezeInputs(ObjectNode manifest, ObjectNode precommit, ObjectNode profile,
                ObjectNode executorIdentity, ObjectNode candidateInventory, ObjectNode priorFamilyInventory) {
            this(manifest, precommit, profile, executorIdentity, candidateInventory, priorFamilyInventory,
                    null, null, null, null);
        }
        public FreezeInputs {
            manifest = copy(manifest, "manifest");
            precommit = copy(precommit, "precommit");
            profile = copy(profile, "profile");
            executorIdentity = copy(executorIdentity, "executorIdentity");
            candidateInventory = copy(candidateInventory, "candidateInventory");
            priorFamilyInventory = priorFamilyInventory == null ? null : priorFamilyInventory.deepCopy();
            parentPrecommit = parentPrecommit == null ? null : parentPrecommit.deepCopy();
            parentFreezeManifest = parentFreezeManifest == null ? null : parentFreezeManifest.deepCopy();
        }
    }

    /** Canonical current inventory only. It is deliberately not represented as cumulative family K. */
    public static ObjectNode frozenCandidateInventory(ObjectNode profile) {
        LiquidationDailyStressProfileV1.validate(Objects.requireNonNull(profile, "profile"));
        ObjectNode inventory = JsonHashes.mapper().createObjectNode()
                .put("schema", CANDIDATE_INVENTORY_SCHEMA).put("version", 1)
                .put("strategy_family", FAMILY)
                .put("precommit_sha256", profile.path("precommit_content_sha256").asText())
                .put("profile_sha256", profile.path("content_sha256").asText())
                .put("default_stage_add_macro_gate_policy", "REQUIRE_MACRO_CONFIRMATION")
                .put("selection_policy", "FROZEN_NO_OPTIMIZATION")
                .put("inventory_scope", "CURRENT_V002_RESERVED_VARIANTS_ONLY;PRIOR_FAMILY_EXPOSURE_IS_SEPARATELY_REQUIRED");
        ArrayNode candidates = inventory.putArray("current_candidates");
        ArrayNode reservedIds = inventory.putArray("reserved_candidate_ids");
        for (CandidateSpec candidate : CANDIDATES) {
            candidates.addObject().put("candidate_id", candidate.id()).put("variant", candidate.variant())
                    .put("audit_only", candidate.auditOnly());
            reservedIds.add(candidate.id());
        }
        inventory.putNull("evaluated_candidate_ids").putNull("effective_candidate_ids")
                .put("cumulative_k_status", "UNKNOWN_UNTIL_PRIOR_FAMILY_EXPOSURE_AND_ACTUAL_EVALUATION_ARE_REOPENED");
        inventory.put("content_sha256", JsonHashes.ownHash(inventory));
        return inventory;
    }

    /** Validates the replay, binds frozen inputs, and computes development-only paired statistics/gates. */
    public static ObjectNode build(ObjectNode replay, FreezeInputs frozen) {
        Objects.requireNonNull(frozen, "frozen");
        // JCS can represent an in-memory DecimalNode as a binary64 JSON number on disk. Evidence
        // arithmetic must use the same canonical replay values whether the caller is the runner
        // or a public evaluator reopening its persisted result.
        replay = canonicalRoundTrip(replay, "core replay");
        validateFrozenInputs(frozen);
        validateReplayEnvelope(replay, frozen);
        List<Opportunity> opportunities = parseOpportunities(replay.path("opportunities"));
        List<EvaluatedCandidate> evaluatedCandidates = parseEvaluatedCandidates(
                replay.path("evaluated_candidates"), frozen.candidateInventory(), opportunities,
                frozen.manifest().path("source_mode").asText());
        validateDecisionWindow(frozen.profile(), opportunities);
        validatePairedInventory(opportunities);

        List<LiquidationChronologicalPolicyV1.Observation> observations = new ArrayList<>();
        for (Opportunity opportunity : opportunities) {
            if (TRADED_VARIANTS.contains(opportunity.variant())) observations.add(policyObservation(opportunity));
        }
        ObjectNode profile = frozen.profile();
        LiquidationChronologicalPolicyV1.Inventory foldInventory =
                LiquidationChronologicalPolicyV1.buildInventory(profile, observations);
        List<LiquidationV2EvidenceMathV1.MarketInterval> intervals = opportunities.stream()
                .filter(row -> TRADED_VARIANTS.contains(row.variant()))
                .map(row -> new LiquidationV2EvidenceMathV1.MarketInterval(row.opportunityId(), row.asset(),
                        row.decisionTime(), row.firstFillTime(), row.exitTime())).toList();
        List<LiquidationChronologicalPolicyV1.MarketTimeBlock> blocks =
                LiquidationV2EvidenceMathV1.marketTimeBlocks(profile, intervals);
        LiquidationChronologicalPolicyV1.SynchronizedBlockSample blockSample =
                LiquidationChronologicalPolicyV1.sampleSynchronizedBlocks(profile, blocks);
        CoreStatistics coreStatistics = coreStatistics(opportunities, blocks, blockSample);
        int independentEpisodes = LiquidationV2EvidenceMathV1.independentCompletedEpisodes(
                opportunities.stream().filter(row -> "ROUTED_REVERSAL_CONTINUATION".equals(row.variant()))
                        .map(row -> new LiquidationV2EvidenceMathV1.MarketInterval(row.opportunityId(), row.asset(),
                                row.decisionTime(), row.firstFillTime(), row.exitTime())).toList(), BLOCK_DAYS);
        int maximumPossibleEpisodes = maximumPossibleDependenceEpisodes(profile);
        int descriptiveEpisodes = descriptiveEpisodeClusters(opportunities);
        List<String> currentEvaluatedIds = evaluatedCandidateIds(evaluatedCandidates);
        Integer cumulativeK = cumulativeK(frozen, currentEvaluatedIds);
        ObjectNode acceptance = acceptanceGates(replay, opportunities, foldInventory, coreStatistics,
                independentEpisodes, frozen);
        ArrayNode blockers = blockers(replay, opportunities, coreStatistics, cumulativeK, acceptance, frozen);
        String status = gateStatus(frozen.manifest(), independentEpisodes, blockers);

        ObjectNode evidence = JsonHashes.mapper().createObjectNode()
                .put("schema", EVIDENCE_SCHEMA).put("version", 1)
                .put("status", status).put("evidence_tier", "DEVELOPMENT_ONLY")
                .put("strategy_family", FAMILY)
                .put("current_frozen_candidate_count", CANDIDATES.size())
                .put("current_evaluated_candidate_count", currentEvaluatedIds.size())
                .put("statistics_sample_scope", "POOLED_DEVELOPMENT_AND_EXPOSED_OUTER_WINDOWS;NOT_AN_UNTOUCHED_OOS_CLAIM")
                .put("paired_core_decision_count", pairedDecisionCount(opportunities))
                .put("diagnostic_audit_row_count", diagnosticCount(opportunities))
                .put("effective_independent_episode_count", independentEpisodes)
                .put("maximum_possible_67d_dependence_components_in_frozen_window", maximumPossibleEpisodes)
                .put("minimum_67d_episode_floor_feasible_in_frozen_window", maximumPossibleEpisodes >= MINIMUM_INDEPENDENT_EPISODES)
                .put("67d_episode_feasibility_scope", "MATHEMATICAL_UPPER_BOUND_FROM_FROZEN_DECISION_WINDOW;IGNORES_POSITION_OVERLAP_AND_COVERAGE_GAPS")
                .put("descriptive_72h_episode_cluster_count", descriptiveEpisodes)
                .put("independence_basis", "CONNECTED_COMPLETED_ROUTED_POSITIONS_MERGED_WHEN_DECISION_GAP_LT_67D_OR_ACTUAL_FILL_EXIT_INTERVALS_OVERLAP;72H_CLUSTERS_DESCRIPTIVE_ONLY")
                .put("minimum_effective_independent_episodes", MINIMUM_INDEPENDENT_EPISODES)
                .put("block_days", BLOCK_DAYS)
                .put("bootstrap_draw_count", LiquidationChronologicalPolicyV1.DEFAULT_DRAWS)
                .put("bootstrap_seed", LiquidationChronologicalPolicyV1.DEFAULT_SEED)
                .put("source_mode", frozen.manifest().path("source_mode").asText())
                .put("replay_result_sha256", replay.path("content_sha256").asText())
                .put("event_stream_sha256", replay.path("event_stream_sha256").asText())
                .put("event_count", replay.path("event_count").asLong())
                .put("account_curve_sha256", replay.path("account_curve_sha256").asText())
                .put("ledger_sha256", replay.path("ledger_sha256").asText())
                .put("metrics_emitted", true).put("p_values_emitted", true)
                .put("promotion_permitted", false).put("trade_authorization_permitted", false);
        evidence.putObject("overfit_selection_diagnostics")
                .put("selection_search_performed", false)
                .put("pbo_status", "NOT_APPLICABLE_FIXED_PREDECLARED_NO_SELECTION")
                .putNull("pbo_value")
                .put("pbo_reason", "FIXED_PREDECLARED_SEQUENTIAL_COMPARISONS_HAVE_NO_WINNER_SELECTION_MATRIX")
                .put("dsr_status", "UNAVAILABLE_NO_FROZEN_DSR_ESTIMATE")
                .putNull("dsr_value");
        if (cumulativeK == null) evidence.putNull("cumulative_effective_candidate_count");
        else evidence.put("cumulative_effective_candidate_count", cumulativeK);
        evidence.put("candidate_multiplicity_status", cumulativeK == null ? "BLOCKED_PRIOR_FAMILY_EXPOSURE_MISSING"
                : "BOUND_TO_PRIOR_FAMILY_EXPOSURE_AND_CURRENT_INVENTORY");
        evidence.set("current_evaluated_candidate_ids", strings(currentEvaluatedIds));
        evidence.set("evaluated_candidate_inventory", replay.path("evaluated_candidates").deepCopy());
        evidence.set("candidate_evaluation_scopes", strings(evaluatedCandidates.stream()
                .map(EvaluatedCandidate::executionScope).distinct().sorted().toList()));
        evidence.put("physical_provenance_scope", "SELF_HASHED_OBJECTS_VALIDATED;PUBLIC_EVALUATOR_MUST_REOPEN_PHYSICAL_AND_EXECUTOR_BINDINGS");
        evidence.put("public_evaluation_requires_physical_reopen", true);
        evidence.put("stress_provenance_scope", "RUNNER_RESULT_ROWS_BOUND_TO_BASE_LEDGER;PUBLIC_EVALUATOR_MUST_REOPEN_RUNNER_OUTPUTS");
        ObjectNode accountMetrics = evidence.putObject("account_path_metrics");
        JsonNode drawdownMetric = accountDrawdown(replay, frozen.precommit());
        JsonNode basePnlMetric = accountNetPnl(replay, frozen.precommit());
        if (drawdownMetric == null) accountMetrics.putNull("maximum_adverse_mark_drawdown_pct");
        else accountMetrics.set("maximum_adverse_mark_drawdown_pct", drawdownMetric.deepCopy());
        if (basePnlMetric == null) accountMetrics.putNull("base_net_pnl_usdt");
        else accountMetrics.set("base_net_pnl_usdt", basePnlMetric.deepCopy());
        evidence.set("freeze", replay.path("freeze").deepCopy());
        evidence.set("current_candidate_inventory", frozen.candidateInventory().deepCopy());
        if (frozen.priorFamilyInventory() == null) evidence.putNull("prior_family_inventory");
        else evidence.set("prior_family_inventory", frozen.priorFamilyInventory().deepCopy());
        evidence.set("parent_family_lineage", parentLineageEvidence(frozen));
        evidence.set("opportunity_disposition", dispositionRows(opportunities));
        evidence.set("chronological_inventory", foldInventory.toJson());
        evidence.set("outer_fold_metrics", outerFoldMetrics(foldInventory, opportunities));
        evidence.set("market_time_blocks", marketTimeBlockRows(blocks));
        evidence.set("synchronized_block_indices", blockSample.toJson());
        evidence.set("paired_core_statistics", coreStatistics.toJson());
        RobustTradeStatistics robustTradeStatistics = robustTradeStatistics(replay, opportunities);
        evidence.set("robust_trade_statistics", robustTradeStatistics.toJson());
        evidence.set("acceptance_gates", acceptance);
        evidence.set("advancement_blockers", blockers);
        evidence.put("content_sha256", JsonHashes.ownHash(evidence));
        return evidence;
    }

    /**
     * Performs only structural replay validation before a public deterministic reexecution.
     * Statistical summaries, bootstrap draws, and acceptance gates remain in {@link #build}.
     */
    static void validateReplayStructure(ObjectNode replay, FreezeInputs frozen) {
        Objects.requireNonNull(frozen, "frozen");
        ObjectNode normalized = canonicalRoundTrip(replay, "core replay");
        validateFrozenInputs(frozen);
        validateReplayEnvelope(normalized, frozen);
        List<Opportunity> opportunities = parseOpportunities(normalized.path("opportunities"));
        parseEvaluatedCandidates(normalized.path("evaluated_candidates"), frozen.candidateInventory(), opportunities,
                frozen.manifest().path("source_mode").asText());
        validateDecisionWindow(frozen.profile(), opportunities);
        validatePairedInventory(opportunities);
    }

    /** Reopens the replay and expected output by deterministic reconstruction. */
    public static boolean validate(ObjectNode replay, FreezeInputs frozen, JsonNode evidence) {
        if (evidence == null || !evidence.isObject()
                || !EVIDENCE_SCHEMA.equals(evidence.path("schema").asText())
                || evidence.path("version").asInt(-1) != 1
                || !JsonHashes.ownHash(evidence).equals(evidence.path("content_sha256").asText())) {
            throw failure("v002 replay evidence is missing, malformed, or hash-tampered");
        }
        ObjectNode expected = build(replay, frozen);
        if (!JsonHashes.canonicalSha256(expected).equals(JsonHashes.canonicalSha256(evidence))) {
            throw failure("v002 replay evidence differs from its reopened frozen inputs and opportunity inventory");
        }
        return true;
    }

    /**
     * Reopens every retained scenario-run artifact before stress evidence is evaluated.
     * The public replay evaluator supplies its frozen physical root; the pure {@link #build}
     * overload remains usable by deterministic calculation tests with in-memory fixtures.
     */
    static void validateScenarioRunArtifacts(ObjectNode replay, Path physicalRoot) {
        Objects.requireNonNull(replay, "replay");
        Objects.requireNonNull(physicalRoot, "physicalRoot");
        JsonNode evaluation = replay.path("stress_evaluation");
        if (evaluation.isMissingNode() || evaluation.isNull()) return;
        if (!evaluation.isObject() || !evaluation.path("scenario_results").isArray()) {
            throw failure("stress evaluation scenario inventory is malformed");
        }
        Path root = physicalRoot.toAbsolutePath().normalize();
        Path realRoot;
        try { realRoot = root.toRealPath(); }
        catch (IOException missingRoot) { throw failure("frozen physical root is unavailable for scenario artifact reopening"); }
        String policySha = evaluation.path("stress_policy_sha256").asText();
        for (JsonNode row : evaluation.path("scenario_results")) {
            JsonNode artifact = row.path("scenario_run_artifact");
            if (!validScenarioRunArtifact(artifact, row)) {
                throw failure("scenario run artifact reference is malformed or not hash-bound");
            }
            Path relative = Path.of(artifact.path("relative_path").asText());
            Path resolved = root.resolve(relative).normalize();
            if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
                throw failure("scenario run artifact is missing or escapes the frozen physical root");
            }
            try {
                Path realArtifact = resolved.toRealPath();
                if (!realArtifact.startsWith(realRoot)) {
                    throw failure("scenario run artifact resolves outside the frozen physical root");
                }
                byte[] bytes = Files.readAllBytes(resolved);
                if (!JsonHashes.sha256(bytes).equals(artifact.path("sha256").asText())) {
                    throw failure("scenario run artifact bytes differ from their retained SHA-256");
                }
                JsonNode run = JsonHashes.mapper().readTree(bytes);
                if (run == null || !INPUT_SCHEMA.equals(run.path("schema").asText())
                        || run.path("version").asInt(-1) != 1
                        || !JsonHashes.ownHash(run).equals(run.path("content_sha256").asText())
                        || !run.path("content_sha256").asText().equals(row.path("scenario_run_content_sha256").asText())
                        || !run.path("ledger_sha256").asText().equals(row.path("runner_ledger_sha256").asText())
                        || !run.path("event_stream_sha256").asText().equals(row.path("runner_event_stream_sha256").asText())
                        || !run.path("scenario_execution").path("scenario_id").asText().equals(row.path("scenario_id").asText())
                        || !run.path("scenario_execution").path("stress_policy_sha256").asText().equals(policySha)
                        || !run.path("scenario_execution").path("transform_count").isIntegralNumber()
                        || !row.path("transform_count").isIntegralNumber()
                        || run.path("scenario_execution").path("transform_count").asLong(-1)
                                != row.path("transform_count").asLong(-2)
                        || !run.path("scenario_execution").path("transform_chain_sha256").asText().equals(row.path("transform_chain_sha256").asText())) {
                    throw failure("reopened scenario run differs from its retained ledger, event, policy, or transform bindings");
                }
            } catch (IOException invalidJson) {
                throw failure("scenario run artifact is not valid replay-result JSON");
            }
        }
    }

    private static void validateFrozenInputs(FreezeInputs frozen) {
        ObjectNode profile = frozen.profile();
        LiquidationDailyStressProfileV1.validate(profile);
        ObjectNode precommit = frozen.precommit();
        verifySelfHash(precommit, "frozen precommit");
        if (!"strategy-precommit/1".equals(precommit.path("schema").asText())
                || !"liquidation-daily-stress-v002".equals(precommit.path("precommit_id").asText())
                || !precommit.path("content_sha256").asText().equals(profile.path("precommit_content_sha256").asText())
                || !FAMILY.equals(precommit.path("hypothesis_family").asText())) {
            throw failure("replay precommit is not the frozen liquidation v002 premise");
        }
        validateParentLineage(frozen);

        ObjectNode manifest = frozen.manifest();
        verifySelfHash(manifest, "physical manifest");
        String mode = manifest.path("source_mode").asText();
        if (!"liquidation-v2-physical-manifest/1".equals(manifest.path("schema").asText())
                || manifest.path("version").asInt(-1) != 1
                || !Set.of(SYNTHETIC_MODE, PROXY_MODE).contains(mode)
                || !profile.path("content_sha256").asText().equals(manifest.path("profile_sha256").asText())
                || !precommit.path("content_sha256").asText().equals(manifest.path("precommit_sha256").asText())
                || manifest.path("authoritative").asBoolean(true)
                || manifest.path("authoritative_evaluation_permitted").asBoolean(true)) {
            throw failure("physical manifest is not a bound v002 development manifest");
        }
        if ((SYNTHETIC_MODE.equals(mode) && !"DEVELOPMENT_SYNTHETIC".equals(manifest.path("status").asText()))
                || (PROXY_MODE.equals(mode) && !"DEVELOPMENT_PROXY_DISCLOSED".equals(manifest.path("status").asText()))) {
            throw failure("physical manifest status does not match its frozen source mode");
        }

        ObjectNode executor = frozen.executorIdentity();
        verifySelfHash(executor, "executor identity");
        if (!EXECUTOR_IDENTITY_SCHEMA.equals(executor.path("schema").asText())
                || executor.path("version").asInt(-1) != 1
                || !profile.path("executor_capability").asText().equals(executor.path("capability").asText())
                || !"REQUIRE_MACRO_CONFIRMATION".equals(executor.path("default_stage_add_macro_gate_policy").asText())) {
            throw failure("executor identity does not match the frozen v002 capability");
        }

        ObjectNode inventory = frozen.candidateInventory();
        verifySelfHash(inventory, "candidate inventory");
        ObjectNode expectedInventory = frozenCandidateInventory(profile);
        if (!JsonHashes.canonicalSha256(expectedInventory).equals(JsonHashes.canonicalSha256(inventory))) {
            throw failure("candidate inventory differs from the exact frozen four-variant inventory");
        }
        ObjectNode prior = frozen.priorFamilyInventory();
        if (prior != null) {
            verifySelfHash(prior, "prior family exposure inventory");
            String parentHash = precommit.path("parent_precommit").path("content_sha256").asText();
            if (!"liquidation-family-exposure-inventory/1".equals(prior.path("schema").asText())
                    || prior.path("version").asInt(-1) != 1
                    || !FAMILY.equals(prior.path("strategy_family").asText())
                    || !parentHash.equals(prior.path("precommit_sha256").asText())
                    || !prior.path("effective_candidate_ids").isArray()
                    || prior.path("effective_candidate_ids").isEmpty()) {
                throw failure("prior family exposure inventory is not bound to the frozen v001 predecessor");
            }
            Set<String> priorIds = new HashSet<>();
            for (JsonNode id : prior.path("effective_candidate_ids")) {
                if (!id.isTextual() || id.asText().isBlank() || !priorIds.add(id.asText())) {
                    throw failure("prior family exposure inventory has invalid or duplicate candidate identities");
                }
            }
            // Reused IDs are deduplicated by the cumulative union below; current exposure never replaces prior IDs.
        }
    }

    private static void validateParentLineage(FreezeInputs frozen) {
        ObjectNode parent = frozen.parentPrecommit();
        ObjectNode freezeManifest = frozen.parentFreezeManifest();
        String manifestBytes = frozen.parentFreezeManifestBytes();
        String feasibility = frozen.parentFeasibilityMarkdown();
        if (parent == null && freezeManifest == null && manifestBytes == null && feasibility == null) return;
        if (parent == null || freezeManifest == null || manifestBytes == null || feasibility == null) {
            throw failure("parent family lineage must reopen precommit, exact freeze-manifest bytes, and exact feasibility bytes together");
        }
        verifySelfHash(parent, "parent v001 precommit");
        ObjectNode currentPrecommit = frozen.precommit();
        if (!"liquidation-structure-v001".equals(parent.path("precommit_id").asText())
                || !FAMILY.equals(parent.path("hypothesis_family").asText())
                || !parent.path("content_sha256").asText().equals(currentPrecommit.path("parent_precommit").path("content_sha256").asText())) {
            throw failure("v002 precommit does not bind the reopened v001 family predecessor");
        }
        JsonNode reparsed;
        try { reparsed = JsonHashes.mapper().readTree(manifestBytes); }
        catch (java.io.IOException error) { throw failure("parent freeze manifest bytes are not valid JSON"); }
        if (!reparsed.isObject() || !JsonHashes.canonicalSha256(reparsed).equals(JsonHashes.canonicalSha256(freezeManifest))) {
            throw failure("reopened parent freeze manifest object does not match its exact bytes");
        }
        if (!"research-specification-freeze-manifest/1".equals(freezeManifest.path("schema").asText())
                || !parent.path("content_sha256").asText().equals(freezeManifest.path("precommit_content_sha256").asText())
                || !"SPECIFICATION_FROZEN_EXECUTION_BLOCKED".equals(freezeManifest.path("status").asText())) {
            throw failure("parent freeze manifest is not the frozen blocked v001 family record");
        }
        String expectedFeasibilityHash = null;
        for (JsonNode row : freezeManifest.path("files")) {
            if ("docs/research/liquidation-structure-v001/FEASIBILITY.md".equals(row.path("path").asText())) {
                expectedFeasibilityHash = row.path("byte_sha256").asText();
                break;
            }
        }
        if (expectedFeasibilityHash == null || !expectedFeasibilityHash.equals(JsonHashes.sha256(feasibility))) {
            throw failure("parent FEASIBILITY.md bytes do not match the frozen v001 manifest digest");
        }
    }

    private static ObjectNode parentLineageEvidence(FreezeInputs frozen) {
        ObjectNode row = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-parent-family-lineage/1")
                .put("reopened", frozen.parentPrecommit() != null && frozen.parentFreezeManifest() != null
                        && frozen.parentFreezeManifestBytes() != null && frozen.parentFeasibilityMarkdown() != null)
                .put("prior_exposure_inventory_reopened", frozen.priorFamilyInventory() != null)
                .put("cumulative_k_status", frozen.priorFamilyInventory() == null ? "UNKNOWN" : "BOUND_FROM_EXPOSURE_INVENTORY");
        if (frozen.parentPrecommit() != null) row.put("parent_precommit_sha256", frozen.parentPrecommit().path("content_sha256").asText());
        else row.putNull("parent_precommit_sha256");
        if (frozen.parentFreezeManifest() != null) {
            row.put("parent_freeze_manifest_canonical_sha256", JsonHashes.canonicalSha256(frozen.parentFreezeManifest()));
        } else row.putNull("parent_freeze_manifest_canonical_sha256");
        String manifestByteHash = parentFreezeManifestByteHash(frozen);
        if (manifestByteHash == null) row.putNull("parent_freeze_manifest_byte_sha256");
        else row.put("parent_freeze_manifest_byte_sha256", manifestByteHash);
        String feasibilityHash = parentFeasibilityByteHash(frozen);
        if (feasibilityHash == null) row.putNull("parent_feasibility_byte_sha256");
        else row.put("parent_feasibility_byte_sha256", feasibilityHash);
        row.put("parent_manifest_status", frozen.parentFreezeManifest() == null ? "MISSING"
                : frozen.parentFreezeManifest().path("status").asText());
        if (frozen.priorFamilyInventory() == null) row.putNull("parent_family_prior_effective_k");
        else row.put("parent_family_prior_effective_k", frozen.priorFamilyInventory().path("effective_candidate_ids").size());
        row.put("content_sha256", JsonHashes.ownHash(row));
        return row;
    }

    private static String parentFreezeManifestByteHash(FreezeInputs frozen) {
        return frozen.parentFreezeManifestBytes() == null ? null : JsonHashes.sha256(frozen.parentFreezeManifestBytes());
    }

    private static String parentFeasibilityByteHash(FreezeInputs frozen) {
        return frozen.parentFeasibilityMarkdown() == null ? null : JsonHashes.sha256(frozen.parentFeasibilityMarkdown());
    }

    private static void checkOptionalRef(ObjectNode refs, String name, String expected) {
        JsonNode value = refs.get(name);
        if (expected == null) {
            if (value != null && !value.isNull()) throw failure("replay contains an un-reopened parent lineage reference: " + name);
        } else if (value == null || !value.isTextual() || !expected.equals(value.asText())) {
            throw failure("replay parent lineage reference does not match reopened bytes: " + name);
        }
    }

    private static void validateReplayEnvelope(ObjectNode replay, FreezeInputs frozen) {
        if (replay == null || !INPUT_SCHEMA.equals(replay.path("schema").asText())
                || replay.path("version").asInt(-1) != 1
                || !JsonHashes.ownHash(replay).equals(replay.path("content_sha256").asText())) {
            throw failure("v002 replay result is missing, unsupported, or hash-tampered");
        }
        ObjectNode refs = object(replay, "freeze");
        ObjectNode manifest = frozen.manifest(), precommit = frozen.precommit(), profile = frozen.profile();
        ObjectNode executor = frozen.executorIdentity(), inventory = frozen.candidateInventory();
        if (!manifest.path("content_sha256").asText().equals(requiredText(refs, "manifest_sha256"))
                || !precommit.path("content_sha256").asText().equals(requiredText(refs, "precommit_sha256"))
                || !profile.path("content_sha256").asText().equals(requiredText(refs, "profile_sha256"))
                || !executor.path("content_sha256").asText().equals(requiredText(refs, "executor_sha256"))
                || !inventory.path("content_sha256").asText().equals(requiredText(refs, "candidate_inventory_sha256"))
                || !manifest.path("source_mode").asText().equals(requiredText(refs, "source_mode"))) {
            throw failure("replay freeze references do not match the reopened frozen inputs");
        }
        requireHash(replay.path("event_stream_sha256").asText(), "event_stream_sha256");
        if (!replay.path("event_count").isIntegralNumber() || !replay.path("event_count").canConvertToLong()
                || replay.path("event_count").asLong() < 0) {
            throw failure("event count must be a nonnegative integer");
        }
        JsonNode curve = replay.path("account_curve");
        if (!curve.isArray() || !JsonHashes.canonicalSha256(curve)
                .equals(requiredText(replay, "account_curve_sha256"))) {
            throw failure("account curve is missing or its canonical hash does not match");
        }
        validateAccountCurve((ArrayNode) curve);
        requireHash(requiredText(replay, "ledger_sha256"), "ledger_sha256");
        if (!replay.path("opportunities").isArray()) throw failure("opportunities must be an array");
        JsonNode priorRef = refs.get("prior_family_inventory_sha256");
        if (frozen.priorFamilyInventory() == null) {
            if (priorRef != null && !priorRef.isNull()) {
                throw failure("replay references a prior family inventory that was not reopened by the helper");
            }
        } else if (priorRef == null || !frozen.priorFamilyInventory().path("content_sha256").asText().equals(priorRef.asText())) {
            throw failure("replay prior family inventory reference does not match the reopened artifact");
        }
        checkOptionalRef(refs, "parent_precommit_sha256", frozen.parentPrecommit() == null ? null
                : frozen.parentPrecommit().path("content_sha256").asText());
        checkOptionalRef(refs, "parent_freeze_manifest_byte_sha256", parentFreezeManifestByteHash(frozen));
        checkOptionalRef(refs, "parent_feasibility_byte_sha256", parentFeasibilityByteHash(frozen));
    }

    private static List<Opportunity> parseOpportunities(JsonNode rows) {
        ArrayList<Opportunity> result = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        Map<String, String> candidateVariants = candidateVariantMap();
        for (JsonNode node : rows) {
            if (!node.isObject()) throw failure("opportunity rows must be objects");
            ObjectNode row = (ObjectNode) node;
            String id = requiredText(row, "opportunity_id");
            String pairId = requiredText(row, "pair_id");
            String candidateId = requiredText(row, "candidate_id");
            String variant = requiredText(row, "variant");
            if (!identities.add(id)) throw failure("duplicate opportunity identity: " + id);
            String expectedVariant = candidateVariants.get(candidateId);
            if (expectedVariant == null || !expectedVariant.equals(variant)) {
                throw failure("unknown or mismatched frozen candidate variant: " + candidateId + "/" + variant);
            }
            String asset = requiredText(row, "asset");
            if (!ASSETS.contains(asset)) throw failure("opportunity asset is outside the frozen crypto universe: " + asset);
            Instant decision = instant(row, "decision_time");
            OutcomeState state = outcomeState(requiredText(row, "outcome_state"));
            Instant available = optionalInstant(row, "outcome_available_time");
            Instant firstFill = optionalInstant(row, "first_fill_time");
            Instant exit = optionalInstant(row, "exit_time");
            Double pnl = optionalNumber(row, "net_pnl_usdt");
            Double risk = optionalNumber(row, "reference_risk_usdt");
            if (!row.has("net_pnl_usdt") || !row.has("reference_risk_usdt")) {
                throw failure("opportunity rows must explicitly carry net_pnl_usdt and reference_risk_usdt, including nulls");
            }
            List<String> reasons = stringList(row.path("reason_codes"), "reason_codes");
            String branch = optionalText(row, "branch");
            String direction = optionalText(row, "direction");
            Opportunity opportunity = new Opportunity(id, pairId, candidateId, variant, asset, decision,
                    state, available, firstFill, exit, pnl, risk, reasons, branch, direction);
            validateOutcome(opportunity);
            result.add(opportunity);
        }
        result.sort(Comparator.comparing(Opportunity::opportunityId));
        return List.copyOf(result);
    }

    private static void validateOutcome(Opportunity row) {
        switch (row.outcomeState()) {
            case CLOSED_TRADE -> {
                if (row.outcomeAvailableTime() == null || row.firstFillTime() == null || row.exitTime() == null
                        || !row.firstFillTime().isAfter(row.decisionTime()) || row.exitTime().isBefore(row.firstFillTime())
                        || !row.outcomeAvailableTime().equals(row.exitTime()) || row.netPnlUsdt() == null
                        || row.referenceRiskUsdt() == null || row.referenceRiskUsdt() <= 0.0) {
                    throw failure("closed trade must resolve at exit and carry valid fill, PnL, and reference risk: " + row.opportunityId());
                }
            }
            case RESOLVED_NO_TRADE -> {
                if (row.outcomeAvailableTime() == null || row.outcomeAvailableTime().isBefore(row.decisionTime())
                        || row.firstFillTime() != null || row.exitTime() != null || row.netPnlUsdt() == null
                        || row.netPnlUsdt() != 0.0 || !zeroOrNull(row.referenceRiskUsdt())) {
                    throw failure("resolved no-trade requires an explicit resolution time and zero economic row: " + row.opportunityId());
                }
            }
            case OPEN_UNRESOLVED -> {
                if (row.outcomeAvailableTime() != null || row.firstFillTime() == null || row.exitTime() != null
                        || !row.firstFillTime().isAfter(row.decisionTime()) || row.netPnlUsdt() != null
                        || row.referenceRiskUsdt() == null || row.referenceRiskUsdt() <= 0.0) {
                    throw failure("open unresolved trade has invalid lifecycle or risk fields: " + row.opportunityId());
                }
            }
            case UNRESOLVED_NO_FILL -> {
                if (row.outcomeAvailableTime() != null || row.firstFillTime() != null || row.exitTime() != null
                        || row.netPnlUsdt() != null || !zeroOrNull(row.referenceRiskUsdt())) {
                    throw failure("unresolved no-fill row cannot carry trade outcome fields: " + row.opportunityId());
                }
            }
            case COVERAGE_BLOCKED -> {
                if (row.outcomeAvailableTime() != null || row.exitTime() != null || row.netPnlUsdt() != null
                        || (row.firstFillTime() != null && (!row.firstFillTime().isAfter(row.decisionTime())
                                || row.referenceRiskUsdt() == null || row.referenceRiskUsdt() <= 0.0))
                        || (row.firstFillTime() == null && !zeroOrNull(row.referenceRiskUsdt()))
                        || row.reasonCodes().isEmpty()) {
                    throw failure("coverage-blocked row must retain its reason and unresolved lifecycle: " + row.opportunityId());
                }
            }
        }
        if (candidateVariantMap().get(row.candidateId()).equals("PRICE_OI_ONLY_EVENT_DIAGNOSTIC")
                && (row.outcomeState() == OutcomeState.CLOSED_TRADE || row.outcomeState() == OutcomeState.OPEN_UNRESOLVED
                        || row.firstFillTime() != null || row.exitTime() != null)) {
            throw failure("price-plus-OI diagnostic is audit-only and cannot carry a traded or zero-PnL arm");
        }
    }

    private static void validatePairedInventory(List<Opportunity> opportunities) {
        TreeMap<String, List<Opportunity>> byPair = new TreeMap<>();
        for (Opportunity row : opportunities) {
            if (TRADED_VARIANTS.contains(row.variant())) {
                byPair.computeIfAbsent(row.pairId(), ignored -> new ArrayList<>()).add(row);
            }
        }
        for (Map.Entry<String, List<Opportunity>> entry : byPair.entrySet()) {
            List<Opportunity> rows = entry.getValue();
            if (rows.size() != TRADED_VARIANTS.size()) throw failure("paired decision does not contain exactly three traded variants: " + entry.getKey());
            Set<String> variants = new HashSet<>();
            Opportunity first = rows.get(0);
            for (Opportunity row : rows) {
                if (!variants.add(row.variant()) || !first.asset().equals(row.asset())
                        || !first.decisionTime().equals(row.decisionTime())) {
                    throw failure("paired variants must be unique and share pair, asset, and decision time: " + entry.getKey());
                }
            }
            if (!variants.equals(TRADED_VARIANTS)) throw failure("paired decision is missing a frozen traded variant: " + entry.getKey());
        }
    }

    private static LiquidationChronologicalPolicyV1.Observation policyObservation(Opportunity row) {
        return switch (row.outcomeState()) {
            case CLOSED_TRADE -> LiquidationChronologicalPolicyV1.Observation.completed(
                    row.opportunityId(), row.decisionTime(), row.firstFillTime(), row.exitTime());
            case RESOLVED_NO_TRADE -> LiquidationChronologicalPolicyV1.Observation.resolvedNoTrade(
                    row.opportunityId(), row.decisionTime(), row.outcomeAvailableTime());
            case OPEN_UNRESOLVED -> LiquidationChronologicalPolicyV1.Observation.openTrade(
                    row.opportunityId(), row.decisionTime(), row.firstFillTime());
            case UNRESOLVED_NO_FILL, COVERAGE_BLOCKED -> row.firstFillTime() == null
                    ? LiquidationChronologicalPolicyV1.Observation.unresolved(row.opportunityId(), row.decisionTime())
                    : LiquidationChronologicalPolicyV1.Observation.openTrade(
                            row.opportunityId(), row.decisionTime(), row.firstFillTime());
        };
    }

    private static void validateDecisionWindow(ObjectNode profile, List<Opportunity> opportunities) {
        Instant start = Instant.parse(profile.path("windows").path("decision_start").asText());
        Instant end = Instant.parse(profile.path("windows").path("decision_end_exclusive").asText());
        for (Opportunity row : opportunities) if (row.decisionTime().isBefore(start) || !row.decisionTime().isBefore(end)) {
            throw failure("opportunity decision time is outside the frozen decision window: " + row.opportunityId());
        }
    }

    private static int maximumPossibleDependenceEpisodes(ObjectNode profile) {
        Instant start = Instant.parse(profile.path("windows").path("decision_start").asText());
        Instant end = Instant.parse(profile.path("windows").path("decision_end_exclusive").asText());
        Duration window = Duration.between(start, end);
        if (window.isNegative() || window.isZero()) throw failure("frozen decision window is not positive");
        return Math.toIntExact(window.minusNanos(1).dividedBy(Duration.ofDays(BLOCK_DAYS)) + 1);
    }

    private static int descriptiveEpisodeClusters(List<Opportunity> opportunities) {
        List<Instant> times = opportunities.stream().filter(row -> "ROUTED_REVERSAL_CONTINUATION".equals(row.variant()))
                .map(Opportunity::decisionTime).distinct().sorted().toList();
        int count = 0;
        Instant previous = null;
        for (Instant time : times) {
            if (previous == null || Duration.between(previous, time).compareTo(DESCRIPTIVE_CLUSTER_WINDOW) > 0) count++;
            previous = time;
        }
        return count;
    }

    private static CoreStatistics coreStatistics(List<Opportunity> opportunities,
            List<LiquidationChronologicalPolicyV1.MarketTimeBlock> blocks,
            LiquidationChronologicalPolicyV1.SynchronizedBlockSample sample) {
        Map<String, String> blockByObservation = blockByObservation(blocks);
        TreeMap<String, List<Opportunity>> byPair = new TreeMap<>();
        for (Opportunity row : opportunities) if (TRADED_VARIANTS.contains(row.variant())) {
            byPair.computeIfAbsent(row.pairId(), ignored -> new ArrayList<>()).add(row);
        }
        ArrayList<CorePair> complete = new ArrayList<>();
        for (Map.Entry<String, List<Opportunity>> entry : byPair.entrySet()) {
            Map<String, Opportunity> arms = new HashMap<>();
            for (Opportunity row : entry.getValue()) arms.put(row.variant(), row);
            if (arms.size() != 3 || arms.values().stream().anyMatch(row -> !resolvedForMetric(row))) continue;
            Opportunity routed = arms.get("ROUTED_REVERSAL_CONTINUATION");
            complete.add(new CorePair(entry.getKey(), routed.opportunityId(), blockByObservation.get(routed.opportunityId()),
                    expectancyR(routed), expectancyR(arms.get("ALWAYS_CONTINUATION_CONTROL")),
                    expectancyR(arms.get("ALWAYS_REVERSAL_CONTROL"))));
        }
        complete.sort(Comparator.comparing(CorePair::pairId));
        if (complete.isEmpty()) return CoreStatistics.empty(byPair.size());

        double routedMean = LiquidationV2EvidenceMathV1.mean(complete.stream().map(CorePair::routedR).toList());
        double continueMean = LiquidationV2EvidenceMathV1.mean(complete.stream().map(CorePair::continuationR).toList());
        double reverseMean = LiquidationV2EvidenceMathV1.mean(complete.stream().map(CorePair::reversalR).toList());
        double continuationDelta = routedMean - continueMean;
        double reversalDelta = routedMean - reverseMean;
        Map<String, Double> routedVector = new HashMap<>(), continuationDeltaVector = new HashMap<>(),
                reversalDeltaVector = new HashMap<>();
        for (CorePair pair : complete) {
            routedVector.put(pair.observationId(), pair.routedR());
            continuationDeltaVector.put(pair.observationId(), pair.routedR() - pair.continuationR());
            reversalDeltaVector.put(pair.observationId(), pair.routedR() - pair.reversalR());
        }
        List<Double> routedDraws = LiquidationV2EvidenceMathV1.synchronizedBlockMeans(sample, blocks, routedVector);
        List<Double> continuationDeltaDraws = LiquidationV2EvidenceMathV1.synchronizedBlockMeans(
                sample, blocks, continuationDeltaVector);
        List<Double> reversalDeltaDraws = LiquidationV2EvidenceMathV1.synchronizedBlockMeans(
                sample, blocks, reversalDeltaVector);
        Map<String, Double> centeredContinuation = new HashMap<>(), centeredReversal = new HashMap<>();
        continuationDeltaVector.forEach((id, value) -> centeredContinuation.put(id, value - continuationDelta));
        reversalDeltaVector.forEach((id, value) -> centeredReversal.put(id, value - reversalDelta));
        List<Double> centeredContinuationDraws = LiquidationV2EvidenceMathV1.synchronizedBlockMeans(
                sample, blocks, centeredContinuation);
        List<Double> centeredReversalDraws = LiquidationV2EvidenceMathV1.synchronizedBlockMeans(
                sample, blocks, centeredReversal);
        ArrayList<Double> nullMaxDraws = new ArrayList<>(sample.draws().size());
        for (int index = 0; index < sample.draws().size(); index++) {
            nullMaxDraws.add(Math.max(centeredContinuationDraws.get(index), centeredReversalDraws.get(index)));
        }
        boolean degenerateBothContrasts = LiquidationV2EvidenceMathV1.zeroVariance(complete.stream()
                .map(pair -> pair.routedR() - pair.continuationR()).toList())
                && LiquidationV2EvidenceMathV1.zeroVariance(complete.stream()
                        .map(pair -> pair.routedR() - pair.reversalR()).toList());
        return new CoreStatistics(byPair.size(), complete.size(), routedMean, continueMean, reverseMean,
                continuationDelta, reversalDelta, LiquidationV2EvidenceMathV1.percentile20(routedDraws),
                LiquidationV2EvidenceMathV1.percentile20(continuationDeltaDraws),
                LiquidationV2EvidenceMathV1.percentile20(reversalDeltaDraws),
                degenerateBothContrasts ? 1.0 : LiquidationV2EvidenceMathV1.adjustedPValue(nullMaxDraws, continuationDelta),
                degenerateBothContrasts ? 1.0 : LiquidationV2EvidenceMathV1.adjustedPValue(nullMaxDraws, reversalDelta),
                List.copyOf(complete));
    }

    /** Robust statistics over routed completed positions, with costs reopened from the bound account ledger. */
    private static RobustTradeStatistics robustTradeStatistics(ObjectNode replay, List<Opportunity> opportunities) {
        List<Opportunity> closed = opportunities.stream()
                .filter(row -> "ROUTED_REVERSAL_CONTINUATION".equals(row.variant()))
                .filter(row -> row.outcomeState() == OutcomeState.CLOSED_TRADE)
                .sorted(Comparator.comparing(Opportunity::exitTime).thenComparing(Opportunity::opportunityId)).toList();
        if (closed.isEmpty()) return new RobustTradeStatistics(0, null, null, List.of());
        ArrayList<Double> netR = new ArrayList<>(closed.size());
        for (Opportunity row : closed) {
            if (row.netPnlUsdt() == null || row.referenceRiskUsdt() == null || row.referenceRiskUsdt() <= 0.0) {
                return new RobustTradeStatistics(closed.size(), null, null, List.of());
            }
            double value = row.netPnlUsdt() / row.referenceRiskUsdt();
            if (!Double.isFinite(value)) return new RobustTradeStatistics(closed.size(), null, null, List.of());
            netR.add(value);
        }
        List<Double> costR = closedPositionCostR(replay, closed);
        Double meanCost = costR.size() == closed.size()
                ? LiquidationV2EvidenceMathV1.meanPositionCostR(costR) : null;
        return new RobustTradeStatistics(closed.size(),
                LiquidationV2EvidenceMathV1.cumulativeTradeDrawdownR(netR), meanCost, costR);
    }

    /** Cost R is trade-count weighted and charges positive funding debits only, never netting credits. */
    private static List<Double> closedPositionCostR(ObjectNode replay, List<Opportunity> closed) {
        JsonNode ledger = replay.path("ledger");
        if (!ledger.isObject() || !JsonHashes.ownHash(ledger).equals(ledger.path("content_sha256").asText())
                || !ledger.path("content_sha256").asText().equals(replay.path("ledger_sha256").asText())
                || !ledger.path("accounts").isArray()) return List.of();
        JsonNode routedAccount = null;
        for (JsonNode candidate : ledger.path("accounts")) if ("liquidation-v2-core-routed-one-entry".equals(candidate.path("candidate_id").asText())) {
            routedAccount = candidate.path("account"); break;
        }
        if (routedAccount == null || !routedAccount.path("closed_episodes").isArray()
                || !routedAccount.path("positions").isArray()) return List.of();
        Map<String, JsonNode> episodes = new HashMap<>();
        for (JsonNode episode : routedAccount.path("closed_episodes")) {
            putPositionEpisode(episodes, episode, true);
        }
        // The current account position retains the just-closed episode until a later setup
        // starts. Include it after archived episodes so exact identities deduplicate safely.
        for (JsonNode position : routedAccount.path("positions")) {
            if ("CLOSED".equals(position.path("status").asText()) && position.path("exits").isArray()
                    && !position.path("exits").isEmpty()) putPositionEpisode(episodes, position, false);
        }
        ArrayList<Double> result = new ArrayList<>(closed.size());
        for (Opportunity opportunity : closed) {
            JsonNode raw = findOpportunity(replay, opportunity.opportunityId());
            if (raw == null || !raw.path("first_fill_time").isTextual() || !raw.path("setup_id").isTextual()) return List.of();
            long fillTime;
            try { fillTime = Instant.parse(raw.path("first_fill_time").asText()).toEpochMilli(); }
            catch (RuntimeException invalid) { return List.of(); }
            JsonNode episode = episodes.get(raw.path("setup_id").asText() + "|" + opportunity.asset() + "|" + fillTime);
            if (episode == null) return List.of();
            if (opportunity.referenceRiskUsdt() == null || opportunity.referenceRiskUsdt() <= 0) return List.of();
            Double cost = LiquidationV2EvidenceMathV1.positionCostR(episode,
                    BigDecimal.valueOf(opportunity.referenceRiskUsdt()));
            if (cost == null || !Double.isFinite(cost)) return List.of();
            result.add(cost);
        }
        return List.copyOf(result);
    }

    private static void putPositionEpisode(Map<String, JsonNode> episodes, JsonNode episode, boolean archive) {
        JsonNode fill = episode.path("first_fill_time");
        String setup = episode.path("active_setup_id").asText();
        String asset = episode.path("asset").asText();
        if (fill.isIntegralNumber() && fill.canConvertToLong() && !setup.isBlank() && !asset.isBlank()) {
            String identity = setup + "|" + asset + "|" + fill.asLong();
            if (archive) episodes.put(identity, episode);
            else episodes.putIfAbsent(identity, episode);
        }
    }

    private static JsonNode findOpportunity(ObjectNode replay, String opportunityId) {
        for (JsonNode row : replay.path("opportunities")) if (opportunityId.equals(row.path("opportunity_id").asText())) return row;
        return null;
    }

    private static double frozenNumber(ObjectNode precommit, String dottedPath) {
        JsonNode cursor = precommit;
        for (String component : dottedPath.split("\\.")) cursor = cursor.path(component);
        if (!finiteNumber(cursor)) throw failure("frozen acceptance threshold is missing or nonfinite: " + dottedPath);
        return cursor.asDouble();
    }

    private record RobustTradeStatistics(int completedPositions, Double maximumDrawdownR,
            Double meanPositionCostR, List<Double> perPositionCostR) {
        private RobustTradeStatistics { perPositionCostR = List.copyOf(perPositionCostR); }
        private ObjectNode toJson() {
            ObjectNode row = JsonHashes.mapper().createObjectNode()
                    .put("schema", "liquidation-v2-core-robust-trade-statistics/1")
                    .put("completed_routed_positions", completedPositions)
                    .put("maximum_drawdown_basis", "SIGNED_DRAWDOWN_OF_CUMULATIVE_COMPLETED_TRADE_NET_R_SEQUENCE;ZERO_NO_TRADE_ROWS_OMITTED")
                    .put("cost_r_basis", "TRADE_COUNT_WEIGHTED_MEAN_OF_ENTRY_COSTS_PLUS_EXIT_COSTS_PLUS_POSITIVE_FUNDING_DEBITS_OVER_INITIAL_TRANCHE_REFERENCE_RISK")
                    .put("cost_rows_are_ledger_matched", meanPositionCostR != null);
            if (maximumDrawdownR == null) row.putNull("maximum_drawdown_r"); else row.put("maximum_drawdown_r", maximumDrawdownR);
            if (meanPositionCostR == null) row.putNull("mean_cost_r"); else row.put("mean_cost_r", meanPositionCostR);
            ArrayNode rows = row.putArray("per_position_cost_r");
            for (double value : perPositionCostR) rows.add(value);
            row.put("content_sha256", JsonHashes.ownHash(row));
            return row;
        }
    }

    private static boolean resolvedForMetric(Opportunity row) {
        return row.outcomeState() == OutcomeState.CLOSED_TRADE || row.outcomeState() == OutcomeState.RESOLVED_NO_TRADE;
    }

    private static double expectancyR(Opportunity row) {
        double value = row.outcomeState() == OutcomeState.RESOLVED_NO_TRADE ? 0.0
                : Objects.requireNonNull(row.netPnlUsdt()) / Objects.requireNonNull(row.referenceRiskUsdt());
        if (!Double.isFinite(value)) throw failure("net outcome in reference-risk units is not finite: " + row.opportunityId());
        return value;
    }

    private static Map<String, String> blockByObservation(
            List<LiquidationChronologicalPolicyV1.MarketTimeBlock> blocks) {
        Map<String, String> result = new HashMap<>();
        for (LiquidationChronologicalPolicyV1.MarketTimeBlock block : blocks) {
            for (String id : block.synchronizedObservationIds()) result.put(id, block.blockId());
        }
        return result;
    }

    private static ArrayNode marketTimeBlockRows(List<LiquidationChronologicalPolicyV1.MarketTimeBlock> blocks) {
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        for (LiquidationChronologicalPolicyV1.MarketTimeBlock block : blocks) {
            ObjectNode row = result.addObject().put("block_id", block.blockId())
                    .put("start_inclusive", block.startInclusive().toString())
                    .put("end_exclusive", block.endExclusive().toString())
                    .put("observation_count", block.synchronizedObservationIds().size());
            row.set("synchronized_observation_ids", strings(block.synchronizedObservationIds()));
        }
        return result;
    }

    private static Integer cumulativeK(FreezeInputs frozen, List<String> currentEvaluatedIds) {
        if (frozen.priorFamilyInventory() == null) return null;
        Set<String> union = new HashSet<>();
        frozen.priorFamilyInventory().path("effective_candidate_ids").forEach(id -> union.add(id.asText()));
        union.addAll(currentEvaluatedIds);
        return union.size();
    }

    private static List<EvaluatedCandidate> parseEvaluatedCandidates(JsonNode rows, ObjectNode inventory,
            List<Opportunity> opportunities, String sourceMode) {
        JsonNode frozenCandidates = inventory.path("current_candidates");
        if (!rows.isArray() || !frozenCandidates.isArray() || rows.size() != frozenCandidates.size()) {
            throw failure("replay must bind every frozen candidate as evaluated, including zero-opportunity arms");
        }
        String expectedScope = switch (sourceMode) {
            case SYNTHETIC_MODE -> "SYNTHETIC_FIXTURE_EXECUTED";
            case PROXY_MODE -> "PROXY_RETROSPECTIVE_DIAGNOSTIC_EXECUTED";
            default -> throw failure("replay has an unknown evaluated-candidate exposure scope");
        };
        Map<String, Long> opportunityCounts = new HashMap<>();
        for (Opportunity opportunity : opportunities) {
            opportunityCounts.merge(opportunity.candidateId(), 1L, Long::sum);
        }
        ArrayList<EvaluatedCandidate> result = new ArrayList<>(rows.size());
        Set<String> identities = new HashSet<>();
        for (int index = 0; index < frozenCandidates.size(); index++) {
            JsonNode expected = frozenCandidates.get(index), row = rows.get(index);
            if (!row.isObject() || row.size() != 6) {
                throw failure("evaluated candidate row must contain exactly the frozen identity and execution fields");
            }
            String candidateId = requiredText((ObjectNode) row, "candidate_id");
            String variant = requiredText((ObjectNode) row, "variant");
            String executionScope = requiredText((ObjectNode) row, "execution_scope");
            JsonNode auditOnlyNode = row.path("audit_only"), executedNode = row.path("executed");
            JsonNode countNode = row.path("opportunity_count");
            if (!identities.add(candidateId)
                    || !candidateId.equals(expected.path("candidate_id").asText())
                    || !variant.equals(expected.path("variant").asText())
                    || !auditOnlyNode.isBoolean()
                    || auditOnlyNode.asBoolean() != expected.path("audit_only").asBoolean()
                    || !executionScope.equals(expectedScope)
                    || !executedNode.isBoolean() || !executedNode.asBoolean()
                    || !countNode.isIntegralNumber() || !countNode.canConvertToLong() || countNode.asLong() < 0
                    || countNode.asLong() != opportunityCounts.getOrDefault(candidateId, 0L)) {
                throw failure("replay evaluated-candidate exposure differs from the exact frozen inventory or opportunity rows");
            }
            result.add(new EvaluatedCandidate(candidateId, variant, auditOnlyNode.asBoolean(), executionScope,
                    countNode.asLong()));
        }
        return List.copyOf(result);
    }

    private static List<String> evaluatedCandidateIds(List<EvaluatedCandidate> candidates) {
        return candidates.stream().map(EvaluatedCandidate::candidateId).distinct().sorted().toList();
    }

    private static ObjectNode acceptanceGates(ObjectNode replay, List<Opportunity> opportunities,
            LiquidationChronologicalPolicyV1.Inventory inventory, CoreStatistics statistics,
            int independentEpisodes, FreezeInputs frozen) {
        ObjectNode gates = JsonHashes.mapper().createObjectNode();
        gates.put("minimum_67d_dependence_aware_episodes", independentEpisodes >= MINIMUM_INDEPENDENT_EPISODES);
        gates.put("minimum_portfolio_routed_positions", routedClosedTradeCount(opportunities) >= 60);
        Integer positiveFolds = positiveOuterFolds(inventory, opportunities);
        if (positiveFolds == null) gates.putNull("minimum_positive_outer_folds");
        else gates.put("minimum_positive_outer_folds", positiveFolds >= 5);
        if (statistics.completePairCount() == 0) {
            gates.putNull("routed_net_expectancy_p20_positive");
            gates.putNull("incremental_p20_positive_vs_continuation");
            gates.putNull("incremental_p20_positive_vs_reversal");
            gates.putNull("centered_max_statistic_adjusted_p_le_0_05_vs_continuation");
            gates.putNull("centered_max_statistic_adjusted_p_le_0_05_vs_reversal");
        } else {
            gates.put("routed_net_expectancy_p20_positive", statistics.netP20R() > 0.0);
            gates.put("incremental_p20_positive_vs_continuation", statistics.continuationP20R() > 0.0);
            gates.put("incremental_p20_positive_vs_reversal", statistics.reversalP20R() > 0.0);
            gates.put("centered_max_statistic_adjusted_p_le_0_05_vs_continuation", statistics.continuationAdjustedP() <= .05);
            gates.put("centered_max_statistic_adjusted_p_le_0_05_vs_reversal", statistics.reversalAdjustedP() <= .05);
        }
        JsonNode drawdown = accountDrawdown(replay, frozen.precommit());
        if (drawdown == null) gates.putNull("marked_equity_drawdown_at_most_30_percent");
        else gates.put("marked_equity_drawdown_at_most_30_percent", drawdown.asDouble() <= 30.0);
        JsonNode basePnl = accountNetPnl(replay, frozen.precommit());
        if (basePnl == null) gates.putNull("base_net_pnl_positive");
        else gates.put("base_net_pnl_positive", basePnl.asDouble() > 0.0);
        RobustTradeStatistics robustTradeStats = robustTradeStatistics(replay, opportunities);
        double maximumDrawdownLimit = frozenNumber(frozen.precommit(), "experiment.acceptance.robust_stats.maximum_drawdown_r");
        double maximumCostLimit = frozenNumber(frozen.precommit(), "experiment.acceptance.robust_stats.maximum_cost_r");
        if (robustTradeStats.maximumDrawdownR() == null) gates.putNull("maximum_drawdown_r");
        else gates.put("maximum_drawdown_r", Math.abs(robustTradeStats.maximumDrawdownR()) <= maximumDrawdownLimit);
        if (robustTradeStats.meanPositionCostR() == null) gates.putNull("maximum_cost_r");
        else gates.put("maximum_cost_r", robustTradeStats.meanPositionCostR() <= maximumCostLimit);
        ObjectNode stress = stressGates(replay, frozen.precommit());
        gates.set("five_frozen_stress_gates", stress);
        boolean stressPresent = REQUIRED_STRESSES.stream().allMatch(id -> stress.path(id).isBoolean());
        gates.put("all_five_stresses_pass", stressPresent && REQUIRED_STRESSES.stream().allMatch(id -> stress.path(id).asBoolean()));
        Boolean branchPass = branchCountsPass(opportunities);
        if (branchPass == null) gates.putNull("branch_counts_at_least_20_each");
        else gates.put("branch_counts_at_least_20_each", branchPass);
        gates.put("cumulative_family_exposure_reopened", frozen.priorFamilyInventory() != null);
        gates.put("cumulative_familywise_max_statistic_available", false);
        gates.put("outcomes_complete_for_advancement", completePairCount(opportunities) == pairedDecisionCount(opportunities));
        gates.put("public_physical_reopen_required", true);
        return gates;
    }

    private static ArrayNode blockers(ObjectNode replay, List<Opportunity> opportunities, CoreStatistics statistics,
            Integer cumulativeK, ObjectNode acceptance, FreezeInputs frozen) {
        ArrayNode rows = JsonHashes.mapper().createArrayNode();
        if (cumulativeK == null) rows.add("PRIOR_FAMILY_EXPOSURE_INVENTORY_NOT_REOPENED_CUMULATIVE_K_UNKNOWN");
        rows.add("CUMULATIVE_FAMILY_MAXSTAT_PRIOR_CANDIDATE_RETURN_VECTORS_NOT_AVAILABLE");
        int maximumPossibleEpisodes = maximumPossibleDependenceEpisodes(frozen.profile());
        if (maximumPossibleEpisodes < MINIMUM_INDEPENDENT_EPISODES) {
            rows.add("FROZEN_WINDOW_CANNOT_SUPPORT_MINIMUM_67D_EPISODE_FLOOR_MAX_POSSIBLE_" + maximumPossibleEpisodes);
        }
        if (frozen.parentPrecommit() == null || frozen.parentFreezeManifest() == null
                || frozen.parentFreezeManifestBytes() == null || frozen.parentFeasibilityMarkdown() == null) {
            rows.add("PARENT_V001_PRECOMMIT_FREEZE_MANIFEST_AND_FEASIBILITY_NOT_REOPENED");
        }
        if (statistics.incompletePairCount() > 0) rows.add("OPEN_UNRESOLVED_OR_COVERAGE_BLOCKED_PAIRED_OUTCOMES_BLOCK_ADVANCEMENT");
        if (branchCountsPass(opportunities) == null) rows.add("BRANCH_METADATA_MISSING_FOR_20_PER_BRANCH_GATE");
        if (!hasStressEvidence(replay, frozen.precommit())) rows.add("FIVE_FROZEN_STRESS_SCENARIOS_NOT_VALIDATED");
        if (accountDrawdown(replay, frozen.precommit()) == null) rows.add("INTRATRADE_MARKED_EQUITY_PATH_UNVERIFIED");
        if (!Boolean.TRUE.equals(acceptance.path("minimum_positive_outer_folds").isBoolean()
                ? acceptance.path("minimum_positive_outer_folds").asBoolean() : null)) {
            if (acceptance.path("minimum_positive_outer_folds").isMissingNode()
                    || acceptance.path("minimum_positive_outer_folds").isNull()) rows.add("OUTER_FOLD_PNL_EVIDENCE_INCOMPLETE");
        }
        if (PROXY_MODE.equals(frozen.manifest().path("source_mode").asText())
                && !frozen.manifest().path("development_replay_permitted").asBoolean(false)) {
            rows.add("PROXY_MANIFEST_DOES_NOT_PERMIT_DEVELOPMENT_REPLAY");
        }
        Map<String, String> requiredGates = new LinkedHashMap<>();
        requiredGates.put("minimum_67d_dependence_aware_episodes", "MINIMUM_67D_DEPENDENCE_AWARE_EPISODE_GATE");
        requiredGates.put("minimum_portfolio_routed_positions", "MINIMUM_ROUTED_POSITION_GATE");
        requiredGates.put("minimum_positive_outer_folds", "MINIMUM_POSITIVE_OUTER_FOLD_GATE");
        requiredGates.put("routed_net_expectancy_p20_positive", "ROUTED_NET_EXPECTANCY_P20_GATE");
        requiredGates.put("incremental_p20_positive_vs_continuation", "INCREMENTAL_P20_VS_CONTINUATION_GATE");
        requiredGates.put("incremental_p20_positive_vs_reversal", "INCREMENTAL_P20_VS_REVERSAL_GATE");
        requiredGates.put("centered_max_statistic_adjusted_p_le_0_05_vs_continuation", "ADJUSTED_P_VS_CONTINUATION_GATE");
        requiredGates.put("centered_max_statistic_adjusted_p_le_0_05_vs_reversal", "ADJUSTED_P_VS_REVERSAL_GATE");
        requiredGates.put("marked_equity_drawdown_at_most_30_percent", "ADVERSE_MARK_DRAWDOWN_GATE");
        requiredGates.put("base_net_pnl_positive", "BASE_NET_PNL_GATE");
        requiredGates.put("maximum_drawdown_r", "MAXIMUM_TRADE_EQUITY_DRAWDOWN_R_GATE");
        requiredGates.put("maximum_cost_r", "MEAN_POSITION_COST_R_GATE");
        requiredGates.put("all_five_stresses_pass", "FROZEN_STRESS_SUITE_GATE");
        requiredGates.put("branch_counts_at_least_20_each", "ROUTED_BRANCH_SAMPLE_GATE");
        for (Map.Entry<String, String> gate : requiredGates.entrySet()) {
            JsonNode value = acceptance.path(gate.getKey());
            if (value.isBoolean() && !value.asBoolean()) rows.add("FROZEN_ACCEPTANCE_GATE_FAILED_" + gate.getValue());
            else if (!value.isBoolean()) rows.add("FROZEN_ACCEPTANCE_GATE_UNAVAILABLE_" + gate.getValue());
        }
        return rows;
    }

    private static String gateStatus(ObjectNode manifest, int independentEpisodes, ArrayNode blockers) {
        if (!blockers.isEmpty()) return "BLOCKED";
        if (PROXY_MODE.equals(manifest.path("source_mode").asText())
                && !manifest.path("development_replay_permitted").asBoolean(false)) return "BLOCKED";
        if (independentEpisodes < MINIMUM_INDEPENDENT_EPISODES) return "INSUFFICIENT_EVIDENCE";
        return "DEVELOPMENT";
    }

    private static Integer positiveOuterFolds(LiquidationChronologicalPolicyV1.Inventory inventory,
            List<Opportunity> opportunities) {
        Map<String, Opportunity> byId = new HashMap<>();
        opportunities.forEach(row -> byId.put(row.opportunityId(), row));
        int positive = 0;
        for (LiquidationChronologicalPolicyV1.Fold fold : inventory.folds()) {
            double pnl = 0.0;
            for (String id : fold.testIds()) {
                Opportunity row = byId.get(id);
                if (row != null && "ROUTED_REVERSAL_CONTINUATION".equals(row.variant())
                        && row.outcomeState() == OutcomeState.CLOSED_TRADE) pnl += row.netPnlUsdt();
            }
            if (pnl > 0.0) positive++;
        }
        return positive;
    }

    private static ArrayNode outerFoldMetrics(LiquidationChronologicalPolicyV1.Inventory inventory,
            List<Opportunity> opportunities) {
        Map<String, Opportunity> byId = new HashMap<>();
        opportunities.forEach(row -> byId.put(row.opportunityId(), row));
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        for (LiquidationChronologicalPolicyV1.Fold fold : inventory.folds()) {
            int decisions = 0, completed = 0;
            double pnl = 0.0;
            for (String id : fold.testIds()) {
                Opportunity row = byId.get(id);
                if (row == null || !"ROUTED_REVERSAL_CONTINUATION".equals(row.variant())) continue;
                decisions++;
                if (row.outcomeState() == OutcomeState.CLOSED_TRADE) {
                    completed++;
                    pnl += row.netPnlUsdt();
                }
            }
            result.addObject().put("fold_id", fold.foldId()).put("test_decision_count", decisions)
                    .put("routed_completed_positions", completed).put("routed_net_pnl_usdt", pnl)
                    .put("positive", pnl > 0.0).put("status", fold.testIds().isEmpty() ? "NO_RETAINED_TEST_OUTCOMES" : "RETAINED_TEST_OUTCOMES");
        }
        return result;
    }

    private static int routedClosedTradeCount(List<Opportunity> opportunities) {
        return (int) opportunities.stream().filter(row -> "ROUTED_REVERSAL_CONTINUATION".equals(row.variant()))
                .filter(row -> row.outcomeState() == OutcomeState.CLOSED_TRADE).count();
    }

    private static Boolean branchCountsPass(List<Opportunity> opportunities) {
        int continuation = 0, reversal = 0;
        for (Opportunity row : opportunities) {
            if (!"ROUTED_REVERSAL_CONTINUATION".equals(row.variant())
                    || row.outcomeState() != OutcomeState.CLOSED_TRADE) continue;
            if (row.branch() == null) return null;
            if ("CONTINUATION".equals(row.branch())) continuation++;
            else if ("REVERSAL".equals(row.branch())) reversal++;
            else throw failure("routed branch metadata has an unknown value: " + row.branch());
        }
        return continuation >= 20 && reversal >= 20;
    }

    private static JsonNode accountDrawdown(ObjectNode replay, ObjectNode precommit) {
        JsonNode summary = accountPathSummary(replay, precommit);
        if (summary == null) return null;
        BigDecimal fraction = decimal(summary.path("maximum_adverse_mark_drawdown_fraction"),
                "maximum_adverse_mark_drawdown_fraction");
        if (fraction.signum() < 0 || !Double.isFinite(fraction.doubleValue())) return null;
        return JsonHashes.mapper().getNodeFactory().numberNode(fraction.doubleValue() * 100.0);
    }

    private static JsonNode accountNetPnl(ObjectNode replay, ObjectNode precommit) {
        JsonNode summary = accountPathSummary(replay, precommit);
        if (summary == null) return null;
        BigDecimal expectedStart = new BigDecimal(precommit.path("user_constraints").path("starting_equity_usdt").asText());
        BigDecimal starting = decimal(summary.path("starting_equity_usdt"), "account_path_summary.starting_equity_usdt");
        BigDecimal ending = decimal(summary.path("ending_marked_equity_usdt"), "account_path_summary.ending_marked_equity_usdt");
        if (starting.compareTo(expectedStart) != 0) throw failure("account path starting equity differs from frozen precommit");
        return JsonHashes.mapper().getNodeFactory().numberNode(ending.subtract(starting).doubleValue());
    }

    private static JsonNode accountPathSummary(ObjectNode replay, ObjectNode precommit) {
        JsonNode summary = replay.path("account_path_summary");
        if (!summary.isObject() || !"liquidation-v2-account-path-summary/1".equals(summary.path("schema").asText())
                || !JsonHashes.ownHash(summary).equals(summary.path("content_sha256").asText())
                || !replay.path("ledger_sha256").asText().equals(summary.path("ledger_sha256").asText())
                || !"ADVERSE_MARK_OHLC_EXTREMUM_BEFORE_EXIT_PATH".equals(summary.path("drawdown_basis").asText())
                || !summary.path("maximum_adverse_mark_drawdown_fraction").isNumber()
                || !Double.isFinite(summary.path("maximum_adverse_mark_drawdown_fraction").asDouble())) return null;
        BigDecimal expectedStart = new BigDecimal(precommit.path("user_constraints").path("starting_equity_usdt").asText());
        BigDecimal starting = decimal(summary.path("starting_equity_usdt"), "account_path_summary.starting_equity_usdt");
        decimal(summary.path("ending_marked_equity_usdt"), "account_path_summary.ending_marked_equity_usdt");
        return starting.compareTo(expectedStart) == 0 ? summary : null;
    }

    private static boolean hasStressEvidence(ObjectNode replay, ObjectNode precommit) {
        return REQUIRED_STRESSES.stream().allMatch(id -> stressGates(replay, precommit).path(id).isBoolean());
    }

    private static ObjectNode stressGates(ObjectNode replay, ObjectNode precommit) {
        ObjectNode result = JsonHashes.mapper().createObjectNode();
        JsonNode scenarios = precommit.path("experiment").path("acceptance").path("stress").path("required_scenarios");
        JsonNode evaluation = replay.path("stress_evaluation");
        JsonNode observed = evaluation.path("scenario_results");
        if (!scenarios.isArray() || !evaluation.isObject() || !observed.isArray()
                || !"liquidation-v2-stress-evaluation/1".equals(evaluation.path("schema").asText())
                || !JsonHashes.ownHash(evaluation).equals(evaluation.path("content_sha256").asText())
                || !replay.path("ledger_sha256").asText().equals(evaluation.path("base_ledger_sha256").asText())
                || !"RUNNER_EXECUTED_DEVELOPMENT_ONLY".equals(evaluation.path("status").asText())
                || !evaluation.path("all_scenarios_rerun_from_physical_observations").asBoolean(false)
                || !evaluation.path("no_posthoc_pnl_adjustment").asBoolean(false)) return result;
        String requiredHash = JsonHashes.canonicalSha256(scenarios);
        String stressPolicySha256;
        try { stressPolicySha256 = LiquidationV2StressPolicyV1.reopen(precommit).contentSha256(); }
        catch (IllegalArgumentException invalidPrecommit) { return result; }
        if (!requiredHash.equals(evaluation.path("frozen_scenario_policy_sha256").asText())
                || !stressPolicySha256.equals(evaluation.path("stress_policy_sha256").asText())) return result;
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode row : observed) {
            String id = row.path("scenario_id").asText();
            if (id.isBlank() || byId.putIfAbsent(id, row) != null) throw failure("stress scenario IDs must be unique and non-empty");
        }
        if (byId.size() != scenarios.size()) return result;
        for (JsonNode required : scenarios) {
            String id = required.path("id").asText();
            JsonNode row = byId.get(id);
            JsonNode artifact = row == null ? null : row.path("scenario_run_artifact");
            JsonNode completed = row == null ? null : row.path("completed_observations");
            JsonNode expectancy = row == null ? null : row.path("expectancy_r");
            JsonNode unresolved = row == null ? null : row.path("unresolved_count");
            JsonNode coverageBlocked = row == null ? null : row.path("coverage_blocked_count");
            JsonNode affected = row == null ? null : row.path("affected_position_count");
            JsonNode minObservations = row == null ? null : row.path("minimum_observations");
            JsonNode minExpectancy = row == null ? null : row.path("minimum_expectancy_r");
            if (row == null || !validScenarioRunArtifact(artifact, row)
                    || !validHash(row.path("runner_result_sha256"))
                    || !validHash(row.path("scenario_run_content_sha256"))
                    || !validHash(row.path("runner_ledger_sha256"))
                    || !validHash(row.path("runner_event_stream_sha256"))
                    || !row.path("transform_count").isIntegralNumber() || !row.path("transform_count").canConvertToLong()
                    || row.path("transform_count").asLong() < 0 || !validHash(row.path("transform_chain_sha256"))
                    || !count(completed) || !finiteNumber(expectancy) || !count(unresolved)
                    || !count(coverageBlocked) || !count(affected)
                    || !minObservations.isIntegralNumber() || !minObservations.canConvertToInt()
                    || minObservations.asInt() != required.path("minimum_observations").asInt()
                    || !finiteNumber(minExpectancy)
                    || Double.compare(minExpectancy.asDouble(), required.path("minimum_expectancy_r").asDouble()) != 0
                    || !row.path("development_only").isBoolean() || !row.path("development_only").asBoolean()
                    || !validRoutedStressSummary(row)) continue;
            boolean active = completed.asInt() >= minObservations.asInt()
                    && expectancy.asDouble() >= minExpectancy.asDouble()
                    && unresolved.asInt() == 0 && coverageBlocked.asInt() == 0;
            if ("venue_outage_blackout".equals(id)) active &= affected.asInt() > 0;
            String expectedStatus = unresolved.asInt() > 0 || coverageBlocked.asInt() > 0
                    ? "BLOCKED_UNRESOLVED_OR_COVERAGE"
                    : completed.asInt() >= minObservations.asInt()
                            && expectancy.asDouble() >= minExpectancy.asDouble()
                                    ? "CANDIDATE_THRESHOLDS_MET_DEVELOPMENT_ONLY"
                                    : "INSUFFICIENT_OR_BELOW_THRESHOLD";
            if (!expectedStatus.equals(row.path("candidate_gate_status").asText())) continue;
            result.put(id, active);
        }
        if (byId.keySet().stream().anyMatch(id -> REQUIRED_STRESSES.stream().noneMatch(id::equals))) {
            throw failure("stress result contains an undeclared scenario");
        }
        return result;
    }

    private static boolean validScenarioRunArtifact(JsonNode artifact, JsonNode row) {
        if (artifact == null || !artifact.isObject()) return false;
        JsonNode path = artifact.path("relative_path");
        if (!LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA.equals(artifact.path("schema").asText())
                || !path.isTextual() || path.asText().isBlank() || !validHash(artifact.path("sha256"))
                || !artifact.path("sha256").asText().equals(row.path("runner_result_sha256").asText())) return false;
        try {
            Path raw = Path.of(path.asText());
            for (Path element : raw) if ("..".equals(element.toString())) return false;
            Path normalized = raw.normalize();
            return !normalized.isAbsolute() && normalized.getNameCount() > 0
                    && !normalized.startsWith("..") && !path.asText().contains("\\")
                    && !path.asText().contains(":") && normalized.startsWith("scenario-runs");
        } catch (RuntimeException invalidPath) {
            return false;
        }
    }

    private static boolean validRoutedStressSummary(JsonNode row) {
        JsonNode summaries = row.path("by_candidate");
        if (!summaries.isArray() || summaries.size() != 3) return false;
        Map<String, JsonNode> byCandidate = new HashMap<>();
        for (JsonNode summary : summaries) {
            String candidateId = summary.path("candidate_id").asText();
            if (candidateId.isBlank() || byCandidate.putIfAbsent(candidateId, summary) != null
                    || !count(summary.path("completed_observations"))
                    || !finiteNumber(summary.path("expectancy_r"))
                    || !count(summary.path("open_unresolved_count"))
                    || !count(summary.path("coverage_blocked_count"))
                    || !count(summary.path("unresolved_count"))
                    || !count(summary.path("affected_position_count"))
                    || summary.path("unresolved_count").asInt() != summary.path("open_unresolved_count").asInt()
                            + summary.path("coverage_blocked_count").asInt()) return false;
        }
        Set<String> expected = Set.of("liquidation-v2-core-routed-one-entry",
                "liquidation-v2-core-always-continuation-one-entry",
                "liquidation-v2-core-always-reversal-one-entry");
        if (!byCandidate.keySet().equals(expected)) return false;
        JsonNode routed = byCandidate.get("liquidation-v2-core-routed-one-entry");
        if (routed == null) return false;
        return row.path("completed_observations").asInt() == routed.path("completed_observations").asInt()
                && Double.compare(row.path("expectancy_r").asDouble(), routed.path("expectancy_r").asDouble()) == 0
                && row.path("unresolved_count").asInt() == routed.path("unresolved_count").asInt()
                && row.path("coverage_blocked_count").asInt() == routed.path("coverage_blocked_count").asInt()
                && row.path("affected_position_count").asInt() == routed.path("affected_position_count").asInt();
    }

    private static boolean count(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0;
    }

    private static boolean finiteNumber(JsonNode value) {
        return value != null && value.isNumber() && Double.isFinite(value.asDouble());
    }

    private static boolean validHash(JsonNode value) {
        return value != null && value.isTextual() && value.asText().matches("[0-9a-f]{64}");
    }

    private static int completePairCount(List<Opportunity> opportunities) {
        TreeMap<String, List<Opportunity>> byPair = new TreeMap<>();
        for (Opportunity row : opportunities) if (TRADED_VARIANTS.contains(row.variant())) {
            byPair.computeIfAbsent(row.pairId(), ignored -> new ArrayList<>()).add(row);
        }
        return (int) byPair.values().stream().filter(rows -> rows.size() == 3 && rows.stream().allMatch(LiquidationV2ReplayEvidenceV1::resolvedForMetric)).count();
    }

    private static int pairedDecisionCount(List<Opportunity> opportunities) {
        return (int) opportunities.stream().filter(row -> TRADED_VARIANTS.contains(row.variant()))
                .map(Opportunity::pairId).distinct().count();
    }

    private static int diagnosticCount(List<Opportunity> opportunities) {
        return (int) opportunities.stream().filter(row -> "PRICE_OI_ONLY_EVENT_DIAGNOSTIC".equals(row.variant())).count();
    }

    private static ArrayNode dispositionRows(List<Opportunity> opportunities) {
        ArrayNode rows = JsonHashes.mapper().createArrayNode();
        for (Opportunity row : opportunities) {
            boolean diagnostic = "PRICE_OI_ONLY_EVENT_DIAGNOSTIC".equals(row.variant());
            ObjectNode item = rows.addObject().put("opportunity_id", row.opportunityId())
                    .put("pair_id", row.pairId()).put("candidate_id", row.candidateId())
                    .put("variant", row.variant()).put("asset", row.asset())
                    .put("decision_time", row.decisionTime().toString())
                    .put("outcome_state", row.outcomeState().name())
                    .put("policy_disposition", diagnostic ? "AUDIT_ONLY_EXCLUDED_FROM_TRADED_ZERO_ARM"
                            : row.outcomeState().name().equals("COVERAGE_BLOCKED") ? "RETAINED_COVERAGE_BLOCKED"
                                    : row.outcomeState().name().equals("OPEN_UNRESOLVED") ? "RETAINED_OPEN_UNRESOLVED"
                                            : row.outcomeState().name().equals("UNRESOLVED_NO_FILL") ? "RETAINED_UNRESOLVED_NO_FILL"
                                                    : "INCLUDED_IN_CHRONOLOGICAL_INVENTORY");
            item.set("reason_codes", strings(row.reasonCodes()));
        }
        return rows;
    }

    private static void validateAccountCurve(ArrayNode curve) {
        Long previousTime = null;
        for (JsonNode row : curve) {
            if (!row.isObject() || !row.path("time").isIntegralNumber() || !row.path("time").canConvertToLong()) {
                throw failure("account curve rows require integer millisecond timestamps");
            }
            long time = row.path("time").asLong();
            if (previousTime != null && time <= previousTime) throw failure("account curve timestamps must be strictly chronological");
            decimal(row.path("equity_usdt"), "account curve equity_usdt");
            previousTime = time;
        }
    }

    private static ObjectNode copy(ObjectNode node, String name) {
        return Objects.requireNonNull(node, name).deepCopy();
    }

    private static ObjectNode canonicalRoundTrip(ObjectNode value, String label) {
        try {
            JsonNode parsed = JsonHashes.mapper().readTree(JsonHashes.canonicalBytes(value));
            if (!(parsed instanceof ObjectNode object)) throw failure(label + " canonical JSON is not an object");
            return object;
        } catch (IOException error) {
            throw failure("cannot canonicalize " + label + " for deterministic evidence: " + error.getMessage());
        }
    }

    private static void verifySelfHash(ObjectNode node, String label) {
        if (!JsonHashes.ownHash(node).equals(node.path("content_sha256").asText())) {
            throw failure(label + " content hash does not match its reopened object");
        }
    }

    private static void requireHash(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw failure(label + " must be a lowercase SHA-256 digest");
    }

    private static ObjectNode object(ObjectNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) throw failure(field + " must be an object");
        return (ObjectNode) value;
    }

    private static String requiredText(ObjectNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw failure(field + " must be non-empty text");
        return value.asText();
    }

    private static String optionalText(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.asText().isBlank()) throw failure(field + " must be null or non-empty text");
        return value.asText();
    }

    private static Instant instant(ObjectNode parent, String field) {
        String value = requiredText(parent, field);
        try { return Instant.parse(value); }
        catch (RuntimeException error) { throw failure(field + " must be an ISO-8601 instant"); }
    }

    private static Instant optionalInstant(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw failure(field + " must be null or an ISO-8601 instant");
        try { return Instant.parse(value.asText()); }
        catch (RuntimeException error) { throw failure(field + " must be null or an ISO-8601 instant"); }
    }

    private static Double optionalNumber(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() || !Double.isFinite(value.asDouble())) throw failure(field + " must be null or finite numeric data");
        return value.asDouble();
    }

    private static BigDecimal decimal(JsonNode value, String label) {
        if (value == null || value.isNull() || !(value.isNumber() || value.isTextual())) throw failure(label + " must be finite numeric data");
        try { return new BigDecimal(value.asText()); }
        catch (NumberFormatException error) { throw failure(label + " must be finite numeric data"); }
    }

    private static boolean zeroOrNull(Double value) { return value == null || value == 0.0; }

    private static List<String> stringList(JsonNode values, String label) {
        if (!values.isArray()) throw failure(label + " must be an array");
        ArrayList<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank()) throw failure(label + " entries must be non-empty text");
            result.add(value.asText());
        }
        if (new HashSet<>(result).size() != result.size()) throw failure(label + " cannot contain duplicates");
        return List.copyOf(result);
    }

    private static OutcomeState outcomeState(String value) {
        try { return OutcomeState.valueOf(value); }
        catch (IllegalArgumentException error) { throw failure("unknown replay outcome state: " + value); }
    }

    private static Map<String, String> candidateVariantMap() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        CANDIDATES.forEach(candidate -> result.put(candidate.id(), candidate.variant()));
        return Map.copyOf(result);
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        values.forEach(result::add);
        return result;
    }

    private static IllegalArgumentException failure(String message) { return new IllegalArgumentException(message); }

    private enum OutcomeState { CLOSED_TRADE, RESOLVED_NO_TRADE, OPEN_UNRESOLVED, UNRESOLVED_NO_FILL, COVERAGE_BLOCKED }
    private record CandidateSpec(String id, String variant, boolean auditOnly) {}
    private record EvaluatedCandidate(String candidateId, String variant, boolean auditOnly,
            String executionScope, long opportunityCount) {}
    private record CorePair(String pairId, String observationId, String blockId,
            double routedR, double continuationR, double reversalR) {}
    private record CoreStatistics(int pairedDecisionCount, int completePairCount, double routedMeanR,
            double continuationMeanR, double reversalMeanR, double continuationIncrementalR,
            double reversalIncrementalR, double netP20R, double continuationP20R, double reversalP20R,
            double continuationAdjustedP, double reversalAdjustedP, List<CorePair> pairs) {
        private CoreStatistics { pairs = List.copyOf(pairs); }
        private int incompletePairCount() { return pairedDecisionCount - completePairCount; }
        private static CoreStatistics empty(int pairedCount) {
            return new CoreStatistics(pairedCount, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, List.of());
        }
        private ObjectNode toJson() {
            ObjectNode row = JsonHashes.mapper().createObjectNode()
                    .put("schema", "liquidation-v2-paired-core-statistics/1")
                    .put("paired_decision_count", pairedDecisionCount)
                    .put("complete_paired_decision_count", completePairCount)
                    .put("incomplete_paired_decision_count", incompletePairCount())
                    .put("resolved_subset_only", incompletePairCount() > 0)
                    .put("bootstrap_draw_count", LiquidationChronologicalPolicyV1.DEFAULT_DRAWS)
                    .put("bootstrap_seed", LiquidationChronologicalPolicyV1.DEFAULT_SEED)
                    .put("resampling_unit", "SYNCHRONIZED_67D_MARKET_TIME_BLOCKS_WITH_REPLACEMENT")
                    .put("comparison", "ROUTED_MINUS_EACH_FIXED_DIRECTION_CONTROL_ON_SAME_PAIR_DECISIONS")
                    .put("centered_null", true)
                    .put("familywise_scope", "TWO_CURRENT_FROZEN_DIRECTION_CONTROLS_ONLY_NOT_CUMULATIVE_FAMILY")
                    .put("cumulative_family_adjustment_available", false)
                    .put("local_contrast_interpretation", "DIAGNOSTIC_ONLY_CURRENT_CONTROLS;NOT_COMPLETE_FAMILY_MAXSTAT_OR_PROMOTION_EVIDENCE")
                    .put("routed_mean_net_r", routedMeanR).put("always_continuation_mean_net_r", continuationMeanR)
                    .put("always_reversal_mean_net_r", reversalMeanR)
                    .put("incremental_mean_vs_continuation_r", continuationIncrementalR)
                    .put("incremental_mean_vs_reversal_r", reversalIncrementalR);
            if (completePairCount == 0) {
                for (String key : List.of("routed_net_expectancy_p20_r", "incremental_p20_vs_continuation_r",
                        "incremental_p20_vs_reversal_r", "centered_max_statistic_adjusted_p_vs_continuation",
                        "centered_max_statistic_adjusted_p_vs_reversal")) row.putNull(key);
            } else {
                row.put("routed_net_expectancy_p20_r", netP20R)
                        .put("incremental_p20_vs_continuation_r", continuationP20R)
                        .put("incremental_p20_vs_reversal_r", reversalP20R)
                        .put("centered_max_statistic_adjusted_p_vs_continuation", continuationAdjustedP)
                        .put("centered_max_statistic_adjusted_p_vs_reversal", reversalAdjustedP);
            }
            row.put("content_sha256", JsonHashes.ownHash(row));
            return row;
        }
    }
    private record Opportunity(String opportunityId, String pairId, String candidateId, String variant,
            String asset, Instant decisionTime, OutcomeState outcomeState, Instant outcomeAvailableTime,
            Instant firstFillTime, Instant exitTime, Double netPnlUsdt, Double referenceRiskUsdt,
            List<String> reasonCodes, String branch, String direction) {
        private Opportunity { reasonCodes = List.copyOf(reasonCodes); }
    }
}

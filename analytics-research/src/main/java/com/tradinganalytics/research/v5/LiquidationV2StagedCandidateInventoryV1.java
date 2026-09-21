package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Sequential, immutable candidate lineage for the staged and then macro comparison. */
public final class LiquidationV2StagedCandidateInventoryV1 {
    public static final String SCHEMA = "liquidation-v2-staged-candidate-plan/1";
    public static final String OUTCOME_VALIDATION_SCHEMA = "liquidation-v2-staged-outcome-validation/1";
    public static final String EVIDENCE_SCHEMA = "liquidation-v2-staged-replay-evidence/1";
    public static final String THREE_STAGE_NO_MACRO = "THREE_STAGE_NO_MACRO";
    public static final String THREE_STAGE_MACRO = "THREE_STAGE_MACRO";
    public static final String NO_MACRO_CANDIDATE = "liquidation-v2-three-stage-no-macro";
    public static final String MACRO_CANDIDATE = "liquidation-v2-three-stage-macro";
    public static final String FAMILY = "liquidation-structure";
    public static final String FULL_POSITION_R_BASIS = "FULL_POSITION_5_PERCENT_OF_FIRST_FILL_REFERENCE_EQUITY";
    public static final String ADVANCEMENT_POLICY_ID = "LIQUIDATION_V2_STAGED_ADVANCEMENT/1";
    private static final Set<String> REQUIRED_ADVANCEMENT_GATES = Set.of(
            "minimum_accepted_trades",
            "minimum_effective_independent_episode_count",
            "minimum_completed_positions_per_branch",
            "minimum_positive_outer_folds",
            "bootstrap_p20_expectancy_r_positive",
            "bootstrap_p20_incremental_expectancy_r_positive_vs_predecessor",
            "familywise_max_statistic_p_le_0_05_vs_predecessor",
            "maximum_drawdown_r",
            "maximum_cost_r",
            "portfolio_minimum_net_pnl",
            "portfolio_maximum_drawdown_pct",
            "full_position_5_percent_r_normalization",
            "risk_exposure_matched_attribution_reported",
            "all_anchor_outcomes_resolved",
            "stress_fee_slippage",
            "stress_funding_carry",
            "stress_adverse_execution_gap",
            "stress_liquidity_capacity",
            "stress_venue_outage_blackout");

    private static final String CORE_REPLAY_SCHEMA = LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA;
    private static final String CORE_EVIDENCE_SCHEMA = LiquidationV2ReplayEvidenceV1.EVIDENCE_SCHEMA;
    private static final String CORE_INVENTORY_SCHEMA = LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA;
    private static final String ROUTED_CANDIDATE = "liquidation-v2-core-routed-one-entry";
    private static final String ROUTED_VARIANT = "ROUTED_REVERSAL_CONTINUATION";
    private static final List<String> CORE_IDS = List.of(
            "liquidation-v2-core-routed-one-entry",
            "liquidation-v2-core-always-continuation-one-entry",
            "liquidation-v2-core-always-reversal-one-entry",
            "liquidation-v2-price-oi-only-event-diagnostic");
    private static final List<String> CORE_VARIANTS = List.of("ROUTED_REVERSAL_CONTINUATION",
            "ALWAYS_CONTINUATION_CONTROL", "ALWAYS_REVERSAL_CONTROL", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
    private static final Set<String> OUTCOME_STATES = Set.of("CLOSED_TRADE", "RESOLVED_NO_TRADE",
            "OPEN_UNRESOLVED", "UNRESOLVED_NO_FILL", "COVERAGE_BLOCKED");
    private static final double[] STAGE_RISK = {0.01, 0.015, 0.025};

    private LiquidationV2StagedCandidateInventoryV1() {}

    /**
     * Freezes the full routed stage-one inventory from the exact completed core replay.
     * Outcomes are never used to filter anchors. Each anchor retains the exact intent and its
     * pre-event setup seed so a later runner can inject it without rediscovering a signal.
     */
    public static ObjectNode freezeNoMacro(ObjectNode coreReplay, ObjectNode coreEvidence,
            ObjectNode coreCandidateInventory) {
        validateCorePredecessor(coreReplay, coreEvidence, coreCandidateInventory);
        ArrayNode anchors = coreAnchors(coreReplay);
        ObjectNode plan = basePlan(THREE_STAGE_NO_MACRO, NO_MACRO_CANDIDATE,
                "STRUCTURE_ONLY", anchors, coreReplay.path("source_mode").asText());
        plan.put("sequence", 1).put("predecessor_replay_sha256", coreReplay.path("content_sha256").asText())
                .put("predecessor_evidence_sha256", coreEvidence.path("content_sha256").asText())
                .put("predecessor_candidate_inventory_sha256", coreCandidateInventory.path("content_sha256").asText());
        plan.putObject("core_anchor_lineage")
                .put("core_replay_sha256", coreReplay.path("content_sha256").asText())
                .put("core_evidence_sha256", coreEvidence.path("content_sha256").asText())
                .put("core_candidate_inventory_sha256", coreCandidateInventory.path("content_sha256").asText())
                .put("source_mode", coreReplay.path("source_mode").asText());
        plan.put("content_sha256", JsonHashes.ownHash(plan));
        validatePlan(plan);
        return plan;
    }

    /**
     * Freezes macro-last only after the no-macro evaluator has produced a hash-bound staged
     * evidence object. Real input requires recomputed advancement success; synthetic fixtures
     * may exercise mechanics but are marked as non-survival and cannot claim advancement.
     */
    public static ObjectNode freezeMacro(ObjectNode noMacroPlan, ObjectNode noMacroReplay,
            ObjectNode noMacroEvidence, ObjectNode recomputedAdvancementGates,
            ArrayNode recomputedAdvancementBlockers) {
        validatePlan(noMacroPlan);
        verifyObjectHash(noMacroReplay, "no-macro predecessor replay");
        verifyNoMacroEvidence(noMacroPlan, noMacroReplay, noMacroEvidence,
                recomputedAdvancementGates, recomputedAdvancementBlockers);
        boolean synthetic = "SYNTHETIC_DEVELOPMENT_ONLY".equals(noMacroPlan.path("source_mode").asText());
        String status = noMacroEvidence.path("advancement_status").asText();
        if (synthetic) {
            if (!"SYNTHETIC_FIXTURE_ONLY".equals(status)
                    || noMacroEvidence.path("survival_claim").asBoolean(true)
                    || noMacroEvidence.path("promotion_permitted").asBoolean(true)) {
                throw failure("synthetic predecessor can only create a clearly non-surviving macro fixture plan");
            }
        } else if (!"SURVIVES".equals(status)) {
            throw failure("macro-last candidate is frozen only after a real no-macro predecessor survives");
        }
        ArrayNode anchors = noMacroPlan.path("decision_anchors").deepCopy();
        ObjectNode plan = basePlan(THREE_STAGE_MACRO, MACRO_CANDIDATE,
                "REQUIRE_MACRO_CONFIRMATION", anchors, noMacroPlan.path("source_mode").asText());
        plan.put("sequence", 2).put("predecessor_plan_sha256", noMacroPlan.path("content_sha256").asText())
                .put("predecessor_replay_sha256", noMacroReplay.path("content_sha256").asText())
                .put("predecessor_evidence_sha256", noMacroEvidence.path("content_sha256").asText())
                .put("advancement_policy_id", ADVANCEMENT_POLICY_ID)
                .put("advancement_status", status).put("survival_claim", !synthetic)
                .put("promotion_permitted", false);
        plan.set("core_anchor_lineage", noMacroPlan.path("core_anchor_lineage").deepCopy());
        plan.put("content_sha256", JsonHashes.ownHash(plan));
        validatePlan(plan);
        return plan;
    }

    /** Preferred path: the complete evaluator result, including recomputed statistics, is bound. */
    public static ObjectNode freezeMacro(ObjectNode noMacroPlan, ObjectNode noMacroReplay,
            ObjectNode noMacroEvidence, LiquidationV2StagedStatisticsV1.Evaluation evaluation) {
        validatePlan(noMacroPlan);
        verifyObjectHash(noMacroReplay, "no-macro predecessor replay");
        verifyNoMacroEvidence(noMacroPlan, noMacroReplay, noMacroEvidence, evaluation);
        return freezeMacroAfterEvidence(noMacroPlan, noMacroReplay, noMacroEvidence);
    }

    private static ObjectNode freezeMacroAfterEvidence(ObjectNode noMacroPlan, ObjectNode noMacroReplay,
            ObjectNode noMacroEvidence) {
        boolean synthetic = "SYNTHETIC_DEVELOPMENT_ONLY".equals(noMacroPlan.path("source_mode").asText());
        String status = noMacroEvidence.path("advancement_status").asText();
        if (synthetic) {
            if (!"SYNTHETIC_FIXTURE_ONLY".equals(status)
                    || noMacroEvidence.path("survival_claim").asBoolean(true)
                    || noMacroEvidence.path("promotion_permitted").asBoolean(true)) {
                throw failure("synthetic predecessor can only create a clearly non-surviving macro fixture plan");
            }
        } else if (!"SURVIVES".equals(status)) {
            throw failure("macro-last candidate is frozen only after a real no-macro predecessor survives");
        }
        ArrayNode anchors = noMacroPlan.path("decision_anchors").deepCopy();
        ObjectNode plan = basePlan(THREE_STAGE_MACRO, MACRO_CANDIDATE,
                "REQUIRE_MACRO_CONFIRMATION", anchors, noMacroPlan.path("source_mode").asText());
        plan.put("sequence", 2).put("predecessor_plan_sha256", noMacroPlan.path("content_sha256").asText())
                .put("predecessor_replay_sha256", noMacroReplay.path("content_sha256").asText())
                .put("predecessor_evidence_sha256", noMacroEvidence.path("content_sha256").asText())
                .put("advancement_policy_id", ADVANCEMENT_POLICY_ID)
                .put("advancement_status", status).put("survival_claim", !synthetic)
                .put("promotion_permitted", false);
        plan.set("core_anchor_lineage", noMacroPlan.path("core_anchor_lineage").deepCopy());
        plan.put("content_sha256", JsonHashes.ownHash(plan));
        validatePlan(plan);
        return plan;
    }

    /** Reopens and reconstructs a no-macro plan from its exact core predecessor artifacts. */
    public static boolean validateNoMacroPlan(ObjectNode plan, ObjectNode coreReplay,
            ObjectNode coreEvidence, ObjectNode coreCandidateInventory) {
        validatePlan(plan);
        ObjectNode expected = freezeNoMacro(coreReplay, coreEvidence, coreCandidateInventory);
        if (!JsonHashes.canonicalSha256(expected).equals(JsonHashes.canonicalSha256(plan))) {
            throw failure("staged no-macro plan differs from its reopened core replay/evidence/inventory");
        }
        return true;
    }

    /** Reopens and reconstructs macro-last from the surviving no-macro predecessor artifacts. */
    public static boolean validateMacroPlan(ObjectNode plan, ObjectNode noMacroPlan,
            ObjectNode noMacroReplay, ObjectNode noMacroEvidence,
            ObjectNode recomputedAdvancementGates, ArrayNode recomputedAdvancementBlockers) {
        validatePlan(plan);
        ObjectNode expected = freezeMacro(noMacroPlan, noMacroReplay, noMacroEvidence,
                recomputedAdvancementGates, recomputedAdvancementBlockers);
        if (!JsonHashes.canonicalSha256(expected).equals(JsonHashes.canonicalSha256(plan))) {
            throw failure("macro-last plan differs from its reopened surviving no-macro lineage");
        }
        return true;
    }

    public static boolean validateMacroPlan(ObjectNode plan, ObjectNode noMacroPlan,
            ObjectNode noMacroReplay, ObjectNode noMacroEvidence,
            LiquidationV2StagedStatisticsV1.Evaluation evaluation) {
        validatePlan(plan);
        ObjectNode expected = freezeMacro(noMacroPlan, noMacroReplay, noMacroEvidence, evaluation);
        if (!JsonHashes.canonicalSha256(expected).equals(JsonHashes.canonicalSha256(plan))) {
            throw failure("macro-last plan differs from its reopened surviving no-macro lineage");
        }
        return true;
    }

    /** Creates canonical staged evidence from evaluator-computed named gate results. */
    public static ObjectNode buildReplayEvidence(ObjectNode plan, ObjectNode replay,
            ObjectNode evaluatorComputedGates, ArrayNode evaluatorComputedBlockers) {
        return buildReplayEvidence(plan, replay, evaluatorComputedGates, evaluatorComputedBlockers, null);
    }

    public static ObjectNode buildReplayEvidence(ObjectNode plan, ObjectNode replay,
            LiquidationV2StagedStatisticsV1.Evaluation evaluation) {
        Objects.requireNonNull(evaluation, "evaluation");
        return buildReplayEvidence(plan, replay, evaluation.gates(), evaluation.blockers(), evaluation.statistics());
    }

    private static ObjectNode buildReplayEvidence(ObjectNode plan, ObjectNode replay,
            ObjectNode evaluatorComputedGates, ArrayNode evaluatorComputedBlockers, ObjectNode statistics) {
        validatePlan(plan);
        verifyObjectHash(replay, "staged runner result");
        Objects.requireNonNull(evaluatorComputedGates, "evaluatorComputedGates");
        Objects.requireNonNull(evaluatorComputedBlockers, "evaluatorComputedBlockers");
        requireReplayBoundToPlan(plan, replay);
        ObjectNode outcomeValidation = validateAnchoredOutcomes(plan, plan.path("mode_id").asText(),
                array(replay, "opportunities"));
        validateBooleanGates(evaluatorComputedGates);
        validateBlockers(evaluatorComputedBlockers);
        if (statistics != null) validateStatistics(plan, statistics);
        boolean synthetic = "SYNTHETIC_DEVELOPMENT_ONLY".equals(plan.path("source_mode").asText());
        boolean allGates = statistics != null && allTrue(evaluatorComputedGates) && evaluatorComputedBlockers.isEmpty()
                && "DEVELOPMENT_ONLY".equals(outcomeValidation.path("status").asText());
        String advancementStatus = synthetic ? "SYNTHETIC_FIXTURE_ONLY" : allGates ? "SURVIVES" : "DOES_NOT_SURVIVE";
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", EVIDENCE_SCHEMA).put("version", 1)
                .put("evidence_scope", "DEVELOPMENT_STAGED_COMPARISON;PUBLIC_RUNNER_MUST_REOPEN_AND_RECOMPUTE")
                .put("mode_id", plan.path("mode_id").asText()).put("candidate_id", plan.path("candidate_id").asText())
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText())
                .put("replay_result_sha256", replay.path("content_sha256").asText())
                .put("source_mode", plan.path("source_mode").asText())
                .put("advancement_policy_id", ADVANCEMENT_POLICY_ID)
                .put("advancement_status", advancementStatus)
                .put("survival_claim", !synthetic && allGates)
                .put("promotion_permitted", false);
        result.set("advancement_gates", evaluatorComputedGates.deepCopy());
        result.set("advancement_blockers", evaluatorComputedBlockers.deepCopy());
        result.set("outcome_validation", outcomeValidation);
        result.set("overfit_selection_diagnostics", overfitSelectionDiagnostics());
        if (statistics == null) result.putNull("statistics");
        else result.set("statistics", statistics.deepCopy());
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    private static ObjectNode overfitSelectionDiagnostics() {
        return JsonHashes.mapper().createObjectNode()
                .put("selection_search_performed", false)
                .put("pbo_status", "NOT_APPLICABLE_FIXED_PREDECLARED_NO_SELECTION")
                .putNull("pbo_value")
                .put("pbo_reason", "FIXED_PREDECLARED_SEQUENTIAL_COMPARISONS_HAVE_NO_WINNER_SELECTION_MATRIX")
                .put("dsr_status", "UNAVAILABLE_NO_FROZEN_DSR_ESTIMATE")
                .putNull("dsr_value")
                .put("promotion_permitted", false);
    }

    private static void validateStatistics(ObjectNode plan, ObjectNode statistics) {
        if (!LiquidationV2StagedStatisticsV1.SCHEMA.equals(statistics.path("schema").asText())
                || statistics.path("version").asInt(-1) != 1
                || !JsonHashes.ownHash(statistics).equals(statistics.path("content_sha256").asText())
                || !plan.path("mode_id").asText().equals(statistics.path("mode_id").asText())
                || !plan.path("candidate_id").asText().equals(statistics.path("candidate_id").asText())
                || !"POOLED_DEVELOPMENT;PREDECESSOR_PAIRED;NOT_OOS_OR_PROMOTION_EVIDENCE"
                        .equals(statistics.path("statistics_scope").asText())
                || !LiquidationV2EvidenceMathV1.FULL_POSITION_RISK_NUMERIC_REPRESENTATION
                        .equals(statistics.path("full_position_risk_numeric_representation").asText())
                || !validOverfitSelectionDiagnostics(statistics.path("overfit_selection_diagnostics"))) {
            throw failure("recomputed staged statistics are malformed or detached from the exact plan");
        }
    }

    private static boolean validOverfitSelectionDiagnostics(JsonNode value) {
        return value.isObject() && value.path("selection_search_performed").isBoolean()
                && !value.path("selection_search_performed").asBoolean()
                && "NOT_APPLICABLE_FIXED_PREDECLARED_NO_SELECTION".equals(value.path("pbo_status").asText())
                && value.path("pbo_value").isNull()
                && "FIXED_PREDECLARED_SEQUENTIAL_COMPARISONS_HAVE_NO_WINNER_SELECTION_MATRIX"
                        .equals(value.path("pbo_reason").asText())
                && "UNAVAILABLE_NO_FROZEN_DSR_ESTIMATE".equals(value.path("dsr_status").asText())
                && value.path("dsr_value").isNull() && value.path("promotion_permitted").isBoolean()
                && !value.path("promotion_permitted").asBoolean();
    }

    /** Immutable deep copy of the exact shared core stage-one anchors. */
    public static ArrayNode anchors(ObjectNode plan) {
        validatePlan(plan);
        return plan.path("decision_anchors").deepCopy();
    }

    /** Returns the single candidate row bound to this sequential plan. */
    public static ObjectNode candidate(ObjectNode plan, String modeId) {
        validatePlan(plan);
        if (!plan.path("mode_id").asText().equals(modeId)) throw failure("requested staged mode is not the frozen mode");
        return plan.path("candidate").deepCopy();
    }

    /**
     * Validates one aggregate outcome per frozen anchor. Coverage-blocked rows may be filled
     * positions and remain unresolved blockers; stage additions remain nested in that episode.
     */
    public static ObjectNode validateAnchoredOutcomes(ObjectNode plan, String modeId, ArrayNode rows) {
        validatePlan(plan);
        Objects.requireNonNull(rows, "rows");
        ObjectNode candidate = candidate(plan, modeId);
        Map<String, JsonNode> anchorByPair = new HashMap<>();
        for (JsonNode anchor : plan.path("decision_anchors")) anchorByPair.put(text(anchor, "pair_id"), anchor);
        Map<String, JsonNode> seen = new HashMap<>();
        Set<String> positionIds = new HashSet<>();
        int trades = 0, resolvedZero = 0, unresolved = 0, coverageBlocked = 0, filledBlocked = 0;
        for (JsonNode row : rows) {
            String pairId = text(row, "pair_id");
            JsonNode anchor = anchorByPair.get(pairId);
            if (anchor == null || seen.putIfAbsent(pairId, row) != null) {
                throw failure("staged output has an extra or duplicate frozen predecessor pair");
            }
            JsonNode intent = anchor.path("initial_intent");
            if (!candidate.path("candidate_id").asText().equals(row.path("candidate_id").asText())
                    || !ROUTED_VARIANT.equals(row.path("variant").asText())
                    || !text(anchor, "asset").equals(row.path("asset").asText())
                    || !text(anchor, "decision_time").equals(row.path("decision_time").asText())
                    || !text(intent, "branch").equals(row.path("branch").asText())
                    || !text(intent, "direction").equals(row.path("direction").asText())
                    || !sameInitialIntent(anchor, row)) {
                throw failure("staged output changed its frozen initial decision, branch, direction, or full intent geometry");
            }
            String state = text(row, "outcome_state");
            if (!OUTCOME_STATES.contains(state)) throw failure("staged output has an unsupported outcome state");
            Instant decision = instant(row, "decision_time");
            Instant firstFill = optionalInstant(row, "first_fill_time");
            Instant available = optionalInstant(row, "outcome_available_time");
            boolean filledState = firstFill != null;
            if (filledState) {
                if (!firstFill.isAfter(decision)) throw failure("staged first fill must be strictly after its anchored decision");
                String episode = text(row, "position_episode_id");
                if (!positionIds.add(episode)) throw failure("staged additions were counted as more than one position episode");
                requireFullPositionRisk(row);
            } else if (presentText(row, "position_episode_id")) {
                throw failure("no-fill outcome cannot invent a first-fill position episode");
            }
            switch (state) {
                case "CLOSED_TRADE" -> {
                    if (!filledState) throw failure("closed staged trade is missing its first fill");
                    Instant exit = instant(row, "exit_time");
                    if (exit.isBefore(firstFill) || available == null || available.isBefore(exit)
                            || !row.path("net_pnl_usdt").isNumber() || !Double.isFinite(row.path("net_pnl_usdt").asDouble())) {
                        throw failure("closed staged position has invalid exit/economic availability");
                    }
                    trades++;
                }
                case "OPEN_UNRESOLVED" -> {
                    if (!filledState || available != null || !row.path("exit_time").isNull()
                            || !row.path("net_pnl_usdt").isNull()) throw failure("open staged position must remain unresolved");
                    unresolved++;
                }
                case "COVERAGE_BLOCKED" -> {
                    requireReasons(row);
                    if (available != null && available.isBefore(decision)) throw failure("coverage block predates its anchor");
                    if (filledState) filledBlocked++;
                    coverageBlocked++;
                    unresolved++;
                }
                case "RESOLVED_NO_TRADE" -> {
                    if (filledState || available == null || available.isBefore(decision)
                            || !row.path("net_pnl_usdt").isNumber() || row.path("net_pnl_usdt").decimalValue().signum() != 0) {
                        throw failure("resolved no-trade must be an explicit zero known no earlier than its anchor");
                    }
                    requireReasons(row); resolvedZero++;
                }
                case "UNRESOLVED_NO_FILL" -> {
                    if (filledState || available != null || !row.path("net_pnl_usdt").isNull()) {
                        throw failure("unresolved no-fill row cannot carry outcome economics");
                    }
                    requireReasons(row); unresolved++;
                }
                default -> throw failure("unsupported staged outcome state");
            }
        }
        if (seen.size() != anchorByPair.size()) throw failure("staged output omitted frozen decision anchors or explicit zero outcomes");
        ObjectNode result = JsonHashes.mapper().createObjectNode().put("schema", OUTCOME_VALIDATION_SCHEMA)
                .put("version", 1).put("plan_sha256", plan.path("content_sha256").asText())
                .put("mode_id", modeId).put("candidate_id", candidate.path("candidate_id").asText())
                .put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText())
                .put("retained_anchor_count", anchorByPair.size()).put("position_outcome_count", seen.size())
                .put("completed_position_count", trades).put("resolved_zero_count", resolvedZero)
                .put("unresolved_count", unresolved).put("coverage_blocked_count", coverageBlocked)
                .put("filled_coverage_blocked_count", filledBlocked).put("development_only", true)
                .put("promotion_permitted", false);
        result.put("status", unresolved > 0 ? "BLOCKED" : "DEVELOPMENT_ONLY");
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    /** Reconstructs and hash-checks an advancement attestation before macro-last can be frozen. */
    public static boolean validateReplayEvidence(ObjectNode plan, ObjectNode replay, ObjectNode evidence,
            ObjectNode recomputedAdvancementGates, ArrayNode recomputedAdvancementBlockers) {
        validatePlan(plan);
        ObjectNode expected = buildReplayEvidence(plan, replay,
                recomputedAdvancementGates, recomputedAdvancementBlockers);
        if (!JsonHashes.canonicalSha256(expected).equals(JsonHashes.canonicalSha256(evidence))) {
            throw failure("staged evidence differs from reopened plan, replay, anchors, outcomes, or advancement gates");
        }
        return true;
    }

    public static boolean validateReplayEvidence(ObjectNode plan, ObjectNode replay, ObjectNode evidence,
            LiquidationV2StagedStatisticsV1.Evaluation evaluation) {
        validatePlan(plan);
        ObjectNode expected = buildReplayEvidence(plan, replay, evaluation);
        if (!JsonHashes.canonicalSha256(expected).equals(JsonHashes.canonicalSha256(evidence))) {
            throw failure("staged evidence differs from reopened plan, replay, paired statistics, gates, or blockers");
        }
        return true;
    }

    public static boolean validateOutcomeSummary(ObjectNode summary) {
        if (summary == null || !OUTCOME_VALIDATION_SCHEMA.equals(summary.path("schema").asText())
                || summary.path("version").asInt(-1) != 1
                || !JsonHashes.ownHash(summary).equals(summary.path("content_sha256").asText())
                || summary.path("promotion_permitted").asBoolean(true)
                || !summary.path("development_only").asBoolean(false)
                || summary.path("position_outcome_count").asInt(-1) != summary.path("retained_anchor_count").asInt(-2)) {
            throw failure("staged outcome summary is malformed or omits predecessor anchors");
        }
        return true;
    }

    private static ObjectNode basePlan(String mode, String candidateId, String macroPolicy,
            ArrayNode anchors, String sourceMode) {
        ObjectNode plan = JsonHashes.mapper().createObjectNode()
                .put("schema", SCHEMA).put("version", 1).put("strategy_family", FAMILY)
                .put("status", "PREDECESSOR_ANCHORED_DEVELOPMENT_PLAN")
                .put("mode_id", mode).put("candidate_id", candidateId)
                .put("source_mode", sourceMode)
                .put("selection_policy", "ALL_FROZEN_ROUTED_STAGE_ONE_ANCHORS;NO_OUTCOME_BASED_FILTERING")
                .put("macro_gate_policy", macroPolicy)
                .put("position_outcome_granularity", "ONE_OUTCOME_PER_FIRST_FILL_EPISODE")
                .put("staged_reference_r_basis", FULL_POSITION_R_BASIS)
                .put("staged_position_risk_fraction", 0.05)
                .put("stage_risk_fraction_sum", 0.05)
                .put("initial_opportunity_source", "PREDECESSOR_ROUTED_STAGE_ONE_INTENT_AND_SETUP_SEED")
                .put("outcome_selection_permitted", false).put("promotion_permitted", false);
        ArrayNode fractions = plan.putArray("stage_risk_fractions");
        for (double risk : STAGE_RISK) fractions.add(risk);
        plan.set("decision_anchors", anchors.deepCopy());
        plan.put("decision_anchor_count", anchors.size());
        plan.put("anchor_inventory_sha256", JsonHashes.canonicalSha256(anchors));
        ObjectNode candidate = plan.putObject("candidate").put("mode_id", mode)
                .put("candidate_id", candidateId).put("macro_gate_policy", macroPolicy)
                .put("initial_opportunity_source", "PREDECESSOR_ROUTED_STAGE_ONE_INTENT_AND_SETUP_SEED")
                .put("initial_pair_inventory_sha256", JsonHashes.canonicalSha256(anchors))
                .put("position_outcome_granularity", "ONE_OUTCOME_PER_FIRST_FILL_EPISODE")
                .put("reference_risk_basis", FULL_POSITION_R_BASIS)
                .put("full_position_reference_risk_fraction", 0.05)
                .put("stage_risk_fraction_sum", 0.05).put("outcome_selection_permitted", false);
        ArrayNode candidateFractions = candidate.putArray("stage_risk_fractions");
        for (double risk : STAGE_RISK) candidateFractions.add(risk);
        return plan;
    }

    private static void validateCorePredecessor(ObjectNode replay, ObjectNode evidence, ObjectNode inventory) {
        verifyObjectHash(replay, "core predecessor replay");
        verifyObjectHash(evidence, "core predecessor evidence");
        verifyObjectHash(inventory, "core candidate inventory");
        if (!CORE_REPLAY_SCHEMA.equals(replay.path("schema").asText()) || replay.path("version").asInt(-1) != 1
                || !CORE_EVIDENCE_SCHEMA.equals(evidence.path("schema").asText())
                || evidence.path("version").asInt(-1) != 1
                || !CORE_INVENTORY_SCHEMA.equals(inventory.path("schema").asText())
                || !FAMILY.equals(inventory.path("strategy_family").asText())) {
            throw failure("staged plan requires the exact v002 core replay/evidence/family inventory");
        }
        if (!inventory.path("content_sha256").asText().equals(replay.path("freeze").path("candidate_inventory_sha256").asText())
                || !replay.path("content_sha256").asText().equals(evidence.path("replay_result_sha256").asText())
                || !replay.path("source_mode").asText().equals(evidence.path("source_mode").asText())
                || !replay.path("source_mode").asText().equals(replay.path("freeze").path("source_mode").asText())) {
            throw failure("core predecessor replay/evidence/inventory bindings disagree");
        }
        JsonNode current = inventory.path("current_candidates");
        if (!current.isArray() || current.size() != CORE_IDS.size()) throw failure("predecessor is not the exact frozen four-candidate inventory");
        Set<String> seen = new HashSet<>();
        for (JsonNode row : current) {
            String id = text(row, "candidate_id");
            int index = CORE_IDS.indexOf(id);
            if (index < 0 || !CORE_VARIANTS.get(index).equals(row.path("variant").asText()) || !seen.add(id)) {
                throw failure("core predecessor inventory contains an unexpected or duplicate frozen candidate");
            }
        }
        if (evidence.path("promotion_permitted").asBoolean(true) || evidence.path("trade_authorization_permitted").asBoolean(true)) {
            throw failure("core predecessor evidence cannot authorize promotion or trading");
        }
        if (!"SYNTHETIC_DEVELOPMENT_ONLY".equals(replay.path("source_mode").asText())) {
            if (!"DEVELOPMENT".equals(evidence.path("status").asText())
                    || !evidence.path("advancement_blockers").isArray()
                    || !evidence.path("advancement_blockers").isEmpty()
                    || !allBooleanEvidenceTrue(evidence.path("acceptance_gates"))) {
                throw failure("failed or incomplete real core evidence cannot seed staged advancement");
            }
        }
    }

    private static ArrayNode coreAnchors(ObjectNode replay) {
        JsonNode opportunities = replay.path("opportunities");
        JsonNode audit = replay.path("route_audit");
        if (!opportunities.isArray() || !audit.isArray()) throw failure("core replay lacks routed opportunities or route audit");
        Map<String, List<JsonNode>> auditByIntent = new HashMap<>();
        for (JsonNode row : audit) {
            if ("CONFIRMED_STAGE_ONE_INTENT".equals(row.path("status").asText())) {
                String id = text(row, "intent_id");
                auditByIntent.computeIfAbsent(id, ignored -> new ArrayList<>()).add(row);
            }
        }
        List<ObjectNode> sorted = new ArrayList<>();
        Set<String> pairs = new HashSet<>();
        for (JsonNode opportunity : opportunities) {
            if (!ROUTED_CANDIDATE.equals(opportunity.path("candidate_id").asText())) continue;
            if (!ROUTED_VARIANT.equals(opportunity.path("variant").asText())
                    || !opportunity.path("stage").isIntegralNumber() || opportunity.path("stage").asInt() != 1) {
                throw failure("routed core opportunity is not explicitly a stage-one routed decision");
            }
            JsonNode initial = opportunity.path("initial_intent");
            String pair = text(opportunity, "pair_id");
            if (!pairs.add(pair) || !initial.isObject()
                    || !JsonHashes.ownHash(initial).equals(initial.path("content_sha256").asText())) {
                throw failure("routed core opportunity has duplicate pair or malformed full initial intent");
            }
            String intentId = text(initial, "intent_id");
            String intentHash = JsonHashes.canonicalSha256(initial);
            if (!intentHash.equals(opportunity.path("initial_intent_sha256").asText())) {
                throw failure("core routed opportunity does not bind its full initial intent hash");
            }
            if (!text(initial, "setup_id").equals(opportunity.path("setup_id").asText())
                    || !pair.equals(initial.path("pair_id").asText())
                    || !text(initial, "asset").equals(opportunity.path("asset").asText())
                    || !text(initial, "decision_time").equals(opportunity.path("decision_time").asText())
                    || !ROUTED_VARIANT.equals(initial.path("variant").asText())
                    || initial.path("stage").asInt(-1) != 1 || initial.path("diagnostic_only").asBoolean(true)
                    || !initial.path("setup_seed").isObject()) {
                throw failure("core routed opportunity identity or bound setup seed differs from its initial intent");
            }
            String seedSha = JsonHashes.ownHash(initial.path("setup_seed"));
            if (!seedSha.equals(initial.path("setup_seed").path("content_sha256").asText())) {
                throw failure("core initial intent setup seed self-hash is invalid");
            }
            List<JsonNode> audits = auditByIntent.getOrDefault(intentId, List.of());
            if (audits.size() != 1) throw failure("core initial intent lacks exactly one confirmed route-audit row");
            JsonNode signal = audits.get(0);
            if (!intentHash.equals(signal.path("initial_intent_sha256").asText())
                    || !intentHash.equals(JsonHashes.canonicalSha256(signal.path("initial_intent")))
                    || !JsonHashes.canonicalSha256(initial).equals(JsonHashes.canonicalSha256(signal.path("initial_intent")))
                    || !pair.equals(signal.path("pair_id").asText())
                    || !text(initial, "setup_id").equals(signal.path("setup_id").asText())
                    || !intentId.equals(signal.path("intent_id").asText())
                    || !"CONFIRMED_STAGE_ONE_INTENT".equals(signal.path("status").asText())
                    || !ROUTED_CANDIDATE.equals(signal.path("candidate_id").asText())
                    || !text(initial, "asset").equals(signal.path("asset").asText())
                    || !text(initial, "decision_time").equals(signal.path("decision_time").asText())
                    || !text(initial, "branch").equals(signal.path("branch").asText())
                    || !text(initial, "direction").equals(signal.path("direction").asText())) {
                throw failure("core route audit does not preserve the exact opportunity initial intent and hash");
            }
            ObjectNode anchor = JsonHashes.mapper().createObjectNode().put("pair_id", pair)
                    .put("asset", text(opportunity, "asset")).put("decision_time", text(opportunity, "decision_time"))
                    .put("branch", text(initial, "branch")).put("direction", text(initial, "direction"))
                    .put("setup_id", text(initial, "setup_id")).put("intent_id", intentId)
                    .put("initial_intent_sha256", intentHash).put("selection_source", "CORE_ROUTED_CONFIRMED_STAGE_ONE");
            anchor.set("initial_intent", initial.deepCopy());
            sorted.add(anchor);
        }
        sorted.sort(Comparator.comparing((ObjectNode row) -> Instant.parse(row.path("decision_time").asText()))
                .thenComparing(row -> row.path("asset").asText()).thenComparing(row -> row.path("pair_id").asText()));
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        sorted.forEach(result::add);
        return result;
    }

    private static boolean sameInitialIntent(JsonNode anchor, JsonNode row) {
        JsonNode expected = anchor.path("initial_intent"), actual = row.path("initial_intent");
        return actual.isObject()
                && anchor.path("initial_intent_sha256").asText().equals(row.path("initial_intent_sha256").asText())
                && anchor.path("initial_intent_sha256").asText().equals(JsonHashes.canonicalSha256(actual))
                && anchor.path("initial_intent_sha256").asText().equals(JsonHashes.canonicalSha256(expected))
                && JsonHashes.canonicalSha256(expected).equals(JsonHashes.canonicalSha256(actual));
    }

    private static void requireFullPositionRisk(JsonNode row) {
        JsonNode equity = row.path("first_fill_reference_equity_usdt");
        JsonNode risk = row.path("full_position_reference_risk_usdt");
        JsonNode rDenominator = row.path("reference_risk_usdt");
        if (!equity.isNumber() || !risk.isNumber() || !rDenominator.isNumber()
                || !Double.isFinite(equity.asDouble()) || !Double.isFinite(risk.asDouble())
                || !Double.isFinite(rDenominator.asDouble()) || equity.decimalValue().signum() <= 0
                || !FULL_POSITION_R_BASIS.equals(row.path("full_position_reference_risk_basis").asText())) {
            throw failure("filled staged position lacks numeric full-position reference equity and 5% R");
        }
        BigDecimal fivePercent = new BigDecimal("0.05");
        if (!LiquidationV2EvidenceMathV1.scaledRoundedBinary64IntervalsOverlap(
                    equity.asDouble(), risk.asDouble(), fivePercent)
                || !LiquidationV2EvidenceMathV1.scaledRoundedBinary64IntervalsOverlap(
                    equity.asDouble(), rDenominator.asDouble(), fivePercent)
                || !LiquidationV2EvidenceMathV1.roundedBinary64IntervalsOverlap(
                    risk.asDouble(), rDenominator.asDouble())) {
            throw failure("staged R denominator is inconsistent with 5% of first-fill reference equity under canonical binary64 rounding");
        }
    }

    private static void verifyNoMacroEvidence(ObjectNode plan, ObjectNode replay, ObjectNode evidence,
            ObjectNode recomputedGates, ArrayNode recomputedBlockers) {
        if (evidence == null || !EVIDENCE_SCHEMA.equals(evidence.path("schema").asText())
                || evidence.path("version").asInt(-1) != 1
                || !JsonHashes.ownHash(evidence).equals(evidence.path("content_sha256").asText())
                || !THREE_STAGE_NO_MACRO.equals(plan.path("mode_id").asText())
                || !evidence.path("plan_sha256").asText().equals(plan.path("content_sha256").asText())
                || !evidence.path("replay_result_sha256").asText().equals(replay.path("content_sha256").asText())
                || !evidence.path("anchor_inventory_sha256").asText().equals(plan.path("anchor_inventory_sha256").asText())
                || !evidence.path("source_mode").asText().equals(plan.path("source_mode").asText())
                || !ADVANCEMENT_POLICY_ID.equals(evidence.path("advancement_policy_id").asText())) {
            throw failure("no-macro staged evidence is not hash-bound to its exact plan/replay/anchor lineage");
        }
        if (!validateReplayEvidence(plan, replay, evidence, recomputedGates, recomputedBlockers)) {
            throw failure("staged evidence did not reopen");
        }
        ObjectNode summary = object(evidence, "outcome_validation");
        validateOutcomeSummary(summary);
        if (!plan.path("anchor_inventory_sha256").asText().equals(summary.path("anchor_inventory_sha256").asText())
                || !plan.path("candidate_id").asText().equals(summary.path("candidate_id").asText())
                || !plan.path("mode_id").asText().equals(summary.path("mode_id").asText())) {
            throw failure("no-macro evidence outcome summary detached from its frozen anchor inventory");
        }
        if ("SURVIVES".equals(evidence.path("advancement_status").asText())) {
            if (!evidence.path("survival_claim").asBoolean(false)
                    || !evidence.path("promotion_permitted").isBoolean()
                    || evidence.path("promotion_permitted").asBoolean()
                    || !allTrue(object(evidence, "advancement_gates"))
                    || !array(evidence, "advancement_blockers").isEmpty()
                    || !"DEVELOPMENT_ONLY".equals(summary.path("status").asText())) {
                throw failure("surviving staged predecessor has failed or incomplete frozen advancement evidence");
            }
        } else if ("SYNTHETIC_FIXTURE_ONLY".equals(evidence.path("advancement_status").asText())) {
            if (evidence.path("survival_claim").asBoolean(true) || evidence.path("promotion_permitted").asBoolean(true)
                    || !"SYNTHETIC_DEVELOPMENT_ONLY".equals(plan.path("source_mode").asText())) {
                throw failure("synthetic staged evidence cannot claim survival or promotion");
            }
        } else {
            throw failure("staged predecessor did not survive; macro-last cannot be frozen");
        }
    }

    private static void verifyNoMacroEvidence(ObjectNode plan, ObjectNode replay, ObjectNode evidence,
            LiquidationV2StagedStatisticsV1.Evaluation evaluation) {
        if (evidence == null || !EVIDENCE_SCHEMA.equals(evidence.path("schema").asText())
                || evidence.path("version").asInt(-1) != 1
                || !JsonHashes.ownHash(evidence).equals(evidence.path("content_sha256").asText())
                || !THREE_STAGE_NO_MACRO.equals(plan.path("mode_id").asText())
                || !evidence.path("plan_sha256").asText().equals(plan.path("content_sha256").asText())
                || !evidence.path("replay_result_sha256").asText().equals(replay.path("content_sha256").asText())
                || !evidence.path("anchor_inventory_sha256").asText().equals(plan.path("anchor_inventory_sha256").asText())
                || !evidence.path("source_mode").asText().equals(plan.path("source_mode").asText())
                || !ADVANCEMENT_POLICY_ID.equals(evidence.path("advancement_policy_id").asText())) {
            throw failure("no-macro staged evidence is not hash-bound to its exact plan/replay/anchor lineage");
        }
        if (!validateReplayEvidence(plan, replay, evidence, evaluation)) {
            throw failure("staged evidence did not reopen from the complete recomputed evaluator output");
        }
        ObjectNode summary = object(evidence, "outcome_validation");
        validateOutcomeSummary(summary);
        if (!plan.path("anchor_inventory_sha256").asText().equals(summary.path("anchor_inventory_sha256").asText())
                || !plan.path("candidate_id").asText().equals(summary.path("candidate_id").asText())
                || !plan.path("mode_id").asText().equals(summary.path("mode_id").asText())) {
            throw failure("no-macro evidence outcome summary detached from its frozen anchor inventory");
        }
        boolean synthetic = "SYNTHETIC_DEVELOPMENT_ONLY".equals(plan.path("source_mode").asText());
        if (synthetic) {
            if (!"SYNTHETIC_FIXTURE_ONLY".equals(evidence.path("advancement_status").asText())
                    || evidence.path("survival_claim").asBoolean(true)
                    || evidence.path("promotion_permitted").asBoolean(true)) {
                throw failure("synthetic staged evidence cannot claim survival or promotion");
            }
        } else if (!"SURVIVES".equals(evidence.path("advancement_status").asText())
                || !evidence.path("survival_claim").asBoolean(false)
                || evidence.path("promotion_permitted").asBoolean(true)
                || !allTrue(object(evidence, "advancement_gates"))
                || !array(evidence, "advancement_blockers").isEmpty()
                || !"DEVELOPMENT_ONLY".equals(summary.path("status").asText())) {
            throw failure("real no-macro staged evidence did not pass every recomputed frozen advancement gate");
        }
    }

    private static void requireReplayBoundToPlan(ObjectNode plan, ObjectNode replay) {
        if (!CORE_REPLAY_SCHEMA.equals(replay.path("schema").asText()) || replay.path("version").asInt(-1) != 1
                || !plan.path("source_mode").asText().equals(replay.path("source_mode").asText())
                || !plan.path("candidate_id").asText().equals(replay.path("candidate_id").asText())
                || !replay.path("plan_sha256").asText().equals(plan.path("content_sha256").asText())
                || !plan.path("anchor_inventory_sha256").asText().equals(replay.path("anchor_inventory_sha256").asText())) {
            throw failure("staged runner output is not explicitly bound to the frozen mode, plan and anchor inventory");
        }
    }

    private static void validatePlan(ObjectNode plan) {
        verifyObjectHash(plan, "staged candidate inventory");
        String mode = plan.path("mode_id").asText();
        boolean noMacro = THREE_STAGE_NO_MACRO.equals(mode);
        if (!SCHEMA.equals(plan.path("schema").asText()) || plan.path("version").asInt(-1) != 1
                || !FAMILY.equals(plan.path("strategy_family").asText())
                || !(noMacro || THREE_STAGE_MACRO.equals(mode))
                || !plan.path("decision_anchors").isArray()
                || !JsonHashes.canonicalSha256(plan.path("decision_anchors")).equals(plan.path("anchor_inventory_sha256").asText())
                || plan.path("decision_anchor_count").asInt(-1) != plan.path("decision_anchors").size()
                || !plan.path("candidate").isObject()) {
            throw failure("staged inventory hash, family, mode or anchor list is invalid");
        }
        String expectedId = noMacro ? NO_MACRO_CANDIDATE : MACRO_CANDIDATE;
        String expectedPolicy = noMacro ? "STRUCTURE_ONLY" : "REQUIRE_MACRO_CONFIRMATION";
        if (!expectedId.equals(plan.path("candidate_id").asText())
                || !expectedId.equals(plan.path("candidate").path("candidate_id").asText())
                || !expectedPolicy.equals(plan.path("macro_gate_policy").asText())
                || !expectedPolicy.equals(plan.path("candidate").path("macro_gate_policy").asText())
                || !JsonHashes.canonicalSha256(plan.path("decision_anchors")).equals(plan.path("candidate").path("initial_pair_inventory_sha256").asText())
                || !FULL_POSITION_R_BASIS.equals(plan.path("staged_reference_r_basis").asText())
                || !FULL_POSITION_R_BASIS.equals(plan.path("candidate").path("reference_risk_basis").asText())
                || !numberEquals(plan.path("staged_position_risk_fraction"), "0.05")
                || !numberEquals(plan.path("stage_risk_fraction_sum"), "0.05")
                || !numberEquals(plan.path("candidate").path("full_position_reference_risk_fraction"), "0.05")
                || !numberEquals(plan.path("candidate").path("stage_risk_fraction_sum"), "0.05")
                || !stageRisksEqual(plan.path("stage_risk_fractions"))
                || !stageRisksEqual(plan.path("candidate").path("stage_risk_fractions"))
                || plan.path("outcome_selection_permitted").asBoolean(true)
                || plan.path("promotion_permitted").asBoolean(true)) {
            throw failure("staged plan differs from the fixed mode, risk, allocation or selection contract");
        }
        JsonNode coreLineage = plan.path("core_anchor_lineage");
        if (!coreLineage.isObject() || !isHash(coreLineage.path("core_replay_sha256").asText())
                || !isHash(coreLineage.path("core_evidence_sha256").asText())
                || !isHash(coreLineage.path("core_candidate_inventory_sha256").asText())
                || !plan.path("source_mode").asText().equals(coreLineage.path("source_mode").asText())) {
            throw failure("staged plan lost its immutable core anchor lineage");
        }
        validateAnchors(plan.path("decision_anchors"));
        if (noMacro) {
            if (plan.path("sequence").asInt(-1) != 1 || plan.has("predecessor_plan_sha256")
                    || !isHash(plan.path("predecessor_replay_sha256").asText())
                    || !plan.path("predecessor_replay_sha256").asText().equals(coreLineage.path("core_replay_sha256").asText())) {
                throw failure("no-macro plan has invalid core predecessor sequence");
            }
        } else if (plan.path("sequence").asInt(-1) != 2
                || !isHash(plan.path("predecessor_plan_sha256").asText())
                || !isHash(plan.path("predecessor_replay_sha256").asText())
                || !isHash(plan.path("predecessor_evidence_sha256").asText())
                || !ADVANCEMENT_POLICY_ID.equals(plan.path("advancement_policy_id").asText())
                || !plan.path("survival_claim").isBoolean() || plan.path("promotion_permitted").asBoolean(true)
                || (!"SURVIVES".equals(plan.path("advancement_status").asText())
                    && !"SYNTHETIC_FIXTURE_ONLY".equals(plan.path("advancement_status").asText()))
                || ("SURVIVES".equals(plan.path("advancement_status").asText())
                    && (!plan.path("survival_claim").asBoolean(false)
                        || "SYNTHETIC_DEVELOPMENT_ONLY".equals(plan.path("source_mode").asText())))
                || ("SYNTHETIC_FIXTURE_ONLY".equals(plan.path("advancement_status").asText())
                    && (plan.path("survival_claim").asBoolean(true)
                        || !"SYNTHETIC_DEVELOPMENT_ONLY".equals(plan.path("source_mode").asText())))) {
            throw failure("macro-last plan has invalid sequential predecessor/advancement lineage");
        }
        if (!Set.of("SYNTHETIC_DEVELOPMENT_ONLY", "PROXY_DISCLOSED_DEVELOPMENT_ONLY").contains(plan.path("source_mode").asText())) {
            throw failure("staged plan source mode is outside the explicitly disclosed development modes");
        }
    }

    private static void validateAnchors(JsonNode anchors) {
        Set<String> pairs = new HashSet<>();
        Instant priorDecision = null;
        String priorAsset = "", priorPair = "";
        for (JsonNode anchor : anchors) {
            String pair = text(anchor, "pair_id"), asset = text(anchor, "asset");
            Instant decision = instant(anchor, "decision_time");
            JsonNode intent = anchor.path("initial_intent");
            if (!pairs.add(pair) || !intent.isObject()
                    || !JsonHashes.ownHash(intent).equals(intent.path("content_sha256").asText())
                    || !text(anchor, "initial_intent_sha256").equals(JsonHashes.canonicalSha256(intent))
                    || !text(intent, "pair_id").equals(pair) || !text(intent, "asset").equals(asset)
                    || !text(intent, "setup_id").equals(text(anchor, "setup_id"))
                    || !text(intent, "intent_id").equals(text(anchor, "intent_id"))
                    || !text(intent, "decision_time").equals(decision.toString())
                    || !ROUTED_VARIANT.equals(intent.path("variant").asText())
                    || intent.path("stage").asInt(-1) != 1 || intent.path("diagnostic_only").asBoolean(true)
                    || !intent.path("setup_seed").isObject()
                    || !JsonHashes.ownHash(intent.path("setup_seed")).equals(intent.path("setup_seed").path("content_sha256").asText())) {
                throw failure("staged anchor has a duplicate pair or malformed initial intent/setup seed");
            }
            if (priorDecision != null) {
                int order = decision.compareTo(priorDecision);
                if (order < 0 || (order == 0 && (asset.compareTo(priorAsset) < 0
                        || asset.equals(priorAsset) && pair.compareTo(priorPair) <= 0))) {
                    throw failure("staged anchors are not in deterministic decision/asset/pair order");
                }
            }
            priorDecision = decision; priorAsset = asset; priorPair = pair;
        }
    }

    private static boolean stageRisksEqual(JsonNode values) {
        if (!values.isArray() || values.size() != STAGE_RISK.length) return false;
        for (int index = 0; index < STAGE_RISK.length; index++) {
            if (!numberEquals(values.get(index), BigDecimal.valueOf(STAGE_RISK[index]).toPlainString())) return false;
        }
        return true;
    }

    private static boolean numberEquals(JsonNode value, String expected) {
        return value != null && value.isNumber() && value.decimalValue().compareTo(new BigDecimal(expected)) == 0;
    }

    private static boolean allTrue(ObjectNode gates) {
        if (gates == null || gates.isEmpty()) return false;
        var fields = gates.fields();
        while (fields.hasNext()) {
            JsonNode value = fields.next().getValue();
            if (!value.isBoolean() || !value.asBoolean()) return false;
        }
        return true;
    }

    private static void validateBooleanGates(ObjectNode gates) {
        Set<String> names = new HashSet<>();
        var fields = gates.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getKey().isBlank() || !entry.getValue().isBoolean() || !names.add(entry.getKey())) {
                throw failure("staged advancement gates must be unique named booleans computed by the evaluator");
            }
        }
        if (!names.equals(REQUIRED_ADVANCEMENT_GATES)) {
            throw failure("staged advancement gates do not match the frozen exact gate inventory");
        }
    }

    private static void validateBlockers(ArrayNode blockers) {
        for (JsonNode row : blockers) if (!row.isTextual() || row.asText().isBlank()) {
            throw failure("staged advancement blockers must be nonempty text identifiers");
        }
    }

    private static boolean isHash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }

    private static boolean allBooleanEvidenceTrue(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) return false;
        var fields = node.fields();
        while (fields.hasNext()) {
            JsonNode value = fields.next().getValue();
            if (value.isBoolean()) {
                if (!value.asBoolean()) return false;
            } else if (!allBooleanEvidenceTrue(value)) return false;
        }
        return true;
    }

    private static boolean presentText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank();
    }

    private static void requireReasons(JsonNode row) {
        JsonNode reasons = row.path("reason_codes");
        if (!reasons.isArray() || reasons.isEmpty()) throw failure("staged zero/unresolved outcome needs explicit reason codes");
        for (JsonNode reason : reasons) if (!reason.isTextual() || reason.asText().isBlank()) {
            throw failure("staged outcome reason codes must be nonempty text");
        }
    }

    private static ArrayNode array(ObjectNode node, String field) {
        if (!node.path(field).isArray()) throw failure("staged replay requires array " + field);
        return (ArrayNode) node.path(field);
    }

    private static ObjectNode object(ObjectNode node, String field) {
        if (!node.path(field).isObject()) throw failure("staged evidence requires object " + field);
        return (ObjectNode) node.path(field);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw failure("staged artifact requires " + field);
        return value.asText();
    }

    private static Instant instant(JsonNode node, String field) {
        Instant value = optionalInstant(node, field);
        if (value == null) throw failure("staged artifact requires timestamp " + field);
        return value;
    }

    private static Instant optionalInstant(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual()) throw failure("staged timestamp must be ISO text " + field);
        try { return Instant.parse(value.asText()); }
        catch (RuntimeException invalid) { throw failure("staged timestamp is invalid " + field); }
    }

    private static void verifyObjectHash(ObjectNode node, String name) {
        Objects.requireNonNull(node, name);
        if (!JsonHashes.ownHash(node).equals(node.path("content_sha256").asText())) throw failure(name + " content hash is invalid");
    }

    private static IllegalArgumentException failure(String message) { return new IllegalArgumentException(message); }
}

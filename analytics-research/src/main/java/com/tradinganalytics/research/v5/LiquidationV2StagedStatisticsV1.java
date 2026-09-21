package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.math.BigDecimal;
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

/** Frozen, paired, development-only statistics for predecessor-anchored v002 stages. */
public final class LiquidationV2StagedStatisticsV1 {
    public static final String SCHEMA = "liquidation-v2-staged-statistics/1";
    private static final String ROUTED = "ROUTED_REVERSAL_CONTINUATION";
    private static final String CORE_ROUTED_ID = "liquidation-v2-core-routed-one-entry";
    private static final String NO_MACRO = LiquidationV2StagedCandidateInventoryV1.THREE_STAGE_NO_MACRO;
    private static final String MACRO = LiquidationV2StagedCandidateInventoryV1.THREE_STAGE_MACRO;
    private static final BigDecimal FIVE_PERCENT_R = new BigDecimal("0.05");
    private static final Set<String> REQUIRED_GATES = Set.of(
            "minimum_accepted_trades", "minimum_effective_independent_episode_count",
            "minimum_completed_positions_per_branch", "minimum_positive_outer_folds",
            "bootstrap_p20_expectancy_r_positive", "bootstrap_p20_incremental_expectancy_r_positive_vs_predecessor",
            "familywise_max_statistic_p_le_0_05_vs_predecessor", "maximum_drawdown_r", "maximum_cost_r",
            "portfolio_minimum_net_pnl", "portfolio_maximum_drawdown_pct",
            "full_position_5_percent_r_normalization", "risk_exposure_matched_attribution_reported",
            "all_anchor_outcomes_resolved", "stress_fee_slippage", "stress_funding_carry",
            "stress_adverse_execution_gap", "stress_liquidity_capacity", "stress_venue_outage_blackout");

    private LiquidationV2StagedStatisticsV1() {}

    /** Immutable evaluator output. All JSON accessors return defensive copies. */
    public record Evaluation(ObjectNode gates, ArrayNode blockers, ObjectNode statistics) {
        public Evaluation {
            gates = gates.deepCopy(); blockers = blockers.deepCopy(); statistics = statistics.deepCopy();
        }
        @Override public ObjectNode gates() { return gates.deepCopy(); }
        @Override public ArrayNode blockers() { return blockers.deepCopy(); }
        @Override public ObjectNode statistics() { return statistics.deepCopy(); }
    }

    /**
     * Recomputes the exact current-mode paired comparison. For no-macro, baselineReplay is the
     * core predecessor. For macro-last it is the exact no-macro result and the predecessor plan
     * and evidence are required. Family exposure refs come from the staged replay's immutable
     * pre-outcome receipt; a later mutable HEAD is validated by the public custody layer and is
     * deliberately not copied into this historical evidence object.
     */
    public static Evaluation recompute(ObjectNode physicalFreeze, ObjectNode plan,
            ObjectNode baselineReplay, ObjectNode stagedReplay, ObjectNode predecessorPlan,
            ObjectNode predecessorEvidence, ObjectNode familyExposureState) {
        Objects.requireNonNull(physicalFreeze, "physicalFreeze");
        Objects.requireNonNull(plan, "plan"); Objects.requireNonNull(baselineReplay, "baselineReplay");
        Objects.requireNonNull(stagedReplay, "stagedReplay");
        String mode = requiredText(plan, "mode_id");
        if (!Set.of(NO_MACRO, MACRO).contains(mode)) throw fail("unknown staged mode");
        verifyHash(plan, "staged plan"); verifyHash(baselineReplay, "baseline replay"); verifyHash(stagedReplay, "staged replay");
        // In-memory Engine output can contain DecimalNodes while the same hash-bound artifact,
        // after canonical JSON serialization and reopen, contains JCS's IEEE-754 number form.
        // Normalize every statistics input through those canonical bytes before arithmetic so
        // evidence is identical whether it is derived in-process or from a reopened artifact.
        physicalFreeze = canonicalRoundTrip(physicalFreeze, "physical freeze");
        plan = canonicalRoundTrip(plan, "staged plan");
        baselineReplay = canonicalRoundTrip(baselineReplay, "baseline replay");
        stagedReplay = canonicalRoundTrip(stagedReplay, "staged replay");
        if (predecessorPlan != null) predecessorPlan = canonicalRoundTrip(predecessorPlan, "predecessor plan");
        if (predecessorEvidence != null) predecessorEvidence = canonicalRoundTrip(predecessorEvidence, "predecessor evidence");
        if (familyExposureState != null) familyExposureState = canonicalRoundTrip(familyExposureState, "family exposure state");
        if (!requiredText(plan, "content_sha256").equals(stagedReplay.path("plan_sha256").asText())
                || !requiredText(plan, "content_sha256").equals(stagedReplay.path("staged_plan").path("content_sha256").asText())
                || !requiredText(plan, "candidate_id").equals(stagedReplay.path("candidate_id").asText())
                || !mode.equals(stagedReplay.path("mode_id").asText())
                || !requiredText(plan, "source_mode").equals(stagedReplay.path("source_mode").asText())) {
            throw fail("staged replay is detached from its exact plan, candidate, mode, or source mode");
        }
        if (!requiredText(plan, "predecessor_replay_sha256").equals(baselineReplay.path("content_sha256").asText())) {
            throw fail("staged baseline replay differs from the exact frozen predecessor result");
        }
        ObjectNode profile = object(physicalFreeze, "profile");
        LiquidationDailyStressProfileV1.validate(profile);
        if (NO_MACRO.equals(mode)) {
            if (predecessorPlan != null || predecessorEvidence != null
                    || !"liquidation-v2-replay-result/1".equals(baselineReplay.path("schema").asText())) {
                throw fail("no-macro statistics require the bound core predecessor and no staged predecessor artifacts");
            }
        } else {
            if (predecessorPlan == null || predecessorEvidence == null) throw fail("macro-last statistics require the exact no-macro plan and evidence");
            verifyHash(predecessorPlan, "no-macro predecessor plan"); verifyHash(predecessorEvidence, "no-macro predecessor evidence");
            if (!requiredText(predecessorPlan, "content_sha256").equals(plan.path("predecessor_plan_sha256").asText())
                    || !requiredText(predecessorEvidence, "content_sha256").equals(plan.path("predecessor_evidence_sha256").asText())
                    || !requiredText(baselineReplay, "content_sha256").equals(plan.path("predecessor_replay_sha256").asText())
                    || !requiredText(predecessorPlan, "anchor_inventory_sha256").equals(plan.path("anchor_inventory_sha256").asText())
                    || !requiredText(predecessorEvidence, "replay_result_sha256").equals(baselineReplay.path("content_sha256").asText())
                    || !NO_MACRO.equals(predecessorPlan.path("mode_id").asText())) {
                throw fail("macro-last comparison does not bind its exact no-macro predecessor and common anchors");
            }
        }
        ArrayNode anchors = LiquidationV2StagedCandidateInventoryV1.anchors(plan);
        ArrayNode stagedRows = array(stagedReplay, "opportunities");
        ObjectNode validation = LiquidationV2StagedCandidateInventoryV1.validateAnchoredOutcomes(plan, mode, stagedRows);
        Map<String, JsonNode> baselineByPair = baselineRows(baselineReplay, anchors, mode);
        Map<String, JsonNode> stageByPair = indexByPair(stagedRows);
        ArrayList<Pair> pairs = new ArrayList<>();
        ArrayList<LiquidationV2EvidenceMathV1.MarketInterval> intervals = new ArrayList<>();
        ArrayList<LiquidationChronologicalPolicyV1.Observation> observations = new ArrayList<>();
        ArrayNode pairedOutcomeInventory = JsonHashes.mapper().createArrayNode();
        Map<String, Double> stagedRById = new HashMap<>(), deltaRById = new HashMap<>();
        Set<String> resolvedPairIds = new HashSet<>();
        int unresolved = 0, coverageBlocked = 0, closed = 0, longCount = 0, shortCount = 0;
        double stageDollars = 0.0, baselineDollars = 0.0;
        double fullRiskSum = 0.0, baselineInitialRisk = 0.0;
        List<TradeR> stagedTrades = new ArrayList<>();
        List<Double> positionCostR = new ArrayList<>();
        boolean fullRiskValid = true, costComplete = true;

        for (JsonNode anchor : anchors) {
            String pairId = requiredText(anchor, "pair_id");
            JsonNode b = baselineByPair.get(pairId), s = stageByPair.get(pairId);
            if (b == null || s == null) throw fail("paired inventory is missing a predecessor or staged anchor row");
            verifyAnchorIdentity(anchor, b, mode.equals(NO_MACRO));
            verifyAnchorIdentity(anchor, s, false);
            String state = requiredText(s, "outcome_state");
            String baselineState = requiredText(b, "outcome_state");
            boolean stageResolved = resolved(state);
            boolean baselineResolved = resolved(baselineState);
            if (stageResolved) validateResolvedOutcome(s, state);
            if (baselineResolved) validateResolvedOutcome(b, baselineState);
            boolean resolved = stageResolved && baselineResolved;
            if (!resolved) {
                unresolved++;
                if ("COVERAGE_BLOCKED".equals(state) || "COVERAGE_BLOCKED".equals(baselineState)) coverageBlocked++;
            }
            JsonNode risk = s.path("full_position_reference_risk_usdt");
            if (s.path("first_fill_time").isTextual()) {
                JsonNode equity = s.path("first_fill_reference_equity_usdt");
                JsonNode referenceRisk = s.path("reference_risk_usdt");
                if (!equity.isNumber() || !risk.isNumber() || !referenceRisk.isNumber()
                        || !LiquidationV2EvidenceMathV1.scaledRoundedBinary64IntervalsOverlap(
                                equity.asDouble(), risk.asDouble(), FIVE_PERCENT_R)
                        || !LiquidationV2EvidenceMathV1.scaledRoundedBinary64IntervalsOverlap(
                                equity.asDouble(), referenceRisk.asDouble(), FIVE_PERCENT_R)
                        || !LiquidationV2EvidenceMathV1.roundedBinary64IntervalsOverlap(
                                risk.asDouble(), referenceRisk.asDouble())
                        || !LiquidationV2StagedCandidateInventoryV1.FULL_POSITION_R_BASIS.equals(s.path("full_position_reference_risk_basis").asText())) {
                    fullRiskValid = false;
                } else {
                    fullRiskSum += risk.asDouble();
                }
            }
            double sR = resolved ? normalizedR(s, false) : Double.NaN;
            double bR = resolved ? normalizedR(b, mode.equals(NO_MACRO)) : Double.NaN;
            if (resolved) {
                resolvedPairIds.add(pairId);
                stagedRById.put(pairId, sR); deltaRById.put(pairId, sR - bR);
                stageDollars += pnl(s); baselineDollars += pnl(b);
                pairs.add(new Pair(pairId, requiredText(anchor, "asset"), Instant.parse(requiredText(anchor, "decision_time")),
                        optionalInstant(s, "first_fill_time"), optionalInstant(s, "exit_time"), sR, bR,
                        requiredText(anchor, "branch"), state));
                if ("CLOSED_TRADE".equals(state)) {
                    closed++;
                    if ("CONTINUATION".equals(requiredText(anchor, "branch"))) longCount++;
                    else if ("REVERSAL".equals(requiredText(anchor, "branch"))) shortCount++;
                    stagedTrades.add(new TradeR(pairId, optionalInstant(s, "exit_time"), sR));
                    Double costR = positionCostR(stagedReplay, s);
                    if (costR == null) costComplete = false; else positionCostR.add(costR);
                }
                if ("CLOSED_TRADE".equals(b.path("outcome_state").asText())) {
                    baselineInitialRisk += number(b, "reference_risk_usdt");
                }
            }
            Instant decision = Instant.parse(requiredText(anchor, "decision_time"));
            Instant first = optionalInstant(s, "first_fill_time"), exit = optionalInstant(s, "exit_time");
            Instant baselineFirst = optionalInstant(b, "first_fill_time"), baselineExit = optionalInstant(b, "exit_time");
            Instant intervalFirst = earliest(first, baselineFirst);
            boolean eitherStillOpen = (first != null && exit == null) || (baselineFirst != null && baselineExit == null);
            Instant intervalExit = resolved && !eitherStillOpen ? latestNonNull(exit, baselineExit) : null;
            intervals.add(new LiquidationV2EvidenceMathV1.MarketInterval(pairId,
                    requiredText(anchor, "asset"), decision, intervalFirst, intervalExit));
            Instant resolution = resolved ? latestNonNull(optionalInstant(s, "outcome_available_time"),
                    optionalInstant(b, "outcome_available_time")) : null;
            LiquidationChronologicalPolicyV1.Observation observation;
            if (resolved && intervalFirst != null) observation = LiquidationChronologicalPolicyV1.Observation.pairedCompleted(
                    pairId, decision, intervalFirst, Objects.requireNonNull(intervalExit), Objects.requireNonNull(resolution));
            else if (resolved) observation = LiquidationChronologicalPolicyV1.Observation.resolvedNoTrade(
                    pairId, decision, Objects.requireNonNull(resolution));
            else if (intervalFirst != null) observation = LiquidationChronologicalPolicyV1.Observation.openTrade(pairId, decision, intervalFirst);
            else observation = LiquidationChronologicalPolicyV1.Observation.unresolved(pairId, decision);
            observations.add(observation);
            ObjectNode pairedAudit = pairedOutcomeInventory.addObject().put("pair_id", pairId)
                    .put("decision_time", decision.toString()).put("staged_outcome_state", state)
                    .put("predecessor_outcome_state", baselineState)
                    .put("joint_resolution_state", resolved ? "RESOLVED"
                            : intervalFirst == null ? "UNRESOLVED_NO_FILL" : "OPEN_UNRESOLVED");
            putInstant(pairedAudit, "union_first_fill_time", intervalFirst);
            putInstant(pairedAudit, "union_final_exit_time", intervalExit);
            putInstant(pairedAudit, "joint_outcome_available_time", resolution);
        }

        List<LiquidationV2EvidenceMathV1.MarketInterval> completeIntervals = intervals.stream()
                .filter(interval -> resolvedPairIds.contains(interval.id())
                        && interval.firstFillTime() != null && interval.exitTime() != null).toList();
        int independent = LiquidationV2EvidenceMathV1.independentCompletedEpisodes(completeIntervals,
                LiquidationChronologicalPolicyV1.MINIMUM_BLOCK_DAYS);
        int possibleIndependent = maximumPossibleComponents(profile);
        List<LiquidationChronologicalPolicyV1.MarketTimeBlock> blocks = LiquidationV2EvidenceMathV1.marketTimeBlocks(profile, intervals);
        LiquidationChronologicalPolicyV1.SynchronizedBlockSample draws = LiquidationChronologicalPolicyV1.sampleSynchronizedBlocks(profile, blocks);
        boolean complete = unresolved == 0 && pairs.size() == anchors.size();
        Bootstrap bootstrap = pairedBootstrap(draws, blocks, stagedRById, deltaRById, complete);
        LiquidationChronologicalPolicyV1.Inventory foldInventory = LiquidationChronologicalPolicyV1.buildInventory(profile, observations);
        int positiveFolds = positiveOuterFolds(foldInventory, stageByPair, resolvedPairIds);
        double maxDdR = maxDrawdownR(stagedTrades);
        Double averageCostR = costComplete && !positionCostR.isEmpty()
                ? LiquidationV2EvidenceMathV1.meanPositionCostR(positionCostR) : null;
        JsonNode path = accountPath(stagedReplay);
        double portfolioNet = path == null ? Double.NaN : decimal(path, "ending_marked_equity_usdt").subtract(decimal(path, "starting_equity_usdt")).doubleValue();
        double portfolioDd = path == null ? Double.NaN : number(path, "maximum_adverse_mark_drawdown_fraction");
        Double plannedRiskAllowance = plannedRiskAllowance(stagedReplay);
        FillExposure fillExposure = actualFillExposureByAsset(stagedReplay);
        Attribution attribution = attribution(stagedReplay, pairs, fullRiskSum, plannedRiskAllowance, fillExposure, baselineInitialRisk,
                stageDollars, baselineDollars);
        ObjectNode exposure = immutableExposureRefs(stagedReplay);
        if (familyExposureState != null) verifyExposureState(familyExposureState, exposure);

        ObjectNode gates = JsonHashes.mapper().createObjectNode();
        gates.put("minimum_accepted_trades", closed >= 60);
        gates.put("minimum_effective_independent_episode_count", independent >= 30);
        gates.put("minimum_completed_positions_per_branch", longCount >= 20 && shortCount >= 20);
        gates.put("minimum_positive_outer_folds", positiveFolds >= 5);
        gates.put("bootstrap_p20_expectancy_r_positive", complete && bootstrap.netP20() != null && bootstrap.netP20() > 0.0);
        gates.put("bootstrap_p20_incremental_expectancy_r_positive_vs_predecessor",
                complete && bootstrap.deltaP20() != null && bootstrap.deltaP20() > 0.0);
        gates.put("familywise_max_statistic_p_le_0_05_vs_predecessor",
                complete && bootstrap.localAdjustedP() != null && bootstrap.localAdjustedP() <= 0.05);
        gates.put("maximum_drawdown_r", !stagedTrades.isEmpty() && Double.isFinite(maxDdR) && Math.abs(maxDdR) <= 20.0);
        gates.put("maximum_cost_r", averageCostR != null && averageCostR <= 0.5);
        gates.put("portfolio_minimum_net_pnl", Double.isFinite(portfolioNet) && portfolioNet > 0.0);
        gates.put("portfolio_maximum_drawdown_pct", Double.isFinite(portfolioDd) && portfolioDd * 100.0 <= 30.0);
        gates.put("full_position_5_percent_r_normalization", fullRiskValid && closed > 0);
        gates.put("risk_exposure_matched_attribution_reported", attribution.complete());
        gates.put("all_anchor_outcomes_resolved", complete);
        for (String stress : List.of("fee_slippage", "funding_carry", "adverse_execution_gap", "liquidity_capacity", "venue_outage_blackout")) {
            gates.put("stress_" + stress, stagedStressPass(stagedReplay, plan, stress, physicalFreeze));
        }
        validateGateInventory(gates);

        ArrayNode blockers = JsonHashes.mapper().createArrayNode();
        if (closed < 60) blockers.add("MINIMUM_60_COMPLETED_POSITIONS_NOT_MET");
        if (independent < 30) blockers.add("MINIMUM_30_COMPLETED_67D_DEPENDENCE_COMPONENTS_NOT_MET");
        if (possibleIndependent < 30) blockers.add("FROZEN_WINDOW_MAX_67D_COMPONENTS_BELOW_30:" + possibleIndependent);
        if (longCount < 20 || shortCount < 20) blockers.add("MINIMUM_20_COMPLETED_POSITIONS_PER_BRANCH_NOT_MET");
        if (positiveFolds < 5) blockers.add("MINIMUM_5_POSITIVE_OUTER_FOLDS_OF_8_NOT_MET");
        if (!complete) blockers.add("UNRESOLVED_OPEN_OR_COVERAGE_BLOCKED_ANCHORS_RETAINED_AND_BLOCK_ADVANCEMENT");
        if (bootstrap.netP20() == null) blockers.add("NET_EXPECTANCY_BOOTSTRAP_UNAVAILABLE_INCOMPLETE_OR_EMPTY_SAMPLE");
        if (bootstrap.deltaP20() == null) blockers.add("PAIRED_PREDECESSOR_BOOTSTRAP_UNAVAILABLE_INCOMPLETE_OR_EMPTY_SAMPLE");
        if (bootstrap.localAdjustedP() == null) blockers.add("LOCAL_PREDECESSOR_MAXSTAT_UNAVAILABLE_INCOMPLETE_OR_EMPTY_SAMPLE");
        if (familyExposureUnknown(familyExposureState)) blockers.add("HISTORICAL_FAMILY_K_UNKNOWN; LOCAL_PREDECESSOR_TEST_IS_NOT_COMPLETE_FAMILYWISE_CONTROL");
        if (stagedTrades.isEmpty()) blockers.add("NO_COMPLETED_STAGED_POSITIONS_FOR_DRAWDOWN_OR_COST_STATISTICS");
        if (averageCostR == null) blockers.add("POSITION_COST_R_INCOMPLETE_REQUIRED_FEE_SLIPPAGE_OR_FUNDING_COST_ROW_MISSING");
        if (path == null) blockers.add("HASH_BOUND_ADVERSE_INTRATRADE_ACCOUNT_PATH_UNAVAILABLE");
        if (!attribution.complete()) blockers.add("RISK_EXPOSURE_ATTRIBUTION_INCOMPLETE");
        if (!fullRiskValid) blockers.add("FILLED_POSITION_5_PERCENT_REFERENCE_R_VALIDATION_FAILED");
        if (!stagedReplay.path("stress_evaluation").isObject()) blockers.add("STAGED_STRESS_SCENARIO_RUNS_NOT_PRESENT");
        for (String stress : List.of("fee_slippage", "funding_carry", "adverse_execution_gap", "liquidity_capacity", "venue_outage_blackout")) {
            if (!gates.path("stress_" + stress).asBoolean()) blockers.add("STRESS_GATE_NOT_MET_OR_MISSING:" + stress);
        }

        ObjectNode stats = JsonHashes.mapper().createObjectNode().put("schema", SCHEMA).put("version", 1)
                .put("mode_id", mode).put("candidate_id", requiredText(plan, "candidate_id"))
                .put("statistics_scope", "POOLED_DEVELOPMENT;PREDECESSOR_PAIRED;NOT_OOS_OR_PROMOTION_EVIDENCE")
                .put("baseline_kind", NO_MACRO.equals(mode) ? "CORE_ONE_ENTRY_1_PERCENT_RISK" : "THREE_STAGE_NO_MACRO_5_PERCENT_RISK")
                .put("comparison_basis", NO_MACRO.equals(mode)
                        ? "CORE_HEADLINE_1_PERCENT_INITIAL_TRANCHE_R_VS_STAGED_FULL_POSITION_5_PERCENT_R;DOLLAR_UTILIZATION_ATTRIBUTED_SEPARATELY"
                        : "BOTH_ARMS_FULL_POSITION_5_PERCENT_FIRST_FILL_EQUITY_R")
                .put("anchor_count", anchors.size()).put("resolved_anchor_count", pairs.size())
                .put("unresolved_count", unresolved).put("coverage_blocked_count", coverageBlocked)
                .put("completed_position_count", closed).put("continuation_completed_count", longCount)
                .put("reversal_completed_count", shortCount).put("effective_67d_dependence_components", independent)
                .put("maximum_possible_67d_components_in_frozen_window", possibleIndependent)
                .put("outer_fold_count", 8).put("positive_outer_fold_count", positiveFolds)
                .put("block_days", LiquidationChronologicalPolicyV1.MINIMUM_BLOCK_DAYS)
                .put("bootstrap_draw_count", LiquidationChronologicalPolicyV1.DEFAULT_DRAWS)
                .put("bootstrap_seed", LiquidationChronologicalPolicyV1.DEFAULT_SEED)
                .put("cumulative_family_maxstat_available", false)
                .put("local_predecessor_test_scope", "ONE_FROZEN_PLANNED_CONTRAST;NOT_COMPLETE_HISTORICAL_FAMILYWISE_ADJUSTMENT")
                .putNull("historical_cumulative_k")
                .put("historical_cumulative_k_status", "UNKNOWN_NO_VERIFIED_CANONICAL_PREDECESSOR_EXPOSURE_INVENTORY")
                .put("signed_completed_position_net_r_drawdown", Double.isFinite(maxDdR) ? maxDdR : 0.0)
                .put("maximum_cost_r_scope", "MEAN_PER_COMPLETED_POSITION:(ENTRY_COSTS+EXIT_COSTS+POSITIVE_FUNDING_DEBITS)/POSITION_REFERENCE_RISK");
        stats.put("full_position_risk_numeric_representation",
                LiquidationV2EvidenceMathV1.FULL_POSITION_RISK_NUMERIC_REPRESENTATION);
        ObjectNode selectionDiagnostics = stats.putObject("overfit_selection_diagnostics")
                .put("selection_search_performed", false)
                .put("pbo_status", "NOT_APPLICABLE_FIXED_PREDECLARED_NO_SELECTION")
                .putNull("pbo_value")
                .put("pbo_reason", "FIXED_PREDECLARED_SEQUENTIAL_COMPARISONS_HAVE_NO_WINNER_SELECTION_MATRIX")
                .put("dsr_status", "UNAVAILABLE_NO_FROZEN_DSR_ESTIMATE")
                .putNull("dsr_value");
        selectionDiagnostics.put("promotion_permitted", false);
        stats.put("observed_incremental_mean_r_vs_predecessor", bootstrap.observedDelta());
        if (bootstrap.netP20() == null) stats.putNull("expectancy_p20_r"); else stats.put("expectancy_p20_r", bootstrap.netP20());
        if (bootstrap.deltaP20() == null) stats.putNull("incremental_p20_vs_predecessor_r"); else stats.put("incremental_p20_vs_predecessor_r", bootstrap.deltaP20());
        if (bootstrap.localAdjustedP() == null) stats.putNull("local_predecessor_centered_maxstat_p_value"); else stats.put("local_predecessor_centered_maxstat_p_value", bootstrap.localAdjustedP());
        if (averageCostR == null) stats.putNull("mean_position_cost_r"); else stats.put("mean_position_cost_r", averageCostR);
        if (path == null) { stats.putNull("portfolio_net_pnl_usdt"); stats.putNull("adverse_mark_drawdown_fraction"); }
        else { stats.put("portfolio_net_pnl_usdt", portfolioNet).put("adverse_mark_drawdown_fraction", portfolioDd); }
        if (attribution.object() != null) stats.set("risk_exposure_attribution", attribution.object());
        stats.set("outcome_validation", validation);
        stats.set("paired_outcome_inventory", pairedOutcomeInventory);
        stats.set("outer_folds", outerFoldRows(foldInventory, stagedRows, resolvedPairIds));
        stats.set("chronological_inventory", foldInventory.toJson());
        stats.set("market_time_blocks", blockRows(blocks));
        stats.set("synchronized_block_indices", draws.toJson());
        stats.set("pre_outcome_exposure_attempt_refs", exposure);
        stats.put("content_sha256", JsonHashes.ownHash(stats));
        return new Evaluation(gates, blockers, stats);
    }

    private static Map<String, JsonNode> baselineRows(ObjectNode replay, ArrayNode anchors, String mode) {
        JsonNode rows = replay.path("opportunities");
        if (!rows.isArray()) throw fail("baseline replay lacks opportunities");
        Map<String, JsonNode> result = new HashMap<>();
        for (JsonNode row : rows) {
            boolean relevant = NO_MACRO.equals(mode)
                    ? CORE_ROUTED_ID.equals(row.path("candidate_id").asText())
                    : requiredText(planCandidateForMode(mode), "candidate_id").equals(row.path("candidate_id").asText());
            if (!relevant) continue;
            String pair = requiredText(row, "pair_id");
            if (result.putIfAbsent(pair, row) != null) throw fail("baseline has duplicate decision pairs");
        }
        if (result.size() != anchors.size()) throw fail("baseline opportunity inventory differs from frozen anchors");
        return result;
    }

    // Candidate identity is checked against exact replay/plan hashes by the caller; use the fixed
    // sequential predecessor ID here rather than searching data-derived variants.
    private static ObjectNode planCandidateForMode(String mode) {
        return JsonHashes.mapper().createObjectNode().put("candidate_id", NO_MACRO.equals(mode)
                ? CORE_ROUTED_ID : LiquidationV2StagedCandidateInventoryV1.NO_MACRO_CANDIDATE);
    }

    private static void verifyAnchorIdentity(JsonNode anchor, JsonNode row, boolean core) {
        if (!requiredText(anchor, "pair_id").equals(row.path("pair_id").asText())
                || !requiredText(anchor, "asset").equals(row.path("asset").asText())
                || !requiredText(anchor, "decision_time").equals(row.path("decision_time").asText())
                || !requiredText(anchor, "branch").equals(row.path("branch").asText())
                || !requiredText(anchor, "direction").equals(row.path("direction").asText())) {
            throw fail("baseline pair does not preserve frozen decision time, asset, branch, and direction");
        }
        if (core && (!ROUTED.equals(row.path("variant").asText()) || row.path("stage").asInt(-1) != 1)) {
            throw fail("core predecessor anchor must be a stage-one routed row");
        }
        String expectedIntentHash = requiredText(anchor, "initial_intent_sha256");
        if (!expectedIntentHash.equals(row.path("initial_intent_sha256").asText())
                || !row.path("initial_intent").isObject()
                || !expectedIntentHash.equals(JsonHashes.canonicalSha256(row.path("initial_intent")))
                || !expectedIntentHash.equals(JsonHashes.canonicalSha256(anchor.path("initial_intent")))) {
            throw fail("paired result does not preserve exact frozen initial intent geometry");
        }
    }

    private static Map<String, JsonNode> indexByPair(JsonNode rows) {
        if (!rows.isArray()) throw fail("staged replay opportunities must be an array");
        Map<String, JsonNode> result = new HashMap<>();
        for (JsonNode row : rows) if (ROUTED.equals(row.path("variant").asText())) {
            if (result.putIfAbsent(requiredText(row, "pair_id"), row) != null) throw fail("staged replay repeats an anchor pair");
        }
        return result;
    }

    static double normalizedR(JsonNode row, boolean core) {
        if ("RESOLVED_NO_TRADE".equals(row.path("outcome_state").asText())) return 0.0;
        JsonNode pnl = row.path("net_pnl_usdt"), risk = core ? row.path("reference_risk_usdt")
                : row.path("full_position_reference_risk_usdt");
        if (!pnl.isNumber() || !risk.isNumber() || risk.decimalValue().signum() <= 0) {
            throw fail("resolved position lacks its frozen risk denominator for staged statistics");
        }
        double value = pnl.asDouble() / risk.asDouble();
        if (!Double.isFinite(value)) throw fail("normalized net-R value is nonfinite");
        return value;
    }

    private static void validateResolvedOutcome(JsonNode row, String state) {
        Instant decision = Instant.parse(requiredText(row, "decision_time"));
        Instant available = optionalInstant(row, "outcome_available_time");
        if ("RESOLVED_NO_TRADE".equals(state)) {
            if (available == null || available.isBefore(decision) || optionalInstant(row, "first_fill_time") != null
                    || optionalInstant(row, "exit_time") != null || !finite(row.path("net_pnl_usdt"))
                    || row.path("net_pnl_usdt").decimalValue().signum() != 0) {
                throw fail("paired resolved no-trade outcome is missing a valid zero and availability time");
            }
            return;
        }
        Instant fill = optionalInstant(row, "first_fill_time"), exit = optionalInstant(row, "exit_time");
        if (fill == null || !fill.isAfter(decision) || exit == null || exit.isBefore(fill)
                || available == null || available.isBefore(exit) || !finite(row.path("net_pnl_usdt"))
                || !finite(row.path("reference_risk_usdt")) || row.path("reference_risk_usdt").decimalValue().signum() <= 0) {
            throw fail("paired closed-trade outcome is missing valid fill, exit, availability, PnL, or risk");
        }
    }

    private static void putInstant(ObjectNode row, String field, Instant time) {
        if (time == null) row.putNull(field); else row.put(field, time.toString());
    }

    private static double pnl(JsonNode row) {
        if ("RESOLVED_NO_TRADE".equals(row.path("outcome_state").asText())) return 0.0;
        JsonNode value = row.path("net_pnl_usdt");
        if (!value.isNumber() || !Double.isFinite(value.asDouble())) throw fail("resolved paired position is missing finite net PnL");
        return value.asDouble();
    }

    private static Double positionCostR(ObjectNode replay, JsonNode row) {
        JsonNode risk = row.path("full_position_reference_risk_usdt");
        JsonNode episode = ledgerEpisode(replay, row);
        return !risk.isNumber() ? null : LiquidationV2EvidenceMathV1.positionCostR(episode, risk.decimalValue());
    }

    private static JsonNode ledgerEpisode(ObjectNode replay, JsonNode opportunity) {
        JsonNode accounts = replay.path("ledger").path("accounts");
        if (!accounts.isArray()) return null;
        String candidate = opportunity.path("candidate_id").asText();
        for (JsonNode accountRow : accounts) {
            if (!candidate.equals(accountRow.path("candidate_id").asText())) continue;
            JsonNode episodes = accountRow.path("account").path("closed_episodes");
            JsonNode positions = accountRow.path("account").path("positions");
            if (!episodes.isArray() || !positions.isArray()) return null;
            long firstFill = Instant.parse(opportunity.path("first_fill_time").asText()).toEpochMilli();
            String setup = opportunity.path("setup_id").asText();
            for (JsonNode episode : episodes) if (setup.equals(episode.path("active_setup_id").asText())
                    && opportunity.path("asset").asText().equals(episode.path("asset").asText())
                    && firstFill == episode.path("first_fill_time").asLong(Long.MIN_VALUE)) return episode;
            for (JsonNode position : positions) if ("CLOSED".equals(position.path("status").asText())
                    && position.path("exits").isArray() && !position.path("exits").isEmpty()
                    && setup.equals(position.path("active_setup_id").asText())
                    && opportunity.path("asset").asText().equals(position.path("asset").asText())
                    && firstFill == position.path("first_fill_time").asLong(Long.MIN_VALUE)) return position;
        }
        return null;
    }

    /** Sums frozen risk allowances only; this is not a claim about actual partial-fill exposure. */
    private static Double plannedRiskAllowance(ObjectNode replay) {
        double risk = 0;
        for (JsonNode row : replay.path("opportunities")) {
            if (!ROUTED.equals(row.path("variant").asText()) || !row.path("first_fill_time").isTextual()) continue;
            if (!finite(row.path("full_position_reference_risk_usdt"))) return null;
            risk += row.path("full_position_reference_risk_usdt").asDouble() * .2;
        }
        for (JsonNode attempt : replay.path("stage_attempts")) if ("STAGE_FILLED".equals(attempt.path("outcome_state").asText())) {
            if (!finite(attempt.path("planned_tranche_risk_usdt"))) return null;
            risk += attempt.path("planned_tranche_risk_usdt").asDouble();
        }
        return Double.isFinite(risk) ? risk : null;
    }

    /** Retains actual fill quantity/notional by asset instead of calling planned risk actual exposure. */
    private static FillExposure actualFillExposureByAsset(ObjectNode replay) {
        Map<String, FillAmount> amounts = new java.util.TreeMap<>();
        int fills = 0;
        for (JsonNode row : replay.path("opportunities")) {
            if (!ROUTED.equals(row.path("variant").asText()) || !row.path("first_fill_time").isTextual()) continue;
            if (!finite(row.path("filled_quantity")) || !finite(row.path("fill_price"))) return new FillExposure(fills, amounts, false);
            addFill(amounts, row.path("asset").asText(), row.path("filled_quantity").asDouble(), row.path("fill_price").asDouble());
            fills++;
        }
        for (JsonNode attempt : replay.path("stage_attempts")) if ("STAGE_FILLED".equals(attempt.path("outcome_state").asText())) {
            if (!finite(attempt.path("filled_quantity")) || !finite(attempt.path("fill_price"))) return new FillExposure(fills, amounts, false);
            addFill(amounts, attempt.path("asset").asText(), attempt.path("filled_quantity").asDouble(), attempt.path("fill_price").asDouble());
            fills++;
        }
        return new FillExposure(fills, amounts, true);
    }

    private static void addFill(Map<String, FillAmount> amounts, String asset, double quantity, double price) {
        if (asset == null || asset.isBlank() || !Double.isFinite(quantity) || quantity <= 0
                || !Double.isFinite(price) || price <= 0) throw fail("actual staged fill attribution is malformed");
        FillAmount prior = amounts.getOrDefault(asset, new FillAmount(0, 0));
        amounts.put(asset, new FillAmount(prior.quantity() + quantity, prior.notional() + quantity * price));
    }

    private static double maxDrawdownR(List<TradeR> trades) {
        List<Double> ordered = trades.stream().sorted(Comparator.comparing(TradeR::exit).thenComparing(TradeR::pairId))
                .map(TradeR::netR).toList();
        return LiquidationV2EvidenceMathV1.cumulativeTradeDrawdownR(ordered);
    }

    private static Bootstrap pairedBootstrap(LiquidationChronologicalPolicyV1.SynchronizedBlockSample draws,
            List<LiquidationChronologicalPolicyV1.MarketTimeBlock> blocks, Map<String, Double> stage,
            Map<String, Double> delta, boolean complete) {
        if (!complete || stage.isEmpty() || !stage.keySet().equals(delta.keySet())) return new Bootstrap(null, null, null, 0, 0);
        List<Double> net = LiquidationV2EvidenceMathV1.synchronizedBlockMeans(draws, blocks, stage);
        List<Double> diff = LiquidationV2EvidenceMathV1.synchronizedBlockMeans(draws, blocks, delta);
        double observed = LiquidationV2EvidenceMathV1.mean(delta.values());
        Map<String, Double> centered = new HashMap<>();
        delta.forEach((id, value) -> centered.put(id, value - observed));
        List<Double> nullDraws = LiquidationV2EvidenceMathV1.synchronizedBlockMeans(draws, blocks, centered);
        double p = LiquidationV2EvidenceMathV1.adjustedPValue(nullDraws, observed);
        return new Bootstrap(LiquidationV2EvidenceMathV1.percentile20(net),
                LiquidationV2EvidenceMathV1.percentile20(diff), p, observed, net.size());
    }

    private static int maximumPossibleComponents(ObjectNode profile) {
        Instant start = Instant.parse(profile.path("windows").path("decision_start").asText());
        Instant end = Instant.parse(profile.path("windows").path("decision_end_exclusive").asText());
        return Math.toIntExact(Duration.between(start, end).minusNanos(1)
                .dividedBy(Duration.ofDays(LiquidationChronologicalPolicyV1.MINIMUM_BLOCK_DAYS)) + 1);
    }

    private static int positiveOuterFolds(LiquidationChronologicalPolicyV1.Inventory inventory,
            Map<String, JsonNode> stagedRows, Set<String> jointlyResolvedPairs) {
        int positive = 0;
        for (LiquidationChronologicalPolicyV1.Fold fold : inventory.folds()) {
            double pnl = 0;
            for (String id : fold.testIds()) {
                JsonNode row = jointlyResolvedPairs.contains(id) ? stagedRows.get(id) : null;
                if (row != null && "CLOSED_TRADE".equals(row.path("outcome_state").asText())) pnl += pnl(row);
            }
            if (pnl > 0.0) positive++;
        }
        return positive;
    }

    private static ArrayNode outerFoldRows(LiquidationChronologicalPolicyV1.Inventory inventory, ArrayNode rows,
            Set<String> jointlyResolvedPairs) {
        Map<String, JsonNode> byPair = indexByPair(rows);
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        for (LiquidationChronologicalPolicyV1.Fold fold : inventory.folds()) {
            int completed = 0; double pnl = 0;
            for (String id : fold.testIds()) {
                JsonNode row = jointlyResolvedPairs.contains(id) ? byPair.get(id) : null;
                if (row != null && "CLOSED_TRADE".equals(row.path("outcome_state").asText())) { completed++; pnl += pnl(row); }
            }
            result.addObject().put("fold_id", fold.foldId()).put("test_decision_count", fold.testIds().size())
                    .put("completed_positions", completed).put("net_pnl_usdt", pnl).put("positive", pnl > 0)
                    .put("status", fold.testIds().isEmpty() ? "NO_RETAINED_TEST_OUTCOMES" : "RETAINED_TEST_OUTCOMES");
        }
        return result;
    }

    private static ArrayNode blockRows(List<LiquidationChronologicalPolicyV1.MarketTimeBlock> blocks) {
        ArrayNode rows = JsonHashes.mapper().createArrayNode();
        for (LiquidationChronologicalPolicyV1.MarketTimeBlock block : blocks) {
            ObjectNode row = rows.addObject().put("block_id", block.blockId())
                    .put("start_inclusive", block.startInclusive().toString()).put("end_exclusive", block.endExclusive().toString())
                    .put("observation_count", block.synchronizedObservationIds().size());
            ArrayNode ids = row.putArray("synchronized_observation_ids");
            block.synchronizedObservationIds().forEach(ids::add);
        }
        return rows;
    }

    private static ObjectNode immutableExposureRefs(ObjectNode replay) {
        JsonNode refs = replay.path("pre_outcome_exposure_attempts");
        if (!refs.isObject() || !"liquidation-v2-pre-outcome-exposure-refs/1".equals(refs.path("schema").asText())
                || !JsonHashes.ownHash(refs).equals(refs.path("content_sha256").asText())
                || !refs.path("attempts").isArray() || refs.path("attempt_count").asInt(-1) != refs.path("attempts").size()) {
            throw fail("staged replay must retain its immutable hash-bound pre-outcome exposure-attempt receipt");
        }
        Set<String> unique = new HashSet<>();
        for (JsonNode row : refs.path("attempts")) {
            String identity = requiredText(row, "candidate_id") + "|" + requiredText(row, "attempt_freeze_sha256");
            if (!unique.add(identity) || !isHash(row.path("physical_freeze_sha256").asText())
                    || !isHash(row.path("behavior_sha256").asText())) throw fail("pre-outcome exposure refs contain duplicate or malformed identities");
        }
        return (ObjectNode) refs.deepCopy();
    }

    private static void verifyExposureState(ObjectNode state, ObjectNode refs) {
        verifyHash(state, "durable family exposure state");
        if (!LiquidationV2FamilyExposureAttemptV1.STATE_SCHEMA.equals(state.path("schema").asText())) {
            throw fail("current custody state schema is invalid");
        }
        // This snapshot is validation-only. It is intentionally not copied into historical evidence,
        // since later legitimate appends must not change an already frozen predecessor's hash.
        JsonNode known = state.path("known_v002_attempts");
        if (!known.isArray()) throw fail("durable family exposure state lacks its known-attempt chain");
        for (JsonNode ref : refs.path("attempts")) {
            boolean found = false;
            for (JsonNode entry : known) if (sameStableAttempt(ref, entry)) { found = true; break; }
            if (!found && !refs.path("attempts").isEmpty()) {
                // A canonical V5 HEAD may hold the prefix while the v002 journal is empty. The
                // current-state adapter must provide a verified matching identity inventory then.
                JsonNode headRefs = state.path("verified_canonical_attempt_refs");
                if (headRefs.isArray()) for (JsonNode entry : headRefs) if (sameStableAttempt(ref, entry)) { found = true; break; }
                if (!found) throw fail("immutable replay attempt refs are not present in the reopened durable family prefix");
            }
        }
    }

    private static boolean sameStableAttempt(JsonNode ref, JsonNode entry) {
        return ref.path("candidate_id").asText().equals(entry.path("candidate_id").asText())
                && ref.path("attempt_freeze_sha256").asText().equals(entry.path("attempt_freeze_sha256").asText())
                && ref.path("physical_freeze_sha256").asText().equals(entry.path("physical_freeze_sha256").asText())
                && ref.path("behavior_sha256").asText().equals(entry.path("behavior_sha256").asText());
    }

    private static boolean familyExposureUnknown(ObjectNode state) {
        // The replay binds attempted candidate identities, not a pre-outcome snapshot of K.
        // A later durable HEAD cannot be copied into historical predecessor evidence.
        return true;
    }

    private static boolean stagedStressPass(ObjectNode replay, ObjectNode plan, String scenarioId, ObjectNode physicalFreeze) {
        JsonNode evaluation = replay.path("stress_evaluation");
        if (!evaluation.isObject() || !"liquidation-v2-stress-evaluation/1".equals(evaluation.path("schema").asText())
                || !JsonHashes.ownHash(evaluation).equals(evaluation.path("content_sha256").asText())
                || !replay.path("ledger_sha256").asText().equals(evaluation.path("base_ledger_sha256").asText())
                || !evaluation.path("scenario_results").isArray()) return false;
        for (JsonNode row : evaluation.path("scenario_results")) {
            if (!scenarioId.equals(row.path("scenario_id").asText())) continue;
            JsonNode summary = null;
            for (JsonNode candidate : row.path("by_candidate")) if (plan.path("candidate_id").asText().equals(candidate.path("candidate_id").asText())) summary = candidate;
            if (summary == null) return false;
            JsonNode policy = row.path("scenario_run_artifact");
            boolean bound = policy.isObject() && LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA.equals(policy.path("schema").asText())
                    && isHash(policy.path("sha256").asText())
                    && policy.path("sha256").asText().equals(row.path("runner_result_sha256").asText())
                    && isHash(row.path("scenario_run_content_sha256").asText())
                    && isHash(row.path("runner_ledger_sha256").asText()) && isHash(row.path("runner_event_stream_sha256").asText())
                    && nonnegativeInt(row.path("transform_count"))
                    && isHash(row.path("transform_chain_sha256").asText());
            JsonNode rule = scenarioPolicy(physicalFreeze, scenarioId);
            boolean metrics = nonnegativeInt(summary.path("completed_observations"))
                    && summary.path("completed_observations").asInt() >= rule.path("minimum_observations").asInt(Integer.MAX_VALUE)
                    && nonnegativeInt(summary.path("open_unresolved_count"))
                    && nonnegativeInt(summary.path("unresolved_count"))
                    && nonnegativeInt(summary.path("coverage_blocked_count"))
                    && summary.path("open_unresolved_count").asInt(-1) == 0 && summary.path("unresolved_count").asInt(-1) == 0
                    && summary.path("coverage_blocked_count").asInt(-1) == 0
                    && summary.path("unresolved_count").asInt(-1)
                            == summary.path("open_unresolved_count").asInt(-1)
                                    + summary.path("coverage_blocked_count").asInt(-1)
                    && summary.path("expectancy_r").isNumber() && Double.isFinite(summary.path("expectancy_r").asDouble())
                    && summary.path("expectancy_r").asDouble() >= rule.path("minimum_expectancy_r").asDouble(Double.POSITIVE_INFINITY);
            JsonNode cancelledEntries = row.path("outage_cancelled_entry_attempt_count");
            JsonNode deferredExits = row.path("outage_deferred_exit_trigger_count");
            JsonNode blockedStopUpdates = row.path("outage_blocked_stop_update_count");
            boolean validOutageCounts = nonnegativeInt(cancelledEntries) && nonnegativeInt(deferredExits)
                    && nonnegativeInt(blockedStopUpdates);
            boolean outageAction = validOutageCounts && (!"venue_outage_blackout".equals(scenarioId)
                    || cancelledEntries.asInt() > 0 || deferredExits.asInt() > 0 || blockedStopUpdates.asInt() > 0);
            return bound && metrics && outageAction;
        }
        return false;
    }

    private static JsonNode scenarioPolicy(ObjectNode physicalFreeze, String id) {
        JsonNode precommit = physicalFreeze.path("precommit");
        if (!precommit.isObject()) return JsonHashes.mapper().createObjectNode();
        JsonNode source;
        try { source = LiquidationV2StressPolicyV1.reopen((ObjectNode) precommit).toJson().path("scenarios"); }
        catch (IllegalArgumentException invalid) { return JsonHashes.mapper().createObjectNode(); }
        if (source.isArray()) for (JsonNode row : source) if (id.equals(row.path("id").asText())) return row;
        return JsonHashes.mapper().createObjectNode();
    }

    private static Attribution attribution(ObjectNode replay, List<Pair> pairs, double fullRisk,
            Double plannedRiskAllowance, FillExposure fillExposure, double baselineRisk,
            double stagedDollars, double baselineDollars) {
        JsonNode ledger = replay.path("ledger");
        boolean ledgerBound = ledger.isObject() && JsonHashes.ownHash(ledger).equals(ledger.path("content_sha256").asText())
                && ledger.path("content_sha256").asText().equals(replay.path("ledger_sha256").asText());
        ObjectNode row = JsonHashes.mapper().createObjectNode()
                .put("scope", "PAIRED_PREDECESSOR_VS_STAGED_DOLLARS_AND_FULL_5_PERCENT_BUDGET_UTILIZATION")
                .put("staged_net_pnl_usdt", stagedDollars).put("baseline_net_pnl_usdt", baselineDollars)
                .put("staged_full_5_percent_reference_risk_usdt", fullRisk)
                .put("staged_planned_tranche_risk_allowance_sum_usdt", plannedRiskAllowance == null ? 0 : plannedRiskAllowance)
                .put("staged_planned_tranche_risk_allowance_utilization_fraction",
                        fullRisk > 0 && plannedRiskAllowance != null ? plannedRiskAllowance / fullRisk : 0)
                .put("staged_actual_fill_count", fillExposure.fillCount())
                .put("staged_actual_fill_attribution_complete", fillExposure.complete())
                .put("baseline_initial_risk_usdt", baselineRisk)
                .put("staged_reference_r_basis", "FULL_POSITION_5_PERCENT_OF_FIRST_FILL_REFERENCE_EQUITY")
                .put("baseline_budget_comparison", "CORE_1_PERCENT_RISK_SCALED_TO_SAME_5_PERCENT_REFERENCE_EQUITY")
                .put("same_anchor_count", pairs.size()).put("ledger_self_hash_valid", ledgerBound);
        ArrayNode actualFills = row.putArray("actual_filled_exposure_by_asset");
        fillExposure.amounts().forEach((asset, amount) -> actualFills.addObject().put("asset", asset)
                .put("filled_quantity", amount.quantity()).put("entry_notional_usdt", amount.notional()));
        row.put("content_sha256", JsonHashes.ownHash(row));
        return new Attribution(row, ledgerBound && !pairs.isEmpty() && fullRisk > 0
                && plannedRiskAllowance != null && fillExposure.complete());
    }

    private static JsonNode accountPath(ObjectNode replay) {
        JsonNode path = replay.path("account_path_summary");
        if (!path.isObject() || !"liquidation-v2-account-path-summary/1".equals(path.path("schema").asText())
                || !JsonHashes.ownHash(path).equals(path.path("content_sha256").asText())
                || !replay.path("ledger_sha256").asText().equals(path.path("ledger_sha256").asText())
                || !"ADVERSE_MARK_OHLC_EXTREMUM_BEFORE_EXIT_PATH".equals(path.path("drawdown_basis").asText())
                || !finite(path.path("maximum_adverse_mark_drawdown_fraction"))
                || !finite(path.path("starting_equity_usdt")) || !finite(path.path("ending_marked_equity_usdt"))) return null;
        return path;
    }

    private static boolean resolved(String state) {
        return "CLOSED_TRADE".equals(state) || "RESOLVED_NO_TRADE".equals(state);
    }

    private static boolean nonnegativeInt(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0;
    }

    private static Instant earliest(Instant left, Instant right) {
        if (left == null) return right; if (right == null) return left; return left.isBefore(right) ? left : right;
    }
    private static Instant latestNonNull(Instant left, Instant right) {
        if (left == null) return right; if (right == null) return left; return left.isAfter(right) ? left : right;
    }

    private static void validateGateInventory(ObjectNode gates) {
        if (!gates.fieldNames().hasNext()) throw fail("staged evaluator emitted no gates");
        Set<String> actual = new HashSet<>(); gates.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(REQUIRED_GATES)) throw fail("staged evaluator gate inventory differs from its frozen versioned policy");
        gates.fields().forEachRemaining(entry -> { if (!entry.getValue().isBoolean()) throw fail("staged gate must resolve to boolean; missing metrics fail false"); });
    }

    private static Map<String, JsonNode> stageRows(ArrayNode rows) { return indexByPair(rows); }

    private static ArrayNode array(ObjectNode node, String field) {
        if (!node.path(field).isArray()) throw fail("required array missing: " + field); return (ArrayNode) node.path(field);
    }
    private static ObjectNode object(ObjectNode node, String field) {
        if (!node.path(field).isObject()) throw fail("required object missing: " + field); return (ObjectNode) node.path(field);
    }
    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw fail("required text missing: " + field); return value.asText();
    }
    private static JsonNode numberNode(JsonNode node, String field) {
        JsonNode value = node.path(field); if (!finite(value)) throw fail("required finite number missing: " + field); return value;
    }
    private static double number(JsonNode node, String field) { return numberNode(node, field).asDouble(); }
    private static BigDecimal decimal(JsonNode node, String field) { return numberNode(node, field).decimalValue(); }
    private static boolean finite(JsonNode value) { return value != null && value.isNumber() && Double.isFinite(value.asDouble()); }
    private static Instant optionalInstant(JsonNode row, String field) {
        JsonNode value = row.path(field); if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual()) throw fail("timestamp must be ISO text: " + field);
        try { return Instant.parse(value.asText()); } catch (RuntimeException invalid) { throw fail("invalid timestamp: " + field); }
    }
    private static void verifyHash(ObjectNode node, String label) {
        if (!JsonHashes.ownHash(node).equals(node.path("content_sha256").asText())) throw fail(label + " content hash is invalid");
    }
    private static ObjectNode canonicalRoundTrip(ObjectNode value, String label) {
        try {
            JsonNode parsed = JsonHashes.mapper().readTree(JsonHashes.canonicalBytes(value));
            if (!(parsed instanceof ObjectNode object)) throw fail(label + " canonical JSON is not an object");
            return object;
        } catch (IOException error) {
            throw fail("cannot canonicalize " + label + " for deterministic statistics: " + error.getMessage());
        }
    }
    private static boolean isHash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static IllegalArgumentException fail(String message) { return new IllegalArgumentException(message); }

    private record Pair(String id, String asset, Instant decision, Instant firstFill, Instant exit,
            double stagedR, double baselineR, String branch, String state) {}
    private record TradeR(String pairId, Instant exit, double netR) {}
    private record Bootstrap(Double netP20, Double deltaP20, Double localAdjustedP, double observedDelta, int drawCount) {}
    private record Attribution(ObjectNode object, boolean complete) {}
    private record FillAmount(double quantity, double notional) {}
    private record FillExposure(int fillCount, Map<String, FillAmount> amounts, boolean complete) {}
}

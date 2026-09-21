package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Hash-bound contract matrix for both public macro-freeze evidence overloads. */
class LiquidationV2NoMacroEvidenceOverloadBoundaryMatrixTest {
    private static final String CORE_ID = "liquidation-v2-core-routed-one-entry";
    private static final String ROUTED = "ROUTED_REVERSAL_CONTINUATION";
    private static final String ASSET = "BTC";
    private static final String PAIR = "evidence-overload-pair";
    private static final String SETUP = "evidence-overload-setup";
    private static final List<String> GATE_NAMES = List.of("minimum_accepted_trades",
            "minimum_effective_independent_episode_count", "minimum_completed_positions_per_branch",
            "minimum_positive_outer_folds", "bootstrap_p20_expectancy_r_positive",
            "bootstrap_p20_incremental_expectancy_r_positive_vs_predecessor",
            "familywise_max_statistic_p_le_0_05_vs_predecessor", "maximum_drawdown_r", "maximum_cost_r",
            "portfolio_minimum_net_pnl", "portfolio_maximum_drawdown_pct",
            "full_position_5_percent_r_normalization", "risk_exposure_matched_attribution_reported",
            "all_anchor_outcomes_resolved", "stress_fee_slippage", "stress_funding_carry",
            "stress_adverse_execution_gap", "stress_liquidity_capacity", "stress_venue_outage_blackout");

    @Test
    void bothOverloadsAcceptOnlyExactSchemaVersionAndHashBoundLineage() {
        Fixture fixture = fixture();
        ObjectNode gateEvidence = LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(
                fixture.plan(), fixture.replay(), fixture.gates(), fixture.blockers());
        ObjectNode evaluatorEvidence = LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(
                fixture.plan(), fixture.replay(), fixture.evaluation());
        assertDoesNotThrow(() -> freezeWithGates(fixture, fixture.plan(), fixture.replay(), gateEvidence));
        assertDoesNotThrow(() -> freezeWithEvaluation(fixture, fixture.plan(), fixture.replay(), evaluatorEvidence,
                fixture.evaluation()));

        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                row -> row.put("schema", "wrong-schema"),
                row -> row.put("version", 2),
                row -> row.put("plan_sha256", "a".repeat(64)),
                row -> row.put("replay_result_sha256", "b".repeat(64)),
                row -> row.put("anchor_inventory_sha256", "c".repeat(64)),
                row -> row.put("source_mode", "OTHER_MODE"),
                row -> row.put("advancement_policy_id", "OTHER_POLICY"))) {
            ObjectNode changedGates = gateEvidence.deepCopy(); mutation.accept(changedGates); rehash(changedGates);
            assertHashBoundFailure(() -> freezeWithGates(fixture, fixture.plan(), fixture.replay(), changedGates));

            ObjectNode changedEvaluation = evaluatorEvidence.deepCopy(); mutation.accept(changedEvaluation); rehash(changedEvaluation);
            assertHashBoundFailure(() -> freezeWithEvaluation(fixture, fixture.plan(), fixture.replay(),
                    changedEvaluation, fixture.evaluation()));
        }

        ObjectNode badHashGates = gateEvidence.deepCopy().put("content_sha256", "d".repeat(64));
        ObjectNode badHashEvaluation = evaluatorEvidence.deepCopy().put("content_sha256", "e".repeat(64));
        assertHashBoundFailure(() -> freezeWithGates(fixture, fixture.plan(), fixture.replay(), badHashGates));
        assertHashBoundFailure(() -> freezeWithEvaluation(fixture, fixture.plan(), fixture.replay(),
                badHashEvaluation, fixture.evaluation()));
        assertHashBoundFailure(() -> freezeWithGates(fixture, fixture.plan(), fixture.replay(), null));
        assertHashBoundFailure(() -> freezeWithEvaluation(fixture, fixture.plan(), fixture.replay(), null,
                fixture.evaluation()));
    }

    @Test
    void bothOverloadsRejectAnEvidenceObjectThatIsBoundToTheWrongPlanMode() {
        Fixture fixture = fixture();
        ObjectNode macroPlan = LiquidationV2StagedCandidateInventoryV1.freezeMacro(fixture.plan(), fixture.replay(),
                fixture.evidenceForGates(), fixture.gates(), fixture.blockers());
        ObjectNode macroReplay = replay(macroPlan);
        ObjectNode macroGates = gates(true);
        ArrayNode blockers = JsonHashes.mapper().createArrayNode();
        ObjectNode macroEvidence = LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(
                macroPlan, macroReplay, macroGates, blockers);
        assertHashBoundFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeMacro(
                macroPlan, macroReplay, macroEvidence, macroGates, blockers));

        LiquidationV2StagedStatisticsV1.Evaluation macroEvaluation = new LiquidationV2StagedStatisticsV1.Evaluation(
                macroGates, blockers, statistics(macroPlan));
        ObjectNode macroEvaluatorEvidence = LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(
                macroPlan, macroReplay, macroEvaluation);
        assertHashBoundFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeMacro(
                macroPlan, macroReplay, macroEvaluatorEvidence, macroEvaluation));
    }

    private static void assertHashBoundFailure(org.junit.jupiter.api.function.Executable action) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, action);
        assertTrue(error.getMessage().contains("no-macro staged evidence is not hash-bound to its exact plan/replay/anchor lineage"),
                error.getMessage());
    }

    private static ObjectNode freezeWithGates(Fixture f, ObjectNode plan, ObjectNode replay, ObjectNode evidence) {
        return LiquidationV2StagedCandidateInventoryV1.freezeMacro(plan, replay, evidence, f.gates(), f.blockers());
    }

    private static ObjectNode freezeWithEvaluation(Fixture f, ObjectNode plan, ObjectNode replay,
            ObjectNode evidence, LiquidationV2StagedStatisticsV1.Evaluation evaluation) {
        return LiquidationV2StagedCandidateInventoryV1.freezeMacro(plan, replay, evidence, evaluation);
    }

    private static Fixture fixture() {
        Instant decision = Instant.parse("2024-01-03T12:00:00Z");
        ObjectNode inventory = inventory();
        ObjectNode intent = intent(decision);
        String intentHash = JsonHashes.canonicalSha256(intent);
        ObjectNode coreRow = opportunity(CORE_ID, intent, intentHash, decision)
                .put("outcome_state", "RESOLVED_NO_TRADE").put("outcome_available_time", decision.toString())
                .put("net_pnl_usdt", 0);
        coreRow.putArray("reason_codes").add("CORE_ZERO");
        ObjectNode coreReplay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY");
        coreReplay.putObject("freeze").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        coreReplay.putArray("opportunities").add(coreRow);
        ObjectNode audit = coreReplay.putArray("route_audit").addObject().put("status", "CONFIRMED_STAGE_ONE_INTENT")
                .put("candidate_id", CORE_ID).put("intent_id", intent.path("intent_id").asText())
                .put("setup_id", SETUP).put("pair_id", PAIR).put("asset", ASSET)
                .put("decision_time", decision.toString()).put("branch", "CONTINUATION")
                .put("direction", "SHORT").put("initial_intent_sha256", intentHash);
        audit.set("initial_intent", intent.deepCopy()); rehash(coreReplay);
        ObjectNode coreEvidence = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EVIDENCE_SCHEMA).put("version", 1)
                .put("status", "BLOCKED").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("replay_result_sha256", coreReplay.path("content_sha256").asText())
                .put("promotion_permitted", false).put("trade_authorization_permitted", false);
        coreEvidence.putArray("advancement_blockers").add("SYNTHETIC"); rehash(coreEvidence);
        ObjectNode plan = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(coreReplay, coreEvidence, inventory);
        ObjectNode replay = replay(plan);
        ObjectNode gates = gates(true);
        ArrayNode blockers = JsonHashes.mapper().createArrayNode();
        LiquidationV2StagedStatisticsV1.Evaluation evaluation = new LiquidationV2StagedStatisticsV1.Evaluation(
                gates, blockers, statistics(plan));
        ObjectNode evidence = LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(plan, replay, gates, blockers);
        return new Fixture(plan, replay, gates, blockers, evaluation, evidence);
    }

    private static ObjectNode replay(ObjectNode plan) {
        ObjectNode anchor = (ObjectNode) plan.path("decision_anchors").get(0);
        ObjectNode row = opportunity(plan.path("candidate_id").asText(), (ObjectNode) anchor.path("initial_intent"),
                anchor.path("initial_intent_sha256").asText(), Instant.parse(anchor.path("decision_time").asText()))
                .put("outcome_state", "RESOLVED_NO_TRADE").put("outcome_available_time", anchor.path("decision_time").asText())
                .put("net_pnl_usdt", 0);
        row.putArray("reason_codes").add("STAGED_ZERO");
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", plan.path("source_mode").asText()).put("candidate_id", plan.path("candidate_id").asText())
                .put("mode_id", plan.path("mode_id").asText()).put("plan_sha256", plan.path("content_sha256").asText())
                .put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText());
        replay.set("staged_plan", plan.deepCopy()); replay.putArray("opportunities").add(row); rehash(replay); return replay;
    }

    private static ObjectNode inventory() {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA).put("version", 1)
                .put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode rows = inventory.putArray("current_candidates");
        rows.addObject().put("candidate_id", CORE_ID).put("variant", ROUTED);
        rows.addObject().put("candidate_id", "liquidation-v2-core-always-continuation-one-entry").put("variant", "ALWAYS_CONTINUATION_CONTROL");
        rows.addObject().put("candidate_id", "liquidation-v2-core-always-reversal-one-entry").put("variant", "ALWAYS_REVERSAL_CONTROL");
        rows.addObject().put("candidate_id", "liquidation-v2-price-oi-only-event-diagnostic").put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        rehash(inventory); return inventory;
    }

    private static ObjectNode opportunity(String candidate, ObjectNode intent, String intentHash, Instant decision) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("candidate_id", candidate).put("variant", ROUTED)
                .put("stage", 1).put("pair_id", PAIR).put("asset", ASSET).put("decision_time", decision.toString())
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("setup_id", SETUP)
                .put("initial_intent_sha256", intentHash);
        row.set("initial_intent", intent.deepCopy()); return row;
    }

    private static ObjectNode intent(Instant decision) {
        ObjectNode seed = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-anchor-setup-seed/1")
                .put("version", 1).put("setup_id", SETUP); rehash(seed);
        ObjectNode intent = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-confirmed-entry-intent/1")
                .put("version", 1).put("intent_id", "overload-intent").put("setup_id", SETUP).put("pair_id", PAIR)
                .put("asset", ASSET).put("variant", ROUTED).put("branch", "CONTINUATION").put("direction", "SHORT")
                .put("stage", 1).put("decision_time", decision.toString()).put("diagnostic_only", false);
        intent.set("setup_seed", seed); rehash(intent); return intent;
    }

    private static ObjectNode gates(boolean passed) {
        ObjectNode result = JsonHashes.mapper().createObjectNode();
        for (String name : GATE_NAMES) result.put(name, passed);
        return result;
    }

    private static ObjectNode statistics(ObjectNode plan) {
        ObjectNode stats = JsonHashes.mapper().createObjectNode().put("schema", LiquidationV2StagedStatisticsV1.SCHEMA)
                .put("version", 1).put("mode_id", plan.path("mode_id").asText())
                .put("candidate_id", plan.path("candidate_id").asText())
                .put("statistics_scope", "POOLED_DEVELOPMENT;PREDECESSOR_PAIRED;NOT_OOS_OR_PROMOTION_EVIDENCE")
                .put("full_position_risk_numeric_representation", LiquidationV2EvidenceMathV1.FULL_POSITION_RISK_NUMERIC_REPRESENTATION);
        stats.putObject("overfit_selection_diagnostics").put("selection_search_performed", false)
                .put("pbo_status", "NOT_APPLICABLE_FIXED_PREDECLARED_NO_SELECTION").putNull("pbo_value")
                .put("pbo_reason", "FIXED_PREDECLARED_SEQUENTIAL_COMPARISONS_HAVE_NO_WINNER_SELECTION_MATRIX")
                .put("dsr_status", "UNAVAILABLE_NO_FROZEN_DSR_ESTIMATE").putNull("dsr_value").put("promotion_permitted", false);
        rehash(stats); return stats;
    }

    private static void rehash(ObjectNode node) { node.remove("content_sha256"); node.put("content_sha256", JsonHashes.ownHash(node)); }

    private record Fixture(ObjectNode plan, ObjectNode replay, ObjectNode gates, ArrayNode blockers,
            LiquidationV2StagedStatisticsV1.Evaluation evaluation, ObjectNode evidenceForGates) {}
}

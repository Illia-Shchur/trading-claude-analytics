package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exercises the evaluator-backed real-development rejection path; this is structural, not a historical result. */
class LiquidationV2NoMacroEvaluatorOverloadStructuralMatrixTest {
    private static final String CORE = "liquidation-v2-core-routed-one-entry";
    private static final String VARIANT = "ROUTED_REVERSAL_CONTINUATION";
    private static final String PAIR = "structural-overload-pair";
    private static final String SETUP = "structural-overload-setup";
    private static final List<String> GATES = List.of("minimum_accepted_trades",
            "minimum_effective_independent_episode_count", "minimum_completed_positions_per_branch",
            "minimum_positive_outer_folds", "bootstrap_p20_expectancy_r_positive",
            "bootstrap_p20_incremental_expectancy_r_positive_vs_predecessor",
            "familywise_max_statistic_p_le_0_05_vs_predecessor", "maximum_drawdown_r", "maximum_cost_r",
            "portfolio_minimum_net_pnl", "portfolio_maximum_drawdown_pct",
            "full_position_5_percent_r_normalization", "risk_exposure_matched_attribution_reported",
            "all_anchor_outcomes_resolved", "stress_fee_slippage", "stress_funding_carry",
            "stress_adverse_execution_gap", "stress_liquidity_capacity", "stress_venue_outage_blackout");

    @Test
    void evaluatorOverloadRejectsProxyEvidenceWhenAnyRecomputedGateOrBlockerFails() {
        Fixture fixture = fixture();
        for (boolean allGatesTrue : List.of(false, true)) {
            ObjectNode gates = gates(allGatesTrue);
            ArrayNode blockers = JsonHashes.mapper().createArrayNode();
            if (allGatesTrue) blockers.add("UNRESOLVED_OPEN_OR_COVERAGE_BLOCKED_ANCHORS_RETAINED_AND_BLOCK_ADVANCEMENT");
            LiquidationV2StagedStatisticsV1.Evaluation evaluation = new LiquidationV2StagedStatisticsV1.Evaluation(
                    gates, blockers, statistics(fixture.plan()));
            ObjectNode evidence = LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(
                    fixture.plan(), fixture.replay(), evaluation);

            assertEquals("DOES_NOT_SURVIVE", evidence.path("advancement_status").asText());
            assertFalse(evidence.path("survival_claim").asBoolean());
            assertFalse(evidence.path("promotion_permitted").asBoolean());
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> LiquidationV2StagedCandidateInventoryV1.freezeMacro(
                            fixture.plan(), fixture.replay(), evidence, evaluation));
            assertTrue(failure.getMessage().contains(
                    "real no-macro staged evidence did not pass every recomputed frozen advancement gate"),
                    failure.getMessage());
        }
    }

    @Test
    void stagedStatisticsAndNoSelectionDisclosureAreSchemaBoundBeforeEvidenceCanBeBuilt() {
        Fixture fixture = fixture();
        ObjectNode baselineStatistics = statistics(fixture.plan());
        LiquidationV2StagedStatisticsV1.Evaluation baseline = new LiquidationV2StagedStatisticsV1.Evaluation(
                gates(false), JsonHashes.mapper().createArrayNode(), baselineStatistics);
        ObjectNode validEvidence = LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(
                fixture.plan(), fixture.replay(), baseline);
        assertEquals("DOES_NOT_SURVIVE", validEvidence.path("advancement_status").asText());
        assertFalse(validEvidence.path("survival_claim").asBoolean());
        assertFalse(validEvidence.path("promotion_permitted").asBoolean());

        for (java.util.function.Consumer<ObjectNode> mutation : List.<java.util.function.Consumer<ObjectNode>>of(
                row -> row.put("schema", "wrong-statistics-schema"),
                row -> row.put("version", 2),
                row -> row.put("mode_id", "THREE_STAGE_MACRO"),
                row -> row.put("candidate_id", "other-candidate"),
                row -> row.put("statistics_scope", "OOS_PROMOTION_EVIDENCE"),
                row -> row.put("full_position_risk_numeric_representation", "ARBITRARY_TOLERANCE"),
                row -> row.put("content_sha256", "0".repeat(64)),
                row -> row.remove("overfit_selection_diagnostics"))) {
            ObjectNode changed = baselineStatistics.deepCopy();
            mutation.accept(changed);
            if (!"0".repeat(64).equals(changed.path("content_sha256").asText())) rehash(changed);
            assertMalformedStatistics(fixture, changed);
        }

        for (java.util.function.Consumer<ObjectNode> mutation : List.<java.util.function.Consumer<ObjectNode>>of(
                row -> row.put("selection_search_performed", true),
                row -> row.put("pbo_status", "PASS"),
                row -> row.put("pbo_value", 0),
                row -> row.put("pbo_reason", "NOT_DISCLOSED"),
                row -> row.put("dsr_status", "COMPUTED"),
                row -> row.put("dsr_value", 0),
                row -> row.put("promotion_permitted", true),
                row -> row.remove("selection_search_performed"),
                row -> row.remove("pbo_value"),
                row -> row.remove("dsr_value"))) {
            ObjectNode changed = baselineStatistics.deepCopy();
            mutation.accept((ObjectNode) changed.path("overfit_selection_diagnostics"));
            rehash(changed);
            assertMalformedStatistics(fixture, changed);
        }
    }

    private static void assertMalformedStatistics(Fixture fixture, ObjectNode statistics) {
        LiquidationV2StagedStatisticsV1.Evaluation invalid = new LiquidationV2StagedStatisticsV1.Evaluation(
                gates(false), JsonHashes.mapper().createArrayNode(), statistics);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(
                        fixture.plan(), fixture.replay(), invalid));
        assertTrue(failure.getMessage().contains("recomputed staged statistics are malformed or detached from the exact plan"),
                failure.getMessage());
    }

    private static Fixture fixture() {
        Instant decision = Instant.parse("2024-01-03T12:00:00Z");
        ObjectNode inventory = inventory();
        ObjectNode intent = intent(decision);
        String intentHash = JsonHashes.canonicalSha256(intent);
        ObjectNode coreRow = opportunity(CORE, intent, intentHash, decision)
                .put("outcome_state", "RESOLVED_NO_TRADE").put("outcome_available_time", decision.toString())
                .put("net_pnl_usdt", 0);
        coreRow.putArray("reason_codes").add("CORE_ZERO");
        ObjectNode coreReplay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", "PROXY_DISCLOSED_DEVELOPMENT_ONLY");
        coreReplay.putObject("freeze").put("source_mode", "PROXY_DISCLOSED_DEVELOPMENT_ONLY")
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        coreReplay.putArray("opportunities").add(coreRow);
        ObjectNode audit = coreReplay.putArray("route_audit").addObject()
                .put("status", "CONFIRMED_STAGE_ONE_INTENT").put("candidate_id", CORE)
                .put("intent_id", intent.path("intent_id").asText()).put("setup_id", SETUP)
                .put("pair_id", PAIR).put("asset", "BTC").put("decision_time", decision.toString())
                .put("branch", "CONTINUATION").put("direction", "SHORT")
                .put("initial_intent_sha256", intentHash);
        audit.set("initial_intent", intent.deepCopy()); rehash(coreReplay);
        ObjectNode coreEvidence = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EVIDENCE_SCHEMA).put("version", 1)
                .put("status", "DEVELOPMENT").put("source_mode", "PROXY_DISCLOSED_DEVELOPMENT_ONLY")
                .put("replay_result_sha256", coreReplay.path("content_sha256").asText())
                .put("promotion_permitted", false).put("trade_authorization_permitted", false);
        coreEvidence.putObject("acceptance_gates").put("all_core_gates", true);
        coreEvidence.putArray("advancement_blockers"); rehash(coreEvidence);
        ObjectNode plan = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(coreReplay, coreEvidence, inventory);
        ObjectNode replay = stagedReplay(plan, decision);
        return new Fixture(plan, replay);
    }

    private static ObjectNode stagedReplay(ObjectNode plan, Instant decision) {
        ObjectNode anchor = (ObjectNode) plan.path("decision_anchors").get(0);
        ObjectNode intent = (ObjectNode) anchor.path("initial_intent");
        ObjectNode row = opportunity(plan.path("candidate_id").asText(), intent,
                anchor.path("initial_intent_sha256").asText(), decision)
                .put("outcome_state", "RESOLVED_NO_TRADE").put("outcome_available_time", decision.toString())
                .put("net_pnl_usdt", 0);
        row.putArray("reason_codes").add("STAGED_ZERO");
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", plan.path("source_mode").asText()).put("candidate_id", plan.path("candidate_id").asText())
                .put("mode_id", plan.path("mode_id").asText()).put("plan_sha256", plan.path("content_sha256").asText())
                .put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText());
        replay.set("staged_plan", plan.deepCopy()); replay.putArray("opportunities").add(row); rehash(replay);
        return replay;
    }

    private static ObjectNode inventory() {
        ObjectNode value = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA).put("version", 1)
                .put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode rows = value.putArray("current_candidates");
        rows.addObject().put("candidate_id", CORE).put("variant", VARIANT);
        rows.addObject().put("candidate_id", "liquidation-v2-core-always-continuation-one-entry").put("variant", "ALWAYS_CONTINUATION_CONTROL");
        rows.addObject().put("candidate_id", "liquidation-v2-core-always-reversal-one-entry").put("variant", "ALWAYS_REVERSAL_CONTROL");
        rows.addObject().put("candidate_id", "liquidation-v2-price-oi-only-event-diagnostic").put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        rehash(value); return value;
    }

    private static ObjectNode opportunity(String candidate, ObjectNode intent, String hash, Instant decision) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("candidate_id", candidate).put("variant", VARIANT)
                .put("stage", 1).put("pair_id", PAIR).put("asset", "BTC").put("decision_time", decision.toString())
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("setup_id", SETUP)
                .put("initial_intent_sha256", hash);
        row.set("initial_intent", intent.deepCopy()); return row;
    }

    private static ObjectNode intent(Instant decision) {
        ObjectNode seed = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-anchor-setup-seed/1")
                .put("version", 1).put("setup_id", SETUP); rehash(seed);
        ObjectNode intent = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-confirmed-entry-intent/1").put("version", 1)
                .put("intent_id", "structural-overload-intent").put("setup_id", SETUP).put("pair_id", PAIR)
                .put("asset", "BTC").put("variant", VARIANT).put("branch", "CONTINUATION").put("direction", "SHORT")
                .put("stage", 1).put("decision_time", decision.toString()).put("diagnostic_only", false);
        intent.set("setup_seed", seed); rehash(intent); return intent;
    }

    private static ObjectNode gates(boolean pass) {
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        GATES.forEach(name -> value.put(name, pass)); return value;
    }

    private static ObjectNode statistics(ObjectNode plan) {
        ObjectNode value = JsonHashes.mapper().createObjectNode().put("schema", LiquidationV2StagedStatisticsV1.SCHEMA)
                .put("version", 1).put("mode_id", plan.path("mode_id").asText())
                .put("candidate_id", plan.path("candidate_id").asText())
                .put("statistics_scope", "POOLED_DEVELOPMENT;PREDECESSOR_PAIRED;NOT_OOS_OR_PROMOTION_EVIDENCE")
                .put("full_position_risk_numeric_representation", LiquidationV2EvidenceMathV1.FULL_POSITION_RISK_NUMERIC_REPRESENTATION);
        value.putObject("overfit_selection_diagnostics").put("selection_search_performed", false)
                .put("pbo_status", "NOT_APPLICABLE_FIXED_PREDECLARED_NO_SELECTION").putNull("pbo_value")
                .put("pbo_reason", "FIXED_PREDECLARED_SEQUENTIAL_COMPARISONS_HAVE_NO_WINNER_SELECTION_MATRIX")
                .put("dsr_status", "UNAVAILABLE_NO_FROZEN_DSR_ESTIMATE").putNull("dsr_value").put("promotion_permitted", false);
        rehash(value); return value;
    }

    private static void rehash(ObjectNode value) { value.remove("content_sha256"); value.put("content_sha256", JsonHashes.ownHash(value)); }
    private record Fixture(ObjectNode plan, ObjectNode replay) {}
}

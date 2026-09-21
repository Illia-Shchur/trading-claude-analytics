package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiquidationV2StagedInventoryLineageTest {
    private static final String ROUTED = "liquidation-v2-core-routed-one-entry";
    private static final String CONTINUE = "liquidation-v2-core-always-continuation-one-entry";
    private static final String REVERSE = "liquidation-v2-core-always-reversal-one-entry";
    private static final String DIAGNOSTIC = "liquidation-v2-price-oi-only-event-diagnostic";
    private static final Instant DECISION = Instant.parse("2024-01-03T12:00:00Z");
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
    void rehashedMacroAnchorAndPredecessorMutationsCannotChangeSequentialPlan() {
        CoreFixture core = coreFixture("SYNTHETIC_DEVELOPMENT_ONLY", false);
        ObjectNode noMacro = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                core.replay(), core.evidence(), core.inventory());
        ObjectNode replay = stagedReplay(noMacro);
        ObjectNode gates = gates(true);
        ArrayNode blockers = JsonHashes.mapper().createArrayNode();
        ObjectNode evidence = LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(noMacro, replay, gates, blockers);
        ObjectNode macro = LiquidationV2StagedCandidateInventoryV1.freezeMacro(noMacro, replay, evidence, gates, blockers);
        assertTrue(LiquidationV2StagedCandidateInventoryV1.validateMacroPlan(
                macro, noMacro, replay, evidence, gates, blockers));

        ObjectNode changedPredecessor = macro.deepCopy();
        changedPredecessor.put("predecessor_plan_sha256", "a".repeat(64));
        rehash(changedPredecessor);
        assertFailureContains(() -> LiquidationV2StagedCandidateInventoryV1.validateMacroPlan(
                changedPredecessor, noMacro, replay, evidence, gates, blockers),
                "differs from its reopened surviving no-macro lineage");

        ObjectNode changedAnchor = macro.deepCopy();
        ArrayNode anchors = (ArrayNode) changedAnchor.path("decision_anchors");
        ObjectNode anchor = (ObjectNode) anchors.get(0);
        ObjectNode intent = (ObjectNode) anchor.path("initial_intent");
        ObjectNode seed = (ObjectNode) intent.path("setup_seed");
        seed.put("event_boundary_high", 120.0);
        rehash(seed);
        rehash(intent);
        anchor.put("initial_intent_sha256", JsonHashes.canonicalSha256(intent));
        String changedInventory = JsonHashes.canonicalSha256(anchors);
        changedAnchor.put("anchor_inventory_sha256", changedInventory);
        ((ObjectNode) changedAnchor.path("candidate")).put("initial_pair_inventory_sha256", changedInventory);
        rehash(changedAnchor);
        assertFailureContains(() -> LiquidationV2StagedCandidateInventoryV1.validateMacroPlan(
                changedAnchor, noMacro, replay, evidence, gates, blockers),
                "differs from its reopened surviving no-macro lineage");

        ObjectNode changedPolicy = macro.deepCopy();
        changedPolicy.put("macro_gate_policy", "STRUCTURE_ONLY");
        ((ObjectNode) changedPolicy.path("candidate")).put("macro_gate_policy", "STRUCTURE_ONLY");
        rehash(changedPolicy);
        assertFailureContains(() -> LiquidationV2StagedCandidateInventoryV1.validateMacroPlan(
                changedPolicy, noMacro, replay, evidence, gates, blockers),
                "fixed mode, risk, allocation or selection contract");
    }

    @Test
    void realNoMacroMechanicsWithoutRecomputedStatisticsCannotAdvanceMacro() {
        CoreFixture core = coreFixture("PROXY_DISCLOSED_DEVELOPMENT_ONLY", true);
        ObjectNode noMacro = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                core.replay(), core.evidence(), core.inventory());
        ObjectNode replay = stagedReplay(noMacro);
        ObjectNode gates = gates(true);
        ArrayNode blockers = JsonHashes.mapper().createArrayNode();
        ObjectNode evidence = LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(noMacro, replay, gates, blockers);

        assertEquals("DOES_NOT_SURVIVE", evidence.path("advancement_status").asText());
        assertFailureContains(() -> LiquidationV2StagedCandidateInventoryV1.freezeMacro(
                noMacro, replay, evidence, gates, blockers),
                "staged predecessor did not survive; macro-last cannot be frozen");
    }

    @Test
    void exactGateContractRejectsMissingNonBooleanAndInventedNames() {
        CoreFixture core = coreFixture("SYNTHETIC_DEVELOPMENT_ONLY", false);
        ObjectNode noMacro = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                core.replay(), core.evidence(), core.inventory());
        ObjectNode replay = stagedReplay(noMacro);
        ArrayNode blockers = JsonHashes.mapper().createArrayNode();
        ObjectNode accepted = LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(
                noMacro, replay, gates(true), blockers);
        assertTrue(JsonHashes.ownHash(accepted).equals(accepted.path("content_sha256").asText()));

        ObjectNode missing = gates(true);
        missing.remove(GATE_NAMES.get(0));
        assertFailureContains(() -> LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(
                noMacro, replay, missing, blockers), "do not match the frozen exact gate inventory");

        ObjectNode invented = gates(true);
        invented.remove(GATE_NAMES.get(0));
        invented.put("caller_approval", true);
        assertFailureContains(() -> LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(
                noMacro, replay, invented, blockers), "do not match the frozen exact gate inventory");

        ObjectNode nonBoolean = gates(true);
        nonBoolean.put(GATE_NAMES.get(0), "true");
        assertFailureContains(() -> LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(
                noMacro, replay, nonBoolean, blockers), "must be unique named booleans");
        assertTrue(GATE_NAMES.contains("minimum_accepted_trades"));
    }

    private static CoreFixture coreFixture(String sourceMode, boolean realDevelopment) {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA)
                .put("version", 1).put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode candidates = inventory.putArray("current_candidates");
        candidates.addObject().put("candidate_id", ROUTED).put("variant", "ROUTED_REVERSAL_CONTINUATION");
        candidates.addObject().put("candidate_id", CONTINUE).put("variant", "ALWAYS_CONTINUATION_CONTROL");
        candidates.addObject().put("candidate_id", REVERSE).put("variant", "ALWAYS_REVERSAL_CONTROL");
        candidates.addObject().put("candidate_id", DIAGNOSTIC).put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        rehash(inventory);

        ObjectNode seed = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-anchor-setup-seed/1")
                .put("version", 1).put("setup_id", "setup-1");
        rehash(seed);
        ObjectNode intent = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-confirmed-entry-intent/1").put("version", 1)
                .put("intent_id", "intent-1").put("setup_id", "setup-1").put("pair_id", "pair-1")
                .put("asset", "BTC").put("variant", "ROUTED_REVERSAL_CONTINUATION")
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("stage", 1)
                .put("decision_time", DECISION.toString()).put("diagnostic_only", false);
        intent.set("setup_seed", seed);
        rehash(intent);
        String intentSha = JsonHashes.canonicalSha256(intent);
        ObjectNode opportunity = JsonHashes.mapper().createObjectNode()
                .put("candidate_id", ROUTED).put("variant", "ROUTED_REVERSAL_CONTINUATION")
                .put("stage", 1).put("pair_id", "pair-1").put("asset", "BTC")
                .put("decision_time", DECISION.toString()).put("setup_id", "setup-1")
                .put("initial_intent_sha256", intentSha);
        opportunity.set("initial_intent", intent.deepCopy());
        ObjectNode signal = JsonHashes.mapper().createObjectNode()
                .put("status", "CONFIRMED_STAGE_ONE_INTENT").put("candidate_id", ROUTED)
                .put("intent_id", "intent-1").put("setup_id", "setup-1").put("pair_id", "pair-1")
                .put("asset", "BTC").put("decision_time", DECISION.toString())
                .put("branch", "CONTINUATION").put("direction", "SHORT")
                .put("initial_intent_sha256", intentSha);
        signal.set("initial_intent", intent.deepCopy());
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", sourceMode);
        replay.putObject("freeze").put("source_mode", sourceMode)
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        replay.putArray("opportunities").add(opportunity);
        replay.putArray("route_audit").add(signal);
        rehash(replay);
        ObjectNode evidence = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EVIDENCE_SCHEMA).put("version", 1)
                .put("status", realDevelopment ? "DEVELOPMENT" : "BLOCKED").put("source_mode", sourceMode)
                .put("replay_result_sha256", replay.path("content_sha256").asText())
                .put("promotion_permitted", false).put("trade_authorization_permitted", false);
        ObjectNode acceptance = evidence.putObject("acceptance_gates");
        if (realDevelopment) acceptance.put("all_core_gates", true);
        evidence.putArray("advancement_blockers");
        rehash(evidence);
        return new CoreFixture(replay, evidence, inventory, sourceMode);
    }

    private static ObjectNode stagedReplay(ObjectNode plan) {
        ObjectNode anchor = (ObjectNode) plan.path("decision_anchors").get(0);
        ObjectNode intent = (ObjectNode) anchor.path("initial_intent");
        ObjectNode row = JsonHashes.mapper().createObjectNode()
                .put("candidate_id", plan.path("candidate_id").asText())
                .put("variant", "ROUTED_REVERSAL_CONTINUATION").put("stage", 1)
                .put("pair_id", anchor.path("pair_id").asText()).put("asset", anchor.path("asset").asText())
                .put("decision_time", anchor.path("decision_time").asText())
                .put("branch", intent.path("branch").asText()).put("direction", intent.path("direction").asText())
                .put("initial_intent_sha256", anchor.path("initial_intent_sha256").asText())
                .put("outcome_state", "RESOLVED_NO_TRADE").put("outcome_available_time", DECISION.toString())
                .put("net_pnl_usdt", 0);
        row.set("initial_intent", intent.deepCopy());
        row.putArray("reason_codes").add("FIXTURE_ZERO");
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", plan.path("source_mode").asText())
                .put("candidate_id", plan.path("candidate_id").asText())
                .put("mode_id", plan.path("mode_id").asText())
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText());
        replay.putArray("opportunities").add(row);
        rehash(replay);
        return replay;
    }

    private static ObjectNode gates(boolean passed) {
        ObjectNode result = JsonHashes.mapper().createObjectNode();
        for (String name : GATE_NAMES) result.put(name, passed);
        return result;
    }

    private static void rehash(ObjectNode object) {
        object.remove("content_sha256");
        object.put("content_sha256", JsonHashes.ownHash(object));
    }

    private static void assertFailureContains(org.junit.jupiter.api.function.Executable action, String message) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action);
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
    }

    private record CoreFixture(ObjectNode replay, ObjectNode evidence, ObjectNode inventory, String sourceMode) {}
}

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

class LiquidationV2StagedCandidateInventoryV1Test {
    private static final String ROUTED = "liquidation-v2-core-routed-one-entry";
    private static final String CONTINUE = "liquidation-v2-core-always-continuation-one-entry";
    private static final String REVERSE = "liquidation-v2-core-always-reversal-one-entry";
    private static final String DIAGNOSTIC = "liquidation-v2-price-oi-only-event-diagnostic";
    private static final Instant DECISION = Instant.parse("2024-01-03T12:00:00Z");

    @Test
    void freezesFullRunnerShapedAnchorAndChainsSequentialMacroOnlyToNoMacroResult() {
        CoreFixture core = coreFixture("SYNTHETIC_DEVELOPMENT_ONLY", "BLOCKED");
        ObjectNode plan = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                core.replay(), core.evidence(), core.inventory());
        assertEquals("liquidation-v2-staged-candidate-plan/1", plan.path("schema").asText());
        assertEquals(JsonHashes.ownHash(plan), plan.path("content_sha256").asText());
        assertEquals(1, plan.path("decision_anchors").size());
        assertTrue(plan.path("decision_anchors").get(0).path("initial_intent").path("setup_seed").isObject());
        assertEquals(0.05, plan.path("stage_risk_fraction_sum").asDouble());
        assertTrue(plan.path("stage_risk_fractions").isArray());
        assertTrue(LiquidationV2StagedCandidateInventoryV1.validateNoMacroPlan(
                plan, core.replay(), core.evidence(), core.inventory()));

        ObjectNode replay = stagedReplay(plan, "RESOLVED_NO_TRADE");
        ObjectNode gates = allRequiredGates();
        ArrayNode blockers = JsonHashes.mapper().createArrayNode();
        ObjectNode evidence = LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(plan, replay, gates, blockers);
        assertEquals("SYNTHETIC_FIXTURE_ONLY", evidence.path("advancement_status").asText());
        assertFalse(evidence.path("survival_claim").asBoolean());
        assertFalse(evidence.path("promotion_permitted").asBoolean());
        assertTrue(LiquidationV2StagedCandidateInventoryV1.validateReplayEvidence(plan, replay, evidence, gates, blockers));

        ObjectNode macro = LiquidationV2StagedCandidateInventoryV1.freezeMacro(plan, replay, evidence, gates, blockers);
        assertEquals("liquidation-v2-staged-candidate-plan/1", macro.path("schema").asText());
        assertEquals(JsonHashes.ownHash(macro), macro.path("content_sha256").asText());
        assertEquals(LiquidationV2StagedCandidateInventoryV1.THREE_STAGE_MACRO, macro.path("mode_id").asText());
        assertEquals(plan.path("anchor_inventory_sha256").asText(), macro.path("anchor_inventory_sha256").asText());
        assertEquals(plan.path("content_sha256").asText(), macro.path("predecessor_plan_sha256").asText());
        assertEquals(replay.path("content_sha256").asText(), macro.path("predecessor_replay_sha256").asText());
        assertFalse(macro.path("survival_claim").asBoolean());
        assertTrue(LiquidationV2StagedCandidateInventoryV1.validateMacroPlan(
                macro, plan, replay, evidence, gates, blockers));
    }

    @Test
    void rejectsFailedRealCoreBeforeCreatingStagedInventory() {
        CoreFixture core = coreFixture("PROXY_DISCLOSED_DEVELOPMENT_ONLY", "BLOCKED");
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                core.replay(), core.evidence(), core.inventory()));
    }

    @Test
    void exactGateInventoryAndOutcomeGeometryCannotBeRehashedIntoAnApproval() {
        CoreFixture core = coreFixture("SYNTHETIC_DEVELOPMENT_ONLY", "BLOCKED");
        ObjectNode plan = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                core.replay(), core.evidence(), core.inventory());
        ObjectNode replay = stagedReplay(plan, "RESOLVED_NO_TRADE");
        ObjectNode gates = allRequiredGates();
        gates.remove("maximum_cost_r");
        gates.put("caller_says_yes", true);
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(plan, replay, gates,
                        JsonHashes.mapper().createArrayNode()));

        ObjectNode filled = stagedReplay(plan, "CLOSED_TRADE");
        ObjectNode row = (ObjectNode) filled.path("opportunities").get(0);
        row.put("first_fill_time", DECISION.toString()); // strict next-time fill rule
        rehash(filled);
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(plan, filled,
                        allRequiredGates(), JsonHashes.mapper().createArrayNode()));
    }

    @Test
    void candidatePlanSchemaIsExactAndARehashedLegacyInventorySchemaIsRejected() {
        CoreFixture core = coreFixture("SYNTHETIC_DEVELOPMENT_ONLY", "BLOCKED");
        ObjectNode plan = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                core.replay(), core.evidence(), core.inventory());
        ObjectNode legacyInventorySchema = plan.deepCopy();
        legacyInventorySchema.put("schema", "liquidation-v2-staged-candidate-inventory/1");
        rehash(legacyInventorySchema);

        assertThrows(IllegalArgumentException.class, () -> LiquidationV2StagedCandidateInventoryV1.validateNoMacroPlan(
                legacyInventorySchema, core.replay(), core.evidence(), core.inventory()));
    }

    @Test
    void missingAnchoredPairAndFilledCoverageBlockedEpisodeRemainExplicitBlockers() {
        CoreFixture core = coreFixture("SYNTHETIC_DEVELOPMENT_ONLY", "BLOCKED");
        ObjectNode plan = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                core.replay(), core.evidence(), core.inventory());
        ObjectNode replay = stagedReplay(plan, "COVERAGE_BLOCKED");
        ObjectNode row = (ObjectNode) replay.path("opportunities").get(0);
        row.put("first_fill_time", DECISION.plusSeconds(60).toString())
                .put("position_episode_id", "episode-1")
                .put("first_fill_reference_equity_usdt", 10000)
                .put("full_position_reference_risk_usdt", 500)
                .put("reference_risk_usdt", 500)
                .put("full_position_reference_risk_basis", LiquidationV2StagedCandidateInventoryV1.FULL_POSITION_R_BASIS)
                .putNull("outcome_available_time").putNull("exit_time").putNull("net_pnl_usdt");
        rehash(replay);
        ObjectNode validated = LiquidationV2StagedCandidateInventoryV1.validateAnchoredOutcomes(
                plan, LiquidationV2StagedCandidateInventoryV1.THREE_STAGE_NO_MACRO,
                (ArrayNode) replay.path("opportunities"));
        assertEquals("BLOCKED", validated.path("status").asText());
        assertEquals(1, validated.path("filled_coverage_blocked_count").asInt());

        ArrayNode omitted = JsonHashes.mapper().createArrayNode();
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2StagedCandidateInventoryV1.validateAnchoredOutcomes(
                plan, LiquidationV2StagedCandidateInventoryV1.THREE_STAGE_NO_MACRO, omitted));
    }

    private static CoreFixture coreFixture(String sourceMode, String evidenceStatus) {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA)
                .put("version", 1).put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode candidates = inventory.putArray("current_candidates");
        candidates.addObject().put("candidate_id", ROUTED).put("variant", "ROUTED_REVERSAL_CONTINUATION");
        candidates.addObject().put("candidate_id", CONTINUE).put("variant", "ALWAYS_CONTINUATION_CONTROL");
        candidates.addObject().put("candidate_id", REVERSE).put("variant", "ALWAYS_REVERSAL_CONTROL");
        candidates.addObject().put("candidate_id", DIAGNOSTIC).put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        inventory.put("content_sha256", JsonHashes.ownHash(inventory));

        ObjectNode seed = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-anchor-setup-seed/1")
                .put("version", 1).put("setup_id", "setup-1").put("content_sha256", "placeholder");
        seed.put("content_sha256", JsonHashes.ownHash(seed));
        ObjectNode intent = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-confirmed-entry-intent/1").put("version", 1)
                .put("intent_id", "intent-1").put("setup_id", "setup-1").put("pair_id", "pair-1")
                .put("asset", "BTC").put("variant", "ROUTED_REVERSAL_CONTINUATION")
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("stage", 1)
                .put("decision_time", DECISION.toString()).put("diagnostic_only", false);
        intent.set("setup_seed", seed);
        intent.put("content_sha256", JsonHashes.ownHash(intent));
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
                .put("status", evidenceStatus).put("source_mode", sourceMode)
                .put("replay_result_sha256", replay.path("content_sha256").asText())
                .put("promotion_permitted", false).put("trade_authorization_permitted", false);
        ObjectNode acceptance = evidence.putObject("acceptance_gates");
        if ("DEVELOPMENT".equals(evidenceStatus)) acceptance.put("all_core_gates", true);
        evidence.putArray("advancement_blockers");
        evidence.put("content_sha256", JsonHashes.ownHash(evidence));
        return new CoreFixture(replay, evidence, inventory);
    }

    private static ObjectNode stagedReplay(ObjectNode plan, String state) {
        JsonNodeIntent frozen = new JsonNodeIntent(plan.path("decision_anchors").get(0).path("initial_intent"));
        ObjectNode row = JsonHashes.mapper().createObjectNode()
                .put("candidate_id", plan.path("candidate_id").asText())
                .put("variant", "ROUTED_REVERSAL_CONTINUATION").put("stage", 1)
                .put("pair_id", frozen.pairId()).put("asset", frozen.asset())
                .put("decision_time", frozen.decisionTime()).put("branch", frozen.branch())
                .put("direction", frozen.direction()).put("initial_intent_sha256", frozen.sha());
        row.set("initial_intent", frozen.intent());
        row.put("outcome_state", state).put("outcome_available_time", DECISION.toString())
                .put("net_pnl_usdt", 0).putNull("first_fill_time").putNull("exit_time")
                .putArray("reason_codes").add("ANCHOR_REJECTED_BY_TEST_FIXTURE");
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", plan.path("source_mode").asText())
                .put("candidate_id", plan.path("candidate_id").asText())
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText());
        replay.putArray("opportunities").add(row);
        rehash(replay);
        return replay;
    }

    private static ObjectNode allRequiredGates() {
        ObjectNode gates = JsonHashes.mapper().createObjectNode();
        for (String name : List.of("minimum_accepted_trades", "minimum_effective_independent_episode_count",
                "minimum_completed_positions_per_branch", "minimum_positive_outer_folds",
                "bootstrap_p20_expectancy_r_positive", "bootstrap_p20_incremental_expectancy_r_positive_vs_predecessor",
                "familywise_max_statistic_p_le_0_05_vs_predecessor", "maximum_drawdown_r", "maximum_cost_r",
                "portfolio_minimum_net_pnl", "portfolio_maximum_drawdown_pct",
                "full_position_5_percent_r_normalization", "risk_exposure_matched_attribution_reported",
                "all_anchor_outcomes_resolved",
                "stress_fee_slippage", "stress_funding_carry", "stress_adverse_execution_gap",
                "stress_liquidity_capacity", "stress_venue_outage_blackout")) gates.put(name, true);
        return gates;
    }

    private static void rehash(ObjectNode value) {
        value.remove("content_sha256");
        value.put("content_sha256", JsonHashes.ownHash(value));
    }

    private record CoreFixture(ObjectNode replay, ObjectNode evidence, ObjectNode inventory) {}
    private record JsonNodeIntent(com.fasterxml.jackson.databind.JsonNode node) {
        String pairId() { return node.path("pair_id").asText(); }
        String asset() { return node.path("asset").asText(); }
        String decisionTime() { return node.path("decision_time").asText(); }
        String branch() { return node.path("branch").asText(); }
        String direction() { return node.path("direction").asText(); }
        String sha() { return JsonHashes.canonicalSha256(node); }
        ObjectNode intent() { return (ObjectNode) node.deepCopy(); }
    }
}

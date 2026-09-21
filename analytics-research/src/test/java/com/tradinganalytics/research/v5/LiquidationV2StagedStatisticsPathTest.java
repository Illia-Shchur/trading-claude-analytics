package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiquidationV2StagedStatisticsPathTest {
    private static final String CORE_ROUTED_ID = "liquidation-v2-core-routed-one-entry";
    private static final String ROUTED = "ROUTED_REVERSAL_CONTINUATION";

    @Test
    void positivePairedContrastsProduceNonzeroSynchronizedBootstrapMetrics() throws Exception {
        Fixture fixture = fixture(20, 0.5, 1.0);
        LiquidationV2StagedStatisticsV1.Evaluation evaluation = LiquidationV2StagedStatisticsV1.recompute(
                fixture.physicalFreeze(), fixture.noMacroPlan(), fixture.coreReplay(), fixture.noMacroReplay(),
                null, null, null);
        ObjectNode stats = evaluation.statistics();

        assertEquals(20, stats.path("effective_67d_dependence_components").asInt());
        assertEquals(10, stats.path("continuation_completed_count").asInt());
        assertEquals(10, stats.path("reversal_completed_count").asInt());
        assertEquals(0.5, stats.path("observed_incremental_mean_r_vs_predecessor").asDouble(), 1e-12);
        assertTrue(stats.path("expectancy_p20_r").asDouble() > 0.0);
        assertTrue(stats.path("incremental_p20_vs_predecessor_r").asDouble() > 0.0);
        assertEquals(10_000, stats.path("synchronized_block_indices").path("draw_count").asInt());
        assertEquals(20, stats.path("paired_outcome_inventory").size());
        JsonNode selection = stats.path("overfit_selection_diagnostics");
        assertEquals("NOT_APPLICABLE_FIXED_PREDECLARED_NO_SELECTION", selection.path("pbo_status").asText());
        assertTrue(selection.path("pbo_value").isNull());
        assertEquals("UNAVAILABLE_NO_FROZEN_DSR_ESTIMATE", selection.path("dsr_status").asText());
        assertTrue(selection.path("dsr_value").isNull());
        assertFalse(selection.path("selection_search_performed").asBoolean(true));
        assertFalse(selection.path("promotion_permitted").asBoolean(true));
    }

    @Test
    void macroStatisticsReopenTheExactNoMacroPredecessorAndUseItsFivePercentRiskBasis() throws Exception {
        Fixture fixture = fixture(1, 0.5, 1.0);
        LiquidationV2StagedStatisticsV1.Evaluation noMacroEvaluation = LiquidationV2StagedStatisticsV1.recompute(
                fixture.physicalFreeze(), fixture.noMacroPlan(), fixture.coreReplay(), fixture.noMacroReplay(),
                null, null, null);
        ObjectNode noMacroEvidence = LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(
                fixture.noMacroPlan(), fixture.noMacroReplay(), noMacroEvaluation);
        assertEquals("NOT_APPLICABLE_FIXED_PREDECLARED_NO_SELECTION",
                noMacroEvidence.path("overfit_selection_diagnostics").path("pbo_status").asText());
        assertTrue(noMacroEvidence.path("overfit_selection_diagnostics").path("pbo_value").isNull());
        ObjectNode macroPlan = LiquidationV2StagedCandidateInventoryV1.freezeMacro(fixture.noMacroPlan(),
                fixture.noMacroReplay(), noMacroEvidence, noMacroEvaluation);
        ObjectNode macroReplay = stagedReplay(macroPlan, fixture.planDecisionTimes(), 0.75);

        LiquidationV2StagedStatisticsV1.Evaluation macro = LiquidationV2StagedStatisticsV1.recompute(
                fixture.physicalFreeze(), macroPlan, fixture.noMacroReplay(), macroReplay,
                fixture.noMacroPlan(), noMacroEvidence, null);
        ObjectNode stats = macro.statistics();

        assertEquals("THREE_STAGE_NO_MACRO_5_PERCENT_RISK", stats.path("baseline_kind").asText());
        assertEquals(-0.25, stats.path("observed_incremental_mean_r_vs_predecessor").asDouble(), 1e-12);
        assertEquals(1, stats.path("paired_outcome_inventory").size());
        assertTrue(macro.blockers().toString().contains("HISTORICAL_FAMILY_K_UNKNOWN"));
        assertEquals("NOT_APPLICABLE_FIXED_PREDECLARED_NO_SELECTION",
                stats.path("overfit_selection_diagnostics").path("pbo_status").asText());
        assertTrue(stats.path("overfit_selection_diagnostics").path("pbo_value").isNull());
    }

    @Test
    void fullPositionRiskUsesOverlappingCanonicalRoundingIntervals() throws Exception {
        Fixture fixture = fixture(1, 0.5, 1.0);
        ObjectNode validReplay = fixture.noMacroReplay().deepCopy();
        ObjectNode validRow = (ObjectNode) validReplay.path("opportunities").get(0);
        BigDecimal equity = new BigDecimal("73282.9075072922063823855");
        BigDecimal fivePercentRisk = new BigDecimal("3664.145375364610319119275");
        validRow.put("first_fill_reference_equity_usdt", equity);
        validRow.put("full_position_reference_risk_usdt", fivePercentRisk);
        validRow.put("reference_risk_usdt", fivePercentRisk);
        rehash(validReplay);

        LiquidationV2StagedCandidateInventoryV1.validateAnchoredOutcomes(fixture.noMacroPlan(),
                LiquidationV2StagedCandidateInventoryV1.THREE_STAGE_NO_MACRO,
                JsonHashes.mapper().createArrayNode().add(validRow.deepCopy()));
        LiquidationV2StagedStatisticsV1.Evaluation accepted = LiquidationV2StagedStatisticsV1.recompute(
                fixture.physicalFreeze(), fixture.noMacroPlan(), fixture.coreReplay(), validReplay,
                null, null, null);
        assertTrue(accepted.gates().path("full_position_5_percent_r_normalization").asBoolean());
        assertEquals(LiquidationV2EvidenceMathV1.FULL_POSITION_RISK_NUMERIC_REPRESENTATION,
                accepted.statistics().path("full_position_risk_numeric_representation").asText());

        double serializedRisk = fivePercentRisk.doubleValue();
        ObjectNode oneStepInsideReplay = validReplay.deepCopy();
        ObjectNode oneStepInsideRow = (ObjectNode) oneStepInsideReplay.path("opportunities").get(0);
        double oneStepInside = Math.nextUp(serializedRisk);
        oneStepInsideRow.put("full_position_reference_risk_usdt", oneStepInside);
        oneStepInsideRow.put("reference_risk_usdt", oneStepInside);
        rehash(oneStepInsideReplay);
        assertDoesNotThrow(() -> LiquidationV2StagedCandidateInventoryV1.validateAnchoredOutcomes(
                fixture.noMacroPlan(), LiquidationV2StagedCandidateInventoryV1.THREE_STAGE_NO_MACRO,
                JsonHashes.mapper().createArrayNode().add(oneStepInsideRow.deepCopy())));

        ObjectNode nearestOutsideReplay = validReplay.deepCopy();
        ObjectNode nearestOutsideRow = (ObjectNode) nearestOutsideReplay.path("opportunities").get(0);
        double nearestOutside = Math.nextDown(serializedRisk);
        nearestOutsideRow.put("full_position_reference_risk_usdt", nearestOutside);
        nearestOutsideRow.put("reference_risk_usdt", nearestOutside);
        rehash(nearestOutsideReplay);
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2StagedCandidateInventoryV1.validateAnchoredOutcomes(
                fixture.noMacroPlan(), LiquidationV2StagedCandidateInventoryV1.THREE_STAGE_NO_MACRO,
                JsonHashes.mapper().createArrayNode().add(nearestOutsideRow.deepCopy())));

        ObjectNode forgedReplay = validReplay.deepCopy();
        ObjectNode forgedRow = (ObjectNode) forgedReplay.path("opportunities").get(0);
        BigDecimal forgedRisk = fivePercentRisk.multiply(new BigDecimal("1.01"));
        forgedRow.put("full_position_reference_risk_usdt", forgedRisk);
        forgedRow.put("reference_risk_usdt", forgedRisk);
        rehash(forgedReplay);
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2StagedCandidateInventoryV1.validateAnchoredOutcomes(
                fixture.noMacroPlan(), LiquidationV2StagedCandidateInventoryV1.THREE_STAGE_NO_MACRO,
                JsonHashes.mapper().createArrayNode().add(forgedRow.deepCopy())));
    }

    @Test
    void rehashedStatisticsCannotTurnPboNotApplicableIntoAnApproval() throws Exception {
        Fixture fixture = fixture(1, 0.5, 1.0);
        LiquidationV2StagedStatisticsV1.Evaluation evaluation = LiquidationV2StagedStatisticsV1.recompute(
                fixture.physicalFreeze(), fixture.noMacroPlan(), fixture.coreReplay(), fixture.noMacroReplay(),
                null, null, null);
        ObjectNode forgedStatistics = evaluation.statistics();
        ObjectNode diagnostics = (ObjectNode) forgedStatistics.path("overfit_selection_diagnostics");
        diagnostics.put("pbo_status", "PASS").put("pbo_value", 0.001);
        rehash(forgedStatistics);
        LiquidationV2StagedStatisticsV1.Evaluation forged = new LiquidationV2StagedStatisticsV1.Evaluation(
                evaluation.gates(), evaluation.blockers(), forgedStatistics);

        assertThrows(IllegalArgumentException.class, () -> LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(
                fixture.noMacroPlan(), fixture.noMacroReplay(), forged));
    }

    private static Fixture fixture(int count, double coreR, double noMacroR) throws Exception {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        Instant start = Instant.parse(profile.path("windows").path("decision_start").asText());
        ObjectNode precommit = readPrecommit();
        ObjectNode physicalFreeze = JsonHashes.mapper().createObjectNode();
        physicalFreeze.set("profile", profile.deepCopy());
        physicalFreeze.set("precommit", precommit.deepCopy());
        ObjectNode inventory = coreInventory();
        ObjectNode coreReplay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY");
        coreReplay.putObject("freeze").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        ArrayNode coreRows = coreReplay.putArray("opportunities");
        ArrayNode routeAudit = coreReplay.putArray("route_audit");
        ArrayList<Instant> decisionTimes = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Instant decision = start.plus(Duration.ofDays(10L + 67L * index));
            decisionTimes.add(decision);
            String pairId = "paired-" + index;
            String setupId = "setup-" + index;
            String branch = index % 2 == 0 ? "CONTINUATION" : "REVERSAL";
            String direction = index % 2 == 0 ? "SHORT" : "LONG";
            ObjectNode intent = initialIntent(pairId, setupId, decision, branch, direction);
            String intentSha = JsonHashes.canonicalSha256(intent);
            Instant fill = decision.plusSeconds(60), exit = decision.plus(Duration.ofDays(2));
            ObjectNode core = opportunity(CORE_ROUTED_ID, pairId, setupId, decision, branch, direction, intent, intentSha)
                    .put("outcome_state", "CLOSED_TRADE").put("first_fill_time", fill.toString())
                    .put("exit_time", exit.toString()).put("outcome_available_time", exit.toString())
                    .put("net_pnl_usdt", 200.0 * coreR).put("reference_risk_usdt", 200.0);
            coreRows.add(core);
            ObjectNode audit = routeAudit.addObject().put("status", "CONFIRMED_STAGE_ONE_INTENT")
                    .put("candidate_id", CORE_ROUTED_ID).put("intent_id", intent.path("intent_id").asText())
                    .put("setup_id", setupId).put("pair_id", pairId).put("asset", "BTC")
                    .put("decision_time", decision.toString()).put("branch", branch).put("direction", direction)
                    .put("initial_intent_sha256", intentSha);
            audit.set("initial_intent", intent.deepCopy());
        }
        rehash(coreReplay);
        ObjectNode coreEvidence = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EVIDENCE_SCHEMA).put("version", 1)
                .put("status", "BLOCKED").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("replay_result_sha256", coreReplay.path("content_sha256").asText())
                .put("promotion_permitted", false).put("trade_authorization_permitted", false);
        coreEvidence.putObject("acceptance_gates").put("all_core_gates", false);
        coreEvidence.putArray("advancement_blockers").add("SYNTHETIC_FIXTURE");
        rehash(coreEvidence);
        ObjectNode plan = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(coreReplay, coreEvidence, inventory);
        ObjectNode noMacroReplay = stagedReplay(plan, decisionTimes, noMacroR);
        return new Fixture(physicalFreeze, inventory, coreReplay, plan, noMacroReplay, List.copyOf(decisionTimes));
    }

    private static ObjectNode stagedReplay(ObjectNode plan, List<Instant> decisionTimes, double stagedR) {
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", plan.path("source_mode").asText())
                .put("candidate_id", plan.path("candidate_id").asText())
                .put("mode_id", plan.path("mode_id").asText())
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText());
        replay.set("staged_plan", plan.deepCopy());
        ArrayNode opportunities = replay.putArray("opportunities");
        ObjectNode account = JsonHashes.mapper().createObjectNode();
        ArrayNode positions = account.putArray("positions");
        account.putArray("closed_episodes");
        for (int index = 0; index < decisionTimes.size(); index++) {
            Instant decision = decisionTimes.get(index);
            String pairId = "paired-" + index, setupId = "setup-" + index;
            String branch = index % 2 == 0 ? "CONTINUATION" : "REVERSAL";
            String direction = index % 2 == 0 ? "SHORT" : "LONG";
            ObjectNode anchor = (ObjectNode) plan.path("decision_anchors").get(index);
            ObjectNode opportunity = opportunity(plan.path("candidate_id").asText(), pairId, setupId,
                    decision, branch, direction, (ObjectNode) anchor.path("initial_intent"),
                    anchor.path("initial_intent_sha256").asText());
            Instant fill = decision.plusSeconds(60), exit = decision.plus(Duration.ofDays(2));
            opportunity.put("outcome_state", "CLOSED_TRADE").put("first_fill_time", fill.toString())
                    .put("exit_time", exit.toString()).put("outcome_available_time", exit.toString())
                    .put("net_pnl_usdt", 1000.0 * stagedR).put("reference_risk_usdt", 1000.0)
                    .put("first_fill_reference_equity_usdt", 20_000.0)
                    .put("full_position_reference_risk_usdt", 1_000.0)
                    .put("full_position_reference_risk_basis", LiquidationV2StagedCandidateInventoryV1.FULL_POSITION_R_BASIS)
                    .put("position_episode_id", "episode-" + index).put("filled_quantity", 10.0).put("fill_price", 100.0);
            opportunities.add(opportunity);
            ObjectNode position = positions.addObject().put("status", "CLOSED").put("active_setup_id", setupId)
                    .put("asset", "BTC").put("first_fill_time", fill.toEpochMilli())
                    .put("entry_costs_usdt", 10.0).put("exit_costs_usdt", 10.0)
                    .put("funding_debits_for_risk_headroom_usdt", 10.0);
            position.putArray("exits").addObject().put("exit_time", exit.toString());
        }
        ObjectNode refs = replay.putObject("pre_outcome_exposure_attempts")
                .put("schema", "liquidation-v2-pre-outcome-exposure-refs/1").put("attempt_count", 0);
        refs.putArray("attempts"); rehash(refs);
        ObjectNode ledger = replay.putObject("ledger");
        ledger.putArray("accounts").addObject().put("candidate_id", plan.path("candidate_id").asText())
                .set("account", account);
        rehash(ledger);
        replay.put("ledger_sha256", ledger.path("content_sha256").asText());
        double pnl = 1000.0 * stagedR * decisionTimes.size();
        ObjectNode path = replay.putObject("account_path_summary")
                .put("schema", "liquidation-v2-account-path-summary/1")
                .put("ledger_sha256", ledger.path("content_sha256").asText())
                .put("drawdown_basis", "ADVERSE_MARK_OHLC_EXTREMUM_BEFORE_EXIT_PATH")
                .put("maximum_adverse_mark_drawdown_fraction", 0.02)
                .put("starting_equity_usdt", 20_000.0).put("ending_marked_equity_usdt", 20_000.0 + pnl);
        rehash(path); rehash(replay);
        return replay;
    }

    private static ObjectNode opportunity(String candidate, String pair, String setup, Instant decision,
            String branch, String direction, ObjectNode intent, String intentSha) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("candidate_id", candidate)
                .put("variant", ROUTED).put("stage", 1).put("pair_id", pair).put("asset", "BTC")
                .put("decision_time", decision.toString()).put("branch", branch).put("direction", direction)
                .put("setup_id", setup).put("initial_intent_sha256", intentSha);
        row.set("initial_intent", intent.deepCopy());
        return row;
    }

    private static ObjectNode initialIntent(String pair, String setup, Instant decision, String branch, String direction) {
        ObjectNode seed = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-anchor-setup-seed/1").put("version", 1).put("setup_id", setup);
        rehash(seed);
        ObjectNode intent = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-confirmed-entry-intent/1").put("version", 1)
                .put("intent_id", "intent-" + pair).put("setup_id", setup).put("pair_id", pair)
                .put("asset", "BTC").put("variant", ROUTED).put("branch", branch).put("direction", direction)
                .put("stage", 1).put("decision_time", decision.toString()).put("diagnostic_only", false);
        intent.set("setup_seed", seed); rehash(intent);
        return intent;
    }

    private static ObjectNode coreInventory() {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA).put("version", 1)
                .put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode rows = inventory.putArray("current_candidates");
        rows.addObject().put("candidate_id", "liquidation-v2-core-routed-one-entry").put("variant", ROUTED);
        rows.addObject().put("candidate_id", "liquidation-v2-core-always-continuation-one-entry").put("variant", "ALWAYS_CONTINUATION_CONTROL");
        rows.addObject().put("candidate_id", "liquidation-v2-core-always-reversal-one-entry").put("variant", "ALWAYS_REVERSAL_CONTROL");
        rows.addObject().put("candidate_id", "liquidation-v2-price-oi-only-event-diagnostic").put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        rehash(inventory);
        return inventory;
    }

    private static ObjectNode readPrecommit() throws Exception {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) {
            cursor = cursor.getParent();
        }
        if (cursor == null) throw new IllegalStateException("frozen precommit was not found from test cwd");
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readString(
                cursor.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json")));
    }

    private static void rehash(ObjectNode node) {
        node.remove("content_sha256");
        node.put("content_sha256", JsonHashes.ownHash(node));
    }

    private record Fixture(ObjectNode physicalFreeze, ObjectNode inventory, ObjectNode coreReplay,
            ObjectNode noMacroPlan, ObjectNode noMacroReplay, List<Instant> planDecisionTimes) {}
}

package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Adversarial recompute checks for the exact staged macro predecessor chain. */
class LiquidationV2StagedStatisticsMacroBindingMatrixTest {
    private static final String CORE_ID = "liquidation-v2-core-routed-one-entry";
    private static final String VARIANT = "ROUTED_REVERSAL_CONTINUATION";
    private static final String ASSET = "BTC";
    private static final String PAIR = "macro-binding-pair";
    private static final String SETUP = "macro-binding-setup";

    @Test
    void macroRecomputeRequiresTheExactRehashedNoMacroPlanAndEvidence() throws Exception {
        Fixture fixture = fixture();
        assertDoesNotThrow(() -> evaluate(fixture, fixture.macroPlan(), fixture.noMacroReplay(),
                fixture.macroReplay(), fixture.noMacroPlan(), fixture.noMacroEvidence()));

        reject(fixture, fixture.macroPlan(), fixture.noMacroReplay(), fixture.macroReplay(), null,
                fixture.noMacroEvidence(), "macro-last statistics require the exact no-macro plan and evidence");
        reject(fixture, fixture.macroPlan(), fixture.noMacroReplay(), fixture.macroReplay(),
                fixture.noMacroPlan(), null, "macro-last statistics require the exact no-macro plan and evidence");

        ObjectNode stalePlan = fixture.noMacroPlan().deepCopy().put("mode_id", "THREE_STAGE_MACRO");
        reject(fixture, fixture.macroPlan(), fixture.noMacroReplay(), fixture.macroReplay(), stalePlan,
                fixture.noMacroEvidence(), "no-macro predecessor plan content hash is invalid");

        ObjectNode staleEvidence = fixture.noMacroEvidence().deepCopy().put("status", "REBOUND");
        reject(fixture, fixture.macroPlan(), fixture.noMacroReplay(), fixture.macroReplay(),
                fixture.noMacroPlan(), staleEvidence, "no-macro predecessor evidence content hash is invalid");
    }

    @Test
    void rehashedPredecessorMutationsMustStillMatchAnchorAndEvidenceLineage() throws Exception {
        Fixture fixture = fixture();
        ObjectNode wrongModePredecessor = fixture.noMacroPlan().deepCopy().put("mode_id", "THREE_STAGE_MACRO");
        rehash(wrongModePredecessor);
        ObjectNode modeBoundPlan = fixture.macroPlan().deepCopy()
                .put("predecessor_plan_sha256", wrongModePredecessor.path("content_sha256").asText());
        rehash(modeBoundPlan);
        reject(fixture, modeBoundPlan, fixture.noMacroReplay(), rebind(fixture.macroReplay(), modeBoundPlan),
                wrongModePredecessor, fixture.noMacroEvidence(),
                "macro-last comparison does not bind its exact no-macro predecessor and common anchors");

        ObjectNode changedAnchorPlan = fixture.noMacroPlan().deepCopy().put("anchor_inventory_sha256", "f".repeat(64));
        rehash(changedAnchorPlan);
        ObjectNode reboundMacroPlan = fixture.macroPlan().deepCopy()
                .put("predecessor_plan_sha256", changedAnchorPlan.path("content_sha256").asText());
        rehash(reboundMacroPlan);
        ObjectNode reboundMacroReplay = rebind(fixture.macroReplay(), reboundMacroPlan);
        reject(fixture, reboundMacroPlan, fixture.noMacroReplay(), reboundMacroReplay, changedAnchorPlan,
                fixture.noMacroEvidence(), "macro-last comparison does not bind its exact no-macro predecessor and common anchors");

        ObjectNode changedEvidence = fixture.noMacroEvidence().deepCopy()
                .put("replay_result_sha256", "e".repeat(64));
        rehash(changedEvidence);
        ObjectNode evidenceBoundMacroPlan = fixture.macroPlan().deepCopy()
                .put("predecessor_evidence_sha256", changedEvidence.path("content_sha256").asText());
        rehash(evidenceBoundMacroPlan);
        ObjectNode evidenceBoundReplay = rebind(fixture.macroReplay(), evidenceBoundMacroPlan);
        reject(fixture, evidenceBoundMacroPlan, fixture.noMacroReplay(), evidenceBoundReplay,
                fixture.noMacroPlan(), changedEvidence,
                "macro-last comparison does not bind its exact no-macro predecessor and common anchors");
    }

    private static void reject(Fixture fixture, ObjectNode plan, ObjectNode baseline, ObjectNode staged,
            ObjectNode predecessorPlan, ObjectNode predecessorEvidence, String message) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> evaluate(fixture, plan, baseline, staged, predecessorPlan, predecessorEvidence));
        assertTrue(error.getMessage().contains(message),
                () -> "expected '" + message + "', got '" + error.getMessage() + "'");
    }

    private static LiquidationV2StagedStatisticsV1.Evaluation evaluate(Fixture fixture, ObjectNode plan,
            ObjectNode baseline, ObjectNode staged, ObjectNode predecessorPlan, ObjectNode predecessorEvidence) {
        return LiquidationV2StagedStatisticsV1.recompute(fixture.physicalFreeze(), plan, baseline, staged,
                predecessorPlan, predecessorEvidence, null);
    }

    private static Fixture fixture() throws Exception {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        ObjectNode physical = JsonHashes.mapper().createObjectNode();
        physical.set("profile", profile.deepCopy()); physical.set("precommit", readPrecommit());
        Instant decision = Instant.parse(profile.path("windows").path("decision_start").asText()).plusSeconds(5);
        Instant fill = decision.plusSeconds(60), exit = decision.plusSeconds(86_400);
        ObjectNode inventory = inventory();
        ObjectNode intent = intent(decision);
        String intentSha = JsonHashes.canonicalSha256(intent);
        ObjectNode coreOpportunity = opportunity(CORE_ID, PAIR, decision, intent, intentSha)
                .put("outcome_state", "CLOSED_TRADE").put("first_fill_time", fill.toString())
                .put("exit_time", exit.toString()).put("outcome_available_time", exit.toString())
                .put("net_pnl_usdt", 200).put("reference_risk_usdt", 200);
        ObjectNode coreReplay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY");
        coreReplay.putObject("freeze").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        coreReplay.putArray("opportunities").add(coreOpportunity);
        ObjectNode audit = coreReplay.putArray("route_audit").addObject()
                .put("status", "CONFIRMED_STAGE_ONE_INTENT").put("candidate_id", CORE_ID)
                .put("intent_id", intent.path("intent_id").asText()).put("setup_id", SETUP)
                .put("pair_id", PAIR).put("asset", ASSET).put("decision_time", decision.toString())
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("initial_intent_sha256", intentSha);
        audit.set("initial_intent", intent.deepCopy());
        rehash(coreReplay);
        ObjectNode coreEvidence = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EVIDENCE_SCHEMA).put("version", 1)
                .put("status", "BLOCKED").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("replay_result_sha256", coreReplay.path("content_sha256").asText())
                .put("promotion_permitted", false).put("trade_authorization_permitted", false);
        coreEvidence.putArray("advancement_blockers").add("SYNTHETIC_ONLY"); rehash(coreEvidence);
        ObjectNode noMacroPlan = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(coreReplay, coreEvidence, inventory);
        ObjectNode noMacroReplay = replay(noMacroPlan, decision, fill, exit);
        LiquidationV2StagedStatisticsV1.Evaluation noMacroEvaluation = evaluate(
                new Fixture(physical, coreReplay, noMacroPlan, noMacroReplay, null, null, null), noMacroPlan,
                coreReplay, noMacroReplay, null, null);
        ObjectNode noMacroEvidence = LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(
                noMacroPlan, noMacroReplay, noMacroEvaluation);
        ObjectNode macroPlan = LiquidationV2StagedCandidateInventoryV1.freezeMacro(
                noMacroPlan, noMacroReplay, noMacroEvidence, noMacroEvaluation);
        ObjectNode macroReplay = rebind(noMacroReplay, macroPlan);
        return new Fixture(physical, coreReplay, noMacroPlan, noMacroReplay, noMacroEvidence, macroPlan, macroReplay);
    }

    private static ObjectNode replay(ObjectNode plan, Instant decision, Instant fill, Instant exit) {
        ObjectNode intent = (ObjectNode) plan.path("decision_anchors").get(0).path("initial_intent");
        ObjectNode row = opportunity(plan.path("candidate_id").asText(), PAIR, decision, intent,
                plan.path("decision_anchors").get(0).path("initial_intent_sha256").asText())
                .put("outcome_state", "CLOSED_TRADE").put("first_fill_time", fill.toString())
                .put("position_episode_id", "episode-1").put("first_fill_reference_equity_usdt", 20_000)
                .put("full_position_reference_risk_usdt", 1_000).put("reference_risk_usdt", 1_000)
                .put("full_position_reference_risk_basis", LiquidationV2StagedCandidateInventoryV1.FULL_POSITION_R_BASIS)
                .put("filled_quantity", 10).put("fill_price", 100).put("exit_time", exit.toString())
                .put("outcome_available_time", exit.toString()).put("net_pnl_usdt", 1_000);
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", plan.path("source_mode").asText()).put("candidate_id", plan.path("candidate_id").asText())
                .put("mode_id", plan.path("mode_id").asText()).put("plan_sha256", plan.path("content_sha256").asText())
                .put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText());
        replay.set("staged_plan", plan.deepCopy()); replay.putArray("opportunities").add(row);
        ObjectNode refs = replay.putObject("pre_outcome_exposure_attempts")
                .put("schema", "liquidation-v2-pre-outcome-exposure-refs/1").put("attempt_count", 0);
        refs.putArray("attempts"); rehash(refs);
        ObjectNode position = JsonHashes.mapper().createObjectNode().put("status", "CLOSED")
                .put("active_setup_id", SETUP).put("asset", ASSET).put("first_fill_time", fill.toEpochMilli())
                .put("entry_costs_usdt", 10).put("exit_costs_usdt", 20).put("funding_debits_for_risk_headroom_usdt", 10);
        position.putArray("exits").addObject().put("exit_time", exit.toString());
        ObjectNode account = JsonHashes.mapper().createObjectNode(); account.putArray("closed_episodes"); account.putArray("positions").add(position);
        ObjectNode ledger = replay.putObject("ledger");
        ledger.putArray("accounts").addObject().put("candidate_id", plan.path("candidate_id").asText()).set("account", account);
        rehash(ledger); replay.put("ledger_sha256", ledger.path("content_sha256").asText());
        ObjectNode path = replay.putObject("account_path_summary")
                .put("schema", "liquidation-v2-account-path-summary/1").put("ledger_sha256", ledger.path("content_sha256").asText())
                .put("drawdown_basis", "ADVERSE_MARK_OHLC_EXTREMUM_BEFORE_EXIT_PATH")
                .put("maximum_adverse_mark_drawdown_fraction", 0.02).put("starting_equity_usdt", 20_000).put("ending_marked_equity_usdt", 21_000);
        rehash(path); rehash(replay); return replay;
    }

    private static ObjectNode rebind(ObjectNode source, ObjectNode plan) {
        ObjectNode replay = source.deepCopy().put("candidate_id", plan.path("candidate_id").asText())
                .put("mode_id", plan.path("mode_id").asText()).put("plan_sha256", plan.path("content_sha256").asText());
        replay.put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText()); replay.set("staged_plan", plan.deepCopy());
        ((ObjectNode) replay.path("opportunities").get(0)).put("candidate_id", plan.path("candidate_id").asText());
        ((ObjectNode) replay.path("ledger").path("accounts").get(0)).put("candidate_id", plan.path("candidate_id").asText());
        ObjectNode ledger = (ObjectNode) replay.path("ledger"); rehash(ledger);
        replay.put("ledger_sha256", ledger.path("content_sha256").asText());
        ObjectNode path = (ObjectNode) replay.path("account_path_summary"); path.put("ledger_sha256", ledger.path("content_sha256").asText()); rehash(path);
        rehash(replay); return replay;
    }

    private static ObjectNode opportunity(String candidate, String pair, Instant decision, ObjectNode intent, String intentSha) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("candidate_id", candidate).put("variant", VARIANT)
                .put("stage", 1).put("pair_id", pair).put("asset", ASSET).put("decision_time", decision.toString())
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("setup_id", SETUP)
                .put("initial_intent_sha256", intentSha);
        row.set("initial_intent", intent.deepCopy()); return row;
    }

    private static ObjectNode intent(Instant decision) {
        ObjectNode seed = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-anchor-setup-seed/1")
                .put("version", 1).put("setup_id", SETUP); rehash(seed);
        ObjectNode intent = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-confirmed-entry-intent/1")
                .put("version", 1).put("intent_id", "macro-intent").put("setup_id", SETUP).put("pair_id", PAIR)
                .put("asset", ASSET).put("variant", VARIANT).put("branch", "CONTINUATION").put("direction", "SHORT")
                .put("stage", 1).put("decision_time", decision.toString()).put("diagnostic_only", false);
        intent.set("setup_seed", seed); rehash(intent); return intent;
    }

    private static ObjectNode inventory() {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode().put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA)
                .put("version", 1).put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode rows = inventory.putArray("current_candidates");
        rows.addObject().put("candidate_id", CORE_ID).put("variant", VARIANT);
        rows.addObject().put("candidate_id", "liquidation-v2-core-always-continuation-one-entry").put("variant", "ALWAYS_CONTINUATION_CONTROL");
        rows.addObject().put("candidate_id", "liquidation-v2-core-always-reversal-one-entry").put("variant", "ALWAYS_REVERSAL_CONTROL");
        rows.addObject().put("candidate_id", "liquidation-v2-price-oi-only-event-diagnostic").put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        rehash(inventory); return inventory;
    }

    private static ObjectNode readPrecommit() throws Exception {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) cursor = cursor.getParent();
        if (cursor == null) throw new IllegalStateException("frozen precommit missing");
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readString(cursor.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json")));
    }

    private static void rehash(ObjectNode node) { node.remove("content_sha256"); node.put("content_sha256", JsonHashes.ownHash(node)); }

    private record Fixture(ObjectNode physicalFreeze, ObjectNode coreReplay, ObjectNode noMacroPlan,
            ObjectNode noMacroReplay, ObjectNode noMacroEvidence, ObjectNode macroPlan, ObjectNode macroReplay) {}
}

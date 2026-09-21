package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Exercises paired statistics against realistic filled-position ledger shapes. */
class LiquidationV2StagedStatisticsLedgerLifecycleMatrixTest {
    private static final String CORE_ID = "liquidation-v2-core-routed-one-entry";
    private static final String CORE_VARIANT = "ROUTED_REVERSAL_CONTINUATION";
    private static final String ASSET = "BTC";
    private static final String PAIR = "ledger-pair-1";
    private static final String SETUP = "ledger-setup-1";

    @Test
    void closedEpisodeLedgerWinsOverPositionFallbackAndPositionFallbackRemainsUsable() throws Exception {
        Fixture fixture = fixture();
        ObjectNode replay = fixture.stagedReplay().deepCopy();
        ObjectNode account = account(replay);
        var originalPositionCosts = evaluate(fixture, replay);
        assertEquals(0.04, originalPositionCosts.statistics().path("mean_position_cost_r").asDouble(), 1e-12);
        assertTrue(originalPositionCosts.gates().path("maximum_cost_r").asBoolean());

        ObjectNode position = (ObjectNode) account.path("positions").get(0);
        position.put("entry_costs_usdt", 900).put("exit_costs_usdt", 900)
                .put("funding_debits_for_risk_headroom_usdt", 900);
        ObjectNode closed = ((ArrayNode) account.path("closed_episodes")).addObject()
                .put("status", "CLOSED").put("active_setup_id", SETUP).put("asset", ASSET)
                .put("first_fill_time", position.path("first_fill_time").asLong())
                .put("entry_costs_usdt", 5).put("exit_costs_usdt", 10)
                .put("funding_debits_for_risk_headroom_usdt", 15);
        closed.putArray("exits").addObject().put("exit_time", fixture.exit().toString());
        rehashReplay(replay);

        var episodePreferred = evaluate(fixture, replay);
        assertEquals(0.03, episodePreferred.statistics().path("mean_position_cost_r").asDouble(), 1e-12);

        ((ArrayNode) account.path("closed_episodes")).removeAll();
        rehashReplay(replay);
        var positionFallback = evaluate(fixture, replay);
        assertEquals(2.7, positionFallback.statistics().path("mean_position_cost_r").asDouble(), 1e-12);
        assertFalse(positionFallback.gates().path("maximum_cost_r").asBoolean());
    }

    @Test
    void missingRequiredLedgerCostBlocksOnlyTheCostEvidence() throws Exception {
        Fixture fixture = fixture();
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                row -> row.remove("exit_costs_usdt"),
                row -> row.put("funding_debits_for_risk_headroom_usdt", -1))) {
            ObjectNode replay = fixture.stagedReplay().deepCopy();
            mutation.accept((ObjectNode) account(replay).path("positions").get(0));
            rehashReplay(replay);

            var evaluation = evaluate(fixture, replay);
            assertTrue(evaluation.statistics().path("mean_position_cost_r").isNull());
            assertFalse(evaluation.gates().path("maximum_cost_r").asBoolean());
            assertTrue(evaluation.blockers().toString().contains("POSITION_COST_R_INCOMPLETE_REQUIRED_FEE_SLIPPAGE_OR_FUNDING_COST_ROW_MISSING"));
            assertTrue(evaluation.gates().path("all_anchor_outcomes_resolved").asBoolean());
        }
    }

    @Test
    void ledgerEpisodeLookupRequiresTheMatchingCandidateAndClosedPositionIdentity() throws Exception {
        Fixture fixture = fixture();
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                row -> row.put("candidate_id", "different-candidate"),
                row -> { accountFromRow(row).remove("closed_episodes"); accountFromRow(row).remove("positions"); },
                row -> accountFromRow(row).remove("positions"),
                row -> accountFromRow(row).putArray("positions"),
                row -> ((ObjectNode) accountFromRow(row).path("positions").get(0)).put("status", "OPEN"),
                row -> ((ObjectNode) accountFromRow(row).path("positions").get(0)).remove("exits"),
                row -> ((ObjectNode) accountFromRow(row).path("positions").get(0)).put("active_setup_id", "other-setup"),
                row -> ((ObjectNode) accountFromRow(row).path("positions").get(0)).put("asset", "ETH"))) {
            ObjectNode replay = fixture.stagedReplay().deepCopy();
            ObjectNode accountRow = (ObjectNode) replay.path("ledger").path("accounts").get(0);
            mutation.accept(accountRow);
            rehashReplay(replay);
            var evaluation = evaluate(fixture, replay);
            assertTrue(evaluation.statistics().path("mean_position_cost_r").isNull());
            assertFalse(evaluation.gates().path("maximum_cost_r").asBoolean());
        }
    }

    @Test
    void filledTranchesAccumulateActualExposureAndMissingRiskAllowanceFailsAttribution() throws Exception {
        Fixture fixture = fixture();
        ObjectNode replay = fixture.stagedReplay().deepCopy();
        ObjectNode tranche = replay.putArray("stage_attempts").addObject()
                .put("outcome_state", "STAGE_FILLED").put("asset", ASSET)
                .put("planned_tranche_risk_usdt", 50).put("filled_quantity", 2).put("fill_price", 110);
        rehashReplay(replay);

        var complete = evaluate(fixture, replay);
        ObjectNode attribution = (ObjectNode) complete.statistics().path("risk_exposure_attribution");
        assertTrue(complete.gates().path("risk_exposure_matched_attribution_reported").asBoolean());
        assertEquals(250, attribution.path("staged_planned_tranche_risk_allowance_sum_usdt").asDouble());
        assertEquals(2, attribution.path("staged_actual_fill_count").asInt());
        assertEquals(12, attribution.path("actual_filled_exposure_by_asset").get(0).path("filled_quantity").asDouble());
        assertEquals(1220, attribution.path("actual_filled_exposure_by_asset").get(0).path("entry_notional_usdt").asDouble());

        tranche.remove("planned_tranche_risk_usdt");
        rehashReplay(replay);
        var missingAllowance = evaluate(fixture, replay);
        assertFalse(missingAllowance.gates().path("risk_exposure_matched_attribution_reported").asBoolean());
        assertTrue(missingAllowance.blockers().toString().contains("RISK_EXPOSURE_ATTRIBUTION_INCOMPLETE"));
        assertEquals(2, missingAllowance.statistics().path("risk_exposure_attribution")
                .path("staged_actual_fill_count").asInt(), "actual fills remain reportable when planned allowance is incomplete");
    }

    @Test
    void incompleteFillIdentityAndInvalidAdversePathFailTheirOwnGates() throws Exception {
        Fixture fixture = fixture();
        ObjectNode replay = fixture.stagedReplay().deepCopy();
        ((ObjectNode) replay.path("opportunities").get(0)).remove("filled_quantity");
        ObjectNode path = (ObjectNode) replay.path("account_path_summary");
        path.remove("maximum_adverse_mark_drawdown_fraction");
        rehash(path);
        rehashReplay(replay);

        var evaluation = evaluate(fixture, replay);
        assertFalse(evaluation.gates().path("risk_exposure_matched_attribution_reported").asBoolean());
        assertFalse(evaluation.gates().path("portfolio_minimum_net_pnl").asBoolean());
        assertFalse(evaluation.gates().path("portfolio_maximum_drawdown_pct").asBoolean());
        assertTrue(evaluation.statistics().path("portfolio_net_pnl_usdt").isNull());
        assertTrue(evaluation.blockers().toString().contains("HASH_BOUND_ADVERSE_INTRATRADE_ACCOUNT_PATH_UNAVAILABLE"));
    }

    private static LiquidationV2StagedStatisticsV1.Evaluation evaluate(Fixture fixture, ObjectNode replay) {
        return LiquidationV2StagedStatisticsV1.recompute(fixture.physicalFreeze(), fixture.plan(),
                fixture.coreReplay(), replay, null, null, null);
    }

    private static ObjectNode account(ObjectNode replay) {
        return (ObjectNode) replay.path("ledger").path("accounts").get(0).path("account");
    }

    private static ObjectNode accountFromRow(ObjectNode accountRow) {
        return (ObjectNode) accountRow.path("account");
    }

    private static Fixture fixture() throws Exception {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        ObjectNode physicalFreeze = JsonHashes.mapper().createObjectNode().set("profile", profile.deepCopy());
        physicalFreeze.set("precommit", readPrecommit());
        Instant decision = Instant.parse(profile.path("windows").path("decision_start").asText()).plusSeconds(1);
        Instant fill = decision.plusSeconds(60), exit = decision.plusSeconds(86_400);
        ObjectNode inventory = coreInventory();
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
        ObjectNode audit = coreReplay.putArray("route_audit").addObject().put("status", "CONFIRMED_STAGE_ONE_INTENT")
                .put("candidate_id", CORE_ID).put("intent_id", intent.path("intent_id").asText())
                .put("setup_id", SETUP).put("pair_id", PAIR).put("asset", ASSET)
                .put("decision_time", decision.toString()).put("branch", "CONTINUATION").put("direction", "SHORT")
                .put("initial_intent_sha256", intentSha);
        audit.set("initial_intent", intent.deepCopy());
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

        ObjectNode stagedOpportunity = opportunity(plan.path("candidate_id").asText(), PAIR, decision,
                (ObjectNode) plan.path("decision_anchors").get(0).path("initial_intent"), intentSha)
                .put("outcome_state", "CLOSED_TRADE").put("first_fill_time", fill.toString())
                .put("exit_time", exit.toString()).put("outcome_available_time", exit.toString())
                .put("net_pnl_usdt", 1000).put("reference_risk_usdt", 1000)
                .put("first_fill_reference_equity_usdt", 20_000).put("full_position_reference_risk_usdt", 1000)
                .put("full_position_reference_risk_basis", LiquidationV2StagedCandidateInventoryV1.FULL_POSITION_R_BASIS)
                .put("position_episode_id", "episode-1").put("filled_quantity", 10).put("fill_price", 100);
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY").put("candidate_id", plan.path("candidate_id").asText())
                .put("mode_id", plan.path("mode_id").asText()).put("plan_sha256", plan.path("content_sha256").asText())
                .put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText());
        replay.set("staged_plan", plan.deepCopy());
        replay.putArray("opportunities").add(stagedOpportunity);
        ObjectNode refs = replay.putObject("pre_outcome_exposure_attempts")
                .put("schema", "liquidation-v2-pre-outcome-exposure-refs/1").put("attempt_count", 0);
        refs.putArray("attempts"); rehash(refs);
        ObjectNode position = JsonHashes.mapper().createObjectNode().put("status", "CLOSED")
                .put("active_setup_id", SETUP).put("asset", ASSET).put("first_fill_time", fill.toEpochMilli())
                .put("entry_costs_usdt", 10).put("exit_costs_usdt", 20)
                .put("funding_debits_for_risk_headroom_usdt", 10);
        position.putArray("exits").addObject().put("exit_time", exit.toString());
        ObjectNode account = JsonHashes.mapper().createObjectNode();
        account.putArray("closed_episodes"); account.putArray("positions").add(position);
        ObjectNode ledger = replay.putObject("ledger");
        ledger.putArray("accounts").addObject().put("candidate_id", plan.path("candidate_id").asText()).set("account", account);
        rehash(ledger); replay.put("ledger_sha256", ledger.path("content_sha256").asText());
        ObjectNode path = replay.putObject("account_path_summary")
                .put("schema", "liquidation-v2-account-path-summary/1").put("ledger_sha256", ledger.path("content_sha256").asText())
                .put("drawdown_basis", "ADVERSE_MARK_OHLC_EXTREMUM_BEFORE_EXIT_PATH")
                .put("maximum_adverse_mark_drawdown_fraction", 0.02).put("starting_equity_usdt", 20_000)
                .put("ending_marked_equity_usdt", 21_000);
        rehash(path); rehash(replay);
        return new Fixture(physicalFreeze, coreReplay, plan, replay, exit);
    }

    private static ObjectNode opportunity(String candidate, String pair, Instant decision, ObjectNode intent, String intentSha) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("candidate_id", candidate)
                .put("variant", CORE_VARIANT).put("stage", 1).put("pair_id", pair).put("asset", ASSET)
                .put("decision_time", decision.toString()).put("branch", "CONTINUATION").put("direction", "SHORT")
                .put("setup_id", SETUP).put("initial_intent_sha256", intentSha);
        row.set("initial_intent", intent.deepCopy()); return row;
    }

    private static ObjectNode intent(Instant decision) {
        ObjectNode seed = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-anchor-setup-seed/1")
                .put("version", 1).put("setup_id", SETUP); rehash(seed);
        ObjectNode intent = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-confirmed-entry-intent/1")
                .put("version", 1).put("intent_id", "ledger-intent").put("setup_id", SETUP).put("pair_id", PAIR)
                .put("asset", ASSET).put("variant", CORE_VARIANT).put("branch", "CONTINUATION")
                .put("direction", "SHORT").put("stage", 1).put("decision_time", decision.toString()).put("diagnostic_only", false);
        intent.set("setup_seed", seed); rehash(intent); return intent;
    }

    private static ObjectNode coreInventory() {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode().put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA)
                .put("version", 1).put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode candidates = inventory.putArray("current_candidates");
        candidates.addObject().put("candidate_id", CORE_ID).put("variant", CORE_VARIANT);
        candidates.addObject().put("candidate_id", "liquidation-v2-core-always-continuation-one-entry").put("variant", "ALWAYS_CONTINUATION_CONTROL");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-always-reversal-one-entry").put("variant", "ALWAYS_REVERSAL_CONTROL");
        candidates.addObject().put("candidate_id", "liquidation-v2-price-oi-only-event-diagnostic").put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        rehash(inventory); return inventory;
    }

    private static ObjectNode readPrecommit() throws Exception {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) cursor = cursor.getParent();
        if (cursor == null) throw new IllegalStateException("cannot locate frozen liquidation precommit");
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readString(cursor.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json")));
    }

    private static void rehashReplay(ObjectNode replay) {
        ObjectNode ledger = (ObjectNode) replay.path("ledger"); rehash(ledger);
        replay.put("ledger_sha256", ledger.path("content_sha256").asText());
        ObjectNode path = (ObjectNode) replay.path("account_path_summary"); path.put("ledger_sha256", ledger.path("content_sha256").asText()); rehash(path);
        rehash(replay);
    }

    private static void rehash(ObjectNode node) { node.remove("content_sha256"); node.put("content_sha256", JsonHashes.ownHash(node)); }

    private record Fixture(ObjectNode physicalFreeze, ObjectNode coreReplay, ObjectNode plan,
            ObjectNode stagedReplay, Instant exit) {}
}

package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Exercises paired-statistics validation of predecessor outcomes at their causal boundaries. */
class LiquidationV2StagedBaselineOutcomeValidationMatrixTest {
    private static final String CORE_ID = "liquidation-v2-core-routed-one-entry";
    private static final String ROUTED = "ROUTED_REVERSAL_CONTINUATION";
    private static final String ASSET = "BTC";
    private static final String PAIR = "baseline-validation-pair";
    private static final String SETUP = "baseline-validation-setup";
    private static final Instant DECISION = Instant.parse("2024-01-20T12:00:00Z");
    private static final Instant FILL = DECISION.plusSeconds(60);
    private static final Instant EXIT = DECISION.plus(Duration.ofDays(1));

    @Test
    void resolvedNoTradePredecessorIsAnExplicitZeroAvailableAtDecision() throws Exception {
        Fixture base = fixture();
        Fixture noTrade = rebuild(base, row -> row.put("outcome_state", "RESOLVED_NO_TRADE")
                .putNull("first_fill_time").putNull("exit_time")
                .put("outcome_available_time", DECISION.toString()).put("net_pnl_usdt", 0));

        var evaluation = evaluate(noTrade);
        assertEquals("RESOLVED_NO_TRADE", evaluation.statistics().path("paired_outcome_inventory")
                .get(0).path("predecessor_outcome_state").asText());
        assertEquals(1.0, evaluation.statistics().path("observed_incremental_mean_r_vs_predecessor").asDouble(), 1e-12);
    }

    @Test
    void noTradePredecessorRejectsMissingOrPrematureAvailabilityPositionFieldsAndNonzeroEconomics() throws Exception {
        List<Consumer<ObjectNode>> mutations = List.of(
                row -> row.putNull("outcome_available_time"),
                row -> row.put("outcome_available_time", DECISION.minusNanos(1).toString()),
                row -> row.put("first_fill_time", FILL.toString()),
                row -> row.put("exit_time", EXIT.toString()),
                row -> row.put("net_pnl_usdt", 1));
        for (Consumer<ObjectNode> mutation : mutations) {
            Fixture invalid = rebuild(fixture(), row -> {
                row.put("outcome_state", "RESOLVED_NO_TRADE").putNull("first_fill_time")
                        .putNull("exit_time").put("outcome_available_time", DECISION.toString()).put("net_pnl_usdt", 0);
                mutation.accept(row);
            });
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> evaluate(invalid));
            assertTrue(failure.getMessage().contains("paired resolved no-trade outcome is missing a valid zero and availability time"),
                    failure.getMessage());
        }
    }

    @Test
    void closedPredecessorRequiresStrictFillExitAvailabilityFinitePnlAndPositiveReferenceRisk() throws Exception {
        List<Consumer<ObjectNode>> mutations = List.of(
                row -> row.putNull("first_fill_time"),
                row -> row.put("first_fill_time", DECISION.toString()),
                row -> row.putNull("exit_time"),
                row -> row.put("exit_time", FILL.minusNanos(1).toString()),
                row -> row.putNull("outcome_available_time"),
                row -> row.put("outcome_available_time", EXIT.minusNanos(1).toString()),
                row -> row.putNull("net_pnl_usdt"),
                row -> row.putNull("reference_risk_usdt"),
                row -> row.put("reference_risk_usdt", 0));
        for (Consumer<ObjectNode> mutation : mutations) {
            Fixture invalid = rebuild(fixture(), mutation);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> evaluate(invalid));
            assertTrue(failure.getMessage().contains("paired closed-trade outcome is missing valid fill, exit, availability, PnL, or risk"),
                    failure.getMessage());
        }
    }

    private static LiquidationV2StagedStatisticsV1.Evaluation evaluate(Fixture fixture) {
        return LiquidationV2StagedStatisticsV1.recompute(fixture.physicalFreeze(), fixture.plan(),
                fixture.coreReplay(), fixture.stagedReplay(), null, null, null);
    }

    private static Fixture rebuild(Fixture base, Consumer<ObjectNode> mutateBaseline) {
        ObjectNode core = base.coreReplay().deepCopy();
        mutateBaseline.accept((ObjectNode) core.path("opportunities").get(0));
        rehash(core);
        ObjectNode evidence = base.coreEvidence().deepCopy().put("replay_result_sha256", core.path("content_sha256").asText());
        rehash(evidence);
        ObjectNode plan = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(core, evidence, base.inventory());
        ObjectNode staged = base.stagedReplay().deepCopy();
        staged.put("plan_sha256", plan.path("content_sha256").asText())
                .put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText());
        staged.set("staged_plan", plan.deepCopy());
        rehash(staged);
        return new Fixture(base.physicalFreeze(), base.inventory(), core, evidence, plan, staged);
    }

    private static Fixture fixture() throws Exception {
        ObjectNode physical = JsonHashes.mapper().createObjectNode()
                .set("profile", LiquidationDailyStressProfileV1.frozenContract());
        physical.set("precommit", readPrecommit());
        ObjectNode inventory = coreInventory();
        ObjectNode intent = intent();
        String intentHash = JsonHashes.canonicalSha256(intent);
        ObjectNode baseline = opportunity(CORE_ID, intent, intentHash)
                .put("outcome_state", "CLOSED_TRADE").put("first_fill_time", FILL.toString())
                .put("exit_time", EXIT.toString()).put("outcome_available_time", EXIT.toString())
                .put("net_pnl_usdt", 200).put("reference_risk_usdt", 200);
        ObjectNode core = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY");
        core.putObject("freeze").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        core.putArray("opportunities").add(baseline);
        ObjectNode audit = core.putArray("route_audit").addObject()
                .put("status", "CONFIRMED_STAGE_ONE_INTENT").put("candidate_id", CORE_ID)
                .put("intent_id", intent.path("intent_id").asText()).put("setup_id", SETUP)
                .put("pair_id", PAIR).put("asset", ASSET).put("decision_time", DECISION.toString())
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("initial_intent_sha256", intentHash);
        audit.set("initial_intent", intent.deepCopy());
        rehash(core);
        ObjectNode coreEvidence = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EVIDENCE_SCHEMA).put("version", 1)
                .put("status", "BLOCKED").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("replay_result_sha256", core.path("content_sha256").asText())
                .put("promotion_permitted", false).put("trade_authorization_permitted", false);
        coreEvidence.putArray("advancement_blockers").add("SYNTHETIC_FIXTURE");
        rehash(coreEvidence);
        ObjectNode plan = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(core, coreEvidence, inventory);

        ObjectNode stagedOpportunity = opportunity(plan.path("candidate_id").asText(), intent, intentHash)
                .put("outcome_state", "CLOSED_TRADE").put("first_fill_time", FILL.toString())
                .put("position_episode_id", "episode-1").put("first_fill_reference_equity_usdt", 20_000)
                .put("full_position_reference_risk_usdt", 1_000).put("reference_risk_usdt", 1_000)
                .put("full_position_reference_risk_basis", LiquidationV2StagedCandidateInventoryV1.FULL_POSITION_R_BASIS)
                .put("filled_quantity", 10).put("fill_price", 100).put("exit_time", EXIT.toString())
                .put("outcome_available_time", EXIT.toString()).put("net_pnl_usdt", 1_000);
        ObjectNode staged = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY").put("candidate_id", plan.path("candidate_id").asText())
                .put("mode_id", plan.path("mode_id").asText()).put("plan_sha256", plan.path("content_sha256").asText())
                .put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText());
        staged.set("staged_plan", plan.deepCopy());
        staged.putArray("opportunities").add(stagedOpportunity);
        ObjectNode refs = staged.putObject("pre_outcome_exposure_attempts")
                .put("schema", "liquidation-v2-pre-outcome-exposure-refs/1").put("attempt_count", 0);
        refs.putArray("attempts"); rehash(refs);
        ObjectNode position = JsonHashes.mapper().createObjectNode().put("status", "CLOSED")
                .put("active_setup_id", SETUP).put("asset", ASSET).put("first_fill_time", FILL.toEpochMilli())
                .put("entry_costs_usdt", 10).put("exit_costs_usdt", 20).put("funding_debits_for_risk_headroom_usdt", 10);
        position.putArray("exits").addObject().put("exit_time", EXIT.toString());
        ObjectNode account = JsonHashes.mapper().createObjectNode();
        account.putArray("closed_episodes"); account.putArray("positions").add(position);
        ObjectNode ledger = staged.putObject("ledger");
        ledger.putArray("accounts").addObject().put("candidate_id", plan.path("candidate_id").asText()).set("account", account);
        rehash(ledger); staged.put("ledger_sha256", ledger.path("content_sha256").asText());
        ObjectNode accountPath = staged.putObject("account_path_summary")
                .put("schema", "liquidation-v2-account-path-summary/1").put("ledger_sha256", ledger.path("content_sha256").asText())
                .put("drawdown_basis", "ADVERSE_MARK_OHLC_EXTREMUM_BEFORE_EXIT_PATH")
                .put("maximum_adverse_mark_drawdown_fraction", 0.02)
                .put("starting_equity_usdt", 20_000).put("ending_marked_equity_usdt", 21_000);
        rehash(accountPath); rehash(staged);
        return new Fixture(physical, inventory, core, coreEvidence, plan, staged);
    }

    private static ObjectNode opportunity(String candidate, ObjectNode intent, String intentHash) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("candidate_id", candidate)
                .put("variant", ROUTED).put("stage", 1).put("pair_id", PAIR).put("asset", ASSET)
                .put("decision_time", DECISION.toString()).put("branch", "CONTINUATION").put("direction", "SHORT")
                .put("setup_id", SETUP).put("initial_intent_sha256", intentHash);
        row.set("initial_intent", intent.deepCopy()); return row;
    }

    private static ObjectNode intent() {
        ObjectNode seed = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-anchor-setup-seed/1")
                .put("version", 1).put("setup_id", SETUP); rehash(seed);
        ObjectNode intent = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-confirmed-entry-intent/1")
                .put("version", 1).put("intent_id", "baseline-intent").put("setup_id", SETUP).put("pair_id", PAIR)
                .put("asset", ASSET).put("variant", ROUTED).put("branch", "CONTINUATION").put("direction", "SHORT")
                .put("stage", 1).put("decision_time", DECISION.toString()).put("diagnostic_only", false);
        intent.set("setup_seed", seed); rehash(intent); return intent;
    }

    private static ObjectNode coreInventory() {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode().put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA)
                .put("version", 1).put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode rows = inventory.putArray("current_candidates");
        rows.addObject().put("candidate_id", CORE_ID).put("variant", ROUTED);
        rows.addObject().put("candidate_id", "liquidation-v2-core-always-continuation-one-entry").put("variant", "ALWAYS_CONTINUATION_CONTROL");
        rows.addObject().put("candidate_id", "liquidation-v2-core-always-reversal-one-entry").put("variant", "ALWAYS_REVERSAL_CONTROL");
        rows.addObject().put("candidate_id", "liquidation-v2-price-oi-only-event-diagnostic").put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        rehash(inventory); return inventory;
    }

    private static ObjectNode readPrecommit() throws Exception {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) {
            cursor = cursor.getParent();
        }
        if (cursor == null) throw new IllegalStateException("frozen precommit not found from test cwd");
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readString(
                cursor.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json")));
    }

    private static void rehash(ObjectNode value) { value.remove("content_sha256"); value.put("content_sha256", JsonHashes.ownHash(value)); }
    private record Fixture(ObjectNode physicalFreeze, ObjectNode inventory, ObjectNode coreReplay,
            ObjectNode coreEvidence, ObjectNode plan, ObjectNode stagedReplay) {}
}

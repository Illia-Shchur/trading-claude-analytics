package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Paired statistics retain the union holding interval until both arms resolve. */
class LiquidationV2StagedStatisticsJointResolutionMatrixTest {
    private static final String CORE = "liquidation-v2-core-routed-one-entry";
    private static final String ROUTED = "ROUTED_REVERSAL_CONTINUATION";
    private static final String PAIR = "joint-resolution-pair";
    private static final String SETUP = "joint-resolution-setup";
    private static final String ASSET = "BTC";
    private static final Instant DECISION = Instant.parse("2024-01-20T12:00:00Z");
    private static final Instant FILL = DECISION.plusSeconds(60);
    private static final Instant EXIT = DECISION.plus(Duration.ofDays(1));

    @Test
    void filledCoverageBlockRetainsTheOpenUnionAndBlocksPairedCompletion() throws Exception {
        Fixture fixture = fixture();
        ObjectNode row = stagedRow(fixture);
        row.put("outcome_state", "COVERAGE_BLOCKED").putNull("exit_time")
                .putNull("outcome_available_time").putNull("net_pnl_usdt");
        row.putArray("reason_codes").add("MISSING_REQUIRED_BAR_COVERAGE");
        rehashReplay(fixture.stagedReplay());

        var evaluation = evaluate(fixture);
        ObjectNode stats = evaluation.statistics();
        ObjectNode pair = (ObjectNode) stats.path("paired_outcome_inventory").get(0);
        assertEquals(1, stats.path("unresolved_count").asInt());
        assertEquals(1, stats.path("coverage_blocked_count").asInt());
        assertEquals(0, stats.path("resolved_anchor_count").asInt());
        assertEquals("OPEN_UNRESOLVED", pair.path("joint_resolution_state").asText());
        assertEquals(FILL.toString(), pair.path("union_first_fill_time").asText());
        assertTrue(pair.path("union_final_exit_time").isNull());
        assertTrue(pair.path("joint_outcome_available_time").isNull());
        assertTrue(evaluation.blockers().toString().contains("UNRESOLVED_OPEN_OR_COVERAGE_BLOCKED_ANCHORS_RETAINED"));
        assertTrue(!evaluation.gates().path("all_anchor_outcomes_resolved").asBoolean());
    }

    @Test
    void unresolvedNoFillUsesThePredecessorHoldingAsItsUnionExposure() throws Exception {
        Fixture fixture = fixture();
        ObjectNode row = stagedRow(fixture);
        row.put("outcome_state", "UNRESOLVED_NO_FILL").putNull("first_fill_time")
                .remove("position_episode_id");
        row.putNull("exit_time").putNull("outcome_available_time").putNull("net_pnl_usdt");
        row.putArray("reason_codes").add("NO_EXECUTABLE_FILL");
        rehashReplay(fixture.stagedReplay());

        var evaluation = evaluate(fixture);
        ObjectNode pair = (ObjectNode) evaluation.statistics().path("paired_outcome_inventory").get(0);
        assertEquals(1, evaluation.statistics().path("unresolved_count").asInt());
        assertEquals(0, evaluation.statistics().path("coverage_blocked_count").asInt());
        assertEquals("UNRESOLVED_NO_FILL", pair.path("staged_outcome_state").asText());
        assertEquals("CLOSED_TRADE", pair.path("predecessor_outcome_state").asText());
        assertEquals("OPEN_UNRESOLVED", pair.path("joint_resolution_state").asText());
        assertEquals(FILL.toString(), pair.path("union_first_fill_time").asText());
        assertTrue(pair.path("union_final_exit_time").isNull());
    }

    @Test
    void completedStagedPositionCannotResolveBeforeAnOpenPredecessor() throws Exception {
        Fixture fixture = rebuildBaseline(fixture(), row -> row.put("outcome_state", "OPEN_UNRESOLVED")
                .putNull("exit_time").putNull("outcome_available_time").putNull("net_pnl_usdt"));

        var evaluation = evaluate(fixture);
        ObjectNode pair = (ObjectNode) evaluation.statistics().path("paired_outcome_inventory").get(0);
        assertEquals(1, evaluation.statistics().path("unresolved_count").asInt());
        assertEquals(0, evaluation.statistics().path("resolved_anchor_count").asInt());
        assertEquals("CLOSED_TRADE", pair.path("staged_outcome_state").asText());
        assertEquals("OPEN_UNRESOLVED", pair.path("predecessor_outcome_state").asText());
        assertEquals(FILL.toString(), pair.path("union_first_fill_time").asText());
        assertTrue(pair.path("union_final_exit_time").isNull(), "the predecessor has not resolved its exposure");
        assertTrue(pair.path("joint_outcome_available_time").isNull());
    }

    @Test
    void pairedZeroTradesAreResolvedAtTheirEvidenceAvailabilityWithoutInventingHoldingTime() throws Exception {
        Fixture fixture = rebuildBaseline(fixture(), row -> noTrade(row));
        noTrade(stagedRow(fixture));
        rehashReplay(fixture.stagedReplay());

        var evaluation = evaluate(fixture);
        ObjectNode stats = evaluation.statistics();
        ObjectNode pair = (ObjectNode) stats.path("paired_outcome_inventory").get(0);
        assertEquals(1, stats.path("resolved_anchor_count").asInt());
        assertEquals(0, stats.path("completed_position_count").asInt());
        assertTrue(evaluation.gates().path("all_anchor_outcomes_resolved").asBoolean());
        assertEquals(0.0, stats.path("observed_incremental_mean_r_vs_predecessor").asDouble());
        assertEquals("RESOLVED", pair.path("joint_resolution_state").asText());
        assertEquals(DECISION.toString(), pair.path("joint_outcome_available_time").asText());
        assertTrue(pair.path("union_first_fill_time").isNull());
        assertTrue(pair.path("union_final_exit_time").isNull());
    }

    private static void noTrade(ObjectNode row) {
        row.put("outcome_state", "RESOLVED_NO_TRADE").putNull("first_fill_time")
                .putNull("exit_time").put("outcome_available_time", DECISION.toString()).put("net_pnl_usdt", 0)
                .remove("position_episode_id");
        row.putArray("reason_codes").add("NO_TRADE_RESOLVED_AT_DECISION");
    }

    private static ObjectNode stagedRow(Fixture fixture) {
        return (ObjectNode) fixture.stagedReplay().path("opportunities").get(0);
    }

    private static LiquidationV2StagedStatisticsV1.Evaluation evaluate(Fixture fixture) {
        return LiquidationV2StagedStatisticsV1.recompute(fixture.physical(), fixture.plan(),
                fixture.coreReplay(), fixture.stagedReplay(), null, null, null);
    }

    private static Fixture rebuildBaseline(Fixture base, Consumer<ObjectNode> mutation) {
        ObjectNode core = base.coreReplay().deepCopy();
        mutation.accept((ObjectNode) core.path("opportunities").get(0));
        rehash(core);
        ObjectNode evidence = base.coreEvidence().deepCopy()
                .put("replay_result_sha256", core.path("content_sha256").asText());
        rehash(evidence);
        ObjectNode plan = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(core, evidence, base.inventory());
        ObjectNode staged = base.stagedReplay().deepCopy()
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText());
        staged.set("staged_plan", plan.deepCopy());
        rehash(staged);
        return new Fixture(base.physical(), base.inventory(), core, evidence, plan, staged);
    }

    private static Fixture fixture() throws Exception {
        ObjectNode physical = JsonHashes.mapper().createObjectNode()
                .set("profile", LiquidationDailyStressProfileV1.frozenContract());
        physical.set("precommit", readPrecommit());
        ObjectNode inventory = inventory();
        ObjectNode intent = intent();
        String intentHash = JsonHashes.canonicalSha256(intent);
        ObjectNode baseline = opportunity(CORE, intent, intentHash)
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
                .put("status", "CONFIRMED_STAGE_ONE_INTENT").put("candidate_id", CORE)
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
        coreEvidence.putArray("advancement_blockers").add("SYNTHETIC_FIXTURE"); rehash(coreEvidence);
        ObjectNode plan = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(core, coreEvidence, inventory);

        ObjectNode stagedRow = opportunity(plan.path("candidate_id").asText(), intent, intentHash)
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
        staged.set("staged_plan", plan.deepCopy()); staged.putArray("opportunities").add(stagedRow);
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
        ObjectNode path = staged.putObject("account_path_summary")
                .put("schema", "liquidation-v2-account-path-summary/1").put("ledger_sha256", ledger.path("content_sha256").asText())
                .put("drawdown_basis", "ADVERSE_MARK_OHLC_EXTREMUM_BEFORE_EXIT_PATH")
                .put("maximum_adverse_mark_drawdown_fraction", 0.02).put("starting_equity_usdt", 20_000)
                .put("ending_marked_equity_usdt", 21_000);
        rehash(path); rehash(staged);
        return new Fixture(physical, inventory, core, coreEvidence, plan, staged);
    }

    private static ObjectNode opportunity(String candidate, ObjectNode intent, String intentHash) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("candidate_id", candidate).put("variant", ROUTED)
                .put("stage", 1).put("pair_id", PAIR).put("asset", ASSET).put("decision_time", DECISION.toString())
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("setup_id", SETUP)
                .put("initial_intent_sha256", intentHash);
        row.set("initial_intent", intent.deepCopy()); return row;
    }

    private static ObjectNode intent() {
        ObjectNode seed = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-anchor-setup-seed/1").put("version", 1).put("setup_id", SETUP);
        rehash(seed);
        ObjectNode intent = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-confirmed-entry-intent/1").put("version", 1)
                .put("intent_id", "joint-intent").put("setup_id", SETUP).put("pair_id", PAIR)
                .put("asset", ASSET).put("variant", ROUTED).put("branch", "CONTINUATION").put("direction", "SHORT")
                .put("stage", 1).put("decision_time", DECISION.toString()).put("diagnostic_only", false);
        intent.set("setup_seed", seed); rehash(intent); return intent;
    }

    private static ObjectNode inventory() {
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA).put("version", 1)
                .put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode candidates = result.putArray("current_candidates");
        candidates.addObject().put("candidate_id", CORE).put("variant", ROUTED);
        candidates.addObject().put("candidate_id", "liquidation-v2-core-always-continuation-one-entry").put("variant", "ALWAYS_CONTINUATION_CONTROL");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-always-reversal-one-entry").put("variant", "ALWAYS_REVERSAL_CONTROL");
        candidates.addObject().put("candidate_id", "liquidation-v2-price-oi-only-event-diagnostic").put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        rehash(result); return result;
    }

    private static ObjectNode readPrecommit() throws Exception {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) cursor = cursor.getParent();
        if (cursor == null) throw new IllegalStateException("frozen precommit not found");
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readString(
                cursor.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json")));
    }

    private static void rehashReplay(ObjectNode replay) {
        rehash((ObjectNode) replay.path("ledger"));
        replay.put("ledger_sha256", replay.path("ledger").path("content_sha256").asText());
        ((ObjectNode) replay.path("account_path_summary")).put("ledger_sha256", replay.path("ledger_sha256").asText());
        rehash((ObjectNode) replay.path("account_path_summary")); rehash(replay);
    }

    private static void rehash(ObjectNode node) {
        node.remove("content_sha256"); node.put("content_sha256", JsonHashes.ownHash(node));
    }

    private record Fixture(ObjectNode physical, ObjectNode inventory, ObjectNode coreReplay,
            ObjectNode coreEvidence, ObjectNode plan, ObjectNode stagedReplay) {}
}

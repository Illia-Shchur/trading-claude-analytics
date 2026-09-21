package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

/** Exercises both arms' independent binding to the frozen staged decision anchor. */
class LiquidationV2StagedPairedAnchorGeometryBoundaryMatrixTest {
    private static final String CORE_ID = "liquidation-v2-core-routed-one-entry";
    private static final String CORE_VARIANT = "ROUTED_REVERSAL_CONTINUATION";
    private static final String PAIR = "stats-boundary-pair";
    private static final String SETUP = "stats-boundary-setup";
    private static final String ASSET = "BTC";
    private static final Instant DECISION = Instant.parse("2024-01-20T12:00:00Z");
    private static final Instant FILL = DECISION.plusSeconds(60);
    private static final Instant EXIT = DECISION.plus(Duration.ofDays(1));

    @Test
    void acceptedPairedBaselineAndStagedRowsPreserveTheFrozenGeometry() throws Exception {
        Fixture fixture = fixture();
        assertDoesNotThrow(() -> evaluation(fixture, fixture.plan(), fixture.coreReplay(), fixture.stagedReplay(), null, null));
    }

    @Test
    void baselineGeometryChangesAreRejectedAgainstThePlanAnchor() throws Exception {
        for (Consumer<ObjectNode> mutation : java.util.List.<Consumer<ObjectNode>>of(
                row -> row.put("asset", "ETH"),
                row -> row.put("decision_time", DECISION.plusSeconds(60).toString()),
                row -> row.put("branch", "REVERSAL"),
                row -> row.put("direction", "LONG"))) {
            Fixture fixture = fixture();
            ObjectNode baseline = fixture.coreReplay().deepCopy();
            mutation.accept((ObjectNode) baseline.path("opportunities").get(0)); rehash(baseline);
            ObjectNode plan = rebindCoreReplayToPlan(fixture.plan(), baseline);
            ObjectNode staged = rebindStagedReplay(fixture.stagedReplay(), plan);
            reject(fixture, plan, baseline, staged, null, null, null,
                    "baseline pair does not preserve frozen decision time, asset, branch, and direction");
        }
    }

    @Test
    void coreBaselineAndStagedRowsMustRetainStageOneAndFullIntentIdentity() throws Exception {
        for (Consumer<ObjectNode> mutation : java.util.List.<Consumer<ObjectNode>>of(
                row -> row.put("variant", "ALWAYS_CONTINUATION_CONTROL"),
                row -> row.put("stage", 2))) {
            Fixture fixture = fixture();
            ObjectNode baseline = fixture.coreReplay().deepCopy();
            mutation.accept((ObjectNode) baseline.path("opportunities").get(0)); rehash(baseline);
            ObjectNode plan = rebindCoreReplayToPlan(fixture.plan(), baseline);
            ObjectNode staged = rebindStagedReplay(fixture.stagedReplay(), plan);
            reject(fixture, plan, baseline, staged, null, null, null, "core predecessor anchor must be a stage-one routed row");
        }
        for (Consumer<ObjectNode> mutation : java.util.List.<Consumer<ObjectNode>>of(
                row -> row.put("asset", "ETH"),
                row -> row.put("decision_time", DECISION.plusSeconds(60).toString()),
                row -> row.put("branch", "REVERSAL"),
                row -> row.put("direction", "LONG"),
                row -> row.put("variant", "ALWAYS_CONTINUATION_CONTROL"))) {
            Fixture fixture = fixture();
            ObjectNode staged = fixture.stagedReplay().deepCopy();
            mutation.accept((ObjectNode) staged.path("opportunities").get(0)); rehash(staged);
            reject(fixture, fixture.plan(), fixture.coreReplay(), staged, null, null, null,
                    "staged output changed its frozen initial decision, branch, direction, or full intent geometry");
        }
        Fixture fixture = fixture();
        ObjectNode staged = fixture.stagedReplay().deepCopy();
        ObjectNode row = (ObjectNode) staged.path("opportunities").get(0);
        ObjectNode intent = (ObjectNode) row.path("initial_intent");
        intent.put("asset", "ETH"); rehash(intent); row.put("initial_intent_sha256", JsonHashes.canonicalSha256(intent));
        rehash(staged);
        reject(fixture, fixture.plan(), fixture.coreReplay(), staged, null, null, null,
                "staged output changed its frozen initial decision, branch, direction, or full intent geometry");
    }

    private static ObjectNode rebindCoreReplayToPlan(ObjectNode sourcePlan, ObjectNode baseline) {
        ObjectNode plan = sourcePlan.deepCopy();
        String replaySha = baseline.path("content_sha256").asText();
        plan.put("predecessor_replay_sha256", replaySha);
        ((ObjectNode) plan.path("core_anchor_lineage")).put("core_replay_sha256", replaySha);
        rehash(plan);
        return plan;
    }

    private static LiquidationV2StagedStatisticsV1.Evaluation evaluation(Fixture fixture, ObjectNode plan,
            ObjectNode baseline, ObjectNode staged, ObjectNode predecessorPlan,
            ObjectNode predecessorEvidence) {
        return LiquidationV2StagedStatisticsV1.recompute(fixture.physicalFreeze(), plan, baseline, staged,
                predecessorPlan, predecessorEvidence, null);
    }

    private static void reject(Fixture fixture, ObjectNode plan, ObjectNode baseline, ObjectNode staged,
            ObjectNode predecessorPlan, ObjectNode predecessorEvidence, ObjectNode exposureState, String message) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2StagedStatisticsV1.recompute(fixture.physicalFreeze(), plan, baseline,
                        staged, predecessorPlan, predecessorEvidence, exposureState));
        assertTrue(error.getMessage().contains(message),
                () -> "expected '" + message + "', got '" + error.getMessage() + "'");
    }

    private static Fixture fixture() throws Exception {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        ObjectNode precommit = read(repositoryRoot().resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json"));
        ObjectNode inventory = coreInventory();
        ObjectNode intent = intent();
        String intentSha = JsonHashes.canonicalSha256(intent);
        ObjectNode coreOpportunity = baseOpportunity(CORE_ID, intent, intentSha);
        coreOpportunity.put("outcome_state", "CLOSED_TRADE").put("first_fill_time", FILL.toString())
                .put("exit_time", EXIT.toString()).put("outcome_available_time", EXIT.toString())
                .put("net_pnl_usdt", 200).put("reference_risk_usdt", 200);
        ObjectNode coreReplay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY");
        coreReplay.putObject("freeze").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        coreReplay.putArray("opportunities").add(coreOpportunity);
        ObjectNode signal = coreReplay.putArray("route_audit").addObject()
                .put("status", "CONFIRMED_STAGE_ONE_INTENT").put("candidate_id", CORE_ID)
                .put("intent_id", intent.path("intent_id").asText()).put("setup_id", SETUP)
                .put("pair_id", PAIR).put("asset", ASSET).put("decision_time", DECISION.toString())
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("initial_intent_sha256", intentSha);
        signal.set("initial_intent", intent.deepCopy());
        rehash(coreReplay);
        ObjectNode coreEvidence = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EVIDENCE_SCHEMA).put("version", 1)
                .put("status", "BLOCKED").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("replay_result_sha256", coreReplay.path("content_sha256").asText())
                .put("promotion_permitted", false).put("trade_authorization_permitted", false);
        coreEvidence.putArray("advancement_blockers").add("SYNTHETIC_ONLY");
        rehash(coreEvidence);
        ObjectNode plan = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(coreReplay, coreEvidence, inventory);

        ObjectNode stagedOpportunity = baseOpportunity(plan.path("candidate_id").asText(), intent, intentSha);
        stagedOpportunity.put("outcome_state", "CLOSED_TRADE").put("first_fill_time", FILL.toString())
                .put("position_episode_id", "episode-1").put("setup_id", SETUP)
                .put("first_fill_reference_equity_usdt", 20000).put("full_position_reference_risk_usdt", 1000)
                .put("reference_risk_usdt", 1000)
                .put("full_position_reference_risk_basis", LiquidationV2StagedCandidateInventoryV1.FULL_POSITION_R_BASIS)
                .put("filled_quantity", 10).put("fill_price", 100)
                .put("exit_time", EXIT.toString()).put("outcome_available_time", EXIT.toString())
                .put("net_pnl_usdt", 1000);
        ObjectNode stagedReplay = replayFor(plan, stagedOpportunity);
        ObjectNode physicalFreeze = JsonHashes.mapper().createObjectNode();
        physicalFreeze.set("profile", profile);
        physicalFreeze.set("precommit", precommit);
        return new Fixture(physicalFreeze, inventory, coreReplay, plan, stagedReplay);
    }

    private static ObjectNode replayFor(ObjectNode plan, ObjectNode opportunity) {
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", plan.path("source_mode").asText())
                .put("candidate_id", plan.path("candidate_id").asText()).put("mode_id", plan.path("mode_id").asText())
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText());
        replay.set("staged_plan", plan.deepCopy());
        replay.putArray("opportunities").add(opportunity);
        ObjectNode refs = replay.putObject("pre_outcome_exposure_attempts")
                .put("schema", "liquidation-v2-pre-outcome-exposure-refs/1").put("attempt_count", 0);
        refs.putArray("attempts"); rehash(refs);
        ObjectNode position = JsonHashes.mapper().createObjectNode().put("status", "CLOSED")
                .put("active_setup_id", SETUP).put("asset", ASSET).put("first_fill_time", FILL.toEpochMilli())
                .put("entry_costs_usdt", 10).put("exit_costs_usdt", 20)
                .put("funding_debits_for_risk_headroom_usdt", 10);
        position.putArray("exits").addObject().put("exit_time", EXIT.toString());
        ObjectNode account = JsonHashes.mapper().createObjectNode();
        account.putArray("closed_episodes");
        account.putArray("positions").add(position);
        ObjectNode accountRow = replay.putObject("ledger").putArray("accounts").addObject()
                .put("candidate_id", plan.path("candidate_id").asText());
        accountRow.set("account", account);
        ObjectNode ledger = (ObjectNode) replay.path("ledger"); rehash(ledger);
        replay.put("ledger_sha256", ledger.path("content_sha256").asText());
        ObjectNode path = replay.putObject("account_path_summary")
                .put("schema", "liquidation-v2-account-path-summary/1")
                .put("ledger_sha256", ledger.path("content_sha256").asText())
                .put("drawdown_basis", "ADVERSE_MARK_OHLC_EXTREMUM_BEFORE_EXIT_PATH")
                .put("maximum_adverse_mark_drawdown_fraction", 0.02)
                .put("starting_equity_usdt", 20000).put("ending_marked_equity_usdt", 21000);
        rehash(path); rehash(replay);
        return replay;
    }

    private static ObjectNode rebindStagedReplay(ObjectNode source, ObjectNode plan) {
        ObjectNode replay = source.deepCopy();
        replay.put("candidate_id", plan.path("candidate_id").asText()).put("mode_id", plan.path("mode_id").asText())
                .put("plan_sha256", plan.path("content_sha256").asText());
        replay.set("staged_plan", plan.deepCopy());
        ((ObjectNode) replay.path("opportunities").get(0)).put("candidate_id", plan.path("candidate_id").asText());
        ObjectNode accountRow = (ObjectNode) replay.path("ledger").path("accounts").get(0);
        accountRow.put("candidate_id", plan.path("candidate_id").asText());
        ObjectNode ledger = (ObjectNode) replay.path("ledger"); rehash(ledger);
        replay.put("ledger_sha256", ledger.path("content_sha256").asText());
        ((ObjectNode) replay.path("account_path_summary")).put("ledger_sha256", ledger.path("content_sha256").asText());
        rehash((ObjectNode) replay.path("account_path_summary"));
        rehash(replay);
        return replay;
    }

    private static ObjectNode baseOpportunity(String candidate, ObjectNode intent, String intentSha) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("candidate_id", candidate)
                .put("variant", CORE_VARIANT).put("stage", 1).put("pair_id", PAIR).put("asset", ASSET)
                .put("decision_time", DECISION.toString()).put("branch", "CONTINUATION").put("direction", "SHORT")
                .put("setup_id", SETUP).put("initial_intent_sha256", intentSha);
        row.set("initial_intent", intent.deepCopy());
        return row;
    }

    private static ObjectNode intent() {
        ObjectNode seed = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-anchor-setup-seed/1")
                .put("version", 1).put("setup_id", SETUP);
        rehash(seed);
        ObjectNode intent = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-confirmed-entry-intent/1").put("version", 1)
                .put("intent_id", "stats-intent-1").put("setup_id", SETUP).put("pair_id", PAIR)
                .put("asset", ASSET).put("variant", CORE_VARIANT).put("branch", "CONTINUATION")
                .put("direction", "SHORT").put("stage", 1).put("decision_time", DECISION.toString())
                .put("diagnostic_only", false);
        intent.set("setup_seed", seed); rehash(intent); return intent;
    }

    private static ObjectNode coreInventory() {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA).put("version", 1)
                .put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode candidates = inventory.putArray("current_candidates");
        candidates.addObject().put("candidate_id", CORE_ID).put("variant", CORE_VARIANT);
        candidates.addObject().put("candidate_id", "liquidation-v2-core-always-continuation-one-entry").put("variant", "ALWAYS_CONTINUATION_CONTROL");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-always-reversal-one-entry").put("variant", "ALWAYS_REVERSAL_CONTROL");
        candidates.addObject().put("candidate_id", "liquidation-v2-price-oi-only-event-diagnostic").put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        rehash(inventory); return inventory;
    }

    private static void rehash(ObjectNode object) { object.remove("content_sha256"); object.put("content_sha256", JsonHashes.ownHash(object)); }
    private static ObjectNode read(Path path) throws Exception { return (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(path)); }
    private static Path repositoryRoot() { Path p=Path.of("").toAbsolutePath().normalize(); while(p!=null&&!Files.exists(p.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) p=p.getParent(); if(p==null) throw new IllegalStateException("frozen precommit missing"); return p; }

    private record Fixture(ObjectNode physicalFreeze, ObjectNode inventory, ObjectNode coreReplay,
            ObjectNode plan, ObjectNode stagedReplay) {}
}

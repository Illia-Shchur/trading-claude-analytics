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

/** Mutation matrix for the staged evaluator's hash, lineage, outcome and custody boundaries. */
class LiquidationV2StagedStatisticsBoundaryMatrixTest {
    private static final String CORE_ID = "liquidation-v2-core-routed-one-entry";
    private static final String CORE_VARIANT = "ROUTED_REVERSAL_CONTINUATION";
    private static final String PAIR = "stats-boundary-pair";
    private static final String SETUP = "stats-boundary-setup";
    private static final String ASSET = "BTC";
    private static final Instant DECISION = Instant.parse("2024-01-20T12:00:00Z");
    private static final Instant FILL = DECISION.plusSeconds(60);
    private static final Instant EXIT = DECISION.plus(Duration.ofDays(1));

    @Test
    void validNoMacroAndMacroResultsRecomputeAgainstTheirExactSequentialPredecessors() throws Exception {
        Fixture fixture = fixture();
        LiquidationV2StagedStatisticsV1.Evaluation noMacro = evaluation(fixture, fixture.plan(), fixture.coreReplay(),
                fixture.stagedReplay(), null, null);
        assertTrue(noMacro.statistics().path("comparison_basis").asText().contains("CORE_HEADLINE_1_PERCENT"));
        ObjectNode evidence = LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(
                fixture.plan(), fixture.stagedReplay(), noMacro);
        ObjectNode macroPlan = LiquidationV2StagedCandidateInventoryV1.freezeMacro(
                fixture.plan(), fixture.stagedReplay(), evidence, noMacro);
        ObjectNode macroReplay = rebindStagedReplay(fixture.stagedReplay(), macroPlan);
        LiquidationV2StagedStatisticsV1.Evaluation macro = assertDoesNotThrow(() -> evaluation(fixture,
                macroPlan, fixture.stagedReplay(), macroReplay, fixture.plan(), evidence));
        assertTrue(macro.statistics().path("comparison_basis").asText().contains("BOTH_ARMS_FULL_POSITION_5_PERCENT"));
        assertTrue(macro.statistics().path("overfit_selection_diagnostics").path("pbo_value").isNull());
    }

    @Test
    void planReplayAndPredecessorReferencesAreCheckedBeforeAnyStatisticsAreProduced() throws Exception {
        Fixture fixture = fixture();
        assertDoesNotThrow(() -> evaluation(fixture, fixture.plan(), fixture.coreReplay(), fixture.stagedReplay(), null, null));

        ObjectNode unknownMode = fixture.plan().deepCopy().put("mode_id", "UNFROZEN_MODE");
        rehash(unknownMode);
        reject(fixture, unknownMode, fixture.coreReplay(), fixture.stagedReplay(), null, null, null,
                "unknown staged mode");

        ObjectNode stalePlan = fixture.plan().deepCopy().put("candidate_id", "rebound");
        reject(fixture, stalePlan, fixture.coreReplay(), fixture.stagedReplay(), null, null, null,
                "staged plan content hash is invalid");

        ObjectNode detached = fixture.stagedReplay().deepCopy().put("candidate_id", "another-candidate");
        rehash(detached);
        reject(fixture, fixture.plan(), fixture.coreReplay(), detached, null, null, null,
                "staged replay is detached from its exact plan, candidate, mode, or source mode");

        ObjectNode changedBaseline = fixture.coreReplay().deepCopy().put("event_count", 99);
        rehash(changedBaseline);
        reject(fixture, fixture.plan(), changedBaseline, fixture.stagedReplay(), null, null, null,
                "staged baseline replay differs from the exact frozen predecessor result");

        reject(fixture, fixture.plan(), fixture.coreReplay(), fixture.stagedReplay(),
                JsonHashes.mapper().createObjectNode(), null, null,
                "no-macro statistics require the bound core predecessor and no staged predecessor artifacts");
    }

    @Test
    void stagedOutcomeAndExposureReceiptsCannotBeDetachedOrForged() throws Exception {
        Fixture fixture = fixture();
        ObjectNode invalidOutcome = fixture.stagedReplay().deepCopy();
        ((ObjectNode) invalidOutcome.path("opportunities").get(0)).put("outcome_state", "FUTURE_WINNER");
        rehash(invalidOutcome);
        reject(fixture, fixture.plan(), fixture.coreReplay(), invalidOutcome, null, null, null,
                "staged output has an unsupported outcome state");

        ObjectNode wrongGeometry = fixture.stagedReplay().deepCopy();
        ((ObjectNode) wrongGeometry.path("opportunities").get(0)).put("direction", "LONG");
        rehash(wrongGeometry);
        reject(fixture, fixture.plan(), fixture.coreReplay(), wrongGeometry, null, null, null,
                "staged output changed its frozen initial decision, branch, direction, or full intent geometry");

        ObjectNode badReceipt = fixture.stagedReplay().deepCopy();
        ((ObjectNode) badReceipt.path("pre_outcome_exposure_attempts")).put("schema", "other");
        rehash((ObjectNode) badReceipt.path("pre_outcome_exposure_attempts"));
        rehash(badReceipt);
        reject(fixture, fixture.plan(), fixture.coreReplay(), badReceipt, null, null, null,
                "staged replay must retain its immutable hash-bound pre-outcome exposure-attempt receipt");

        ObjectNode receiptWithRef = fixture.stagedReplay().deepCopy();
        ObjectNode refs = (ObjectNode) receiptWithRef.path("pre_outcome_exposure_attempts");
        refs.putArray("attempts").addObject().put("candidate_id", "known-candidate")
                .put("attempt_freeze_sha256", "a".repeat(64)).put("physical_freeze_sha256", "b".repeat(64))
                .put("behavior_sha256", "c".repeat(64));
        refs.put("attempt_count", 1);
        rehash(refs);
        rehash(receiptWithRef);
        ObjectNode emptyState = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2FamilyExposureAttemptV1.STATE_SCHEMA);
        emptyState.putArray("known_v002_attempts");
        rehash(emptyState);
        reject(fixture, fixture.plan(), fixture.coreReplay(), receiptWithRef, null, null, emptyState,
                "immutable replay attempt refs are not present in the reopened durable family prefix");

        ObjectNode wrongState = JsonHashes.mapper().createObjectNode().put("schema", "wrong-state");
        wrongState.putArray("known_v002_attempts");
        rehash(wrongState);
        reject(fixture, fixture.plan(), fixture.coreReplay(), fixture.stagedReplay(), null, null, wrongState,
                "current custody state schema is invalid");
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

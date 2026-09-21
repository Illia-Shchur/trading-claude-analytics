package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Additional accepted-baseline vectors for staged attribution and adverse account-path fail-closed branches. */
class LiquidationV2StagedStatisticsAttributionAndPathMatrixTest {
    private static final String ROUTED_ID = "liquidation-v2-core-routed-one-entry";
    private static final String ROUTED = "ROUTED_REVERSAL_CONTINUATION";
    private static final String PAIR = "pair-2023-12-29";
    private static final String ASSET = "BTC";
    private static final String SETUP = "setup-1";

    @Test
    void actualExposureAggregatesByAssetAndIgnoresNonfilledStageAttempts() throws Exception {
        Fixture fixture = fixture();
        ObjectNode replay = fixture.stagedReplay().deepCopy();
        ObjectNode ignored = replay.putArray("stage_attempts").addObject()
                .put("outcome_state", "STAGE_NO_FILL").put("asset", "")
                .put("planned_tranche_risk_usdt", "not-a-number");
        ignored.put("filled_quantity", -1).put("fill_price", 0);
        rehash(replay);
        var noFill = evaluate(fixture, replay);
        ObjectNode baseAttribution = (ObjectNode) noFill.statistics().path("risk_exposure_attribution");
        assertTrue(noFill.gates().path("risk_exposure_matched_attribution_reported").asBoolean());
        assertEquals(200, baseAttribution.path("staged_planned_tranche_risk_allowance_sum_usdt").asDouble());
        assertEquals(1, baseAttribution.path("staged_actual_fill_count").asInt());
        assertEquals("BTC", baseAttribution.path("actual_filled_exposure_by_asset").get(0).path("asset").asText());

        ObjectNode ethOne = JsonHashes.mapper().createObjectNode().put("outcome_state", "STAGE_FILLED")
                .put("asset", "ETH").put("planned_tranche_risk_usdt", 50)
                .put("filled_quantity", 2).put("fill_price", 1000);
        ObjectNode ethTwo = JsonHashes.mapper().createObjectNode().put("outcome_state", "STAGE_FILLED")
                .put("asset", "ETH").put("planned_tranche_risk_usdt", 30)
                .put("filled_quantity", 1).put("fill_price", 20);
        ((ArrayNode) replay.path("stage_attempts")).add(ethOne).add(ethTwo);
        rehash(replay);
        var fills = evaluate(fixture, replay);
        ObjectNode attribution = (ObjectNode) fills.statistics().path("risk_exposure_attribution");
        assertTrue(fills.gates().path("risk_exposure_matched_attribution_reported").asBoolean());
        assertEquals(280, attribution.path("staged_planned_tranche_risk_allowance_sum_usdt").asDouble());
        assertEquals(3, attribution.path("staged_actual_fill_count").asInt());
        assertEquals(2, attribution.path("actual_filled_exposure_by_asset").size());
        assertEquals("BTC", attribution.path("actual_filled_exposure_by_asset").get(0).path("asset").asText());
        assertEquals(10, attribution.path("actual_filled_exposure_by_asset").get(0).path("filled_quantity").asDouble());
        assertEquals("ETH", attribution.path("actual_filled_exposure_by_asset").get(1).path("asset").asText());
        assertEquals(3, attribution.path("actual_filled_exposure_by_asset").get(1).path("filled_quantity").asDouble());
        assertEquals(2020, attribution.path("actual_filled_exposure_by_asset").get(1).path("entry_notional_usdt").asDouble());
    }

    @Test
    void malformedFilledStageAttemptsRejectBlankAssetsAndNonpositiveFillDimensions() throws Exception {
        Fixture fixture = fixture();
        var baseline = evaluate(fixture, fixture.stagedReplay().deepCopy());
        assertTrue(baseline.gates().path("risk_exposure_matched_attribution_reported").asBoolean());
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                row -> row.put("asset", ""),
                row -> row.put("filled_quantity", 0),
                row -> row.put("fill_price", 0))) {
            ObjectNode replay = fixture.stagedReplay().deepCopy();
            ObjectNode attempt = replay.putArray("stage_attempts").addObject()
                    .put("outcome_state", "STAGE_FILLED").put("asset", "ETH")
                    .put("planned_tranche_risk_usdt", 25).put("filled_quantity", 1).put("fill_price", 10);
            mutation.accept(attempt);
            rehash(replay);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> evaluate(fixture, replay));
            assertEquals("actual staged fill attribution is malformed", failure.getMessage());
        }
    }

    @Test
    void malformedNumericalRowsKeepObservedFillFactsButFailAttributionClosed() throws Exception {
        Fixture fixture = fixture();
        var baseline = evaluate(fixture, fixture.stagedReplay().deepCopy());
        assertTrue(baseline.gates().path("risk_exposure_matched_attribution_reported").asBoolean());
        List<Consumer<ObjectNode>> mutations = List.of(
                row -> row.put("planned_tranche_risk_usdt", "25"),
                row -> row.put("filled_quantity", "2"),
                row -> row.remove("fill_price"));
        for (int index = 0; index < mutations.size(); index++) {
            ObjectNode replay = fixture.stagedReplay().deepCopy();
            ObjectNode attempt = replay.putArray("stage_attempts").addObject()
                    .put("outcome_state", "STAGE_FILLED").put("asset", "ETH")
                    .put("planned_tranche_risk_usdt", 25).put("filled_quantity", 2).put("fill_price", 10);
            mutations.get(index).accept(attempt);
            rehash(replay);
            var result = evaluate(fixture, replay);
            ObjectNode attribution = (ObjectNode) result.statistics().path("risk_exposure_attribution");
            assertFalse(result.gates().path("risk_exposure_matched_attribution_reported").asBoolean());
            assertTrue(result.blockers().toString().contains("RISK_EXPOSURE_ATTRIBUTION_INCOMPLETE"));
            assertEquals(index == 0 ? 0.0 : 225.0,
                    attribution.path("staged_planned_tranche_risk_allowance_sum_usdt").asDouble(),
                    "risk allowance is unknown only for the nonnumeric planned-risk mutation");
            assertEquals(index == 0 ? 2 : 1, attribution.path("staged_actual_fill_count").asInt(),
                    "a malformed planned-risk value does not erase the independently valid actual fill");
        }
    }

    @Test
    void durableFamilySnapshotMustContainTheExactImmutableAttemptPrefixWithoutPromotingHistoricalK() throws Exception {
        Fixture fixture = fixture();
        ObjectNode replay = fixture.stagedReplay().deepCopy();
        ObjectNode refs = (ObjectNode) replay.path("pre_outcome_exposure_attempts");
        ObjectNode attempt = JsonHashes.mapper().createObjectNode()
                .put("candidate_id", fixture.plan().path("candidate_id").asText())
                .put("attempt_freeze_sha256", "a".repeat(64))
                .put("physical_freeze_sha256", "b".repeat(64))
                .put("behavior_sha256", "c".repeat(64));
        refs.put("attempt_count", 1);
        refs.putArray("attempts").add(attempt.deepCopy());
        rehash(refs); rehash(replay);

        ObjectNode journalPrefix = familyState();
        journalPrefix.putArray("known_v002_attempts").add(attempt.deepCopy());
        rehash(journalPrefix);
        var accepted = LiquidationV2StagedStatisticsV1.recompute(fixture.physicalFreeze(), fixture.plan(),
                fixture.coreReplay(), replay, null, null, journalPrefix);
        assertTrue(accepted.blockers().toString().contains("HISTORICAL_FAMILY_K_UNKNOWN"));
        assertEquals("UNKNOWN_NO_VERIFIED_CANONICAL_PREDECESSOR_EXPOSURE_INVENTORY",
                accepted.statistics().path("historical_cumulative_k_status").asText());
        assertDoesNotThrow(() -> LiquidationV2StagedStatisticsV1.recompute(fixture.physicalFreeze(), fixture.plan(),
                fixture.coreReplay(), replay, null, null, canonicalHeadFallback(attempt)));

        ObjectNode mismatchedPrefix = familyState();
        ObjectNode wrong = attempt.deepCopy().put("behavior_sha256", "d".repeat(64));
        mismatchedPrefix.putArray("known_v002_attempts").add(wrong);
        rehash(mismatchedPrefix);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2StagedStatisticsV1.recompute(fixture.physicalFreeze(), fixture.plan(),
                        fixture.coreReplay(), replay, null, null, mismatchedPrefix));
        assertEquals("immutable replay attempt refs are not present in the reopened durable family prefix",
                failure.getMessage());
    }

    private static ObjectNode familyState() {
        ObjectNode state = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2FamilyExposureAttemptV1.STATE_SCHEMA);
        state.putArray("known_v002_attempts");
        rehash(state);
        return state;
    }

    private static ObjectNode canonicalHeadFallback(ObjectNode attempt) {
        ObjectNode state = familyState();
        state.putArray("verified_canonical_attempt_refs").add(attempt.deepCopy());
        rehash(state);
        return state;
    }

    @Test
    void adverseAccountPathRequiresEveryFrozenBindingAndNumericField() throws Exception {
        Fixture fixture = fixture();
        ObjectNode baselineReplay = fixture.stagedReplay().deepCopy();
        var baseline = evaluate(fixture, baselineReplay);
        assertEquals(1000, baseline.statistics().path("portfolio_net_pnl_usdt").asDouble());
        assertTrue(baseline.gates().path("portfolio_minimum_net_pnl").asBoolean());
        assertTrue(baseline.gates().path("portfolio_maximum_drawdown_pct").asBoolean());

        List<Consumer<ObjectNode>> badPaths = List.of(
                replay -> replay.remove("account_path_summary"),
                replay -> path(replay).put("schema", "wrong-account-path-schema"),
                replay -> path(replay).put("ledger_sha256", "f".repeat(64)),
                replay -> path(replay).put("drawdown_basis", "CLOSE_ONLY"),
                replay -> path(replay).remove("maximum_adverse_mark_drawdown_fraction"),
                replay -> path(replay).put("starting_equity_usdt", "20000"),
                replay -> path(replay).putNull("ending_marked_equity_usdt"));
        for (int index = 0; index < badPaths.size(); index++) {
            ObjectNode replay = baselineReplay.deepCopy();
            badPaths.get(index).accept(replay);
            if (replay.path("account_path_summary").isObject()) {
                ObjectNode changedPath = path(replay);
                // Re-seal semantic changes; the dedicated stale-own-hash case below exercises hash rejection.
                rehash(changedPath);
            }
            rehash(replay);
            var result = evaluate(fixture, replay);
            assertTrue(result.statistics().path("portfolio_net_pnl_usdt").isNull(), "path vector " + index);
            assertTrue(result.statistics().path("adverse_mark_drawdown_fraction").isNull(), "path vector " + index);
            assertFalse(result.gates().path("portfolio_minimum_net_pnl").asBoolean(), "path vector " + index);
            assertFalse(result.gates().path("portfolio_maximum_drawdown_pct").asBoolean(), "path vector " + index);
            assertTrue(result.blockers().toString().contains("HASH_BOUND_ADVERSE_INTRATRADE_ACCOUNT_PATH_UNAVAILABLE"),
                    result.blockers().toString());
        }

        ObjectNode staleHashReplay = baselineReplay.deepCopy();
        path(staleHashReplay).put("maximum_adverse_mark_drawdown_fraction", 0.03);
        rehash(staleHashReplay);
        var staleHash = evaluate(fixture, staleHashReplay);
        assertTrue(staleHash.statistics().path("portfolio_net_pnl_usdt").isNull());
        assertFalse(staleHash.gates().path("portfolio_maximum_drawdown_pct").asBoolean());
    }

    private static ObjectNode path(ObjectNode replay) { return (ObjectNode) replay.path("account_path_summary"); }
    private static LiquidationV2StagedStatisticsV1.Evaluation evaluate(Fixture fixture, ObjectNode replay) {
        return LiquidationV2StagedStatisticsV1.recompute(fixture.physicalFreeze(), fixture.plan(),
                fixture.coreReplay(), replay, null, null, null);
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
        ObjectNode coreOpportunity = opportunity(ROUTED_ID, PAIR, decision, intent, intentSha)
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
                .put("candidate_id", ROUTED_ID).put("intent_id", intent.path("intent_id").asText())
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
                .put("variant", ROUTED).put("stage", 1).put("pair_id", pair).put("asset", ASSET)
                .put("decision_time", decision.toString()).put("branch", "CONTINUATION").put("direction", "SHORT")
                .put("setup_id", SETUP).put("initial_intent_sha256", intentSha);
        row.set("initial_intent", intent.deepCopy()); return row;
    }

    private static ObjectNode intent(Instant decision) {
        ObjectNode seed = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-anchor-setup-seed/1")
                .put("version", 1).put("setup_id", SETUP); rehash(seed);
        ObjectNode intent = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-confirmed-entry-intent/1")
                .put("version", 1).put("intent_id", "ledger-intent").put("setup_id", SETUP).put("pair_id", PAIR)
                .put("asset", ASSET).put("variant", ROUTED).put("branch", "CONTINUATION")
                .put("direction", "SHORT").put("stage", 1).put("decision_time", decision.toString()).put("diagnostic_only", false);
        intent.set("setup_seed", seed); rehash(intent); return intent;
    }

    private static ObjectNode coreInventory() {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode().put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA)
                .put("version", 1).put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode candidates = inventory.putArray("current_candidates");
        candidates.addObject().put("candidate_id", ROUTED_ID).put("variant", ROUTED);
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
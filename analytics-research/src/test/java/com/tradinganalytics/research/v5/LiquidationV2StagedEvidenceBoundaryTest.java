package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiquidationV2StagedEvidenceBoundaryTest {
    private static final String ROUTED = "liquidation-v2-core-routed-one-entry";
    private static final String VARIANT = "ROUTED_REVERSAL_CONTINUATION";
    private static final List<String> SCENARIOS = List.of("fee_slippage", "funding_carry",
            "adverse_execution_gap", "liquidity_capacity", "venue_outage_blackout");

    @Test
    void durableExposureRefsRequireAnExactCurrentPrefixOrMatchingCanonicalHeadReceipt() throws Exception {
        Fixture fixture = fixture();
        ObjectNode replay = withOneExposureRef(fixture.stagedReplay());
        ObjectNode ref = (ObjectNode) replay.path("pre_outcome_exposure_attempts").path("attempts").get(0);

        ObjectNode missing = familyState();
        assertFailureContains(() -> recompute(fixture, replay, missing),
                "immutable replay attempt refs are not present");

        ObjectNode exact = familyState();
        exact.putArray("known_v002_attempts").add(ref.deepCopy());
        rehash(exact);
        LiquidationV2StagedStatisticsV1.Evaluation accepted = recompute(fixture, replay, exact);
        assertTrue(accepted.blockers().toString().contains("HISTORICAL_FAMILY_K_UNKNOWN"),
                "matching new attempts validate custody without pretending to establish earlier K");

        ObjectNode prefix = familyState();
        prefix.putArray("known_v002_attempts");
        prefix.putArray("verified_canonical_attempt_refs").add(ref.deepCopy());
        rehash(prefix);
        assertTrue(recompute(fixture, replay, prefix).blockers().toString().contains("HISTORICAL_FAMILY_K_UNKNOWN"));

        ObjectNode wrongPrefix = familyState();
        wrongPrefix.putArray("known_v002_attempts").add(ref.deepCopy().put("behavior_sha256", "d".repeat(64)));
        rehash(wrongPrefix);
        assertFailureContains(() -> recompute(fixture, replay, wrongPrefix),
                "immutable replay attempt refs are not present");

        ObjectNode missingChain = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2FamilyExposureAttemptV1.STATE_SCHEMA).put("version", 1);
        rehash(missingChain);
        assertFailureContains(() -> recompute(fixture, replay, missingChain),
                "lacks its known-attempt chain");

        ObjectNode duplicateRefsReplay = replay.deepCopy();
        ArrayNode refs = (ArrayNode) duplicateRefsReplay.path("pre_outcome_exposure_attempts").path("attempts");
        refs.add(ref.deepCopy());
        ((ObjectNode) duplicateRefsReplay.path("pre_outcome_exposure_attempts")).put("attempt_count", 2);
        rehash((ObjectNode) duplicateRefsReplay.path("pre_outcome_exposure_attempts"));
        rehash(duplicateRefsReplay);
        assertFailureContains(() -> recompute(fixture, duplicateRefsReplay, exact),
                "duplicate or malformed identities");
    }

    @Test
    void stagedStressMetricsAndOutageActionAreFailClosedAcrossAllFrozenScenarioRows() throws Exception {
        Fixture fixture = fixture();
        ObjectNode validReplay = positiveStressReplay(fixture);
        LiquidationV2StagedStatisticsV1.Evaluation evaluation = recompute(fixture, validReplay, null);
        for (String scenario : SCENARIOS) {
            assertTrue(evaluation.gates().path("stress_" + scenario).asBoolean(false), scenario);
        }

        ObjectNode feeBindingChanged = validReplay.deepCopy();
        scenario(feeBindingChanged, "fee_slippage").put("runner_result_sha256", "f".repeat(64));
        rehash((ObjectNode) feeBindingChanged.path("stress_evaluation")); rehash(feeBindingChanged);
        var feeEvaluation = recompute(fixture, feeBindingChanged, null);
        assertFalse(feeEvaluation.gates().path("stress_fee_slippage").asBoolean());
        assertTrue(feeEvaluation.gates().path("stress_funding_carry").asBoolean());

        ObjectNode fundingIncomplete = validReplay.deepCopy();
        candidateSummary(scenario(fundingIncomplete, "funding_carry"), fixture).put("completed_observations", 29);
        rehash((ObjectNode) fundingIncomplete.path("stress_evaluation")); rehash(fundingIncomplete);
        assertFalse(recompute(fixture, fundingIncomplete, null).gates().path("stress_funding_carry").asBoolean());

        ObjectNode gapUnresolved = validReplay.deepCopy();
        candidateSummary(scenario(gapUnresolved, "adverse_execution_gap"), fixture).put("open_unresolved_count", 1);
        rehash((ObjectNode) gapUnresolved.path("stress_evaluation")); rehash(gapUnresolved);
        assertFalse(recompute(fixture, gapUnresolved, null).gates().path("stress_adverse_execution_gap").asBoolean());

        ObjectNode capacityBlocked = validReplay.deepCopy();
        candidateSummary(scenario(capacityBlocked, "liquidity_capacity"), fixture).put("coverage_blocked_count", 1);
        rehash((ObjectNode) capacityBlocked.path("stress_evaluation")); rehash(capacityBlocked);
        assertFalse(recompute(fixture, capacityBlocked, null).gates().path("stress_liquidity_capacity").asBoolean());

        ObjectNode outageNoAction = validReplay.deepCopy();
        ObjectNode outageRow = scenario(outageNoAction, "venue_outage_blackout");
        outageRow.put("outage_cancelled_entry_attempt_count", 0)
                .put("outage_deferred_exit_trigger_count", 0)
                .put("outage_blocked_stop_update_count", 0);
        rehash((ObjectNode) outageNoAction.path("stress_evaluation")); rehash(outageNoAction);
        assertFalse(recompute(fixture, outageNoAction, null).gates().path("stress_venue_outage_blackout").asBoolean());

        ObjectNode negativeExpectancy = validReplay.deepCopy();
        candidateSummary(scenario(negativeExpectancy, "fee_slippage"), fixture).put("expectancy_r", -0.01);
        rehash((ObjectNode) negativeExpectancy.path("stress_evaluation")); rehash(negativeExpectancy);
        assertFalse(recompute(fixture, negativeExpectancy, null).gates().path("stress_fee_slippage").asBoolean());

        ObjectNode malformedExpectancy = validReplay.deepCopy();
        candidateSummary(scenario(malformedExpectancy, "fee_slippage"), fixture).put("expectancy_r", "0.0");
        rehash((ObjectNode) malformedExpectancy.path("stress_evaluation")); rehash(malformedExpectancy);
        assertFalse(recompute(fixture, malformedExpectancy, null).gates().path("stress_fee_slippage").asBoolean());

        ObjectNode invalidPrecommitReplay = validReplay.deepCopy();
        ObjectNode invalidPrecommitEvaluation = invalidPrecommitReplay.putObject("stress_evaluation")
                .put("schema", "liquidation-v2-stress-evaluation/1").put("version", 1)
                .put("base_ledger_sha256", invalidPrecommitReplay.path("ledger_sha256").asText());
        invalidPrecommitEvaluation.set("scenario_results",
                ((ArrayNode) validReplay.path("stress_evaluation").path("scenario_results")).deepCopy());
        rehash(invalidPrecommitEvaluation); rehash(invalidPrecommitReplay);
        ObjectNode physical = fixture.physicalFreeze().deepCopy();
        physical.set("precommit", JsonHashes.mapper().createObjectNode());
        LiquidationV2StagedStatisticsV1.Evaluation invalidPolicy = recompute(
                new Fixture(physical, fixture.plan(), fixture.coreReplay(), invalidPrecommitReplay),
                invalidPrecommitReplay, null);
        assertFalse(invalidPolicy.gates().path("stress_fee_slippage").asBoolean());
        assertEquals(false, invalidPolicy.gates().path("stress_funding_carry").asBoolean());
    }

    private static ObjectNode boundScenario(Fixture fixture, String scenarioId) {
        String artifactSha = "a".repeat(64);
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("scenario_id", scenarioId)
                .put("runner_result_sha256", artifactSha).put("scenario_run_content_sha256", "b".repeat(64))
                .put("runner_ledger_sha256", "c".repeat(64)).put("runner_event_stream_sha256", "d".repeat(64))
                .put("transform_count", 0).put("transform_chain_sha256", "e".repeat(64));
        row.set("scenario_run_artifact", JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("sha256", artifactSha));
        row.put("outage_cancelled_entry_attempt_count", 0)
                .put("outage_deferred_exit_trigger_count", 0)
                .put("outage_blocked_stop_update_count", 0);
        return row;
    }

    private static ObjectNode positiveStressReplay(Fixture fixture) {
        ObjectNode replay = fixture.stagedReplay().deepCopy();
        ObjectNode stress = replay.putObject("stress_evaluation")
                .put("schema", "liquidation-v2-stress-evaluation/1").put("version", 1)
                .put("base_ledger_sha256", replay.path("ledger_sha256").asText());
        ArrayNode rows = stress.putArray("scenario_results");
        for (String id : SCENARIOS) {
            ObjectNode row = boundScenario(fixture, id);
            row.putArray("by_candidate").addObject()
                    .put("candidate_id", fixture.plan().path("candidate_id").asText())
                    .put("completed_observations", 30).put("open_unresolved_count", 0)
                    .put("unresolved_count", 0).put("coverage_blocked_count", 0)
                    .put("expectancy_r", 0.01);
            if ("venue_outage_blackout".equals(id)) row.put("outage_blocked_stop_update_count", 1);
            rows.add(row);
        }
        rehash(stress); rehash(replay);
        return replay;
    }

    private static ObjectNode scenario(ObjectNode replay, String id) {
        for (JsonNode row : replay.path("stress_evaluation").path("scenario_results")) {
            if (id.equals(row.path("scenario_id").asText())) return (ObjectNode) row;
        }
        throw new AssertionError("scenario not found: " + id);
    }

    private static ObjectNode candidateSummary(ObjectNode scenario, Fixture fixture) {
        for (JsonNode summary : scenario.path("by_candidate")) {
            if (fixture.plan().path("candidate_id").asText().equals(summary.path("candidate_id").asText())) {
                return (ObjectNode) summary;
            }
        }
        throw new AssertionError("routed candidate summary not found for " + scenario.path("scenario_id").asText());
    }

    private static void assertFailureContains(org.junit.jupiter.api.function.Executable action, String message) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action);
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
    }

    private static LiquidationV2StagedStatisticsV1.Evaluation recompute(Fixture fixture,
            ObjectNode replay, ObjectNode state) {
        return LiquidationV2StagedStatisticsV1.recompute(fixture.physicalFreeze(), fixture.plan(),
                fixture.coreReplay(), replay, null, null, state);
    }

    private static ObjectNode withOneExposureRef(ObjectNode source) {
        ObjectNode replay = source.deepCopy();
        ObjectNode refs = (ObjectNode) replay.path("pre_outcome_exposure_attempts");
        refs.put("attempt_count", 1).putArray("attempts").addObject()
                .put("candidate_id", replay.path("candidate_id").asText())
                .put("attempt_freeze_sha256", "1".repeat(64)).put("physical_freeze_sha256", "2".repeat(64))
                .put("behavior_sha256", "3".repeat(64));
        rehash(refs); rehash(replay);
        return replay;
    }

    private static ObjectNode familyState() {
        ObjectNode state = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2FamilyExposureAttemptV1.STATE_SCHEMA).put("version", 1);
        state.putArray("known_v002_attempts");
        rehash(state);
        return state;
    }

    private static Fixture fixture() throws Exception {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        Instant decision = Instant.parse(profile.path("windows").path("decision_start").asText()).plus(Duration.ofDays(10));
        ObjectNode precommit = readPrecommit();
        ObjectNode physical = JsonHashes.mapper().createObjectNode();
        physical.set("profile", profile);
        physical.set("precommit", precommit);
        ObjectNode inventory = coreInventory();
        ObjectNode intent = initialIntent("pair-1", "setup-1", decision);
        String intentSha = JsonHashes.canonicalSha256(intent);
        Instant fill = decision.plusSeconds(60), exit = decision.plus(Duration.ofDays(2));
        ObjectNode coreOpportunity = opportunity(ROUTED, "pair-1", "setup-1", decision, intent, intentSha)
                .put("outcome_state", "CLOSED_TRADE").put("first_fill_time", fill.toString())
                .put("exit_time", exit.toString()).put("outcome_available_time", exit.toString())
                .put("net_pnl_usdt", 100.0).put("reference_risk_usdt", 200.0);
        ObjectNode coreReplay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY");
        coreReplay.putObject("freeze").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        coreReplay.putArray("opportunities").add(coreOpportunity);
        ObjectNode audit = coreReplay.putArray("route_audit").addObject()
                .put("status", "CONFIRMED_STAGE_ONE_INTENT").put("candidate_id", ROUTED)
                .put("intent_id", "intent-1").put("setup_id", "setup-1").put("pair_id", "pair-1")
                .put("asset", "BTC").put("decision_time", decision.toString())
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("initial_intent_sha256", intentSha);
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

        ObjectNode stageOpportunity = opportunity(plan.path("candidate_id").asText(), "pair-1", "setup-1",
                decision, intent, intentSha).put("outcome_state", "CLOSED_TRADE")
                .put("first_fill_time", fill.toString()).put("exit_time", exit.toString())
                .put("outcome_available_time", exit.toString()).put("net_pnl_usdt", 500.0)
                .put("reference_risk_usdt", 1000.0).put("first_fill_reference_equity_usdt", 20_000.0)
                .put("full_position_reference_risk_usdt", 1000.0)
                .put("full_position_reference_risk_basis", LiquidationV2StagedCandidateInventoryV1.FULL_POSITION_R_BASIS)
                .put("position_episode_id", "episode-1").put("filled_quantity", 10.0).put("fill_price", 100.0);
        ObjectNode account = JsonHashes.mapper().createObjectNode();
        account.putArray("closed_episodes");
        ObjectNode position = account.putArray("positions").addObject().put("status", "CLOSED")
                .put("active_setup_id", "setup-1").put("asset", "BTC").put("first_fill_time", fill.toEpochMilli())
                .put("entry_costs_usdt", 10.0).put("exit_costs_usdt", 10.0)
                .put("funding_debits_for_risk_headroom_usdt", 10.0);
        position.putArray("exits").addObject().put("exit_time", exit.toString());
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", plan.path("source_mode").asText())
                .put("candidate_id", plan.path("candidate_id").asText()).put("mode_id", plan.path("mode_id").asText())
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText());
        replay.set("staged_plan", plan.deepCopy());
        replay.putArray("opportunities").add(stageOpportunity);
        replay.putObject("pre_outcome_exposure_attempts").put("schema", "liquidation-v2-pre-outcome-exposure-refs/1")
                .put("attempt_count", 0).putArray("attempts");
        rehash((ObjectNode) replay.path("pre_outcome_exposure_attempts"));
        ObjectNode ledger = replay.putObject("ledger");
        ledger.putArray("accounts").addObject().put("candidate_id", plan.path("candidate_id").asText())
                .set("account", account);
        rehash(ledger);
        replay.put("ledger_sha256", ledger.path("content_sha256").asText());
        ObjectNode path = replay.putObject("account_path_summary")
                .put("schema", "liquidation-v2-account-path-summary/1")
                .put("ledger_sha256", ledger.path("content_sha256").asText())
                .put("drawdown_basis", "ADVERSE_MARK_OHLC_EXTREMUM_BEFORE_EXIT_PATH")
                .put("maximum_adverse_mark_drawdown_fraction", 0.02)
                .put("starting_equity_usdt", 20_000.0).put("ending_marked_equity_usdt", 20_500.0);
        rehash(path); rehash(replay);
        return new Fixture(physical, plan, coreReplay, replay);
    }

    private static ObjectNode coreInventory() {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA).put("version", 1)
                .put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode rows = inventory.putArray("current_candidates");
        rows.addObject().put("candidate_id", ROUTED).put("variant", VARIANT);
        rows.addObject().put("candidate_id", "liquidation-v2-core-always-continuation-one-entry")
                .put("variant", "ALWAYS_CONTINUATION_CONTROL");
        rows.addObject().put("candidate_id", "liquidation-v2-core-always-reversal-one-entry")
                .put("variant", "ALWAYS_REVERSAL_CONTROL");
        rows.addObject().put("candidate_id", "liquidation-v2-price-oi-only-event-diagnostic")
                .put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        rehash(inventory);
        return inventory;
    }

    private static ObjectNode initialIntent(String pair, String setup, Instant decision) {
        ObjectNode seed = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-anchor-setup-seed/1").put("version", 1).put("setup_id", setup);
        rehash(seed);
        ObjectNode intent = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-confirmed-entry-intent/1").put("version", 1)
                .put("intent_id", "intent-1").put("setup_id", setup).put("pair_id", pair)
                .put("asset", "BTC").put("variant", VARIANT).put("branch", "CONTINUATION")
                .put("direction", "SHORT").put("stage", 1).put("decision_time", decision.toString())
                .put("diagnostic_only", false);
        intent.set("setup_seed", seed); rehash(intent);
        return intent;
    }

    private static ObjectNode opportunity(String candidate, String pair, String setup, Instant decision,
            ObjectNode intent, String intentSha) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("candidate_id", candidate)
                .put("variant", VARIANT).put("stage", 1).put("pair_id", pair).put("asset", "BTC")
                .put("decision_time", decision.toString()).put("branch", "CONTINUATION").put("direction", "SHORT")
                .put("setup_id", setup).put("initial_intent_sha256", intentSha);
        row.set("initial_intent", intent.deepCopy());
        return row;
    }

    private static ObjectNode readPrecommit() throws Exception {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) {
            cursor = cursor.getParent();
        }
        if (cursor == null) throw new IllegalStateException("frozen v002 precommit not found from test cwd");
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readString(
                cursor.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json")));
    }

    private static void rehash(ObjectNode node) {
        node.remove("content_sha256");
        node.put("content_sha256", JsonHashes.ownHash(node));
    }

    private record Fixture(ObjectNode physicalFreeze, ObjectNode plan, ObjectNode coreReplay, ObjectNode stagedReplay) {}
}

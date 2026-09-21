package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LiquidationV2StagedStatisticsV1Test {
    private static final String ROUTED_ID = "liquidation-v2-core-routed-one-entry";
    private static final String ROUTED = "ROUTED_REVERSAL_CONTINUATION";
    private static final String PAIR = "pair-2023-12-29";
    private static final String ASSET = "BTC";
    private static final String SETUP = "setup-1";

    @Test
    void equalRiskNormalizedReturnsHaveZeroPairedGainAndUnionExposureControlsBlocksAndAvailability() throws Exception {
        Fixture fixture = fixture("CLOSED_TRADE", true);
        LiquidationV2StagedStatisticsV1.Evaluation result = evaluate(fixture);
        ObjectNode stats = result.statistics();
        JsonNode pair = stats.path("paired_outcome_inventory").get(0);

        assertEquals(1.0, LiquidationV2StagedStatisticsV1.normalizedR(fixture.coreOpportunity(), true));
        assertEquals(1.0, LiquidationV2StagedStatisticsV1.normalizedR(fixture.stagedOpportunity(), false));
        assertEquals(0.0, stats.path("observed_incremental_mean_r_vs_predecessor").asDouble());
        assertEquals(0.0, stats.path("incremental_p20_vs_predecessor_r").asDouble());
        assertEquals(1.0, stats.path("local_predecessor_centered_maxstat_p_value").asDouble());
        assertEquals("RESOLVED", pair.path("joint_resolution_state").asText());
        assertEquals(fixture.baselineExit().toString(), pair.path("union_final_exit_time").asText());
        assertEquals(fixture.baselineAvailable().toString(), pair.path("joint_outcome_available_time").asText());
        assertEquals(1, stats.path("effective_67d_dependence_components").asInt());
        assertTrue(stats.path("market_time_blocks").size() < fixture.nominalBlockCount(),
                "the predecessor's longer exposure crosses and merges the nominal block boundary");
        assertEquals(10_000, stats.path("synchronized_block_indices").path("draw_count").asInt());
        assertEquals(20260920L, stats.path("synchronized_block_indices").path("seed").asLong());
        assertFalse(result.gates().path("bootstrap_p20_incremental_expectancy_r_positive_vs_predecessor").asBoolean());
        assertTrue(result.blockers().toString().contains("HISTORICAL_FAMILY_K_UNKNOWN"));
    }

    @Test
    void filledCoverageBlockedAnchorRemainsInPairInventoryAndCannotBecomeResolvedSubsetEvidence() throws Exception {
        Fixture fixture = fixture("COVERAGE_BLOCKED", false);
        LiquidationV2StagedStatisticsV1.Evaluation result = evaluate(fixture);
        ObjectNode stats = result.statistics();
        JsonNode pair = stats.path("paired_outcome_inventory").get(0);

        assertEquals(1, stats.path("unresolved_count").asInt());
        assertEquals(1, stats.path("coverage_blocked_count").asInt());
        assertEquals(0, stats.path("resolved_anchor_count").asInt());
        assertEquals("OPEN_UNRESOLVED", pair.path("joint_resolution_state").asText());
        assertTrue(stats.path("incremental_p20_vs_predecessor_r").isNull());
        assertFalse(result.gates().path("all_anchor_outcomes_resolved").asBoolean());
        assertFalse(result.gates().path("bootstrap_p20_expectancy_r_positive").asBoolean());
        assertTrue(result.blockers().toString().contains("UNRESOLVED_OPEN_OR_COVERAGE_BLOCKED_ANCHORS_RETAINED"));
    }

    @Test
    void decimalNodeAndCanonicalJsonReplayProduceIdenticalStatisticsEvidence() throws Exception {
        Fixture fixture = fixture("CLOSED_TRADE", true);
        ObjectNode inMemoryReplay = fixture.stagedReplay().deepCopy();
        ObjectNode path = (ObjectNode) inMemoryReplay.path("account_path_summary");
        path.put("ending_marked_equity_usdt", new BigDecimal("20312.123456789123456789"));
        rehash(path);
        rehash(inMemoryReplay);
        ObjectNode reopenedReplay = (ObjectNode) JsonHashes.mapper().readTree(JsonHashes.canonicalBytes(inMemoryReplay));

        LiquidationV2StagedStatisticsV1.Evaluation inMemory = LiquidationV2StagedStatisticsV1.recompute(
                fixture.physicalFreeze(), fixture.plan(), fixture.coreReplay(), inMemoryReplay, null, null, null);
        LiquidationV2StagedStatisticsV1.Evaluation reopened = LiquidationV2StagedStatisticsV1.recompute(
                fixture.physicalFreeze(), fixture.plan(), fixture.coreReplay(), reopenedReplay, null, null, null);

        assertEquals(JsonHashes.canonicalSha256(inMemory.statistics()), JsonHashes.canonicalSha256(reopened.statistics()));
        assertEquals(JsonHashes.canonicalSha256(inMemory.gates()), JsonHashes.canonicalSha256(reopened.gates()));
        assertEquals(JsonHashes.canonicalSha256(inMemory.blockers()), JsonHashes.canonicalSha256(reopened.blockers()));
        double expectedNet = reopenedReplay.path("account_path_summary").path("ending_marked_equity_usdt")
                .decimalValue().subtract(reopenedReplay.path("account_path_summary")
                        .path("starting_equity_usdt").decimalValue()).doubleValue();
        assertEquals(expectedNet, inMemory.statistics().path("portfolio_net_pnl_usdt").asDouble(), 0.0,
                "statistics use the JCS-reopened numeric amount exactly, without business-tolerance rounding");
        ObjectNode inMemoryEvidence = LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(
                fixture.plan(), inMemoryReplay, inMemory);
        ObjectNode reopenedEvidence = LiquidationV2StagedCandidateInventoryV1.buildReplayEvidence(
                fixture.plan(), reopenedReplay, reopened);
        assertEquals(JsonHashes.canonicalSha256(inMemoryEvidence), JsonHashes.canonicalSha256(reopenedEvidence));
    }

    private static LiquidationV2StagedStatisticsV1.Evaluation evaluate(Fixture fixture) {
        return LiquidationV2StagedStatisticsV1.recompute(fixture.physicalFreeze(), fixture.plan(),
                fixture.coreReplay(), fixture.stagedReplay(), null, null, null);
    }

    private static Fixture fixture(String stagedState, boolean longerCoreExposure) throws Exception {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        Instant blockBoundary = Instant.parse(profile.path("windows").path("decision_start").asText()).plus(Duration.ofDays(67));
        Instant decision = blockBoundary.minus(Duration.ofDays(3));
        Instant firstFill = decision.plusSeconds(60);
        Instant stageExit = decision.plus(Duration.ofDays(1));
        Instant coreExit = longerCoreExposure ? blockBoundary.plus(Duration.ofDays(1)) : decision.plus(Duration.ofDays(2));
        Instant baselineAvailable = coreExit.plus(Duration.ofHours(12));
        ObjectNode precommit = readPrecommit();

        ObjectNode inventory = coreInventory();
        ObjectNode intent = intent(decision);
        String intentSha = JsonHashes.canonicalSha256(intent);
        ObjectNode coreOpportunity = baseOpportunity(ROUTED_ID, PAIR, decision, intent, intentSha);
        coreOpportunity.put("outcome_state", "CLOSED_TRADE").put("first_fill_time", firstFill.toString())
                .put("exit_time", coreExit.toString()).put("outcome_available_time", baselineAvailable.toString())
                .put("net_pnl_usdt", 200).put("reference_risk_usdt", 200);
        ObjectNode coreReplay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY");
        coreReplay.putObject("freeze").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        coreReplay.putArray("opportunities").add(coreOpportunity);
        ObjectNode signal = coreReplay.putArray("route_audit").addObject()
                .put("status", "CONFIRMED_STAGE_ONE_INTENT").put("candidate_id", ROUTED_ID)
                .put("intent_id", intent.path("intent_id").asText()).put("setup_id", SETUP)
                .put("pair_id", PAIR).put("asset", ASSET).put("decision_time", decision.toString())
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("initial_intent_sha256", intentSha);
        signal.set("initial_intent", intent.deepCopy());
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

        ObjectNode staged = baseOpportunity(plan.path("candidate_id").asText(), PAIR, decision, intent, intentSha);
        staged.put("outcome_state", stagedState).put("first_fill_time", firstFill.toString())
                .put("position_episode_id", "episode-1").put("first_fill_reference_equity_usdt", 20000)
                .put("full_position_reference_risk_usdt", 1000).put("reference_risk_usdt", 1000)
                .put("full_position_reference_risk_basis", LiquidationV2StagedCandidateInventoryV1.FULL_POSITION_R_BASIS)
                .put("filled_quantity", 10).put("fill_price", 100);
        if ("CLOSED_TRADE".equals(stagedState)) {
            staged.put("exit_time", stageExit.toString()).put("outcome_available_time", stageExit.toString())
                    .put("net_pnl_usdt", 1000);
        } else {
            staged.putNull("exit_time").putNull("outcome_available_time").putNull("net_pnl_usdt")
                    .putArray("reason_codes").add("COVERAGE_SOURCE_GAP");
        }
        ObjectNode stagedReplay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("candidate_id", plan.path("candidate_id").asText())
                .put("mode_id", plan.path("mode_id").asText()).put("plan_sha256", plan.path("content_sha256").asText())
                .put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText());
        stagedReplay.set("staged_plan", plan.deepCopy());
        stagedReplay.putArray("opportunities").add(staged);
        ObjectNode refs = stagedReplay.putObject("pre_outcome_exposure_attempts")
                .put("schema", "liquidation-v2-pre-outcome-exposure-refs/1").put("attempt_count", 0);
        refs.putArray("attempts"); rehash(refs);
        ObjectNode accountPosition = JsonHashes.mapper().createObjectNode().put("status", "CLOSED")
                .put("active_setup_id", SETUP).put("asset", ASSET).put("first_fill_time", firstFill.toEpochMilli())
                .put("entry_costs_usdt", 10).put("exit_costs_usdt", 20)
                .put("funding_debits_for_risk_headroom_usdt", 10);
        accountPosition.putArray("exits").addObject().put("exit_time", stageExit.toString());
        ObjectNode account = JsonHashes.mapper().createObjectNode();
        account.putArray("closed_episodes");
        account.putArray("positions").add(accountPosition);
        ObjectNode ledger = stagedReplay.putObject("ledger").putArray("accounts").addObject()
                .put("candidate_id", plan.path("candidate_id").asText());
        ledger.set("account", account);
        rehash(ledger);
        String ledgerSha = ledger.path("content_sha256").asText();
        stagedReplay.put("ledger_sha256", ledgerSha);
        ObjectNode path = stagedReplay.putObject("account_path_summary")
                .put("schema", "liquidation-v2-account-path-summary/1").put("ledger_sha256", ledgerSha)
                .put("drawdown_basis", "ADVERSE_MARK_OHLC_EXTREMUM_BEFORE_EXIT_PATH")
                .put("maximum_adverse_mark_drawdown_fraction", 0.02)
                .put("starting_equity_usdt", 20000).put("ending_marked_equity_usdt", 21000);
        rehash(path);
        rehash(stagedReplay);

        ObjectNode physicalFreeze = JsonHashes.mapper().createObjectNode();
        physicalFreeze.set("profile", profile);
        physicalFreeze.set("precommit", precommit);
        return new Fixture(profile, physicalFreeze, inventory, coreReplay, coreOpportunity, plan,
                stagedReplay, staged, coreExit, baselineAvailable, nominalBlocks(profile));
    }

    private static ObjectNode baseOpportunity(String candidateId, String pair, Instant decision,
            ObjectNode intent, String intentSha) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("candidate_id", candidateId)
                .put("variant", ROUTED).put("stage", 1).put("pair_id", pair).put("asset", ASSET)
                .put("decision_time", decision.toString()).put("branch", "CONTINUATION").put("direction", "SHORT")
                .put("setup_id", SETUP).put("initial_intent_sha256", intentSha);
        row.set("initial_intent", intent.deepCopy());
        return row;
    }

    private static ObjectNode intent(Instant decision) {
        ObjectNode seed = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-anchor-setup-seed/1")
                .put("version", 1).put("setup_id", SETUP);
        seed.put("content_sha256", JsonHashes.ownHash(seed));
        ObjectNode intent = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-confirmed-entry-intent/1").put("version", 1)
                .put("intent_id", "intent-1").put("setup_id", SETUP).put("pair_id", PAIR)
                .put("asset", ASSET).put("variant", ROUTED).put("branch", "CONTINUATION")
                .put("direction", "SHORT").put("stage", 1).put("decision_time", decision.toString())
                .put("diagnostic_only", false);
        intent.set("setup_seed", seed);
        intent.put("content_sha256", JsonHashes.ownHash(intent));
        return intent;
    }

    private static ObjectNode coreInventory() {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA).put("version", 1)
                .put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode candidates = inventory.putArray("current_candidates");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-routed-one-entry").put("variant", ROUTED);
        candidates.addObject().put("candidate_id", "liquidation-v2-core-always-continuation-one-entry").put("variant", "ALWAYS_CONTINUATION_CONTROL");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-always-reversal-one-entry").put("variant", "ALWAYS_REVERSAL_CONTROL");
        candidates.addObject().put("candidate_id", "liquidation-v2-price-oi-only-event-diagnostic").put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        inventory.put("content_sha256", JsonHashes.ownHash(inventory));
        return inventory;
    }

    private static ObjectNode readPrecommit() throws Exception {
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readString(repositoryRoot()
                .resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json")));
    }

    private static int nominalBlocks(ObjectNode profile) {
        Instant start = Instant.parse(profile.path("windows").path("decision_start").asText());
        Instant end = Instant.parse(profile.path("windows").path("decision_end_exclusive").asText());
        return (int) (Duration.between(start, end).toDays() / 67);
    }

    private static void rehash(ObjectNode object) {
        object.remove("content_sha256");
        object.put("content_sha256", JsonHashes.ownHash(object));
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve(
                "docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) cursor = cursor.getParent();
        if (cursor == null) throw new IllegalStateException("repository root unavailable to staged statistics test");
        return cursor;
    }

    private record Fixture(ObjectNode profile, ObjectNode physicalFreeze, ObjectNode inventory,
            ObjectNode coreReplay, ObjectNode coreOpportunity, ObjectNode plan, ObjectNode stagedReplay,
            ObjectNode stagedOpportunity, Instant baselineExit, Instant baselineAvailable, int nominalBlockCount) {}
}

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
import org.junit.jupiter.api.Test;

/** Verifies staged replay attempt refs against an immutable reopened family prefix. */
class LiquidationV2StagedStatisticsExposurePrefixMatrixTest {
    private static final String CORE_ID = "liquidation-v2-core-routed-one-entry";
    private static final String CORE_VARIANT = "ROUTED_REVERSAL_CONTINUATION";
    private static final String PAIR = "stats-boundary-pair";
    private static final String SETUP = "stats-boundary-setup";
    private static final String ASSET = "BTC";
    private static final Instant DECISION = Instant.parse("2024-01-20T12:00:00Z");
    private static final Instant FILL = DECISION.plusSeconds(60);
    private static final Instant EXIT = DECISION.plus(Duration.ofDays(1));

    @Test
    void matchingDurablePrefixIsAcceptedWithoutPromotingUnknownHistoricalK() throws Exception {
        Fixture fixture = fixture();
        ObjectNode replay = withOneAttempt(fixture);
        ObjectNode state = stateWith(replay.path("pre_outcome_exposure_attempts").path("attempts").get(0), true);

        LiquidationV2StagedStatisticsV1.Evaluation result = recompute(fixture, replay, state);
        assertEquals(1, result.statistics().path("pre_outcome_exposure_attempt_refs").path("attempt_count").asInt());
        assertTrue(result.statistics().path("historical_cumulative_k").isNull());
        assertEquals("UNKNOWN_NO_VERIFIED_CANONICAL_PREDECESSOR_EXPOSURE_INVENTORY",
                result.statistics().path("historical_cumulative_k_status").asText());
        assertTrue(result.blockers().toString().contains("HISTORICAL_FAMILY_K_UNKNOWN"));
    }

    @Test
    void durableStateMustContainTheExactStableAttemptIdentity() throws Exception {
        Fixture fixture = fixture();
        ObjectNode replay = withOneAttempt(fixture);
        ObjectNode ref = ((ObjectNode) replay.path("pre_outcome_exposure_attempts").path("attempts").get(0)).deepCopy();
        ref.put("physical_freeze_sha256", "f".repeat(64));
        ObjectNode state = stateWith(ref, false);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> recompute(fixture, replay, state));
        assertTrue(error.getMessage().contains("immutable replay attempt refs are not present in the reopened durable family prefix"),
                error.getMessage());
    }

    @Test
    void duplicateReceiptIdentityIsRejectedBeforeConsultingTheDurablePrefix() throws Exception {
        Fixture fixture = fixture();
        ObjectNode replay = withOneAttempt(fixture);
        ObjectNode refs = (ObjectNode) replay.path("pre_outcome_exposure_attempts");
        ((ArrayNode) refs.path("attempts")).add(refs.path("attempts").get(0).deepCopy());
        refs.put("attempt_count", 2); rehash(refs); rehash(replay);
        ObjectNode state = stateWith(refs.path("attempts").get(0), true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> recompute(fixture, replay, state));
        assertTrue(error.getMessage().contains("pre-outcome exposure refs contain duplicate or malformed identities"),
                error.getMessage());
    }

    private static LiquidationV2StagedStatisticsV1.Evaluation recompute(Fixture fixture, ObjectNode replay, ObjectNode state) {
        return LiquidationV2StagedStatisticsV1.recompute(fixture.physicalFreeze(), fixture.plan(),
                fixture.coreReplay(), replay, null, null, state);
    }

    private static ObjectNode withOneAttempt(Fixture fixture) {
        ObjectNode replay = fixture.stagedReplay().deepCopy();
        ObjectNode refs = (ObjectNode) replay.path("pre_outcome_exposure_attempts");
        ObjectNode attempt = refs.putArray("attempts").addObject()
                .put("candidate_id", fixture.plan().path("candidate_id").asText())
                .put("attempt_freeze_sha256", fixture.plan().path("content_sha256").asText())
                .put("physical_freeze_sha256", "b".repeat(64))
                .put("behavior_sha256", JsonHashes.canonicalSha256(fixture.plan().path("candidate")));
        refs.put("attempt_count", 1); rehash(refs); rehash(replay);
        return replay;
    }

    private static ObjectNode stateWith(com.fasterxml.jackson.databind.JsonNode ref, boolean includeExact) {
        ObjectNode state = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2FamilyExposureAttemptV1.STATE_SCHEMA).put("version", 1);
        ArrayNode attempts = state.putArray("known_v002_attempts");
        if (includeExact) {
            attempts.add(ref.deepCopy());
            attempts.addObject().put("candidate_id", "later-known-attempt")
                    .put("attempt_freeze_sha256", "e".repeat(64))
                    .put("physical_freeze_sha256", "f".repeat(64))
                    .put("behavior_sha256", "1".repeat(64));
        } else attempts.addObject().put("candidate_id", ref.path("candidate_id").asText())
                .put("attempt_freeze_sha256", ref.path("attempt_freeze_sha256").asText())
                .put("physical_freeze_sha256", "d".repeat(64))
                .put("behavior_sha256", ref.path("behavior_sha256").asText());
        rehash(state); return state;
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

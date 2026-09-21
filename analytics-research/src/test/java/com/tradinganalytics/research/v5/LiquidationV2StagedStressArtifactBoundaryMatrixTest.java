package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Exercises staged-statistics stress artifact bindings and gate evidence boundaries. */
class LiquidationV2StagedStressArtifactBoundaryMatrixTest {
    private static final String CORE_ID = "liquidation-v2-core-routed-one-entry";
    private static final String ROUTED = "ROUTED_REVERSAL_CONTINUATION";
    private static final String ASSET = "BTC";
    private static final String PAIR = "baseline-validation-pair";
    private static final String SETUP = "baseline-validation-setup";
    private static final Instant DECISION = Instant.parse("2024-01-20T12:00:00Z");
    private static final Instant FILL = DECISION.plusSeconds(60);
    private static final Instant EXIT = DECISION.plus(Duration.ofDays(1));

    private static final List<String> SCENARIOS = List.of("fee_slippage", "funding_carry",
            "adverse_execution_gap", "liquidity_capacity", "venue_outage_blackout");

    @Test
    void validFrozenStressMatrixPassesAtTheExactObservationAndExpectancyFloors() throws Exception {
        Fixture fixture = fixture();
        ObjectNode replay = positiveStressReplay(fixture);
        var evaluation = evaluate(fixture, replay);
        for (String id : SCENARIOS) assertTrue(evaluation.gates().path("stress_" + id).asBoolean(false), id);
        assertEquals(30, summary(scenario(replay, "fee_slippage"), fixture).path("completed_observations").asInt());
        assertEquals(0.0, summary(scenario(replay, "fee_slippage"), fixture).path("expectancy_r").asDouble());
    }

    @Test
    void stressArtifactsAndRoutedSummaryFieldsFailClosedIndividually() throws Exception {
        Fixture fixture = fixture();
        ObjectNode valid = positiveStressReplay(fixture);
        assertTrue(evaluate(fixture, valid).gates().path("stress_fee_slippage").asBoolean());
        List<Mutation> mutations = List.of(
                new Mutation("missing evaluation", replay -> replay.remove("stress_evaluation"), false),
                new Mutation("evaluation schema", replay -> evaluation(replay).put("schema", "wrong/1"), false),
                new Mutation("evaluation hash", replay -> evaluation(replay).put("content_sha256", "f".repeat(64)), true),
                new Mutation("base ledger binding", replay -> evaluation(replay).put("base_ledger_sha256", "f".repeat(64)), false),
                new Mutation("scenario result shape", replay -> evaluation(replay).set("scenario_results", JsonHashes.mapper().createObjectNode()), false),
                new Mutation("scenario absent", replay -> scenarioResults(replay).remove(0), false),
                new Mutation("candidate summary absent", replay -> scenario(replay, "fee_slippage").putArray("by_candidate").addObject().put("candidate_id", "other"), false),
                new Mutation("artifact schema", replay -> artifact(scenario(replay, "fee_slippage")).put("schema", "wrong/1"), false),
                new Mutation("artifact sha malformed", replay -> artifact(scenario(replay, "fee_slippage")).put("sha256", "not-a-hash"), false),
                new Mutation("artifact runner sha mismatch", replay -> scenario(replay, "fee_slippage").put("runner_result_sha256", "f".repeat(64)), false),
                new Mutation("scenario content hash malformed", replay -> scenario(replay, "fee_slippage").put("scenario_run_content_sha256", "bad"), false),
                new Mutation("runner ledger hash malformed", replay -> scenario(replay, "fee_slippage").put("runner_ledger_sha256", "bad"), false),
                new Mutation("runner event hash malformed", replay -> scenario(replay, "fee_slippage").put("runner_event_stream_sha256", "bad"), false),
                new Mutation("fractional transform count", replay -> scenario(replay, "fee_slippage").put("transform_count", 0.5), false),
                new Mutation("negative transform count", replay -> scenario(replay, "fee_slippage").put("transform_count", -1), false),
                new Mutation("transform chain malformed", replay -> scenario(replay, "fee_slippage").put("transform_chain_sha256", "bad"), false));
        for (Mutation mutation : mutations) {
            ObjectNode changed = valid.deepCopy();
            mutation.change().accept(changed);
            if (mutation.preserveEvaluationHash()) rehash(changed); else sealStressAndReplay(changed);
            var result = evaluate(fixture, changed);
            assertFalse(result.gates().path("stress_fee_slippage").asBoolean(), mutation.name());
        }
    }

    @Test
    void eachIndependentOutageActionCanSatisfyTheFrozenImpactRequirement() throws Exception {
        Fixture fixture = fixture();
        for (String action : List.of("outage_cancelled_entry_attempt_count", "outage_deferred_exit_trigger_count",
                "outage_blocked_stop_update_count")) {
            ObjectNode replay = positiveStressReplay(fixture);
            ObjectNode outage = scenario(replay, "venue_outage_blackout");
            outage.put("outage_cancelled_entry_attempt_count", 0).put("outage_deferred_exit_trigger_count", 0)
                    .put("outage_blocked_stop_update_count", 0).put(action, 1);
            sealStressAndReplay(replay);
            assertTrue(evaluate(fixture, replay).gates().path("stress_venue_outage_blackout").asBoolean(), action);
        }
    }

    @Test
    void stressCountsRejectFractionalStringNegativeOverflowAndMissingValues() throws Exception {
        Fixture fixture = fixture();
        ObjectNode valid = positiveStressReplay(fixture);
        assertTrue(evaluate(fixture, valid).gates().path("stress_fee_slippage").asBoolean());

        List<CounterMutation> invalidCounts = List.of(
                new CounterMutation("fractional", (row, field) -> row.put(field, 30.5)),
                new CounterMutation("numeric string", (row, field) -> row.put(field, "30")),
                new CounterMutation("negative", (row, field) -> row.put(field, -1)),
                new CounterMutation("int overflow", (row, field) -> row.put(field, (long) Integer.MAX_VALUE + 1L)),
                new CounterMutation("missing", ObjectNode::remove));

        for (String field : List.of("completed_observations", "open_unresolved_count",
                "unresolved_count", "coverage_blocked_count")) {
            for (CounterMutation invalid : invalidCounts) {
                ObjectNode replay = valid.deepCopy();
                invalid.mutate().accept(summary(scenario(replay, "fee_slippage"), fixture), field);
                sealStressAndReplay(replay);
                assertFalse(evaluate(fixture, replay).gates().path("stress_fee_slippage").asBoolean(),
                        field + " " + invalid.name());
            }
        }

        for (CounterMutation invalid : invalidCounts) {
            ObjectNode replay = valid.deepCopy();
            invalid.mutate().accept(scenario(replay, "fee_slippage"), "transform_count");
            sealStressAndReplay(replay);
            assertFalse(evaluate(fixture, replay).gates().path("stress_fee_slippage").asBoolean(),
                    "transform_count " + invalid.name());
        }

        for (String field : List.of("outage_cancelled_entry_attempt_count", "outage_deferred_exit_trigger_count",
                "outage_blocked_stop_update_count")) {
            for (CounterMutation invalid : invalidCounts) {
                ObjectNode replay = valid.deepCopy();
                ObjectNode outage = scenario(replay, "venue_outage_blackout");
                outage.put("outage_cancelled_entry_attempt_count", 0).put("outage_deferred_exit_trigger_count", 0)
                        .put("outage_blocked_stop_update_count", 0);
                invalid.mutate().accept(outage, field);
                sealStressAndReplay(replay);
                assertFalse(evaluate(fixture, replay).gates().path("stress_venue_outage_blackout").asBoolean(),
                        field + " " + invalid.name());
            }
        }
    }

    @Test
    void coercibleStressCountersCannotMasqueradeAsZeroOrOne() throws Exception {
        Fixture fixture = fixture();
        ObjectNode valid = positiveStressReplay(fixture);
        assertTrue(evaluate(fixture, valid).gates().path("stress_fee_slippage").asBoolean());
        assertTrue(evaluate(fixture, valid).gates().path("stress_venue_outage_blackout").asBoolean());

        List<CounterMutation> zeroCoercions = List.of(
                new CounterMutation("open unresolved fractional zero", (row, field) -> row.put(field, 0.5)),
                new CounterMutation("open unresolved string zero", (row, field) -> row.put(field, "0")),
                new CounterMutation("unresolved fractional zero", (row, field) -> row.put(field, 0.5)),
                new CounterMutation("unresolved string zero", (row, field) -> row.put(field, "0")),
                new CounterMutation("coverage blocked fractional zero", (row, field) -> row.put(field, 0.5)),
                new CounterMutation("coverage blocked string zero", (row, field) -> row.put(field, "0")),
                new CounterMutation("unresolved 32-bit wraparound zero", (row, field) -> row.put(field, 4_294_967_296L)));
        for (String field : List.of("open_unresolved_count", "unresolved_count", "coverage_blocked_count")) {
            for (CounterMutation mutation : zeroCoercions) {
                if (!mutationAppliesTo(field, mutation.name())) continue;
                ObjectNode replay = valid.deepCopy();
                mutation.mutate().accept(summary(scenario(replay, "fee_slippage"), fixture), field);
                sealStressAndReplay(replay);
                assertFalse(evaluate(fixture, replay).gates().path("stress_fee_slippage").asBoolean(),
                        field + " " + mutation.name());
            }
        }

        for (CounterMutation mutation : List.of(
                new CounterMutation("transform 32-bit wraparound zero", (row, field) -> row.put(field, 4_294_967_296L)),
                new CounterMutation("completed observations fractional", (row, field) -> row.put(field, 30.5)))) {
            ObjectNode replay = valid.deepCopy();
            boolean transform = "transform 32-bit wraparound zero".equals(mutation.name());
            mutation.mutate().accept(transform ? scenario(replay, "fee_slippage")
                            : summary(scenario(replay, "fee_slippage"), fixture),
                    transform ? "transform_count" : "completed_observations");
            sealStressAndReplay(replay);
            assertFalse(evaluate(fixture, replay).gates().path("stress_fee_slippage").asBoolean(), mutation.name());
        }

        ObjectNode outageReplay = valid.deepCopy();
        ObjectNode outage = scenario(outageReplay, "venue_outage_blackout");
        outage.put("outage_cancelled_entry_attempt_count", 0).put("outage_deferred_exit_trigger_count", 0)
                .put("outage_blocked_stop_update_count", 0).put("outage_blocked_stop_update_count", 1.5);
        sealStressAndReplay(outageReplay);
        assertFalse(evaluate(fixture, outageReplay).gates().path("stress_venue_outage_blackout").asBoolean());
    }

    private static ObjectNode positiveStressReplay(Fixture fixture) {
        ObjectNode replay = fixture.stagedReplay().deepCopy();
        ObjectNode stress = replay.putObject("stress_evaluation")
                .put("schema", "liquidation-v2-stress-evaluation/1").put("version", 1)
                .put("base_ledger_sha256", replay.path("ledger_sha256").asText());
        ArrayNode scenarioRows = stress.putArray("scenario_results");
        for (String id : SCENARIOS) {
            ObjectNode row = scenarioRows.addObject().put("scenario_id", id)
                    .put("runner_result_sha256", "a".repeat(64)).put("scenario_run_content_sha256", "b".repeat(64))
                    .put("runner_ledger_sha256", "c".repeat(64)).put("runner_event_stream_sha256", "d".repeat(64))
                    .put("transform_count", 0).put("transform_chain_sha256", "e".repeat(64));
            row.set("scenario_run_artifact", JsonHashes.mapper().createObjectNode()
                    .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA)
                    .put("relative_path", "scenario-runs/" + id + ".json").put("sha256", "a".repeat(64)));
            row.put("outage_cancelled_entry_attempt_count", 0).put("outage_deferred_exit_trigger_count", 0)
                    .put("outage_blocked_stop_update_count", "venue_outage_blackout".equals(id) ? 1 : 0);
            row.putArray("by_candidate").addObject().put("candidate_id", fixture.plan().path("candidate_id").asText())
                    .put("completed_observations", 30).put("open_unresolved_count", 0).put("unresolved_count", 0)
                    .put("coverage_blocked_count", 0).put("expectancy_r", 0.0);
        }
        sealStressAndReplay(replay);
        return replay;
    }

    private static ArrayNode scenarioResults(ObjectNode replay) { return (ArrayNode) evaluation(replay).path("scenario_results"); }
    private static ObjectNode evaluation(ObjectNode replay) { return (ObjectNode) replay.path("stress_evaluation"); }
    private static ObjectNode scenario(ObjectNode replay, String id) {
        for (var row : scenarioResults(replay)) if (id.equals(row.path("scenario_id").asText())) return (ObjectNode) row;
        throw new AssertionError("scenario not found: " + id);
    }
    private static ObjectNode artifact(ObjectNode scenario) { return (ObjectNode) scenario.path("scenario_run_artifact"); }
    private static ObjectNode summary(ObjectNode scenario, Fixture fixture) {
        for (var row : scenario.path("by_candidate")) if (fixture.plan().path("candidate_id").asText().equals(row.path("candidate_id").asText())) return (ObjectNode) row;
        throw new AssertionError("routed summary missing");
    }
    private static boolean mutationAppliesTo(String field, String name) {
        return name.startsWith("unresolved ") && "unresolved_count".equals(field)
                || name.startsWith("open unresolved ") && "open_unresolved_count".equals(field)
                || name.startsWith("coverage blocked ") && "coverage_blocked_count".equals(field);
    }
    private static void sealStressAndReplay(ObjectNode replay) {
        JsonNode evaluation = replay.path("stress_evaluation");
        if (evaluation instanceof ObjectNode object) rehash(object);
        rehash(replay);
    }
    private record Mutation(String name, Consumer<ObjectNode> change, boolean preserveEvaluationHash) {}
    private record CounterMutation(String name, java.util.function.BiConsumer<ObjectNode, String> mutate) {}

    private static LiquidationV2StagedStatisticsV1.Evaluation evaluate(Fixture fixture) {
        return evaluate(fixture, fixture.stagedReplay());
    }

    private static LiquidationV2StagedStatisticsV1.Evaluation evaluate(Fixture fixture, ObjectNode replay) {
        return LiquidationV2StagedStatisticsV1.recompute(fixture.physicalFreeze(), fixture.plan(),
                fixture.coreReplay(), replay, null, null, null);
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

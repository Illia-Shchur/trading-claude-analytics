package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Exact staged recompute bindings and core inventory input shape from accepted synthetic baselines. */
class LiquidationV2StagedBindingAndCoreArrayBoundaryMatrixTest {
    private static final String CORE = "liquidation-v2-core-routed-one-entry";
    private static final String PAIR = "binding-pair";
    private static final String SETUP = "binding-setup";
    private static final String ASSET = "BTC";
    private static final Instant DECISION = Instant.parse("2024-01-03T12:00:00Z");

    @Test
    void stagedReplayMustBindPlanCandidateModeAndSourceIndependently() throws Exception {
        Fixture f = fixture();
        assertDoesNotThrow(() -> evaluate(f.physical(), f.plan(), f.coreReplay(), f.stagedReplay()));
        String detached = "staged replay is detached from its exact plan, candidate, mode, or source mode";

        reject(f, mutateStaged(f.stagedReplay(), row -> row.put("plan_sha256", "0".repeat(64))), detached);
        reject(f, mutateStaged(f.stagedReplay(), row -> row.put("candidate_id", "other-candidate")), detached);
        reject(f, mutateStaged(f.stagedReplay(), row -> row.put("mode_id", "THREE_STAGE_MACRO")), detached);
        reject(f, mutateStaged(f.stagedReplay(), row -> row.put("source_mode", "PROXY_DISCLOSED_DEVELOPMENT_ONLY")), detached);

        ObjectNode nestedPlanMismatch = f.stagedReplay().deepCopy();
        ((ObjectNode) nestedPlanMismatch.path("staged_plan")).put("content_sha256", "1".repeat(64));
        rehash(nestedPlanMismatch);
        reject(f, nestedPlanMismatch, detached);
    }

    @Test
    void corePredecessorSchemaIsCheckedAfterItsExactReplayHashIsRebound() throws Exception {
        Fixture f = fixture();
        ObjectNode changedCore = f.coreReplay().deepCopy().put("schema", "wrong-core-schema/1");
        rehash(changedCore);
        ObjectNode plan = f.plan().deepCopy().put("predecessor_replay_sha256", changedCore.path("content_sha256").asText());
        rehash(plan);
        ObjectNode staged = rebindStaged(f.stagedReplay(), plan);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> evaluate(f.physical(), plan, changedCore, staged));
        assertTrue(failure.getMessage().contains("no-macro statistics require the bound core predecessor"),
                failure.getMessage());
    }

    @Test
    void missingAndNonArrayCoreDecisionCollectionsAreRejectedAtTheInventoryBoundary() throws Exception {
        Fixture f = fixture();
        assertDoesNotThrow(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                f.coreReplay(), f.coreEvidence(), f.inventory()));
        for (String field : new String[] {"opportunities", "route_audit"}) {
            for (boolean missing : new boolean[] {true, false}) {
                ObjectNode replay = f.coreReplay().deepCopy();
                if (missing) replay.remove(field);
                else replay.put(field, "not-an-array");
                rehash(replay);
                ObjectNode evidence = f.coreEvidence().deepCopy()
                        .put("replay_result_sha256", replay.path("content_sha256").asText());
                rehash(evidence);
                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                        () -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(replay, evidence, f.inventory()));
                assertTrue(failure.getMessage().contains("core replay lacks routed opportunities or route audit"),
                        failure.getMessage());
            }
        }
    }

    private static void reject(Fixture f, ObjectNode staged, String expected) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> evaluate(f.physical(), f.plan(), f.coreReplay(), staged));
        assertTrue(failure.getMessage().contains(expected),
                () -> "expected '" + expected + "', got '" + failure.getMessage() + "'");
    }

    private static ObjectNode mutateStaged(ObjectNode source, java.util.function.Consumer<ObjectNode> mutation) {
        ObjectNode changed = source.deepCopy();
        mutation.accept(changed);
        rehash(changed);
        return changed;
    }

    private static LiquidationV2StagedStatisticsV1.Evaluation evaluate(ObjectNode physical, ObjectNode plan,
            ObjectNode baseline, ObjectNode staged) {
        return LiquidationV2StagedStatisticsV1.recompute(physical, plan, baseline, staged, null, null, null);
    }

    private static ObjectNode rebindStaged(ObjectNode source, ObjectNode plan) {
        ObjectNode staged = source.deepCopy();
        staged.put("plan_sha256", plan.path("content_sha256").asText());
        staged.set("staged_plan", plan.deepCopy());
        rehash(staged);
        return staged;
    }

    private static Fixture fixture() throws Exception {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        ObjectNode physical = JsonHashes.mapper().createObjectNode().set("profile", profile);
        physical.set("precommit", read(repositoryRoot().resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json")));
        ObjectNode inventory = inventory();
        ObjectNode intent = intent();
        String intentSha = JsonHashes.canonicalSha256(intent);
        ObjectNode coreRow = opportunity(CORE, intent, intentSha);
        ObjectNode core = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY");
        core.putObject("freeze").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        core.putArray("opportunities").add(coreRow);
        coreRow.put("outcome_state", "RESOLVED_NO_TRADE").put("outcome_available_time", DECISION.toString()).put("net_pnl_usdt", 0);
        ObjectNode audit = core.putArray("route_audit").addObject()
                .put("status", "CONFIRMED_STAGE_ONE_INTENT").put("candidate_id", CORE)
                .put("intent_id", intent.path("intent_id").asText()).put("setup_id", SETUP)
                .put("pair_id", PAIR).put("asset", ASSET).put("decision_time", DECISION.toString())
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("initial_intent_sha256", intentSha);
        audit.set("initial_intent", intent.deepCopy());
        rehash(core);
        ObjectNode evidence = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EVIDENCE_SCHEMA).put("version", 1)
                .put("status", "BLOCKED").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("replay_result_sha256", core.path("content_sha256").asText())
                .put("promotion_permitted", false).put("trade_authorization_permitted", false);
        evidence.putArray("advancement_blockers").add("SYNTHETIC_FIXTURE_ONLY");
        rehash(evidence);
        ObjectNode plan = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(core, evidence, inventory);

        ObjectNode stagedRow = opportunity(plan.path("candidate_id").asText(), intent, intentSha);
        stagedRow.put("outcome_state", "RESOLVED_NO_TRADE").put("outcome_available_time", DECISION.toString())
                .put("net_pnl_usdt", 0);
        stagedRow.putArray("reason_codes").add("NO_TRADE");
        ObjectNode staged = newReplay(plan);
        staged.putArray("opportunities").add(stagedRow);
        ObjectNode refs = staged.putObject("pre_outcome_exposure_attempts")
                .put("schema", "liquidation-v2-pre-outcome-exposure-refs/1").put("attempt_count", 0);
        refs.putArray("attempts"); rehash(refs);
        rehash(staged);
        return new Fixture(physical, inventory, core, evidence, plan, staged);
    }

    private static ObjectNode newReplay(ObjectNode plan) {
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", plan.path("source_mode").asText())
                .put("candidate_id", plan.path("candidate_id").asText()).put("mode_id", plan.path("mode_id").asText())
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText());
        result.set("staged_plan", plan.deepCopy());
        return result;
    }

    private static ObjectNode opportunity(String candidate, ObjectNode intent, String intentSha) {
        ObjectNode row = JsonHashes.mapper().createObjectNode()
                .put("candidate_id", candidate).put("variant", "ROUTED_REVERSAL_CONTINUATION").put("stage", 1)
                .put("pair_id", PAIR).put("asset", ASSET).put("decision_time", DECISION.toString())
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("setup_id", SETUP)
                .put("initial_intent_sha256", intentSha);
        row.set("initial_intent", intent.deepCopy());
        return row;
    }

    private static ObjectNode intent() {
        ObjectNode seed = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-anchor-setup-seed/1")
                .put("version", 1).put("setup_id", SETUP);
        rehash(seed);
        ObjectNode intent = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-confirmed-entry-intent/1").put("version", 1)
                .put("intent_id", "binding-intent").put("setup_id", SETUP).put("pair_id", PAIR)
                .put("asset", ASSET).put("variant", "ROUTED_REVERSAL_CONTINUATION")
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("stage", 1)
                .put("decision_time", DECISION.toString()).put("diagnostic_only", false);
        intent.set("setup_seed", seed);
        rehash(intent);
        return intent;
    }

    private static ObjectNode inventory() {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA).put("version", 1)
                .put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode candidates = inventory.putArray("current_candidates");
        candidates.addObject().put("candidate_id", CORE).put("variant", "ROUTED_REVERSAL_CONTINUATION");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-always-continuation-one-entry")
                .put("variant", "ALWAYS_CONTINUATION_CONTROL");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-always-reversal-one-entry")
                .put("variant", "ALWAYS_REVERSAL_CONTROL");
        candidates.addObject().put("candidate_id", "liquidation-v2-price-oi-only-event-diagnostic")
                .put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        rehash(inventory);
        return inventory;
    }

    private static ObjectNode read(Path path) throws Exception {
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readString(path));
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) {
            cursor = cursor.getParent();
        }
        if (cursor == null) throw new IllegalStateException("frozen precommit unavailable");
        return cursor;
    }

    private static void rehash(ObjectNode node) {
        node.remove("content_sha256");
        node.put("content_sha256", JsonHashes.ownHash(node));
    }

    private record Fixture(ObjectNode physical, ObjectNode inventory, ObjectNode coreReplay,
            ObjectNode coreEvidence, ObjectNode plan, ObjectNode stagedReplay) {}
}

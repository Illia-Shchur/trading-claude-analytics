package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Reopens a valid synthetic predecessor and exercises the no-macro plan's frozen policy boundary. */
class LiquidationV2StagedNoMacroPlanReopenMatrixTest {
    private static final String ROUTED = "liquidation-v2-core-routed-one-entry";
    private static final String CONTINUE = "liquidation-v2-core-always-continuation-one-entry";
    private static final String REVERSE = "liquidation-v2-core-always-reversal-one-entry";
    private static final String DIAGNOSTIC = "liquidation-v2-price-oi-only-event-diagnostic";
    private static final String VARIANT = "ROUTED_REVERSAL_CONTINUATION";
    private static final Instant DECISION = Instant.parse("2024-01-03T12:00:00Z");

    @Test
    void validNoMacroPlanReopensAndEveryFrozenPolicyMutationIsRejected() {
        CoreFixture core = core("SYNTHETIC_DEVELOPMENT_ONLY", "BLOCKED");
        ObjectNode valid = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                core.replay(), core.evidence(), core.inventory());
        assertTrue(LiquidationV2StagedCandidateInventoryV1.validateNoMacroPlan(
                valid, core.replay(), core.evidence(), core.inventory()));

        for (PlanMutation mutation : planMutations()) {
            ObjectNode changed = valid.deepCopy();
            mutation.change().accept(changed);
            resealNestedAnchorIfRequested(changed, mutation.name());
            rehash(changed);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> LiquidationV2StagedCandidateInventoryV1.validateNoMacroPlan(
                            changed, core.replay(), core.evidence(), core.inventory()), mutation.name());
            assertTrue(failure.getMessage().contains(mutation.message()),
                    () -> mutation.name() + " expected " + mutation.message() + ", got " + failure.getMessage());
        }
    }

    @Test
    void realCoreNeedsCompleteNonpromotingDevelopmentEvidenceAndExactFourCandidateInventory() {
        CoreFixture real = core("PROXY_DISCLOSED_DEVELOPMENT_ONLY", "DEVELOPMENT");
        assertDoesNotThrow(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                real.replay(), real.evidence(), real.inventory()));

        ObjectNode failedStatus = real.evidence().deepCopy().put("status", "BLOCKED");
        rehash(failedStatus);
        assertFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                real.replay(), failedStatus, real.inventory()), "failed or incomplete real core evidence");

        ObjectNode failedGate = real.evidence().deepCopy();
        ((ObjectNode) failedGate.path("acceptance_gates")).put("second_independent_gate", false);
        rehash(failedGate);
        assertFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                real.replay(), failedGate, real.inventory()), "failed or incomplete real core evidence");

        ObjectNode missingBlockers = real.evidence().deepCopy();
        missingBlockers.remove("advancement_blockers");
        rehash(missingBlockers);
        assertFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                real.replay(), missingBlockers, real.inventory()), "failed or incomplete real core evidence");

        ObjectNode nonemptyBlockers = real.evidence().deepCopy();
        ((ArrayNode) nonemptyBlockers.path("advancement_blockers")).add("UNRESOLVED");
        rehash(nonemptyBlockers);
        assertFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                real.replay(), nonemptyBlockers, real.inventory()), "failed or incomplete real core evidence");

        ObjectNode wrongEvidenceMode = real.evidence().deepCopy().put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY");
        rehash(wrongEvidenceMode);
        assertFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                real.replay(), wrongEvidenceMode, real.inventory()), "replay/evidence/inventory bindings disagree");

        ObjectNode authorizingEvidence = real.evidence().deepCopy().put("promotion_permitted", true);
        rehash(authorizingEvidence);
        assertFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                real.replay(), authorizingEvidence, real.inventory()), "cannot authorize promotion or trading");

        CoreFixture synthetic = core("SYNTHETIC_DEVELOPMENT_ONLY", "BLOCKED");
        ObjectNode promotedFixture = synthetic.evidence().deepCopy().put("trade_authorization_permitted", true);
        rehash(promotedFixture);
        assertFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                synthetic.replay(), promotedFixture, synthetic.inventory()), "cannot authorize promotion or trading");

        CoreFixture wrongReplaySchema = rebindReplay(real, replay -> replay.put("schema", "wrong/1"));
        assertFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                wrongReplaySchema.replay(), wrongReplaySchema.evidence(), wrongReplaySchema.inventory()),
                "exact v002 core replay/evidence/family inventory");
        CoreFixture wrongReplayVersion = rebindReplay(real, replay -> replay.put("version", 2));
        assertFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                wrongReplayVersion.replay(), wrongReplayVersion.evidence(), wrongReplayVersion.inventory()),
                "exact v002 core replay/evidence/family inventory");
        CoreFixture detachedInventoryBinding = rebindReplay(real, replay ->
                ((ObjectNode) replay.path("freeze")).put("candidate_inventory_sha256", "d".repeat(64)));
        assertFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                detachedInventoryBinding.replay(), detachedInventoryBinding.evidence(), detachedInventoryBinding.inventory()),
                "replay/evidence/inventory bindings disagree");
        CoreFixture wrongFreezeMode = rebindReplay(real, replay ->
                ((ObjectNode) replay.path("freeze")).put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY"));
        assertFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                wrongFreezeMode.replay(), wrongFreezeMode.evidence(), wrongFreezeMode.inventory()),
                "replay/evidence/inventory bindings disagree");

        CoreFixture wrongEvidenceSchema = rebindEvidence(real, evidence -> evidence.put("schema", "wrong/1"));
        assertFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                wrongEvidenceSchema.replay(), wrongEvidenceSchema.evidence(), wrongEvidenceSchema.inventory()),
                "exact v002 core replay/evidence/family inventory");
        CoreFixture wrongEvidenceVersion = rebindEvidence(real, evidence -> evidence.put("version", 2));
        assertFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                wrongEvidenceVersion.replay(), wrongEvidenceVersion.evidence(), wrongEvidenceVersion.inventory()),
                "exact v002 core replay/evidence/family inventory");
        CoreFixture wrongEvidenceReplayBinding = rebindEvidence(real, evidence ->
                evidence.put("replay_result_sha256", "e".repeat(64)));
        assertFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                wrongEvidenceReplayBinding.replay(), wrongEvidenceReplayBinding.evidence(), wrongEvidenceReplayBinding.inventory()),
                "replay/evidence/inventory bindings disagree");

        ObjectNode wrongInventorySchema = real.inventory().deepCopy().put("schema", "wrong/1");
        rehash(wrongInventorySchema);
        CoreFixture wrongInventorySchemaBound = rebindInventory(real, wrongInventorySchema);
        assertFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                wrongInventorySchemaBound.replay(), wrongInventorySchemaBound.evidence(), wrongInventorySchemaBound.inventory()),
                "exact v002 core replay/evidence/family inventory");
        ObjectNode wrongInventoryFamily = real.inventory().deepCopy().put("strategy_family", "other-family");
        rehash(wrongInventoryFamily);
        CoreFixture wrongInventoryFamilyBound = rebindInventory(real, wrongInventoryFamily);
        assertFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                wrongInventoryFamilyBound.replay(), wrongInventoryFamilyBound.evidence(), wrongInventoryFamilyBound.inventory()),
                "exact v002 core replay/evidence/family inventory");
        ObjectNode threeCandidateInventory = real.inventory().deepCopy();
        ((ArrayNode) threeCandidateInventory.path("current_candidates")).remove(3);
        rehash(threeCandidateInventory);
        CoreFixture threeCandidateBound = rebindInventory(real, threeCandidateInventory);
        assertFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                threeCandidateBound.replay(), threeCandidateBound.evidence(), threeCandidateBound.inventory()),
                "exact frozen four-candidate inventory");

        ObjectNode wrongInventory = real.inventory().deepCopy();
        ((ObjectNode) wrongInventory.path("current_candidates").get(2)).put("variant", "ALWAYS_CONTINUATION_CONTROL");
        rehash(wrongInventory);
        CoreFixture rebound = rebindInventory(real, wrongInventory);
        assertFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                rebound.replay(), rebound.evidence(), rebound.inventory()), "unexpected or duplicate frozen candidate");

        ObjectNode duplicateInventory = real.inventory().deepCopy();
        ((ObjectNode) duplicateInventory.path("current_candidates").get(1)).put("candidate_id", ROUTED);
        rehash(duplicateInventory);
        CoreFixture duplicateRebound = rebindInventory(real, duplicateInventory);
        assertFailure(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                duplicateRebound.replay(), duplicateRebound.evidence(), duplicateRebound.inventory()),
                "unexpected or duplicate frozen candidate");
    }

    private static List<PlanMutation> planMutations() {
        ArrayList<PlanMutation> cases = new ArrayList<>();
        String base = "staged inventory hash, family, mode or anchor list is invalid";
        String policy = "staged plan differs from the fixed mode, risk, allocation or selection contract";
        cases.add(new PlanMutation("schema", p -> p.put("schema", "wrong/1"), base));
        cases.add(new PlanMutation("version", p -> p.put("version", 2), base));
        cases.add(new PlanMutation("strategy family", p -> p.put("strategy_family", "other"), base));
        cases.add(new PlanMutation("mode", p -> p.put("mode_id", "UNRECOGNIZED_MODE"), base));
        cases.add(new PlanMutation("anchor digest", p -> p.put("anchor_inventory_sha256", "a".repeat(64)), base));
        cases.add(new PlanMutation("anchor count", p -> p.put("decision_anchor_count", 9), base));
        cases.add(new PlanMutation("candidate missing", p -> p.remove("candidate"), base));
        cases.add(new PlanMutation("candidate id", p -> p.put("candidate_id", "unknown"), policy));
        cases.add(new PlanMutation("nested candidate id", p -> ((ObjectNode) p.path("candidate")).put("candidate_id", "unknown"), policy));
        cases.add(new PlanMutation("macro policy", p -> p.put("macro_gate_policy", "REQUIRE_MACRO_CONFIRMATION"), policy));
        cases.add(new PlanMutation("nested macro policy", p -> ((ObjectNode) p.path("candidate")).put("macro_gate_policy", "REQUIRE_MACRO_CONFIRMATION"), policy));
        cases.add(new PlanMutation("candidate anchor digest", p -> ((ObjectNode) p.path("candidate")).put("initial_pair_inventory_sha256", "b".repeat(64)), policy));
        cases.add(new PlanMutation("R basis", p -> p.put("staged_reference_r_basis", "PER_TRANCHE"), policy));
        cases.add(new PlanMutation("nested R basis", p -> ((ObjectNode) p.path("candidate")).put("reference_risk_basis", "PER_TRANCHE"), policy));
        cases.add(new PlanMutation("position risk fraction", p -> p.put("staged_position_risk_fraction", 0.04), policy));
        cases.add(new PlanMutation("risk sum", p -> p.put("stage_risk_fraction_sum", 0.04), policy));
        cases.add(new PlanMutation("candidate full risk fraction", p -> ((ObjectNode) p.path("candidate")).put("full_position_reference_risk_fraction", 0.04), policy));
        cases.add(new PlanMutation("nested risk sum", p -> ((ObjectNode) p.path("candidate")).put("stage_risk_fraction_sum", 0.04), policy));
        cases.add(new PlanMutation("risk fractions as wrong shape", p -> p.put("stage_risk_fractions", "0.01,0.01,0.03"), policy));
        cases.add(new PlanMutation("risk fractions wrong allocation", p -> p.putArray("stage_risk_fractions").add(.01).add(.02).add(.02), policy));
        cases.add(new PlanMutation("nested risk fractions", p -> ((ObjectNode) p.path("candidate")).putArray("stage_risk_fractions").add(.05), policy));
        cases.add(new PlanMutation("selection enabled", p -> p.put("outcome_selection_permitted", true), policy));
        cases.add(new PlanMutation("promotion enabled", p -> p.put("promotion_permitted", true), policy));
        cases.add(new PlanMutation("malformed core replay lineage", p -> ((ObjectNode) p.path("core_anchor_lineage")).put("core_replay_sha256", "not-a-hash"),
                "staged plan lost its immutable core anchor lineage"));
        cases.add(new PlanMutation("rebound core replay lineage", p -> ((ObjectNode) p.path("core_anchor_lineage")).put("core_replay_sha256", "c".repeat(64)),
                "no-macro plan has invalid core predecessor sequence"));
        cases.add(new PlanMutation("lineage wrong source mode", p -> ((ObjectNode) p.path("core_anchor_lineage")).put("source_mode", "other"),
                "staged plan lost its immutable core anchor lineage"));
        cases.add(new PlanMutation("wrong sequence", p -> p.put("sequence", 2), "no-macro plan has invalid core predecessor sequence"));
        cases.add(new PlanMutation("missing core replay lineage", p -> p.remove("predecessor_replay_sha256"),
                "no-macro plan has invalid core predecessor sequence"));
        cases.add(new PlanMutation("unsupported source mode", p -> p.put("source_mode", "LIVE"),
                "staged plan lost its immutable core anchor lineage"));
        cases.add(new PlanMutation("anchor initial intent hash", p -> ((ObjectNode) p.path("decision_anchors").get(0))
                .put("initial_intent_sha256", "f".repeat(64)),
                "staged anchor has a duplicate pair or malformed initial intent/setup seed"));
        cases.add(new PlanMutation("anchor intent pair", p -> ((ObjectNode) p.path("decision_anchors").get(0)
                .path("initial_intent")).put("pair_id", "different"),
                "staged anchor has a duplicate pair or malformed initial intent/setup seed"));
        cases.add(new PlanMutation("validly rehashed seed mutation", p -> resealNestedSeed(p),
                "staged no-macro plan differs from its reopened core replay/evidence/inventory"));
        return List.copyOf(cases);
    }

    private static void resealNestedAnchorIfRequested(ObjectNode plan, String name) {
        if (name.startsWith("anchor initial intent hash") || name.startsWith("anchor intent pair")
                || "validly rehashed seed mutation".equals(name)) {
            ObjectNode anchor = (ObjectNode) plan.path("decision_anchors").get(0);
            ObjectNode intent = (ObjectNode) anchor.path("initial_intent");
            if ("anchor intent pair".equals(name)) rehash(intent);
            if ("validly rehashed seed mutation".equals(name)) rehash(intent);
            if (name.startsWith("anchor initial intent hash")) {
                anchor.put("initial_intent_sha256", "f".repeat(64));
            } else {
                anchor.put("initial_intent_sha256", JsonHashes.canonicalSha256(intent));
            }
            plan.put("anchor_inventory_sha256", JsonHashes.canonicalSha256(plan.path("decision_anchors")));
            ((ObjectNode) plan.path("candidate")).put("initial_pair_inventory_sha256", plan.path("anchor_inventory_sha256").asText());
        }
    }

    private static void resealNestedSeed(ObjectNode plan) {
        ObjectNode anchor = (ObjectNode) plan.path("decision_anchors").get(0);
        ObjectNode intent = (ObjectNode) anchor.path("initial_intent");
        ((ObjectNode) intent.path("setup_seed")).put("setup_id", "changed-seed");
        rehash((ObjectNode) intent.path("setup_seed"));
        rehash(intent);
    }

    private static CoreFixture rebindReplay(CoreFixture source, Consumer<ObjectNode> mutation) {
        ObjectNode replay = source.replay().deepCopy();
        mutation.accept(replay);
        rehash(replay);
        ObjectNode evidence = source.evidence().deepCopy().put("replay_result_sha256", replay.path("content_sha256").asText());
        rehash(evidence);
        return new CoreFixture(replay, evidence, source.inventory());
    }

    private static CoreFixture rebindEvidence(CoreFixture source, Consumer<ObjectNode> mutation) {
        ObjectNode evidence = source.evidence().deepCopy();
        mutation.accept(evidence);
        rehash(evidence);
        return new CoreFixture(source.replay(), evidence, source.inventory());
    }

    private static CoreFixture rebindInventory(CoreFixture source, ObjectNode inventory) {
        ObjectNode replay = source.replay().deepCopy();
        ((ObjectNode) replay.path("freeze")).put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        rehash(replay);
        ObjectNode evidence = source.evidence().deepCopy().put("replay_result_sha256", replay.path("content_sha256").asText());
        rehash(evidence);
        return new CoreFixture(replay, evidence, inventory);
    }

    private static CoreFixture core(String sourceMode, String evidenceStatus) {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA)
                .put("version", 1).put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode candidates = inventory.putArray("current_candidates");
        candidates.addObject().put("candidate_id", ROUTED).put("variant", VARIANT);
        candidates.addObject().put("candidate_id", CONTINUE).put("variant", "ALWAYS_CONTINUATION_CONTROL");
        candidates.addObject().put("candidate_id", REVERSE).put("variant", "ALWAYS_REVERSAL_CONTROL");
        candidates.addObject().put("candidate_id", DIAGNOSTIC).put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        rehash(inventory);

        ObjectNode seed = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-anchor-setup-seed/1")
                .put("version", 1).put("setup_id", "setup-1");
        rehash(seed);
        ObjectNode intent = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-confirmed-entry-intent/1").put("version", 1)
                .put("intent_id", "intent-1").put("setup_id", "setup-1").put("pair_id", "pair-1")
                .put("asset", "BTC").put("variant", VARIANT).put("branch", "CONTINUATION")
                .put("direction", "SHORT").put("stage", 1).put("decision_time", DECISION.toString())
                .put("diagnostic_only", false);
        intent.set("setup_seed", seed); rehash(intent);
        String intentHash = JsonHashes.canonicalSha256(intent);
        ObjectNode opportunity = JsonHashes.mapper().createObjectNode().put("candidate_id", ROUTED)
                .put("variant", VARIANT).put("stage", 1).put("pair_id", "pair-1").put("asset", "BTC")
                .put("decision_time", DECISION.toString()).put("setup_id", "setup-1")
                .put("initial_intent_sha256", intentHash);
        opportunity.set("initial_intent", intent.deepCopy());
        ObjectNode audit = JsonHashes.mapper().createObjectNode().put("status", "CONFIRMED_STAGE_ONE_INTENT")
                .put("candidate_id", ROUTED).put("intent_id", "intent-1").put("setup_id", "setup-1")
                .put("pair_id", "pair-1").put("asset", "BTC").put("decision_time", DECISION.toString())
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("initial_intent_sha256", intentHash);
        audit.set("initial_intent", intent.deepCopy());
        ObjectNode replay = JsonHashes.mapper().createObjectNode().put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA)
                .put("version", 1).put("source_mode", sourceMode);
        replay.putObject("freeze").put("source_mode", sourceMode)
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        replay.putArray("opportunities").add(opportunity); replay.putArray("route_audit").add(audit);
        rehash(replay);
        ObjectNode evidence = JsonHashes.mapper().createObjectNode().put("schema", LiquidationV2ReplayEvidenceV1.EVIDENCE_SCHEMA)
                .put("version", 1).put("status", evidenceStatus).put("source_mode", sourceMode)
                .put("replay_result_sha256", replay.path("content_sha256").asText())
                .put("promotion_permitted", false).put("trade_authorization_permitted", false);
        evidence.putObject("acceptance_gates").put("all_core_gates", "DEVELOPMENT".equals(evidenceStatus));
        evidence.putArray("advancement_blockers");
        rehash(evidence);
        return new CoreFixture(replay, evidence, inventory);
    }

    private static void assertFailure(org.junit.jupiter.api.function.Executable action, String message) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action);
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
    }

    private static void rehash(ObjectNode node) { node.remove("content_sha256"); node.put("content_sha256", JsonHashes.ownHash(node)); }

    private record CoreFixture(ObjectNode replay, ObjectNode evidence, ObjectNode inventory) {}
    private record PlanMutation(String name, Consumer<ObjectNode> change, String message) {}
}

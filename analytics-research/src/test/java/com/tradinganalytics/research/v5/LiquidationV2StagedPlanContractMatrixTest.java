package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Ensures staged-plan validators reopen every frozen policy and anchor field. */
class LiquidationV2StagedPlanContractMatrixTest {
    private static final String ROUTED = "liquidation-v2-core-routed-one-entry";
    private static final String CONTINUE = "liquidation-v2-core-always-continuation-one-entry";
    private static final String REVERSE = "liquidation-v2-core-always-reversal-one-entry";
    private static final String DIAGNOSTIC = "liquidation-v2-price-oi-only-event-diagnostic";
    private static final String SOURCE = "SYNTHETIC_DEVELOPMENT_ONLY";
    private static final Instant DECISION = Instant.parse("2024-01-03T12:00:00Z");

    @Test
    void acceptsFrozenPlanThenRejectsEveryTopLevelAndCandidateContractMutation() {
        Fixture fixture = fixture();
        assertDoesNotThrow(() -> LiquidationV2StagedCandidateInventoryV1.validateNoMacroPlan(
                fixture.plan(), fixture.replay(), fixture.evidence(), fixture.inventory()));
        String inventoryError = "staged inventory hash, family, mode or anchor list is invalid";
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                p -> p.put("schema", "legacy-inventory/1"), p -> p.put("version", 2),
                p -> p.put("strategy_family", "other-family"), p -> p.put("mode_id", "UNFROZEN_MODE"),
                p -> p.put("anchor_inventory_sha256", "f".repeat(64)), p -> p.put("decision_anchor_count", 2),
                p -> p.set("decision_anchors", JsonHashes.mapper().getNodeFactory().textNode("anchors")),
                p -> p.putNull("candidate"))) {
            rejectPlan(fixture, mutation, inventoryError);
        }

        String policyError = "staged plan differs from the fixed mode, risk, allocation or selection contract";
        List<Consumer<ObjectNode>> candidateMutations = List.of(
                p -> p.put("candidate_id", "other-candidate"),
                p -> ((ObjectNode) p.path("candidate")).put("candidate_id", "other-candidate"),
                p -> p.put("macro_gate_policy", "REQUIRE_MACRO_CONFIRMATION"),
                p -> ((ObjectNode) p.path("candidate")).put("macro_gate_policy", "REQUIRE_MACRO_CONFIRMATION"),
                p -> ((ObjectNode) p.path("candidate")).put("initial_pair_inventory_sha256", "f".repeat(64)),
                p -> p.put("staged_reference_r_basis", "PER_TRANCHE_R"),
                p -> ((ObjectNode) p.path("candidate")).put("reference_risk_basis", "PER_TRANCHE_R"),
                p -> p.put("staged_position_risk_fraction", 0.04),
                p -> p.put("stage_risk_fraction_sum", 0.04),
                p -> ((ObjectNode) p.path("candidate")).put("full_position_reference_risk_fraction", 0.04),
                p -> ((ObjectNode) p.path("candidate")).put("stage_risk_fraction_sum", 0.04),
                p -> p.set("stage_risk_fractions", JsonHashes.mapper().createArrayNode().add(0.01)),
                p -> ((ObjectNode) p.path("candidate")).putArray("stage_risk_fractions").add(0.01),
                p -> p.put("outcome_selection_permitted", true), p -> p.put("promotion_permitted", true));
        for (Consumer<ObjectNode> mutation : candidateMutations) rejectPlan(fixture, mutation, policyError);
    }

    @Test
    void coreLineageSequenceAndSourceModeAreIndividuallyBound() {
        Fixture fixture = fixture();
        String lineageError = "staged plan lost its immutable core anchor lineage";
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                p -> p.remove("core_anchor_lineage"),
                p -> ((ObjectNode) p.path("core_anchor_lineage")).put("core_replay_sha256", "invalid"),
                p -> ((ObjectNode) p.path("core_anchor_lineage")).put("core_evidence_sha256", "invalid"),
                p -> ((ObjectNode) p.path("core_anchor_lineage")).put("core_candidate_inventory_sha256", "invalid"),
                p -> ((ObjectNode) p.path("core_anchor_lineage")).put("source_mode", "OTHER"))) {
            rejectPlan(fixture, mutation, lineageError);
        }

        String predecessorError = "no-macro plan has invalid core predecessor sequence";
        rejectPlan(fixture, p -> p.put("sequence", 2), predecessorError);
        rejectPlan(fixture, p -> p.put("predecessor_plan_sha256", "a".repeat(64)), predecessorError);
        rejectPlan(fixture, p -> p.put("predecessor_replay_sha256", "invalid"), predecessorError);
        rejectPlan(fixture, p -> p.put("predecessor_replay_sha256", "a".repeat(64)), predecessorError);

        Fixture changedSource = fixture();
        rejectPlan(changedSource, p -> {
            p.put("source_mode", "UNDECLARED_SOURCE");
            ((ObjectNode) p.path("core_anchor_lineage")).put("source_mode", "UNDECLARED_SOURCE");
        }, "staged plan source mode is outside the explicitly disclosed development modes");
    }

    @Test
    void anchorFieldsAndSetupSeedHashesCannotBeDetachedFromTheFrozenIntent() {
        Fixture fixture = fixture();
        String anchorError = "staged anchor has a duplicate pair or malformed initial intent/setup seed";
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                p -> ((ObjectNode) p.path("decision_anchors").get(0)).put("pair_id", "other-pair"),
                p -> ((ObjectNode) p.path("decision_anchors").get(0)).putNull("initial_intent"),
                p -> ((ObjectNode) p.path("decision_anchors").get(0)).put("initial_intent_sha256", "0".repeat(64)),
                p -> ((ObjectNode) p.path("decision_anchors").get(0).path("initial_intent")).put("asset", "ETH"),
                p -> ((ObjectNode) p.path("decision_anchors").get(0).path("initial_intent")).put("variant", "OTHER"),
                p -> ((ObjectNode) p.path("decision_anchors").get(0).path("initial_intent")).put("stage", 2),
                p -> ((ObjectNode) p.path("decision_anchors").get(0).path("initial_intent")).put("diagnostic_only", true),
                p -> ((ObjectNode) p.path("decision_anchors").get(0).path("initial_intent")).put("setup_id", "other-setup"),
                p -> ((ObjectNode) p.path("decision_anchors").get(0).path("initial_intent")).put("intent_id", "other-intent"),
                p -> ((ObjectNode) p.path("decision_anchors").get(0).path("initial_intent")).put("decision_time", DECISION.plusSeconds(1).toString()),
                p -> ((ObjectNode) p.path("decision_anchors").get(0).path("initial_intent")).remove("setup_seed"))) {
            rejectAnchorPlan(fixture, mutation, anchorError, false);
        }

        Fixture staleSeed = fixture();
        rejectAnchorPlan(staleSeed, p -> ((ObjectNode) p.path("decision_anchors").get(0)
                .path("initial_intent").path("setup_seed")).put("event_boundary_high", 123),
                "staged anchor has a duplicate pair or malformed initial intent/setup seed", true);

        Fixture duplicatePair = fixture();
        rejectAnchorPlan(duplicatePair, p -> ((ArrayNode) p.path("decision_anchors")).add(
                p.path("decision_anchors").get(0).deepCopy()), anchorError, false);
    }

    private static void rejectAnchorPlan(Fixture fixture, Consumer<ObjectNode> mutation, String message,
            boolean preserveSeedHashFailure) {
        ObjectNode plan = fixture.plan().deepCopy();
        ObjectNode anchor = (ObjectNode) plan.path("decision_anchors").get(0);
        ObjectNode intent = anchor.path("initial_intent").isObject() ? (ObjectNode) anchor.path("initial_intent") : null;
        mutation.accept(plan);
        if (intent != null && intent.isObject()) {
            if (!preserveSeedHashFailure && intent.path("setup_seed").isObject()) {
                reseal((ObjectNode) intent.path("setup_seed"));
            }
            reseal(intent);
            if (!"0".repeat(64).equals(anchor.path("initial_intent_sha256").asText())) {
                anchor.put("initial_intent_sha256", JsonHashes.canonicalSha256(intent));
            }
        }
        resealAnchorBindings(plan);
        reject(fixture, plan, message);
    }

    private static void rejectPlan(Fixture fixture, Consumer<ObjectNode> mutation, String message) {
        ObjectNode plan = fixture.plan().deepCopy();
        mutation.accept(plan);
        reseal(plan);
        reject(fixture, plan, message);
    }

    private static void reject(Fixture fixture, ObjectNode plan, String message) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2StagedCandidateInventoryV1.validateNoMacroPlan(
                        plan, fixture.replay(), fixture.evidence(), fixture.inventory()));
        assertTrue(error.getMessage().contains(message),
                () -> "expected '" + message + "', got '" + error.getMessage() + "'");
    }

    private static void resealAnchorBindings(ObjectNode plan) {
        ArrayNode anchors = (ArrayNode) plan.path("decision_anchors");
        plan.put("decision_anchor_count", anchors.size());
        String digest = JsonHashes.canonicalSha256(anchors);
        plan.put("anchor_inventory_sha256", digest);
        ((ObjectNode) plan.path("candidate")).put("initial_pair_inventory_sha256", digest);
        reseal(plan);
    }

    private static Fixture fixture() {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA)
                .put("version", 1).put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode candidates = inventory.putArray("current_candidates");
        candidates.addObject().put("candidate_id", ROUTED).put("variant", "ROUTED_REVERSAL_CONTINUATION");
        candidates.addObject().put("candidate_id", CONTINUE).put("variant", "ALWAYS_CONTINUATION_CONTROL");
        candidates.addObject().put("candidate_id", REVERSE).put("variant", "ALWAYS_REVERSAL_CONTROL");
        candidates.addObject().put("candidate_id", DIAGNOSTIC).put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        reseal(inventory);

        ObjectNode seed = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-anchor-setup-seed/1").put("version", 1)
                .put("setup_id", "setup-1").put("event_boundary_high", 110.0);
        reseal(seed);
        ObjectNode intent = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-confirmed-entry-intent/1").put("version", 1)
                .put("intent_id", "intent-1").put("setup_id", "setup-1").put("pair_id", "pair-1")
                .put("asset", "BTC").put("variant", "ROUTED_REVERSAL_CONTINUATION")
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("stage", 1)
                .put("decision_time", DECISION.toString()).put("diagnostic_only", false);
        intent.set("setup_seed", seed);
        reseal(intent);
        String intentSha = JsonHashes.canonicalSha256(intent);
        ObjectNode opportunity = JsonHashes.mapper().createObjectNode()
                .put("candidate_id", ROUTED).put("variant", "ROUTED_REVERSAL_CONTINUATION").put("stage", 1)
                .put("pair_id", "pair-1").put("asset", "BTC").put("decision_time", DECISION.toString())
                .put("setup_id", "setup-1").put("initial_intent_sha256", intentSha);
        opportunity.set("initial_intent", intent.deepCopy());
        ObjectNode signal = JsonHashes.mapper().createObjectNode()
                .put("status", "CONFIRMED_STAGE_ONE_INTENT").put("candidate_id", ROUTED)
                .put("intent_id", "intent-1").put("setup_id", "setup-1").put("pair_id", "pair-1")
                .put("asset", "BTC").put("decision_time", DECISION.toString())
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("initial_intent_sha256", intentSha);
        signal.set("initial_intent", intent.deepCopy());
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1).put("source_mode", SOURCE);
        replay.putObject("freeze").put("source_mode", SOURCE)
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        replay.putArray("opportunities").add(opportunity);
        replay.putArray("route_audit").add(signal);
        reseal(replay);
        ObjectNode evidence = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EVIDENCE_SCHEMA).put("version", 1)
                .put("status", "BLOCKED").put("source_mode", SOURCE)
                .put("replay_result_sha256", replay.path("content_sha256").asText())
                .put("promotion_permitted", false).put("trade_authorization_permitted", false);
        evidence.putArray("advancement_blockers").add("SYNTHETIC_FIXTURE_ONLY");
        reseal(evidence);
        ObjectNode plan = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(replay, evidence, inventory);
        return new Fixture(plan, replay, evidence, inventory);
    }

    private static void reseal(ObjectNode value) {
        value.remove("content_sha256");
        value.put("content_sha256", JsonHashes.ownHash(value));
    }

    private record Fixture(ObjectNode plan, ObjectNode replay, ObjectNode evidence, ObjectNode inventory) { }
}

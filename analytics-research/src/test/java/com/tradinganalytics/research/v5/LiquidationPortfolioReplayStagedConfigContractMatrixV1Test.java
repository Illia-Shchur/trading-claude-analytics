package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Public run-boundary validation for the immutable staged configuration before setup or outcome work. */
class LiquidationPortfolioReplayStagedConfigContractMatrixV1Test {
    private static final String ROUTED = "liquidation-v2-core-routed-one-entry";
    private static final String CONTINUE = "liquidation-v2-core-always-continuation-one-entry";
    private static final String REVERSE = "liquidation-v2-core-always-reversal-one-entry";
    private static final String DIAGNOSTIC = "liquidation-v2-price-oi-only-event-diagnostic";

    @Test
    void bothFrozenModesAcceptValidConfigurationAndRejectEnvelopePolicyAndAnchorContracts() {
        ObjectNode accepted = fixture();
        assertConfigParsed(accepted);
        ObjectNode macro = accepted.deepCopy().put("mode_id", LiquidationV2StagedCandidateInventoryV1.THREE_STAGE_MACRO)
                .put("macro_gate_policy", "REQUIRE_MACRO_CONFIRMATION");
        reseal(macro);
        assertConfigParsed(macro);

        ObjectNode badSchema = accepted.deepCopy().put("schema", "liquidation-v2-staged-candidate-plan/99");
        reseal(badSchema);
        assertConfigRejected(badSchema, "staged candidate plan schema or self-hash is invalid");

        ObjectNode staleOwnHash = accepted.deepCopy().put("content_sha256", "0".repeat(64));
        assertConfigRejected(staleOwnHash, "staged candidate plan schema or self-hash is invalid");

        ObjectNode missingMode = accepted.deepCopy();
        missingMode.remove("mode_id");
        reseal(missingMode);
        assertConfigRejected(missingMode, "mode_id is required");

        ObjectNode missingCandidate = accepted.deepCopy();
        missingCandidate.remove("candidate_id");
        reseal(missingCandidate);
        assertConfigRejected(missingCandidate, "candidate_id is required");

        ObjectNode invalidPolicy = accepted.deepCopy().put("macro_gate_policy", "UNFROZEN_POLICY");
        reseal(invalidPolicy);
        assertConfigRejected(invalidPolicy, "staged candidate macro policy is invalid");

        ObjectNode mismatchedPolicy = accepted.deepCopy().put("macro_gate_policy", "REQUIRE_MACRO_CONFIRMATION");
        reseal(mismatchedPolicy);
        assertConfigRejected(mismatchedPolicy, "staged candidate mode and macro policy disagree");

        ObjectNode unknownMode = accepted.deepCopy().put("mode_id", "FUTURE_UNFROZEN_MODE");
        reseal(unknownMode);
        assertConfigRejected(unknownMode, "staged candidate mode and macro policy disagree");

        ObjectNode absentAnchors = accepted.deepCopy().put("decision_anchors", "not-an-array");
        reseal(absentAnchors);
        assertConfigRejected(absentAnchors, "staged candidate plan must include the complete decision_anchors array");

        ObjectNode badInventoryHash = accepted.deepCopy().put("anchor_inventory_sha256", "0".repeat(64));
        reseal(badInventoryHash);
        assertConfigRejected(badInventoryHash, "staged decision anchor inventory hash is invalid");
    }

    @Test
    void anchorRowsRequireBoundStageOneIntentsSetupSeedsAndChronologicalOrdering() {
        ObjectNode accepted = fixture();

        ObjectNode nonObject = accepted.deepCopy();
        ((ArrayNode) nonObject.path("decision_anchors")).set(0, JsonHashes.mapper().getNodeFactory().textNode("anchor"));
        resealPlanInventory(nonObject);
        assertConfigRejected(nonObject, "staged decision anchor must be an object");

        ObjectNode missingIntent = accepted.deepCopy();
        ((ObjectNode) missingIntent.path("decision_anchors").get(0)).remove("initial_intent");
        resealPlanInventory(missingIntent);
        assertConfigRejected(missingIntent, "initial_intent must be a JSON object");

        ObjectNode badIntentSelfHash = accepted.deepCopy();
        ((ObjectNode) badIntentSelfHash.path("decision_anchors").get(0).path("initial_intent")).put("caller_field", true);
        resealPlanInventory(badIntentSelfHash);
        assertConfigRejected(badIntentSelfHash, "staged decision anchor intent or setup seed is invalid");

        ObjectNode badIntentReference = accepted.deepCopy();
        ((ObjectNode) badIntentReference.path("decision_anchors").get(0)).put("initial_intent_sha256", "f".repeat(64));
        resealPlanInventory(badIntentReference);
        assertConfigRejected(badIntentReference, "staged decision anchor intent or setup seed is invalid");

        ObjectNode wrongStage = accepted.deepCopy();
        ObjectNode wrongStageAnchor = (ObjectNode) wrongStage.path("decision_anchors").get(0);
        ObjectNode wrongStageIntent = (ObjectNode) wrongStageAnchor.path("initial_intent");
        wrongStageIntent.put("stage", 2);
        reseal(wrongStageIntent);
        rebindIntent(wrongStageAnchor, wrongStageIntent);
        resealPlanInventory(wrongStage);
        assertConfigRejected(wrongStage, "staged decision anchor intent or setup seed is invalid");

        for (String field : List.of("pair_id", "asset", "decision_time")) {
            ObjectNode mismatch = accepted.deepCopy();
            ObjectNode anchor = (ObjectNode) mismatch.path("decision_anchors").get(0);
            anchor.put(field, "detached-anchor-identity");
            resealPlanInventory(mismatch);
            assertConfigRejected(mismatch, "staged decision anchor intent or setup seed is invalid");
        }

        ObjectNode missingSeed = accepted.deepCopy();
        ObjectNode missingSeedAnchor = (ObjectNode) missingSeed.path("decision_anchors").get(0);
        ObjectNode missingSeedIntent = (ObjectNode) missingSeedAnchor.path("initial_intent");
        missingSeedIntent.remove("setup_seed");
        reseal(missingSeedIntent);
        rebindIntent(missingSeedAnchor, missingSeedIntent);
        resealPlanInventory(missingSeed);
        assertConfigRejected(missingSeed, "staged decision anchor intent or setup seed is invalid");

        ObjectNode reversed = accepted.deepCopy();
        ArrayNode anchors = (ArrayNode) reversed.path("decision_anchors");
        JsonNode first = anchors.get(0).deepCopy(), second = anchors.get(1).deepCopy();
        anchors.set(0, second);
        anchors.set(1, first);
        resealPlanInventory(reversed);
        assertConfigRejected(reversed, "staged decision anchors are not in frozen chronological asset order");
    }

    private static void assertConfigParsed(ObjectNode plan) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> invoke(plan));
        assertTrue(failure.getMessage().contains("freeze schema or outer content hash is invalid"),
                "valid staged configuration must parse before the deliberately minimal freeze is rejected: " + failure.getMessage());
    }

    private static void assertConfigRejected(ObjectNode plan, String expected) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> invoke(plan));
        assertTrue(failure.getMessage().contains(expected),
                "expected staged configuration guard '" + expected + "', got '" + failure.getMessage() + "'");
    }

    private static void invoke(ObjectNode plan) {
        ObjectNode options = JsonHashes.mapper().createObjectNode();
        options.set("freeze", JsonHashes.mapper().createObjectNode());
        options.set("staged_plan", plan.deepCopy());
        LiquidationPortfolioReplayV1.runResumable(options);
    }

    private static ObjectNode fixture() {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA)
                .put("version", 1).put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        inventory.putArray("current_candidates")
                .addObject().put("candidate_id", ROUTED).put("variant", "ROUTED_REVERSAL_CONTINUATION");
        ((ArrayNode) inventory.path("current_candidates")).addObject().put("candidate_id", CONTINUE)
                .put("variant", "ALWAYS_CONTINUATION_CONTROL");
        ((ArrayNode) inventory.path("current_candidates")).addObject().put("candidate_id", REVERSE)
                .put("variant", "ALWAYS_REVERSAL_CONTROL");
        ((ArrayNode) inventory.path("current_candidates")).addObject().put("candidate_id", DIAGNOSTIC)
                .put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        reseal(inventory);

        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY");
        replay.putObject("freeze").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        ArrayNode opportunities = replay.putArray("opportunities"), audits = replay.putArray("route_audit");
        addAnchor(opportunities, audits, "pair-late", "intent-late", Instant.parse("2024-01-03T12:00:00Z"));
        addAnchor(opportunities, audits, "pair-early", "intent-early", Instant.parse("2024-01-04T12:00:00Z"));
        reseal(replay);
        ObjectNode evidence = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EVIDENCE_SCHEMA).put("version", 1)
                .put("status", "BLOCKED").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("replay_result_sha256", replay.path("content_sha256").asText())
                .put("promotion_permitted", false).put("trade_authorization_permitted", false);
        evidence.putArray("advancement_blockers").add("SYNTHETIC_FIXTURE_ONLY");
        reseal(evidence);
        return LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(replay, evidence, inventory);
    }

    private static void addAnchor(ArrayNode opportunities, ArrayNode audits, String pair, String intentId, Instant time) {
        ObjectNode seed = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-anchor-setup-seed/1")
                .put("version", 1).put("setup_id", "setup-" + intentId).put("event_boundary_high", 110.0);
        reseal(seed);
        ObjectNode intent = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-confirmed-entry-intent/1").put("version", 1)
                .put("intent_id", intentId).put("setup_id", "setup-" + intentId).put("pair_id", pair)
                .put("asset", "BTC").put("variant", "ROUTED_REVERSAL_CONTINUATION").put("branch", "CONTINUATION")
                .put("direction", "SHORT").put("stage", 1).put("decision_time", time.toString()).put("diagnostic_only", false);
        intent.set("setup_seed", seed);
        reseal(intent);
        String intentHash = JsonHashes.canonicalSha256(intent);
        ObjectNode opportunity = opportunities.addObject().put("candidate_id", ROUTED)
                .put("variant", "ROUTED_REVERSAL_CONTINUATION").put("stage", 1).put("pair_id", pair)
                .put("asset", "BTC").put("decision_time", time.toString()).put("setup_id", "setup-" + intentId)
                .put("initial_intent_sha256", intentHash);
        opportunity.set("initial_intent", intent.deepCopy());
        ObjectNode signal = audits.addObject().put("status", "CONFIRMED_STAGE_ONE_INTENT").put("candidate_id", ROUTED)
                .put("intent_id", intentId).put("setup_id", "setup-" + intentId).put("pair_id", pair)
                .put("asset", "BTC").put("decision_time", time.toString()).put("branch", "CONTINUATION")
                .put("direction", "SHORT").put("initial_intent_sha256", intentHash);
        signal.set("initial_intent", intent.deepCopy());
    }

    private static void rebindIntent(ObjectNode anchor, ObjectNode intent) {
        anchor.put("initial_intent_sha256", JsonHashes.canonicalSha256(intent));
    }

    private static void resealPlanInventory(ObjectNode plan) {
        plan.put("anchor_inventory_sha256", JsonHashes.canonicalSha256(plan.path("decision_anchors")));
        reseal(plan);
    }

    private static void reseal(ObjectNode value) {
        value.remove("content_sha256");
        value.put("content_sha256", JsonHashes.ownHash(value));
    }
}

package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import org.junit.jupiter.api.Test;

/** Confirms a control-only core run creates no routed stage-one anchors. */
class LiquidationV2CoreNoSignalAnchorInventoryTest {
    private static final String ROUTED = "liquidation-v2-core-routed-one-entry";
    private static final String CONTINUATION = "liquidation-v2-core-always-continuation-one-entry";
    private static final String REVERSAL = "liquidation-v2-core-always-reversal-one-entry";
    private static final String DIAGNOSTIC = "liquidation-v2-price-oi-only-event-diagnostic";

    @Test
    void controlOnlyOpportunityRowsDoNotBecomeStagedDecisionAnchors() {
        ObjectNode inventory = inventory();
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY");
        replay.putObject("freeze").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        ArrayNode opportunities = replay.putArray("opportunities");
        opportunities.addObject().put("candidate_id", CONTINUATION).put("variant", "ALWAYS_CONTINUATION_CONTROL");
        opportunities.addObject().put("candidate_id", REVERSAL).put("variant", "ALWAYS_REVERSAL_CONTROL");
        opportunities.addObject().put("candidate_id", DIAGNOSTIC).put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        replay.putArray("route_audit");
        rehash(replay);

        ObjectNode evidence = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EVIDENCE_SCHEMA).put("version", 1)
                .put("status", "BLOCKED").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("replay_result_sha256", replay.path("content_sha256").asText())
                .put("promotion_permitted", false).put("trade_authorization_permitted", false);
        evidence.putArray("advancement_blockers").add("SYNTHETIC_FIXTURE_ONLY");
        rehash(evidence);

        ObjectNode plan = assertDoesNotThrow(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                replay, evidence, inventory));
        assertEquals(0, plan.path("decision_anchor_count").asInt());
        assertTrue(plan.path("decision_anchors").isArray() && plan.path("decision_anchors").isEmpty());
        assertFalse(plan.path("promotion_permitted").asBoolean(true));
        assertFalse(plan.path("outcome_selection_permitted").asBoolean(true));
        assertTrue(LiquidationV2StagedCandidateInventoryV1.validateNoMacroPlan(
                plan, replay, evidence, inventory));
    }

    private static ObjectNode inventory() {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA).put("version", 1)
                .put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode rows = inventory.putArray("current_candidates");
        rows.addObject().put("candidate_id", ROUTED).put("variant", "ROUTED_REVERSAL_CONTINUATION");
        rows.addObject().put("candidate_id", CONTINUATION).put("variant", "ALWAYS_CONTINUATION_CONTROL");
        rows.addObject().put("candidate_id", REVERSAL).put("variant", "ALWAYS_REVERSAL_CONTROL");
        rows.addObject().put("candidate_id", DIAGNOSTIC).put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        rehash(inventory);
        return inventory;
    }

    private static void rehash(ObjectNode node) {
        node.remove("content_sha256");
        node.put("content_sha256", JsonHashes.ownHash(node));
    }
}

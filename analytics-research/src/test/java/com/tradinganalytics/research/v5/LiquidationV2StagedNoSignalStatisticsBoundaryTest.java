package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Ensures an empty routed decision inventory remains blocked by statistical gates. */
class LiquidationV2StagedNoSignalStatisticsBoundaryTest {
    private static final String ROUTED = "liquidation-v2-core-routed-one-entry";
    private static final String CONTINUATION = "liquidation-v2-core-always-continuation-one-entry";
    private static final String REVERSAL = "liquidation-v2-core-always-reversal-one-entry";
    private static final String DIAGNOSTIC = "liquidation-v2-price-oi-only-event-diagnostic";

    @Test
    void emptyRoutedInventoryCannotPassByVacuousResolution() throws Exception {
        ObjectNode inventory = inventory();
        ObjectNode core = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY");
        core.putObject("freeze").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        core.putArray("opportunities")
                .addObject().put("candidate_id", CONTINUATION).put("variant", "ALWAYS_CONTINUATION_CONTROL");
        core.withArray("opportunities").addObject().put("candidate_id", REVERSAL)
                .put("variant", "ALWAYS_REVERSAL_CONTROL");
        core.withArray("opportunities").addObject().put("candidate_id", DIAGNOSTIC)
                .put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        core.putArray("route_audit"); rehash(core);

        ObjectNode coreEvidence = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EVIDENCE_SCHEMA).put("version", 1)
                .put("status", "BLOCKED").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("replay_result_sha256", core.path("content_sha256").asText())
                .put("promotion_permitted", false).put("trade_authorization_permitted", false);
        coreEvidence.putArray("advancement_blockers").add("SYNTHETIC_FIXTURE_ONLY"); rehash(coreEvidence);
        ObjectNode plan = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(core, coreEvidence, inventory);
        ObjectNode physical = JsonHashes.mapper().createObjectNode()
                .set("profile", LiquidationDailyStressProfileV1.frozenContract());
        physical.set("precommit", readPrecommit());
        ObjectNode staged = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", plan.path("source_mode").asText())
                .put("candidate_id", plan.path("candidate_id").asText())
                .put("mode_id", plan.path("mode_id").asText())
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("anchor_inventory_sha256", plan.path("anchor_inventory_sha256").asText());
        staged.set("staged_plan", plan.deepCopy()); staged.putArray("opportunities");
        ObjectNode receipt = staged.putObject("pre_outcome_exposure_attempts")
                .put("schema", "liquidation-v2-pre-outcome-exposure-refs/1").put("attempt_count", 0);
        receipt.putArray("attempts"); rehash(receipt); rehash(staged);

        LiquidationV2StagedStatisticsV1.Evaluation result = LiquidationV2StagedStatisticsV1.recompute(
                physical, plan, core, staged, null, null, null);
        assertEquals(0, result.statistics().path("retained_anchor_count").asInt());
        assertEquals(0, result.statistics().path("completed_position_count").asInt());
        assertTrue(result.gates().path("all_anchor_outcomes_resolved").asBoolean());
        assertFalse(result.gates().path("minimum_accepted_trades").asBoolean());
        assertFalse(result.gates().path("bootstrap_p20_expectancy_r_positive").asBoolean());
        assertFalse(result.gates().path("full_position_5_percent_r_normalization").asBoolean());
        assertTrue(result.blockers().toString().contains("MINIMUM_60_COMPLETED_POSITIONS_NOT_MET"));
        assertTrue(result.blockers().toString().contains("NO_COMPLETED_STAGED_POSITIONS_FOR_DRAWDOWN_OR_COST_STATISTICS"));
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
        rehash(inventory); return inventory;
    }

    private static ObjectNode readPrecommit() throws Exception {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) {
            cursor = cursor.getParent();
        }
        if (cursor == null) throw new IllegalStateException("frozen precommit missing");
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readString(
                cursor.resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json")));
    }

    private static void rehash(ObjectNode node) {
        node.remove("content_sha256"); node.put("content_sha256", JsonHashes.ownHash(node));
    }
}

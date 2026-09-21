package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** Exercises core anchor extraction against valid, runner-shaped positive fixtures. */
class LiquidationV2CoreAnchorMutationMatrixTest {
    private static final String ROUTED = "liquidation-v2-core-routed-one-entry";
    private static final String CONTINUE = "liquidation-v2-core-always-continuation-one-entry";
    private static final String REVERSE = "liquidation-v2-core-always-reversal-one-entry";
    private static final String DIAGNOSTIC = "liquidation-v2-price-oi-only-event-diagnostic";

    @Test
    void completeCoreDecisionInventoryIsSortedAndNestedRealDevelopmentGatesAreReadRecursively() {
        Fixture fixture = fixture("PROXY_DISCLOSED_DEVELOPMENT_ONLY", true);
        ObjectNode plan = assertDoesNotThrow(() -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                fixture.replay(), fixture.evidence(), fixture.inventory()));
        assertEquals("pair-late", plan.path("decision_anchors").get(0).path("pair_id").asText());
        assertEquals("pair-early", plan.path("decision_anchors").get(1).path("pair_id").asText());
        assertEquals(2, plan.path("decision_anchor_count").asInt());
    }

    @Test
    void sourceShapeAndStageOneIdentityMutationsRejectAtTheSpecificCoreBoundary() {
        assertCoreFailure(row -> row.put("stage", 2), "not explicitly a stage-one routed decision");
        assertCoreFailure(row -> row.put("variant", "ALWAYS_CONTINUATION_CONTROL"),
                "not explicitly a stage-one routed decision");
        assertCoreFailure(row -> row.remove("initial_intent"), "duplicate pair or malformed full initial intent");
        assertCoreFailure(row -> row.put("initial_intent_sha256", "0".repeat(64)),
                "does not bind its full initial intent hash");
        assertCoreFailure(row -> row.put("setup_id", "other-setup"),
                "identity or bound setup seed differs from its initial intent");
        assertCoreFailure(row -> row.put("asset", "ETH"),
                "identity or bound setup seed differs from its initial intent");
        assertIntentFailure(intent -> intent.put("diagnostic_only", true),
                "identity or bound setup seed differs from its initial intent");
        assertIntentFailure(intent -> intent.remove("setup_seed"),
                "identity or bound setup seed differs from its initial intent");
    }

    @Test
    void setupSeedAndAuditMustMatchOneExactConfirmedSignal() {
        assertSeedFailure(seed -> seed.put("event_boundary_high", 123.0),
                "core initial intent setup seed self-hash is invalid");
        assertAuditFailure(audit -> audit.put("candidate_id", "not-routed"),
                "core route audit does not preserve the exact opportunity initial intent and hash");
        assertAuditFailure(audit -> audit.put("branch", "REVERSAL"),
                "core route audit does not preserve the exact opportunity initial intent and hash");
        assertAuditFailure(audit -> audit.put("direction", "LONG"),
                "core route audit does not preserve the exact opportunity initial intent and hash");
        assertAuditFailure(audit -> audit.put("decision_time", "2024-01-01T00:00:00Z"),
                "core route audit does not preserve the exact opportunity initial intent and hash");

        Fixture duplicatedAudit = fixture("SYNTHETIC_DEVELOPMENT_ONLY", true);
        ((ArrayNode) duplicatedAudit.replay().path("route_audit")).add(
                duplicatedAudit.replay().path("route_audit").get(0).deepCopy());
        refreshReplayAndEvidence(duplicatedAudit);
        reject(duplicatedAudit, "core initial intent lacks exactly one confirmed route-audit row");

        Fixture missingAudit = fixture("SYNTHETIC_DEVELOPMENT_ONLY", true);
        ((ArrayNode) missingAudit.replay().path("route_audit")).remove(0);
        refreshReplayAndEvidence(missingAudit);
        reject(missingAudit, "core initial intent lacks exactly one confirmed route-audit row");
    }

    @Test
    void predecessorInventoryMustRemainTheExactFourFrozenArmsAndFailedRealEvidenceCannotSeedStages() {
        Fixture badInventory = fixture("SYNTHETIC_DEVELOPMENT_ONLY", true);
        ((ObjectNode) badInventory.inventory().path("current_candidates").get(0)).put("variant", "ALTERED");
        rehash(badInventory.inventory());
        refreshReplayAndEvidence(badInventory);
        reject(badInventory, "core predecessor inventory contains an unexpected or duplicate frozen candidate");

        Fixture falseLeaf = fixture("PROXY_DISCLOSED_DEVELOPMENT_ONLY", false);
        reject(falseLeaf, "failed or incomplete real core evidence cannot seed staged advancement");

        Fixture missingGate = fixture("PROXY_DISCLOSED_DEVELOPMENT_ONLY", true);
        ((ObjectNode) missingGate.evidence().path("acceptance_gates")).remove("nested");
        refreshEvidence(missingGate);
        reject(missingGate, "failed or incomplete real core evidence cannot seed staged advancement");

        Fixture missingBlockerList = fixture("PROXY_DISCLOSED_DEVELOPMENT_ONLY", true);
        missingBlockerList.evidence().remove("advancement_blockers");
        refreshEvidence(missingBlockerList);
        reject(missingBlockerList, "failed or incomplete real core evidence cannot seed staged advancement");
    }

    private static void assertCoreFailure(Consumer<ObjectNode> mutation, String message) {
        Fixture fixture = fixture("SYNTHETIC_DEVELOPMENT_ONLY", true);
        mutation.accept((ObjectNode) fixture.replay().path("opportunities").get(0));
        // These mutations target a branch before the nested initial-intent/audit bindings are read.
        refreshReplayAndEvidence(fixture);
        reject(fixture, message);
    }

    private static void assertAuditFailure(Consumer<ObjectNode> mutation, String message) {
        Fixture fixture = fixture("SYNTHETIC_DEVELOPMENT_ONLY", true);
        mutation.accept((ObjectNode) fixture.replay().path("route_audit").get(0));
        refreshReplayAndEvidence(fixture);
        reject(fixture, message);
    }

    private static void assertIntentFailure(Consumer<ObjectNode> mutation, String message) {
        Fixture fixture = fixture("SYNTHETIC_DEVELOPMENT_ONLY", true);
        ObjectNode opportunity = (ObjectNode) fixture.replay().path("opportunities").get(0);
        ObjectNode intent = (ObjectNode) opportunity.path("initial_intent");
        mutation.accept(intent);
        rehash(intent);
        rebindInitialIntent(fixture, opportunity, intent);
        refreshReplayAndEvidence(fixture);
        reject(fixture, message);
    }

    private static void assertSeedFailure(Consumer<ObjectNode> mutation, String message) {
        Fixture fixture = fixture("SYNTHETIC_DEVELOPMENT_ONLY", true);
        ObjectNode opportunity = (ObjectNode) fixture.replay().path("opportunities").get(0);
        ObjectNode intent = (ObjectNode) opportunity.path("initial_intent");
        mutation.accept((ObjectNode) intent.path("setup_seed"));
        // Keep the seed digest stale while rebinding the enclosing immutable intent and audit row.
        rehash(intent);
        rebindInitialIntent(fixture, opportunity, intent);
        refreshReplayAndEvidence(fixture);
        reject(fixture, message);
    }

    private static void rebindInitialIntent(Fixture fixture, ObjectNode opportunity, ObjectNode intent) {
        String hash = JsonHashes.canonicalSha256(intent);
        opportunity.put("initial_intent_sha256", hash);
        String intentId = intent.path("intent_id").asText();
        for (JsonNode row : fixture.replay().path("route_audit")) {
            if (intentId.equals(row.path("intent_id").asText())) {
                ((ObjectNode) row).put("initial_intent_sha256", hash).set("initial_intent", intent.deepCopy());
            }
        }
    }

    private static void reject(Fixture fixture, String message) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                        fixture.replay(), fixture.evidence(), fixture.inventory()));
        assertTrue(error.getMessage().contains(message),
                () -> "expected '" + message + "', got '" + error.getMessage() + "'");
    }

    private static Fixture fixture(String sourceMode, boolean gatesPass) {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA)
                .put("version", 1).put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode candidates = inventory.putArray("current_candidates");
        candidates.addObject().put("candidate_id", ROUTED).put("variant", "ROUTED_REVERSAL_CONTINUATION");
        candidates.addObject().put("candidate_id", CONTINUE).put("variant", "ALWAYS_CONTINUATION_CONTROL");
        candidates.addObject().put("candidate_id", REVERSE).put("variant", "ALWAYS_REVERSAL_CONTROL");
        candidates.addObject().put("candidate_id", DIAGNOSTIC).put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        rehash(inventory);
        List<Instant> times = List.of(Instant.parse("2024-01-03T12:00:00Z"), Instant.parse("2024-01-04T12:00:00Z"));
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", sourceMode);
        replay.putObject("freeze").put("source_mode", sourceMode)
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        ArrayNode opportunities = replay.putArray("opportunities"), audit = replay.putArray("route_audit");
        for (int index = 0; index < times.size(); index++) addAnchor(opportunities, audit,
                index == 0 ? "pair-late" : "pair-early", index == 0 ? "intent-late" : "intent-early", times.get(index));
        rehash(replay);
        ObjectNode evidence = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EVIDENCE_SCHEMA).put("version", 1)
                .put("status", "SYNTHETIC_DEVELOPMENT_ONLY".equals(sourceMode) ? "BLOCKED" : "DEVELOPMENT")
                .put("source_mode", sourceMode).put("replay_result_sha256", replay.path("content_sha256").asText())
                .put("promotion_permitted", false).put("trade_authorization_permitted", false);
        if ("DEVELOPMENT".equals(evidence.path("status").asText())) {
            ObjectNode gates = evidence.putObject("acceptance_gates");
            if (gatesPass) gates.putObject("nested").put("all_core_gates", true).put("authorized", true);
            else gates.putObject("nested").put("all_core_gates", false);
            evidence.putArray("advancement_blockers");
        } else evidence.putArray("advancement_blockers").add("SYNTHETIC_FIXTURE_ONLY");
        rehash(evidence);
        return new Fixture(replay, evidence, inventory);
    }

    private static void addAnchor(ArrayNode opportunities, ArrayNode audit, String pair, String intentId, Instant time) {
        ObjectNode seed = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-anchor-setup-seed/1")
                .put("version", 1).put("setup_id", "setup-" + intentId).put("event_boundary_high", 110.0);
        rehash(seed);
        ObjectNode intent = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-confirmed-entry-intent/1").put("version", 1)
                .put("intent_id", intentId).put("setup_id", "setup-" + intentId).put("pair_id", pair)
                .put("asset", "BTC").put("variant", "ROUTED_REVERSAL_CONTINUATION")
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("stage", 1)
                .put("decision_time", time.toString()).put("diagnostic_only", false);
        intent.set("setup_seed", seed);
        rehash(intent);
        String intentHash = JsonHashes.canonicalSha256(intent);
        ObjectNode opportunity = opportunities.addObject().put("candidate_id", ROUTED)
                .put("variant", "ROUTED_REVERSAL_CONTINUATION").put("stage", 1)
                .put("pair_id", pair).put("asset", "BTC").put("decision_time", time.toString())
                .put("setup_id", intent.path("setup_id").asText()).put("initial_intent_sha256", intentHash);
        opportunity.set("initial_intent", intent.deepCopy());
        ObjectNode signal = audit.addObject().put("status", "CONFIRMED_STAGE_ONE_INTENT").put("candidate_id", ROUTED)
                .put("intent_id", intentId).put("setup_id", intent.path("setup_id").asText())
                .put("pair_id", pair).put("asset", "BTC").put("decision_time", time.toString())
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("initial_intent_sha256", intentHash);
        signal.set("initial_intent", intent.deepCopy());
    }

    private static void refreshReplayAndEvidence(Fixture fixture) {
        ((ObjectNode) fixture.replay().path("freeze")).put("candidate_inventory_sha256",
                fixture.inventory().path("content_sha256").asText());
        rehash(fixture.replay());
        refreshEvidence(fixture);
    }

    private static void refreshEvidence(Fixture fixture) {
        fixture.evidence().put("replay_result_sha256", fixture.replay().path("content_sha256").asText());
        rehash(fixture.evidence());
    }

    private static void rehash(ObjectNode object) {
        object.remove("content_sha256");
        object.put("content_sha256", JsonHashes.ownHash(object));
    }

    private record Fixture(ObjectNode replay, ObjectNode evidence, ObjectNode inventory) {}
}

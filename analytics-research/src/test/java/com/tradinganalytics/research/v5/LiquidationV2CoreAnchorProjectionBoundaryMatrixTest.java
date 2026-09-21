package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Reachable core anchor projection guards from a valid synthetic contract fixture. */
class LiquidationV2CoreAnchorProjectionBoundaryMatrixTest {
    private static final String ROUTED = "liquidation-v2-core-routed-one-entry";
    private static final String PAIR = "projection-pair";
    private static final String SETUP = "projection-setup";
    private static final Instant DECISION = Instant.parse("2024-01-03T12:00:00Z");

    @Test
    void acceptedProjectionIgnoresDiagnosticRowsAndPreservesOneRoutedAnchor() {
        Fixture fixture = fixture();
        ((ArrayNode) fixture.replay().path("opportunities")).add(JsonHashes.mapper().createObjectNode()
                .put("candidate_id", "liquidation-v2-price-oi-only-event-diagnostic")
                .put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC").put("stage", 1));
        refresh(fixture);
        ObjectNode plan = assertDoesNotThrow(() -> freeze(fixture));
        assertEquals(1, plan.path("decision_anchor_count").asInt());
        assertEquals(PAIR, plan.path("decision_anchors").get(0).path("pair_id").asText());
    }

    @Test
    void initialIntentMustRepeatPairSetupAssetDecisionVariantStageAndDiagnosticIdentity() {
        assertIntentMutation(intent -> intent.put("pair_id", "other-pair"),
                "core routed opportunity identity or bound setup seed differs from its initial intent");
        assertIntentMutation(intent -> intent.put("setup_id", "other-setup"),
                "core routed opportunity identity or bound setup seed differs from its initial intent");
        assertIntentMutation(intent -> intent.put("asset", "ETH"),
                "core routed opportunity identity or bound setup seed differs from its initial intent");
        assertIntentMutation(intent -> intent.put("decision_time", DECISION.plusSeconds(1).toString()),
                "core routed opportunity identity or bound setup seed differs from its initial intent");
        assertIntentMutation(intent -> intent.put("variant", "ALWAYS_CONTINUATION_CONTROL"),
                "core routed opportunity identity or bound setup seed differs from its initial intent");
        assertIntentMutation(intent -> intent.put("stage", 2),
                "core routed opportunity identity or bound setup seed differs from its initial intent");
        assertIntentMutation(intent -> intent.put("diagnostic_only", true),
                "core routed opportunity identity or bound setup seed differs from its initial intent");
    }

    @Test
    void branchAndDirectionAreReconciledAgainstTheAuditProjection() {
        assertIntentProjectionMutation(intent -> intent.put("branch", "REVERSAL"));
        assertIntentProjectionMutation(intent -> intent.put("direction", "LONG"));
        assertAuditMutation(audit -> audit.put("pair_id", "other-pair"));
        assertAuditMutation(audit -> audit.put("setup_id", "other-setup"));
        assertAuditMutation(audit -> audit.put("asset", "ETH"));
        assertAuditMutation(audit -> audit.put("initial_intent_sha256", "0".repeat(64)));
    }

    @Test
    void nestedIntentHashDuplicatePairAndSetupSeedHashFailAtTheirOwnBoundaries() {
        Fixture staleIntent = fixture();
        ((ObjectNode) staleIntent.replay().path("opportunities").get(0).path("initial_intent")).put("asset", "ETH");
        refresh(staleIntent);
        assertFailure(staleIntent, "duplicate pair or malformed full initial intent");

        Fixture duplicate = fixture();
        ((ArrayNode) duplicate.replay().path("opportunities")).add(
                duplicate.replay().path("opportunities").get(0).deepCopy());
        refresh(duplicate);
        assertFailure(duplicate, "duplicate pair or malformed full initial intent");

        Fixture seed = fixture();
        ObjectNode opportunity = (ObjectNode) seed.replay().path("opportunities").get(0);
        ObjectNode intent = (ObjectNode) opportunity.path("initial_intent");
        ((ObjectNode) intent.path("setup_seed")).put("setup_id", "other-setup");
        rehash(intent);
        rebind(seed, opportunity, intent, true);
        refresh(seed);
        assertFailure(seed, "core initial intent setup seed self-hash is invalid");
    }

    private static void assertIntentMutation(Consumer<ObjectNode> mutation, String expected) {
        Fixture fixture = fixture();
        ObjectNode opportunity = (ObjectNode) fixture.replay().path("opportunities").get(0);
        ObjectNode intent = (ObjectNode) opportunity.path("initial_intent");
        mutation.accept(intent);
        rehash(intent);
        rebind(fixture, opportunity, intent, true);
        refresh(fixture);
        assertFailure(fixture, expected);
    }

    private static void assertIntentProjectionMutation(Consumer<ObjectNode> mutation) {
        Fixture fixture = fixture();
        ObjectNode opportunity = (ObjectNode) fixture.replay().path("opportunities").get(0);
        ObjectNode intent = (ObjectNode) opportunity.path("initial_intent");
        mutation.accept(intent);
        rehash(intent);
        String hash = JsonHashes.canonicalSha256(intent);
        opportunity.put("initial_intent_sha256", hash).set("initial_intent", intent.deepCopy());
        ObjectNode audit = (ObjectNode) fixture.replay().path("route_audit").get(0);
        audit.put("initial_intent_sha256", hash).set("initial_intent", intent.deepCopy());
        refresh(fixture);
        assertFailure(fixture, "core route audit does not preserve the exact opportunity initial intent and hash");
    }

    private static void assertAuditMutation(Consumer<ObjectNode> mutation) {
        Fixture fixture = fixture();
        mutation.accept((ObjectNode) fixture.replay().path("route_audit").get(0));
        refresh(fixture);
        assertFailure(fixture, "core route audit does not preserve the exact opportunity initial intent and hash");
    }

    private static void rebind(Fixture fixture, ObjectNode opportunity, ObjectNode intent, boolean rebindAuditIntent) {
        String hash = JsonHashes.canonicalSha256(intent);
        opportunity.put("initial_intent_sha256", hash).set("initial_intent", intent.deepCopy());
        ObjectNode audit = (ObjectNode) fixture.replay().path("route_audit").get(0);
        audit.put("initial_intent_sha256", hash);
        if (rebindAuditIntent) audit.set("initial_intent", intent.deepCopy());
    }

    private static void assertFailure(Fixture fixture, String message) {
        var failure = assertThrows(IllegalArgumentException.class, () -> freeze(fixture));
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
    }

    private static ObjectNode freeze(Fixture fixture) {
        return LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(
                fixture.replay(), fixture.evidence(), fixture.inventory());
    }

    private static Fixture fixture() {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA).put("version", 1)
                .put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode candidates = inventory.putArray("current_candidates");
        candidates.addObject().put("candidate_id", ROUTED).put("variant", "ROUTED_REVERSAL_CONTINUATION");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-always-continuation-one-entry").put("variant", "ALWAYS_CONTINUATION_CONTROL");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-always-reversal-one-entry").put("variant", "ALWAYS_REVERSAL_CONTROL");
        candidates.addObject().put("candidate_id", "liquidation-v2-price-oi-only-event-diagnostic").put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        rehash(inventory);

        ObjectNode seed = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-anchor-setup-seed/1").put("version", 1).put("setup_id", SETUP);
        rehash(seed);
        ObjectNode intent = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-confirmed-entry-intent/1").put("version", 1)
                .put("intent_id", "projection-intent").put("setup_id", SETUP).put("pair_id", PAIR)
                .put("asset", "BTC").put("variant", "ROUTED_REVERSAL_CONTINUATION")
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("stage", 1)
                .put("decision_time", DECISION.toString()).put("diagnostic_only", false);
        intent.set("setup_seed", seed); rehash(intent);
        String intentHash = JsonHashes.canonicalSha256(intent);
        ObjectNode opportunity = JsonHashes.mapper().createObjectNode()
                .put("candidate_id", ROUTED).put("variant", "ROUTED_REVERSAL_CONTINUATION")
                .put("stage", 1).put("pair_id", PAIR).put("asset", "BTC")
                .put("decision_time", DECISION.toString()).put("branch", "CONTINUATION").put("direction", "SHORT")
                .put("setup_id", SETUP).put("initial_intent_sha256", intentHash);
        opportunity.set("initial_intent", intent.deepCopy());
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY");
        replay.putObject("freeze").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        replay.putArray("opportunities").add(opportunity);
        ObjectNode audit = replay.putArray("route_audit").addObject()
                .put("status", "CONFIRMED_STAGE_ONE_INTENT").put("candidate_id", ROUTED)
                .put("intent_id", "projection-intent").put("setup_id", SETUP).put("pair_id", PAIR)
                .put("asset", "BTC").put("decision_time", DECISION.toString()).put("branch", "CONTINUATION")
                .put("direction", "SHORT").put("initial_intent_sha256", intentHash);
        audit.set("initial_intent", intent.deepCopy());
        rehash(replay);
        ObjectNode evidence = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EVIDENCE_SCHEMA).put("version", 1)
                .put("status", "BLOCKED").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("replay_result_sha256", replay.path("content_sha256").asText())
                .put("promotion_permitted", false).put("trade_authorization_permitted", false);
        evidence.putArray("advancement_blockers").add("SYNTHETIC_FIXTURE_ONLY");
        rehash(evidence);
        return new Fixture(replay, evidence, inventory);
    }

    private static void refresh(Fixture fixture) {
        rehash(fixture.replay());
        fixture.evidence().put("replay_result_sha256", fixture.replay().path("content_sha256").asText());
        rehash(fixture.evidence());
    }

    private static void rehash(ObjectNode node) {
        node.remove("content_sha256"); node.put("content_sha256", JsonHashes.ownHash(node));
    }

    private record Fixture(ObjectNode replay, ObjectNode evidence, ObjectNode inventory) {}
}

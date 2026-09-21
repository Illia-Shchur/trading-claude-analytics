package com.tradinganalytics.research.v5;

import static com.tradinganalytics.research.v5.LiquidationStructureRouterV1.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Public runner-boundary parsing for frozen stage-one intent JSON and optional geometry. */
class LiquidationStructureRouterAnchorJsonBoundaryMatrixTest {
    private static final Instant DECISION = Instant.parse("2024-07-01T16:00:00Z");
    private static final Instant SEED_END = DECISION.minus(Duration.ofHours(4));
    private static final Instant SEED_START = SEED_END.minus(Duration.ofDays(1));
    private static final String SERIES = "anchor-json-boundary-v1";

    @Test
    void canonicalStageOneProjectionPreservesNullableAndPresentPivotFields() {
        ObjectNode nullable = LiquidationStructureRouterV1.stageOneAnchorJson(anchor(false));
        assertTrue(nullable.path("pivot_price").isNull());
        assertTrue(nullable.path("pivot_time").isNull());
        assertTrue(nullable.path("pivot_confirmed_at").isNull());
        assertTrue(nullable.path("reversal_target").isNull());
        RouteResult accepted = Router.anchoredStaged(MacroGatePolicy.STRUCTURE_ONLY)
                .acceptFrozenInitialAnchor(nullable);
        assertEquals(1, accepted.intents().size());
        assertEquals("anchor-json-intent", accepted.intents().get(0).intentId());

        ObjectNode present = LiquidationStructureRouterV1.stageOneAnchorJson(anchor(true));
        assertEquals(95.0, present.path("pivot_price").asDouble());
        assertEquals(DECISION.minus(Duration.ofHours(5)).toString(), present.path("pivot_time").asText());
        assertEquals(DECISION.minus(Duration.ofHours(1)).toString(), present.path("pivot_confirmed_at").asText());
        assertEquals(100.0, present.path("reversal_target").asDouble());
        assertEquals(1, Router.anchoredStaged(MacroGatePolicy.STRUCTURE_ONLY)
                .acceptFrozenInitialAnchor(present).intents().size());
    }

    @Test
    void optionalTimestampsNumbersAndTextListsRejectMalformedRepresentations() {
        reject(row -> row.put("pivot_price", "95"), "anchor optional number is invalid pivot_price");
        reject(row -> row.put("reversal_target", true), "anchor optional number is invalid reversal_target");
        reject(row -> row.put("pivot_time", 123), "anchor optional timestamp must be ISO text pivot_time");
        reject(row -> row.put("pivot_confirmed_at", "yesterday"), "anchor has invalid optional timestamp pivot_confirmed_at");
        reject(row -> row.putArray("rejection_reasons").add(7), "anchor text list contains a nontext value");

        ObjectNode sourceEvidence = validJson(false);
        sourceEvidence.put("source_evidence", "not-an-array");
        reseal(sourceEvidence);
        rejectRaw(sourceEvidence, "anchor source evidence must be an array");

        ObjectNode geometryEvidence = validJson(false);
        ObjectNode seed = (ObjectNode) geometryEvidence.path("setup_seed");
        seed.put("geometry_source_evidence", "not-an-array");
        rehash(seed);
        reseal(geometryEvidence);
        rejectRaw(geometryEvidence, "anchor source evidence must be an array");

        ObjectNode malformedSeedNumber = validJson(false);
        ((ObjectNode) malformedSeedNumber.path("setup_seed")).put("prior_high", "110");
        rehash((ObjectNode) malformedSeedNumber.path("setup_seed"));
        reseal(malformedSeedNumber);
        rejectRaw(malformedSeedNumber, "anchor requires finite number prior_high");
    }

    @Test
    void frozenIntentAndNestedSeedEnvelopesRejectSchemaVersionAndDigestMutations() {
        ObjectNode wrongSchema = validJson(false).put("schema", "other/1");
        reseal(wrongSchema);
        rejectRaw(wrongSchema, "frozen initial intent is not a valid self-hashed intent projection");

        ObjectNode wrongVersion = validJson(false).put("version", 2);
        reseal(wrongVersion);
        rejectRaw(wrongVersion, "frozen initial intent is not a valid self-hashed intent projection");

        ObjectNode staleIntentHash = validJson(false).put("content_sha256", "0".repeat(64));
        rejectRaw(staleIntentHash, "frozen initial intent is not a valid self-hashed intent projection");

        ObjectNode wrongSeedSchema = validJson(false);
        ((ObjectNode) wrongSeedSchema.path("setup_seed")).put("schema", "other-seed/1");
        rehash((ObjectNode) wrongSeedSchema.path("setup_seed"));
        reseal(wrongSeedSchema);
        rejectRaw(wrongSeedSchema, "frozen initial intent lacks a valid immutable setup seed");

        ObjectNode wrongSeedVersion = validJson(false);
        ((ObjectNode) wrongSeedVersion.path("setup_seed")).put("version", 2);
        rehash((ObjectNode) wrongSeedVersion.path("setup_seed"));
        reseal(wrongSeedVersion);
        rejectRaw(wrongSeedVersion, "frozen initial intent lacks a valid immutable setup seed");

        ObjectNode staleSeedHash = validJson(false);
        ((ObjectNode) staleSeedHash.path("setup_seed")).put("content_sha256", "f".repeat(64));
        reseal(staleSeedHash);
        rejectRaw(staleSeedHash, "frozen initial intent lacks a valid immutable setup seed");
    }

    @Test
    void requiredAnchorTimestampsAndFiniteNumbersRejectEachMalformedField() {
        for (String field : List.of("decision_time", "requested_execution_after", "confirmation_bar_start")) {
            ObjectNode malformedType = validJson(false).put(field, 7);
            reseal(malformedType);
            rejectRaw(malformedType, "anchor intent requires ISO timestamp " + field);
            ObjectNode malformedValue = validJson(false).put(field, "not-a-time");
            reseal(malformedValue);
            rejectRaw(malformedValue, "anchor intent has invalid timestamp " + field);
        }
        for (String field : List.of("confirmation_close", "zone_center", "zone_lower", "zone_upper",
                "pre_event_atr", "initial_stop", "max_chase_distance", "tranche_risk_fraction")) {
            ObjectNode malformed = validJson(false).put(field, "not-a-number");
            reseal(malformed);
            rejectRaw(malformed, "anchor requires finite number " + field);
        }
        ObjectNode missingText = validJson(false);
        missingText.put("intent_id", " ");
        reseal(missingText);
        rejectRaw(missingText, "anchor intent requires intent_id");
    }

    @Test
    void setupSeedBindingRequiresTheExactProjectionIdentity() {
        ObjectNode valid = validJson(false);
        assertEquals(JsonHashes.ownHash(valid), valid.path("content_sha256").asText());
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                row -> row.put("intent_id", "other-intent"),
                row -> row.put("setup_id", "other-setup"),
                row -> row.put("pair_id", "other-pair"),
                row -> row.put("stage", 2),
                row -> row.put("variant", "ALWAYS_CONTINUATION_CONTROL"))) {
            ObjectNode changed = valid.deepCopy();
            mutation.accept(changed);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> LiquidationStructureRouterV1.bindSetupSeed(changed, anchor(false)));
            assertEquals("setup seed can only bind its exact routed stage-one intent projection", failure.getMessage());
        }
    }

    private static void reject(Consumer<ObjectNode> mutation, String expected) {
        ObjectNode changed = validJson(false);
        mutation.accept(changed);
        reseal(changed);
        rejectRaw(changed, expected);
    }

    private static void rejectRaw(ObjectNode changed, String expected) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Router.anchoredStaged(MacroGatePolicy.STRUCTURE_ONLY).acceptFrozenInitialAnchor(changed));
        assertTrue(failure.getMessage().contains(expected),
                () -> "expected '" + expected + "', got '" + failure.getMessage() + "'");
    }

    private static ObjectNode validJson(boolean withPivot) {
        return LiquidationStructureRouterV1.stageOneAnchorJson(anchor(withPivot));
    }

    private static StageOneAnchorContext anchor(boolean withPivot) {
        AnchorSetupSeed seed = new AnchorSetupSeed("anchor-json-setup", "BTC", Direction.SHORT,
                SEED_START, SEED_END, SEED_END, 2.0, 110.0, 90.0, 90.0, 100.0,
                89.5, 90.5, true, false, "FAST", List.of(geometryEvidence()));
        Branch branch = withPivot ? Branch.REVERSAL : Branch.CONTINUATION;
        Direction direction = withPivot ? Direction.LONG : Direction.SHORT;
        ConfirmedIntent intent = new ConfirmedIntent("anchor-json-intent", "anchor-json-setup", "anchor-json-pair",
                "BTC", Variant.ROUTED_REVERSAL_CONTINUATION, branch, Branch.CONTINUATION,
                direction, Direction.SHORT, 1, DECISION, DECISION.plusNanos(1),
                DECISION.minus(Duration.ofHours(1)), 89.8, 90.0, 89.5, 90.5,
                withPivot ? 95.0 : null,
                withPivot ? DECISION.minus(Duration.ofHours(5)) : null,
                withPivot ? DECISION.minus(Duration.ofHours(1)) : null,
                2.0, withPivot ? 87.0 : 92.0, withPivot ? 100.0 : null, 1.0, 0.01, 2, 60,
                MacroState.NOT_REQUIRED, true, false, List.of(), List.of(confirmationEvidence()));
        return new StageOneAnchorContext(intent, seed);
    }

    private static SourceEvidence geometryEvidence() {
        return new SourceEvidence("GEOMETRY", "BTC", SERIES, SEED_START, SEED_START);
    }

    private static SourceEvidence confirmationEvidence() {
        return new SourceEvidence("CONFIRMATION", "BTC", SERIES, DECISION, DECISION);
    }

    private static void reseal(ObjectNode node) { rehash(node); }

    private static void rehash(ObjectNode node) {
        node.remove("content_sha256");
        node.put("content_sha256", JsonHashes.ownHash(node));
    }
}

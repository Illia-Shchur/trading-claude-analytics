package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Validated-outcome state and position-R matrix for predecessor-anchored staged rows. */
class LiquidationV2StagedOutcomeLifecycleMatrixTest {
    private static final String CORE = "liquidation-v2-core-routed-one-entry";
    private static final Instant DECISION = Instant.parse("2024-01-03T12:00:00Z");
    private static final String NO_MACRO = LiquidationV2StagedCandidateInventoryV1.THREE_STAGE_NO_MACRO;

    @Test
    void everyFrozenAnchorRequiresExactlyOneWellFormedAggregateEpisodeOutcome() {
        Fixture one = fixture(1);
        ObjectNode plan = one.plan();
        ArrayNode closed = rows(plan, "CLOSED_TRADE", 1);
        assertEquals("DEVELOPMENT_ONLY", LiquidationV2StagedCandidateInventoryV1
                .validateAnchoredOutcomes(plan, NO_MACRO, closed).path("status").asText());

        ArrayNode noTrade = rows(plan, "RESOLVED_NO_TRADE", 1);
        ObjectNode zero = LiquidationV2StagedCandidateInventoryV1.validateAnchoredOutcomes(plan, NO_MACRO, noTrade);
        assertEquals(1, zero.path("resolved_zero_count").asInt());
        assertTrue(LiquidationV2StagedCandidateInventoryV1.validateOutcomeSummary(zero));

        ArrayNode open = rows(plan, "OPEN_UNRESOLVED", 1);
        ObjectNode openSummary = LiquidationV2StagedCandidateInventoryV1.validateAnchoredOutcomes(plan, NO_MACRO, open);
        assertEquals("BLOCKED", openSummary.path("status").asText());
        assertEquals(1, openSummary.path("unresolved_count").asInt());

        ArrayNode blockedUnfilled = rows(plan, "COVERAGE_BLOCKED", 1);
        ObjectNode blockedSummary = LiquidationV2StagedCandidateInventoryV1.validateAnchoredOutcomes(
                plan, NO_MACRO, blockedUnfilled);
        assertEquals(1, blockedSummary.path("coverage_blocked_count").asInt());
        assertEquals(0, blockedSummary.path("filled_coverage_blocked_count").asInt());

        ArrayNode unresolvedNoFill = rows(plan, "UNRESOLVED_NO_FILL", 1);
        assertEquals("BLOCKED", LiquidationV2StagedCandidateInventoryV1
                .validateAnchoredOutcomes(plan, NO_MACRO, unresolvedNoFill).path("status").asText());
    }

    @Test
    void filledCoverageBlockIsRetainedAsOneUnresolvedPositionAndCannotWidenItsRiskDenominator() {
        ObjectNode plan = fixture(1).plan();
        ArrayNode blocked = rows(plan, "COVERAGE_BLOCKED", 1);
        ObjectNode row = (ObjectNode) blocked.get(0);
        fill(row, "episode-1");
        ObjectNode summary = LiquidationV2StagedCandidateInventoryV1.validateAnchoredOutcomes(plan, NO_MACRO, blocked);
        assertEquals(1, summary.path("filled_coverage_blocked_count").asInt());
        assertEquals(1, summary.path("position_outcome_count").asInt(), "a filled blocked episode remains an aggregate position row");

        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                r -> r.remove("first_fill_reference_equity_usdt"),
                r -> r.put("full_position_reference_risk_usdt", 100),
                r -> r.put("reference_risk_usdt", 100),
                r -> r.put("full_position_reference_risk_basis", "PER_TRANCHE_R"),
                r -> r.put("first_fill_reference_equity_usdt", -10_000),
                r -> r.put("full_position_reference_risk_usdt", Double.NaN))) {
            ArrayNode changed = rows(plan, "COVERAGE_BLOCKED", 1);
            fill((ObjectNode) changed.get(0), "episode-1");
            mutation.accept((ObjectNode) changed.get(0));
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> LiquidationV2StagedCandidateInventoryV1.validateAnchoredOutcomes(plan, NO_MACRO, changed));
            assertTrue(error.getMessage().contains("numeric full-position reference equity and 5% R")
                    || error.getMessage().contains("inconsistent with 5% of first-fill reference equity"), error.getMessage());
        }

        ArrayNode insideCanonicalRounding = rows(plan, "COVERAGE_BLOCKED", 1);
        ObjectNode rounded = (ObjectNode) insideCanonicalRounding.get(0);
        fill(rounded, "episode-rounded");
        rounded.put("first_fill_reference_equity_usdt", 73282.90750729221)
                .put("full_position_reference_risk_usdt", 3664.14537536461)
                .put("reference_risk_usdt", 3664.14537536461);
        assertDoesNotThrow(() -> LiquidationV2StagedCandidateInventoryV1.validateAnchoredOutcomes(
                plan, NO_MACRO, insideCanonicalRounding));
    }

    @Test
    void outcomeStateFieldsAreValidatedAtTheirCausalBoundaries() {
        ObjectNode plan = fixture(1).plan();
        reject(plan, rows(plan, "CLOSED_TRADE", 1), r -> r.putNull("first_fill_time"), "no-fill outcome cannot invent a first-fill position episode");

        reject(plan, rows(plan, "CLOSED_TRADE", 1), r -> r.put("first_fill_time", DECISION.toString()),
                "staged first fill must be strictly after its anchored decision");

        reject(plan, rows(plan, "CLOSED_TRADE", 1), r -> r.put("exit_time", DECISION.plusSeconds(30).toString()),
                "closed staged position has invalid exit/economic availability");
        reject(plan, rows(plan, "CLOSED_TRADE", 1), r -> r.put("outcome_available_time", DECISION.plusSeconds(90).toString()),
                "closed staged position has invalid exit/economic availability");
        reject(plan, rows(plan, "CLOSED_TRADE", 1), r -> r.put("net_pnl_usdt", Double.POSITIVE_INFINITY),
                "closed staged position has invalid exit/economic availability");

        reject(plan, rows(plan, "OPEN_UNRESOLVED", 1), r -> r.putNull("first_fill_time").putNull("position_episode_id"),
                "open staged position must remain unresolved");
        reject(plan, rows(plan, "OPEN_UNRESOLVED", 1), r -> r.put("outcome_available_time", DECISION.toString()),
                "open staged position must remain unresolved");
        reject(plan, rows(plan, "OPEN_UNRESOLVED", 1), r -> r.put("exit_time", DECISION.plusSeconds(1).toString()),
                "open staged position must remain unresolved");
        reject(plan, rows(plan, "OPEN_UNRESOLVED", 1), r -> r.put("net_pnl_usdt", 0),
                "open staged position must remain unresolved");

        reject(plan, rows(plan, "COVERAGE_BLOCKED", 1), r -> r.put("outcome_available_time", DECISION.minusNanos(1).toString()),
                "coverage block predates its anchor");
        reject(plan, rows(plan, "COVERAGE_BLOCKED", 1), r -> r.remove("reason_codes"),
                "staged zero/unresolved outcome needs explicit reason codes");

        reject(plan, rows(plan, "RESOLVED_NO_TRADE", 1), r -> fill(r, "invalid-no-trade-fill"),
                "resolved no-trade must be an explicit zero known no earlier than its anchor");
        reject(plan, rows(plan, "RESOLVED_NO_TRADE", 1), r -> r.put("outcome_available_time", DECISION.minusNanos(1).toString()),
                "resolved no-trade must be an explicit zero known no earlier than its anchor");
        reject(plan, rows(plan, "RESOLVED_NO_TRADE", 1), r -> r.put("net_pnl_usdt", 1),
                "resolved no-trade must be an explicit zero known no earlier than its anchor");
        reject(plan, rows(plan, "RESOLVED_NO_TRADE", 1), r -> r.putArray("reason_codes"),
                "staged zero/unresolved outcome needs explicit reason codes");

        reject(plan, rows(plan, "UNRESOLVED_NO_FILL", 1), r -> r.put("net_pnl_usdt", 0),
                "unresolved no-fill row cannot carry outcome economics");
        reject(plan, rows(plan, "UNRESOLVED_NO_FILL", 1), r -> fill(r, "invalid-unresolved-episode"),
                "unresolved no-fill row cannot carry outcome economics");
        reject(plan, rows(plan, "UNRESOLVED_NO_FILL", 1), r -> r.putArray("reason_codes"),
                "staged zero/unresolved outcome needs explicit reason codes");
        reject(plan, rows(plan, "UNRESOLVED_NO_FILL", 1), r -> r.put("reason_codes", "missing-array"),
                "staged zero/unresolved outcome needs explicit reason codes");

        reject(plan, rows(plan, "FUTURE_WINNER", 1), r -> { }, "staged output has an unsupported outcome state");
    }

    @Test
    void exactPairInventoryRejectsDuplicateOmittedUnknownAndReboundRows() {
        ObjectNode plan = fixture(2).plan();
        ArrayNode both = rows(plan, "RESOLVED_NO_TRADE", 2);
        assertEquals(2, LiquidationV2StagedCandidateInventoryV1
                .validateAnchoredOutcomes(plan, NO_MACRO, both).path("retained_anchor_count").asInt());

        ArrayNode duplicate = both.deepCopy();
        duplicate.add(duplicate.get(0).deepCopy());
        assertReject(plan, duplicate, "staged output has an extra or duplicate frozen predecessor pair");

        ArrayNode omitted = rows(plan, "RESOLVED_NO_TRADE", 1);
        assertReject(plan, omitted, "staged output omitted frozen decision anchors or explicit zero outcomes");

        ArrayNode unknown = rows(plan, "RESOLVED_NO_TRADE", 1);
        ((ObjectNode) unknown.get(0)).put("pair_id", "not-a-frozen-pair");
        assertReject(plan, unknown, "staged output has an extra or duplicate frozen predecessor pair");

        ArrayNode wrongCandidate = rows(plan, "RESOLVED_NO_TRADE", 2);
        ((ObjectNode) wrongCandidate.get(0)).put("candidate_id", "other-candidate");
        assertReject(plan, wrongCandidate, "staged output changed its frozen initial decision, branch, direction, or full intent geometry");

        ArrayNode wrongGeometry = rows(plan, "RESOLVED_NO_TRADE", 2);
        ((ObjectNode) wrongGeometry.get(0)).put("asset", "ETH");
        assertReject(plan, wrongGeometry, "staged output changed its frozen initial decision, branch, direction, or full intent geometry");

        ArrayNode duplicateEpisodes = rows(plan, "CLOSED_TRADE", 2);
        fill((ObjectNode) duplicateEpisodes.get(0), "shared-episode");
        fill((ObjectNode) duplicateEpisodes.get(1), "shared-episode");
        assertReject(plan, duplicateEpisodes, "staged additions were counted as more than one position episode");
    }

    private static void reject(ObjectNode plan, ArrayNode rows, Consumer<ObjectNode> mutation, String message) {
        ArrayNode changed = rows.deepCopy();
        mutation.accept((ObjectNode) changed.get(0));
        assertReject(plan, changed, message);
    }

    private static void assertReject(ObjectNode plan, ArrayNode rows, String message) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2StagedCandidateInventoryV1.validateAnchoredOutcomes(plan, NO_MACRO, rows));
        assertTrue(error.getMessage().contains(message), () -> "expected '" + message + "', got '" + error.getMessage() + "'");
    }

    private static ArrayNode rows(ObjectNode plan, String outcome, int count) {
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        for (int index = 0; index < count; index++) {
            JsonNode anchor = plan.path("decision_anchors").get(index);
            Instant decision = Instant.parse(anchor.path("decision_time").asText());
            String pair = anchor.path("pair_id").asText();
            ObjectNode row = result.addObject().put("candidate_id", plan.path("candidate_id").asText())
                    .put("variant", "ROUTED_REVERSAL_CONTINUATION").put("stage", 1)
                    .put("pair_id", pair).put("asset", anchor.path("asset").asText())
                    .put("decision_time", decision.toString()).put("branch", anchor.path("branch").asText())
                    .put("direction", anchor.path("direction").asText())
                    .put("initial_intent_sha256", anchor.path("initial_intent_sha256").asText())
                    .put("outcome_state", outcome);
            row.set("initial_intent", anchor.path("initial_intent").deepCopy());
            switch (outcome) {
                case "CLOSED_TRADE" -> {
                    fill(row, "episode-" + index);
                    Instant exit = decision.plus(Duration.ofDays(1));
                    row.put("exit_time", exit.toString()).put("outcome_available_time", exit.toString()).put("net_pnl_usdt", 100);
                }
                case "OPEN_UNRESOLVED" -> {
                    fill(row, "episode-" + index);
                    row.putNull("exit_time").putNull("outcome_available_time").putNull("net_pnl_usdt");
                }
                case "COVERAGE_BLOCKED" -> {
                    row.putNull("first_fill_time").putNull("position_episode_id")
                            .putNull("exit_time").putNull("net_pnl_usdt").putNull("outcome_available_time")
                            .putArray("reason_codes").add("SOURCE_GAP");
                }
                case "RESOLVED_NO_TRADE" -> row.putNull("first_fill_time").putNull("position_episode_id")
                        .putNull("exit_time").put("outcome_available_time", decision.toString())
                        .put("net_pnl_usdt", 0).putArray("reason_codes").add("ANCHOR_REJECTED");
                case "UNRESOLVED_NO_FILL" -> row.putNull("first_fill_time").putNull("position_episode_id")
                        .putNull("exit_time").putNull("outcome_available_time").putNull("net_pnl_usdt")
                        .putArray("reason_codes").add("EXECUTION_UNRESOLVED");
                default -> row.putNull("first_fill_time").putNull("position_episode_id")
                        .putNull("exit_time").putNull("outcome_available_time").putNull("net_pnl_usdt")
                        .putArray("reason_codes").add("FIXTURE");
            }
        }
        return result;
    }

    private static void fill(ObjectNode row, String episode) {
        Instant decision = Instant.parse(row.path("decision_time").asText());
        row.put("first_fill_time", decision.plusSeconds(60).toString())
                .put("position_episode_id", episode)
                .put("first_fill_reference_equity_usdt", 10000)
                .put("full_position_reference_risk_usdt", 500)
                .put("reference_risk_usdt", 500)
                .put("full_position_reference_risk_basis", LiquidationV2StagedCandidateInventoryV1.FULL_POSITION_R_BASIS);
    }

    private static Fixture fixture(int count) {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA)
                .put("version", 1).put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode candidates = inventory.putArray("current_candidates");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-routed-one-entry").put("variant", "ROUTED_REVERSAL_CONTINUATION");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-always-continuation-one-entry").put("variant", "ALWAYS_CONTINUATION_CONTROL");
        candidates.addObject().put("candidate_id", "liquidation-v2-core-always-reversal-one-entry").put("variant", "ALWAYS_REVERSAL_CONTROL");
        candidates.addObject().put("candidate_id", "liquidation-v2-price-oi-only-event-diagnostic").put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        reseal(inventory);

        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY");
        replay.putObject("freeze").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        ArrayNode opportunities = replay.putArray("opportunities");
        ArrayNode audit = replay.putArray("route_audit");
        for (int index = 0; index < count; index++) {
            Instant decision = DECISION.plus(Duration.ofHours(index));
            String pair = "pair-" + index, setup = "setup-" + index, intentId = "intent-" + index;
            ObjectNode seed = JsonHashes.mapper().createObjectNode()
                    .put("schema", "liquidation-v2-anchor-setup-seed/1").put("version", 1).put("setup_id", setup);
            reseal(seed);
            ObjectNode intent = JsonHashes.mapper().createObjectNode()
                    .put("schema", "liquidation-v2-confirmed-entry-intent/1").put("version", 1)
                    .put("intent_id", intentId).put("setup_id", setup).put("pair_id", pair).put("asset", "BTC")
                    .put("variant", "ROUTED_REVERSAL_CONTINUATION").put("branch", index % 2 == 0 ? "CONTINUATION" : "REVERSAL")
                    .put("direction", "SHORT").put("stage", 1).put("decision_time", decision.toString()).put("diagnostic_only", false);
            intent.set("setup_seed", seed); reseal(intent);
            String sha = JsonHashes.canonicalSha256(intent);
            ObjectNode opportunity = opportunities.addObject()
                    .put("candidate_id", CORE).put("variant", "ROUTED_REVERSAL_CONTINUATION").put("stage", 1)
                    .put("pair_id", pair).put("asset", "BTC").put("decision_time", decision.toString())
                    .put("setup_id", setup).put("initial_intent_sha256", sha);
            opportunity.set("initial_intent", intent.deepCopy());
            ObjectNode signal = audit.addObject().put("status", "CONFIRMED_STAGE_ONE_INTENT")
                    .put("candidate_id", CORE).put("intent_id", intentId).put("setup_id", setup).put("pair_id", pair)
                    .put("asset", "BTC").put("decision_time", decision.toString())
                    .put("branch", intent.path("branch").asText()).put("direction", "SHORT").put("initial_intent_sha256", sha);
            signal.set("initial_intent", intent.deepCopy());
        }
        reseal(replay);
        ObjectNode evidence = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EVIDENCE_SCHEMA).put("version", 1)
                .put("status", "BLOCKED").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("replay_result_sha256", replay.path("content_sha256").asText())
                .put("promotion_permitted", false).put("trade_authorization_permitted", false);
        evidence.putArray("advancement_blockers"); reseal(evidence);
        ObjectNode plan = LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(replay, evidence, inventory);
        return new Fixture(plan);
    }

    private static void reseal(ObjectNode value) {
        value.remove("content_sha256");
        value.put("content_sha256", JsonHashes.ownHash(value));
    }

    private record Fixture(ObjectNode plan) { }
}

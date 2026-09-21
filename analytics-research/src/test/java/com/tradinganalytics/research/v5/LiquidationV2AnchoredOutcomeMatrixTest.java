package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Validates aggregate outcome rows against every frozen predecessor decision anchor. */
class LiquidationV2AnchoredOutcomeMatrixTest {
    private static final String ROUTED = "liquidation-v2-core-routed-one-entry";
    private static final String CONTINUE = "liquidation-v2-core-always-continuation-one-entry";
    private static final String REVERSE = "liquidation-v2-core-always-reversal-one-entry";
    private static final String DIAGNOSTIC = "liquidation-v2-price-oi-only-event-diagnostic";
    private static final String MODE = LiquidationV2StagedCandidateInventoryV1.THREE_STAGE_NO_MACRO;
    private static final Instant FIRST_DECISION = Instant.parse("2024-03-01T12:00:00Z");

    @Test
    void acceptsEveryOutcomeClassAndRetainsOneAggregateRowPerAnchor() {
        ObjectNode plan = plan();
        for (String state : List.of("CLOSED_TRADE", "OPEN_UNRESOLVED", "COVERAGE_BLOCKED",
                "RESOLVED_NO_TRADE", "UNRESOLVED_NO_FILL")) {
            ArrayNode rows = JsonHashes.mapper().createArrayNode();
            rows.add(row(plan, 0, state));
            rows.add(row(plan, 1, state));
            ObjectNode summary = LiquidationV2StagedCandidateInventoryV1.validateAnchoredOutcomes(plan, MODE, rows);
            assertEquals(2, summary.path("retained_anchor_count").asInt(), state);
            assertEquals(2, summary.path("position_outcome_count").asInt(), state);
            assertTrue(LiquidationV2StagedCandidateInventoryV1.validateOutcomeSummary(summary), state);
            assertEquals("CLOSED_TRADE".equals(state) ? 2 : 0, summary.path("completed_position_count").asInt(), state);
        }
    }

    @Test
    void anchorIdentityPairUniquenessAndCompletenessAreNotCallerSelectable() {
        ObjectNode plan = plan();
        reject(plan, JsonHashes.mapper().createArrayNode().add(row(plan, 0, "RESOLVED_NO_TRADE"))
                        .add(row(plan, 0, "RESOLVED_NO_TRADE")),
                "staged output has an extra or duplicate frozen predecessor pair");
        reject(plan, JsonHashes.mapper().createArrayNode().add(row(plan, 0, "RESOLVED_NO_TRADE"))
                        .add(row(plan, 1, "RESOLVED_NO_TRADE")).add(row(plan, 0, "RESOLVED_NO_TRADE")),
                "staged output has an extra or duplicate frozen predecessor pair");
        reject(plan, JsonHashes.mapper().createArrayNode().add(row(plan, 0, "RESOLVED_NO_TRADE")),
                "staged output omitted frozen decision anchors or explicit zero outcomes");
        for (Mutation mutation : List.of(
                new Mutation("candidate_id", value -> value.put("candidate_id", "not-frozen")),
                new Mutation("variant", value -> value.put("variant", "ALWAYS_REVERSAL_CONTROL")),
                new Mutation("asset", value -> value.put("asset", "ETH")),
                new Mutation("decision time", value -> value.put("decision_time", FIRST_DECISION.plusSeconds(1).toString())),
                new Mutation("branch", value -> value.put("branch", "REVERSAL")),
                new Mutation("direction", value -> value.put("direction", "LONG")),
                new Mutation("intent hash", value -> value.put("initial_intent_sha256", "0".repeat(64))))) {
            ArrayNode rows = JsonHashes.mapper().createArrayNode().add(row(plan, 0, "RESOLVED_NO_TRADE"))
                    .add(row(plan, 1, "RESOLVED_NO_TRADE"));
            mutation.mutation().accept((ObjectNode) rows.get(0));
            reject(plan, rows, "staged output changed its frozen initial decision, branch, direction, or full intent geometry");
        }
        ArrayNode changedIntentRows = JsonHashes.mapper().createArrayNode().add(row(plan, 0, "RESOLVED_NO_TRADE"))
                .add(row(plan, 1, "RESOLVED_NO_TRADE"));
        ObjectNode intent = (ObjectNode) changedIntentRows.get(0).path("initial_intent");
        intent.put("direction", "LONG");
        rehash(intent);
        reject(plan, changedIntentRows,
                "staged output changed its frozen initial decision, branch, direction, or full intent geometry");
    }

    @Test
    void lifecycleAndRiskRulesRejectCausalTimestampAndAccountingContradictions() {
        ObjectNode plan = plan();
        expectRowFailure(plan, row -> row.put("outcome_state", "FUTURE_WINNER"),
                "staged output has an unsupported outcome state");
        expectRowFailure(plan, row -> row.put("outcome_state", "CLOSED_TRADE")
                .put("exit_time", FIRST_DECISION.plus(Duration.ofDays(1)).toString()),
                "closed staged trade is missing its first fill");
        expectRowFailure(plan, row -> addFillFields(row, FIRST_DECISION),
                "staged first fill must be strictly after its anchored decision");
        expectRowFailure(plan, row -> row.put("position_episode_id", "invented-without-fill"),
                "no-fill outcome cannot invent a first-fill position episode");
        expectRowFailure(plan, row -> {
            addFillFields(row, FIRST_DECISION.plusSeconds(60));
            row.put("first_fill_reference_equity_usdt", 20000).put("full_position_reference_risk_usdt", 200)
                    .put("reference_risk_usdt", 200);
        }, "staged R denominator is inconsistent with 5% of first-fill reference equity");
        expectRowFailure(plan, row -> {
            addFillFields(row, FIRST_DECISION.plusSeconds(60));
            row.put("first_fill_reference_equity_usdt", "20000").put("full_position_reference_risk_usdt", 1000)
                    .put("reference_risk_usdt", 1000);
        }, "filled staged position lacks numeric full-position reference equity and 5% R");
        expectRowFailure(plan, row -> {
            addFillFields(row, FIRST_DECISION.plusSeconds(60));
            row.put("first_fill_reference_equity_usdt", 20000).put("full_position_reference_risk_usdt", 1000)
                    .put("reference_risk_usdt", 1000).put("full_position_reference_risk_basis", "TRANCHE_ONLY");
        }, "filled staged position lacks numeric full-position reference equity and 5% R");

        expectRowFailure(plan, row -> row.put("outcome_state", "RESOLVED_NO_TRADE")
                .put("outcome_available_time", FIRST_DECISION.minusSeconds(1).toString()),
                "resolved no-trade must be an explicit zero known no earlier than its anchor");
        expectRowFailure(plan, row -> row.put("outcome_state", "RESOLVED_NO_TRADE").put("net_pnl_usdt", -1),
                "resolved no-trade must be an explicit zero known no earlier than its anchor");
        expectRowFailure(plan, row -> row.put("outcome_state", "RESOLVED_NO_TRADE").putArray("reason_codes"),
                "staged zero/unresolved outcome needs explicit reason codes");

        expectRowFailure(plan, row -> {
            row.put("outcome_state", "OPEN_UNRESOLVED").put("outcome_available_time", FIRST_DECISION.toString());
            addFillFields(row, FIRST_DECISION.plusSeconds(60));
        }, "open staged position must remain unresolved");
        expectRowFailure(plan, row -> row.put("outcome_state", "COVERAGE_BLOCKED")
                .put("outcome_available_time", FIRST_DECISION.minusSeconds(1).toString()), "coverage block predates its anchor");
        expectRowFailure(plan, row -> row.put("outcome_state", "COVERAGE_BLOCKED").putArray("reason_codes"),
                "staged zero/unresolved outcome needs explicit reason codes");

        expectRowFailure(plan, row -> row.put("outcome_state", "UNRESOLVED_NO_FILL")
                .put("outcome_available_time", FIRST_DECISION.toString()),
                "unresolved no-fill row cannot carry outcome economics");
        expectRowFailure(plan, row -> row.put("outcome_state", "UNRESOLVED_NO_FILL").put("net_pnl_usdt", 0),
                "unresolved no-fill row cannot carry outcome economics");
        expectRowFailure(plan, row -> row.put("outcome_state", "UNRESOLVED_NO_FILL")
                .putNull("outcome_available_time").putNull("net_pnl_usdt").putArray("reason_codes"),
                "staged zero/unresolved outcome needs explicit reason codes");
    }

    @Test
    void positionEpisodeIdsCannotTurnTranchesIntoIndependentTradesAndSummaryMustBeHashBound() {
        ObjectNode plan = plan();
        ArrayNode twoFills = JsonHashes.mapper().createArrayNode();
        ObjectNode first = row(plan, 0, "CLOSED_TRADE"), second = row(plan, 1, "CLOSED_TRADE");
        addFillFields(first, FIRST_DECISION.plusSeconds(60));
        addFillFields(second, FIRST_DECISION.plus(Duration.ofDays(1)).plusSeconds(60));
        first.put("position_episode_id", "same-episode");
        second.put("position_episode_id", "same-episode");
        twoFills.add(first).add(second);
        reject(plan, twoFills, "staged additions were counted as more than one position episode");

        ArrayNode good = JsonHashes.mapper().createArrayNode().add(row(plan, 0, "RESOLVED_NO_TRADE"))
                .add(row(plan, 1, "RESOLVED_NO_TRADE"));
        ObjectNode summary = LiquidationV2StagedCandidateInventoryV1.validateAnchoredOutcomes(plan, MODE, good);
        assertTrue(LiquidationV2StagedCandidateInventoryV1.validateOutcomeSummary(summary));
        for (Mutation mutation : List.of(
                new Mutation("schema", value -> value.put("schema", "other")),
                new Mutation("version", value -> value.put("version", 9)),
                new Mutation("promotion", value -> value.put("promotion_permitted", true)),
                new Mutation("development scope", value -> value.put("development_only", false)),
                new Mutation("outcome count", value -> value.put("position_outcome_count", 1)))) {
            ObjectNode damaged = summary.deepCopy();
            mutation.mutation().accept(damaged);
            rehash(damaged);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> LiquidationV2StagedCandidateInventoryV1.validateOutcomeSummary(damaged));
            assertTrue(error.getMessage().contains("staged outcome summary is malformed or omits predecessor anchors"), mutation.label());
        }
    }

    private static void expectRowFailure(ObjectNode plan, Consumer<ObjectNode> mutation, String expected) {
        ObjectNode row = row(plan, 0, "RESOLVED_NO_TRADE");
        mutation.accept(row);
        ArrayNode rows = JsonHashes.mapper().createArrayNode().add(row).add(row(plan, 1, "RESOLVED_NO_TRADE"));
        reject(plan, rows, expected);
    }

    private static void reject(ObjectNode plan, ArrayNode rows, String expected) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2StagedCandidateInventoryV1.validateAnchoredOutcomes(plan, MODE, rows));
        assertTrue(error.getMessage().contains(expected),
                () -> "expected '" + expected + "', got '" + error.getMessage() + "'");
    }

    private static ObjectNode row(ObjectNode plan, int index, String state) {
        JsonNode anchor = plan.path("decision_anchors").get(index);
        ObjectNode intent = (ObjectNode) anchor.path("initial_intent");
        ObjectNode row = JsonHashes.mapper().createObjectNode()
                .put("candidate_id", plan.path("candidate_id").asText()).put("variant", "ROUTED_REVERSAL_CONTINUATION")
                .put("stage", 1).put("pair_id", anchor.path("pair_id").asText())
                .put("asset", anchor.path("asset").asText()).put("decision_time", anchor.path("decision_time").asText())
                .put("branch", anchor.path("branch").asText()).put("direction", anchor.path("direction").asText())
                .put("initial_intent_sha256", anchor.path("initial_intent_sha256").asText())
                .put("outcome_state", state).put("outcome_available_time", anchor.path("decision_time").asText())
                .put("net_pnl_usdt", 0).putNull("first_fill_time").putNull("exit_time");
        row.putArray("reason_codes").add("EXPLICIT_FIXTURE_RESOLUTION");
        row.set("initial_intent", intent.deepCopy());
        if ("RESOLVED_NO_TRADE".equals(state)) row.put("reference_risk_usdt", 0);
        if ("UNRESOLVED_NO_FILL".equals(state)) row.putNull("net_pnl_usdt").putNull("outcome_available_time");
        if ("OPEN_UNRESOLVED".equals(state)) row.putNull("net_pnl_usdt").putNull("outcome_available_time");
        if ("COVERAGE_BLOCKED".equals(state)) row.putNull("net_pnl_usdt").putNull("outcome_available_time");
        if ("CLOSED_TRADE".equals(state)) {
            addFillFields(row, Instant.parse(anchor.path("decision_time").asText()).plusSeconds(60));
            Instant exit = Instant.parse(anchor.path("decision_time").asText()).plus(Duration.ofHours(12));
            row.put("exit_time", exit.toString()).put("outcome_available_time", exit.toString()).put("net_pnl_usdt", 10.0);
        }
        if ("OPEN_UNRESOLVED".equals(state) || "COVERAGE_BLOCKED".equals(state)) {
            addFillFields(row, Instant.parse(anchor.path("decision_time").asText()).plusSeconds(60));
            row.putNull("exit_time").putNull("outcome_available_time").putNull("net_pnl_usdt");
        }
        return row;
    }

    private static void addFillFields(ObjectNode row, Instant fill) {
        row.put("first_fill_time", fill.toString()).put("position_episode_id", "episode-" + row.path("pair_id").asText())
                .put("first_fill_reference_equity_usdt", 20000).put("full_position_reference_risk_usdt", 1000)
                .put("reference_risk_usdt", 1000)
                .put("full_position_reference_risk_basis", LiquidationV2StagedCandidateInventoryV1.FULL_POSITION_R_BASIS);
    }

    private static ObjectNode plan() {
        ObjectNode inventory = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.CANDIDATE_INVENTORY_SCHEMA).put("version", 1)
                .put("strategy_family", LiquidationV2StagedCandidateInventoryV1.FAMILY);
        ArrayNode candidates = inventory.putArray("current_candidates");
        candidates.addObject().put("candidate_id", ROUTED).put("variant", "ROUTED_REVERSAL_CONTINUATION");
        candidates.addObject().put("candidate_id", CONTINUE).put("variant", "ALWAYS_CONTINUATION_CONTROL");
        candidates.addObject().put("candidate_id", REVERSE).put("variant", "ALWAYS_REVERSAL_CONTROL");
        candidates.addObject().put("candidate_id", DIAGNOSTIC).put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
        rehash(inventory);
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1)
                .put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY");
        replay.putObject("freeze").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("candidate_inventory_sha256", inventory.path("content_sha256").asText());
        ArrayNode opportunities = replay.putArray("opportunities"), audit = replay.putArray("route_audit");
        addAnchor(opportunities, audit, "pair-1", "intent-1", FIRST_DECISION);
        addAnchor(opportunities, audit, "pair-2", "intent-2", FIRST_DECISION.plus(Duration.ofDays(1)));
        rehash(replay);
        ObjectNode evidence = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EVIDENCE_SCHEMA).put("version", 1)
                .put("status", "BLOCKED").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("replay_result_sha256", replay.path("content_sha256").asText())
                .put("promotion_permitted", false).put("trade_authorization_permitted", false);
        evidence.putArray("advancement_blockers").add("SYNTHETIC_FIXTURE");
        rehash(evidence);
        return LiquidationV2StagedCandidateInventoryV1.freezeNoMacro(replay, evidence, inventory);
    }

    private static void addAnchor(ArrayNode opportunities, ArrayNode audit, String pair, String intentId, Instant time) {
        ObjectNode seed = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-anchor-setup-seed/1")
                .put("version", 1).put("setup_id", "setup-" + intentId);
        rehash(seed);
        ObjectNode intent = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-confirmed-entry-intent/1").put("version", 1)
                .put("intent_id", intentId).put("setup_id", "setup-" + intentId).put("pair_id", pair)
                .put("asset", "BTC").put("variant", "ROUTED_REVERSAL_CONTINUATION")
                .put("branch", "CONTINUATION").put("direction", "SHORT").put("stage", 1)
                .put("decision_time", time.toString()).put("diagnostic_only", false);
        intent.set("setup_seed", seed);
        rehash(intent);
        String sha = JsonHashes.canonicalSha256(intent);
        ObjectNode opportunity = opportunities.addObject().put("candidate_id", ROUTED)
                .put("variant", "ROUTED_REVERSAL_CONTINUATION").put("stage", 1).put("pair_id", pair)
                .put("asset", "BTC").put("decision_time", time.toString()).put("setup_id", intent.path("setup_id").asText())
                .put("initial_intent_sha256", sha);
        opportunity.set("initial_intent", intent.deepCopy());
        ObjectNode signal = audit.addObject().put("status", "CONFIRMED_STAGE_ONE_INTENT").put("candidate_id", ROUTED)
                .put("intent_id", intentId).put("setup_id", intent.path("setup_id").asText()).put("pair_id", pair)
                .put("asset", "BTC").put("decision_time", time.toString()).put("branch", "CONTINUATION")
                .put("direction", "SHORT").put("initial_intent_sha256", sha);
        signal.set("initial_intent", intent.deepCopy());
    }

    private static void rehash(ObjectNode object) {
        object.remove("content_sha256");
        object.put("content_sha256", JsonHashes.ownHash(object));
    }

    private record Mutation(String label, Consumer<ObjectNode> mutation) {}
}

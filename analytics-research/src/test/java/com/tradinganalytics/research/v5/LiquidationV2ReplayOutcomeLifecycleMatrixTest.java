package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Table-driven causal lifecycle mutations start from a replay accepted by the public evaluator. */
class LiquidationV2ReplayOutcomeLifecycleMatrixTest {
    private static final String ROUTED = "liquidation-v2-core-routed-one-entry";
    private static final String CONTINUE = "liquidation-v2-core-always-continuation-one-entry";
    private static final String REVERSE = "liquidation-v2-core-always-reversal-one-entry";
    private static final String DIAGNOSTIC = "liquidation-v2-price-oi-only-event-diagnostic";
    private static final List<String> IDS = List.of(ROUTED, CONTINUE, REVERSE, DIAGNOSTIC);
    private static final List<String> VARIANTS = List.of("ROUTED_REVERSAL_CONTINUATION", "ALWAYS_CONTINUATION_CONTROL",
            "ALWAYS_REVERSAL_CONTROL", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC");
    private static final Instant DECISION = Instant.parse("2024-02-01T00:00:00Z");

    @Test
    void baselineIsAcceptedAndEveryClosedTradeLifecycleClauseIsEnforced() throws Exception {
        var frozen = frozen();
        assertDoesNotThrow(() -> LiquidationV2ReplayEvidenceV1.build(replay(frozen), frozen));
        String error = "closed trade must resolve at exit and carry valid fill, PnL, and reference risk:";
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                row -> row.putNull("outcome_available_time"), row -> row.putNull("first_fill_time"),
                row -> row.putNull("exit_time"), row -> row.put("first_fill_time", DECISION.toString()),
                row -> row.put("exit_time", DECISION.toString()),
                row -> row.put("outcome_available_time", DECISION.plus(Duration.ofDays(2)).plusSeconds(1).toString()),
                row -> row.putNull("net_pnl_usdt"), row -> row.putNull("reference_risk_usdt"),
                row -> row.put("reference_risk_usdt", 0.0))) reject(frozen, mutation, error);
    }

    @Test
    void noTradeOpenAndUnresolvedNoFillStatesRejectEachContradictoryLifecycleField() throws Exception {
        var frozen = frozen();
        String noTrade = "resolved no-trade requires an explicit resolution time and zero economic row:";
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                row -> row.putNull("outcome_available_time"),
                row -> row.put("outcome_available_time", DECISION.minusSeconds(1).toString()),
                row -> row.put("first_fill_time", DECISION.plusSeconds(1).toString()),
                row -> row.put("exit_time", DECISION.plusSeconds(1).toString()),
                row -> row.putNull("net_pnl_usdt"), row -> row.put("reference_risk_usdt", 1.0))) {
            reject(frozen, "RESOLVED_NO_TRADE", mutation, noTrade);
        }

        String open = "open unresolved trade has invalid lifecycle or risk fields:";
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                row -> row.put("outcome_available_time", DECISION.plusSeconds(1).toString()),
                row -> row.putNull("first_fill_time"), row -> row.put("first_fill_time", DECISION.toString()),
                row -> row.put("exit_time", DECISION.plusSeconds(2).toString()),
                row -> row.put("net_pnl_usdt", 0.0), row -> row.putNull("reference_risk_usdt"),
                row -> row.put("reference_risk_usdt", 0.0))) reject(frozen, "OPEN_UNRESOLVED", mutation, open);

        String noFill = "unresolved no-fill row cannot carry trade outcome fields";
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                row -> row.put("outcome_available_time", DECISION.toString()),
                row -> row.put("first_fill_time", DECISION.plusSeconds(1).toString()),
                row -> row.put("exit_time", DECISION.plusSeconds(1).toString()),
                row -> row.put("net_pnl_usdt", 0.0), row -> row.put("reference_risk_usdt", 1.0))) {
            reject(frozen, "UNRESOLVED_NO_FILL", mutation, noFill);
        }
    }

    @Test
    void coverageBlockedRowsRequireReasonAndRetainConsistentFilledOrUnfilledRisk() throws Exception {
        var frozen = frozen();
        String error = "coverage-blocked row must retain its reason and unresolved lifecycle:";
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                row -> row.put("outcome_available_time", DECISION.toString()),
                row -> row.put("exit_time", DECISION.plusSeconds(1).toString()),
                row -> row.put("net_pnl_usdt", 1.0),
                row -> row.put("first_fill_time", DECISION.toString()),
                row -> row.put("first_fill_time", DECISION.plusSeconds(1).toString()).putNull("reference_risk_usdt"),
                row -> row.put("first_fill_time", DECISION.plusSeconds(1).toString()).put("reference_risk_usdt", 0.0),
                row -> row.putArray("reason_codes"))) {
            reject(frozen, "COVERAGE_BLOCKED", mutation, error);
        }
        reject(frozen, "COVERAGE_BLOCKED", row -> row.putArray("reason_codes").add(" "),
                "reason_codes entries must be non-empty text");
    }

    private static void reject(LiquidationV2ReplayEvidenceV1.FreezeInputs frozen,
            Consumer<ObjectNode> mutation, String expected) {
        reject(frozen, "CLOSED_TRADE", mutation, expected);
    }

    private static void reject(LiquidationV2ReplayEvidenceV1.FreezeInputs frozen, String state,
            Consumer<ObjectNode> mutation, String expected) {
        ObjectNode replay = replay(frozen);
        ObjectNode row = (ObjectNode) replay.path("opportunities").get(0);
        setState(row, state);
        mutation.accept(row);
        reseal(replay);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayEvidenceV1.build(replay, frozen));
        assertTrue(error.getMessage().contains(expected),
                () -> "expected '" + expected + "', got '" + error.getMessage() + "'");
    }

    private static void setState(ObjectNode row, String state) {
        switch (state) {
            case "CLOSED_TRADE" -> { }
            case "RESOLVED_NO_TRADE" -> row.put("outcome_state", state)
                    .put("outcome_available_time", DECISION.toString()).putNull("first_fill_time").putNull("exit_time")
                    .put("net_pnl_usdt", 0.0).put("reference_risk_usdt", 0.0);
            case "OPEN_UNRESOLVED" -> row.put("outcome_state", state).putNull("outcome_available_time")
                    .put("first_fill_time", DECISION.plusSeconds(1).toString()).putNull("exit_time")
                    .putNull("net_pnl_usdt").put("reference_risk_usdt", 100.0);
            case "UNRESOLVED_NO_FILL" -> row.put("outcome_state", state).putNull("outcome_available_time")
                    .putNull("first_fill_time").putNull("exit_time").putNull("net_pnl_usdt").put("reference_risk_usdt", 0.0);
            case "COVERAGE_BLOCKED" -> row.put("outcome_state", state).putNull("outcome_available_time")
                    .putNull("first_fill_time").putNull("exit_time").putNull("net_pnl_usdt").put("reference_risk_usdt", 0.0)
                    .putArray("reason_codes").add("COVERAGE_LIMIT");
            default -> throw new IllegalArgumentException(state);
        }
    }

    private static LiquidationV2ReplayEvidenceV1.FreezeInputs frozen() throws Exception {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        Path repository = repositoryRoot();
        ObjectNode precommit = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(repository.resolve(
                "docs/research/liquidation-daily-stress-v002/frozen-precommit.json")));
        ObjectNode manifest = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-physical-manifest/1")
                .put("version", 1).put("status", "DEVELOPMENT_SYNTHETIC").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("profile_sha256", profile.path("content_sha256").asText())
                .put("precommit_sha256", precommit.path("content_sha256").asText())
                .put("authoritative", false).put("authoritative_evaluation_permitted", false);
        reseal(manifest);
        ObjectNode executor = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EXECUTOR_IDENTITY_SCHEMA).put("version", 1)
                .put("capability", profile.path("executor_capability").asText())
                .put("default_stage_add_macro_gate_policy", "REQUIRE_MACRO_CONFIRMATION");
        reseal(executor);
        return new LiquidationV2ReplayEvidenceV1.FreezeInputs(manifest, precommit, profile, executor,
                LiquidationV2ReplayEvidenceV1.frozenCandidateInventory(profile));
    }

    private static ObjectNode replay(LiquidationV2ReplayEvidenceV1.FreezeInputs frozen) {
        ObjectNode replay = JsonHashes.mapper().createObjectNode().put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA)
                .put("version", 1);
        replay.putObject("freeze").put("manifest_sha256", frozen.manifest().path("content_sha256").asText())
                .put("precommit_sha256", frozen.precommit().path("content_sha256").asText())
                .put("profile_sha256", frozen.profile().path("content_sha256").asText())
                .put("executor_sha256", frozen.executorIdentity().path("content_sha256").asText())
                .put("candidate_inventory_sha256", frozen.candidateInventory().path("content_sha256").asText())
                .put("source_mode", frozen.manifest().path("source_mode").asText());
        replay.put("event_stream_sha256", "a".repeat(64)).put("event_count", 3);
        ArrayNode curve = replay.putArray("account_curve");
        curve.addObject().put("time", 1_700_000_000_000L).put("equity_usdt", 20000.0);
        curve.addObject().put("time", 1_700_000_060_000L).put("equity_usdt", 20001.0);
        replay.put("account_curve_sha256", JsonHashes.canonicalSha256(curve)).put("ledger_sha256", "b".repeat(64));
        ArrayNode opportunities = replay.putArray("opportunities");
        for (int index = 0; index < 3; index++) {
            String id = IDS.get(index), variant = VARIANTS.get(index);
            opportunities.addObject().put("opportunity_id", "pair-1::" + id).put("pair_id", "pair-1")
                    .put("candidate_id", id).put("variant", variant).put("asset", "BTC")
                    .put("decision_time", DECISION.toString()).put("outcome_state", "CLOSED_TRADE")
                    .put("outcome_available_time", DECISION.plus(Duration.ofDays(2)).toString())
                    .put("first_fill_time", DECISION.plusSeconds(60).toString())
                    .put("exit_time", DECISION.plus(Duration.ofDays(2)).toString())
                    .put("net_pnl_usdt", 12.5).put("reference_risk_usdt", 200.0).putArray("reason_codes");
        }
        ArrayNode evaluated = replay.putArray("evaluated_candidates");
        for (int index = 0; index < IDS.size(); index++) evaluated.addObject().put("candidate_id", IDS.get(index))
                .put("variant", VARIANTS.get(index)).put("audit_only", index == 3)
                .put("execution_scope", "SYNTHETIC_FIXTURE_EXECUTED").put("opportunity_count", index == 3 ? 0 : 1)
                .put("executed", true);
        reseal(replay);
        return replay;
    }

    private static void reseal(ObjectNode object) {
        object.remove("content_sha256");
        object.put("content_sha256", JsonHashes.ownHash(object));
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve(
                "docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) cursor = cursor.getParent();
        if (cursor == null) throw new IllegalStateException("repository root unavailable to replay lifecycle test");
        return cursor;
    }
}

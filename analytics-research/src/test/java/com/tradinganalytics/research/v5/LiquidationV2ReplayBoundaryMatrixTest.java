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
import org.junit.jupiter.api.Test;

/** Public build-boundary matrix; every negative starts from a replay accepted by the same validator. */
class LiquidationV2ReplayBoundaryMatrixTest {
    private static final String ROUTED = "liquidation-v2-core-routed-one-entry";
    private static final String CONTINUE = "liquidation-v2-core-always-continuation-one-entry";
    private static final String REVERSE = "liquidation-v2-core-always-reversal-one-entry";
    private static final String DIAGNOSTIC = "liquidation-v2-price-oi-only-event-diagnostic";
    private static final String ROUTED_VARIANT = "ROUTED_REVERSAL_CONTINUATION";
    private static final String CONTINUE_VARIANT = "ALWAYS_CONTINUATION_CONTROL";
    private static final String REVERSE_VARIANT = "ALWAYS_REVERSAL_CONTROL";

    @Test
    void acceptsBaselineAndRejectsEnvelopeMutationsAtTheirOwnValidationBoundary() throws Exception {
        var frozen = frozen();
        ObjectNode baseline = replay(frozen);
        assertDoesNotThrow(() -> LiquidationV2ReplayEvidenceV1.build(baseline, frozen));

        ObjectNode tampered = baseline.deepCopy();
        tampered.put("event_count", baseline.path("event_count").asInt() + 1);
        IllegalArgumentException hashFailure = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayEvidenceV1.build(tampered, frozen));
        assertTrue(hashFailure.getMessage().contains("v002 replay result is missing, unsupported, or hash-tampered"));

        ObjectNode schema = baseline.deepCopy().put("schema", "liquidation-v2-replay-result/99");
        reseal(schema);
        reject(frozen, schema, "v002 replay result is missing, unsupported, or hash-tampered");

        ObjectNode badVersion = baseline.deepCopy().put("version", 2);
        reseal(badVersion);
        reject(frozen, badVersion, "v002 replay result is missing, unsupported, or hash-tampered");

        ObjectNode badRef = baseline.deepCopy();
        ((ObjectNode) badRef.path("freeze")).put("profile_sha256", "0".repeat(64));
        reseal(badRef);
        reject(frozen, badRef, "replay freeze references do not match the reopened frozen inputs");

        ObjectNode badEventHash = baseline.deepCopy().put("event_stream_sha256", "not-a-digest");
        reseal(badEventHash);
        reject(frozen, badEventHash, "event_stream_sha256 must be a lowercase SHA-256 digest");

        ObjectNode negativeEventCount = baseline.deepCopy().put("event_count", -1);
        reseal(negativeEventCount);
        reject(frozen, negativeEventCount, "event count must be a nonnegative integer");

        ObjectNode brokenCurveRef = baseline.deepCopy().put("account_curve_sha256", "f".repeat(64));
        reseal(brokenCurveRef);
        reject(frozen, brokenCurveRef, "account curve is missing or its canonical hash does not match");

        ObjectNode duplicateCurveTime = baseline.deepCopy();
        ((ObjectNode) duplicateCurveTime.path("account_curve").get(1))
                .put("time", duplicateCurveTime.path("account_curve").get(0).path("time").asLong());
        resealCurveAndReplay(duplicateCurveTime);
        reject(frozen, duplicateCurveTime, "account curve timestamps must be strictly chronological");

        ObjectNode badLedger = baseline.deepCopy().put("ledger_sha256", "X".repeat(64));
        reseal(badLedger);
        reject(frozen, badLedger, "ledger_sha256 must be a lowercase SHA-256 digest");

        ObjectNode missingRows = baseline.deepCopy().put("opportunities", "not-an-array");
        reseal(missingRows);
        reject(frozen, missingRows, "opportunities must be an array");
    }

    @Test
    void opportunityValidationRejectsBadIdentitiesTypesAndLifecycleFieldsPrecisely() throws Exception {
        var frozen = frozen();
        assertDoesNotThrow(() -> LiquidationV2ReplayEvidenceV1.build(replay(frozen), frozen));

        ObjectNode scalar = replay(frozen);
        ((ArrayNode) scalar.path("opportunities")).set(0, JsonHashes.mapper().getNodeFactory().textNode("row"));
        syncCounts(scalar);
        reject(frozen, scalar, "opportunity rows must be objects");

        ObjectNode duplicateId = replay(frozen);
        ((ObjectNode) duplicateId.path("opportunities").get(1)).put("opportunity_id",
                duplicateId.path("opportunities").get(0).path("opportunity_id").asText());
        reject(frozen, duplicateId, "duplicate opportunity identity:");

        ObjectNode mismatchedVariant = replay(frozen);
        ((ObjectNode) mismatchedVariant.path("opportunities").get(0)).put("variant", "ALWAYS_SHORT_CONTROL");
        reject(frozen, mismatchedVariant, "unknown or mismatched frozen candidate variant:");

        ObjectNode invalidAsset = replay(frozen);
        ((ObjectNode) invalidAsset.path("opportunities").get(0)).put("asset", "SPY");
        reject(frozen, invalidAsset, "opportunity asset is outside the frozen crypto universe:");

        ObjectNode invalidDecision = replay(frozen);
        ((ObjectNode) invalidDecision.path("opportunities").get(0)).put("decision_time", "tomorrow-ish");
        reject(frozen, invalidDecision, "decision_time must be an ISO-8601 instant");

        ObjectNode unknownState = replay(frozen);
        ((ObjectNode) unknownState.path("opportunities").get(0)).put("outcome_state", "WINNER");
        reject(frozen, unknownState, "unknown replay outcome state: WINNER");

        ObjectNode missingPnl = replay(frozen);
        ((ObjectNode) missingPnl.path("opportunities").get(0)).remove("net_pnl_usdt");
        reject(frozen, missingPnl,
                "opportunity rows must explicitly carry net_pnl_usdt and reference_risk_usdt");

        ObjectNode nonfinite = replay(frozen);
        ((ObjectNode) nonfinite.path("opportunities").get(0)).put("net_pnl_usdt", "not-a-number");
        reject(frozen, nonfinite, "net_pnl_usdt must be null or finite numeric data");

        ObjectNode reasonNotArray = replay(frozen);
        ((ObjectNode) reasonNotArray.path("opportunities").get(0)).put("reason_codes", "reason");
        reject(frozen, reasonNotArray, "reason_codes must be an array");

        ObjectNode duplicateReasons = replay(frozen);
        ObjectNode reasonRow = (ObjectNode) duplicateReasons.path("opportunities").get(0);
        reasonRow.putArray("reason_codes").add("SAME").add("SAME");
        reject(frozen, duplicateReasons, "reason_codes cannot contain duplicates");

        ObjectNode invalidBranch = replay(frozen);
        ((ObjectNode) invalidBranch.path("opportunities").get(0)).put("branch", " ");
        reject(frozen, invalidBranch, "branch must be null or non-empty text");
    }

    @Test
    void pairedDecisionAndEachOutcomeStateRequireTheirOwnCompleteCausalShape() throws Exception {
        var frozen = frozen();
        assertDoesNotThrow(() -> LiquidationV2ReplayEvidenceV1.build(replay(frozen), frozen));

        ObjectNode missingArm = replay(frozen);
        ((ArrayNode) missingArm.path("opportunities")).remove(2);
        syncCounts(missingArm);
        reject(frozen, missingArm, "paired decision does not contain exactly three traded variants:");

        ObjectNode timeMismatch = replay(frozen);
        ((ObjectNode) timeMismatch.path("opportunities").get(1)).put("decision_time", TIME.minusSeconds(60).toString());
        reject(frozen, timeMismatch, "paired variants must be unique and share pair, asset, and decision time:");

        ObjectNode assetMismatch = replay(frozen);
        ((ObjectNode) assetMismatch.path("opportunities").get(1)).put("asset", "ETH");
        reject(frozen, assetMismatch, "paired variants must be unique and share pair, asset, and decision time:");

        ObjectNode badClosedResolution = replay(frozen);
        ((ObjectNode) badClosedResolution.path("opportunities").get(0)).put("outcome_available_time",
                TIME.plus(Duration.ofDays(3)).toString());
        reject(frozen, badClosedResolution, "closed trade must resolve at exit and carry valid fill, PnL, and reference risk:");

        ObjectNode badNoTrade = replay(frozen);
        ObjectNode noTrade = replaceWithNoTrade((ObjectNode) badNoTrade.path("opportunities").get(0));
        noTrade.put("net_pnl_usdt", 1.0);
        reject(frozen, badNoTrade, "resolved no-trade requires an explicit resolution time and zero economic row:");

        ObjectNode badOpen = replay(frozen);
        ObjectNode open = (ObjectNode) badOpen.path("opportunities").get(0);
        open.put("outcome_state", "OPEN_UNRESOLVED").putNull("outcome_available_time")
                .put("first_fill_time", TIME.toString()).putNull("exit_time").putNull("net_pnl_usdt");
        reject(frozen, badOpen, "open unresolved trade has invalid lifecycle or risk fields:");

        ObjectNode badNoFill = replay(frozen);
        ObjectNode noFill = (ObjectNode) badNoFill.path("opportunities").get(0);
        noFill.put("outcome_state", "UNRESOLVED_NO_FILL").putNull("outcome_available_time")
                .putNull("first_fill_time").putNull("exit_time").put("net_pnl_usdt", 0.0).putNull("reference_risk_usdt");
        reject(frozen, badNoFill, "unresolved no-fill row cannot carry trade outcome fields:");

        ObjectNode blockedWithoutReason = replay(frozen);
        ObjectNode blocked = (ObjectNode) blockedWithoutReason.path("opportunities").get(0);
        blocked.put("outcome_state", "COVERAGE_BLOCKED").putNull("outcome_available_time")
                .putNull("first_fill_time").putNull("exit_time").putNull("net_pnl_usdt")
                .putNull("reference_risk_usdt").putArray("reason_codes");
        reject(frozen, blockedWithoutReason, "coverage-blocked row must retain its reason and unresolved lifecycle:");

        ObjectNode diagnosticTrade = replay(frozen);
        ArrayNode rows = (ArrayNode) diagnosticTrade.path("opportunities");
        rows.addObject().put("opportunity_id", "diagnostic-trade").put("pair_id", "diagnostic-trade")
                .put("candidate_id", DIAGNOSTIC).put("variant", "PRICE_OI_ONLY_EVENT_DIAGNOSTIC")
                .put("asset", "BTC").put("decision_time", TIME.toString()).put("outcome_state", "CLOSED_TRADE")
                .put("outcome_available_time", TIME.plusSeconds(60).toString())
                .put("first_fill_time", TIME.plusSeconds(1).toString()).put("exit_time", TIME.plusSeconds(60).toString())
                .put("net_pnl_usdt", 1.0).put("reference_risk_usdt", 1.0).putArray("reason_codes");
        syncCounts(diagnosticTrade);
        reject(frozen, diagnosticTrade, "price-plus-OI diagnostic is audit-only and cannot carry a traded or zero-PnL arm");
    }

    @Test
    void evaluatedCandidateRowsAreExactFrozenExecutionInventoryNotCallerClaims() throws Exception {
        var frozen = frozen();
        ObjectNode accepted = replay(frozen);
        assertDoesNotThrow(() -> LiquidationV2ReplayEvidenceV1.build(accepted, frozen));

        ObjectNode omitted = accepted.deepCopy();
        ((ArrayNode) omitted.path("evaluated_candidates")).remove(3);
        reject(frozen, omitted, "replay must bind every frozen candidate as evaluated, including zero-opportunity arms");

        ObjectNode extraField = accepted.deepCopy();
        ((ObjectNode) extraField.path("evaluated_candidates").get(0)).put("caller_claim", true);
        reject(frozen, extraField, "evaluated candidate row must contain exactly the frozen identity and execution fields");

        ObjectNode auditFlag = accepted.deepCopy();
        ((ObjectNode) auditFlag.path("evaluated_candidates").get(0)).put("audit_only", true);
        reject(frozen, auditFlag,
                "replay evaluated-candidate exposure differs from the exact frozen inventory or opportunity rows");

        ObjectNode notExecuted = accepted.deepCopy();
        ((ObjectNode) notExecuted.path("evaluated_candidates").get(0)).put("executed", false);
        reject(frozen, notExecuted,
                "replay evaluated-candidate exposure differs from the exact frozen inventory or opportunity rows");

        ObjectNode fractional = accepted.deepCopy();
        ((ObjectNode) fractional.path("evaluated_candidates").get(0)).put("opportunity_count", 3.5);
        reject(frozen, fractional,
                "replay evaluated-candidate exposure differs from the exact frozen inventory or opportunity rows");

        ObjectNode duplicateCandidate = accepted.deepCopy();
        ((ObjectNode) duplicateCandidate.path("evaluated_candidates").get(1)).put("candidate_id", ROUTED);
        reject(frozen, duplicateCandidate,
                "replay evaluated-candidate exposure differs from the exact frozen inventory or opportunity rows");
    }

    private static final Instant TIME = Instant.parse("2024-02-01T00:00:00Z");

    private static LiquidationV2ReplayEvidenceV1.FreezeInputs frozen() throws Exception {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        ObjectNode precommit = (ObjectNode) JsonHashes.mapper().readTree(Files.readString(repositoryRoot()
                .resolve("docs/research/liquidation-daily-stress-v002/frozen-precommit.json")));
        ObjectNode manifest = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-physical-manifest/1").put("version", 1)
                .put("status", "DEVELOPMENT_SYNTHETIC").put("source_mode", "SYNTHETIC_DEVELOPMENT_ONLY")
                .put("profile_sha256", profile.path("content_sha256").asText())
                .put("precommit_sha256", precommit.path("content_sha256").asText())
                .put("authoritative", false).put("authoritative_evaluation_permitted", false);
        manifest.put("content_sha256", JsonHashes.ownHash(manifest));
        ObjectNode executor = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.EXECUTOR_IDENTITY_SCHEMA).put("version", 1)
                .put("capability", profile.path("executor_capability").asText())
                .put("default_stage_add_macro_gate_policy", "REQUIRE_MACRO_CONFIRMATION");
        executor.put("content_sha256", JsonHashes.ownHash(executor));
        return new LiquidationV2ReplayEvidenceV1.FreezeInputs(manifest, precommit, profile, executor,
                LiquidationV2ReplayEvidenceV1.frozenCandidateInventory(profile));
    }

    private static ObjectNode replay(LiquidationV2ReplayEvidenceV1.FreezeInputs frozen) {
        ObjectNode replay = JsonHashes.mapper().createObjectNode()
                .put("schema", LiquidationV2ReplayEvidenceV1.INPUT_SCHEMA).put("version", 1);
        replay.putObject("freeze")
                .put("manifest_sha256", frozen.manifest().path("content_sha256").asText())
                .put("precommit_sha256", frozen.precommit().path("content_sha256").asText())
                .put("profile_sha256", frozen.profile().path("content_sha256").asText())
                .put("executor_sha256", frozen.executorIdentity().path("content_sha256").asText())
                .put("candidate_inventory_sha256", frozen.candidateInventory().path("content_sha256").asText())
                .put("source_mode", frozen.manifest().path("source_mode").asText());
        replay.put("event_stream_sha256", "a".repeat(64)).put("event_count", 3);
        ArrayNode curve = replay.putArray("account_curve");
        curve.addObject().put("time", 1_700_000_000_000L).put("equity_usdt", "20000.00");
        curve.addObject().put("time", 1_700_000_060_000L).put("equity_usdt", "20010.00");
        replay.put("account_curve_sha256", JsonHashes.canonicalSha256(curve))
                .put("ledger_sha256", "b".repeat(64)).put("account_curve_scope", "DAILY_MARKS_ONLY");
        ArrayNode opportunities = replay.putArray("opportunities");
        addClosed(opportunities, "pair-1", ROUTED, ROUTED_VARIANT, TIME);
        addClosed(opportunities, "pair-1", CONTINUE, CONTINUE_VARIANT, TIME);
        addClosed(opportunities, "pair-1", REVERSE, REVERSE_VARIANT, TIME);
        ArrayNode evaluated = replay.putArray("evaluated_candidates");
        for (JsonNode candidate : frozen.candidateInventory().path("current_candidates")) {
            String candidateId = candidate.path("candidate_id").asText();
            long count = 0;
            for (JsonNode row : opportunities) {
                if (candidateId.equals(row.path("candidate_id").asText())) count++;
            }
            evaluated.addObject().put("candidate_id", candidateId).put("variant", candidate.path("variant").asText())
                    .put("audit_only", candidate.path("audit_only").asBoolean())
                    .put("execution_scope", "SYNTHETIC_FIXTURE_EXECUTED")
                    .put("opportunity_count", count).put("executed", true);
        }
        reseal(replay);
        return replay;
    }

    private static void addClosed(ArrayNode rows, String pairId, String candidateId,
            String variant, Instant decision) {
        rows.addObject().put("opportunity_id", pairId + "::" + candidateId).put("pair_id", pairId)
                .put("candidate_id", candidateId).put("variant", variant).put("asset", "BTC")
                .put("decision_time", decision.toString()).put("branch", "CONTINUATION").put("direction", "LONG")
                .put("outcome_state", "CLOSED_TRADE").put("outcome_available_time", decision.plus(Duration.ofDays(2)).toString())
                .put("first_fill_time", decision.plus(Duration.ofMinutes(1)).toString())
                .put("exit_time", decision.plus(Duration.ofDays(2)).toString())
                .put("net_pnl_usdt", 12.5).put("reference_risk_usdt", 200.0).putArray("reason_codes");
    }

    private static ObjectNode replaceWithNoTrade(ObjectNode row) {
        return row.put("outcome_state", "RESOLVED_NO_TRADE")
                .put("outcome_available_time", TIME.toString()).putNull("first_fill_time").putNull("exit_time")
                .put("net_pnl_usdt", 0.0).put("reference_risk_usdt", 0.0);
    }

    private static void syncCounts(ObjectNode replay) {
        for (JsonNode evaluated : replay.path("evaluated_candidates")) {
            String id = evaluated.path("candidate_id").asText();
            long count = 0;
            for (JsonNode opportunity : replay.path("opportunities")) {
                if (id.equals(opportunity.path("candidate_id").asText())) count++;
            }
            ((ObjectNode) evaluated).put("opportunity_count", count);
        }
        reseal(replay);
    }

    private static void resealCurveAndReplay(ObjectNode replay) {
        replay.put("account_curve_sha256", JsonHashes.canonicalSha256(replay.path("account_curve")));
        reseal(replay);
    }

    private static void reseal(ObjectNode replay) { replay.put("content_sha256", JsonHashes.ownHash(replay)); }

    private static void reject(LiquidationV2ReplayEvidenceV1.FreezeInputs frozen,
            ObjectNode mutated, String targetMessage) {
        reseal(mutated);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2ReplayEvidenceV1.build(mutated, frozen));
        assertTrue(error.getMessage().contains(targetMessage),
                () -> "expected target validation '" + targetMessage + "' but got: " + error.getMessage());
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor.resolve(
                "docs/research/liquidation-daily-stress-v002/frozen-precommit.json"))) cursor = cursor.getParent();
        if (cursor == null) throw new IllegalStateException("repository root is unavailable to frozen precommit test");
        return cursor;
    }
}

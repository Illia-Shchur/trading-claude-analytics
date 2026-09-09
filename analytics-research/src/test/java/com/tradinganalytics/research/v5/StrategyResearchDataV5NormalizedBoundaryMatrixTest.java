package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Deterministic public-wrapper coverage for normalized lifecycle binding. */
final class StrategyResearchDataV5NormalizedBoundaryMatrixTest {
    private static final String H = "a".repeat(64);
    private static final long START = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private static final long MINUTE = 60_000L;

    @Test
    void lifecycleEngineFallbackCopiesCanonicalFieldsAndExecutionRisk() {
        ObjectNode request = normalizedLifecycle(START);
        ObjectNode candidate = (ObjectNode) request.path("candidate");
        ObjectNode lifecycle = (ObjectNode) candidate.remove("lifecycle");
        candidate.put("lifecycle_engine", TradeLifecycleV5.LIFECYCLE_SCHEMA)
                .put("max_lifecycle_ms", 2 * MINUTE).put("gap_policy", "OPEN");
        candidate.set("stop_spec", lifecycle.path("stop").deepCopy());
        candidate.set("target_spec", object().put("type", "PERCENT").put("value", .01));
        candidate.set("sizing", lifecycle.path("sizing").deepCopy());
        ObjectNode exitPolicy = object();
        exitPolicy.set("partials", array().add(object().put("trigger_percent", .005).put("fraction", .5)));
        exitPolicy.set("ratchet", object().put("type", "PERCENT").put("percent", .01));
        candidate.set("exit_policy", exitPolicy);
        candidate.remove("risk_contract");

        ObjectNode result = StrategyResearchDataV5.deriveBoundExecutionOutcome(request);
        assertThat(result.path("provenance").asText()).isEqualTo("DERIVED_FROM_CANONICAL_NORMALIZED_LIFECYCLE");
        assertThat(result.path("exit_policy").path("partial_exits")).hasSize(1);
        assertThat(result.path("exit_policy").path("trailing").path("type").asText()).isEqualTo("PERCENT");
        assertThat(result.path("lifecycle_result").path("target_price").asDouble()).isCloseTo(101,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.path("risk_denominator").asText()).isEqualTo("FROZEN_FIXED_RISK_BUDGET");
        assertThat(result.path("funding_settlements")).isEmpty();
    }

    @Test
    void lifecycleSourceSelectionAndSizingFallbackRemainDeterministic() {
        ObjectNode fromCandidateSpec = normalizedLifecycle(START);
        ObjectNode candidate = (ObjectNode) fromCandidateSpec.path("candidate");
        JsonNode life = candidate.remove("lifecycle");
        candidate.set("lifecycle_spec", life);
        candidate.put("lifecycle_engine", TradeLifecycleV5.LIFECYCLE_SCHEMA);
        ObjectNode first = StrategyResearchDataV5.deriveBoundExecutionOutcome(fromCandidateSpec);
        assertThat(first.path("traded").asBoolean()).isTrue();

        ObjectNode fromExecution = normalizedLifecycle(START);
        ObjectNode execution = (ObjectNode) fromExecution.path("execution");
        ObjectNode executionLifecycle = (ObjectNode) ((ObjectNode) fromExecution.path("candidate")).remove("lifecycle");
        execution.set("lifecycle", executionLifecycle);
        execution.put("lifecycle_engine", TradeLifecycleV5.LIFECYCLE_SCHEMA);
        ObjectNode second = StrategyResearchDataV5.deriveBoundExecutionOutcome(fromExecution);
        assertThat(second.path("traded").asBoolean()).isTrue();

        ObjectNode fromExecutionSpec = normalizedLifecycle(START);
        ObjectNode executionSpec = (ObjectNode) fromExecutionSpec.path("execution");
        ObjectNode executionLifecycleSpec = (ObjectNode) ((ObjectNode) fromExecutionSpec.path("candidate")).remove("lifecycle");
        executionSpec.set("lifecycle_spec", executionLifecycleSpec);
        executionSpec.put("lifecycle_engine", TradeLifecycleV5.LIFECYCLE_SCHEMA);
        ObjectNode third = StrategyResearchDataV5.deriveBoundExecutionOutcome(fromExecutionSpec);
        assertThat(third.path("traded").asBoolean()).isTrue();

        ObjectNode fixedNotional = normalizedLifecycle(START);
        ObjectNode fixedCandidate = (ObjectNode) fixedNotional.path("candidate");
        ((ObjectNode) fixedCandidate.path("lifecycle")).remove("sizing");
        fixedCandidate.set("sizing_contract", sizingContract("FIXED_NOTIONAL_USD", 100));
        ObjectNode fixedResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(fixedNotional);
        assertThat(fixedResult.path("quantity").asDouble()).isCloseTo(1, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(fixedResult.path("risk_denominator").asText()).isEqualTo("FROZEN_FIXED_RISK_BUDGET");

        ObjectNode executionRisk = normalizedLifecycle(START);
        ((ObjectNode) executionRisk.path("candidate")).remove("risk_contract");
        ObjectNode executionRiskResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(executionRisk);
        assertThat(executionRiskResult.path("risk_amount_usd").asDouble()).isEqualTo(100);

        ObjectNode declaredMatch = normalizedLifecycle(START);
        ((ObjectNode) declaredMatch.path("candidate")).set("sizing_contract", sizingContract("FIXED_NOTIONAL_USD", 100));
        ((ObjectNode) declaredMatch.path("candidate").path("lifecycle")).putObject("sizing")
                .put("mode", "FIXED_NOTIONAL_USD").put("notional_usd", 100);
        ObjectNode declaredMatchResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(declaredMatch);
        assertThat(declaredMatchResult.path("quantity").asDouble()).isEqualTo(1);

        ObjectNode candidateSizing = normalizedLifecycle(START);
        ((ObjectNode) candidateSizing.path("candidate").path("lifecycle")).remove("sizing");
        ((ObjectNode) candidateSizing.path("candidate")).set("sizing", object().put("mode", "FIXED_NOTIONAL_USD").put("notional_usd", 100));
        ObjectNode candidateSizingResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(candidateSizing);
        assertThat(candidateSizingResult.path("quantity").asDouble()).isEqualTo(1);

        ObjectNode executionSizing = normalizedLifecycle(START);
        ((ObjectNode) executionSizing.path("candidate").path("lifecycle")).remove("sizing");
        ((ObjectNode) executionSizing.path("execution")).set("sizing", object().put("mode", "FIXED_NOTIONAL_USD").put("notional_usd", 100));
        ObjectNode executionSizingResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(executionSizing);
        assertThat(executionSizingResult.path("quantity").asDouble()).isEqualTo(.01);

        ObjectNode executionFallback = normalizedLifecycle(START);
        ObjectNode fallbackCandidate = (ObjectNode) executionFallback.path("candidate");
        fallbackCandidate.remove("lifecycle");
        fallbackCandidate.put("lifecycle_engine", TradeLifecycleV5.LIFECYCLE_SCHEMA);
        ObjectNode fallbackExecution = (ObjectNode) executionFallback.path("execution");
        fallbackExecution.put("lifecycle_engine", TradeLifecycleV5.LIFECYCLE_SCHEMA)
                .put("max_lifecycle_ms", 2 * MINUTE).put("gap_policy", "OPEN");
        fallbackExecution.set("stop_spec", object().put("type", "PERCENT").put("value", .05));
        fallbackExecution.set("target_spec", object().put("type", "PERCENT").put("value", .01));
        fallbackExecution.set("partials", array().add(object().put("trigger_percent", .005).put("fraction", .5)));
        fallbackExecution.set("ratchet", object().put("type", "PERCENT").put("percent", .01));
        fallbackExecution.set("sizing", object().put("mode", "RISK_USD").put("risk_usd", 100));
        fallbackCandidate.remove("risk_contract");
        ObjectNode fallbackResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(executionFallback);
        assertThat(fallbackResult.path("exit_policy").path("partial_exits")).hasSize(1);
        assertThat(fallbackResult.path("exit_policy").path("trailing").path("type").asText()).isEqualTo("PERCENT");
    }

    @Test
    void normalizedContractsRejectConflictsAndUnboundSizingClaims() {
        ObjectNode riskConflict = normalizedLifecycle(START);
        ((ObjectNode) riskConflict.path("candidate").path("risk_contract")).put("budget_usd", 101);
        expect(riskConflict, "candidate/execution risk_contract values conflict");

        ObjectNode badRiskMode = normalizedLifecycle(START);
        ((ObjectNode) badRiskMode.path("candidate").path("risk_contract")).put("mode", "VOLATILITY");
        ((ObjectNode) badRiskMode.path("execution")).remove("risk_contract");
        expect(badRiskMode, "risk_contract is not a hash-bound fixed risk budget");

        ObjectNode badRiskHash = normalizedLifecycle(START);
        ((ObjectNode) badRiskHash.path("candidate").path("risk_contract")).put("precommit_sha256", "bad");
        ((ObjectNode) badRiskHash.path("execution")).remove("risk_contract");
        expect(badRiskHash, "risk_contract is not a hash-bound fixed risk budget");

        ObjectNode sizingConflict = normalizedLifecycle(START);
        ((ObjectNode) sizingConflict.path("candidate")).set("sizing_contract", sizingContract("FIXED_NOTIONAL_USD", 200));
        ((ObjectNode) sizingConflict.path("execution")).set("sizing_contract", sizingContract("FIXED_NOTIONAL_USD", 201));
        expect(sizingConflict, "candidate/execution sizing_contract values conflict");

        ObjectNode badSizingMode = normalizedLifecycle(START);
        ((ObjectNode) badSizingMode.path("candidate")).set("sizing_contract", sizingContract("VOLATILITY", 100));
        expect(badSizingMode, "sizing_contract is not evaluator-bound");

        ObjectNode badSizingHash = normalizedLifecycle(START);
        ObjectNode badSizing = sizingContract("FIXED_NOTIONAL_USD", 100);
        badSizing.put("evaluator_spec_sha256", "bad");
        ((ObjectNode) badSizingHash.path("candidate")).set("sizing_contract", badSizing);
        expect(badSizingHash, "sizing_contract is not evaluator-bound");

        ObjectNode declaredMismatch = normalizedLifecycle(START);
        ((ObjectNode) declaredMismatch.path("candidate")).set("sizing_contract", sizingContract("FIXED_NOTIONAL_USD", 200));
        expect(declaredMismatch, "lifecycle sizing disagrees with the evaluator-bound sizing_contract");

        ObjectNode zeroFrozen = normalizedLifecycle(START);
        ((ObjectNode) zeroFrozen.path("candidate")).set("sizing_contract", sizingContract("FIXED_NOTIONAL_USD", 0));
        ((ObjectNode) zeroFrozen.path("candidate").path("lifecycle")).remove("sizing");
        expect(zeroFrozen, "frozen sizing amount is invalid");
    }

    @Test
    void normalizedLifecycleSpecGuardsExposeSpecificReasons() {
        ObjectNode missingMax = normalizedLifecycle(START);
        ((ObjectNode) missingMax.path("candidate").path("lifecycle")).remove("max_lifecycle_ms");
        expect(missingMax, "mandatory maximum time stop is missing");

        ObjectNode badGap = normalizedLifecycle(START);
        ((ObjectNode) badGap.path("candidate").path("lifecycle")).put("gap_policy", "MARK");
        expect(badGap, "gap_policy must be OPEN or FAIL");

        ObjectNode badTrailing = normalizedLifecycle(START);
        ((ObjectNode) badTrailing.path("candidate").path("lifecycle")).set("trailing", object().put("type", "CHANNEL"));
        expect(badTrailing, "unsupported trailing type CHANNEL");

        ObjectNode badPercent = normalizedLifecycle(START);
        ((ObjectNode) badPercent.path("candidate").path("lifecycle")).set("trailing", object().put("type", "PERCENT").put("percent", 1));
        expect(badPercent, "trailing percent must be a fraction between 0 and 1");

        ObjectNode badAtr = normalizedLifecycle(START);
        ((ObjectNode) badAtr.path("candidate").path("lifecycle")).set("trailing", object().put("type", "ATR").put("multiple", 0));
        expect(badAtr, "trailing ATR multiple must be positive");

        ObjectNode badActivation = normalizedLifecycle(START);
        ((ObjectNode) badActivation.path("candidate").path("lifecycle")).set("trailing", object().put("type", "BREAK_EVEN").put("activation_r", 0));
        expect(badActivation, "trailing activation_r must be positive");

        ObjectNode badTarget = normalizedLifecycle(START);
        ObjectNode lifecycle = (ObjectNode) badTarget.path("candidate").path("lifecycle");
        lifecycle.remove("stop");
        lifecycle.set("sizing", object().put("mode", "FIXED_NOTIONAL_USD").put("notional_usd", 100));
        lifecycle.set("target", object().put("type", "R").put("value", 1));
        expect(badTarget, "R target requires positive multiple and stop");

        ObjectNode crossMargin = normalizedLifecycle(START);
        ((ObjectNode) crossMargin.path("candidate").path("lifecycle")).put("margin_mode", "CROSS");
        expect(crossMargin, "cross margin is not supported");

        ObjectNode spotShort = normalizedLifecycle(START);
        ((ObjectNode) spotShort.path("candidate")).put("direction", "short");
        expect(spotShort, "spot shorts are not supported");

        ObjectNode contextOnly = normalizedLifecycle(START);
        ((ObjectNode) contextOnly.path("candidate")).put("trade_scope", "CONTEXT_ONLY");
        expect(contextOnly, "CONTEXT_ONLY assets/predictors cannot produce execution");

        ObjectNode noBars = normalizedLifecycle(START);
        ((ObjectNode) noBars.path("execution")).remove("child_bars");
        expect(noBars, "lifecycle requires physical 1m bars");
    }

    @Test
    void normalizedOutcomeBoundariesAndCallerRiskClaimsFailClosed() {
        ObjectNode envelopeStart = normalizedLifecycle(START);
        envelopeStart.set("envelopeWindow", object().put("execution_start", iso(START + MINUTE))
                .put("execution_end", iso(START + 3 * MINUTE)));
        expect(envelopeStart, "path escapes frozen opportunity envelope");

        ObjectNode envelopeEnd = normalizedLifecycle(START);
        envelopeEnd.set("envelopeWindow", object().put("execution_start", iso(START))
                .put("execution_end", iso(START + MINUTE)));
        expect(envelopeEnd, "path escapes frozen opportunity envelope");

        ObjectNode callerRisk = normalizedLifecycle(START);
        ((ObjectNode) callerRisk.path("candidate")).put("risk_amount_usd", 99);
        expect(callerRisk, "caller risk amount conflicts with the frozen fixed-risk budget");

        ObjectNode executionCallerRisk = normalizedLifecycle(START);
        ((ObjectNode) executionCallerRisk.path("execution")).put("risk_amount_usd", 99);
        expect(executionCallerRisk, "caller risk amount conflicts with the frozen fixed-risk budget");

        ObjectNode inferred = normalizedLifecycle(START);
        ((ObjectNode) inferred.path("candidate")).remove("risk_contract");
        ((ObjectNode) inferred.path("execution")).remove("risk_contract");
        ObjectNode inferredResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(inferred);
        assertThat(inferredResult.path("risk_denominator").asText()).isEqualTo("DERIVED_STOP_DISTANCE");
        assertThat(inferredResult.path("risk_amount_usd").asDouble()).isEqualTo(100);

        ObjectNode noStopNotional = normalizedLifecycle(START);
        ObjectNode noStopLifecycle = (ObjectNode) noStopNotional.path("candidate").path("lifecycle");
        noStopLifecycle.remove("stop");
        noStopLifecycle.set("sizing", object().put("mode", "FIXED_NOTIONAL_USD").put("notional_usd", 100));
        ((ObjectNode) noStopNotional.path("candidate")).remove("risk_contract");
        ((ObjectNode) noStopNotional.path("execution")).remove("risk_contract");
        ObjectNode noStopResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(noStopNotional);
        assertThat(noStopResult.path("risk_denominator").asText()).isEqualTo("FIXED_NOTIONAL_OR_VOLATILITY");
        assertThat(noStopResult.path("risk_amount_usd").asDouble()).isEqualTo(100);
    }

    @Test
    void normalizedProjectionCoversTargetPartialAvailabilityAndTrailing() {
        ObjectNode target = normalizedLifecycle(START);
        ObjectNode targetLifecycle = (ObjectNode) target.path("candidate").path("lifecycle");
        targetLifecycle.set("target", object().put("type", "PERCENT").put("value", .005));
        ObjectNode targetResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(target);
        assertThat(targetResult.path("exit_reason").asText()).isEqualTo("TARGET");
        assertThat(targetResult.path("exit_time").asText()).isEqualTo(iso(START));
        assertThat(targetResult.path("exit_price").asDouble()).isCloseTo(100.5,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(targetResult.path("gross_pnl_usd").asDouble()).isCloseTo(10,
                org.assertj.core.data.Offset.offset(1e-12));

        ObjectNode partial = normalizedLifecycle(START);
        ObjectNode partialLifecycle = (ObjectNode) partial.path("candidate").path("lifecycle");
        partialLifecycle.set("target", object().put("type", "PERCENT").put("value", .01));
        partialLifecycle.set("partial_exits", array().add(object().put("trigger_percent", .005).put("fraction", .5)));
        ObjectNode partialResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(partial);
        assertThat(partialResult.path("exit_policy").path("partial_exits")).hasSize(1);
        assertThat(partialResult.path("lifecycle_result").path("exits")).hasSize(2);
        assertThat(partialResult.path("lifecycle_result").path("exits").get(0).path("reason").asText()).isEqualTo("PARTIAL_TARGET");

        ObjectNode availability = normalizedLifecycle(START);
        ObjectNode availabilityLifecycle = (ObjectNode) availability.path("candidate").path("lifecycle");
        availabilityLifecycle.put("fill_availability_policy", "FINAL_IN_HORIZON_BAR_CLOSE_V001")
                .put("max_lifecycle_ms", 2 * MINUTE);
        availabilityLifecycle.set("target", object().put("type", "PERCENT").put("value", .005));
        ObjectNode availabilityResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(availability);
        assertThat(availabilityResult.path("lifecycle_result").path("entry_fill").path("availability_time").asText()).isEqualTo(iso(START));
        assertThat(availabilityResult.path("lifecycle_result").path("exits").get(0).path("availability_time").asText()).isEqualTo(iso(START + MINUTE));

        ObjectNode trailing = normalizedLifecycle(START);
        ObjectNode trailingLifecycle = (ObjectNode) trailing.path("candidate").path("lifecycle");
        trailingLifecycle.set("trailing", object().put("type", "PERCENT").put("percent", .01));
        ObjectNode trailingResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(trailing);
        assertThat(trailingResult.path("exit_policy").path("trailing").path("type").asText()).isEqualTo("PERCENT");
        assertThat(trailingResult.path("lifecycle_result").path("effective_trailing_from").asText()).isEqualTo(iso(START + MINUTE));
    }

    @Test
    void normalizedDerivativeProjectionBindsFundingAndInstrumentIdentity() {
        ObjectNode perpetual = normalizedLifecycle(START);
        derivativeIdentity(perpetual, "BINANCE_USDM_PERPETUAL", "BTCUSDT", "short");
        ObjectNode perpetualCandidate = (ObjectNode) perpetual.path("candidate");
        perpetualCandidate.put("instrument_type", "perpetual").put("direction", "short");
        ArrayNode funding = ((ObjectNode) perpetual.path("execution")).putArray("funding_rows");
        funding.add(object().put("event_time", START + MINUTE).put("event_id", "funding-1")
                .put("rate", .01).put("mark_price", 101));
        ObjectNode perpetualResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(perpetual);
        assertThat(perpetualResult.path("instrument").asText()).isEqualTo("BINANCE_USDM_PERPETUAL");
        assertThat(perpetualResult.path("direction").asText()).isEqualTo("short");
        assertThat(perpetualResult.path("signed_quantity").asDouble()).isEqualTo(-20);
        assertThat(perpetualResult.path("funding_settlements")).hasSize(1);
        assertThat(perpetualResult.path("funding_settlements").get(0).path("event_id").asText()).isEqualTo("funding-1");
        assertThat(perpetualResult.path("funding_pnl_usd").asDouble()).isCloseTo(20.2,
                org.assertj.core.data.Offset.offset(1e-12));

        ObjectNode dated = normalizedLifecycle(START);
        derivativeIdentity(dated, "BINANCE_USDM_DATED_FUTURE", "BTCUSD_20260101", "long");
        ObjectNode datedCandidate = (ObjectNode) dated.path("candidate");
        datedCandidate.put("instrument_type", "dated_future").put("expiry_time", iso(START + MINUTE));
        ObjectNode datedResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(dated);
        assertThat(datedResult.path("instrument").asText()).isEqualTo("BINANCE_USDM_DATED_FUTURE");
        assertThat(datedResult.path("exit_reason").asText()).isEqualTo("EXPIRY");
        assertThat(datedResult.path("exit_time").asText()).isEqualTo(iso(START + MINUTE));
    }

    private static void expect(ObjectNode request, String message) {
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(request))
                .hasMessageContaining(message);
    }

    private static ObjectNode sizingContract(String mode, double amount) {
        return object().put("mode", mode).put("notional_usd", amount)
                .put("precommit_sha256", H).put("evaluator_spec_sha256", H);
    }

    private static ObjectNode normalizedLifecycle(long decision) {
        ObjectNode feature = identity("normalized", "normalized-episode", decision).put("signal_eligible", true);
        ObjectNode label = identity("normalized", "normalized-episode", decision)
                .put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY").put("decision_timeframe", "4h")
                .put("resolution_ceiling_time", iso(decision + 2 * MINUTE));
        ObjectNode execution = identity("normalized", "normalized-episode", decision)
                .put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY").put("decision_timeframe", "4h")
                .put("interval_ms", MINUTE).put("direction", "long");
        ArrayNode bars = execution.putArray("child_bars");
        bars.add(bar(decision, 100)).add(bar(decision + MINUTE, 101)).add(bar(decision + 2 * MINUTE, 102));
        ObjectNode candidate = object().put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY")
                .put("decision_timeframe", "4h").put("direction", "long").put("instrument_type", "spot");
        ObjectNode lifecycle = candidate.putObject("lifecycle").put("max_lifecycle_ms", 2 * MINUTE).put("gap_policy", "OPEN");
        lifecycle.putObject("stop").put("type", "PERCENT").put("value", .05);
        lifecycle.putObject("sizing").put("mode", "RISK_USD").put("risk_usd", 100);
        candidate.set("risk_contract", riskContract(100));
        execution.set("risk_contract", riskContract(100));
        ObjectNode request = object();
        request.set("feature", feature); request.set("label", label); request.set("execution", execution);
        request.set("candidate", candidate); request.put("fixtureOnly", true);
        return request;
    }

    private static ObjectNode riskContract(double budget) {
        return object().put("mode", "FIXED_RISK_BUDGET_USD").put("budget_usd", budget)
                .put("precommit_sha256", H).put("evaluator_spec_sha256", H);
    }

    private static void derivativeIdentity(ObjectNode request, String instrument, String symbol, String direction) {
        for (String name : new String[]{"feature", "label", "execution"}) {
            ((ObjectNode) request.path(name)).put("instrument", instrument).put("symbol", symbol).put("direction", direction);
        }
    }

    private static ObjectNode identity(String signal, String episode, long decision) {
        return object().put("asset", "btc").put("venue", "BINANCE").put("instrument", "BINANCE_SPOT")
                .put("symbol", "BTCUSDT").put("decision_time", iso(decision))
                .put("signal_id", signal).put("episode_id", episode);
    }

    private static ObjectNode bar(long event, double open) {
        return object().put("event_time", event).put("availability_time", event + MINUTE - 1)
                .put("open", open).put("high", open + 1).put("low", open - 1).put("close", open)
                .put("is_closed", true);
    }

    private static String iso(long value) { return Instant.ofEpochMilli(value).toString().replace("Z", ".000Z"); }
    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }
    private static ArrayNode array() { return JsonHashes.mapper().createArrayNode(); }
}

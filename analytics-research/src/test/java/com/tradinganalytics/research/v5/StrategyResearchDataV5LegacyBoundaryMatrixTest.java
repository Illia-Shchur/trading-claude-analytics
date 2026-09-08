package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Boundary contracts for the public legacy outcome evaluator. */
final class StrategyResearchDataV5LegacyBoundaryMatrixTest {
    private static final String H = "a".repeat(64);
    private static final long START = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private static final long MINUTE = 60_000L;

    @Test
    void shortPerpetualOutcomeBindsFundingAndAllExecutionCosts() {
        ObjectNode request = shortPerpetual();
        ObjectNode result = StrategyResearchDataV5.deriveBoundExecutionOutcome(request);

        assertThat(result.path("direction").asText()).isEqualTo("short");
        assertThat(result.path("signed_quantity").asDouble()).isCloseTo(-2,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.path("entry_price").asDouble()).isCloseTo(99.7,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.path("exit_price").asDouble()).isCloseTo(98.294,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.path("gross_pnl_usd").asDouble()).isCloseTo(4,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.path("fees_usd").asDouble()).isCloseTo(.395988,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.path("slippage_usd").asDouble()).isCloseTo(.396,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.path("capacity_debit_usd").asDouble()).isCloseTo(.792,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.path("funding_pnl_usd").asDouble()).isCloseTo(.198,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.path("net_pnl_usd").asDouble()).isCloseTo(2.614012,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.path("net_r").asDouble()).isCloseTo(.02614012,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.path("funding_settlements")).hasSize(1);
        assertThat(result.path("liquidation_model").path("method").asText())
                .isEqualTo("DYNAMIC_ENTRY_MARGIN_EQUITY");

        ObjectNode longDerivative = shortPerpetual();
        ((ObjectNode) longDerivative.path("candidate")).put("direction", "long");
        ((ObjectNode) longDerivative.path("execution")).put("direction", "long");
        ObjectNode longResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(longDerivative);
        assertThat(longResult.path("direction").asText()).isEqualTo("long");
        assertThat(longResult.path("gross_pnl_usd").asDouble()).isCloseTo(-4,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(longResult.path("funding_pnl_usd").asDouble()).isCloseTo(-.198,
                org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void gapFillAndAvailabilityBoundariesFailClosed() {
        ObjectNode gapFill = spotTarget(true);
        ObjectNode gapResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(gapFill);
        assertThat(gapResult.path("exit_reason").asText()).isEqualTo("STOP_GAP_OPEN");
        assertThat(gapResult.path("gap_fill").asBoolean()).isTrue();
        assertThat(gapResult.path("raw_exit_price").asDouble()).isCloseTo(94,
                org.assertj.core.data.Offset.offset(1e-12));

        ObjectNode shortTarget = shortPerpetual();
        ((ObjectNode) shortTarget.path("candidate")).set("exit_policy", object()
                .put("type", "TARGET_STOP").put("collision_policy", "ADVERSE_STOP_FIRST")
                .put("stop_price", 105).put("target_price", 95));
        ((ObjectNode) shortTarget.path("execution").path("child_bars").get(1))
                .put("open", 94).put("high", 96).put("low", 93).put("close", 94);
        ObjectNode shortTargetResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(shortTarget);
        assertThat(shortTargetResult.path("exit_reason").asText()).isEqualTo("TARGET_GAP_OPEN");
        assertThat(shortTargetResult.path("gap_fill").asBoolean()).isTrue();
        assertThat(shortTargetResult.path("raw_exit_price").asDouble()).isCloseTo(94,
                org.assertj.core.data.Offset.offset(1e-12));

        ObjectNode gapFail = spotTarget(true);
        replaceExecutionModel(gapFail, "FAIL", 2, 1);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(gapFail))
                .hasMessageContaining("gap through a target/stop under FAIL gap policy");

        ObjectNode staleBar = spotTarget(true);
        ((ObjectNode) staleBar.path("execution").path("child_bars").get(0))
                .put("availability_time", START + MINUTE - 2_000L);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(staleBar))
                .hasMessageContaining("bar available before close");

        ObjectNode staleMark = shortPerpetual();
        ((ObjectNode) staleMark.path("execution").path("mark_bars").get(1))
                .put("availability_time", START + MINUTE + MINUTE - 2_000L);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(staleMark))
                .hasMessageContaining("derivative mark path contains a bar available before close");
    }

    @Test
    void quantityAndNotionalFloorsAreFrozenExchangeBounds() {
        ObjectNode belowQuantity = spotTimeDerived();
        ((ObjectNode) belowQuantity.path("metadata").path("contract_spec").path("records").get(0))
                .put("min_qty", 1);
        rehashMetadata(belowQuantity, "contract_spec");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(belowQuantity))
                .hasMessageContaining("derived execution quantity is below the frozen minimum quantity");

        ObjectNode belowNotional = spotTimeDerived();
        ((ObjectNode) belowNotional.path("metadata").path("contract_spec").path("records").get(0))
                .put("min_notional", 1_000);
        rehashMetadata(belowNotional, "contract_spec");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(belowNotional))
                .hasMessageContaining("derived execution quantity is below the frozen minimum notional");

        ObjectNode aboveQuantity = spotTimeDerived();
        ((ObjectNode) aboveQuantity.path("candidate").path("sizing_contract")).put("max_quantity", .001);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(aboveQuantity))
                .hasMessageContaining("derived execution quantity exceeds the frozen maximum quantity");

        ObjectNode aboveNotional = spotTimeDerived();
        ((ObjectNode) aboveNotional.path("candidate").path("sizing_contract")).put("max_notional_usd", .5);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(aboveNotional))
                .hasMessageContaining("derived execution quantity exceeds the frozen maximum notional");

        ObjectNode conflictingStep = spotTimeDerived();
        ((ObjectNode) conflictingStep.path("candidate").path("sizing_contract")).put("quantity_step", .015);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(conflictingStep))
                .hasMessageContaining("quantity_step may not loosen or conflict with the frozen exchange step_size");
    }

    @Test
    void fundingCoverageAndExecutionCostModelsCannotBeForged() {
        ObjectNode incompleteFunding = shortPerpetual();
        ObjectNode funding = (ObjectNode) incompleteFunding.path("metadata").path("funding_identity");
        funding.set("coverage", object().put("complete", false).put("coverage_mode", "EVENT_SEQUENCE"));
        rehashMetadata(incompleteFunding, "funding_identity");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(incompleteFunding))
                .hasMessageContaining("perpetual derivative funding coverage is incomplete");

        ObjectNode invalidModel = spotTimeDerived();
        replaceExecutionModel(invalidModel, "FILL_AT_OPEN", -1, 1);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(invalidModel))
                .hasMessageContaining("execution slippage/impact model is invalid");
    }

    @Test
    void entryExitAndPathBoundariesUseSpecificRejectionReasons() {
        ObjectNode duplicate = spotTimeDerived();
        ((ObjectNode) duplicate.path("execution").path("child_bars").get(1))
                .put("event_time", START);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(duplicate))
                .hasMessageContaining("execution path is not dense one-minute data");

        ObjectNode delayedWithoutFrozenDelay = spotTimeDerived();
        ((ObjectNode) delayedWithoutFrozenDelay.path("candidate"))
                .put("entry_policy", "DELAYED_BAR_OPEN").put("entry_delay_bars", 0);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(delayedWithoutFrozenDelay))
                .hasMessageContaining("delayed-bar entry policy requires a positive frozen entry_delay_bars");

        ObjectNode mismatchedLabelEntry = spotTimeDerived();
        ((ObjectNode) mismatchedLabelEntry.path("label")).put("entry_time", iso(START + MINUTE));
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(mismatchedLabelEntry))
                .hasMessageContaining("label entry time does not match frozen next-bar policy");

        ObjectNode invalidDirection = spotTimeDerived();
        ((ObjectNode) invalidDirection.path("candidate")).put("direction", "sideways");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(invalidDirection))
                .hasMessageContaining("execution direction is invalid");

        ObjectNode unsupportedInstrument = spotTimeDerived();
        for (String role : new String[]{"feature", "label", "execution"}) {
            ((ObjectNode) unsupportedInstrument.path(role)).put("instrument", "UNKNOWN_INSTRUMENT");
        }
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(unsupportedInstrument))
                .hasMessageContaining("unsupported execution instrument UNKNOWN_INSTRUMENT");

        ObjectNode unsupportedExit = spotTimeDerived();
        ((ObjectNode) unsupportedExit.path("candidate").path("exit_policy")).put("type", "TRAILING_STOP");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(unsupportedExit))
                .hasMessageContaining("unsupported exit policy TRAILING_STOP");

        ObjectNode envelopeEscape = spotTimeDerived();
        envelopeEscape.set("envelopeWindow", object().put("execution_start", iso(START + MINUTE))
                .put("execution_end", iso(START + 2 * MINUTE)));
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(envelopeEscape))
                .hasMessageContaining("outcome path escapes frozen opportunity envelope");

        ObjectNode truncated = spotTimeDerived();
        ((ArrayNode) truncated.path("execution").path("child_bars")).remove(2);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(truncated))
                .hasMessageContaining("execution path is truncated or contains pre-entry bars");

        ObjectNode invalidResolutionClose = spotTimeDerived();
        ((ObjectNode) invalidResolutionClose.path("execution").path("child_bars").get(2)).put("close", 0);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(invalidResolutionClose))
                .hasMessageContaining("execution path lacks exact policy resolution bar");

        ObjectNode defaultExit = spotTimeDerived();
        ((ObjectNode) defaultExit.path("candidate")).remove("exit_policy");
        ObjectNode defaultExitResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(defaultExit);
        assertThat(defaultExitResult.path("exit_reason").asText()).isEqualTo("TIME_STOP");

        ObjectNode noMetadata = spotTimeDerived();
        noMetadata.remove("metadata");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(noMetadata))
                .hasMessageContaining("CONTRACT_SPEC metadata is not bound");

        ObjectNode shortSpot = spotTimeDerived();
        ((ObjectNode) shortSpot.path("candidate")).put("direction", "short");
        ((ObjectNode) shortSpot.path("execution")).put("direction", "short");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(shortSpot))
                .hasMessageContaining("short BINANCE_SPOT execution is not supported");

        ObjectNode partialExit = spotTimeDerived();
        ((ObjectNode) partialExit.path("candidate")).putArray("partials").add(50);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(partialExit))
                .hasMessageContaining("partial and ratchet exits require an explicitly bound execution implementation");

        ObjectNode missingOutagePolicy = spotTimeDerived();
        replaceExecutionModel(missingOutagePolicy, "FILL_AT_OPEN", 2, 1);
        ((ObjectNode) missingOutagePolicy.path("metadata").path("execution_model").path("records").get(0))
                .remove("outage_policy");
        rehashMetadata(missingOutagePolicy, "execution_model");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(missingOutagePolicy))
                .hasMessageContaining("unsupported outage policy ?");

        ObjectNode missingGapPolicy = spotTimeDerived();
        replaceExecutionModel(missingGapPolicy, "FILL_AT_OPEN", 2, 1);
        ((ObjectNode) missingGapPolicy.path("metadata").path("execution_model").path("records").get(0))
                .remove("gap_policy");
        rehashMetadata(missingGapPolicy, "execution_model");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(missingGapPolicy))
                .hasMessageContaining("unsupported gap policy ?");

        ObjectNode negativeImpact = spotTimeDerived();
        replaceExecutionModel(negativeImpact, "FILL_AT_OPEN", 2, -1);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(negativeImpact))
                .hasMessageContaining("execution slippage/impact model is invalid");

        ObjectNode delayedEntryMissing = spotTimeDerived();
        ((ObjectNode) delayedEntryMissing.path("candidate")).put("entry_policy", "DELAYED_BAR_OPEN")
                .put("entry_delay_bars", 3);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(delayedEntryMissing))
                .hasMessageContaining("execution path lacks the exact contiguous next-bar entry");

        ObjectNode executionPartial = spotTimeDerived();
        ((ObjectNode) executionPartial.path("candidate")).remove("exit_policy");
        ((ObjectNode) executionPartial.path("execution")).set("exit_policy",
                object().put("type", "TIME_STOP").put("partial", true));
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(executionPartial))
                .hasMessageContaining("partial and ratchet exits require an explicitly bound execution implementation");

        ObjectNode invalidMarkOrdering = shortPerpetual();
        ((ObjectNode) invalidMarkOrdering.path("execution").path("mark_bars").get(1))
                .put("mark_high", 98).put("mark_low", 99);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(invalidMarkOrdering))
                .hasMessageContaining("separate dense mark-price path aligned to trade bars");

        ObjectNode misalignedMarkEvent = shortPerpetual();
        ((ObjectNode) misalignedMarkEvent.path("execution").path("mark_bars").get(1))
                .put("event_time", START + 2 * MINUTE);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(misalignedMarkEvent))
                .hasMessageContaining("separate dense mark-price path aligned to trade bars");

        ObjectNode envelopeResolutionEscape = spotTimeDerived();
        envelopeResolutionEscape.set("envelopeWindow", object().put("execution_start", iso(START))
                .put("execution_end", iso(START + MINUTE)));
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(envelopeResolutionEscape))
                .hasMessageContaining("outcome path escapes frozen opportunity envelope");

        ObjectNode preEntryPath = spotTimeDerived();
        ((ObjectNode) preEntryPath.path("candidate")).put("entry_policy", "DELAYED_BAR_OPEN")
                .put("entry_delay_bars", 1);
        ((ArrayNode) preEntryPath.path("execution").path("child_bars")).remove(0);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(preEntryPath))
                .hasMessageContaining("execution path is truncated or contains pre-entry bars");
    }

    @Test
    void sizingLifecycleAndRiskContractsRejectUnboundOrConflictingClaims() {
        ObjectNode legacyBars = spotTimeDerived();
        ObjectNode legacyBarsCandidate = (ObjectNode) legacyBars.path("candidate");
        legacyBarsCandidate.remove("max_lifecycle_ms");
        legacyBarsCandidate.put("max_lifecycle_bars", 2);
        ((ObjectNode) legacyBars.path("execution")).remove("max_lifecycle_ms");
        ObjectNode legacyBarsResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(legacyBars);
        assertThat(legacyBarsResult.path("exit_reason").asText()).isEqualTo("TIME_STOP");

        ObjectNode invalidLegacyBars = spotTimeDerived();
        ObjectNode invalidLegacyBarsCandidate = (ObjectNode) invalidLegacyBars.path("candidate");
        invalidLegacyBarsCandidate.remove("max_lifecycle_ms");
        invalidLegacyBarsCandidate.put("max_lifecycle_bars", 0);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(invalidLegacyBars))
                .hasMessageContaining("maximum lifecycle bars is invalid");

        ObjectNode missingRisk = spotTimeDerived();
        ((ObjectNode) missingRisk.path("candidate")).remove("risk_contract");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(missingRisk))
                .hasMessageContaining("authoritative execution requires a hash-bound sizing contract");

        ObjectNode missingLifecycle = spotTimeDerived();
        ((ObjectNode) missingLifecycle.path("candidate")).remove("max_lifecycle_ms");
        ((ObjectNode) missingLifecycle.path("execution")).remove("max_lifecycle_ms");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(missingLifecycle))
                .hasMessageContaining("maximum lifecycle must be explicitly bound in milliseconds");

        ObjectNode invalidSizingMode = spotTimeDerived();
        ((ObjectNode) invalidSizingMode.path("candidate").path("sizing_contract")).put("mode", "VARIABLE_NOTIONAL");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(invalidSizingMode))
                .hasMessageContaining("time-stop sizing requires an explicit fixed-notional contract");

        ObjectNode missingSizing = spotTimeDerived();
        ((ObjectNode) missingSizing.path("candidate")).remove("sizing_contract");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(missingSizing))
                .hasMessageContaining("time-stop sizing requires an explicit fixed-notional contract");

        ObjectNode invalidNotional = spotTimeDerived();
        ((ObjectNode) invalidNotional.path("candidate").path("sizing_contract")).put("notional_usd", 0);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(invalidNotional))
                .hasMessageContaining("fixed-notional sizing contract is invalid");

        ObjectNode invalidStep = spotTimeDerived();
        ((ObjectNode) invalidStep.path("candidate").path("sizing_contract")).put("quantity_step", 0);
        ((ObjectNode) invalidStep.path("metadata").path("contract_spec").path("records").get(0))
                .remove("step_size");
        rehashMetadata(invalidStep, "contract_spec");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(invalidStep))
                .hasMessageContaining("sizing quantity_step is invalid");

        ObjectNode candidateStepTooSmall = spotTimeDerived();
        ((ObjectNode) candidateStepTooSmall.path("candidate").path("sizing_contract"))
                .put("quantity_step", .005);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(candidateStepTooSmall))
                .hasMessageContaining("quantity_step may not loosen or conflict with the frozen exchange step_size");

        ObjectNode roundsToZero = spotTimeDerived();
        ((ObjectNode) roundsToZero.path("candidate").path("sizing_contract")).put("notional_usd", .001);
        ObjectNode contract = (ObjectNode) roundsToZero.path("metadata").path("contract_spec").path("records").get(0);
        contract.remove("min_qty");
        contract.remove("min_notional");
        rehashMetadata(roundsToZero, "contract_spec");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(roundsToZero))
                .hasMessageContaining("frozen sizing contract rounds execution quantity to zero");

        ObjectNode targetWrongMode = spotTarget(false);
        ((ObjectNode) targetWrongMode.path("candidate").path("risk_contract"))
                .put("mode", "FIXED_NOTIONAL_USD");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(targetWrongMode))
                .hasMessageContaining("target-stop sizing requires a fixed-risk-budget contract");

        ObjectNode targetBadBudget = spotTarget(false);
        ((ObjectNode) targetBadBudget.path("candidate").path("risk_contract")).put("budget_usd", 0);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(targetBadBudget))
                .hasMessageContaining("target-stop sizing contract or stop distance is invalid");

        ObjectNode targetZeroDistance = spotTarget(false);
        ((ObjectNode) targetZeroDistance.path("candidate").path("exit_policy")).put("stop_price", 100.03);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(targetZeroDistance))
                .hasMessageContaining("target-stop sizing contract or stop distance is invalid");

        ObjectNode targetRiskClaim = spotTarget(false);
        ((ObjectNode) targetRiskClaim.path("execution")).put("risk_amount_usd", 999);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(targetRiskClaim))
                .hasMessageContaining("caller-supplied risk amount does not match the authoritative stop-distance denominator");

        ObjectNode timeRiskClaim = spotTimeDerived();
        ((ObjectNode) timeRiskClaim.path("candidate")).put("risk_amount_usd", 999);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(timeRiskClaim))
                .hasMessageContaining("caller-supplied risk amount disagrees with the frozen fixed-risk budget");

        ObjectNode timeBadBudget = spotTimeDerived();
        ((ObjectNode) timeBadBudget.path("candidate").path("risk_contract")).put("budget_usd", 0);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(timeBadBudget))
                .hasMessageContaining("fixed-risk-budget denominator is invalid");

        ObjectNode invalidSuppliedQuantity = spotTimeDerived();
        ((ObjectNode) invalidSuppliedQuantity.path("execution")).put("quantity", 0);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(invalidSuppliedQuantity))
                .hasMessageContaining("execution quantity is invalid");

        ObjectNode evaluatorBound = spotTimeDerived();
        ObjectNode evaluator = object().put("precommit_sha256", H).put("content_sha256", H);
        ObjectNode evaluatorExecution = object();
        evaluatorExecution.set("risk_convention", riskContract(100));
        evaluator.set("execution_contract", evaluatorExecution);
        evaluatorBound.set("evaluatorSpec", evaluator);
        ObjectNode evaluatorResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(evaluatorBound);
        assertThat(evaluatorResult.path("risk_denominator").asText()).isEqualTo("FROZEN_FIXED_RISK_BUDGET");

        ObjectNode evaluatorConflict = evaluatorBound.deepCopy();
        ((ObjectNode) evaluatorConflict.path("evaluatorSpec").path("execution_contract")
                .path("risk_convention")).put("budget_usd", 101);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(evaluatorConflict))
                .hasMessageContaining("fixed-risk-budget contract is not bound to the verified evaluator spec");

        ObjectNode noExchangeStep = spotTimeDerived();
        ObjectNode noExchangeStepContract = (ObjectNode) noExchangeStep.path("metadata").path("contract_spec")
                .path("records").get(0);
        noExchangeStepContract.remove("step_size");
        noExchangeStepContract.remove("lot_step");
        noExchangeStepContract.remove("quantity_step");
        rehashMetadata(noExchangeStep, "contract_spec");
        ObjectNode noExchangeStepResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(noExchangeStep);
        assertThat(noExchangeStepResult.path("quantity").asDouble()).isCloseTo(.01,
                org.assertj.core.data.Offset.offset(1e-12));

        ObjectNode noMaximumNotional = spotTimeDerived();
        ((ObjectNode) noMaximumNotional.path("metadata").path("contract_spec").path("records").get(0))
                .remove("max_notional");
        rehashMetadata(noMaximumNotional, "contract_spec");
        ObjectNode noMaximumNotionalResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(noMaximumNotional);
        assertThat(noMaximumNotionalResult.path("quantity").asDouble()).isCloseTo(.01,
                org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void metadataAndDerivativeBoundariesRemainAuthoritative() {
        ObjectNode badMultiplier = spotTarget(false);
        ((ObjectNode) badMultiplier.path("metadata").path("contract_spec").path("records").get(0))
                .put("contract_multiplier", 0);
        rehashMetadata(badMultiplier, "contract_spec");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(badMultiplier))
                .hasMessageContaining("contract multiplier is invalid");

        ObjectNode expiredContract = spotTarget(false);
        ((ObjectNode) expiredContract.path("metadata").path("contract_spec").path("records").get(0))
                .put("expiry", iso(START + MINUTE));
        rehashMetadata(expiredContract, "contract_spec");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(expiredContract))
                .hasMessageContaining("execution path extends beyond contract expiry");

        ObjectNode invalidFees = spotTimeDerived();
        ((ObjectNode) invalidFees.path("metadata").path("fee_schedule").path("records").get(0))
                .put("taker_fee_rate", -1);
        rehashMetadata(invalidFees, "fee_schedule");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(invalidFees))
                .hasMessageContaining("effective fee schedule rates are invalid");

        ObjectNode unsupportedOutage = spotTimeDerived();
        replaceExecutionModel(unsupportedOutage, "FILL_AT_OPEN", 2, 1);
        ((ObjectNode) unsupportedOutage.path("metadata").path("execution_model").path("records").get(0))
                .put("outage_policy", "OPEN");
        rehashMetadata(unsupportedOutage, "execution_model");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(unsupportedOutage))
                .hasMessageContaining("unsupported outage policy OPEN");

        ObjectNode unsupportedGap = spotTimeDerived();
        replaceExecutionModel(unsupportedGap, "MARKET", 2, 1);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(unsupportedGap))
                .hasMessageContaining("unsupported gap policy MARKET");

        ObjectNode invalidTarget = spotTarget(false);
        ((ObjectNode) invalidTarget.path("candidate").path("exit_policy")).put("stop_price", 0);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(invalidTarget))
                .hasMessageContaining("target/stop exit policy is invalid");

        ObjectNode invalidTargetPrice = spotTarget(false);
        ((ObjectNode) invalidTargetPrice.path("candidate").path("exit_policy")).put("target_price", 0);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(invalidTargetPrice))
                .hasMessageContaining("target/stop exit policy is invalid");

        ObjectNode invalidCollision = spotTarget(false);
        ((ObjectNode) invalidCollision.path("candidate").path("exit_policy"))
                .put("collision_policy", "TARGET_FIRST");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(invalidCollision))
                .hasMessageContaining("only ADVERSE_STOP_FIRST OHLC collision policy is supported");

        ObjectNode missingContract = spotTimeDerived();
        ((ObjectNode) missingContract.path("metadata")).remove("contract_spec");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(missingContract))
                .hasMessageContaining("CONTRACT_SPEC metadata is not bound");

        ObjectNode missingMargin = shortPerpetual();
        ((ObjectNode) missingMargin.path("metadata")).remove("margin");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(missingMargin))
                .hasMessageContaining("MARGIN metadata is not bound");

        ObjectNode invalidMarkRange = shortPerpetual();
        ((ObjectNode) invalidMarkRange.path("execution").path("mark_bars").get(1))
                .put("mark_low", 0);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(invalidMarkRange))
                .hasMessageContaining("separate dense mark-price path aligned to trade bars");

        ObjectNode marginMaxFallback = shortPerpetual();
        ((ObjectNode) marginMaxFallback.path("metadata").path("contract_spec").path("records").get(0))
                .remove("max_leverage");
        rehashMetadata(marginMaxFallback, "contract_spec");
        ObjectNode marginFallbackResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(marginMaxFallback);
        assertThat(marginFallbackResult.path("leverage").asDouble()).isCloseTo(2,
                org.assertj.core.data.Offset.offset(1e-12));

        ObjectNode collateralFallback = shortPerpetual();
        ((ObjectNode) collateralFallback.path("execution")).remove("collateral_usd");
        ObjectNode collateralFallbackResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(collateralFallback);
        assertThat(collateralFallbackResult.path("collateral_used").asDouble()).isCloseTo(99.7,
                org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void fundingAndMarginLifecycleRejectTamperedDerivativeInputs() {
        ObjectNode duplicateFunding = shortPerpetual();
        ObjectNode duplicateReceipt = (ObjectNode) duplicateFunding.path("metadata").path("funding_identity");
        ArrayNode duplicateRecords = duplicateReceipt.withArray("records");
        ObjectNode duplicateRecord = ((ObjectNode) shortPerpetual().path("metadata").path("funding_identity")
                .path("records").get(0)).deepCopy();
        duplicateRecord.put("event_id", "fund-duplicate");
        duplicateRecords.add(duplicateRecord);
        rehashMetadata(duplicateFunding, "funding_identity");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(duplicateFunding))
                .hasMessageContaining("derivative funding lifecycle has missing, extra, or duplicate event identities");

        ObjectNode marginMismatch = shortPerpetual();
        ((ObjectNode) marginMismatch.path("execution")).put("margin_mode", "CROSS");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(marginMismatch))
                .hasMessageContaining("derivative margin mode/tier is not bound");

        ObjectNode leverageTooHigh = shortPerpetual();
        ((ObjectNode) leverageTooHigh.path("execution")).put("leverage", 20);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(leverageTooHigh))
                .hasMessageContaining("derivative leverage exceeds the bound contract tier");

        ObjectNode badCollateral = shortPerpetual();
        ((ObjectNode) badCollateral.path("execution")).put("collateral_usd", 1);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(badCollateral))
                .hasMessageContaining("derivative collateral, maintenance margin, leverage, or notional is invalid");

        ObjectNode staticLiquidation = shortPerpetual();
        ObjectNode liquidation = metadata("LIQUIDATION", "BINANCE_USDM_PERPETUAL",
                object().put("liquidation_price", 50));
        ((ObjectNode) staticLiquidation.path("metadata")).set("liquidation", liquidation);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(staticLiquidation))
                .hasMessageContaining("static liquidation metadata is stress-only");

        ObjectNode liquidationBoundary = shortPerpetual();
        ((ObjectNode) liquidationBoundary.path("execution").path("mark_bars").get(1))
                .put("mark_high", 200);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(liquidationBoundary))
                .hasMessageContaining("dynamically derived maintenance margin/liquidation boundary");
    }

    @Test
    void datedFutureRequiresBoundExpiryAndExplicitlyNonApplicableFunding() {
        ObjectNode valid = datedFuture();
        ObjectNode result = StrategyResearchDataV5.deriveBoundExecutionOutcome(valid);
        assertThat(result.path("instrument").asText()).isEqualTo("BINANCE_USDM_DATED_FUTURE");
        assertThat(result.path("funding_pnl_usd").asDouble()).isCloseTo(0,
                org.assertj.core.data.Offset.offset(1e-12));

        ObjectNode wrongFunding = datedFuture();
        ((ObjectNode) wrongFunding.path("metadata")).set("funding_identity",
                unavailableMetadata("FUNDING_IDENTITY", "MISSING_COVERAGE"));
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(wrongFunding))
                .hasMessageContaining("dated futures must declare funding as NOT_APPLICABLE");

        ObjectNode expired = datedFuture();
        ObjectNode expiry = (ObjectNode) expired.path("metadata").path("expiry");
        ((ObjectNode) expiry.path("records").get(0)).put("expiry", iso(START + MINUTE));
        rehashMetadata(expired, "expiry");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(expired))
                .hasMessageContaining("dated future execution path extends beyond bound settlement expiry");
    }

    private static ObjectNode shortPerpetual() {
        ObjectNode feature = identity("short", "short-episode", START, "BINANCE_USDM_PERPETUAL")
                .put("signal_eligible", true);
        ObjectNode label = identity("short", "short-episode", START, "BINANCE_USDM_PERPETUAL")
                .put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY")
                .put("decision_timeframe", "4h").put("lifecycle_timeframe", "1m")
                .put("resolution_ceiling_time", iso(START + 2 * MINUTE));
        ObjectNode execution = identity("short", "short-episode", START, "BINANCE_USDM_PERPETUAL")
                .put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY")
                .put("decision_timeframe", "4h").put("lifecycle_timeframe", "1m")
                .put("max_lifecycle_ms", 2 * MINUTE).put("direction", "short")
                .put("instrument", "BINANCE_USDM_PERPETUAL").put("quantity", 2)
                .put("margin_mode", "ISOLATED").put("leverage", 2).put("tier_id", "T1")
                .put("collateral_usd", 200);
        ArrayNode bars = execution.putArray("child_bars");
        bars.add(bar(START, 100, 101, 99, 100));
        bars.add(bar(START + MINUTE, 99, 100, 98, 99));
        bars.add(bar(START + 2 * MINUTE, 98, 99, 97, 98));
        ArrayNode marks = execution.putArray("mark_bars");
        marks.add(mark(START, 100, 101, 99, 100));
        marks.add(mark(START + MINUTE, 99, 100, 98, 99));
        marks.add(mark(START + 2 * MINUTE, 98, 99, 97, 98));
        ObjectNode candidate = object().put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY")
                .put("decision_timeframe", "4h").put("lifecycle_timeframe", "1m")
                .put("max_lifecycle_ms", 2 * MINUTE).put("direction", "short")
                .put("entry_policy", "NEXT_BAR_OPEN");
        candidate.set("exit_policy", object().put("type", "TIME_STOP"));
        candidate.set("risk_contract", riskContract(100));
        ObjectNode metadata = object();
        metadata.set("contract_spec", metadata("CONTRACT_SPEC", "BINANCE_USDM_PERPETUAL",
                object().put("contract_multiplier", 1).put("step_size", .01).put("min_qty", .01)
                        .put("max_qty", 1_000_000).put("min_notional", 1).put("max_notional", 10_000_000)
                        .put("max_leverage", 10)));
        metadata.set("fee_schedule", metadata("FEE_SCHEDULE", "BINANCE_USDM_PERPETUAL",
                object().put("taker_fee_rate", .001)));
        metadata.set("execution_model", metadata("EXECUTION_MODEL", "BINANCE_USDM_PERPETUAL",
                object().put("slippage_bps", 10).put("impact_bps", 20)
                        .put("outage_policy", "FAIL").put("gap_policy", "FILL_AT_OPEN")));
        metadata.set("margin", metadata("MARGIN", "BINANCE_USDM_PERPETUAL",
                object().put("maintenance_margin_ratio", .005).put("margin_mode", "ISOLATED")
                        .put("tier_id", "T1").put("max_leverage", 10)));
        ObjectNode fundingFields = object().put("event_id", "fund-1")
                .put("funding_rate", .001).put("event_time", iso(START + MINUTE))
                .put("raw_event_time", iso(START + MINUTE)).put("settlement_slot", iso(START + MINUTE))
                .put("availability_time", iso(START + MINUTE)).put("settlement_mark", 99)
                .put("mark_price", 99);
        ObjectNode fundingReceipt = metadata("FUNDING_IDENTITY", "BINANCE_USDM_PERPETUAL", fundingFields);
        fundingReceipt.set("coverage", object().put("complete", true).put("coverage_mode", "EVENT_SEQUENCE"));
        metadata.set("funding_identity", StrategyResearchDataV5.withHash(fundingReceipt));
        return outcomeRequest(feature, label, execution, candidate, metadata);
    }

    private static ObjectNode datedFuture() {
        ObjectNode request = shortPerpetual();
        for (String role : new String[]{"feature", "label", "execution"}) {
            ((ObjectNode) request.path(role)).put("instrument", "BINANCE_USDM_DATED_FUTURE");
        }
        ObjectNode metadata = (ObjectNode) request.path("metadata");
        for (String name : new String[]{"contract_spec", "fee_schedule", "execution_model", "margin"}) {
            ((ObjectNode) metadata.path(name).path("records").get(0))
                    .put("instrument", "BINANCE_USDM_DATED_FUTURE");
            rehashMetadata(request, name);
        }
        ((ObjectNode) metadata.path("contract_spec").path("records").get(0))
                .put("expiry", iso(START + 3 * MINUTE));
        rehashMetadata(request, "contract_spec");
        metadata.set("expiry", metadata("EXPIRY", "BINANCE_USDM_DATED_FUTURE",
                object().put("expiry", iso(START + 3 * MINUTE))));
        metadata.set("funding_identity", unavailableMetadata("FUNDING_IDENTITY", "NOT_APPLICABLE"));
        return request;
    }

    private static ObjectNode spotTarget(boolean gap) {
        ObjectNode feature = identity("gap", "gap-episode", START).put("signal_eligible", true);
        ObjectNode label = identity("gap", "gap-episode", START)
                .put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY")
                .put("decision_timeframe", "4h").put("lifecycle_timeframe", "1m")
                .put("resolution_ceiling_time", iso(START + 2 * MINUTE));
        ObjectNode execution = identity("gap", "gap-episode", START)
                .put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY")
                .put("decision_timeframe", "4h").put("lifecycle_timeframe", "1m")
                .put("max_lifecycle_ms", 2 * MINUTE).put("direction", "long");
        ArrayNode bars = execution.putArray("child_bars");
        bars.add(bar(START, 100, 101, 99, 100));
        bars.add(gap ? bar(START + MINUTE, 94, 96, 93, 94) : bar(START + MINUTE, 100, 106, 94, 100));
        bars.add(bar(START + 2 * MINUTE, 100, 101, 99, 100));
        ObjectNode candidate = object().put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY")
                .put("decision_timeframe", "4h").put("lifecycle_timeframe", "1m")
                .put("max_lifecycle_ms", 2 * MINUTE).put("direction", "long")
                .put("entry_policy", "NEXT_BAR_OPEN");
        candidate.set("exit_policy", object().put("type", "TARGET_STOP")
                .put("collision_policy", "ADVERSE_STOP_FIRST").put("stop_price", 95).put("target_price", 105));
        candidate.set("risk_contract", riskContract(100));
        ObjectNode metadata = spotMetadata();
        return outcomeRequest(feature, label, execution, candidate, metadata);
    }

    private static ObjectNode spotTimeDerived() {
        ObjectNode feature = identity("sizing", "sizing-episode", START).put("signal_eligible", true);
        ObjectNode label = identity("sizing", "sizing-episode", START)
                .put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY")
                .put("decision_timeframe", "4h").put("lifecycle_timeframe", "1m")
                .put("resolution_ceiling_time", iso(START + 2 * MINUTE));
        ObjectNode execution = identity("sizing", "sizing-episode", START)
                .put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY")
                .put("decision_timeframe", "4h").put("lifecycle_timeframe", "1m")
                .put("max_lifecycle_ms", 2 * MINUTE).put("direction", "long");
        ArrayNode bars = execution.putArray("child_bars");
        bars.add(bar(START, 100, 101, 99, 100));
        bars.add(bar(START + MINUTE, 101, 102, 100, 101));
        bars.add(bar(START + 2 * MINUTE, 102, 103, 101, 102));
        ObjectNode candidate = object().put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY")
                .put("decision_timeframe", "4h").put("lifecycle_timeframe", "1m")
                .put("max_lifecycle_ms", 2 * MINUTE).put("direction", "long")
                .put("entry_policy", "NEXT_BAR_OPEN");
        candidate.set("exit_policy", object().put("type", "TIME_STOP"));
        candidate.set("risk_contract", riskContract(100));
        candidate.set("sizing_contract", object().put("mode", "FIXED_NOTIONAL_USD")
                .put("notional_usd", 1).put("precommit_sha256", H).put("evaluator_spec_sha256", H));
        return outcomeRequest(feature, label, execution, candidate, spotMetadata());
    }

    private static ObjectNode spotMetadata() {
        ObjectNode metadata = object();
        metadata.set("contract_spec", metadata("CONTRACT_SPEC", "BINANCE_SPOT",
                object().put("contract_multiplier", 1).put("step_size", .01).put("min_qty", .01)
                        .put("max_qty", 1_000_000).put("min_notional", 1).put("max_notional", 10_000_000)));
        metadata.set("fee_schedule", metadata("FEE_SCHEDULE", "BINANCE_SPOT",
                object().put("taker_fee_rate", .001)));
        metadata.set("execution_model", metadata("EXECUTION_MODEL", "BINANCE_SPOT",
                object().put("slippage_bps", 2).put("impact_bps", 1)
                        .put("outage_policy", "FAIL").put("gap_policy", "FILL_AT_OPEN")));
        return metadata;
    }

    private static ObjectNode outcomeRequest(ObjectNode feature, ObjectNode label, ObjectNode execution,
            ObjectNode candidate, ObjectNode metadata) {
        ObjectNode request = object();
        request.set("feature", feature);
        request.set("label", label);
        request.set("execution", execution);
        request.set("candidate", candidate);
        request.set("metadata", metadata);
        request.put("fixtureOnly", true);
        return request;
    }

    private static ObjectNode riskContract(double budget) {
        return object().put("mode", "FIXED_RISK_BUDGET_USD").put("budget_usd", budget)
                .put("precommit_sha256", H).put("evaluator_spec_sha256", H);
    }

    private static ObjectNode metadata(String kind, String instrument, ObjectNode fields) {
        ObjectNode record = object().put("asset", "btc").put("venue", "BINANCE")
                .put("instrument", instrument).put("symbol", "BTCUSDT")
                .put("effective_from", iso(START - MINUTE)).put("effective_to", iso(START + 5 * MINUTE))
                .put("availability_time", iso(START));
        record.setAll(fields);
        ObjectNode options = object().put("kind", kind).put("status", "CONSERVATIVE_MODEL")
                .put("capturedAt", iso(START)).put("modelSha256", H).put("precommitSha256", H);
        options.set("records", array().add(record));
        options.set("limitations", array());
        return StrategyResearchDataV5.makeMetadataReceipt(options);
    }

    private static ObjectNode unavailableMetadata(String kind, String limitation) {
        ObjectNode options = object().put("kind", kind).put("status", "UNAVAILABLE")
                .put("capturedAt", iso(START));
        options.set("records", array());
        options.set("limitations", array().add(limitation));
        return StrategyResearchDataV5.makeMetadataReceipt(options);
    }

    private static void replaceExecutionModel(ObjectNode request, String gapPolicy, double slippage, double impact) {
        ObjectNode receipt = (ObjectNode) request.path("metadata").path("execution_model").deepCopy();
        ((ObjectNode) receipt.path("records").get(0)).put("gap_policy", gapPolicy)
                .put("slippage_bps", slippage).put("impact_bps", impact);
        ((ObjectNode) request.path("metadata")).set("execution_model", StrategyResearchDataV5.withHash(receipt));
    }

    private static void rehashMetadata(ObjectNode request, String name) {
        ObjectNode receipt = (ObjectNode) request.path("metadata").path(name).deepCopy();
        ((ObjectNode) request.path("metadata")).set(name, StrategyResearchDataV5.withHash(receipt));
    }

    private static ObjectNode identity(String signal, String episode, long decision) {
        return identity(signal, episode, decision, "BINANCE_SPOT");
    }

    private static ObjectNode identity(String signal, String episode, long decision, String instrument) {
        return object().put("asset", "btc").put("venue", "BINANCE")
                .put("instrument", instrument).put("symbol", "BTCUSDT")
                .put("decision_time", iso(decision)).put("signal_id", signal).put("episode_id", episode);
    }

    private static ObjectNode bar(long event, double open, double high, double low, double close) {
        return object().put("event_time", event).put("availability_time", event + MINUTE - 1)
                .put("open", open).put("high", high).put("low", low).put("close", close)
                .put("is_closed", true);
    }

    private static ObjectNode mark(long event, double open, double high, double low, double close) {
        return object().put("event_time", event).put("availability_time", event + MINUTE - 1)
                .put("mark_open", open).put("mark_high", high).put("mark_low", low)
                .put("mark_close", close);
    }

    private static String iso(long value) {
        return Instant.ofEpochMilli(value).toString().replace("Z", ".000Z");
    }

    private static ObjectNode object() {
        return JsonHashes.mapper().createObjectNode();
    }

    private static ArrayNode array() {
        return JsonHashes.mapper().createArrayNode();
    }
}

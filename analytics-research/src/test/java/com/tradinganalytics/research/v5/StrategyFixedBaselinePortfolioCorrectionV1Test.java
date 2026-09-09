package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression coverage for the additive V1 correction path. */
final class StrategyFixedBaselinePortfolioCorrectionV1Test {
    @Test
    void equalInstantMarksAreExcludedForBothLexicalTimestampOrderings() {
        ObjectNode first = trade("first", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00.000Z", 100, 100);
        first.putArray("portfolio_mark_points").addObject()
                .put("time", "2021-01-01T00:01:00Z").put("price", 1);
        ObjectNode second = trade("second", "2021-01-01T00:00:00.000Z", "2021-01-01T00:01:00Z", 100, 100);
        second.putArray("portfolio_mark_points").addObject()
                .put("time", "2021-01-01T00:01:00.000Z").put("price", 1);

        ObjectNode firstResult = correct(first);
        ObjectNode secondResult = correct(second);
        assertThat(firstResult.path("excluded_exit_boundary_mark_count").asInt()).isEqualTo(1);
        assertThat(secondResult.path("excluded_exit_boundary_mark_count").asInt()).isEqualTo(1);
        assertThat(firstResult.path("final_marked_holdings_usdt").asDouble()).isZero();
        assertThat(secondResult.path("final_marked_holdings_usdt").asDouble()).isZero();
        assertThat(firstResult.path("equity_curve").get(1).path("event_type").asText()).isEqualTo("EXIT");
        assertThat(secondResult.path("equity_curve").get(1).path("event_type").asText()).isEqualTo("EXIT");
    }

    @Test
    void marksAfterExitFailInsteadOfRevivingAClosedHolding() {
        ObjectNode trade = trade("after-exit", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        trade.putArray("portfolio_mark_points").addObject()
                .put("time", "2021-01-01T00:01:00.001Z").put("price", 2);
        assertThatThrownBy(() -> correct(trade)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("after exit");
    }

    @Test
    void exitPrecedesSameInstantEntryAndReleasesFullCapital() {
        ObjectNode first = trade("first", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        ObjectNode second = trade("second", "2021-01-01T00:01:00.000Z", "2021-01-01T00:02:00.000Z", 100, 100);
        ObjectNode result = StrategyFixedBaselinePortfolioCorrectionV1.reconcile(
                JsonHashes.mapper().createArrayNode().add(first).add(second), 100, 100);
        ArrayNode curve = (ArrayNode) result.path("equity_curve");
        assertThat(curve.get(1).path("event_type").asText()).isEqualTo("EXIT");
        assertThat(curve.get(2).path("event_type").asText()).isEqualTo("ENTRY");
        assertThat(result.path("negative_cash").asBoolean()).isFalse();
        assertThat(result.path("final_active_position_count").asInt()).isZero();
    }

    @Test
    void normalMarksRemainInsideTheActiveIntervalAndUseMarkedValue() {
        ObjectNode trade = trade("marked", "2021-01-01T00:00:00Z", "2021-01-01T00:03:00Z", 100, 100);
        trade.putArray("portfolio_mark_points").addObject()
                .put("time", "2021-01-01T00:01:00.000Z").put("price", 1.25);
        ObjectNode result = correct(trade);
        assertThat(result.path("excluded_exit_boundary_mark_count").asInt()).isZero();
        assertThat(result.path("equity_curve").get(1).path("event_type").asText()).isEqualTo("MARK");
        assertThat(result.path("equity_curve").get(1).path("marked_holdings_usdt").asDouble()).isCloseTo(1.25,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.path("equity_curve").get(2).path("marked_holdings_usdt").asDouble()).isZero();
        assertThat(result.path("invariants").path("no_residual_marked_holding_for_exited_trade").asBoolean()).isTrue();
    }

    @Test
    void markAtEntryIsAppliedAfterEntryAndStillUsesTheActiveHolding() {
        ObjectNode trade = trade("entry-mark", "2021-01-01T00:00:00Z", "2021-01-01T00:03:00Z", 100, 100);
        trade.putArray("portfolio_mark_points").addObject()
                .put("time", "2021-01-01T00:00:00.000Z").put("price", 1.5);
        ObjectNode result = correct(trade);
        assertThat(result.path("equity_curve").get(1).path("event_type").asText()).isEqualTo("MARK");
        assertThat(result.path("equity_curve").get(1).path("marked_holdings_usdt").asDouble()).isCloseTo(1.5,
                org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void knownEthReproductionEndsAtCashAfterExit() {
        ObjectNode trade = JsonHashes.mapper().createObjectNode()
                .put("episode_id", "eth:2021-01-12T00:00:00Z")
                .put("entry_price", 1834.9210698907434).put("exit_price", 1724.8258056972986)
                .put("quantity", 0.544982).put("gross_pnl_usdt", -59.99993727067193)
                .put("fees_usdt", 1.9399979717517222).put("slippage_usdt", 0.9699989858758611)
                .put("capacity_debit_usdt", 0).put("net_pnl_usdt", -62.90993422829951);
        ObjectNode lifecycle = trade.putObject("lifecycle")
                .put("entry_time", "2021-01-12T00:00:00.000Z");
        lifecycle.putArray("exits").addObject().put("time", "2021-01-21T23:17:00.000Z")
                .put("availability_time", "2021-01-21T23:18:00.000Z")
                .put("price", 1724.8258056972986).put("fees_usd", 0.9399990172405251)
                .put("slippage_usd", 0.46999950862026263);
        trade.putArray("portfolio_mark_points").addObject()
                .put("time", "2021-01-21T23:18:00Z").put("price", 1725.87084152033);
        ObjectNode result = StrategyFixedBaselinePortfolioCorrectionV1.reconcile(
                JsonHashes.mapper().createArrayNode().add(trade), 10_000, 9937.090065771703);
        assertThat(result.path("ending_equity_usdt").asDouble()).isCloseTo(9937.090065771703,
                org.assertj.core.data.Offset.offset(1e-9));
        assertThat(result.path("equity_curve").get(1).path("event_type").asText()).isEqualTo("EXIT");
        assertThat(result.path("equity_curve").get(1).path("marked_holdings_usdt").asDouble()).isZero();
        assertThat(result.path("invariants").path("final_curve_equity_reconciles_declared_ending_equity").asBoolean())
                .isTrue();
    }

    @Test
    void mixedTimestampTradesHaveDeterministicCanonicalEventOrdering() {
        ObjectNode first = trade("b", "2021-01-01T00:00:00.000Z", "2021-01-01T00:02:00Z", 50, 50);
        ObjectNode second = trade("a", "2021-01-01T00:02:00.000Z", "2021-01-01T00:04:00Z", 50, 50);
        ObjectNode result = StrategyFixedBaselinePortfolioCorrectionV1.reconcile(
                JsonHashes.mapper().createArrayNode().add(first).add(second), 50, 50);
        ArrayNode curve = (ArrayNode) result.path("equity_curve");
        assertThat(curve.get(1).path("event_type").asText()).isEqualTo("EXIT");
        assertThat(curve.get(2).path("event_type").asText()).isEqualTo("ENTRY");
        assertThat(curve.get(1).path("event_time").asText()).isEqualTo("2021-01-01T00:02:00Z");
        assertThat(result.path("invariants").path("final_curve_point_is_exit_with_no_residual_active_position")
                .asBoolean()).isTrue();
    }

    @Test
    void emptyBookIsExplicitlyRepresentedAndFullyClosedBookHasZeroMarks() {
        ObjectNode empty = StrategyFixedBaselinePortfolioCorrectionV1.reconcile(
                JsonHashes.mapper().createArrayNode(), 1000, 1000);
        assertThat(empty.path("trade_count").asInt()).isZero();
        assertThat(empty.path("equity_curve")).isEmpty();
        assertThat(empty.path("final_curve_equity_reconciles_cash_plus_marked_holdings").asBoolean()).isTrue();
        assertThat(empty.path("final_curve_equity_reconciles_declared_ending_equity").asBoolean()).isTrue();
        assertThat(empty.path("final_curve_point_is_exit_with_no_residual_active_position").asBoolean()).isTrue();
        ObjectNode closed = correct(trade("closed", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100));
        assertThat(closed.path("final_active_position_count").asInt()).isZero();
        assertThat(closed.path("final_marked_holdings_usdt").asDouble()).isZero();
        assertThat(closed.path("invariants").path("final_curve_point_is_exit_with_no_residual_active_position")
                .asBoolean()).isTrue();
    }

    @Test
    void duplicateIdsAndMalformedLifecyclesAreRejected() {
        ObjectNode first = trade("duplicate", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        ObjectNode second = first.deepCopy();
        assertThatThrownBy(() -> StrategyFixedBaselinePortfolioCorrectionV1.reconcile(
                JsonHashes.mapper().createArrayNode().add(first).add(second), 1000, 1000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate trade id");
        ObjectNode invalid = trade("invalid", "2021-01-01T00:01:00Z", "2021-01-01T00:00:00Z", 100, 100);
        assertThatThrownBy(() -> correct(invalid)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exit must be after entry");
    }

    @Test
    void inputIsNotMutatedAndRepeatedRunsHaveTheSameCanonicalHash() {
        ObjectNode trade = trade("immutable", "2021-01-01T00:00:00.000Z", "2021-01-01T00:01:00Z", 100, 100);
        trade.putArray("portfolio_mark_points").addObject()
                .put("time", "2021-01-01T00:01:00.000Z").put("price", 2);
        ObjectNode input = JsonHashes.mapper().createObjectNode()
                .put("schema", StrategyFixedBaselinePortfolioCorrectionV1.INPUT_SCHEMA)
                .put("version", 1).put("starting_capital_usdt", 100)
                .put("declared_ending_equity_usdt", 100);
        input.set("trades", JsonHashes.mapper().createArrayNode().add(trade));
        ObjectNode before = input.deepCopy();
        ObjectNode first = StrategyFixedBaselinePortfolioCorrectionV1.reconcile(input);
        ObjectNode second = StrategyFixedBaselinePortfolioCorrectionV1.reconcile(input);
        assertThat(input).isEqualTo(before);
        assertThat(first.path("content_sha256").asText()).isEqualTo(second.path("content_sha256").asText());
        assertThat(first.path("content_sha256").asText()).isEqualTo(JsonHashes.ownHash(first));
        assertThat(first.path("accounting_version").asText())
                .isEqualTo(StrategyFixedBaselinePortfolioCorrectionV1.ACCOUNTING_VERSION);
        assertThat(first.path("evaluator_identity").asText())
                .isEqualTo(StrategyFixedBaselinePortfolioCorrectionV1.EVALUATOR_ID);
        assertThat(first.path("source_fingerprint").asText()).matches("(?:[a-f0-9]{64}|UNKNOWN)");
        assertThat(first.path("correction_receipt").path("content_sha256").asText())
                .isEqualTo(JsonHashes.ownHash(first.path("correction_receipt")));
    }

    @Test
    void duplicateMarkInstantsAreRejectedAfterInstantNormalization() {
        ObjectNode trade = trade("duplicate-mark", "2021-01-01T00:00:00Z", "2021-01-01T00:03:00Z", 100, 100);
        ArrayNode marks = trade.putArray("portfolio_mark_points");
        marks.addObject().put("time", "2021-01-01T00:01:00Z").put("price", 1);
        marks.addObject().put("time", "2021-01-01T00:01:00.000Z").put("price", 2);
        assertThatThrownBy(() -> correct(trade)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate portfolio mark instant");
    }

    @Test
    void inconsistentEconomicsAndReservedSourceHashAreRejected() {
        ObjectNode grossMismatch = trade("gross-mismatch", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        grossMismatch.put("gross_pnl_usdt", 1);
        assertThatThrownBy(() -> correct(grossMismatch)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gross P&L");

        ObjectNode netMismatch = trade("net-mismatch", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        netMismatch.put("net_pnl_usdt", 1);
        assertThatThrownBy(() -> correct(netMismatch)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("net P&L");

        ObjectNode input = JsonHashes.mapper().createObjectNode()
                .put("schema", StrategyFixedBaselinePortfolioCorrectionV1.INPUT_SCHEMA).put("version", 1)
                .put("starting_capital_usdt", 1000).put("declared_ending_equity_usdt", 1000)
                .put("source_input_canonical_sha256", "a".repeat(64));
        input.set("trades", JsonHashes.mapper().createArrayNode());
        assertThatThrownBy(() -> StrategyFixedBaselinePortfolioCorrectionV1.reconcile(input))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reserved");
    }

    @Test
    void partialExitEvidenceIsRejected() {
        ObjectNode trade = trade("partial", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        ((ObjectNode) trade.path("lifecycle").path("exits").get(0)).put("fraction", .5);
        assertThatThrownBy(() -> correct(trade)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fraction must be one");
    }

    @Test
    void legacyBookAdaptationDoesNotMutateSourceAndBindsSourceHash() {
        ObjectNode trade = trade("legacy", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00.000Z", 100, 100);
        trade.putArray("portfolio_mark_points").addObject()
                .put("time", "2021-01-01T00:01:00Z").put("price", 2);
        ObjectNode legacy = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-fixed-baseline-result/1")
                .put("starting_equity_usdt", 100).put("ending_equity_usdt", 100)
                .put("starting_capital_policy", "FIXED_INITIAL_CASH_USDT_10000_V001");
        legacy.set("trades", JsonHashes.mapper().createArrayNode().add(trade));
        ObjectNode before = legacy.deepCopy();
        ObjectNode result = StrategyFixedBaselinePortfolioCorrectionV1.correctLegacyBook(legacy);
        assertThat(legacy).isEqualTo(before);
        assertThat(result.path("source_kind").asText()).isEqualTo("FROZEN_V5_PORTFOLIO_BOOK");
        assertThat(result.path("source_input_canonical_sha256").asText())
                .isEqualTo(JsonHashes.canonicalSha256(legacy));
        assertThat(result.path("excluded_exit_boundary_mark_count").asInt()).isEqualTo(1);
    }

    @Test
    void malformedTypedInputsFailClosed() {
        assertThatThrownBy(() -> StrategyFixedBaselinePortfolioCorrectionV1.reconcile(null))
                .isInstanceOf(IllegalArgumentException.class);
        ObjectNode wrongSchema = typedInput(1000, 1000);
        wrongSchema.put("schema", "wrong");
        assertThatThrownBy(() -> StrategyFixedBaselinePortfolioCorrectionV1.reconcile(wrongSchema))
                .isInstanceOf(IllegalArgumentException.class);
        ObjectNode wrongVersion = typedInput(1000, 1000);
        wrongVersion.put("version", 2);
        assertThatThrownBy(() -> StrategyFixedBaselinePortfolioCorrectionV1.reconcile(wrongVersion))
                .isInstanceOf(IllegalArgumentException.class);
        ObjectNode missingTrades = typedInput(1000, 1000);
        missingTrades.remove("trades");
        assertThatThrownBy(() -> StrategyFixedBaselinePortfolioCorrectionV1.reconcile(missingTrades))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("trades array");
        ObjectNode badStart = typedInput(0, 1000);
        assertThatThrownBy(() -> StrategyFixedBaselinePortfolioCorrectionV1.reconcile(badStart))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
        ObjectNode badDeclared = typedInput(1000, Double.NaN);
        assertThatThrownBy(() -> StrategyFixedBaselinePortfolioCorrectionV1.reconcile(badDeclared))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("finite");
        ObjectNode nonObjectTrade = typedInput(1000, 1000);
        nonObjectTrade.set("trades", JsonHashes.mapper().createArrayNode().add(1));
        assertThatThrownBy(() -> StrategyFixedBaselinePortfolioCorrectionV1.reconcile(nonObjectTrade))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("trade must be an object");
    }

    @Test
    void malformedTradeFieldsAndBoundaryEvidenceFailClosed() {
        ObjectNode noLifecycle = trade("no-lifecycle", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        noLifecycle.remove("lifecycle");
        assertThatThrownBy(() -> correct(noLifecycle)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing lifecycle");

        ObjectNode noExit = trade("no-exit", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        ((ArrayNode) noExit.path("lifecycle").path("exits")).removeAll();
        assertThatThrownBy(() -> correct(noExit)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one complete exit");

        ObjectNode manyExits = trade("many-exits", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        ((ArrayNode) manyExits.path("lifecycle").path("exits")).addObject()
                .put("time", "2021-01-01T00:02:00Z").put("price", 100);
        assertThatThrownBy(() -> correct(manyExits)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one complete exit");

        ObjectNode badEntry = trade("bad-entry", "not-an-instant", "2021-01-01T00:01:00Z", 100, 100);
        assertThatThrownBy(() -> correct(badEntry)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entry time");
        ObjectNode badQuantity = trade("bad-quantity", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        badQuantity.put("quantity", 0);
        assertThatThrownBy(() -> correct(badQuantity)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        ObjectNode badDirection = trade("short", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        badDirection.put("direction", "short");
        assertThatThrownBy(() -> correct(badDirection)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("long spot");

        ObjectNode badMarks = trade("bad-marks", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        badMarks.put("portfolio_mark_points", "not-an-array");
        assertThatThrownBy(() -> correct(badMarks)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be an array");
        ObjectNode badMarkObject = trade("bad-mark-object", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        badMarkObject.putArray("portfolio_mark_points").add(1);
        assertThatThrownBy(() -> correct(badMarkObject)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mark must be an object");
        ObjectNode badMarkTime = trade("bad-mark-time", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        badMarkTime.putArray("portfolio_mark_points").addObject().put("time", "bad").put("price", 1);
        assertThatThrownBy(() -> correct(badMarkTime)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mark time");
        ObjectNode badMarkPrice = trade("bad-mark-price", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        badMarkPrice.putArray("portfolio_mark_points").addObject().put("time", "2021-01-01T00:00:30Z").put("price", 0);
        assertThatThrownBy(() -> correct(badMarkPrice)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        ObjectNode preentryMark = trade("preentry-mark", "2021-01-01T00:01:00Z", "2021-01-01T00:02:00Z", 100, 100);
        preentryMark.putArray("portfolio_mark_points").addObject().put("time", "2021-01-01T00:00:30Z").put("price", 1);
        assertThatThrownBy(() -> correct(preentryMark)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precedes entry");
    }

    @Test
    void lifecycleQuantitiesCostsAndCapitalAreValidated() {
        ObjectNode lifecycleQuantity = trade("lifecycle-quantity", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        ((ObjectNode) lifecycleQuantity.path("lifecycle")).put("quantity", 2);
        assertThatThrownBy(() -> correct(lifecycleQuantity)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lifecycle quantity");

        ObjectNode exitQuantity = trade("exit-quantity", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        ((ObjectNode) exitQuantity.path("lifecycle").path("exits").get(0)).put("quantity", 2);
        assertThatThrownBy(() -> correct(exitQuantity)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exit quantity");

        ObjectNode remaining = trade("remaining", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        ((ObjectNode) remaining.path("lifecycle")).put("remaining_quantity", 1);
        assertThatThrownBy(() -> correct(remaining)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remaining quantity");

        ObjectNode costs = trade("negative-cost", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        costs.put("fees_usdt", -1);
        assertThatThrownBy(() -> correct(costs)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
        ObjectNode exitCosts = trade("exit-cost", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        exitCosts.put("fees_usdt", 0);
        ((ObjectNode) exitCosts.path("lifecycle").path("exits").get(0)).put("fees_usd", 1);
        assertThatThrownBy(() -> correct(exitCosts)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exit costs");

        ObjectNode overlappingFirst = trade("overlap-first", "2021-01-01T00:00:00Z", "2021-01-01T00:02:00Z", 100, 100);
        ObjectNode overlappingSecond = trade("overlap-second", "2021-01-01T00:01:00Z", "2021-01-01T00:03:00Z", 100, 100);
        assertThatThrownBy(() -> StrategyFixedBaselinePortfolioCorrectionV1.reconcile(
                JsonHashes.mapper().createArrayNode().add(overlappingFirst).add(overlappingSecond), 100, 100))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exhausted");
    }

    @Test
    void compatibilityAndDefensiveInputBranchesAreCovered() {
        ObjectNode empty = StrategyFixedBaselinePortfolioCorrectionV1.reconcile(null, 1000, 1000);
        assertThat(empty.path("trade_count").asInt()).isZero();
        assertThatThrownBy(() -> StrategyFixedBaselinePortfolioCorrectionV1.correctLegacyBook(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be an object");
        assertThatThrownBy(() -> StrategyFixedBaselinePortfolioCorrectionV1.correctLegacyBook(
                JsonHashes.mapper().createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lacks trades");

        ObjectNode noPolicy = JsonHashes.mapper().createObjectNode()
                .put("starting_equity_usdt", 100).put("ending_equity_usdt", 100);
        noPolicy.set("trades", JsonHashes.mapper().createArrayNode().add(
                trade("no-policy", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100)));
        ObjectNode noPolicyResult = StrategyFixedBaselinePortfolioCorrectionV1.correctLegacyBook(noPolicy);
        assertThat(noPolicyResult.path("starting_capital_policy").asText()).isEmpty();

        ObjectNode missingId = trade("missing-id", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        missingId.remove("episode_id");
        assertThatThrownBy(() -> correct(missingId)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lacks episode_id");
    }

    @Test
    void lifecycleShapeAndOptionalEvidenceBranchesAreValidated() {
        ObjectNode noExitArray = trade("no-exit-array", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        ((ObjectNode) noExitArray.path("lifecycle")).put("exits", "not-an-array");
        assertThatThrownBy(() -> correct(noExitArray)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one complete exit");

        ObjectNode nonObjectExit = trade("non-object-exit", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        ((ArrayNode) nonObjectExit.path("lifecycle").path("exits")).removeAll().add(1);
        assertThatThrownBy(() -> correct(nonObjectExit)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one complete exit");

        ObjectNode optional = trade("optional", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        optional.remove("exit_price");
        optional.remove("gross_pnl_usdt");
        optional.remove("fees_usdt");
        optional.remove("slippage_usdt");
        optional.remove("capacity_debit_usdt");
        optional.remove("net_pnl_usdt");
        ObjectNode lifecycle = (ObjectNode) optional.path("lifecycle");
        lifecycle.put("quantity", 1).put("remaining_quantity", 0);
        ObjectNode exit = (ObjectNode) lifecycle.path("exits").get(0);
        exit.put("quantity", 1).put("fraction", 1);
        optional.put("direction", "");
        ObjectNode optionalResult = correct(optional);
        assertThat(optionalResult.path("sum_check").asBoolean()).isTrue();
    }

    @Test
    void aggregateOverflowAndAdditionalCostBranchesFailClosed() {
        ObjectNode hugeFirst = trade("huge-first", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 1, Double.MAX_VALUE);
        hugeFirst.put("gross_pnl_usdt", Double.MAX_VALUE).put("net_pnl_usdt", Double.MAX_VALUE);
        ObjectNode hugeSecond = trade("huge-second", "2021-01-01T00:02:00Z", "2021-01-01T00:03:00Z", 1, Double.MAX_VALUE);
        hugeSecond.put("gross_pnl_usdt", Double.MAX_VALUE).put("net_pnl_usdt", Double.MAX_VALUE);
        assertThatThrownBy(() -> StrategyFixedBaselinePortfolioCorrectionV1.reconcile(
                JsonHashes.mapper().createArrayNode().add(hugeFirst).add(hugeSecond), 1000, 1000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not finite");

        ObjectNode negativeSlippage = trade("negative-slippage", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        negativeSlippage.put("slippage_usdt", -1);
        assertThatThrownBy(() -> correct(negativeSlippage)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
        ObjectNode negativeCapacity = trade("negative-capacity", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        negativeCapacity.put("capacity_debit_usdt", -1);
        assertThatThrownBy(() -> correct(negativeCapacity)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
        ObjectNode negativeExitSlippage = trade("negative-exit-slippage", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 100);
        ((ObjectNode) negativeExitSlippage.path("lifecycle").path("exits").get(0)).put("slippage_usd", -1);
        assertThatThrownBy(() -> correct(negativeExitSlippage)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
    }

    @Test
    void overlappingClosedTradesKeepActiveMarkedMapBoundedAndDeterministic() {
        ObjectNode first = trade("first", "2021-01-01T00:00:00Z", "2021-01-01T00:03:00Z", 100, 100);
        ObjectNode second = trade("second", "2021-01-01T00:00:00.000Z", "2021-01-01T00:04:00Z", 100, 100);
        ObjectNode result = StrategyFixedBaselinePortfolioCorrectionV1.reconcile(
                JsonHashes.mapper().createArrayNode().add(second).add(first), 200, 200);
        ArrayNode curve = (ArrayNode) result.path("equity_curve");
        assertThat(curve.get(1).path("active_position_count").asInt()).isEqualTo(2);
        assertThat(curve.get(2).path("active_position_count").asInt()).isEqualTo(1);
        assertThat(curve.get(3).path("active_position_count").asInt()).isZero();
        assertThat(curve.get(3).path("marked_holdings_usdt").asDouble()).isZero();
        assertThat(result.path("invariants").path("final_curve_equity_reconciles_cash_plus_marked_holdings")
                .asBoolean()).isTrue();
    }

    private static ObjectNode correct(ObjectNode trade) {
        return StrategyFixedBaselinePortfolioCorrectionV1.reconcile(
                JsonHashes.mapper().createArrayNode().add(trade), 1000, 1000);
    }

    private static ObjectNode typedInput(double starting, double declared) {
        return JsonHashes.mapper().createObjectNode()
                .put("schema", StrategyFixedBaselinePortfolioCorrectionV1.INPUT_SCHEMA)
                .put("version", 1).put("starting_capital_usdt", starting)
                .put("declared_ending_equity_usdt", declared)
                .set("trades", JsonHashes.mapper().createArrayNode());
    }

    private static ObjectNode trade(String id, String entry, String exit, double entryPrice, double exitPrice) {
        ObjectNode trade = JsonHashes.mapper().createObjectNode().put("episode_id", id)
                .put("entry_price", entryPrice).put("exit_price", exitPrice).put("quantity", 1)
                .put("gross_pnl_usdt", exitPrice - entryPrice).put("fees_usdt", 0)
                .put("slippage_usdt", 0).put("capacity_debit_usdt", 0)
                .put("net_pnl_usdt", exitPrice - entryPrice);
        ObjectNode lifecycle = trade.putObject("lifecycle").put("entry_time", entry);
        lifecycle.putArray("exits").addObject().put("time", exit).put("price", exitPrice)
                .put("fees_usd", 0).put("slippage_usd", 0);
        return trade;
    }
}

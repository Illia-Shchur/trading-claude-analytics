package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.build.BuildIdentityService;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Portable contract and integration checks for the corrected evaluator seam. */
final class StrategyFixedBaselineCorrectedV1Test {
    private static final String EXECUTOR = "a".repeat(64);

    @Test
    void streamingLargeValueHashesMatchExistingCanonicalAndSerializedBytes() throws Exception {
        ObjectNode value = JsonHashes.mapper().createObjectNode()
                .put("😀", "é\n\u000f")
                .put("negative_zero", -0D)
                .put("small_exponent", 1e-7D)
                .put("fixed_number", 1e-6D);
        value.putArray("rows").addObject().put("z", 2).put("a", true);
        value.putObject("nested").put("path", "/tmp/retained").put("value", 1.5D);

        assertThat(JsonHashes.canonicalSha256Streaming(value))
                .isEqualTo(JsonHashes.sha256(JsonHashes.canonicalBytes(value)));
        assertThat(JsonHashes.ownHashStreaming(value))
                .isEqualTo(JsonHashes.sha256(JsonHashes.canonicalBytes(value)));
        assertThat(JsonHashes.serializedSha256Streaming(value))
                .isEqualTo(JsonHashes.sha256(JsonHashes.mapper().writeValueAsBytes(value)));

        value.put("content_sha256", "stale");
        ObjectNode withoutContent = value.deepCopy();
        withoutContent.remove("content_sha256");
        assertThat(JsonHashes.ownHashStreaming(value))
                .isEqualTo(JsonHashes.sha256(JsonHashes.canonicalBytes(withoutContent)));
    }

    @Test
    void correctsBothBooksAndRecomputesCombinedEquityMetrics() {
        ObjectNode eventTrade = trade("event", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 110);
        eventTrade.putArray("portfolio_mark_points").addObject()
                .put("time", "2021-01-01T00:01:00.000Z").put("price", 1);
        ObjectNode controlTrade = trade("control", "2021-01-01T00:01:00.000Z", "2021-01-01T00:02:00.000Z", 100, 90);
        controlTrade.putArray("portfolio_mark_points").addObject()
                .put("time", "2021-01-01T00:02:00Z").put("price", 999);
        ObjectNode frozen = frozen(book(1000, 1010, eventTrade), book(1000, 990, controlTrade));
        ObjectNode before = frozen.deepCopy();

        ObjectNode corrected = correctForTest(frozen);

        assertThat(frozen).isEqualTo(before);
        assertThat(corrected.path("schema").asText()).isEqualTo(StrategyFixedBaselineCorrectedV1.RESULT_SCHEMA);
        assertThat(corrected.path("content_sha256").asText()).isEqualTo(JsonHashes.ownHash(corrected));
        assertThat(corrected.path("source_result_content_sha256").asText())
                .isEqualTo(JsonHashes.ownHash(frozen));
        assertThat(corrected.path("source_executor_identity_sha256").asText()).isEqualTo(EXECUTOR);
        assertThat(corrected.path("corrected_executor_identity_sha256").asText()).isEqualTo("b".repeat(64));
        assertThat(corrected.path("executor_identity_sha256").asText()).isEqualTo("b".repeat(64));
        assertThat(corrected.path("accounting_version").asText())
                .isEqualTo(StrategyFixedBaselineCorrectedV1.ACCOUNTING_VERSION);
        assertThat(corrected.path("evaluator_identity").asText())
                .isEqualTo(StrategyFixedBaselineCorrectedV1.EVALUATOR_ID);

        ObjectNode portfolio = (ObjectNode) corrected.path("portfolio");
        assertThat(portfolio.path("event_book").path("excluded_exit_boundary_mark_count").asInt()).isOne();
        assertThat(portfolio.path("control_book").path("excluded_exit_boundary_mark_count").asInt()).isOne();
        assertThat(portfolio.path("event_book").path("final_marked_holdings_usdt").asDouble()).isZero();
        assertThat(portfolio.path("control_book").path("final_marked_holdings_usdt").asDouble()).isZero();
        assertThat(portfolio.path("starting_equity_usdt").asDouble()).isEqualTo(2000D);
        assertThat(portfolio.path("ending_equity_usdt").asDouble()).isEqualTo(2000D);
        assertThat(portfolio.path("net_pnl_usdt").asDouble()).isZero();
        assertThat(portfolio.path("max_drawdown_usdt").asDouble()).isEqualTo(10D);
        assertThat(portfolio.path("corrected_metrics").path("combined").path("return_fraction").asDouble())
                .isZero();
        assertThat(portfolio.path("combined_equity_curve")).hasSize(4);
        assertThat(portfolio.path("combined_equity_curve").findValuesAsText("equity_usdt"))
                .containsExactly("2000.0", "2010.0", "2010.0", "2000.0");
        assertThat(portfolio.path("corrected_metrics").has("equity_curve")).isFalse();
        assertThat(portfolio.path("corrected_metrics").path("equity_curve_source").asText())
                .isEqualTo("portfolio.combined_equity_curve");
        assertThat(portfolio.path("corrected_metrics").path("equity_curve_sha256").asText())
                .isEqualTo(JsonHashes.canonicalSha256(portfolio.path("combined_equity_curve")));
        assertThat(portfolio.path("combined_equity_curve").get(3).path("marked_holdings_usdt").asDouble()).isZero();
        assertThat(portfolio.path("combined_equity_curve").get(3).path("active_position_count").asInt()).isZero();
        assertThat(corrected.path("metrics").path("portfolio_equity_recomputed").asBoolean()).isTrue();
        assertThat(corrected.path("metrics").path("corrected_portfolio").path("combined").path("max_drawdown_usdt").isNumber())
                .isTrue();
    }

    @Test
    void acceptsEquivalentInstantFormsAndCanonicalizesCorrectionCurve() {
        ObjectNode trade = trade("instant", "2021-01-01T00:00:00+00:00",
                "2021-01-01T00:01:00.000Z", 100, 110);
        trade.putArray("portfolio_mark_points").addObject()
                .put("time", "2021-01-01T00:00:30.000+00:00").put("price", 105);
        ObjectNode corrected = correctForTest(frozen(book(1000, 1010, trade), book(1000, 1000)));
        ArrayNode curve = (ArrayNode) corrected.path("portfolio").path("event_book").path("equity_curve");
        assertThat(curve).hasSize(3);
        assertThat(curve.get(0).path("event_time").asText()).isEqualTo("2021-01-01T00:00:00Z");
        assertThat(curve.get(1).path("event_time").asText()).isEqualTo("2021-01-01T00:00:30Z");
        assertThat(curve.get(2).path("event_time").asText()).isEqualTo("2021-01-01T00:01:00Z");
    }

    @Test
    void emptyBooksReconcileWithoutSyntheticCurvePoints() {
        ObjectNode corrected = correctForTest(frozen(book(1000, 1000), book(2000, 2000)));
        ObjectNode portfolio = (ObjectNode) corrected.path("portfolio");
        assertThat(portfolio.path("event_book").path("equity_curve")).isEmpty();
        assertThat(portfolio.path("control_book").path("equity_curve")).isEmpty();
        assertThat(portfolio.path("combined_equity_curve")).isEmpty();
        assertThat(portfolio.path("starting_equity_usdt").asDouble()).isEqualTo(3000D);
        assertThat(portfolio.path("ending_equity_usdt").asDouble()).isEqualTo(3000D);
        assertThat(portfolio.path("max_drawdown_usdt").asDouble()).isZero();
        assertThat(portfolio.path("final_marked_holdings_usdt").asDouble()).isZero();
        assertThat(portfolio.path("final_active_position_count").asInt()).isZero();
        assertThat(portfolio.path("ending_minus_starting_equals_net").asBoolean()).isTrue();
        assertThat(portfolio.path("final_curve_equity_reconciles_cash_plus_marked_holdings").asBoolean()).isTrue();
    }

    @Test
    void propagatesTradeCostsIntoBothBookAndPortfolioAggregates() {
        ObjectNode costly = tradeWithCosts("costly", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110, 1, .5, .25);
        ObjectNode corrected = correctForTest(frozen(book(1000, 1008.25, costly), book(1000, 1000)));
        ObjectNode portfolio = (ObjectNode) corrected.path("portfolio");
        assertThat(portfolio.path("gross_pnl_usdt").asDouble()).isEqualTo(10D);
        assertThat(portfolio.path("fees_usdt").asDouble()).isEqualTo(1D);
        assertThat(portfolio.path("slippage_usdt").asDouble()).isEqualTo(.5D);
        assertThat(portfolio.path("capacity_debit_usdt").asDouble()).isEqualTo(.25D);
        assertThat(portfolio.path("net_pnl_usdt").asDouble()).isEqualTo(8.25D);
        assertThat(portfolio.path("ending_equity_usdt").asDouble()).isEqualTo(2008.25D);
        assertThat(portfolio.path("sum_check").asBoolean()).isTrue();
        assertThat(portfolio.path("event_book").path("ending_equity_usdt").asDouble()).isEqualTo(1008.25D);
        assertThat(portfolio.path("corrected_metrics").path("combined").path("net_pnl_usdt").asDouble())
                .isEqualTo(8.25D);
    }

    @Test
    void aggregatesDistinctBookEconomicsAndReplacesStaleMetricProjection() {
        ObjectNode event = tradeWithCosts("event-costly", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110, 1, .5, .25);
        ObjectNode control = tradeWithCosts("control-costly", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 200, 190, 2, 1, .5);
        ObjectNode frozen = frozen(book(1000, 1008.25, event), book(2000, 1986.5, control));
        ((ObjectNode) frozen.path("metrics")).put("net_pnl_usdt", 999D)
                .put("ending_equity_usdt", 999D).put("max_drawdown_usdt", 999D)
                .put("return_fraction", 999D);
        rehash(frozen);

        ObjectNode corrected = correctForTest(frozen);
        ObjectNode portfolio = (ObjectNode) corrected.path("portfolio");
        assertThat(portfolio.path("gross_pnl_usdt").asDouble()).isZero();
        assertThat(portfolio.path("fees_usdt").asDouble()).isEqualTo(3D);
        assertThat(portfolio.path("slippage_usdt").asDouble()).isEqualTo(1.5D);
        assertThat(portfolio.path("capacity_debit_usdt").asDouble()).isEqualTo(.75D);
        assertThat(portfolio.path("realized_pnl_usdt").asDouble()).isEqualTo(-5.25D);
        assertThat(portfolio.path("net_pnl_usdt").asDouble()).isEqualTo(-5.25D);
        assertThat(portfolio.path("starting_equity_usdt").asDouble()).isEqualTo(3000D);
        assertThat(portfolio.path("ending_equity_usdt").asDouble()).isEqualTo(2994.75D);
        assertThat(portfolio.path("max_drawdown_usdt").asDouble()).isEqualTo(11.5D);
        assertThat(portfolio.path("corrected_metrics").path("trade_count").asInt()).isEqualTo(2);

        ObjectNode combined = (ObjectNode) portfolio.path("corrected_metrics").path("combined");
        assertThat(combined.path("gross_pnl_usdt").asDouble()).isZero();
        assertThat(combined.path("fees_usdt").asDouble()).isEqualTo(3D);
        assertThat(combined.path("slippage_usdt").asDouble()).isEqualTo(1.5D);
        assertThat(combined.path("capacity_debit_usdt").asDouble()).isEqualTo(.75D);
        assertThat(combined.path("return_fraction").asDouble()).isEqualTo(-5.25D / 3000D);
        assertThat(combined.path("max_drawdown_fraction").asDouble()).isEqualTo(11.5D / 3000D);
        assertThat(portfolio.path("corrected_metrics").path("event_book").path("return_fraction").asDouble())
                .isEqualTo(8.25D / 1000D);
        assertThat(portfolio.path("corrected_metrics").path("control_book").path("return_fraction").asDouble())
                .isEqualTo(-13.5D / 2000D);

        ObjectNode metrics = (ObjectNode) corrected.path("metrics");
        assertThat(metrics.path("net_pnl_usdt").asDouble()).isEqualTo(-5.25D);
        assertThat(metrics.path("ending_equity_usdt").asDouble()).isEqualTo(2994.75D);
        assertThat(metrics.path("max_drawdown_usdt").asDouble()).isEqualTo(11.5D);
        assertThat(metrics.path("return_fraction").asDouble()).isEqualTo(-5.25D / 3000D);
    }

    @Test
    void overlappingPositionsRemainIndependentAcrossEventAndControlBooks() {
        ObjectNode event = trade("event-overlap", "2021-01-01T00:00:00Z",
                "2021-01-01T00:02:00Z", 100, 110);
        ObjectNode control = trade("control-overlap", "2021-01-01T00:01:00Z",
                "2021-01-01T00:03:00Z", 100, 90);
        ObjectNode corrected = correctForTest(frozen(book(1000, 1010, event), book(1000, 990, control)));
        ArrayNode curve = (ArrayNode) corrected.path("portfolio").path("combined_equity_curve");
        assertThat(curve.findValuesAsText("equity_usdt"))
                .containsExactly("2000.0", "2000.0", "2010.0", "2000.0");
        assertThat(curve.findValuesAsText("active_position_count"))
                .containsExactly("1", "2", "1", "0");
        assertThat(curve.get(3).path("marked_holdings_usdt").asDouble()).isZero();
        assertThat(curve.findValuesAsText("cash_usdt"))
                .containsExactly("1900.0", "1800.0", "1910.0", "2000.0");
        assertThat(curve.findValuesAsText("marked_holdings_usdt"))
                .containsExactly("100.0", "200.0", "100.0", "0.0");
        assertThat(curve.findValuesAsText("holdings_at_entry_cost_usdt"))
                .containsExactly("100.0", "200.0", "100.0", "0.0");
    }

    @Test
    void ordersExitBeforeOppositeBookEntryAtTheSameInstant() {
        ObjectNode control = trade("control-first", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 90);
        ObjectNode event = trade("event-second", "2021-01-01T00:01:00Z",
                "2021-01-01T00:02:00Z", 200, 210);
        ObjectNode corrected = correctForTest(frozen(book(1000, 1010, event), book(2000, 1990, control)));
        ArrayNode curve = (ArrayNode) corrected.path("portfolio").path("combined_equity_curve");
        assertThat(curve.findValuesAsText("source_book"))
                .containsExactly("CONTROL", "CONTROL", "EVENT", "EVENT");
        assertThat(curve.findValuesAsText("event_type"))
                .containsExactly("ENTRY", "EXIT", "ENTRY", "EXIT");
        assertThat(curve.findValuesAsText("active_position_count"))
                .containsExactly("1", "0", "1", "0");
    }

    @Test
    void preservesOrdinalOrderForSameBookEntriesAtOneInstant() {
        ObjectNode first = trade("a-first", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110);
        ObjectNode second = trade("b-second", "2021-01-01T00:00:00Z",
                "2021-01-01T00:02:00Z", 200, 210);
        ObjectNode corrected = correctForTest(frozen(book(1000, 1020, first, second), book(1000, 1000)));
        ArrayNode curve = (ArrayNode) corrected.path("portfolio").path("combined_equity_curve");
        assertThat(curve.findValuesAsText("marked_holdings_usdt").subList(0, 2))
                .containsExactly("100.0", "300.0");
        assertThat(curve.findValuesAsText("cash_usdt").subList(0, 2))
                .containsExactly("1900.0", "1700.0");
    }

    @Test
    void acceptsOnlyTheDeclaredLongSpotOptionalContract() {
        ObjectNode supported = trade("optional", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110);
        supported.put("direction", "LONG").put("instrument_type", "spot").put("instrument", "SPOT")
                .put("funding_usdt", 0D).put("funding_fee_usdt", 0D).put("borrow_fee_usdt", 0D)
                .put("margin_usdt", 0D).put("leverage", 1D);
        ObjectNode corrected = correctForTest(frozen(book(1000, 1010, supported), book(1000, 1000)));
        assertThat(corrected.path("portfolio").path("event_book").path("trade_count").asInt()).isOne();

        ObjectNode numericDirection = trade("numeric-direction", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110).put("direction", 1);
        assertThatThrownBy(() -> correctForTest(frozen(book(1000, 1010, numericDirection), book(1000, 1000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("malformed direction");

        ObjectNode numericInstrumentType = trade("numeric-instrument-type", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110).put("instrument_type", 1);
        assertThatThrownBy(() -> correctForTest(frozen(book(1000, 1010, numericInstrumentType), book(1000, 1000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("malformed instrument_type");

        ObjectNode badFunding = trade("bad-funding", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110).put("funding_usdt", 1D);
        assertThatThrownBy(() -> correctForTest(frozen(book(1000, 1010, badFunding), book(1000, 1000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("funding_usdt");

        ObjectNode boundaryFunding = trade("boundary-funding", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110).put("funding_usdt", 1e-8D);
        ObjectNode boundaryResult = correctForTest(frozen(book(1000, 1010, boundaryFunding), book(1000, 1000)));
        assertThat(boundaryResult.path("portfolio").path("event_book").path("trade_count").asInt()).isOne();

        ObjectNode leveraged = trade("leveraged", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110).put("leverage", 2D);
        assertThatThrownBy(() -> correctForTest(frozen(book(1000, 1010, leveraged), book(1000, 1000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unlevered spot");
    }

    @Test
    void validatesTheNestedLifecycleContractAndExitFunding() {
        ObjectNode supported = trade("nested-supported", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110);
        ObjectNode lifecycle = (ObjectNode) supported.path("lifecycle");
        lifecycle.put("direction", "long").put("instrument_type", "SPOT")
                .put("contract_multiplier", 1D).put("funding_usd", 0D);
        ((ObjectNode) lifecycle.path("exits").get(0)).put("funding_usd", 0D);
        assertThat(correctForTest(frozen(book(1000, 1010, supported), book(1000, 1000)))
                .path("portfolio").path("event_book").path("trade_count").asInt()).isOne();

        ObjectNode shortLifecycle = trade("nested-short", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110);
        ((ObjectNode) shortLifecycle.path("lifecycle")).put("direction", "short");
        assertThatThrownBy(() -> correctForTest(frozen(book(1000, 1010, shortLifecycle), book(1000, 1000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("long spot");

        ObjectNode derivativeLifecycle = trade("nested-derivative", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110);
        ((ObjectNode) derivativeLifecycle.path("lifecycle")).put("instrument_type", "PERPETUAL");
        assertThatThrownBy(() -> correctForTest(frozen(book(1000, 1010, derivativeLifecycle), book(1000, 1000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("spot instruments");

        ObjectNode scaledLifecycle = trade("nested-scaled", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110);
        ((ObjectNode) scaledLifecycle.path("lifecycle")).put("contract_multiplier", 2D);
        assertThatThrownBy(() -> correctForTest(frozen(book(1000, 1010, scaledLifecycle), book(1000, 1000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contract multipliers");

        ObjectNode fundedExit = trade("nested-funded-exit", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110);
        ((ObjectNode) fundedExit.path("lifecycle").path("exits").get(0)).put("funding_usd", 1D);
        assertThatThrownBy(() -> correctForTest(frozen(book(1000, 1010, fundedExit), book(1000, 1000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("funding_usd");
    }

    @Test
    void validationReplayRejectsNestedUnsupportedContractBeforeRecomputation() {
        ObjectNode corrected = correctForTest(frozen(book(1000, 1010,
                trade("replay-nested", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 110)),
                book(1000, 1000)));
        ObjectNode portfolio = (ObjectNode) corrected.path("portfolio");
        ((ObjectNode) portfolio.path("event_book").path("trades").get(0).path("lifecycle"))
                .put("contract_multiplier", 2D);

        assertThatThrownBy(() -> StrategyFixedBaselineCorrectedV1.correctedPortfolioForValidation(portfolio))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contract multipliers");
    }

    @Test
    void rejectsMalformedBooksLifecycleAndBoundaryEvidence() {
        ObjectNode nonArrayTrades = book(1000, 1000);
        nonArrayTrades.put("trades", "not-an-array");
        assertThatThrownBy(() -> correctForTest(frozen(nonArrayTrades, book(1000, 1000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("trades must be an array");

        ObjectNode nonObjectTradeSource = frozen(book(1000, 1000), book(1000, 1000));
        ((ArrayNode) nonObjectTradeSource.path("portfolio").path("event_book").path("trades")).add("bad-trade");
        rehash(nonObjectTradeSource);
        assertThatThrownBy(() -> correctForTest(nonObjectTradeSource))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("closed trade must be an object");

        ObjectNode malformedMarks = trade("marks-object", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110);
        malformedMarks.set("portfolio_mark_points", JsonHashes.mapper().createObjectNode());
        assertThatThrownBy(() -> correctForTest(frozen(book(1000, 1010, malformedMarks), book(1000, 1000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("portfolio_mark_points");

        ObjectNode afterExit = trade("mark-after-exit", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110);
        afterExit.putArray("portfolio_mark_points").addObject()
                .put("time", "2021-01-01T00:01:01Z").put("price", 110);
        assertThatThrownBy(() -> correctForTest(frozen(book(1000, 1010, afterExit), book(1000, 1000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("after exit");

        ObjectNode beforeEntry = trade("mark-before-entry", "2021-01-01T00:01:00Z",
                "2021-01-01T00:02:00Z", 100, 110);
        beforeEntry.putArray("portfolio_mark_points").addObject()
                .put("time", "2021-01-01T00:00:59Z").put("price", 100);
        assertThatThrownBy(() -> correctForTest(frozen(book(1000, 1010, beforeEntry), book(1000, 1000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("precedes entry");

        ObjectNode duplicateMark = trade("duplicate-mark", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110);
        duplicateMark.putArray("portfolio_mark_points").addObject()
                .put("time", "2021-01-01T00:00:30Z").put("price", 105);
        ((ArrayNode) duplicateMark.path("portfolio_mark_points")).addObject()
                .put("time", "2021-01-01T00:00:30.000+00:00").put("price", 106);
        assertThatThrownBy(() -> correctForTest(frozen(book(1000, 1010, duplicateMark), book(1000, 1000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate portfolio mark");

        ObjectNode multipleExits = trade("multiple-exits", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110);
        ((ArrayNode) multipleExits.path("lifecycle").path("exits")).addObject()
                .put("time", "2021-01-01T00:02:00Z").put("availability_time", "2021-01-01T00:02:00Z")
                .put("price", 110);
        assertThatThrownBy(() -> correctForTest(frozen(book(1000, 1010, multipleExits), book(1000, 1000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("one complete exit");

        ObjectNode partialExit = trade("partial-exit", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110);
        ((ObjectNode) partialExit.path("lifecycle").path("exits").get(0)).put("fraction", .5D);
        assertThatThrownBy(() -> correctForTest(frozen(book(1000, 1010, partialExit), book(1000, 1000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exit fraction");

        ObjectNode residualLifecycle = trade("residual", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110);
        ((ObjectNode) residualLifecycle.path("lifecycle")).put("remaining_quantity", 1D);
        assertThatThrownBy(() -> correctForTest(frozen(book(1000, 1010, residualLifecycle), book(1000, 1000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("remaining quantity");

        ObjectNode badDeclarationTrade = trade("bad-declaration", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110);
        assertThatThrownBy(() -> correctForTest(frozen(book(1000, 1009, badDeclarationTrade), book(1000, 1000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ending equity");
    }

    @Test
    void rejectsInvalidBindingsAndReplacesOnlyCompleteAttemptProjections() {
        assertThatThrownBy(() -> StrategyFixedBaselineCorrectedV1.correctFrozenResultForTest(
                frozen(book(1000, 1000), book(1000, 1000)), "not-a-hash"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SHA-256");
        assertThatThrownBy(() -> StrategyFixedBaselineCorrectedV1.correctFrozenResultForTest(
                null, "b".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("object frozen result");

        ObjectNode refinement = frozen(book(1000, 1000), book(1000, 1000));
        refinement.put("schema", "strategy-fixed-refinement-member-result/1");
        rehash(refinement);
        assertThat(correctForTest(refinement).path("source_result_schema").asText())
                .isEqualTo("strategy-fixed-refinement-member-result/1");

        ObjectNode noMetrics = frozen(book(1000, 1000), book(1000, 1000));
        noMetrics.remove("metrics");
        rehash(noMetrics);
        assertThat(correctForTest(noMetrics).path("metrics").path("portfolio_equity_recomputed").asBoolean()).isTrue();

        ObjectNode wrongEvaluator = frozen(book(1000, 1000), book(1000, 1000));
        ((ObjectNode) wrongEvaluator.path("evaluator")).put("name", "OtherEvaluator");
        rehash(wrongEvaluator);
        assertThatThrownBy(() -> correctForTest(wrongEvaluator))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TradeLifecycleV5");

        ObjectNode missingBinding = frozen(book(1000, 1000), book(1000, 1000));
        missingBinding.remove("baseline_sha256");
        rehash(missingBinding);
        assertThatThrownBy(() -> correctForTest(missingBinding))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("baseline_sha256");

        ObjectNode attempts = frozen(book(1000, 1010,
                trade("attempt-event", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 110)),
                book(1000, 1000));
        ArrayNode rows = attempts.putArray("attempts");
        rows.add("ignored");
        rows.addObject().put("status", "INCOMPLETE").set("event_trade", JsonHashes.mapper().createObjectNode());
        rows.addObject().put("status", "COMPLETE").set("event_trade", JsonHashes.mapper().createObjectNode());
        rehash(attempts);
        ObjectNode corrected = correctForTest(attempts);
        assertThat(corrected.path("attempts")).hasSize(3);
        assertThat(corrected.path("attempts").get(2).has("control_trade")).isFalse();
    }

    @Test
    void supportsSameInstantBookTieBreakAndRejectsUnknownInstrument() {
        ObjectNode event = trade("same-time-event", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110);
        ObjectNode control = trade("same-time-control", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 90);
        ObjectNode corrected = correctForTest(frozen(book(1000, 1010, event), book(1000, 990, control)));
        assertThat(corrected.path("portfolio").path("combined_equity_curve")).hasSize(4);

        ObjectNode unknownInstrument = trade("unknown-instrument", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110).put("instrument", "OTHER");
        assertThatThrownBy(() -> correctForTest(frozen(book(1000, 1010, unknownInstrument), book(1000, 1000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("spot instruments");

        ObjectNode blankInstrument = trade("blank-instrument", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110).put("instrument", "");
        assertThatThrownBy(() -> correctForTest(frozen(book(1000, 1010, blankInstrument), book(1000, 1000))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("spot instruments");
    }

    @Test
    void rewritesAttemptTradeProjectionsSoNoStaleMarkCanBeConsumedDownstream() {
        ObjectNode event = trade("event", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 110);
        event.putArray("portfolio_mark_points").addObject().put("time", "2021-01-01T00:01:00Z").put("price", 999);
        // Event and control books may use the same episode id; replacement is
        // role-scoped so one book can never consume the other's corrected row.
        ObjectNode control = trade("event", "2021-01-01T00:02:00Z", "2021-01-01T00:03:00Z", 100, 90);
        control.putArray("portfolio_mark_points").addObject()
                .put("time", "2021-01-01T00:03:00Z").put("price", 90);
        ObjectNode frozen = frozen(book(1000, 1010, event), book(1000, 990, control));
        ObjectNode attempt = frozen.putArray("attempts").addObject();
        attempt.put("status", "COMPLETE").put("event_id", "event").put("paired_net_pnl_usdt", -999);
        attempt.set("event_trade", event.deepCopy());
        attempt.set("control_trade", control.deepCopy());
        frozen.put("content_sha256", JsonHashes.ownHash(frozen));

        ObjectNode corrected = correctForTest(frozen);
        assertThat(corrected.path("attempts").get(0).path("event_trade").path("portfolio_mark_points")).isEmpty();
        assertThat(corrected.path("attempts").get(0).path("control_trade").path("portfolio_mark_points")).isEmpty();
        assertThat(corrected.path("attempts").get(0).path("paired_net_pnl_usdt").asDouble()).isEqualTo(20D);
    }

    @Test
    void rejectsUnboundOrAlreadyCorrectedSourceAndUnsupportedBookShape() {
        ObjectNode valid = frozen(book(1000, 1000), book(1000, 1000));
        ObjectNode badHash = valid.deepCopy().put("content_sha256", "b".repeat(64));
        assertThatThrownBy(() -> correctForTest(badHash))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("content hash");

        ObjectNode corrected = valid.deepCopy().put("correction_status", "CORRECTED");
        corrected.put("content_sha256", JsonHashes.ownHash(corrected));
        assertThatThrownBy(() -> correctForTest(corrected))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already corrected");

        ObjectNode noControl = valid.deepCopy();
        ((ObjectNode) noControl.path("portfolio")).remove("control_book");
        noControl.put("content_sha256", JsonHashes.ownHash(noControl));
        assertThatThrownBy(() -> correctForTest(noControl))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("control_book");

        ObjectNode shortTrade = trade("short", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 90);
        shortTrade.put("direction", "short");
        ObjectNode unsupported = frozen(book(1000, 990, shortTrade), book(1000, 1000));
        assertThatThrownBy(() -> correctForTest(unsupported))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("long spot");

        ObjectNode controlShort = trade("control-short", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 90).put("direction", "short");
        ObjectNode unsupportedControl = frozen(book(1000, 1000), book(1000, 990, controlShort));
        assertThatThrownBy(() -> correctForTest(unsupportedControl))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("long spot");

        ObjectNode blankControlDirection = trade("blank-control-direction",
                "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 110)
                .put("direction", "");
        ObjectNode malformedControl = frozen(book(1000, 1000), book(1000, 1010, blankControlDirection));
        assertThatThrownBy(() -> correctForTest(malformedControl))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("malformed direction");

        ObjectNode derivative = trade("derivative", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 90);
        derivative.put("instrument_type", "PERPETUAL");
        ObjectNode unsupportedDerivative = frozen(book(1000, 990, derivative), book(1000, 1000));
        assertThatThrownBy(() -> correctForTest(unsupportedDerivative))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("spot instruments");

        ObjectNode malformed = trade("malformed", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 90);
        malformed.put("leverage", "2");
        ObjectNode malformedBook = frozen(book(1000, 990, malformed), book(1000, 1000));
        assertThatThrownBy(() -> correctForTest(malformedBook))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("malformed leverage");

        ObjectNode blankDirection = trade("blank-direction", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110).put("direction", "");
        ObjectNode blankDirectionBook = frozen(book(1000, 1010, blankDirection), book(1000, 1000));
        assertThatThrownBy(() -> correctForTest(blankDirectionBook))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("malformed direction");

        ObjectNode malformedFunding = trade("malformed-funding", "2021-01-01T00:00:00Z",
                "2021-01-01T00:01:00Z", 100, 110).put("funding_usdt", "0");
        ObjectNode malformedFundingBook = frozen(book(1000, 1010, malformedFunding), book(1000, 1000));
        assertThatThrownBy(() -> correctForTest(malformedFundingBook))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("funding_usdt");
    }

    @Test
    void rejectsNonFrozenSchemaAndMissingExecutableIdentity() {
        ObjectNode wrongSchema = frozen(book(1000, 1000), book(1000, 1000));
        wrongSchema.put("schema", "other/1").put("content_sha256", JsonHashes.ownHash(wrongSchema));
        assertThatThrownBy(() -> correctForTest(wrongSchema))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("frozen V5 result schema");

        ObjectNode missingIdentity = frozen(book(1000, 1000), book(1000, 1000));
        missingIdentity.remove("executor_identity_sha256");
        missingIdentity.put("content_sha256", JsonHashes.ownHash(missingIdentity));
        assertThatThrownBy(() -> correctForTest(missingIdentity))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("executable identity");

        ObjectNode productionInput = frozen(book(1000, 1000), book(1000, 1000));
        ObjectNode productionIdentity = BuildIdentityService.describe(StrategyFixedBaselineCorrectedV1.class);
        ObjectNode executable = productionIdentity.path("executable").isObject()
                ? (ObjectNode) productionIdentity.path("executable") : JsonHashes.mapper().createObjectNode();
        if (!"JAR".equals(executable.path("kind").asText())) {
            assertThatThrownBy(() -> StrategyFixedBaselineCorrectedV1.correctFrozenResult(productionInput))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("packaged executable identity");
        }
    }

    @Test
    void correctedEconomicDigestIgnoresRelocatedCustodyProvenance() {
        ObjectNode first = frozen(book(1000, 1010,
                trade("event", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 110)),
                book(1000, 990,
                        trade("control", "2021-01-01T00:02:00Z", "2021-01-01T00:03:00Z", 100, 90)));
        first.putObject("build_identity").putObject("executable").put("code_source", "C:/worker-a/app.jar");
        first.put("executor_identity_sha256", "1".repeat(64))
                .put("attempt_identity_sha256", "2".repeat(64))
                .put("exposure_head_sha256", "3".repeat(64));
        ((ObjectNode) first.path("portfolio").path("event_book")).put("portfolio_policy_path", "C:/worker-a/policy.json");
        ((ObjectNode) first.path("portfolio").path("event_book")).put("attempt_identity_sha256", "4".repeat(64));
        ((ObjectNode) first.path("portfolio").path("control_book")).put("portfolio_policy_path", "C:/worker-a/policy.json");
        ((ObjectNode) first.path("portfolio").path("control_book")).put("exposure_head_sha256", "5".repeat(64));
        first.putArray("attempts").addObject().put("attempt_identity_sha256", "9".repeat(64))
                .put("attempt_label", "same-economic-attempt");
        first.put("content_sha256", JsonHashes.ownHash(first));

        ObjectNode second = first.deepCopy();
        ((ObjectNode) second.path("build_identity").path("executable")).put("code_source", "D:/worker-b/app.jar");
        second.put("executor_identity_sha256", "6".repeat(64))
                .put("attempt_identity_sha256", "7".repeat(64))
                .put("exposure_head_sha256", "8".repeat(64));
        ((ObjectNode) second.path("portfolio").path("event_book")).put("portfolio_policy_path", "D:/worker-b/policy.json");
        ((ObjectNode) second.path("portfolio").path("event_book")).put("attempt_identity_sha256", "9".repeat(64));
        ((ObjectNode) second.path("portfolio").path("control_book")).put("portfolio_policy_path", "D:/worker-b/policy.json");
        ((ObjectNode) second.path("portfolio").path("control_book")).put("exposure_head_sha256", "a".repeat(64));
        ((ObjectNode) second.path("attempts").get(0)).put("attempt_identity_sha256", "b".repeat(64));
        second.put("content_sha256", JsonHashes.ownHash(second));

        ObjectNode correctedFirst = correctForTest(first);
        ObjectNode correctedSecond = correctForTest(second);
        assertThat(correctedFirst.path("source_result_content_sha256").asText())
                .isNotEqualTo(correctedSecond.path("source_result_content_sha256").asText());
        assertThat(correctedFirst.path("corrected_economic_semantic_sha256").asText())
                .isEqualTo(correctedSecond.path("corrected_economic_semantic_sha256").asText());
    }

    @Test
    void filteredCorrectionDigestsMatchMaterializedReferenceScopes() {
        ObjectNode corrected = correctForTest(frozen(book(1000, 1010,
                trade("scope", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 110)),
                book(1000, 1000)));

        ObjectNode economic = corrected.deepCopy();
        removeRootDigestFields(economic);
        stripEconomicProvenance(economic);
        assertThat(corrected.path("corrected_economic_semantic_sha256").asText())
                .isEqualTo(JsonHashes.sha256(JsonHashes.canonicalBytes(economic)));

        ObjectNode semantic = corrected.deepCopy();
        removeRootDigestFields(semantic);
        assertThat(corrected.path("corrected_semantic_sha256").asText())
                .isEqualTo(JsonHashes.sha256(JsonHashes.canonicalBytes(semantic)));
        assertThat(corrected.path("portfolio").path("event_book").path("content_sha256").asText())
                .isNotBlank();
    }

    @Test
    void correctedEconomicDigestChangesWhenEconomicTradeChanges() {
        ObjectNode first = frozen(book(1000, 1010,
                trade("event", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 110)),
                book(1000, 1000));
        ObjectNode changed = first.deepCopy();
        ObjectNode changedTrade = (ObjectNode) changed.path("portfolio").path("event_book")
                .path("trades").get(0);
        changedTrade.put("exit_price", 111D).put("gross_pnl_usdt", 11D).put("net_pnl_usdt", 11D);
        ((ObjectNode) changedTrade.path("lifecycle").path("exits").get(0)).put("price", 111D);
        ((ObjectNode) changed.path("portfolio").path("event_book")).put("ending_equity_usdt", 1011D);
        changed.put("content_sha256", JsonHashes.ownHash(changed));
        ObjectNode correctedFirst = correctForTest(first);
        ObjectNode correctedChanged = correctForTest(changed);
        assertThat(correctedFirst.path("corrected_economic_semantic_sha256").asText())
                .isNotEqualTo(correctedChanged.path("corrected_economic_semantic_sha256").asText());
    }

    @Test
    void validatesCorrectedEconomicDigestSeamAndRejectsUnboundInputs() {
        ObjectNode corrected = correctForTest(frozen(book(1000, 1010,
                trade("digest", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 110)),
                book(1000, 1000)));
        assertThat(StrategyFixedBaselineCorrectedV1.correctedEconomicSha256ForValidation(corrected))
                .isEqualTo(corrected.path("corrected_economic_semantic_sha256").asText());
        assertThatThrownBy(() -> StrategyFixedBaselineCorrectedV1.correctedEconomicSha256ForValidation(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("corrected evaluator result");
        ObjectNode wrongSchema = corrected.deepCopy().put("schema", "other/1");
        assertThatThrownBy(() -> StrategyFixedBaselineCorrectedV1.correctedEconomicSha256ForValidation(wrongSchema))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("corrected evaluator result");
    }

    @Test
    void correctedResultAndMetricsSatisfyAuthoritativeSchemas() {
        ObjectNode corrected = correctForTest(frozen(book(1000, 1010,
                trade("schema", "2021-01-01T00:00:00Z", "2021-01-01T00:01:00Z", 100, 110)),
                book(1000, 1000)));
        ResearchSchemaRegistry schemas = ResearchSchemaRegistry.defaultRegistry();
        assertThat(schemas.hasContractSchema("strategy-fixed-baseline-corrected-result/1")).isTrue();
        assertThat(schemas.hasContractSchema("strategy-fixed-baseline-corrected-portfolio-metrics/1")).isTrue();
        assertThat(schemas.validateKnownContractSchema(corrected)).isTrue();
        assertThat(schemas.validateKnownContractSchema(corrected.path("corrected_portfolio_metrics"))).isTrue();
    }

    private static ObjectNode frozen(ObjectNode event, ObjectNode control) {
        ObjectNode result = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-fixed-baseline-result/1").put("version", 1)
                .put("stage", "FIXED_BASELINE").put("executor_identity_sha256", EXECUTOR)
                .put("baseline_sha256", "c".repeat(64)).put("control_spec_sha256", "d".repeat(64))
                .put("experiment_sha256", "e".repeat(64)).put("physical_input_sha256", "f".repeat(64));
        result.putObject("evaluator").put("name", "TradeLifecycleV5");
        result.putObject("metrics").put("max_drawdown_usdt", 999D).put("net_pnl_usdt", 999D);
        ObjectNode portfolio = result.putObject("portfolio");
        portfolio.set("event_book", event);
        portfolio.set("control_book", control);
        result.put("content_sha256", JsonHashes.ownHash(result));
        return result;
    }

    private static ObjectNode correctForTest(ObjectNode frozen) {
        return StrategyFixedBaselineCorrectedV1.correctFrozenResultForTest(frozen, "b".repeat(64));
    }

    private static ObjectNode rehash(ObjectNode frozen) {
        frozen.put("content_sha256", JsonHashes.ownHash(frozen));
        return frozen;
    }

    private static void removeRootDigestFields(ObjectNode value) {
        value.remove("content_sha256");
        value.remove("corrected_economic_semantic_sha256");
        value.remove("corrected_semantic_sha256");
        value.remove("build_identity");
    }

    private static void stripEconomicProvenance(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (name.equals("content_sha256") || name.endsWith("_path") || name.equals("path")
                        || name.equals("physical_root_reference") || name.equals("source_build_identity")
                        || name.equals("build_identity") || name.equals("source_evaluator_identity")
                        || name.equals("evaluator_identity") || name.equals("corrected_evaluator_identity")
                        || name.equals("source_result_schema") || name.equals("source_result_content_sha256")
                        || name.equals("source_executor_identity_sha256")
                        || name.equals("corrected_executor_identity_sha256")
                        || name.equals("executor_identity_sha256") || name.equals("attempt_identity_sha256")
                        || name.equals("exposure_head_sha256") || name.equals("source_fingerprint")
                        || name.equals("source_fingerprint_provenance")
                        || name.equals("source_input_canonical_sha256")
                        || name.equals("correction_input_canonical_sha256")
                        || name.equals("corrected_input_binding_sha256")
                        || name.equals("event_book_correction_sha256")
                        || name.equals("control_book_correction_sha256") || name.equals("algorithm_fingerprint")
                        || name.equals("accounting_version") || name.equals("correction_receipt")
                        || name.equals("evaluator") || name.equals("legacy_book_schema")
                        || name.equals("legacy_book_content_sha256")) {
                    object.remove(name);
                } else {
                    stripEconomicProvenance(object.get(name));
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(StrategyFixedBaselineCorrectedV1Test::stripEconomicProvenance);
        }
    }

    private static ObjectNode book(double starting, double ending, ObjectNode... trades) {
        ObjectNode book = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-fixed-baseline-portfolio-book/legacy")
                .put("starting_equity_usdt", starting).put("ending_equity_usdt", ending);
        ArrayNode rows = book.putArray("trades");
        for (ObjectNode trade : trades) rows.add(trade);
        return book;
    }

    private static ObjectNode trade(String id, String entry, String exit,
            double entryPrice, double exitPrice) {
        ObjectNode trade = JsonHashes.mapper().createObjectNode()
                .put("episode_id", id).put("entry_price", entryPrice).put("exit_price", exitPrice)
                .put("quantity", 1D).put("gross_pnl_usdt", exitPrice - entryPrice)
                .put("fees_usdt", 0D).put("slippage_usdt", 0D).put("capacity_debit_usdt", 0D)
                .put("net_pnl_usdt", exitPrice - entryPrice);
        ObjectNode lifecycle = trade.putObject("lifecycle").put("entry_time", entry);
        lifecycle.putArray("exits").addObject().put("time", exit).put("availability_time", exit)
                .put("price", exitPrice).put("fees_usd", 0D).put("slippage_usd", 0D);
        return trade;
    }

    private static ObjectNode tradeWithCosts(String id, String entry, String exit,
            double entryPrice, double exitPrice, double fees, double slippage, double capacity) {
        ObjectNode trade = trade(id, entry, exit, entryPrice, exitPrice);
        double gross = (exitPrice - entryPrice) * trade.path("quantity").asDouble();
        trade.put("fees_usdt", fees).put("slippage_usdt", slippage).put("capacity_debit_usdt", capacity)
                .put("net_pnl_usdt", gross - fees - slippage - capacity);
        ObjectNode exitRow = (ObjectNode) trade.path("lifecycle").path("exits").get(0);
        exitRow.put("fees_usd", fees / 2D).put("slippage_usd", slippage / 2D);
        return trade;
    }
}

package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Independent economic and clock-boundary checks; hashes here identify test-only inputs. */
final class StrategyProspectiveReconciliationReviewTest {
    @Test
    void paperArithmeticReconcilesWithoutInventingObservedFills() {
        ObjectNode result = StrategyProspectiveOutcomeReconciliationV1.reconcile(input());
        assertThat(result.path("paper_status").asText()).isEqualTo("NUMERICALLY_RECONCILED");
        assertThat(result.path("expected_gross_pnl_usdt").decimalValue()).isEqualByComparingTo(new BigDecimal("30"));
        assertThat(result.path("expected_fees_usdt").decimalValue()).isEqualByComparingTo(new BigDecimal("0.53"));
        assertThat(result.path("expected_slippage_usdt").decimalValue()).isEqualByComparingTo(new BigDecimal("0.265"));
        assertThat(result.path("expected_net_pnl_usdt").asDouble()).isCloseTo(29.005, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.path("observed_fill_status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(result.path("trades").get(0).has("observed_net_pnl_usdt")).isFalse();
    }

    @Test
    void aTradeCannotOverrideTheAuthoritativeMaturityClock() {
        ObjectNode input = input().put("maturity_as_of", "2024-01-01T12:00:00Z");
        trade(input).put("maturity_as_of", "2024-01-03T00:00:00Z");
        assertInvalid(input);
    }

    @Test
    void duplicatePaperTradeIdsCannotDoubleTheResult() {
        ObjectNode input = input();
        input.withArray("paper_trades").add(trade(input).deepCopy());
        assertInvalid(input);
    }

    @Test
    void aReportedNetAmountAloneIsNotObservedFillEvidence() {
        ObjectNode input = input();
        input.putArray("observed_fills").addObject().put("trade_id", "review-trade").put("net_pnl_usdt", 999);
        ObjectNode result = StrategyProspectiveOutcomeReconciliationV1.reconcile(input);
        assertThat(result.path("observed_fill_status").asText()).isIn("UNAVAILABLE", "INVALID");
        assertThat(result.path("trades").get(0).has("observed_net_pnl_usdt")).isFalse();
    }

    @Test
    void anIncorrectReportedNetCannotPassBecauseItsInputsAreWellFormed() {
        ObjectNode input = input(); trade(input).put("net_pnl_usdt", 30);
        assertInvalid(input);
    }

    @Test
    void numericStringsAreNotTypedPrices() {
        ObjectNode input = input(); trade(input).put("entry_price", "100");
        assertInvalid(input);
    }

    @Test
    void fundingCannotBeSilentlyDroppedFromANumericClaim() {
        ObjectNode input = input(); trade(input).put("funding_usdt", 3);
        try {
            ObjectNode result = StrategyProspectiveOutcomeReconciliationV1.reconcile(input);
            if ("NUMERICALLY_RECONCILED".equals(result.path("paper_status").asText())) {
                assertThat(result.path("expected_net_pnl_usdt").asDouble())
                        .isCloseTo(26.005, org.assertj.core.data.Offset.offset(1e-12));
            } else assertThat(result.path("paper_status").asText()).isEqualTo("INVALID");
        } catch (IllegalArgumentException acceptedRejection) {
            // An explicitly unsupported funding-bearing instrument must fail closed.
        }
    }

    @Test
    void aDifferentCurrencyCannotBeSilentlyRelabeledAsUsdt() {
        assertInvalid(input().put("account_currency", "EUR"));
    }

    @Test
    void genuineObservedExecutionDifferencesProduceADeltaRatherThanAnError() {
        ObjectNode input = input(); addObserved(input, 23.76);
        ObjectNode result = StrategyProspectiveOutcomeReconciliationV1.reconcile(input);
        assertThat(result.path("paper_status").asText()).isEqualTo("NUMERICALLY_RECONCILED");
        assertThat(result.path("observed_fill_status").asText()).isEqualTo("AVAILABLE");
        var row = result.path("trades").get(0);
        assertThat(row.path("observed_net_pnl_usdt").asDouble()).isCloseTo(23.76, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(row.path("observed_minus_expected_net_pnl_usdt").asDouble())
                .isCloseTo(-5.245, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void aFalseReportedObservedProfitCannotOverrideFillArithmetic() {
        ObjectNode input = input(); addObserved(input, 999);
        try {
            ObjectNode result = StrategyProspectiveOutcomeReconciliationV1.reconcile(input);
            assertThat("INVALID".equals(result.path("paper_status").asText())
                    || !"AVAILABLE".equals(result.path("observed_fill_status").asText())).isTrue();
        } catch (IllegalArgumentException acceptedRejection) {
            // Malformed observed claims may also be rejected at the API boundary.
        }
    }

    private static void addObserved(ObjectNode input, double reportedNet) {
        input.putArray("observed_fills").addObject().put("trade_id", "review-trade").put("direction", "long")
                .put("quantity", 2.5).put("entry_price", 101).put("exit_price", 111)
                .put("fees_usdt", .54).put("slippage_usdt", .4).put("capacity_debit_usdt", .3)
                .put("net_pnl_usdt", reportedNet);
    }

    private static void assertInvalid(ObjectNode input) {
        try {
            ObjectNode result = StrategyProspectiveOutcomeReconciliationV1.reconcile(input);
            assertThat(result.path("paper_status").asText()).isEqualTo("INVALID");
        } catch (IllegalArgumentException acceptedRejection) {
            // Both fail-closed API styles are permitted; a reconciled result is not.
        }
    }

    private static ObjectNode trade(ObjectNode input) { return (ObjectNode) input.path("paper_trades").get(0); }

    private static ObjectNode input() {
        ObjectNode input = JsonHashes.mapper().createObjectNode()
                .put("schema", StrategyProspectiveOutcomeReconciliationV1.INPUT_SCHEMA).put("version", 1)
                .put("maturity_as_of", "2024-01-03T00:00:00Z");
        input.putObject("source_bindings").put("outcome_resolution_source_sha256", "a".repeat(64))
                .put("label_source_sha256", "b".repeat(64)).put("execution_source_sha256", "c".repeat(64));
        input.putArray("paper_trades").addObject().put("trade_id", "review-trade").put("direction", "long")
                .put("quantity", 2.5).put("entry_price", 100).put("exit_price", 112)
                .put("entry_time", "2024-01-01T00:00:00Z").put("exit_time", "2024-01-02T00:00:00Z")
                .put("resolution_time", "2024-01-02T01:00:00Z")
                .put("fee_rate", .001).put("slippage_rate", .0005).put("capacity_debit_usdt", .2);
        return input;
    }
}

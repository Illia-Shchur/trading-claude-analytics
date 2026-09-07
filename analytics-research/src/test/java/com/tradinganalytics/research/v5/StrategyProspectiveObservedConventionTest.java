package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import org.junit.jupiter.api.Test;

/** Observed fill prices carry an explicit cost convention. */
final class StrategyProspectiveObservedConventionTest {
    @Test
    void actualFillPricesDoNotDoubleChargeModeledSlippageOrCapacity() {
        ObjectNode input = input();
        input.put("observed_price_convention", "ACTUAL_FILL_PRICES");
        input.putArray("observed_fills").addObject().put("trade_id", "review-trade")
                .put("direction", "long").put("quantity", 2.5).put("entry_price", 101)
                .put("exit_price", 111).put("fees_usdt", .54).put("slippage_usdt", 0)
                .put("capacity_debit_usdt", 0).put("net_pnl_usdt", 24.46);
        ObjectNode result = StrategyProspectiveOutcomeReconciliationV1.reconcile(input);
        assertThat(result.path("observed_price_convention").asText()).isEqualTo("ACTUAL_FILL_PRICES");
        assertThat(result.path("trades").get(0).path("observed_net_pnl_usdt").asDouble())
                .isCloseTo(24.46, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void actualFillPricesRejectSeparateModeledSlippage() {
        ObjectNode input = input();
        input.put("observed_price_convention", "ACTUAL_FILL_PRICES");
        input.putArray("observed_fills").addObject().put("trade_id", "review-trade")
                .put("direction", "long").put("quantity", 2.5).put("entry_price", 101)
                .put("exit_price", 111).put("fees_usdt", .54).put("slippage_usdt", .4)
                .put("capacity_debit_usdt", 0).put("net_pnl_usdt", 23.06);
        ObjectNode result = StrategyProspectiveOutcomeReconciliationV1.reconcile(input);
        assertThat(result.path("paper_status").asText()).isEqualTo("INVALID");
    }

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

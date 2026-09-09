package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import org.junit.jupiter.api.Test;

class StrategyProspectiveOutcomeReconciliationV1Test {
    private static final String HASH = "a".repeat(64);

    @Test
    void reconcilesPaperLifecycleAndLeavesObservedFillsUnavailable() {
        ObjectNode result = StrategyProspectiveOutcomeReconciliationV1.reconcile(input(false, false));
        assertThat(result.path("paper_status").asText()).isEqualTo("NUMERICALLY_RECONCILED");
        assertThat(result.path("observed_fill_status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(result.path("expected_gross_pnl_usdt").asDouble()).isEqualTo(10D);
        assertThat(result.path("expected_net_pnl_usdt").asDouble()).isEqualTo(-6.25D);
        assertThat(result.path("paper_claim_is_observed_fill").asBoolean()).isFalse();
        assertThat(ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(result)).isTrue();
    }

    @Test
    void reportsObservedDeltaWithoutCallingItArithmeticFailure() {
        ObjectNode result = StrategyProspectiveOutcomeReconciliationV1.reconcile(input(true, false));
        assertThat(result.path("paper_status").asText()).isEqualTo("NUMERICALLY_RECONCILED");
        assertThat(result.path("observed_fill_status").asText()).isEqualTo("AVAILABLE");
        assertThat(result.path("trades").get(0).path("observed_minus_expected_net_pnl_usdt").asDouble()).isEqualTo(.75D);
        assertThat(result.path("errors")).isEmpty();
    }

    @Test
    void rejectsWrongPaperArithmeticAndPrematureResolutionAsInvalid() {
        ObjectNode wrongArithmetic = input(false, false);
        ((ObjectNode) wrongArithmetic.path("paper_trades").get(0)).put("net_pnl_usdt", 999D);
        ObjectNode arithmeticResult = StrategyProspectiveOutcomeReconciliationV1.reconcile(wrongArithmetic);
        assertThat(arithmeticResult.path("paper_status").asText()).isEqualTo("INVALID");
        assertThat(arithmeticResult.path("errors").toString()).contains("net_pnl_usdt");

        ObjectNode tooEarly = input(false, true);
        ObjectNode earlyResult = StrategyProspectiveOutcomeReconciliationV1.reconcile(tooEarly);
        assertThat(earlyResult.path("paper_status").asText()).isEqualTo("INVALID");
        assertThat(earlyResult.path("errors").toString()).contains("maturity_as_of");

        ObjectNode duplicate = input(false, false);
        duplicate.withArray("paper_trades").add(duplicate.path("paper_trades").get(0).deepCopy());
        ObjectNode duplicateResult = StrategyProspectiveOutcomeReconciliationV1.reconcile(duplicate);
        assertThat(duplicateResult.path("paper_status").asText()).isEqualTo("INVALID");
        assertThat(duplicateResult.path("errors").toString()).contains("duplicate paper trade_id");
    }

    @Test
    void requiresTypedSourceBindingsBeforeReadingAnyOutcome() {
        ObjectNode invalid = input(false, false);
        ((ObjectNode) invalid.path("source_bindings")).put("execution_source_sha256", "bad");
        assertThatThrownBy(() -> StrategyProspectiveOutcomeReconciliationV1.reconcile(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("execution_source_sha256");
    }

    private static ObjectNode input(boolean observed, boolean tooEarly) {
        ObjectNode input = JsonHashes.mapper().createObjectNode()
                .put("schema", StrategyProspectiveOutcomeReconciliationV1.INPUT_SCHEMA)
                .put("version", 1)
                .put("account_currency", "USDT")
                .put("instrument_type", "SPOT")
                .put("completed_bar_id", "BTCUSDT-2024-01-01T00:00:00Z")
                .put("decision_lineage_sha256", HASH.replace('a', 'd'))
                .put("maturity_as_of", tooEarly ? "2024-01-01T00:30:00Z" : "2024-01-03T00:00:00Z");
        input.set("source_bindings", JsonHashes.mapper().createObjectNode()
                .put("outcome_resolution_source_sha256", HASH)
                .put("label_source_sha256", HASH.replace('a', 'b'))
                .put("execution_source_sha256", HASH.replace('a', 'c')));
        ObjectNode trade = JsonHashes.mapper().createObjectNode()
                .put("trade_id", "paper-1").put("direction", "long").put("quantity", 1D)
                .put("entry_price", 100D).put("exit_price", 110D)
                .put("entry_time", "2024-01-01T00:00:00Z").put("exit_time", "2024-01-02T00:00:00Z")
                .put("resolution_time", "2024-01-02T01:00:00Z")
                .put("fee_rate", .05D).put("slippage_rate", .025D).put("capacity_debit_usdt", .5D)
                .put("gross_pnl_usdt", 10D).put("fees_usdt", 10.5D).put("slippage_usdt", 5.25D)
                .put("net_pnl_usdt", -6.25D);
        input.putArray("paper_trades").add(trade);
        if (observed) input.putArray("observed_fills").add(JsonHashes.mapper().createObjectNode()
                .put("trade_id", "paper-1").put("direction", "long").put("quantity", 1D)
                .put("entry_price", 100D).put("exit_price", 110D).put("fees_usdt", 10D)
                .put("slippage_usdt", 5D).put("capacity_debit_usdt", .5D).put("net_pnl_usdt", -5.5D));
        return input;
    }
}

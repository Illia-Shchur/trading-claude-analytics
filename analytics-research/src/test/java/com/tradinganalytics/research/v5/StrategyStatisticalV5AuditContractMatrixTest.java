package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Validator-only checks for the canonical fail-closed statistical audit contract. */
final class StrategyStatisticalV5AuditContractMatrixTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    private static final List<String> REQUIRED_GATES = List.of(
            "hard_metrics", "baseline_comparison", "bootstrap_p20_positive",
            "weighted_bootstrap_p20_positive", "max_statistic", "search_adjusted_expectancy_positive",
            "dsr", "pbo", "minimum_independent_episodes", "recent_oos_positive", "earlier_blocks",
            "positive_years", "positive_outer_folds", "plateau", "neighbour_fraction", "seed_stability",
            "null_controls", "stress_ablation", "asset_decisions", "portfolio");

    @Test
    void rejectedAuditWithEveryRequiredFieldIsAcceptedAndOwnHashIsRequired() {
        ObjectNode rejected = audit(false, "REJECTED");
        assertThat(StrategyStatisticalV5.validateStatisticalAudit(rejected)).isTrue();

        ObjectNode tampered = rejected.deepCopy().put("decision", "ACTIVE");
        assertThatThrownBy(() -> StrategyStatisticalV5.validateStatisticalAudit(
                StrategyStatisticalV5.withHash(tampered)))
                .hasMessage("statistical audit semantic fields are missing or activation was attempted");

        ObjectNode badHash = rejected.deepCopy().put("content_sha256", hash("tampered"));
        assertThatThrownBy(() -> StrategyStatisticalV5.validateStatisticalAudit(badHash))
                .hasMessage("statistical audit is missing or hash-tampered");
    }

    @Test
    void passingAuditMayOnlyEmitShadowDecision() {
        ObjectNode shadow = audit(true, "SHADOW");
        assertThat(StrategyStatisticalV5.validateStatisticalAudit(shadow)).isTrue();

        ObjectNode rejected = audit(true, "REJECTED");
        assertThatThrownBy(() -> StrategyStatisticalV5.validateStatisticalAudit(rejected))
                .hasMessage("only SHADOW may be emitted by a passing statistical audit");
    }

    @Test
    void auditRequiresFailClosedSemanticFieldsAndCanonicalIndependentCounts() {
        assertReject(audit(false, "REJECTED"), value -> value.put("fail_closed_missing_inputs", false),
                "statistical audit semantic fields are missing or activation was attempted");
        assertReject(audit(false, "REJECTED"), value -> value.putNull("gates"),
                "statistical audit semantic fields are missing or activation was attempted");
        assertReject(audit(false, "REJECTED"), value -> value.put("pass", "false"),
                "statistical audit semantic fields are missing or activation was attempted");

        assertReject(audit(false, "REJECTED"), value -> value.put("independent_opportunity_count", "0"),
                "statistical audit is missing the canonical independent market-cluster inventory");
        assertReject(audit(false, "REJECTED"), value -> value.put("independent_trade_count", 0.5),
                "statistical audit is missing the canonical independent market-cluster inventory");
        assertReject(audit(false, "REJECTED"), value -> value.put("market_cluster_inventory_sha256", "short"),
                "statistical audit is missing the canonical independent market-cluster inventory");
    }

    @Test
    void auditRequiresEveryNamedGateToBeBoolean() {
        for (String gate : REQUIRED_GATES) {
            ObjectNode candidate = audit(false, "REJECTED");
            candidate.with("gates").putNull(gate);
            assertThatThrownBy(() -> StrategyStatisticalV5.validateStatisticalAudit(
                    StrategyStatisticalV5.withHash(candidate)))
                    .as("missing gate %s", gate)
                    .hasMessage("statistical audit gate " + gate + " is missing");
        }
    }

    private static void assertReject(ObjectNode original, Consumer<ObjectNode> mutation, String message) {
        ObjectNode candidate = original.deepCopy();
        mutation.accept(candidate);
        assertThatThrownBy(() -> StrategyStatisticalV5.validateStatisticalAudit(
                StrategyStatisticalV5.withHash(candidate))).hasMessage(message);
    }

    private static ObjectNode audit(boolean pass, String decision) {
        ObjectNode value = MAPPER.createObjectNode();
        value.put("schema", StrategyStatisticalV5.STAT_SCHEMA.get("audit")).put("version", 1);
        value.put("fail_closed_missing_inputs", true).put("pass", pass).put("decision", decision);
        value.put("independent_opportunity_count", 2).put("independent_trade_count", 1)
                .put("market_cluster_inventory_sha256", hash("market-clusters"));
        ObjectNode gates = value.putObject("gates");
        for (String gate : REQUIRED_GATES) gates.put(gate, pass);
        return StrategyStatisticalV5.withHash(value);
    }

    private static String hash(String value) { return JsonHashes.sha256(value); }
}

package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Covers aggregate asset gates with deterministic fold economics and procedure evidence. */
final class StrategyStatisticalV5AssetAggregateMatrixTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();

    @Test
    void emptyFoldInventoryFailsClosedWithAnExplicitSummary() {
        ObjectNode result = StrategyStatisticalV5.aggregateAssetDecision(MAPPER.createArrayNode());
        assertThat(result.path("pass").asBoolean()).isFalse();
        assertThat(result.path("reason").asText()).isEqualTo("MISSING_ASSET_FOLDS");
        assertThat(result.path("fold_summary").path("fold_count").asInt()).isZero();
        assertThat(result.path("fold_summary").path("failed_folds").asInt()).isZero();
    }

    @Test
    void tradedFoldAggregationReportsExactOpportunityAndYearAccounting() {
        ArrayNode rows = MAPPER.createArrayNode();
        rows.add(fold("fold-2023", "2023-01-01T00:00:00Z", .20, true, .20, true));
        rows.add(fold("fold-2024", "2024-01-01T00:00:00Z", .30, true, .30, true));
        rows.add(fold("fold-2025", "2025-01-01T00:00:00Z", .40, true, .40, true));
        ObjectNode required = object().put("minEpisodes", 1).put("minExpectancy", 0)
                .put("minPositiveFolds", 2).put("minPositiveYears", 2).put("minTradesPerYear", 1)
                .put("bootstrapIterations", 16).put("seed", 17).put("halfLifeMonths", 24);
        ObjectNode result = StrategyStatisticalV5.aggregateAssetDecision(rows, required);
        assertThat(result.path("pass").asBoolean()).isTrue();
        assertThat(result.path("metrics").path("opportunity_count").asInt()).isEqualTo(3);
        assertThat(result.path("metrics").path("traded_count").asInt()).isEqualTo(3);
        assertThat(result.path("metrics").path("expectancy_r").asDouble()).isEqualTo(.30);
        assertThat(result.path("fold_summary").path("fold_count").asInt()).isEqualTo(3);
        assertThat(result.path("fold_summary").path("positive_folds").asInt()).isEqualTo(3);
        assertThat(result.path("fold_summary").path("year_stats")).hasSize(3);
        assertThat(result.path("asset_gates").path("positive_years").asBoolean()).isTrue();
        assertThat(result.path("asset_gates").path("recent_oos_positive").asBoolean()).isTrue();
    }

    @Test
    void untradedOpportunityIsCountedButCannotSatisfyRecentOrHardTradeGates() {
        ArrayNode rows = MAPPER.createArrayNode();
        rows.add(fold("fold-untraded", "2025-01-01T00:00:00Z", 0, false, 0, true));
        ObjectNode required = object().put("minEpisodes", 1).put("minExpectancy", 0)
                .put("minPositiveFolds", 1).put("minPositiveYears", 1).put("minTradesPerYear", 1);
        ObjectNode result = StrategyStatisticalV5.aggregateAssetDecision(rows, required);
        assertThat(result.path("metrics").path("opportunity_count").asInt()).isEqualTo(1);
        assertThat(result.path("metrics").path("traded_count").asInt()).isZero();
        assertThat(result.path("metrics").path("expectancy_r").asDouble()).isZero();
        assertThat(result.path("asset_gates").path("recent_oos_positive").asBoolean()).isFalse();
        assertThat(result.path("asset_gates").path("hard_metrics").asBoolean()).isFalse();
        assertThat(result.path("pass").asBoolean()).isFalse();
    }

    @Test
    void failedStressAndNegativeEarlierBlockRemainVisibleInFoldAndGateSummaries() {
        ArrayNode rows = MAPPER.createArrayNode();
        rows.add(fold("fold-2023", "2023-01-01T00:00:00Z", -.20, true, -.20, false));
        rows.add(fold("fold-2024", "2024-01-01T00:00:00Z", .40, true, .40, true));
        rows.add(fold("fold-2025", "2025-01-01T00:00:00Z", .50, true, .50, true));
        ObjectNode required = object().put("minEpisodes", 1).put("minExpectancy", -1)
                .put("minPositiveFolds", 3).put("minPositiveYears", 1).put("minTradesPerYear", 1)
                .put("bootstrapIterations", 16).put("seed", 17).put("halfLifeMonths", 12);
        ObjectNode result = StrategyStatisticalV5.aggregateAssetDecision(rows, required);
        assertThat(result.path("fold_summary").path("failed_folds").asInt()).isEqualTo(1);
        assertThat(result.path("asset_gates").path("positive_outer_folds").asBoolean()).isFalse();
        assertThat(result.path("asset_gates").path("stress_survival").asBoolean()).isFalse();
        assertThat(result.path("pass").asBoolean()).isFalse();
    }

    @Test
    void procedureEvidenceRequiresBothForwardValidationAndOuterTrainOnlyPbo() {
        ArrayNode rows = MAPPER.createArrayNode();
        rows.add(fold("fold-1", "2025-01-01T00:00:00Z", .30, true, .30, true));
        ObjectNode required = object().put("minEpisodes", 1).put("minExpectancy", -1)
                .put("minPositiveFolds", 1).put("minPositiveYears", 1).put("minTradesPerYear", 1);
        ArrayNode invalid = rows.deepCopy();
        ObjectNode invalidRow = (ObjectNode) invalid.get(0);
        invalidRow.putObject("procedure_validation").put("pass", true);
        invalidRow.putObject("pbo").put("source_phase", "OUTER_OOS").put("outer_oos_bound", true)
                .put("candidate_count", 1);
        invalidRow.put("pbo_pass", true);
        ObjectNode rejected = StrategyStatisticalV5.aggregateAssetDecision(invalid, required);
        assertThat(rejected.path("asset_gates").path("procedure_validation").asBoolean()).isTrue();
        assertThat(rejected.path("asset_gates").path("pbo").asBoolean()).isFalse();
        assertThat(rejected.path("pass").asBoolean()).isFalse();

        ObjectNode validRow = (ObjectNode) rows.get(0);
        validRow.putObject("procedure_validation").put("pass", true);
        validRow.putObject("pbo").put("source_phase", "OUTER_TRAIN_ONLY").put("outer_oos_bound", false)
                .put("candidate_count", 2);
        validRow.put("pbo_pass", true);
        ObjectNode accepted = StrategyStatisticalV5.aggregateAssetDecision(rows, required);
        assertThat(accepted.path("asset_gates").path("procedure_validation").asBoolean()).isTrue();
        assertThat(accepted.path("asset_gates").path("pbo").asBoolean()).isTrue();
        assertThat(accepted.path("pass").asBoolean()).isTrue();
    }

    @Test
    void unsupportedRawRowsFailAtTheDeterministicTimestampContract() {
        ArrayNode rows = MAPPER.createArrayNode();
        ObjectNode row = fold("fold-invalid", "not-a-time", .20, true, .20, true);
        rows.add(row);
        assertThatThrownBy(() -> StrategyStatisticalV5.aggregateAssetDecision(rows))
                .hasMessageContaining("timestamp");
    }

    private static ObjectNode fold(String foldId, String decisionTime, double net, boolean traded,
            double expectancy, boolean stressPass) {
        ObjectNode row = object().put("fold_id", foldId);
        row.putObject("metrics").put("expectancy_r", expectancy).put("cost_r", .01)
                .put("coverage_fraction", 1).put("capacity_pass", true).put("traded_count", traded ? 1 : 0)
                .put("complexity", 1);
        row.putObject("stress").put("pass", stressPass);
        row.putArray("selected_return_vector").addObject().put("episode_id", foldId + "-episode")
                .put("asset", "btc").put("decision_time", decisionTime).put("resolution_time", decisionTime)
                .put("net_r", net).put("traded", traded);
        return row;
    }

    private static ObjectNode object() { return MAPPER.createObjectNode(); }
}

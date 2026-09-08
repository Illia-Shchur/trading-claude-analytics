package com.tradinganalytics.research.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Boundary and fail-closed contract coverage for SwingEngine normalization. */
class SwingEngineNormalizationBoundaryCoverageTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long T = Instant.parse("2026-01-15T00:00:00Z").toEpochMilli();

    @Test
    void normalizeCandidateCoversAliasesDefaultsAndBoundaryRejections() {
        ObjectNode input = JSON.createObjectNode().put("id", "fk-aliases").put("framework", "fallen_knives")
                .put("phase", "1B").put("trigger_freshness_bars", 1).put("max_hold_bars", 5)
                .put("stop_distance_pct", 7).put("take_profit_r", 2).put("cap_pct", 12)
                .put("aligned_rows_min", 3).put("ratchet", "entry").put("funding_debit", "bad")
                .put("fee_pct_one_way", .12).put("slippage_pct_one_way", .04)
                .put("asset", "BTC").put("timeframe", "4H").put("regimes", "RANGE");
        input.set("state_leg_minimums", JSON.createObjectNode().put("technical", 1));
        input.set("impulse_leg_minimums", JSON.createObjectNode().put("flow", 1));
        input.set("filters", JSON.createArrayNode().add(JSON.createObjectNode().put("path", "factors.macro.dxy")
                .put("op", "gt").put("value", 100)));
        ObjectNode normalized = SwingEngine.normalizeCandidate(input);

        assertThat(normalized.path("phase").asText()).isEqualTo("1B");
        assertThat(normalized.path("threshold").asInt()).isEqualTo(11);
        assertThat(normalized.path("trigger_window_bars").asInt()).isOne();
        assertThat(normalized.path("max_hold_bars").asInt()).isEqualTo(5);
        assertThat(normalized.path("stop_pct").asDouble()).isEqualTo(7);
        assertThat(normalized.path("target_r").asDouble()).isEqualTo(2);
        assertThat(normalized.path("cap_pct").asDouble()).isEqualTo(12);
        assertThat(normalized.path("min_flow_aligned").asInt()).isEqualTo(3);
        assertThat(normalized.path("ratchet_to_entry").asBoolean()).isTrue();
        assertThat(normalized.path("funding_debit").asBoolean()).isTrue();
        assertThat(normalized.path("assets").get(0).asText()).isEqualTo("btc");
        assertThat(normalized.path("timeframes").get(0).asText()).isEqualTo("4h");
        assertThat(normalized.path("regime").get(0).asText()).isEqualTo("RANGE");
        assertThat(normalized.path("factor_filters")).hasSize(1);

        assertThatThrownBy(() -> normalize(JSON.createObjectNode().put("framework", "fallen_knives").put("trigger_window_bars", 0)))
                .hasMessageContaining("trigger freshness");
        assertThatThrownBy(() -> normalize(JSON.createObjectNode().put("framework", "fallen_knives").put("max_hold_bars", 0)))
                .hasMessageContaining("time stop");
        assertThatThrownBy(() -> normalize(JSON.createObjectNode().put("framework", "fallen_knives").put("max_concurrent", 0)))
                .hasMessage("max_concurrent must be at least 1");
        assertThatThrownBy(() -> normalize(JSON.createObjectNode().put("framework", "fallen_knives").put("stop_pct", 0)))
                .hasMessageContaining("stop_pct must be >0");
        assertThatThrownBy(() -> normalize(JSON.createObjectNode().put("framework", "fallen_knives").put("stop_atr_multiple", 0)))
                .hasMessage("stop_atr_multiple must be positive");
        assertThatThrownBy(() -> normalize(JSON.createObjectNode().put("framework", "fallen_knives").put("stop_min_pct", 8).put("stop_max_pct", 4)))
                .hasMessageContaining("dynamic stop bounds");
        assertThatThrownBy(() -> normalize(JSON.createObjectNode().put("framework", "fallen_knives").put("target_r", 0)))
                .hasMessage("target_r must be positive");
        assertThatThrownBy(() -> normalize(JSON.createObjectNode().put("framework", "fallen_knives").put("cap_pct", 0)))
                .hasMessageContaining("cap_pct cannot exceed");
        assertThatThrownBy(() -> normalize(JSON.createObjectNode().put("framework", "fallen_knives").put("score_normalization", "raw")))
                .hasMessage("score_normalization is unsupported");
    }

    @Test
    void normalizeFactorFiltersRejectMalformedOperatorContracts() {
        assertThatThrownBy(() -> normalize(filterCandidate(JSON.createObjectNode().put("path", "macro.dxy")
                .put("op", "gt").put("value", 1)))).hasMessageContaining("path must start with factors.");
        assertThatThrownBy(() -> normalize(filterCandidate(JSON.createObjectNode().put("path", "factors.dxy")
                .put("op", "unknown").put("value", 1)))).hasMessageContaining("op is unsupported");
        assertThatThrownBy(() -> normalize(filterCandidate(JSON.createObjectNode().put("path", "factors.dxy")
                .put("op", "between").set("value", JSON.createArrayNode().add(1))))).hasMessageContaining("[low, high]");
        assertThatThrownBy(() -> normalize(filterCandidate(JSON.createObjectNode().put("path", "factors.dxy")
                .put("op", "between").set("value", JSON.createArrayNode().add(1).add("bad"))))).hasMessageContaining("[low, high]");
        assertThatThrownBy(() -> normalize(filterCandidate(JSON.createObjectNode().put("path", "factors.dxy")
                .put("op", "in").put("value", "not-an-array")))).hasMessageContaining("value must be an array");
        assertThatThrownBy(() -> normalize(filterCandidate(JSON.createObjectNode().put("path", "factors.dxy")
                .put("op", "gt").put("value", "bad")))).hasMessageContaining("value must be finite");
    }

    @Test
    void buildFeatureStoreDerivesAllFrameworkSetupFamiliesFromPublicFlags() {
        ArrayNode features = JSON.createArrayNode();
        features.add(flaggedRow("fallen_knives", null, new String[]{"higher_low", "deleveraging_reversal", "reclaim", "reversal"}));
        features.add(flaggedRow("flying_rocket", "A", new String[]{"distribution", "failed_breakout", "rejection"}));
        features.add(flaggedRow("flying_rocket", "B", new String[]{"lower_high", "breakdown_retest", "bear_rally_failure"}));
        ObjectNode input = JSON.createObjectNode();
        input.set("features", features);

        ArrayNode rows = SwingEngine.decodeFeatureStore(SwingEngine.buildFeatureStore(input));
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).path("setup_families")).containsExactlyInAnyOrder(
                JSON.getNodeFactory().textNode("FK_HIGHER_LOW"), JSON.getNodeFactory().textNode("FK_DELEVERAGING_REVERSAL"),
                JSON.getNodeFactory().textNode("FK_SUPPORT_RECLAIM"), JSON.getNodeFactory().textNode("FK_REVERSAL_RECLAIM"));
        assertThat(rows.get(1).path("setup_families")).containsExactlyInAnyOrder(
                JSON.getNodeFactory().textNode("FR_A_DISTRIBUTION"), JSON.getNodeFactory().textNode("FR_A_FAILED_BREAKOUT"),
                JSON.getNodeFactory().textNode("FR_A_EUPHORIA_REJECTION"));
        assertThat(rows.get(2).path("setup_families")).containsExactlyInAnyOrder(
                JSON.getNodeFactory().textNode("FR_B_LOWER_HIGH"), JSON.getNodeFactory().textNode("FR_B_BREAKDOWN_RETEST"),
                JSON.getNodeFactory().textNode("FR_B_BEAR_RALLY_FAILURE"));
    }

    @Test
    void candidateMatchesUsesNestedLegsTriggerFamiliesAndTimeCoverageGuards() {
        ObjectNode candidateInput = JSON.createObjectNode().put("framework", "fallen_knives").put("phase", "1A")
                .put("score_threshold", 8).put("trigger_window_bars", 2).put("min_flow_aligned", 2)
                .put("setup_family", "FK_SUPPORT_RECLAIM");
        candidateInput.set("min_state", JSON.createObjectNode().put("technical", 1));
        ObjectNode candidate = SwingEngine.normalizeCandidate(candidateInput);
        ObjectNode row = matchingRow();
        assertThat(SwingEngine.candidateMatches(row, candidate)).isTrue();

        ObjectNode nestedState = row.deepCopy();
        ((ObjectNode) nestedState.path("state_legs")).remove("technical");
        nestedState.set("leg_components", JSON.createObjectNode().set("technical", JSON.createObjectNode().put("state", 1)));
        assertThat(SwingEngine.candidateMatches(nestedState, candidate)).isTrue();

        ObjectNode familyTrigger = row.deepCopy();
        ((ObjectNode) familyTrigger.path("trigger")).put("valid", false);
        familyTrigger.set("setup_flags", JSON.createObjectNode().put("FK_SUPPORT_RECLAIM", true));
        assertThat(SwingEngine.candidateMatches(familyTrigger, candidate)).isTrue();

        assertThat(SwingEngine.candidateMatches(row.deepCopy().put("time", T - 1),
                SwingEngine.normalizeCandidate(candidateInput.deepCopy().put("active_from", T)))).isFalse();
        assertThat(SwingEngine.candidateMatches(row.deepCopy().put("time", T),
                SwingEngine.normalizeCandidate(candidateInput.deepCopy().put("active_to", T)))).isFalse();
        assertThat(SwingEngine.candidateMatches(row.deepCopy().put("flow_coverage", "PARTIAL"), candidate)).isFalse();
        assertThat(SwingEngine.candidateMatches(row.deepCopy().put("flow_aligned_rows", 1), candidate)).isFalse();
        assertThat(SwingEngine.candidateMatches(row.deepCopy().put("completed_bar", false), candidate)).isFalse();
        ObjectNode wrongTimeframe = row.deepCopy(); ((ObjectNode) wrongTimeframe.path("trigger")).put("timeframe", "1h");
        assertThat(SwingEngine.candidateMatches(wrongTimeframe, candidate)).isFalse();
        ObjectNode stale = row.deepCopy().put("no_lookahead", false);
        stale.set("source_coverage", JSON.createObjectNode().put("point_in_time_safe", false));
        assertThat(SwingEngine.candidateMatches(stale, candidate)).isFalse();
    }

    private static ObjectNode normalize(ObjectNode input) {
        return SwingEngine.normalizeCandidate(input);
    }

    private static ObjectNode filterCandidate(ObjectNode filter) {
        ObjectNode candidate = JSON.createObjectNode().put("framework", "fallen_knives");
        candidate.set("factor_filters", JSON.createArrayNode().add(filter));
        return candidate;
    }

    private static ObjectNode flaggedRow(String framework, String channel, String[] flags) {
        ObjectNode row = JSON.createObjectNode().put("time", T).put("open", 100).put("high", 101)
                .put("low", 99).put("close", 100).put("framework", framework);
        if (channel == null) row.putNull("channel"); else row.put("channel", channel);
        for (String flag : flags) row.put(flag, true);
        return row;
    }

    private static ObjectNode matchingRow() {
        ObjectNode row = JSON.createObjectNode().put("asset", "btc").put("timeframe", "4h")
                .put("framework", "fallen_knives").putNull("channel").put("time", T).put("open", 100)
                .put("high", 101).put("low", 99).put("close", 100).put("mechanical_score", 10)
                .put("flow_aligned_rows", 2).put("flow_coverage", "COMPLETE").put("setup_family", "FK_SUPPORT_RECLAIM")
                .put("regime", "RANGE").put("completed_bar", true).put("timestamp_safe", true).put("no_lookahead", true);
        row.set("setup_families", JSON.createArrayNode().add("FK_SUPPORT_RECLAIM"));
        row.set("trigger", JSON.createObjectNode().put("valid", true).put("completed_bar", true)
                .put("timeframe", "4h").put("age_bars", 0));
        row.set("state_legs", JSON.createObjectNode().put("technical", 1));
        row.set("legs", JSON.createObjectNode().put("flow", 4).put("technical", 4).put("macro", 3)
                .put("sentiment", 3).put("valuation", 3).put("structure", 2));
        return row;
    }
}

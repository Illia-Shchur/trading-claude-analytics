package com.tradinganalytics.research.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Deterministic calendar, phase-cap, and portfolio-context coverage for SwingEngine. */
class SwingEngineWalkForwardCoverageTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long BAR = SwingEngine.BAR_MS;

    @Test
    void phaseCapsAndSignalBudgetConstrainLongAndShortNotional() {
        ObjectNode fk = SwingEngine.normalizeCandidate(JSON.createObjectNode()
                .put("framework", "fallen_knives").put("phase", "1A").put("stop_pct", 5));
        ObjectNode frA = SwingEngine.normalizeCandidate(JSON.createObjectNode()
                .put("framework", "flying_rocket").put("channel", "A").put("phase", "3").put("direction", "short")
                .put("stop_pct", 8));
        ObjectNode frB = SwingEngine.normalizeCandidate(JSON.createObjectNode()
                .put("framework", "flying_rocket").put("channel", "B").put("phase", "2").put("direction", "short")
                .put("stop_pct", 8));

        assertThat(fk.path("threshold").asInt()).isEqualTo(8);
        assertThat(fk.path("cap_pct").asInt()).isEqualTo(10);
        assertThat(frA.path("threshold").asInt()).isEqualTo(19);
        assertThat(frA.path("cap_pct").asInt()).isEqualTo(20);
        assertThat(frB.path("threshold").asInt()).isEqualTo(17);
        assertThat(frB.path("cap_pct").asInt()).isEqualTo(15);
        assertThatThrownBy(() -> SwingEngine.normalizeCandidate(JSON.createObjectNode()
                .put("framework", "flying_rocket").put("channel", "B").put("phase", "3").put("direction", "short")))
                .hasMessageContaining("unsupported phase 3");

        ArrayNode rows = JSON.createArrayNode().add(signalRow("btc", "fallen_knives", null, 100, 2).put("cap_pct", 4))
                .add(bar(14_400_000L, 100, 106, 99, 105));
        ObjectNode boundedLong = SwingEngine.simulateTrade(rows, 0, fk);
        assertThat(boundedLong.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(boundedLong.path("risk_budget").path("phase_cap_pct").asDouble()).isEqualTo(4);
        assertThat(boundedLong.path("risk_budget").path("phase_notional").asDouble()).isEqualTo(4_000);
        assertThat(boundedLong.path("notional").asDouble()).isEqualTo(4_000);

        ObjectNode shortCandidate = frB.deepCopy().put("target_r", 1).put("fee_pct", 0).put("slippage_pct", 0);
        ArrayNode shortRows = JSON.createArrayNode().add(signalRow("btc", "flying_rocket", "B", 100, 2).put("cap_pct", 7))
                .add(bar(BAR, 100, 101, 94, 96));
        ObjectNode boundedShort = SwingEngine.simulateTrade(shortRows, 0, shortCandidate);
        assertThat(boundedShort.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(boundedShort.path("risk_budget").path("phase_cap_pct").asDouble()).isEqualTo(7);
        assertThat(boundedShort.path("risk_budget").path("phase_notional").asDouble()).isEqualTo(7_000);
        assertThat(boundedShort.path("notional").asDouble()).isEqualTo(7_000);
    }

    @Test
    void walkForwardReportsPurgedFoldsBlockedCandidatesAndSealedHoldout() {
        ArrayNode rows = monthlyRows(36, "btc");
        ObjectNode good = longCandidate("fk-good");
        ObjectNode unmatched = JSON.createObjectNode().put("id", "fr-unmatched").put("framework", "flying_rocket")
                .put("channel", "B").put("phase", "1A").put("direction", "short").put("stop_pct", 5)
                .put("target_r", 1).put("max_hold_bars", 2).put("fee_pct", 0).put("slippage_pct", 0);
        ArrayNode candidates = JSON.createArrayNode().add(good).add(unmatched);
        ObjectNode options = walkOptions();

        ObjectNode exposed = SwingEngine.walkForward(rows, candidates, options);

        assertThat(exposed.path("status").asText()).isEqualTo("OK");
        assertThat(exposed.path("months")).hasSize(36);
        assertThat(exposed.path("purge_bars").asInt()).isEqualTo(SwingEngine.MAX_HOLD_BARS);
        assertThat(exposed.path("folds")).hasSize(8);
        assertThat(exposed.path("selected").path("id").asText()).isEqualTo("fk-good");
        assertThat(exposed.path("training_leaderboard")).hasSize(2);
        JsonNode unmatchedReport = null;
        for (JsonNode report : exposed.path("training_leaderboard")) {
            if ("fr-unmatched".equals(report.path("candidate").path("id").asText())) unmatchedReport = report;
        }
        assertThat(unmatchedReport).isNotNull();
        assertThat(unmatchedReport.path("selection").path("admissible").asBoolean()).isFalse();
        for (JsonNode fold : exposed.path("folds")) {
            assertThat(fold.path("purge_bars").asInt()).isEqualTo(SwingEngine.MAX_HOLD_BARS);
            Set<Integer> trainMonths = new HashSet<>();
            for (JsonNode month : fold.path("train_months")) trainMonths.add(month.asInt());
            for (JsonNode month : fold.path("test_months")) assertThat(trainMonths).doesNotContain(month.asInt());
        }
        assertThat(exposed.path("holdout").path("label").asText()).isEqualTo("EXPOSED_CONFIRMATION");
        assertThat(exposed.path("holdout").path("untouched").asBoolean()).isFalse();
        assertThat(exposed.path("holdout").path("selection_blocked").asBoolean()).isFalse();
        assertThat(exposed.path("holdout").path("gate").path("eligible").asBoolean()).isTrue();
        assertThat(exposed.path("walk_forward_oos").path("deoverlap").path("dropped_completed").asInt()).isZero();
        assertThat(exposed.path("holdout").path("seal").path("verified").asBoolean()).isFalse();

        ObjectNode sealedOptions = options.deepCopy().put("sealed_holdout_token", "fixture-token")
                .put("sealed_holdout_hash", exposed.path("holdout").path("seal").path("data_sha256").asText());
        ObjectNode sealed = SwingEngine.walkForward(rows, candidates, sealedOptions);
        assertThat(sealed.path("holdout").path("label").asText()).isEqualTo("SEALED_CONFIRMATION");
        assertThat(sealed.path("holdout").path("untouched").asBoolean()).isTrue();
        assertThat(sealed.path("holdout").path("seal").path("verified").asBoolean()).isTrue();
        assertThat(sealed.path("holdout").path("seal").path("token_supplied").asBoolean()).isTrue();
        assertThat(sealed.path("holdout").path("seal").path("hash_supplied").asBoolean()).isTrue();
    }

    @Test
    void runResearchAggregatesEligibleSeriesAndRetainsExplicitCandidateTrades() {
        ArrayNode rows = monthlyRows(36, "btc");
        rows.addAll(monthlyRows(36, "eth"));
        ArrayNode candidates = JSON.createArrayNode().add(longCandidate("fk-retained"));
        ObjectNode options = JSON.createObjectNode().put("skip_validation", true).put("holdout_months", 6)
                .put("min_trades", 1).put("min_regimes", 1).put("bootstrap_rounds", 10)
                .put("candidate_count", 1);
        options.set("retain_candidate_ids", JSON.createArrayNode().add("fk-retained"));

        ObjectNode run = SwingEngine.runResearch(rows, candidates, options,
                java.time.Clock.fixed(java.time.Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC));

        assertThat(run.path("schema").asText()).isEqualTo(SwingEngine.RUN_SCHEMA);
        assertThat(run.path("activation").asText()).isEqualTo("SHADOW");
        assertThat(run.path("series")).hasSize(2);
        assertThat(run.path("aggregate")).hasSize(1);
        assertThat(run.path("aggregate").get(0).path("candidate").path("id").asText()).isEqualTo("fk-retained");
        assertThat(run.path("aggregate").get(0).path("eligible_series_count").asInt()).isEqualTo(2);
        assertThat(run.path("retained_candidate_ids")).containsExactly(JSON.getNodeFactory().textNode("fk-retained"));
        assertThat(run.path("retained_trades")).isNotEmpty();
        assertThat(SwingEngine.verifyRunHash(run)).isTrue();
    }

    private static ObjectNode longCandidate(String id) {
        return JSON.createObjectNode().put("id", id).put("framework", "fallen_knives").put("phase", "1A")
                .put("direction", "long").put("stop_pct", 5).put("target_r", 1).put("max_hold_bars", 2)
                .put("fee_pct", 0).put("slippage_pct", 0).put("initial_equity", 100_000);
    }

    private static ObjectNode signalRow(String asset, String framework, String channel, double close, double atr) {
        ObjectNode row = JSON.createObjectNode().put("asset", asset).put("timeframe", "4h").put("framework", framework)
                .put("time", 0).put("open", close).put("high", close + 1).put("low", close - 1).put("close", close)
                .put("atr_20d", atr).put("mechanical_score", "fallen_knives".equals(framework) ? 10 : 14)
                .put("flow_aligned_rows", 2).put("flow_coverage", "COMPLETE").put("setup_family", "FK_HIGHER_LOW")
                .put("regime", "RANGE").put("timestamp_safe", true).put("no_lookahead", true).put("completed_bar", true)
                .put("stop_distance_pct", 5);
        if (channel == null) row.putNull("channel"); else row.put("channel", channel);
        row.set("setup_families", JSON.createArrayNode().add("FK_HIGHER_LOW"));
        row.set("trigger", JSON.createObjectNode().put("valid", true).put("completed_bar", true)
                .put("timeframe", "4h").put("age_bars", 0));
        row.set("protective_controls", JSON.createObjectNode().put("stop_valid", true).put("time_stop_valid", true)
                .put("ratchet_valid", true).put("carry_veto", false));
        return row;
    }

    private static ObjectNode bar(long time, double open, double high, double low, double close) {
        assertThat(low).isLessThanOrEqualTo(Math.min(open, close));
        assertThat(high).isGreaterThanOrEqualTo(Math.max(open, close));
        return JSON.createObjectNode().put("time", time).put("open", open).put("high", high).put("low", low).put("close", close)
                .put("volume", 100);
    }

    private static ArrayNode monthlyRows(int count, String asset) {
        ArrayNode rows = JSON.createArrayNode();
        LocalDate first = LocalDate.of(2022, 1, 1);
        for (int index = 0; index < count; index++) {
            long time = first.plusMonths(index).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            ObjectNode signal = signalRow(asset, "fallen_knives", null, 100, 2).put("time", time)
                    .put("available_at", time + BAR).put("signal_id", asset + "-signal-" + index);
            ObjectNode entry = bar(time + BAR, 100, 106, 99, 105).put("asset", asset).put("timeframe", "4h")
                    .put("framework", "fallen_knives").putNull("channel").put("available_at", time + 2 * BAR)
                    .put("mechanical_score", 0).put("flow_aligned_rows", 0).put("setup_family", "NONE");
            entry.set("setup_families", JSON.createArrayNode().add("NONE"));
            entry.set("trigger", JSON.createObjectNode().put("valid", false).put("completed_bar", true)
                    .put("timeframe", "4h").put("age_bars", 0));
            rows.add(signal).add(entry);
        }
        return rows;
    }

    private static ObjectNode walkOptions() {
        return JSON.createObjectNode().put("min_months", 12).put("holdout_months", 6).put("fold_months", 3)
                .put("development_months", 6).put("min_trades", 1).put("min_regimes", 1)
                .put("holdout_min_oos_trades", 1).put("holdout_min_positive_folds", 1)
                .put("bootstrap_rounds", 10).put("max_leaderboard", 5);
    }
}

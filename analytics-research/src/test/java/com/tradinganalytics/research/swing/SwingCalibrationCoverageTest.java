package com.tradinganalytics.research.swing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.core.swing.SwingScore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Deterministic contract coverage for the swing calibration calculation layer. */
class SwingCalibrationCoverageTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC);
    private static final long BAR = SwingCalibration.BAR_MS;

    @Test
    void defaultCandidatesEnumerateDeclaredLongAndShortSearchSpace() {
        ArrayNode candidates = SwingCalibration.defaultCandidates();

        assertThat(candidates).hasSize(22);
        assertThat(candidates.findValuesAsText("framework")).contains("fallen_knives", "flying_rocket");
        assertThat(candidates.findValuesAsText("channel")).contains("A", "B");
        assertThat(candidates.findValuesAsText("phase")).contains("1A", "1B", "2", "3");
        for (JsonNode candidate : candidates) {
            assertThat(candidate.path("trigger_window_bars").asInt()).isBetween(1, 2);
            if ("flying_rocket".equals(candidate.path("framework").asText())
                    && "B".equals(candidate.path("channel").asText())) {
                assertThat(candidate.path("phase").asText()).isNotEqualTo("3");
            }
        }
    }

    @Test
    void labelRowsCoversQuietDirectionalAndUnresolvedOutcomes() {
        ArrayNode quiet = bars(400, 100);
        ArrayNode quietLabels = SwingCalibration.labelRows(quiet);
        assertThat(quietLabels).hasSize(100);
        JsonNode quietFirst = quietLabels.get(0);
        assertThat(quietFirst.path("long").asBoolean()).isFalse();
        assertThat(quietFirst.path("short").asBoolean()).isFalse();
        assertThat(quietFirst.path("long_favorable_bars").isNull()).isTrue();
        assertThat(quietFirst.path("short_favorable_bars").isNull()).isTrue();
        assertThat(quietFirst.path("long_resolution_bars").asInt()).isEqualTo(180);
        assertThat(quietFirst.path("short_resolution_bars").asInt()).isEqualTo(180);

        ArrayNode directional = bars(400, 100);
        ((ObjectNode) directional.get(121)).put("high", 104);
        // The second base bar has a widened ATR after the first spike; 90 is
        // deliberately beyond its 1.5 ATR favorable threshold.
        ((ObjectNode) directional.get(122)).put("low", 90);
        ((ObjectNode) directional.get(140)).put("high", 104).put("low", 96);
        ArrayNode labels = SwingCalibration.labelRows(directional);
        JsonNode firstDirectional = labels.get(0);
        JsonNode secondDirectional = labels.get(1);
        assertThat(firstDirectional.path("long").asBoolean()).isTrue();
        assertThat(firstDirectional.path("long_favorable_bars").asInt()).isEqualTo(1);
        assertThat(firstDirectional.path("short").asBoolean()).isFalse();
        assertThat(secondDirectional.path("short").asBoolean()).isTrue();
        assertThat(secondDirectional.path("short_favorable_bars").asInt()).isEqualTo(1);
        JsonNode unresolved = labels.get(25);
        assertThat(unresolved.path("long_favorable_bars").isNull()).isTrue();
        assertThat(unresolved.path("short_favorable_bars").isNull()).isTrue();

        ArrayNode zeroRange = bars(400, 100);
        for (JsonNode row : zeroRange) ((ObjectNode) row).put("high", 100).put("low", 100);
        assertThat(SwingCalibration.labelRows(zeroRange)).isEmpty();
    }

    @Test
    void validCandidatesRejectMalformedRowsAndNormalizeOptionalBounds() {
        ArrayNode input = JSON.createArrayNode();
        input.add(candidate("fallen_knives", null, "1A", "long", 1).put("min_flow_aligned", 2).put("min_technical", 3));
        input.add(candidate("fallen_knives", null, "1A", "long", 0));
        input.add(candidate("fallen_knives", null, "1A", "long", 3));
        input.add(candidate("fallen_knives", null, "1A", "long", 1).put("threshold_offset", 1));
        input.add(candidate("fallen_knives", null, "1A", "long", 1).put("min_flow_aligned", 6));
        input.add(candidate("fallen_knives", null, "1A", "long", 1).put("min_technical", 5));
        input.add(candidate("fallen_knives", null, "1A", "short", 1));
        input.add(candidate("flying_rocket", "B", "3", "short", 1));
        input.add(candidate("flying_rocket", "A", "1A", "short", 1));
        input.add(candidate("flying_rocket", "B", "1A", "short", 1).put("threshold_offset", 0.5));
        input.addNull();

        ArrayNode normalized = SwingCalibration.validCandidates(input, "fallen_knives", null);
        assertThat(normalized).hasSize(1);
        assertThat(normalized.get(0).path("min_flow_aligned").asInt()).isEqualTo(2);
        assertThat(normalized.get(0).path("min_technical").asInt()).isEqualTo(3);
        assertThat(normalized.get(0).path("threshold_offset").asInt()).isZero();

        ArrayNode shortA = SwingCalibration.validCandidates(input, "flying_rocket", "A");
        assertThat(shortA).hasSize(1);
        assertThat(shortA.get(0).path("channel").asText()).isEqualTo("A");
        assertThat(SwingCalibration.validCandidates(input, "flying_rocket", "B")).isEmpty();
        assertThat(SwingCalibration.validCandidates(null, "fallen_knives", null)).isEmpty();
    }

    @Test
    void featureRowsRejectMissingComponentsAndIncompleteFlowBeforeAcceptingRows() {
        ArrayNode labels = JSON.createArrayNode().add(label(10_000));

        SwingCalibration.FeatureRows missingFeatures = SwingCalibration.featureRows(JSON.createObjectNode(), labels, 1);
        assertThat(missingFeatures.complete()).isFalse();
        assertThat(missingFeatures.excluded()).isEmpty();
        assertThat(missingFeatures.reason()).contains("OHLC labels alone");

        ObjectNode missingLegs = JSON.createObjectNode();
        ObjectNode missingLegRow = featureRow(10_000, true, true);
        missingLegRow.remove("legs");
        missingLegs.set("features", JSON.createArrayNode().add(missingLegRow));
        SwingCalibration.FeatureRows missingLegResult = SwingCalibration.featureRows(missingLegs, labels, 1);
        assertThat(missingLegResult.complete()).isFalse();
        assertThat(missingLegResult.excluded().get(0).path("reason").asText()).isEqualTo("missing_leg");

        ObjectNode invalidComponents = JSON.createObjectNode();
        ObjectNode invalidRow = featureRow(10_000, true, true);
        ((ObjectNode) invalidRow.path("leg_components").path("technical")).put("state", 3);
        invalidComponents.set("features", JSON.createArrayNode().add(invalidRow));
        SwingCalibration.FeatureRows invalidResult = SwingCalibration.featureRows(invalidComponents, labels, 1);
        assertThat(invalidResult.excluded().get(0).path("reason").asText()).isEqualTo("invalid_leg_components");

        ObjectNode incompleteFlow = JSON.createObjectNode();
        incompleteFlow.set("features", JSON.createArrayNode().add(featureRow(10_000, true, false)));
        SwingCalibration.FeatureRows incompleteResult = SwingCalibration.featureRows(incompleteFlow, labels, 1);
        assertThat(incompleteResult.excluded().get(0).path("reason").asText()).contains("requires error-free");

        ObjectNode shortRows = JSON.createObjectNode();
        ObjectNode shortRow = featureRow(10_000, true, true);
        JsonNode panel = shortRow.remove("flow_panel");
        shortRow.set("flow_panels", JSON.createObjectNode().set("short", panel));
        shortRows.set("rows", JSON.createArrayNode().add(shortRow));
        SwingCalibration.FeatureRows accepted = SwingCalibration.featureRows(shortRows, labels, -1);
        assertThat(accepted.complete()).isTrue();
        assertThat(accepted.rows()).hasSize(1);
        assertThat(accepted.rows().get(0).path("flow_assessment").path("eligible_for_entry").asBoolean()).isTrue();
    }

    @Test
    void candidateMetricsAndWalkForwardCoverSignalAccountingAndCalendarBoundaries() {
        SwingCalibration.Options permissive = new SwingCalibration.Options(3, .20, .10, 1, .80, 1, 1, .40);
        assertThat(SwingCalibration.metrics(JSON.createArrayNode(), "long", permissive).path("signals").asInt()).isZero();

        ArrayNode signals = JSON.createArrayNode();
        signals.add(signal(0, true, true, 2));
        signals.add(signal(BAR, true, true, 1));
        signals.add(signal(3 * BAR, false, false, null));
        ObjectNode metrics = SwingCalibration.metrics(signals, "long", permissive);
        assertThat(metrics.path("signals").asInt()).isEqualTo(2);
        assertThat(metrics.path("raw_signals").asInt()).isEqualTo(3);
        assertThat(metrics.path("wins").asInt()).isEqualTo(1);
        assertThat(metrics.path("losses").asInt()).isEqualTo(1);
        assertThat(metrics.path("precision").asDouble()).isEqualTo(.5);
        assertThat(metrics.path("expectancy_r").asDouble()).isGreaterThan(0);

        ObjectNode emptyWalk = SwingCalibration.walkForward(JSON.createArrayNode(), JSON.createArrayNode(),
                "fallen_knives", null, permissive);
        assertThat(emptyWalk.path("development").isNull()).isTrue();
        ArrayNode shortRows = calibrationRows(2);
        ObjectNode insufficient = SwingCalibration.walkForward(shortRows, JSON.createArrayNode().add(candidate("fallen_knives", null, "1A", "long", 1)),
                "fallen_knives", null, permissive);
        assertThat(insufficient.path("split_status").asText()).isEqualTo("INSUFFICIENT_36_CALENDAR_MONTHS");
        assertThat(insufficient.path("observed_months").asInt()).isEqualTo(2);

        ArrayNode rows = calibrationRows(36);
        ObjectNode candidate = candidate("fallen_knives", null, "1A", "long", 1);
        ArrayNode candidates = JSON.createArrayNode().add(candidate);
        ObjectNode direct = SwingCalibration.evaluateCandidate(rows, candidate, "fallen_knives", null, permissive);
        assertThat(direct.path("signals").asInt()).isEqualTo(36);
        ObjectNode walk = SwingCalibration.walkForward(rows, candidates, "fallen_knives", null, permissive);
        assertThat(walk.path("folds")).hasSize(4);
        assertThat(walk.path("holdout").path("selected")).isNotNull();
        assertThat(walk.path("holdout").path("selected").path("phase").asText()).isEqualTo("1A");
    }

    @Test
    void calibrateBuildsCompleteShadowReportForAcceptedContractAndDataset() {
        ArrayNode rows = calibrationRows(36);
        ObjectNode dataset = JSON.createObjectNode().put("asset", "btc").put("framework", "fallen_knives")
                .putNull("channel").put("coverage", "COMPLETE").put("bars", rows.size());
        dataset.set("labels", calibrationLabels(36));
        dataset.set("features", rows);
        dataset.set("coverage_meta", JSON.createObjectNode().put("bars", rows.size()));
        ArrayNode datasets = JSON.createArrayNode().add(dataset);
        ArrayNode candidates = JSON.createArrayNode().add(candidate("fallen_knives", null, "1A", "long", 1));
        ObjectNode contract = acceptedContract();
        SwingCalibration.Options options = new SwingCalibration.Options(3, .20, .10, 1, .80, 1, 1, .40);

        ObjectNode report = SwingCalibration.calibrate(datasets, contract, candidates, options, FIXED);

        assertThat(report.path("schema").asText()).isEqualTo("swing-calibration/1");
        assertThat(report.path("model").asText()).isEqualTo("swing-score/1");
        assertThat(report.path("generated_at").asText()).isEqualTo("2026-08-22T00:00:00.000Z");
        assertThat(report.path("point_in_time_safe").asBoolean()).isTrue();
        assertThat(report.path("proxy_contract").path("accepted").asBoolean()).isTrue();
        assertThat(report.path("activation").asText()).isEqualTo("SHADOW");
        assertThat(report.path("datasets")).hasSize(1);
        JsonNode result = report.path("datasets").get(0);
        assertThat(result.path("feature_coverage").asText()).isEqualTo("COMPLETE");
        assertThat(result.path("coverage_ratio").asDouble()).isEqualTo(1);
        assertThat(result.path("candidates_declared").asInt()).isEqualTo(1);
        assertThat(result.path("holdout_pass").asBoolean()).isTrue();
        assertThat(result.path("holdout_criteria").path("actual_signals").asInt()).isGreaterThan(0);
        assertThat(result.path("walk_forward").path("folds")).hasSize(4);
        assertThat(report.path("model_activation").path("status").asText()).isEqualTo("SHADOW");
    }

    private static ArrayNode bars(int count, double close) {
        ArrayNode rows = JSON.createArrayNode();
        long start = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
        for (int index = 0; index < count; index++) {
            rows.add(JSON.createObjectNode().put("time", start + index * BAR).put("open", close)
                    .put("high", close + 1).put("low", close - 1).put("close", close).put("volume", 1000 + index));
        }
        return rows;
    }

    private static JsonNode label(long time) {
        return JSON.createObjectNode().put("time", time).put("month", 24200).put("close", 100)
                .put("atr_20d", 2).put("long", true).put("short", false)
                .put("long_early_capture", true).put("short_early_capture", false);
    }

    private static ObjectNode candidate(String framework, String channel, String phase, String direction, int bars) {
        ObjectNode candidate = JSON.createObjectNode().put("framework", framework).put("direction", direction)
                .put("phase", phase).put("trigger_window_bars", bars).put("threshold_offset", 0)
                .put("min_flow_aligned", 0).put("min_technical", 0);
        if (channel != null) candidate.put("channel", channel);
        return candidate;
    }

    private static ObjectNode featureRow(long time, boolean validComponents, boolean completeFlow) {
        ObjectNode row = JSON.createObjectNode().put("time", time).put("open", 100).put("high", 101)
                .put("low", 99).put("close", 100).put("volume", 1000).put("equity_usd", 1_000_000)
                .put("stop_distance_pct", 5).put("flow_coverage", completeFlow ? "COMPLETE" : "PARTIAL")
                .put("regime", "RANGE").put("long", true).put("short", false).put("long_early_capture", true)
                .put("short_early_capture", false).put("resolution_bars", 1).put("impulse", 0);
        ObjectNode legs = row.putObject("legs").put("flow", 5).put("technical", 4).put("macro", 3)
                .put("sentiment", 3).put("valuation", 3).put("structure", 2);
        ObjectNode components = row.putObject("leg_components");
        components.putObject("technical").put("state", validComponents ? 2 : 3).put("impulse", 2);
        components.putObject("macro").put("state", 1.5).put("impulse", 1.5);
        components.putObject("sentiment").put("state", 1.5).put("impulse", 1.5);
        components.putObject("valuation").put("state", 2).put("impulse", 1);
        components.putObject("structure").put("state", 1).put("impulse", 1);
        row.set("flow_panel", flowPanel(completeFlow));
        row.set("trigger", JSON.createObjectNode().put("valid", true).put("completed_bar", true)
                .put("timeframe", "4h").put("created_at", "2026-08-22T00:00:00Z").put("level", 100).put("age_bars", 0));
        row.set("protective_controls", JSON.createObjectNode().put("stop_valid", true).put("time_stop_valid", true)
                .put("ratchet_valid", true).put("carry_veto", false));
        row.set("veto_flags", JSON.createObjectNode());
        return row;
    }

    private static ObjectNode flowPanel(boolean complete) {
        ObjectNode panel = JSON.createObjectNode().put("coverage", complete ? "COMPLETE" : "PARTIAL")
                .put("interval_hours", 4).put("completed_through", "2026-08-22T00:00:00Z");
        for (String name : SwingScore.FLOW_PANEL_ROWS) {
            panel.set(name, JSON.createObjectNode().put("available", complete)
                    .put("setup_signal_24h", "aligned").put("setup_signal_3d", "aligned"));
        }
        return panel;
    }

    private static JsonNode signal(double time, boolean win, boolean early, Integer resolution) {
        ObjectNode row = JSON.createObjectNode().put("time", time).put("stop_distance_pct", 5);
        row.set("outcome", JSON.createObjectNode().put("long", win).put("long_early", early));
        if (resolution != null) row.put("resolution_bars", resolution);
        return row;
    }

    private static ArrayNode calibrationRows(int count) {
        ArrayNode rows = JSON.createArrayNode();
        LocalDate first = LocalDate.of(2022, 1, 1);
        for (int index = 0; index < count; index++) {
            LocalDate date = first.plusMonths(index);
            long time = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            ObjectNode row = featureRow(time, true, true);
            row.put("month", date.getYear() * 12 + date.getMonthValue() - 1);
            rows.add(row);
        }
        return rows;
    }

    private static ArrayNode calibrationLabels(int count) {
        ArrayNode labels = JSON.createArrayNode();
        LocalDate first = LocalDate.of(2022, 1, 1);
        for (int index = 0; index < count; index++) {
            LocalDate date = first.plusMonths(index);
            long time = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            labels.add(JSON.createObjectNode().put("time", time).put("month", date.getYear() * 12 + date.getMonthValue() - 1)
                    .put("close", 100).put("atr_20d", 2).put("long", true).put("short", false)
                    .put("long_early_capture", true).put("short_early_capture", false)
                    .put("long_resolution_bars", 1).put("short_resolution_bars", 1));
        }
        return labels;
    }

    private static ObjectNode acceptedContract() {
        ObjectNode contract = JSON.createObjectNode().put("point_in_time_safe", true);
        contract.set("proxy_contract", JSON.createObjectNode().put("accepted", true).put("status", "ACCEPTED"));
        ObjectNode policy = contract.putObject("activation_policy").put("point_in_time_safe_required", true)
                .put("proxy_inputs_accepted", true);
        ArrayNode required = policy.putArray("required_series");
        for (String series : List.of("btc:fallen_knives", "btc:flying_rocket:A", "btc:flying_rocket:B",
                "eth:fallen_knives", "eth:flying_rocket:A", "eth:flying_rocket:B")) required.add(series);
        return contract;
    }
}

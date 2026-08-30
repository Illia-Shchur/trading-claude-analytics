package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.tradinganalytics.research.legacy.LegacyNodeOracle.array;
import static com.tradinganalytics.research.legacy.LegacyNodeOracle.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyStrategyResearchCorrectionTest {
    private static final long BAR_MILLIS = 14_400_000L;

    @Test
    void wfoAcceptanceAndExposedSelectionUseOnlyFrozenParentEvidence() {
        ObjectNode contract = LegacyResearchV3.makeAcceptanceContract();
        ArrayNode rows = featureRows();
        ObjectNode candidates = object().put("declared_k", 2).put("effective_k", 2);
        candidates.set("candidates", array()
                .add(object().put("candidate_id", "a"))
                .add(object().put("candidate_id", "b")));
        ObjectNode manifest = object().put("authoritative", true);
        manifest.set("coverage_summary", object()
                .put("price_fraction", 1).put("derivatives_fraction", 1));

        ObjectNode wfoOptions = lineage(contract).put("experimentId", "wfo-oos-basis")
                .put("stage", "ENTRY_TIMING").put("predecessorStage", "CORE_PREMISE")
                .put("predecessorSha256", "1".repeat(64))
                .put("evidencePhase", "WALK_FORWARD_OOS");
        ObjectNode chronology = object().put("timezone", "UTC")
                .put("bar_convention", "completed-bar-next-open")
                .put("bar_duration_ms", BAR_MILLIS).put("purge_bars", 0)
                .put("embargo_bars", 0);
        chronology.set("seeds", array().add(1));
        chronology.set("folds", array()
                .add(fold("f1", 0, 5 * BAR_MILLIS, 6 * BAR_MILLIS, 10 * BAR_MILLIS))
                .add(fold("f2", 0, 10 * BAR_MILLIS, 11 * BAR_MILLIS,
                        15 * BAR_MILLIS)));
        wfoOptions.set("chronology", chronology);
        ObjectNode wfoExperiment = LegacyResearchV3.makeExperimentV3(wfoOptions);
        ObjectNode input = evaluationInput(
                wfoExperiment, manifest, candidates, rows, null);
        ObjectNode wfoResult = LegacyStrategyResearch.evaluateLocalV3(input,
                LegacyStrategyResearchCorrectionTest::evaluateCandidate);

        assertThat(wfoResult.path("bundle").path("acceptance_basis").asText())
                .isEqualTo("WALK_FORWARD_OOS_AGGREGATE");
        assertThat(wfoResult.path("acceptance").path("decision").asText())
                .isEqualTo("REJECTED");
        assertThat(wfoResult.path("metrics")).anySatisfy(metric -> {
            assertThat(metric.path("phase").asText()).isEqualTo("TRAIN");
            assertThat(metric.path("execution").path("status").asText())
                    .isEqualTo("TRAIN_EVALUATED");
        });
        assertThat(wfoResult.path("metrics")).anySatisfy(metric -> {
            assertThat(metric.path("phase").asText()).isEqualTo("OOS");
            assertThat(metric.path("execution").path("status").asText())
                    .isEqualTo("OOS_WINNER_ONLY");
        });
        JsonNode wfo = wfoResult.path("wfo");
        assertThat(wfo.path("aggregate_oos_metrics").path("expectancy_r").asDouble())
                .isNegative();
        assertThat(wfo.path("final_selection_by_asset").path("btc").asText())
                .isEqualTo("a");
        ObjectNode selectionPayload = object();
        selectionPayload.set("policy", wfo.path("final_selection_policy"));
        selectionPayload.set("selection_by_asset", wfo.path("final_selection_by_asset"));
        selectionPayload.set("selection_metrics_by_asset",
                wfo.path("final_selection_metrics_by_asset"));
        assertThat(wfo.path("final_selection_sha256").asText())
                .isEqualTo(LegacyResearchV3.hash(selectionPayload));
        assertThat(wfo.path("candidate_accounting")).anySatisfy(row -> {
            assertThat(row.path("phase").asText()).isEqualTo("TRAIN");
            assertThat(row.path("actual_trade_count").asInt()).isPositive();
        });

        ObjectNode exposedOptions = lineage(contract)
                .put("experimentId", "exposed-frozen-selection")
                .put("stage", "ENTRY_TIMING").put("predecessorStage", "CORE_PREMISE")
                .put("predecessorSha256", "2".repeat(64))
                .put("evidencePhase", "EXPOSED_CONFIRMATION")
                .put("parentEvidenceSha256",
                        wfoResult.path("bundle").path("content_sha256").asText());
        ObjectNode exposedChronology = object().put("timezone", "UTC")
                .put("bar_convention", "completed-bar-next-open")
                .put("frozen_selection", true);
        exposedChronology.set("seeds", array().add(1));
        exposedChronology.set("frozen_candidate_ids", array().add("a"));
        exposedOptions.set("chronology", exposedChronology);
        ObjectNode exposedExperiment = LegacyResearchV3.makeExperimentV3(exposedOptions);
        ArrayNode shortRows = array();
        for (int index = 0; index < 4; index++) shortRows.add(rows.get(index).deepCopy());
        List<String> used = new ArrayList<>();
        ObjectNode exposedInput = evaluationInput(exposedExperiment, manifest, candidates,
                shortRows, wfoResult.path("bundle"));
        ObjectNode exposed = LegacyStrategyResearch.evaluateLocalV3(exposedInput,
                (series, candidate, options) -> {
                    used.add(candidate.path("id").asText());
                    return evaluateCandidate(series, candidate, options);
                });
        assertThat(used).containsExactly("a");
        assertThat(exposed.path("selected_by_asset").path("btc").asText()).isEqualTo("a");
        assertThat(exposed.path("bundle").path("acceptance_basis").asText())
                .isEqualTo("FROZEN_PARENT_WFO_SELECTION");
        assertThat(exposed.path("acceptance").path("failures"))
                .noneSatisfy(failure -> assertThat(failure.asText())
                        .isEqualTo("MISSING_WFO_EVIDENCE"));

        ObjectNode missingParent = evaluationInput(
                exposedExperiment, manifest, candidates, shortRows, null);
        assertThatThrownBy(() -> LegacyStrategyResearch.evaluateLocalV3(
                missingParent, LegacyStrategyResearchCorrectionTest::evaluateCandidate))
                .hasMessageContaining("parent strategy-evidence");

        ObjectNode wrongParentExperiment = exposedExperiment.deepCopy();
        wrongParentExperiment.put("parent_evidence_sha256", "3".repeat(64));
        assertThatThrownBy(() -> LegacyStrategyResearch.evaluateLocalV3(
                evaluationInput(wrongParentExperiment, manifest, candidates, shortRows,
                        wfoResult.path("bundle")),
                LegacyStrategyResearchCorrectionTest::evaluateCandidate))
                .hasMessageContaining("content hash");

        ObjectNode wrongFrozenExperiment = exposedExperiment.deepCopy();
        ((ObjectNode) wrongFrozenExperiment.path("chronology"))
                .set("frozen_candidate_ids", array().add("b"));
        assertThatThrownBy(() -> LegacyStrategyResearch.evaluateLocalV3(
                evaluationInput(wrongFrozenExperiment, manifest, candidates, shortRows,
                        wfoResult.path("bundle")),
                LegacyStrategyResearchCorrectionTest::evaluateCandidate))
                .hasMessageContaining("content hash");

        ArrayNode leakingRows = rows.deepCopy();
        ((ObjectNode) leakingRows.get(0)).put("availability_time", BAR_MILLIS + 1);
        assertThatThrownBy(() -> LegacyStrategyResearch.evaluateLocalV3(
                evaluationInput(exposedExperiment, manifest, candidates, leakingRows,
                        wfoResult.path("bundle")),
                LegacyStrategyResearchCorrectionTest::evaluateCandidate))
                .hasMessageContaining("availability leak");
    }

    private static ObjectNode lineage(ObjectNode contract) {
        ObjectNode value = object().put("precommitSha256", "a".repeat(64))
                .put("definitionSha256", "b".repeat(64))
                .put("candidateSetSha256", "c".repeat(64))
                .put("dataManifestSha256", "d".repeat(64))
                .put("featureSetSha256", "e".repeat(64))
                .put("labelSetSha256", "f".repeat(64));
        value.set("requiredAssets", array().add("btc"));
        value.set("acceptanceContract", contract);
        return value;
    }

    private static ObjectNode evaluationInput(
            JsonNode experiment, JsonNode manifest, JsonNode candidates, JsonNode rows,
            JsonNode parent) {
        ObjectNode value = object();
        value.set("experiment", experiment);
        value.set("manifest", manifest);
        value.set("featureSet", object());
        value.set("labelSet", object());
        value.set("candidates", candidates);
        value.set("featureRows", rows);
        if (parent != null) value.set("parentEvidence", parent);
        return value;
    }

    private static ArrayNode featureRows() {
        ArrayNode rows = array();
        for (int index = 0; index < 16; index++) {
            rows.add(object().put("asset", "btc").put("time", index * BAR_MILLIS)
                    .put("availability_time", index * BAR_MILLIS).put("close", 100)
                    .put("timeframe", "4h"));
        }
        return rows;
    }

    private static ObjectNode fold(
            String id, long trainStart, long trainEnd, long testStart, long testEnd) {
        return object().put("fold_id", id).put("train_start", trainStart)
                .put("train_end", trainEnd).put("test_start", testStart)
                .put("test_end", testEnd);
    }

    private static JsonNode evaluateCandidate(
            ArrayNode series, JsonNode candidate, JsonNode ignored) {
        long first = series.get(0).path("time").asLong();
        long last = series.get(series.size() - 1).path("time").asLong();
        double result = first >= 6 * BAR_MILLIS ? -1 : 1;
        ObjectNode trade = object().put("signal_time", first).put("entry_time", first)
                .put("exit_time", last + BAR_MILLIS).put("entry_price", 100)
                .put("exit_price", 100).put("net_r", result).put("net_pnl", result)
                .put("fees", 0).put("slippage_debit", 0).put("adverse_gap_r", 0)
                .put("notional", 1).put("available_liquidity_notional", 100)
                .put("venue", "public");
        trade.set("instrument", object().put("asset", "btc")
                .put("asset_class", "crypto").put("instrument_type", "spot")
                .put("venue", "public").put("symbol", "BTCUSDT"));
        return object().set("trades", array().add(trade));
    }
}

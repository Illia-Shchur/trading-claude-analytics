package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression coverage for frozen predicate and promoted market-flow gates. */
class StrategyResearchDataV5CoverageTest {
    private static final String HASH = "a".repeat(64);
    private static final String AS_OF = "2026-08-25T00:00:00.000Z";

    @Test
    void candidatePredicatesRejectUnknownAndDuplicatePredictors() {
        ArrayNode predictors = array();
        for (String id : List.of("price_setup", "open_interest", "funding_rate")) {
            predictors.add(object().put("id", id).put("scalar_type", "number")
                    .put("source_field", "close").put("source_family", "price")
                    .put("availability_derivation", "completed_4h_close")
                    .put("pit_role", "PREDICTOR").put("lookback_ms", 0)
                    .put("code_sha256", HASH).put("config_sha256", HASH));
        }
        ObjectNode registry = StrategyResearchDataV5.withHash(object()
                .put("schema", "strategy-v5-predictor-registry/1").put("version", 1)
                .put("status", "FROZEN").set("predictors", predictors));

        assertThatThrownBy(() -> StrategyResearchDataV5.validateCandidatePredicates(
                predicateOptions(registry, "missing")))
                .hasMessageContaining("unregistered predictor");
        ObjectNode duplicate = object().set("predictorRegistry", registry);
        duplicate.set("predicates", array().add(object().put("predictor_id", "price_setup"))
                .add(object().put("predictor_id", "price_setup")));
        assertThatThrownBy(() -> StrategyResearchDataV5.validateCandidatePredicates(duplicate))
                .hasMessageContaining("duplicate predictor IDs");
        assertThat(StrategyResearchDataV5.validateCandidatePredicates(
                predicateOptions(registry, "price_setup"))).isTrue();
    }

    @Test
    void metricCoverageFailsClosedForPartialBelowMinimumAndLatestRetrieval() {
        ObjectNode requirements = requirements("open_interest", "metrics_events", true,
                List.of("open_interest"), .9);
        ObjectNode plan = plan(requirements, "metric-threshold-fixture");

        ObjectNode partial = resolve(plan, requirements,
                captures(plan, new CaptureOverride("btc", "metrics_events", false, null, .5)));
        assertThat(partial.path("status").asText()).isEqualTo("BLOCKED");
        assertThat(texts(partial.path("limitations")))
                .anyMatch(value -> value.contains("PARTIAL_METRIC_COVERAGE"));

        ObjectNode below = resolve(plan, requirements,
                captures(plan, new CaptureOverride("btc", "metrics_events", true,
                        "HISTORICAL_PIT_VINTAGE", .8)));
        assertThat(below.path("status").asText()).isEqualTo("BLOCKED");
        assertThat(texts(below.path("limitations")))
                .anyMatch(value -> value.contains("METRICS_FIELD_COVERAGE_BELOW_FROZEN_MINIMUM"));

        ObjectNode revised = resolve(plan, requirements,
                captures(plan, new CaptureOverride("btc", "metrics_events", true,
                        "LATEST_RETRIEVAL_NOT_HISTORICAL_VINTAGE", 1)));
        assertThat(revised.path("status").asText()).isEqualTo("BLOCKED");
        assertThat(texts(revised.path("limitations")))
                .anyMatch(value -> value.contains(StrategyResearchDataV5.METRICS_PIT_VINTAGE_BLOCK_REASON));
    }

    @Test
    void exactPriceCoverageCanOpenBaseOnlyButCannotDropItsFrozenRequirementArtifact() {
        ObjectNode requirements = requirements("price_setup", "signal_bars", false,
                List.of(), null);
        ObjectNode plan = plan(requirements, "price-threshold-fixture");
        ObjectNode acquisition = acquisition(plan, captures(plan, null));
        ObjectNode resolved = resolve(plan, requirements, (ArrayNode) acquisition.path("captures"));

        assertThat(resolved.path("status").asText()).isEqualTo("READY");
        assertThat(resolved.path("base_complete").asBoolean()).isTrue();

        ObjectNode missingRequirements = object().set("plan", plan);
        missingRequirements.set("acquisition", acquisition);
        missingRequirements.put("requireParquet", false).put("requireFrozenRequirements", false);
        assertThatThrownBy(() -> StrategyResearchDataV5.resolvePromotedCoverage(missingRequirements))
                .hasMessageContaining("frozen plan requires its bound timeframe requirement artifact");
    }

    @Test
    void baseOnlyFundingPredictorUsesOnlyStrictlyPriorAvailableSettlement() {
        long firstDecision = java.time.Instant.parse("2026-01-01T08:00:00Z").toEpochMilli();
        long secondDecision = java.time.Instant.parse("2026-01-01T12:00:00Z").toEpochMilli();
        ObjectNode funding = object().put("id", "funding_signal").put("scalar_type", "number")
                .put("source_field", "funding_rate").put("source_family", "funding_events")
                .put("source_timeframe", "event").put("availability_derivation",
                        "latest_exact_settlement_before_completed_bar")
                .put("pit_role", "PREDICTOR").put("trade_scope", "CONTEXT_ONLY")
                .put("lookback_ms", 86_400_000).put("code_sha256", HASH).put("config_sha256", HASH);
        ObjectNode recipe = object().put("module", "builtin-pit-transform/1").put("kind", "FIELD")
                .put("source_field", "funding_rate").put("source_series", "funding_events");
        recipe.set("required_series_types", array().add("funding_events"));
        recipe.put("lookback_bars", 0).put("min_history", 1)
                .put("window_policy", "COMPLETED_OBSERVATIONS_ONLY")
                .put("availability_policy", "MAX_INPUT_AVAILABILITY")
                .put("series_scope", "SAME_ASSET_FUNDING_SERIES")
                .put("current_observation_policy", "INCLUDE_CURRENT_COMPLETED")
                .put("excluded_window_bars", 0)
                .put("asof_policy", "LATEST_AVAILABLE_STRICTLY_BEFORE_DECISION")
                .put("max_staleness_ms", 86_400_000).put("lag_bars", 0)
                .put("resample_policy", "LAST_AVAILABLE").put("context_only", true)
                .put("module_code_sha256", HASH).put("module_config_sha256", HASH);
        funding.set("recipe", recipe);
        ObjectNode registry = StrategyResearchDataV5.makePredictorRegistry(
                object().set("predictors", array().add(funding)));
        ArrayNode primary = array();
        primary.add(rawPrice(firstDecision)); primary.add(rawPrice(secondDecision));
        ArrayNode context = array();
        context.add(rawFunding(firstDecision - 1, .00125));
        context.add(rawFunding(firstDecision + 1, .00050));
        ObjectNode options = object().set("rawRows", primary); options.set("contextRows", context);
        options.set("predictorRegistry", registry);

        ArrayNode rows = StrategyResearchDataV5.deriveFeatureRowsFromRaw(options);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).path("funding_signal").asDouble()).isEqualTo(.00125);
        assertThat(rows.get(1).path("funding_signal").asDouble()).isEqualTo(.00050);
        assertThat(rows.get(0).path("availability_time").asText())
                .isEqualTo("2026-01-01T08:00:00.000Z");
    }

    private static ObjectNode rawPrice(long decision) {
        return object().put("asset", "btc").put("venue", "BINANCE").put("instrument", "BINANCE_SPOT")
                .put("symbol", "BTCUSDT").put("timeframe", "4h")
                .put("event_time", decision - 14_400_000).put("close_time", decision - 1)
                .put("availability_time", decision).put("close", 100).put("is_closed", true);
    }

    private static ObjectNode rawFunding(long event, double rate) {
        return object().put("asset", "btc").put("venue", "BINANCE")
                .put("instrument", "BINANCE_USDM_PERPETUAL").put("symbol", "BTCUSDT")
                .put("series_type", "funding_events").put("interval", "event")
                .put("event_time", event).put("settlement_slot", event)
                .put("availability_time", event).put("funding_rate", rate).put("is_closed", true);
    }

    private static ObjectNode predicateOptions(ObjectNode registry, String id) {
        ObjectNode options = object().set("predictorRegistry", registry);
        options.set("predicates", array().add(object().put("predictor_id", id)));
        return options;
    }

    private static ObjectNode requirements(String predictorId, String seriesType,
                                           boolean contextOnly, List<String> fields,
                                           Double minimum) {
        ObjectNode declaration = object().put("predictor_id", predictorId).put("interval", "4h")
                .put("context_only", contextOnly);
        declaration.set("series_types", array().add(seriesType));
        if (!fields.isEmpty()) declaration.set("required_fields", strings(fields));
        if (minimum != null) declaration.put("minimum_field_coverage", minimum);
        ObjectNode options = object(); options.set("declarations", array().add(declaration));
        return StrategyResearchDataV5.makeTimeframeRequirements(options);
    }

    private static ObjectNode plan(ObjectNode requirements, String reference) {
        ObjectNode options = object().put("asOf", AS_OF).put("rootReference", reference);
        options.set("timeframeRequirements", requirements);
        return StrategyResearchDataV5.makeFiveYearAuthoritativePlan(options);
    }

    private static ArrayNode captures(ObjectNode plan, CaptureOverride override) {
        ArrayNode captures = array();
        for (JsonNode value : plan.path("series")) {
            ObjectNode series = (ObjectNode) value;
            if (!series.path("required").asBoolean(true)) continue;
            boolean target = override != null && override.asset().equals(series.path("asset").asText())
                    && override.seriesType().equals(series.path("series_type").asText());
            captures.add(capture(series, target ? override : null));
        }
        return captures;
    }

    private static ObjectNode capture(ObjectNode series, CaptureOverride override) {
        boolean complete = override == null || override.complete();
        double fraction = override == null ? 1 : override.fraction();
        long expected = series.path("expected_event_count").asLong(3);
        ObjectNode coverage = object().put("complete", complete);
        if ("metrics_events".equals(series.path("series_type").asText())) {
            coverage.put("expected_rows", expected)
                    .put("observed_rows", complete ? expected : Math.max(0, expected - 1))
                    .put("min_event_time", series.path("start_at").asText())
                    .put("max_event_time", series.path("end_at").asText())
                    .put("minimum_field_coverage",
                            series.path("metric_minimum_field_coverage").asDouble(.95));
            ArrayNode required = series.path("metric_required_fields").isArray()
                    ? (ArrayNode) series.path("metric_required_fields").deepCopy()
                    : array().add("open_interest");
            coverage.set("required_metric_fields", required);
            ArrayNode observed = array();
            for (JsonNode field : required) observed.add(object().put("field", field.asText())
                    .put("observed", fraction == 1 ? expected : (long) Math.floor(expected * fraction))
                    .put("expected", expected).put("fraction", fraction));
            coverage.set("required_field_coverage", observed);
            if (override != null && override.pit() != null)
                coverage.put("metrics_pit_vintage_status", override.pit());
            else coverage.put("metrics_pit_vintage_status", "HISTORICAL_PIT_VINTAGE");
            if (!complete) coverage.put("reason", "PARTIAL_METRIC_COVERAGE");
        } else if ("event".equals(series.path("interval").asText())
                || "funding_events".equals(series.path("series_type").asText())) {
            coverage.put("observed_events", complete ? 3 : 2)
                    .put("boundaries_covered", complete)
                    .put("source_pagination_complete", complete)
                    .put("first_event_time", series.path("start_at").asText())
                    .put("last_event_time", series.path("end_at").asText());
        } else {
            coverage.put("expected_rows", expected)
                    .put("observed_rows", complete ? expected : Math.max(0, expected - 1))
                    .put("min_event_time", series.path("start_at").asText())
                    .put("max_event_time", complete ? series.path("end_at").asText()
                            : series.path("start_at").asText())
                    .put("expected_first_event_time", series.path("start_at").asText())
                    .put("expected_last_event_time", series.path("end_at").asText());
        }
        ObjectNode capture = series.deepCopy(); capture.remove("trade_scope");
        capture.put("required", true);
        capture.set("partition", object().put("path", "staging/" + series.path("asset").asText()
                        + "-" + series.path("interval").asText() + "-"
                        + series.path("series_type").asText() + ".jsonl")
                .put("sha256", HASH).put("bytes", 1)
                .put("row_count", complete ? expected : Math.max(0, expected - 1))
                .put("format", "JSONL").put("storage_role", "STAGING")
                .put("authoritative", false));
        capture.set("coverage", coverage);
        return capture;
    }

    private static ObjectNode resolve(ObjectNode plan, ObjectNode requirements, ArrayNode captures) {
        ObjectNode options = object().set("plan", plan);
        options.set("acquisition", acquisition(plan, captures));
        options.set("timeframeRequirements", requirements);
        options.put("requireParquet", false);
        return StrategyResearchDataV5.resolvePromotedCoverage(options);
    }

    private static ObjectNode acquisition(ObjectNode plan, JsonNode captureRows) {
        ArrayNode captures = (ArrayNode) captureRows;
        ObjectNode value = object().put("schema", StrategyResearchDataV5.DATA_V5.get("acquisition"))
                .put("version", 1).put("status", "STAGING_COMPLETE")
                .put("plan_sha256", plan.path("content_sha256").asText())
                .put("root_reference", "market-flow-gating-fixture")
                .put("staging_format", "JSONL").put("storage_role", "STAGING")
                .put("authoritative", false);
        value.set("captures", captures.deepCopy());
        value.set("source_receipts", array()); value.set("source_receipt_sha256", array());
        value.set("source_receipt_byte_sha256", array()); value.set("limitations", array());
        return StrategyResearchDataV5.withHash(value);
    }

    private static List<String> texts(JsonNode values) {
        List<String> result = new ArrayList<>(); values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }
    private static ArrayNode array() { return JsonHashes.mapper().createArrayNode(); }
    private static ArrayNode strings(List<String> values) {
        ArrayNode result = array(); values.forEach(result::add); return result;
    }

    private record CaptureOverride(String asset, String seriesType, boolean complete,
                                   String pit, double fraction) {}
}

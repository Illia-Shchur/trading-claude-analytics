package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deterministic public-contract fixtures for the high fan-out V5 data producer.
 *
 * <p>These tests deliberately exercise distinct normalized outputs and failure
 * reasons.  They do not call private helpers, alter frozen oracle evidence, or
 * depend on network data.</p>
 */
class StrategyResearchDataV5ExpandedContractTest {
    private static final String HASH = "b".repeat(64);
    private static final long START = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private static final long EIGHT_HOURS = 8L * 60 * 60 * 1_000;
    private static final long FOUR_HOURS = 4L * 60 * 60 * 1_000;

    @Test
    void recipeKindsNormalizeWindowsAndPreservePitContracts() {
        ObjectNode registry = StrategyResearchDataV5.makePredictorRegistry(registry(
                predictor("sma", "close", "price", "4h", windowRecipe("sma", "close", "bars", 3, 3)
                        .put("current_observation_policy", "EXCLUDE_CURRENT_COMPLETED")
                        .put("excluded_window_bars", 1)),
                predictor("zscore", "volume", "price", "4h", windowRecipe("stddev_zscore", "volume", "bars", 4, 4)),
                predictor("rsi", "close", "price", "4h", windowRecipe("rsi", "close", "bars", 3, 4)
                        .put("rsi_method", "wilder_rsi"))));

        assertThat(registry.path("predictors")).hasSize(3);
        ObjectNode sma = predictorFrom(registry, "sma");
        assertThat(sma.path("recipe").path("kind").asText()).isEqualTo("SMA");
        assertThat(sma.path("recipe").path("lookback_bars").asInt()).isEqualTo(3);
        assertThat(sma.path("recipe").path("min_history").asInt()).isEqualTo(3);
        assertThat(sma.path("recipe").path("current_observation_policy").asText())
                .isEqualTo("EXCLUDE_CURRENT_COMPLETED");
        assertThat(sma.path("recipe").path("excluded_window_bars").asInt()).isEqualTo(1);
        assertThat(predictorFrom(registry, "zscore").path("recipe").path("kind").asText())
                .isEqualTo("STDDEV_ZSCORE");
        assertThat(predictorFrom(registry, "rsi").path("recipe").path("rsi_method").asText())
                .isEqualTo("WILDER_RSI");
    }

    @Test
    void recipeValidationRejectsBadPoliciesBoundsAndBindings() {
        ObjectNode badMinimum = predictor("bad_minimum", "close", "price", "4h",
                windowRecipe("sma", "close", "bars", 3, 5));
        assertThatThrownBy(() -> StrategyResearchDataV5.makePredictorRegistry(registry(badMinimum)))
                .hasMessageContaining("recipe history bounds are invalid");

        ObjectNode badPolicy = predictor("bad_policy", "close", "price", "4h",
                windowRecipe("sma", "close", "bars", 3, 3).put("window_policy", "ALL_OBSERVATIONS"));
        assertThatThrownBy(() -> StrategyResearchDataV5.makePredictorRegistry(registry(badPolicy)))
                .hasMessageContaining("recipe PIT policies are incomplete");

        ObjectNode badExcluded = predictor("bad_excluded", "close", "price", "4h",
                windowRecipe("sma", "close", "bars", 3, 3).put("excluded_window_bars", 5));
        assertThatThrownBy(() -> StrategyResearchDataV5.makePredictorRegistry(registry(badExcluded)))
                .hasMessageContaining("recipe excluded window is invalid");

        ObjectNode badRsi = predictor("bad_rsi", "close", "price", "4h",
                windowRecipe("rsi", "close", "bars", 3, 4).put("rsi_method", "EMA_RSI"));
        assertThatThrownBy(() -> StrategyResearchDataV5.makePredictorRegistry(registry(badRsi)))
                .hasMessageContaining("RSI method is not the registered Wilder implementation");

        ObjectNode badHashes = predictor("bad_hashes", "close", "price", "4h",
                windowRecipe("sma", "close", "bars", 3, 3).put("module_code_sha256", "c".repeat(64)));
        assertThatThrownBy(() -> StrategyResearchDataV5.makePredictorRegistry(registry(badHashes)))
                .hasMessageContaining("recipe module hashes are not bound");
    }

    @Test
    void derivedFeaturesEvaluateFieldWindowReturnAndWilderRsiRecipes() {
        ObjectNode registry = StrategyResearchDataV5.makePredictorRegistry(registry(
                predictor("sma", "close", "price", "4h", windowRecipe("sma", "close", "same_series", 3, 3)),
                predictor("zscore", "close", "price", "4h", windowRecipe("stddev_zscore", "close", "same_series", 3, 3)),
                predictor("return", "close", "price", "4h", windowRecipe("return", "close", "same_series", 2, 3)),
                predictor("rsi", "close", "price", "4h", windowRecipe("rsi", "close", "same_series", 3, 4))));
        ArrayNode raw = array();
        for (int index = 0; index < 6; index++) raw.add(priceBar(index, 100 + index));

        ObjectNode options = object();
        options.set("rawRows", raw);
        options.set("predictorRegistry", registry);
        ArrayNode features = StrategyResearchDataV5.deriveFeatureRowsFromRaw(options);

        assertThat(features).hasSize(6);
        ObjectNode fourth = (ObjectNode) features.get(3);
        assertThat(fourth.path("return").asDouble()).isCloseTo(103.0 / 101.0 - 1.0,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(fourth.path("rsi").asDouble()).isEqualTo(100.0);
        assertThat(fourth.path("sma").asDouble()).isCloseTo(102.0, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(fourth.path("zscore").asDouble()).isCloseTo(1.224744871, org.assertj.core.data.Offset.offset(1e-8));
        assertThat(features.get(0).path("signal_eligible").asBoolean()).isFalse();
        assertThat(features.get(5).path("signal_eligible").asBoolean()).isTrue();
    }

    @Test
    void nonEventFundingCanonicalizationBindsSlotsAndReportsMissingCoverage() {
        ObjectNode series = fundingSeries(START, START + 2 * EIGHT_HOURS);
        ArrayNode rows = array().add(funding("f0", START, .001)).add(funding("f1", START + EIGHT_HOURS, -.002))
                .add(funding("f2", START + 2 * EIGHT_HOURS, .003));
        ObjectNode canonicalOptions = object();
        canonicalOptions.set("rows", rows);
        canonicalOptions.set("series", series);
        ObjectNode canonical = StrategyResearchDataV5.canonicalizeFundingRows(canonicalOptions);
        assertThat(canonical.path("coverage").path("complete").asBoolean()).isTrue();
        assertThat(canonical.path("coverage").path("expected_slots").asInt()).isEqualTo(3);
        assertThat(canonical.path("rows")).hasSize(3);
        assertThat(canonical.path("rows").get(1).path("settlement_slot").asText())
                .isEqualTo(Instant.ofEpochMilli(START + EIGHT_HOURS).toString().replace("Z", ".000Z"));

        ArrayNode partialRows = array().add(funding("f0", START, .001));
        ObjectNode partialOptions = object();
        partialOptions.set("rows", partialRows);
        partialOptions.set("series", series);
        ObjectNode partial = StrategyResearchDataV5.canonicalizeFundingRows(partialOptions);
        assertThat(partial.path("coverage").path("complete").asBoolean()).isFalse();
        assertThat(partial.path("coverage").path("missing_slots")).hasSize(2);
    }

    @Test
    void fundingContractsHandleRequestBoundsPnlMarksAndRejectedRows() {
        ObjectNode series = fundingSeries(START, START + EIGHT_HOURS);
        series.put("availability_cutoff_at", START + EIGHT_HOURS + 30_000);
        ObjectNode bounds = StrategyResearchDataV5.fundingRequestBounds(series);
        assertThat(bounds.path("startTime").asLong()).isEqualTo(START - 60_000);
        assertThat(bounds.path("endTime").asLong()).isEqualTo(START + EIGHT_HOURS + 30_000);
        assertThat(StrategyResearchDataV5.computeFundingPnl(object().put("fundingRate", .001)
                .put("settlementMark", 100).put("signedQuantity", -2).put("contractMultiplier", .01)
                .put("quoteMultiplier", 2))).isCloseTo(.004, org.assertj.core.data.Offset.offset(1e-12));

        String responseSha = "d".repeat(64);
        ObjectNode mark = object().put("event_time", iso(START)).put("availability_time", iso(START))
                .put("mark_open", 100).put("response_sha256", responseSha);
        ObjectNode markOptions = object();
        markOptions.set("fundingRows", array().add(funding("f0", START, .001)
                .put("settlement_slot", iso(START))));
        markOptions.set("markRows", array().add(mark));
        markOptions.set("markResponseSha256", array().add(responseSha));
        ArrayNode marked = StrategyResearchDataV5.bindFundingSettlementMarks(markOptions);
        assertThat(marked).hasSize(1);
        assertThat(marked.get(0).path("settlement_mark").asDouble()).isEqualTo(100.0);

        assertThatThrownBy(() -> StrategyResearchDataV5.computeFundingPnl(object().put("fundingRate", .001)
                .put("settlementMark", 0).put("signedQuantity", 1).put("contractMultiplier", 1)))
                .hasMessageContaining("finite rate/mark/position");
        ObjectNode outsideOptions = object();
        outsideOptions.set("rows", array().add(funding("f0", START + 2 * EIGHT_HOURS + 1_000_000, .001)));
        outsideOptions.set("series", series);
        assertThatThrownBy(() -> StrategyResearchDataV5.canonicalizeFundingRows(outsideOptions))
                .hasMessageContaining("outside declared cadence segments");
        ObjectNode unretainedOptions = object();
        unretainedOptions.set("fundingRows", array().add(funding("f0", START, .001)
                .put("settlement_slot", iso(START))));
        unretainedOptions.set("markRows", array().add(mark));
        unretainedOptions.set("markResponseSha256", array().add(HASH));
        assertThatThrownBy(() -> StrategyResearchDataV5.bindFundingSettlementMarks(unretainedOptions))
                .hasMessageContaining("response SHA is not physically retained");
    }

    @Test
    void timeframeRequirementsNormalizeEventsMetricsAndDefaultFourHourInterval() {
        ObjectNode options = object().put("precommitSha256", HASH).put("predictorRegistrySha256", "c".repeat(64));
        ArrayNode declarations = options.putArray("declarations");
        ObjectNode close = object().put("predictor_id", "close").put("interval", "1H");
        close.set("series_types", array().add("signal_bars"));
        declarations.add(close);
        ObjectNode oi = object().put("predictor_id", "oi").put("interval", "event").put("context_only", true);
        oi.set("series_types", array().add("metrics_events"));
        oi.set("required_fields", array().add("open_interest"));
        oi.put("minimum_field_coverage", .8);
        declarations.add(oi);
        ObjectNode fundingDeclaration = object().put("predictor_id", "funding").put("interval", "event");
        fundingDeclaration.set("series_types", array().add("funding_events"));
        declarations.add(fundingDeclaration);
        ObjectNode requirements = StrategyResearchDataV5.makeTimeframeRequirements(options);
        assertThat(requirements.path("required_intervals")).extracting(JsonNode::asText)
                .containsExactly("1h", "4h");
        assertThat(requirements.path("declarations")).hasSize(3);
        assertThat(declarationFrom(requirements, "oi").path("minimum_field_coverage").asDouble())
                .isEqualTo(.8);
    }

    @Test
    void timeframeRequirementsRejectInvalidIntervalSeriesAndMetricMinimum() {
        ObjectNode invalidInterval = object().set("declarations", array().add(declaration("p", "2h", "signal_bars")));
        assertThatThrownBy(() -> StrategyResearchDataV5.makeTimeframeRequirements(invalidInterval))
                .hasMessageContaining("interval is not permitted");
        ObjectNode eventBars = object().set("declarations", array().add(declaration("p", "event", "signal_bars")));
        assertThatThrownBy(() -> StrategyResearchDataV5.makeTimeframeRequirements(eventBars))
                .hasMessageContaining("cannot request bar series");
        ObjectNode barFunding = object().set("declarations", array().add(declaration("p", "4h", "funding_events")));
        assertThatThrownBy(() -> StrategyResearchDataV5.makeTimeframeRequirements(barFunding))
                .hasMessageContaining("funding events require the event timeframe");
        ObjectNode badMinimum = object().set("declarations", array().add(
                declaration("p", "event", "metrics_events").put("minimum_field_coverage", 1.1)));
        assertThatThrownBy(() -> StrategyResearchDataV5.makeTimeframeRequirements(badMinimum))
                .hasMessageContaining("minimum_field_coverage is invalid");
        assertThatThrownBy(() -> StrategyResearchDataV5.makeTimeframeRequirements(object().set("declarations", array())))
                .hasMessageContaining("must contain at least one frozen declaration");
    }

    @Test
    void timeframeRequirementsFromRegistryMapsPriceMetricFundingAndMarkFamilies() {
        ObjectNode registry = StrategyResearchDataV5.makePredictorRegistry(registry(
                predictorWithoutRecipe("price", "close", "price", "4h"),
                predictorWithoutRecipe("oi", "sum_open_interest", "market_flow", "4h")
                        .put("trade_scope", "CONTEXT_ONLY"),
                predictorWithoutRecipe("funding", "funding_rate", "funding", "event"),
                predictorWithoutRecipe("mark", "mark_price", "mark", "4h")));
        ObjectNode options = object().put("precommitSha256", HASH);
        options.set("predictorRegistry", registry);
        ObjectNode requirements = StrategyResearchDataV5.makeTimeframeRequirementsFromPredictorRegistry(options);
        assertThat(requirements.path("declarations")).hasSize(4);
        assertThat(declarationFrom(requirements, "oi").path("series_types").get(0).asText())
                .isEqualTo("metrics_events");
        assertThat(declarationFrom(requirements, "oi").path("context_only").asBoolean()).isTrue();
        assertThat(declarationFrom(requirements, "funding").path("interval").asText()).isEqualTo("event");
        assertThat(declarationFrom(requirements, "mark").path("series_types").get(0).asText())
                .isEqualTo("mark_bars");
    }

    @Test
    void precommitScopeNormalizesFixedSpotAndPerpetualDeclarations() {
        ObjectNode spot = object().put("universe", "CRYPTO_ONLY");
        spot.set("instruments", array().add(object().put("instrument_type", "spot").put("asset", "BTC")));
        ObjectNode precommit = object();
        precommit.set("tradable_instrument_contract", spot);
        precommit.set("trade_assets", array().add("btc"));
        ObjectNode candidate = object().put("instrument_type", "BINANCE_SPOT");
        ObjectNode scopeOptions = object();
        scopeOptions.set("candidateTemplate", candidate);
        ObjectNode result = StrategyResearchDataV5.derivePrecommitTradeScopeV5(precommit,
                scopeOptions);
        assertThat(result.path("instrument").asText()).isEqualTo("BINANCE_SPOT");
        assertThat(result.path("trade_assets")).extracting(JsonNode::asText).containsExactly("btc");

        ObjectNode perp = object().put("universe", "CRYPTO_ONLY");
        perp.set("instruments", array().add("perpetual"));
        ObjectNode embedded = object();
        embedded.set("tradable_instrument_contract", perp);
        embedded.set("trade_assets", array().add("eth"));
        assertThat(StrategyResearchDataV5.derivePrecommitTradeScopeV5(embedded).path("instrument").asText())
                .isEqualTo("BINANCE_USDM_PERPETUAL");
    }

    @Test
    void precommitScopeRejectsGenesDatedFuturesAssetsAndCandidateMismatch() {
        ObjectNode gene = object().put("universe", "CRYPTO_ONLY");
        ObjectNode geneInstrument = object();
        geneInstrument.set("$gene", object().put("values", "spot"));
        gene.set("instruments", array().add(geneInstrument));
        ObjectNode genePrecommit = object();
        genePrecommit.set("tradable_instrument_contract", gene);
        genePrecommit.set("trade_assets", array().add("btc"));
        assertThatThrownBy(() -> StrategyResearchDataV5.derivePrecommitTradeScopeV5(
                genePrecommit))
                .hasMessageContaining("cannot be a structural gene");

        ObjectNode dated = object().put("universe", "CRYPTO_ONLY");
        dated.set("instruments", array().add("dated_future"));
        ObjectNode datedPrecommit = object();
        datedPrecommit.set("tradable_instrument_contract", dated);
        datedPrecommit.set("trade_assets", array().add("btc"));
        assertThatThrownBy(() -> StrategyResearchDataV5.derivePrecommitTradeScopeV5(
                datedPrecommit))
                .hasMessageContaining("dated-future research requires");

        ObjectNode unsupportedAsset = object().put("universe", "CRYPTO_ONLY");
        unsupportedAsset.set("instruments", array().add(object().put("instrument_type", "spot").put("asset", "doge")));
        ObjectNode unsupportedPrecommit = object();
        unsupportedPrecommit.set("tradable_instrument_contract", unsupportedAsset);
        assertThatThrownBy(() -> StrategyResearchDataV5.derivePrecommitTradeScopeV5(
                unsupportedPrecommit))
                .hasMessageContaining("unsupported crypto asset");

        ObjectNode mismatch = object().put("universe", "CRYPTO_ONLY");
        mismatch.set("instruments", array().add("spot"));
        ObjectNode mismatchPrecommit = object();
        mismatchPrecommit.set("tradable_instrument_contract", mismatch);
        mismatchPrecommit.set("trade_assets", array().add("btc"));
        ObjectNode mismatchOptions = object();
        mismatchOptions.set("candidateTemplate", object().put("instrument_type", "perpetual"));
        assertThatThrownBy(() -> StrategyResearchDataV5.derivePrecommitTradeScopeV5(
                mismatchPrecommit, mismatchOptions))
                .hasMessageContaining("differs from the precommit");
    }

    @Test
    void denseCoverageReportsGridAvailabilityBoundaryAndIrregularBarStates() {
        ObjectNode series = object().put("start_at", iso(START)).put("end_at", iso(START + 2 * FOUR_HOURS))
                .put("availability_cutoff_at", iso(START + 3 * FOUR_HOURS)).put("expected_step_ms", FOUR_HOURS);
        ArrayNode completeRows = array();
        for (int index = 0; index <= 2; index++) completeRows.add(bar(START + index * FOUR_HOURS,
                START + (index + 1) * FOUR_HOURS));
        ObjectNode completeOptions = object();
        completeOptions.set("rows", completeRows);
        completeOptions.set("series", series);
        ObjectNode complete = StrategyResearchDataV5.validateDenseBarCoverageV5(completeOptions);
        assertThat(complete.path("complete").asBoolean()).isTrue();
        assertThat(complete.path("irregular_bar_count").asInt()).isZero();

        ObjectNode noRowsOptions = object();
        noRowsOptions.set("rows", array());
        noRowsOptions.set("series", series);
        assertThat(StrategyResearchDataV5.validateDenseBarCoverageV5(noRowsOptions).path("reason").asText())
                .isEqualTo("NO_ROWS");
        ObjectNode duplicateOptions = object();
        duplicateOptions.set("rows", array().add(bar(START, START + FOUR_HOURS))
                .add(bar(START, START + FOUR_HOURS)));
        duplicateOptions.set("series", series);
        assertThat(StrategyResearchDataV5.validateDenseBarCoverageV5(duplicateOptions)
                .path("reason").asText()).isEqualTo("MISSING_OR_DUPLICATE_BAR");
        ObjectNode beforeOptions = object();
        beforeOptions.set("rows", fullGrid());
        beforeOptions.set("series", series);
        ((ObjectNode) ((ArrayNode) beforeOptions.path("rows")).get(0)).put("availability_time", START - 1);
        assertThat(StrategyResearchDataV5.validateDenseBarCoverageV5(beforeOptions)
                .path("reason").asText()).isEqualTo("AVAILABILITY_BEFORE_EVENT");
        ObjectNode afterOptions = object();
        afterOptions.set("rows", fullGrid());
        ((ObjectNode) ((ArrayNode) afterOptions.path("rows")).get(0)).put("availability_time", START + 3 * FOUR_HOURS + 1);
        afterOptions.set("series", series);
        assertThat(StrategyResearchDataV5.validateDenseBarCoverageV5(afterOptions)
                .path("reason").asText()).isEqualTo("AVAILABILITY_AFTER_CUTOFF");

        ObjectNode earlyOptions = object();
        earlyOptions.set("rows", fullGrid());
        ((ObjectNode) ((ArrayNode) earlyOptions.path("rows")).get(0)).put("availability_time", START + 1_000);
        earlyOptions.set("series", series);
        assertThat(StrategyResearchDataV5.validateDenseBarCoverageV5(earlyOptions)
                .path("reason").asText()).isEqualTo("BAR_AVAILABLE_BEFORE_CLOSE");
        ObjectNode lateOptions = object();
        lateOptions.set("rows", fullGrid());
        ((ObjectNode) ((ArrayNode) lateOptions.path("rows")).get(0)).put("availability_time", START + FOUR_HOURS + 1);
        lateOptions.set("series", series);
        assertThat(StrategyResearchDataV5.validateDenseBarCoverageV5(lateOptions)
                .path("reason").asText()).isEqualTo("BAR_AVAILABLE_AFTER_CLOSE");

        ObjectNode irregularOptions = object();
        irregularOptions.set("rows", fullGrid());
        ((ObjectNode) ((ArrayNode) irregularOptions.path("rows")).get(0)).put("close_time", iso(START + FOUR_HOURS - 2_000))
                .put("availability_time", iso(START + FOUR_HOURS - 2_000));
        irregularOptions.set("series", series);
        ObjectNode irregularResult = StrategyResearchDataV5.validateDenseBarCoverageV5(irregularOptions);
        assertThat(irregularResult.path("complete").asBoolean()).isTrue();
        assertThat(irregularResult.path("irregular_bar_count").asInt()).isEqualTo(1);
    }

    @Test
    void oneMinuteDenseCoverageUsesExplicitMinuteGrid() {
        long end = START + 2 * 60_000;
        ObjectNode series = object().put("start_at", iso(START)).put("end_at", iso(end))
                .put("availability_cutoff_at", iso(end + 60_000)).put("expected_step_ms", FOUR_HOURS);
        ArrayNode rows = array().add(bar(START, START + 60_000)).add(bar(START + 60_000, START + 120_000))
                .add(bar(end, end + 60_000));
        ObjectNode options = object().put("oneMinute", true);
        options.set("rows", rows);
        options.set("series", series);
        ObjectNode result = StrategyResearchDataV5.validateDenseBarCoverageV5(options);
        assertThat(result.path("complete").asBoolean()).isTrue();
        assertThat(result.path("expected_rows").asInt()).isEqualTo(3);
    }

    @Test
    void fundingDiscoveryAndEventSequenceCanonicalizationBindObservedCadence() {
        ArrayNode observed = array().add(funding("f0", START, .001))
                .add(funding("f1", START + EIGHT_HOURS, .002))
                .add(funding("f2", START + EIGHT_HOURS + FOUR_HOURS, .003));
        ObjectNode discoveryOptions = object().put("startAt", iso(START))
                .put("endAt", iso(START + 2 * EIGHT_HOURS));
        discoveryOptions.set("rows", observed);
        ArrayNode segments = StrategyResearchDataV5.discoverFundingCadenceSegments(discoveryOptions);
        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).path("cadence_ms").asLong()).isEqualTo(EIGHT_HOURS);
        assertThat(segments.get(1).path("cadence_ms").asLong()).isEqualTo(FOUR_HOURS);

        ObjectNode sequence = fundingSeries(START, START + 2 * EIGHT_HOURS);
        sequence.put("event_sequence_mode", true).put("source_coverage_complete", true);
        ObjectNode sequenceOptions = object();
        sequenceOptions.set("rows", array().add(funding("f0", START, .001))
                .add(funding("f1", START + EIGHT_HOURS, .002))
                .add(funding("f2", START + 2 * EIGHT_HOURS, .003))
                .add(funding("outside", START - EIGHT_HOURS, .009)));
        sequenceOptions.set("series", sequence);
        ObjectNode canonical = StrategyResearchDataV5.canonicalizeFundingRows(sequenceOptions);
        assertThat(canonical.path("coverage").path("coverage_mode").asText()).isEqualTo("EVENT_SEQUENCE");
        assertThat(canonical.path("coverage").path("complete").asBoolean()).isTrue();
        assertThat(canonical.path("rows")).hasSize(3);

        sequence.put("require_source_coverage", true).put("source_coverage_complete", false);
        ObjectNode incompleteOptions = object();
        incompleteOptions.set("rows", array().add(funding("f0", START, .001))
                .add(funding("f1", START + EIGHT_HOURS, .002))
                .add(funding("f2", START + 2 * EIGHT_HOURS, .003)));
        incompleteOptions.set("series", sequence);
        assertThat(StrategyResearchDataV5.canonicalizeFundingRows(incompleteOptions)
                .path("coverage").path("complete").asBoolean()).isFalse();
        ObjectNode badDiscovery = object().put("startAt", iso(START)).put("endAt", iso(START + EIGHT_HOURS));
        badDiscovery.set("rows", array().add(funding("bad", START, .001))
                .add(funding("bad-gap", START + 10 * 60 * 60 * 1_000, .002)));
        assertThatThrownBy(() -> StrategyResearchDataV5.discoverFundingCadenceSegments(badDiscovery))
                .hasMessageContaining("unsupported funding cadence gap");
    }

    @Test
    void fiveYearPlanBindsRequirementsAndDatedCatalogEvidence() {
        ObjectNode declaration = object().put("predictor_id", "oi").put("interval", "4h")
                .put("context_only", true).put("minimum_field_coverage", .9);
        declaration.set("series_types", array().add("metrics_events"));
        declaration.set("required_fields", array().add("open_interest"));
        ObjectNode requirementOptions = object();
        requirementOptions.set("declarations", array().add(declaration));
        ObjectNode requirements = StrategyResearchDataV5.makeTimeframeRequirements(requirementOptions);

        ObjectNode validContract = object().put("asset", "btc").put("symbol", "BTCUSD_20250101")
                .put("history_status", "AVAILABLE").put("first_bar_at", "2022-01-01T00:00:00.000Z")
                .put("last_bar_at", "2026-01-01T00:00:00.000Z").put("expiry", "2026-01-01T00:00:00.000Z")
                .put("tradeable", true).put("expiry_binding_status", "BOUND");
        ObjectNode unavailableContract = object().put("asset", "eth").put("symbol", "ETHUSD_20250101")
                .put("history_status", "UNAVAILABLE");
        ObjectNode outsideContract = object().put("asset", "doge").put("symbol", "DOGEUSD_20250101")
                .put("first_bar_at", "2022-01-01T00:00:00.000Z").put("expiry", "2026-01-01T00:00:00.000Z");
        ObjectNode catalog = object().put("status", "COMPLETE").put("content_sha256", HASH);
        catalog.set("contracts", array().add(validContract));
        catalog.set("limitations", array().add("CATALOG_FIXTURE_LIMITATION"));
        ObjectNode options = object().put("asOf", "2026-08-25T00:00:00.000Z")
                .put("rootReference", "expanded-plan-fixture");
        options.set("timeframeRequirements", requirements);
        options.set("datedContracts", array().add(unavailableContract).add(outsideContract));
        options.set("datedFuturesCatalog", catalog);
        ObjectNode plan = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(options);

        assertThat(plan.path("dated_futures_catalog_status").asText()).isEqualTo("COMPLETE");
        assertThat(plan.path("timeframe_requirements_sha256").asText()).isEqualTo(requirements.path("content_sha256").asText());
        assertThat(plan.path("limitations").toString()).contains("DATED_FUTURES_OUTSIDE_UNIVERSE_IGNORED")
                .contains("CATALOG_FIXTURE_LIMITATION");
        assertThat(plan.path("series").findValuesAsText("instrument"))
                .contains("BINANCE_USDM_DATED_FUTURE");
        assertThat(plan.path("series").findValuesAsText("series_type"))
                .contains("metrics_events", "funding_events");
    }

    @Test
    void metadataReceiptNormalizesModelAndUnavailableContractStatuses() {
        ObjectNode feeRecord = metadataRecord("btc", "BINANCE_SPOT", START, START + EIGHT_HOURS)
                .put("availability_time", iso(START + EIGHT_HOURS)).put("taker_fee_rate", .001);
        ObjectNode modelOptions = object().put("kind", "fee_schedule").put("status", "CONSERVATIVE_MODEL")
                .put("modelSha256", HASH).put("precommitSha256", "c".repeat(64))
                .put("capturedAt", iso(START + EIGHT_HOURS));
        modelOptions.set("records", array().add(feeRecord));
        modelOptions.set("limitations", array().add("MODEL_ASSUMPTION"));
        ObjectNode model = StrategyResearchDataV5.makeMetadataReceipt(modelOptions);
        assertThat(model.path("kind").asText()).isEqualTo("FEE_SCHEDULE");
        assertThat(model.path("provenance_mode").asText()).isEqualTo("MODEL_BOUND");
        assertThat(model.path("authoritative").asBoolean()).isFalse();
        assertThat(model.path("records").get(0).path("venue").asText()).isEqualTo("BINANCE");

        ObjectNode unavailableOptions = object().put("kind", "liquidation").put("status", "UNAVAILABLE")
                .put("capturedAt", iso(START));
        unavailableOptions.set("records", array());
        unavailableOptions.set("limitations", array().add("NO_PUBLIC_HISTORY"));
        ObjectNode unavailable = StrategyResearchDataV5.makeMetadataReceipt(unavailableOptions);
        assertThat(unavailable.path("kind").asText()).isEqualTo("LIQUIDATION");
        assertThat(unavailable.path("provenance_mode").asText()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.path("limitations")).extracting(JsonNode::asText)
                .containsExactly("NO_PUBLIC_HISTORY");
        assertThatThrownBy(() -> StrategyResearchDataV5.makeMetadataReceipt(object()
                .put("kind", "unknown").put("status", "UNAVAILABLE")))
                .hasMessageContaining("unsupported metadata kind");
    }

    private static ObjectNode predictor(String id, String sourceField, String family,
            String timeframe, ObjectNode recipe) {
        ObjectNode predictor = predictorWithoutRecipe(id, sourceField, family, timeframe);
        predictor.set("recipe", recipe);
        return predictor;
    }

    private static ObjectNode predictorWithoutRecipe(String id, String sourceField, String family, String timeframe) {
        return object().put("id", id).put("scalar_type", "number").put("source_field", sourceField)
                .put("source_family", family).put("source_timeframe", timeframe)
                .put("availability_derivation", "completed_observation_available")
                .put("pit_role", "PREDICTOR").put("lookback_ms", 0).put("code_sha256", HASH)
                .put("config_sha256", HASH);
    }

    private static ObjectNode windowRecipe(String kind, String sourceField, String sourceSeries,
            int lookback, int minimum) {
        return object().put("module", "builtin-pit-transform/1").put("kind", kind)
                .put("source_field", sourceField).put("source_series", sourceSeries)
                .put("lookback_bars", lookback).put("min_history", minimum)
                .put("window_policy", "COMPLETED_OBSERVATIONS_ONLY")
                .put("availability_policy", "MAX_INPUT_AVAILABILITY")
                .put("series_scope", "SAME_ASSET_VENUE_INSTRUMENT_SYMBOL")
                .put("module_code_sha256", HASH).put("module_config_sha256", HASH);
    }

    private static ObjectNode registry(ObjectNode... predictors) {
        ObjectNode result = object();
        ArrayNode rows = result.putArray("predictors");
        for (ObjectNode predictor : predictors) rows.add(predictor);
        return result;
    }

    private static ObjectNode predictorFrom(ObjectNode registry, String id) {
        for (JsonNode node : registry.path("predictors")) {
            if (id.equals(node.path("id").asText())) return (ObjectNode) node;
        }
        throw new AssertionError("missing predictor " + id);
    }

    private static ObjectNode priceBar(int index, double close) {
        long event = START + index * FOUR_HOURS;
        return object().put("asset", "btc").put("venue", "BINANCE").put("instrument", "BINANCE_SPOT")
                .put("symbol", "BTCUSDT").put("timeframe", "4h").put("event_time", event)
                .put("close_time", event + FOUR_HOURS - 1).put("availability_time", event + FOUR_HOURS)
                .put("close", close).put("is_closed", true);
    }

    private static ObjectNode fundingSeries(long start, long end) {
        ObjectNode value = object().put("series_type", "funding_events").put("start_at", iso(start))
                .put("end_at", iso(end)).put("availability_cutoff_at", iso(end + 60_000))
                .put("slot_tolerance_ms", 60_000).put("event_sequence_mode", false);
        value.set("cadence_segments", array().add(object().put("effective_from", iso(start))
                .put("effective_to", iso(end)).put("cadence_ms", EIGHT_HOURS).put("origin_at", iso(start))));
        return value;
    }

    private static ObjectNode funding(String id, long event, double rate) {
        return object().put("event_id", id).put("raw_event_time", iso(event)).put("funding_rate", rate)
                .put("availability_time", iso(event));
    }

    private static ObjectNode metadataRecord(String asset, String instrument, long from, long to) {
        return object().put("asset", asset).put("instrument", instrument)
                .put("effective_from", iso(from)).put("effective_to", iso(to));
    }

    private static ObjectNode declaration(String id, String interval, String type) {
        ObjectNode value = object().put("predictor_id", id).put("interval", interval);
        value.set("series_types", array().add(type));
        return value;
    }

    private static ObjectNode declarationFrom(ObjectNode requirements, String id) {
        for (JsonNode node : requirements.path("declarations")) {
            if (id.equals(node.path("predictor_id").asText())) return (ObjectNode) node;
        }
        throw new AssertionError("missing declaration " + id);
    }

    private static ObjectNode bar(long event, long availability) {
        return object().put("event_time", event).put("availability_time", availability)
                .put("close_time", availability).put("is_closed", true);
    }

    private static ArrayNode fullGrid() {
        return array().add(bar(START, START + FOUR_HOURS)).add(bar(START + FOUR_HOURS, START + 2 * FOUR_HOURS))
                .add(bar(START + 2 * FOUR_HOURS, START + 3 * FOUR_HOURS));
    }

    private static String iso(long value) {
        return Instant.ofEpochMilli(value).toString().replace("Z", ".000Z");
    }

    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }
    private static ArrayNode array() { return JsonHashes.mapper().createArrayNode(); }
}

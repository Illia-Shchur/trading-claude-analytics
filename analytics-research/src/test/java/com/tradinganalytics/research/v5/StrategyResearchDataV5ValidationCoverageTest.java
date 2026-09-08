package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.CustodyException;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.io.TempDir;

/**
 * Focused public-contract coverage for the V5 data producer.
 *
 * <p>The quarantine oracle exercises the historical Node parity fixtures.  This
 * class supplies small deterministic contract fixtures for validation paths
 * which are otherwise difficult to reach through the large physical replay.
 * It deliberately uses public APIs and asserts normalized values or rejection
 * contracts rather than treating any exception as success.</p>
 */
class StrategyResearchDataV5ValidationCoverageTest {
    private static final ObjectMapper JSON = JsonHashes.mapper();
    private static final String H = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final long START = 1_700_000_000_000L;
    private static final long END = START + 86_400_000L;

    @Test
    void predictorRecipesNormalizeFieldReferenceAndFundingContracts() {
        ObjectNode registry = StrategyResearchDataV5.makePredictorRegistry(registry(
                predictor("close_value", "close", "price", "4h", fieldRecipe()),
                predictor("spy_return", "close", "price", "1d", returnRecipe()),
                predictor("funding_rsi", "funding_rate", "funding", "event", fundingRsiRecipe())));

        assertThat(registry.path("schema").asText()).isEqualTo("strategy-v5-predictor-registry/1");
        assertThat(registry.path("status").asText()).isEqualTo("FROZEN");
        assertThat(registry.path("predictors")).hasSize(3);
        assertThat(registry.path("predictors").get(0).path("id").asText()).isEqualTo("close_value");

        ObjectNode field = predictorFrom(registry, "close_value");
        assertThat(field.path("recipe").path("kind").asText()).isEqualTo("FIELD");
        assertThat(field.path("recipe").path("lookback_bars").asInt()).isZero();
        assertThat(field.path("recipe").path("min_history").asInt()).isEqualTo(1);
        assertThat(field.path("recipe").path("current_observation_policy").asText())
                .isEqualTo("INCLUDE_CURRENT_COMPLETED");

        ObjectNode reference = (ObjectNode) predictorFrom(registry, "spy_return").path("recipe");
        assertThat(reference.path("kind").asText()).isEqualTo("RETURN");
        assertThat(reference.path("reference_series").path("asset").asText()).isEqualTo("spy");
        assertThat(reference.path("reference_series").path("venue").asText()).isEqualTo("NYSE");
        assertThat(reference.path("reference_series").path("instrument").asText()).isEqualTo("EQUITY");
        assertThat(reference.path("reference_series").path("symbol").asText()).isEqualTo("SPY");
        assertThat(reference.path("context_only").asBoolean()).isTrue();
        assertThat(reference.path("resample_policy").asText()).isEqualTo("BAR_CLOSE");
        assertThat(reference.path("lag_bars").asInt()).isEqualTo(1);

        ObjectNode funding = (ObjectNode) predictorFrom(registry, "funding_rsi").path("recipe");
        assertThat(funding.path("kind").asText()).isEqualTo("RSI");
        assertThat(funding.path("lookback_bars").asInt()).isEqualTo(14);
        assertThat(funding.path("min_history").asInt()).isEqualTo(15);
        assertThat(funding.path("required_series_types").toString()).isEqualTo("[\"funding_events\"]");
        assertThat(funding.path("asof_policy").asText()).isEqualTo("LATEST_AVAILABLE_STRICTLY_BEFORE_DECISION");
        assertThat(funding.path("rsi_method").asText()).isEqualTo("WILDER_RSI");
    }

    @Test
    void predictorRecipesRejectInvalidKindsScopesAndHistory() {
        ObjectNode unsupportedKind = predictor("bad_kind", "close", "price", "4h", fieldRecipe());
        unsupportedKind.with("recipe").put("kind", "median");
        assertThatThrownBy(() -> StrategyResearchDataV5.makePredictorRegistry(registry(unsupportedKind)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recipe kind is unsupported");

        ObjectNode invalidReference = predictor("bad_reference", "close", "price", "1d", returnRecipe());
        invalidReference.with("recipe").put("context_only", false);
        assertThatThrownBy(() -> StrategyResearchDataV5.makePredictorRegistry(registry(invalidReference)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-crypto reference series must be context_only");

        ObjectNode invalidFunding = predictor("bad_funding", "close", "funding", "event", fundingRsiRecipe());
        invalidFunding.with("recipe").put("source_field", "close");
        assertThatThrownBy(() -> StrategyResearchDataV5.makePredictorRegistry(registry(invalidFunding)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("funding reference source contract is invalid");

        ObjectNode invalidHistory = predictor("bad_history", "funding_rate", "funding", "event", fundingRsiRecipe());
        invalidHistory.with("recipe").put("min_history", 14);
        assertThatThrownBy(() -> StrategyResearchDataV5.makePredictorRegistry(registry(invalidHistory)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recipe history bounds are invalid");

        ObjectNode invalidRequiredTypes = predictor("bad_types", "funding_rate", "funding", "event", fundingRsiRecipe());
        ArrayNode types = invalidRequiredTypes.with("recipe").putArray("required_series_types");
        types.add("funding_events").add("signal_bars");
        assertThatThrownBy(() -> StrategyResearchDataV5.makePredictorRegistry(registry(invalidRequiredTypes)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required_series_types are invalid");
    }

    @Test
    void metadataCoverageAcceptsLowercaseReceiptAndContinuousRecord() {
        ObjectNode receipt = metadataReceipt("FEE_SCHEDULE", "PUBLIC_OBSERVED")
                .set("records", JSON.createArrayNode().add(metadataRecord("btc", "BINANCE_SPOT", START - 1_000, END + 1_000)));
        receipt.put("content_sha256", StrategyResearchDataV5.ownHash(receipt));

        // The lower-case map key exercises the producer's compatibility lookup.
        ObjectNode options = metadataOptions();
        options.putArray("requiredKinds").add("FEE_SCHEDULE");
        options.putArray("requiredPairs").add("btc|BINANCE_SPOT");
        options.with("receipts").set("fee_schedule", receipt);
        ObjectNode result = StrategyResearchDataV5.verifyMetadataCoverage(options);

        assertThat(result.path("pass").asBoolean()).isTrue();
        assertThat(result.path("limitations")).isEmpty();
        assertThat(result.path("coverage").path("FEE_SCHEDULE").path("status").asText())
                .isEqualTo("PUBLIC_OBSERVED");
        assertThat(result.path("coverage").path("FEE_SCHEDULE").path("missing_pairs")).isEmpty();
    }

    @Test
    void metadataCoverageReportsUnavailableMissingTamperedAndUncoveredEvidence() {
        ObjectNode options = metadataOptions();
        options.putArray("requiredKinds")
                .add("FEE_SCHEDULE").add("FUNDING_IDENTITY").add("MARGIN").add("LIQUIDATION");
        options.putArray("requiredPairs")
                .add("btc|BINANCE_SPOT").add("eth|BINANCE_SPOT").add("malformed");

        ObjectNode fee = metadataReceipt("FEE_SCHEDULE", "PUBLIC_OBSERVED")
                .set("records", JSON.createArrayNode().add(metadataRecord("btc", "BINANCE_SPOT", START, END)));
        fee.put("content_sha256", StrategyResearchDataV5.ownHash(fee));
        options.with("receipts").set("fee_schedule", fee);

        ObjectNode unavailable = metadataReceipt("FUNDING_IDENTITY", "UNAVAILABLE")
                .set("limitations", JSON.createArrayNode().add("NO_HISTORY"));
        unavailable.put("content_sha256", StrategyResearchDataV5.ownHash(unavailable));
        options.with("receipts").set("funding_identity", unavailable);

        ObjectNode tampered = metadataReceipt("LIQUIDATION", "PUBLIC_OBSERVED");
        tampered.put("content_sha256", H);
        options.with("receipts").set("liquidation", tampered);

        ObjectNode result = StrategyResearchDataV5.verifyMetadataCoverage(options);

        assertThat(result.path("pass").asBoolean()).isFalse();
        assertThat(result.path("coverage").path("FEE_SCHEDULE").path("missing_pairs"))
                .extracting(JsonNode::asText)
                .containsExactly("eth|BINANCE_SPOT", "malformed");
        assertThat(result.path("limitations").toString())
                .contains("FUNDING_IDENTITY: NO_HISTORY")
                .contains("MARGIN: MISSING_OR_TAMPERED_RECEIPT")
                .contains("LIQUIDATION: MISSING_OR_TAMPERED_RECEIPT")
                .contains("FEE_SCHEDULE: UNCOVERED_PAIRS:eth|BINANCE_SPOT,malformed");
    }

    @Test
    void sourceBundleVerificationRejectsSymlinkedManifestReference(
            @TempDir Path root, @TempDir Path outsideDir) throws Exception {
        Path outside = outsideDir.resolve("bundle.json");
        ObjectNode acquisitionReference = JSON.createObjectNode().put("path", "acquisition.json")
                .put("content_sha256", H).put("byte_sha256", H).put("bytes", 1);
        ObjectNode hydrationReference = JSON.createObjectNode().put("path", "hydration.json")
                .put("content_sha256", H).put("byte_sha256", H).put("bytes", 1);
        ObjectNode bundle = JSON.createObjectNode().put("schema", StrategyResearchDataV5.DATA_V5.get("sourceBundle"))
                .put("version", 1).put("status", "VERIFIED_COMPLETE")
                .put("plan_sha256", H).put("candidate_set_sha256", H).put("envelope_sha256", H);
        bundle.set("acquisition_reference", acquisitionReference);
        bundle.set("hydration_reference", hydrationReference);
        bundle.put("acquisition_sha256", H).put("hydration_sha256", H)
                .put("dataset_root_sha256", H).put("root_reference", "fixture")
                .put("storage_role", "SOURCE_BUNDLE").put("authoritative", false);
        bundle.set("limitations", JSON.createArrayNode());
        bundle.put("content_sha256", StrategyResearchDataV5.ownHash(bundle));
        byte[] bundleBytes = JSON.writeValueAsBytes(bundle);
        Files.write(outside, bundleBytes);
        Files.createSymbolicLink(root.resolve("bundle.json"), outside);
        ObjectNode reference = JSON.createObjectNode()
                .put("path", "bundle.json")
                .put("content_sha256", bundle.path("content_sha256").asText())
                .put("byte_sha256", StrategyResearchDataV5.hash(bundleBytes))
                .put("bytes", bundleBytes.length);
        ObjectNode options = JSON.createObjectNode().put("root", root.toString());
        options.set("reference", reference);

        assertThatThrownBy(() -> StrategyResearchDataV5.verifySourceBundleManifest(options))
                .isInstanceOf(CustodyException.class)
                .hasMessageContaining("source bundle manifest contains a symlink");
    }

    private static ObjectNode registry(ObjectNode... predictors) {
        ObjectNode options = JSON.createObjectNode();
        ArrayNode rows = options.putArray("predictors");
        for (ObjectNode predictor : predictors) rows.add(predictor);
        return options;
    }

    private static ObjectNode predictor(String id, String sourceField, String sourceFamily,
            String timeframe, ObjectNode recipe) {
        return JSON.createObjectNode().put("id", id).put("scalar_type", "number")
                .put("source_field", sourceField).put("source_family", sourceFamily)
                .put("source_timeframe", timeframe).put("lookback_ms", 0)
                .put("availability_derivation", "completed_observation_available")
                .put("pit_role", "PREDICTOR").put("code_sha256", H).put("config_sha256", H)
                .set("recipe", recipe);
    }

    private static ObjectNode fieldRecipe() {
        return recipe("field", "close", "bars", "SAME_ASSET_VENUE_INSTRUMENT_SYMBOL")
                .put("module_code_sha256", H).put("module_config_sha256", H);
    }

    private static ObjectNode returnRecipe() {
        ObjectNode reference = JSON.createObjectNode().put("asset", "SPY").put("venue", "nyse")
                .put("instrument", "equity").put("symbol", "spy");
        ObjectNode value = recipe("return", "close", "bars", "EXPLICIT_REFERENCE_SERIES");
        value.put("lookback_bars", 2).put("min_history", 2);
        value.set("reference_series", reference);
        return value.put("asof_policy", "LATEST_AVAILABLE_NOT_AFTER_DECISION")
                .put("max_staleness_ms", 60_000).put("lag_bars", 1)
                .put("resample_policy", "BAR_CLOSE").put("context_only", true)
                .put("module_code_sha256", H).put("module_config_sha256", H);
    }

    private static ObjectNode fundingRsiRecipe() {
        ObjectNode value = recipe("rsi", "funding_rate", "funding_events", "SAME_ASSET_FUNDING_SERIES");
        value.put("lookback_bars", 14).put("min_history", 15);
        value.putArray("required_series_types").add("funding_events");
        return value.put("asof_policy", "LATEST_AVAILABLE_STRICTLY_BEFORE_DECISION")
                .put("max_staleness_ms", 28_800_000).put("lag_bars", 0)
                .put("resample_policy", "LAST_AVAILABLE").put("context_only", true)
                .put("rsi_method", "wilder_rsi")
                .put("module_code_sha256", H).put("module_config_sha256", H);
    }

    private static ObjectNode recipe(String kind, String sourceField, String sourceSeries, String scope) {
        return JSON.createObjectNode().put("module", "builtin-pit-transform/1").put("kind", kind)
                .put("source_field", sourceField).put("source_series", sourceSeries)
                .put("window_policy", "COMPLETED_OBSERVATIONS_ONLY")
                .put("availability_policy", "MAX_INPUT_AVAILABILITY")
                .put("series_scope", scope);
    }

    private static ObjectNode predictorFrom(ObjectNode registry, String id) {
        for (var predictor : registry.withArray("predictors")) {
            if (id.equals(predictor.path("id").asText())) return (ObjectNode) predictor;
        }
        throw new AssertionError("missing predictor " + id);
    }

    private static ObjectNode metadataOptions() {
        return JSON.createObjectNode().put("startAt", START).put("endAt", END)
                .set("receipts", JSON.createObjectNode());
    }

    private static ObjectNode metadataReceipt(String kind, String status) {
        ObjectNode value = JSON.createObjectNode().put("schema", StrategyResearchDataV5.DATA_V5.get("metadata"))
                .put("version", 1).put("kind", kind).put("status", status);
        value.set("records", JSON.createArrayNode());
        value.set("limitations", JSON.createArrayNode());
        return value;
    }

    private static ObjectNode metadataRecord(String asset, String instrument, long from, long to) {
        return JSON.createObjectNode().put("asset", asset).put("instrument", instrument)
                .put("effective_from", from).put("effective_to", to);
    }

}

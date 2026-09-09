package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.marketdata.PublicDataAdapters;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public API coverage for physical settlement metadata and lifecycle outcomes. */
final class StrategyResearchDataV5OutcomeLifecycleMatrixTest {
    private static final String H = "a".repeat(64);
    private static final long START = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private static final long MINUTE = 60_000L;

    @TempDir
    Path temporary;

    @Test
    void settlementMetadataBindsPhysicalInventoryAndRejectsEachBoundary() {
        ObjectNode valid = settlementOptions(temporary);
        ObjectNode receipt = StrategyResearchDataV5.makeMetadataReceipt(valid);
        assertThat(receipt.path("schema").asText()).isEqualTo("strategy-v5-metadata-receipt/1");
        assertThat(receipt.path("kind").asText()).isEqualTo("SETTLEMENT");
        assertThat(receipt.path("authoritative").asBoolean()).isTrue();
        assertThat(receipt.path("records").get(0).path("settlement_price").asDouble()).isEqualTo(101.25);
        assertThat(receipt.path("records").get(0).has("delivery_price")).isFalse();
        assertThat(receipt.path("content_sha256").asText()).isEqualTo(StrategyResearchDataV5.ownHash(receipt));

        ObjectNode badIdentity = settlementOptions(temporary);
        ((ObjectNode) badIdentity.path("records").get(0)).put("instrument", "BINANCE_USDM_PERPETUAL");
        assertThatThrownBy(() -> StrategyResearchDataV5.makeMetadataReceipt(badIdentity))
                .hasMessageContaining("SETTLEMENT record lacks exact dated-futures identity");
        ObjectNode badPrice = settlementOptions(temporary);
        ((ObjectNode) badPrice.path("records").get(0)).put("settlement_price", 0);
        assertThatThrownBy(() -> StrategyResearchDataV5.makeMetadataReceipt(badPrice))
                .hasMessageContaining("settlement_price is invalid");
        ObjectNode badChronology = settlementOptions(temporary);
        ((ObjectNode) badChronology.path("records").get(0)).put("settlement_time", iso(START));
        assertThatThrownBy(() -> StrategyResearchDataV5.makeMetadataReceipt(badChronology))
                .hasMessageContaining("event/expiry/availability chronology is invalid");
        ObjectNode missingEventId = settlementOptions(temporary);
        ((ObjectNode) missingEventId.path("records").get(0)).remove("settlement_mark_event_id");
        assertThatThrownBy(() -> StrategyResearchDataV5.makeMetadataReceipt(missingEventId))
                .hasMessageContaining("settlement_mark_event_id is missing");
        ObjectNode unboundSource = settlementOptions(temporary);
        ((ObjectNode) unboundSource.path("records").get(0)).put("settlement_mark_source_sha256", "b".repeat(64));
        assertThatThrownBy(() -> StrategyResearchDataV5.makeMetadataReceipt(unboundSource))
                .hasMessageContaining("mark source is not in the bound physical source-byte inventory");
        ObjectNode mismatchedReceipt = settlementOptions(temporary);
        ((ObjectNode) mismatchedReceipt.path("records").get(0)).put("source_receipt_sha256", "b".repeat(64));
        assertThatThrownBy(() -> StrategyResearchDataV5.makeMetadataReceipt(mismatchedReceipt))
                .hasMessageContaining("source receipt identity differs from the bound receipt");

        ObjectNode unavailable = object().put("kind", "SETTLEMENT").put("status", "UNAVAILABLE")
                .put("capturedAt", iso(START));
        unavailable.set("records", array()); unavailable.set("limitations", array().add("NO_DATED_HISTORY"));
        ObjectNode unavailableReceipt = StrategyResearchDataV5.makeMetadataReceipt(unavailable);
        assertThat(unavailableReceipt.path("provenance_mode").asText()).isEqualTo("UNAVAILABLE");
        assertThat(unavailableReceipt.path("authoritative").asBoolean()).isFalse();
    }

    @Test
    void legacyOutcomeMatrixBindsTimeStopTargetStopAndDelayedEntry() {
        ObjectNode timeStop = legacySpot(START, false);
        ObjectNode timeResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(timeStop);
        assertThat(timeResult.path("traded").asBoolean()).isTrue();
        assertThat(timeResult.path("entry_time").asText()).isEqualTo(iso(START));
        assertThat(timeResult.path("exit_time").asText()).isEqualTo(iso(START + 2 * MINUTE));
        assertThat(timeResult.path("entry_price").asDouble()).isCloseTo(100.03,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(timeResult.path("exit_price").asDouble()).isCloseTo(101.9694,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(timeResult.path("quantity").asDouble()).isCloseTo(2,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(timeResult.path("gross_pnl_usd").asDouble()).isCloseTo(4,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(timeResult.path("fees_usd").asDouble()).isCloseTo(.4039988,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(timeResult.path("net_pnl_usd").asDouble()).isCloseTo(3.4748012,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(timeResult.path("net_r").asDouble()).isCloseTo(.034748012,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(timeResult.path("exit_reason").asText()).isEqualTo("TIME_STOP");
        assertThat(timeResult.path("risk_denominator").asText()).isEqualTo("FROZEN_FIXED_RISK_BUDGET");

        ObjectNode targetStop = legacySpot(START, true);
        ((ObjectNode) targetStop.path("execution").path("child_bars").get(1))
                .put("open", 100).put("high", 106).put("low", 94).put("close", 100);
        ObjectNode targetResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(targetStop);
        assertThat(targetResult.path("entry_price").asDouble()).isCloseTo(100.03,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(targetResult.path("exit_price").asDouble()).isCloseTo(94.9715,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(targetResult.path("quantity").asDouble()).isCloseTo(19.88,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(targetResult.path("gross_pnl_usd").asDouble()).isCloseTo(-99.4,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(targetResult.path("net_pnl_usd").asDouble()).isCloseTo(-104.43960982,
                org.assertj.core.data.Offset.offset(1e-10));
        assertThat(targetResult.path("net_r").asDouble()).isCloseTo(-1.044433697813121,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(targetResult.path("exit_reason").asText()).isEqualTo("STOP");
        assertThat(targetResult.path("risk_denominator").asText()).isEqualTo("DERIVED_STOP_DISTANCE");

        ObjectNode delayed = legacySpot(START, false);
        ObjectNode candidate = (ObjectNode) delayed.path("candidate");
        candidate.put("entry_policy", "DELAYED_BAR_OPEN").put("entry_delay_bars", 1);
        ((ObjectNode) delayed.path("label")).put("entry_time", iso(START + MINUTE));
        ObjectNode delayedResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(delayed);
        assertThat(delayedResult.path("entry_time").asText()).isEqualTo(iso(START + MINUTE));
        assertThat(delayedResult.path("entry_price").asDouble()).isCloseTo(101.0303,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(delayedResult.path("exit_price").asDouble()).isCloseTo(101.9694,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(delayedResult.path("gross_pnl_usd").asDouble()).isCloseTo(2,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(delayedResult.path("net_pnl_usd").asDouble()).isCloseTo(1.4722006,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(delayedResult.path("net_r").asDouble()).isCloseTo(.014722006,
                org.assertj.core.data.Offset.offset(1e-12));

        ObjectNode malformed = legacySpot(START, false);
        ((ObjectNode) malformed.path("execution").path("child_bars").get(1)).put("event_time", START + 2 * MINUTE);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(malformed))
                .hasMessageContaining("not dense one-minute data");
        ObjectNode callerFunding = legacySpot(START, false);
        ((ObjectNode) callerFunding.path("execution")).put("funding_amount", 1);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(callerFunding))
                .hasMessageContaining("caller-supplied funding_amount");
        ObjectNode unsupportedPolicy = legacySpot(START, false);
        ((ObjectNode) unsupportedPolicy.path("candidate")).put("entry_policy", "MARKET");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(unsupportedPolicy))
                .hasMessageContaining("unsupported frozen entry policy");
    }

    @Test
    void legacyOutcomeMatrixRejectsResolutionAndSizingClaims() {
        ObjectNode invalidCeiling = legacySpot(START, false);
        ((ObjectNode) invalidCeiling.path("label")).put("resolution_ceiling_time", iso(START));
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(invalidCeiling))
                .hasMessageContaining("label outcome ceiling is invalid");
        ObjectNode invalidLifecycle = legacySpot(START, false);
        ((ObjectNode) invalidLifecycle.path("candidate")).put("max_lifecycle_ms", 0);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(invalidLifecycle))
                .hasMessageContaining("maximum lifecycle must be explicitly bound");
        ObjectNode partialExit = legacySpot(START, false);
        ((ObjectNode) partialExit.path("candidate")).putObject("ratchet").put("enabled", true);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(partialExit))
                .hasMessageContaining("partial and ratchet exits require");
        ObjectNode negativeOpen = legacySpot(START, false);
        ((ObjectNode) negativeOpen.path("execution").path("child_bars").get(0)).put("open", 0);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(negativeOpen))
                .hasMessageContaining("exact contiguous next-bar entry");
    }

    @Test
    void normalizedLifecycleMatrixUsesPublicWrapperAndKeepsContractsBound() {
        ObjectNode request = normalizedLifecycle(START);
        ObjectNode result = StrategyResearchDataV5.deriveBoundExecutionOutcome(request);
        assertThat(result.path("provenance").asText()).isEqualTo("DERIVED_FROM_CANONICAL_NORMALIZED_LIFECYCLE");
        assertThat(result.path("lifecycle_result").isObject()).isTrue();
        assertThat(result.path("entry_time").asText()).isEqualTo(iso(START));
        assertThat(result.path("exit_time").asText()).isEqualTo(iso(START + MINUTE));
        assertThat(result.path("entry_price").asDouble()).isCloseTo(100,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.path("exit_price").asDouble()).isCloseTo(101,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.path("quantity").asDouble()).isCloseTo(20,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.path("gross_pnl_usd").asDouble()).isCloseTo(20,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.path("net_pnl_usd").asDouble()).isCloseTo(20,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.path("risk_amount_usd").asDouble()).isCloseTo(100,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(result.path("net_r").asDouble()).isCloseTo(.2,
                org.assertj.core.data.Offset.offset(1e-12));

        ObjectNode conflict = request.deepCopy();
        ((ObjectNode) conflict.path("candidate").path("risk_contract")).put("budget_usd", 101);
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(conflict))
                .hasMessageContaining("candidate/execution risk_contract values conflict");
        ObjectNode missingLifecycle = request.deepCopy();
        ((ObjectNode) missingLifecycle.path("candidate")).remove("lifecycle");
        assertThatThrownBy(() -> StrategyResearchDataV5.deriveBoundExecutionOutcome(missingLifecycle))
                .hasMessageContaining("lifecycle timeframe is required");
    }

    @Test
    void conversionPublicWrapperPromotesTinyFixtureAndRejectsTamperedStaging(@TempDir Path root) throws Exception {
        ObjectNode plan = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(
                object().put("asOf", "2026-08-25T00:00:00.000Z").put("rootReference", "conversion-matrix"));
        PublicDataAdapters.InjectableHttpClient empty = (uri, headers) -> new PublicDataAdapters.FetchResponse(
                200, "[]".getBytes(StandardCharsets.UTF_8), java.util.Map.of());
        ObjectNode acquisitionOptions = object().put("outputRoot", root.resolve("staging").toString())
                .put("fixtureOnly", true).put("capturedAt", "2026-08-25T00:00:00.000Z");
        acquisitionOptions.set("plan", plan);
        ObjectNode acquisition = StrategyResearchDataV5.acquireAuthoritativeStaging(acquisitionOptions, empty);
        assertThat(acquisition.path("status").asText()).isEqualTo("STAGING_PARTIAL");
        ObjectNode convertOptions = object();
        convertOptions.set("stagingManifest", acquisition);
        convertOptions.put("stagingRoot", root.resolve("staging").toString())
                .put("outputRoot", root.resolve("parquet").toString()).put("fixtureOnly", true);
        ObjectNode converted = StrategyResearchDataV5.convertToParquet(convertOptions);
        assertThat(converted.path("schema").asText()).isEqualTo("strategy-v5-parquet-conversion/1");
        assertThat(converted.path("status").asText()).isEqualTo("AUTHORITATIVE_PARQUET");
        assertThat(converted.path("threads").asInt()).isEqualTo(1);
        ObjectNode tampered = acquisition.deepCopy();
        tampered.put("content_sha256", StrategyResearchDataV5.ownHash(tampered));
        tampered.put("status", "STAGING_COMPLETE");
        ObjectNode tamperedOptions = convertOptions.deepCopy();
        tamperedOptions.set("stagingManifest", tampered);
        assertThatThrownBy(() -> StrategyResearchDataV5.convertToParquet(tamperedOptions))
                .hasMessageContaining("staging manifest");
    }

    private static ObjectNode settlementOptions(Path root) {
        long expiry = START + 86_400_000L;
        try {
            Files.createDirectories(root.resolve("raw"));
            byte[] rawBytes = "settlement-source".getBytes(StandardCharsets.UTF_8);
            String rawSha = StrategyResearchDataV5.hash(rawBytes);
            Files.write(root.resolve("raw/source.bin"), rawBytes);
            ObjectNode raw = object().put("schema", "strategy-v5-source-receipt/1").put("version", 1)
                    .put("path", "raw/source.bin").put("source", "fixture").put("byte_sha256", rawSha)
                    .put("bytes", rawBytes.length).put("format", "RAW_BYTES").put("storage_role", "RAW_IGNORED")
                    .put("authoritative", false);
            raw.set("request", object().put("endpoint", "fixture://settlement"));
            raw = StrategyResearchDataV5.withHash(raw);
            ObjectNode normalized = object().put("schema", "strategy-v5-source-receipt/1").put("version", 1)
                    .put("status", "PUBLIC_OBSERVED").put("captured_at", iso(expiry + 60_000));
            normalized.set("request", object().put("endpoint", "fixture://settlement"));
            normalized.set("response_sha256", array().add(rawSha));
            normalized.set("source_byte_sha256", array().add(rawSha));
            normalized.set("raw_receipts", array().add(raw));
            normalized = StrategyResearchDataV5.withHash(normalized);
            Files.createDirectories(root.resolve("receipts"));
            Files.write(root.resolve("receipts/source.json"), JsonHashes.mapper().writeValueAsBytes(normalized));
            String receiptSha = normalized.path("content_sha256").asText();
        ObjectNode record = object().put("asset", "btc").put("venue", "BINANCE")
                .put("symbol", "BTCUSD_20260101").put("instrument", "BINANCE_USDM_DATED_FUTURE")
                .put("effective_from", iso(START)).put("effective_to", iso(expiry + 1_000))
                .put("availability_time", iso(expiry)).put("expiry", iso(expiry))
                .put("event_time", iso(expiry)).put("settlement_time", iso(expiry))
                .put("settlement_price", 101.25).put("settlement_mark_event_id", "mark-1")
                .put("settlement_mark_source_sha256", rawSha).put("source_receipt_sha256", receiptSha);
        ObjectNode value = object().put("kind", "SETTLEMENT").put("status", "PUBLIC_OBSERVED")
                .put("capturedAt", iso(expiry + 60_000)).put("sourceRoot", root.toString())
                .put("sourceReceiptPath", "receipts/source.json").put("sourceRootReference", "settlement-source")
                .put("sourceReceiptSha256", receiptSha);
        value.set("sourceByteSha256", array().add(rawSha));
        ObjectNode source = object().put("content_sha256", receiptSha);
        source.set("byte_sha256", array().add(rawSha));
        value.set("source", source);
        value.set("records", array().add(record));
        value.set("limitations", array());
        return value;
        } catch (java.io.IOException error) {
            throw new IllegalStateException("settlement source fixture", error);
        }
    }

    private static ObjectNode legacySpot(long decision, boolean targetStop) {
        ObjectNode feature = identity("sig", "episode", decision).put("signal_eligible", true);
        ObjectNode label = identity("sig", "episode", decision)
                .put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY").put("decision_timeframe", "4h")
                .put("lifecycle_timeframe", "1m").put("resolution_ceiling_time", iso(decision + 2 * MINUTE));
        ObjectNode execution = identity("sig", "episode", decision)
                .put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY").put("decision_timeframe", "4h")
                .put("lifecycle_timeframe", "1m").put("max_lifecycle_ms", 2 * MINUTE).put("direction", "long");
        ArrayNode bars = execution.putArray("child_bars");
        bars.add(bar(decision, 100)).add(bar(decision + MINUTE, 101)).add(bar(decision + 2 * MINUTE, 102));
        ObjectNode candidate = object().put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY")
                .put("decision_timeframe", "4h").put("lifecycle_timeframe", "1m").put("max_lifecycle_ms", 2 * MINUTE)
                .put("direction", "long").put("entry_policy", "NEXT_BAR_OPEN");
        candidate.set("risk_contract", object().put("mode", "FIXED_RISK_BUDGET_USD").put("budget_usd", 100)
                .put("precommit_sha256", H).put("evaluator_spec_sha256", H));
        execution.set("risk_contract", object().put("mode", "FIXED_RISK_BUDGET_USD").put("budget_usd", 100)
                .put("precommit_sha256", H).put("evaluator_spec_sha256", H));
        if (targetStop) candidate.set("exit_policy", object().put("type", "TARGET_STOP")
                .put("collision_policy", "ADVERSE_STOP_FIRST").put("stop_price", 95).put("target_price", 105));
        else { candidate.set("exit_policy", object().put("type", "TIME_STOP")); execution.put("quantity", 2); }
        ObjectNode metadata = object();
        metadata.set("contract_spec", metadata("CONTRACT_SPEC", object().put("contract_multiplier", 1)
                .put("step_size", .01).put("min_qty", .01).put("max_qty", 1_000_000)
                .put("min_notional", 1).put("max_notional", 10_000_000)));
        metadata.set("fee_schedule", metadata("FEE_SCHEDULE", object().put("taker_fee_rate", .001)));
        metadata.set("execution_model", metadata("EXECUTION_MODEL", object().put("slippage_bps", 2)
                .put("impact_bps", 1).put("outage_policy", "FAIL").put("gap_policy", "FILL_AT_OPEN")));
        ObjectNode request = object();
        request.set("feature", feature); request.set("label", label); request.set("execution", execution);
        request.set("candidate", candidate); request.set("metadata", metadata); request.put("fixtureOnly", true);
        return request;
    }

    private static ObjectNode normalizedLifecycle(long decision) {
        ObjectNode feature = identity("normalized", "normalized-episode", decision).put("signal_eligible", true);
        ObjectNode label = identity("normalized", "normalized-episode", decision)
                .put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY").put("decision_timeframe", "4h")
                .put("resolution_ceiling_time", iso(decision + 2 * MINUTE));
        ObjectNode execution = identity("normalized", "normalized-episode", decision)
                .put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY").put("decision_timeframe", "4h")
                .put("interval_ms", MINUTE).put("direction", "long");
        ArrayNode bars = execution.putArray("child_bars");
        bars.add(bar(decision, 100)).add(bar(decision + MINUTE, 101)).add(bar(decision + 2 * MINUTE, 102));
        ObjectNode candidate = object().put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY")
                .put("decision_timeframe", "4h").put("direction", "long").put("instrument_type", "spot");
        ObjectNode lifecycle = candidate.putObject("lifecycle").put("max_lifecycle_ms", 2 * MINUTE).put("gap_policy", "OPEN");
        lifecycle.putObject("stop").put("type", "PERCENT").put("value", .05);
        lifecycle.putObject("sizing").put("mode", "RISK_USD").put("risk_usd", 100);
        candidate.set("risk_contract", object().put("mode", "FIXED_RISK_BUDGET_USD").put("budget_usd", 100)
                .put("precommit_sha256", H).put("evaluator_spec_sha256", H));
        execution.set("risk_contract", object().put("mode", "FIXED_RISK_BUDGET_USD").put("budget_usd", 100)
                .put("precommit_sha256", H).put("evaluator_spec_sha256", H));
        ObjectNode request = object();
        request.set("feature", feature); request.set("label", label); request.set("execution", execution);
        request.set("candidate", candidate); request.put("fixtureOnly", true);
        return request;
    }

    private static ObjectNode metadata(String kind, ObjectNode fields) {
        long end = START + 5 * MINUTE;
        ObjectNode record = object().put("asset", "btc").put("venue", "BINANCE")
                .put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT")
                .put("effective_from", iso(START - MINUTE)).put("effective_to", iso(end))
                .put("availability_time", iso(START));
        record.setAll(fields);
        ObjectNode options = object().put("kind", kind).put("status", "CONSERVATIVE_MODEL")
                .put("capturedAt", iso(START)).put("modelSha256", H).put("precommitSha256", H);
        options.set("records", array().add(record)); options.set("limitations", array());
        return StrategyResearchDataV5.makeMetadataReceipt(options);
    }

    private static ObjectNode identity(String signal, String episode, long decision) {
        return object().put("asset", "btc").put("venue", "BINANCE").put("instrument", "BINANCE_SPOT")
                .put("symbol", "BTCUSDT").put("decision_time", iso(decision))
                .put("signal_id", signal).put("episode_id", episode);
    }

    private static ObjectNode bar(long event, double open) {
        return object().put("event_time", event).put("availability_time", event + MINUTE - 1)
                .put("open", open).put("high", open + 1).put("low", open - 1).put("close", open)
                .put("is_closed", true);
    }

    private static String iso(long value) { return Instant.ofEpochMilli(value).toString().replace("Z", ".000Z"); }
    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }
    private static ArrayNode array() { return JsonHashes.mapper().createArrayNode(); }
}

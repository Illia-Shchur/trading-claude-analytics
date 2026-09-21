package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.function.Executable;

class LiquidationV2PhysicalDevelopmentTest {
    @TempDir Path temporary;

    @Test
    void proxyFixtureReopensSixRolesUsesPushedDownRangesAndCannotClaimHistoricalQualification() throws Exception {
        Fixture fixture = proxyFixture(temporary);
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options());
        assertEquals("PROXY_DISCLOSED_DEVELOPMENT_ONLY", manifest.path("source_mode").asText());
        assertTrue(manifest.path("all_source_transformations_recomputed").asBoolean());
        assertFalse(manifest.path("coverage_inventory_complete").asBoolean());
        assertFalse(manifest.path("development_replay_permitted").asBoolean());

        ObjectNode options = fixture.options();
        options.set("manifest", manifest);
        ObjectNode verification = LiquidationV2PhysicalDataV1.verifyDevelopment(options);
        assertEquals(manifest.path("dataset_root_sha256").asText(), verification.path("dataset_root_sha256").asText());
        LiquidationV2PhysicalDataV1.VerifiedDevelopmentSession session = LiquidationV2PhysicalDataV1.openVerifiedDevelopment(options);
        assertEquals(2, LiquidationV2PhysicalDataV1.readRoleRows(session, "feature",
                "2022-08-11T00:00:00Z", "2022-08-12T00:00:00Z", List.of("BTC")).size());
        assertEquals(0, LiquidationV2PhysicalDataV1.readRoleRows(session, "feature",
                "2022-08-12T00:00:00Z", "2022-08-13T00:00:00Z", List.of("BTC")).size());
        assertEquals(0, LiquidationV2PhysicalDataV1.readRoleRows(session, "feature",
                "2022-08-11T00:00:00Z", "2022-08-12T00:00:00Z", List.of("ETH")).size());
        assertEquals(1, LiquidationV2PhysicalDataV1.readRoleRows(session, "label",
                "2022-11-11T00:00:00Z", "2022-11-12T00:00:00Z", List.of("BTC")).size());
        assertEquals(1, LiquidationV2PhysicalDataV1.readRoleRows(session, "metadata",
                "2022-11-11T00:00:00Z", "2022-11-12T00:00:00Z", List.of("BTC")).size());
        assertEquals(1, LiquidationV2PhysicalDataV1.readRoleRows(session, "funding",
                "2022-11-11T00:00:00Z", "2022-11-12T00:00:00Z", List.of("BTC")).size());

        ObjectNode qualification = LiquidationInputQualificationV1.assessPhysicalDevelopment(options);
        assertFalse(qualification.path("development_replay_permitted").asBoolean());
        assertFalse(qualification.path("historical_availability_proven").asBoolean());
        assertFalse(qualification.path("historical_revision_proven").asBoolean());
        assertFalse(qualification.path("authoritative_wfo_permitted").asBoolean());
        assertFalse(qualification.path("sealed_evidence_permitted").asBoolean());
        assertFalse(qualification.path("prospective_live_permitted").asBoolean());
        assertEquals("REOPENED_DEVELOPMENT_ONLY",
                LiquidationInputQualificationV1.verifyPhysicalDevelopmentAssessment(options, qualification).path("status").asText());
    }

    @Test
    void physicalReceiptCannotBeRehashedToSwapAssetSymbolOrCollateralIdentity() throws Exception {
        ObjectNode badSymbol = priceRow("BTC", "ETHUSDT", Instant.parse("2022-08-11T00:00:00Z").toEpochMilli());
        IllegalArgumentException symbolError = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.validateRows("feature", List.of(badSymbol)));
        assertTrue(symbolError.getMessage().contains("symbol does not match"));

        Fixture fixture = proxyFixture(temporary);
        ObjectNode featurePartition = (ObjectNode) fixture.inputs().path("feature").path("partitions").get(0);
        Path receiptPath = temporary.resolve(featurePartition.path("normalization_receipt_path").asText());
        ObjectNode receipt = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(receiptPath));
        ((ObjectNode) receipt.path("physical_identity")).put("collateral", "BUSD");
        receipt.put("physical_identity_sha256", JsonHashes.canonicalSha256(receipt.path("physical_identity")));
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        byte[] changed = JsonHashes.canonicalBytes(receipt);
        Files.write(receiptPath, changed);
        featurePartition.put("normalization_receipt_sha256", JsonHashes.sha256(changed));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options()));
    }

    @Test
    void rawSourceRehashCannotSubstituteDifferentValuesForAReopenedNormalizedPartition() throws Exception {
        Fixture fixture = proxyFixture(temporary);
        ObjectNode featurePartition = (ObjectNode) fixture.inputs().path("feature").path("partitions").get(0);
        Path sourcePath = temporary.resolve(featurePartition.path("source_path").asText());
        long validDailyEvent = Instant.parse("2022-08-11T00:00:00Z").toEpochMilli();
        ObjectNode alternate = dailyRow("BTC", "BTCUSDT_PERP.A", "LONG", validDailyEvent, 2.0);
        byte[] replacementSource = LiquidationV2PhysicalDataV1.canonicalRoleRowsJsonl("feature", List.of(alternate));
        Files.write(sourcePath, replacementSource);
        String sourceSha = JsonHashes.sha256(replacementSource);
        featurePartition.put("source_sha256", sourceSha);

        Path receiptPath = temporary.resolve(featurePartition.path("normalization_receipt_path").asText());
        ObjectNode receipt = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(receiptPath));
        receipt.put("source_byte_sha256", sourceSha);
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        byte[] updatedReceipt = JsonHashes.canonicalBytes(receipt);
        Files.write(receiptPath, updatedReceipt);
        featurePartition.put("normalization_receipt_sha256", JsonHashes.sha256(updatedReceipt));

        assertThrows(IllegalArgumentException.class, () -> LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options()));
    }

    @Test
    void macroAvailabilityBasisIsRecomputedAndBoundToSp500SeriesReceipt() throws Exception {
        Fixture fixture = proxyFixture(temporary);
        long close = Instant.parse("2024-01-02T21:00:00Z").toEpochMilli();
        long nextClose = Instant.parse("2024-01-03T21:00:00Z").toEpochMilli();
        ObjectNode macro = JsonHashes.mapper().createObjectNode().put("asset", "SP500").put("symbol", "SP500")
                .put("series_id", "sp500_close").put("timeframe", "1d").put("event_time", close)
                .put("availability_time", nextClose).put("close", 4_700);
        addPartition(Path.of(fixture.options().path("root").asText()), fixture.inputs(), "feature", "sp500", "PUBLIC_ARCHIVE",
                macro, close, close + 86_400_000L, null);

        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options());
        ObjectNode featurePartitions = (ObjectNode) fixture.inputs().path("feature");
        ObjectNode macroPartition = (ObjectNode) featurePartitions.path("partitions").get(featurePartitions.path("partitions").size() - 1);
        JsonNode manifestMacroPartition = manifest.path("artifacts").path("feature").path("partitions")
                .get(manifest.path("artifacts").path("feature").path("partitions").size() - 1);
        assertTrue(manifestMacroPartition.path("series_scope").toString().contains("sp500_close"));
        assertEquals("MODELED_NEXT_COMPLETED_NYSE_SESSION", manifestMacroPartition.path("availability_basis").asText());
        ObjectNode verifiedOptions = fixture.options();
        verifiedOptions.set("manifest", manifest);
        LiquidationV2PhysicalDataV1.VerifiedDevelopmentSession session =
                LiquidationV2PhysicalDataV1.openVerifiedDevelopment(verifiedOptions);
        assertTrue(LiquidationV2PhysicalDataV1.macroAvailabilityReceiptBound(session, close));
        assertFalse(LiquidationV2PhysicalDataV1.macroAvailabilityReceiptBound(session, close + 86_400_000L));
        Path receiptPath = Path.of(fixture.options().path("root").asText()).resolve(macroPartition.path("normalization_receipt_path").asText());
        ObjectNode receipt = (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(receiptPath));
        assertTrue(receipt.path("series_scope").toString().contains("sp500_close"));
        assertEquals("MODELED_NEXT_COMPLETED_NYSE_SESSION", receipt.path("availability_basis").asText());
        receipt.put("availability_basis", "NOT_APPLICABLE");
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        byte[] changed = JsonHashes.canonicalBytes(receipt);
        Files.write(receiptPath, changed);
        macroPartition.put("normalization_receipt_sha256", JsonHashes.sha256(changed));
        assertFailureContains("availability basis does not match", () -> LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options()));
    }

    @Test
    void pinnedAaveDailyGapInventorySuppressesExactLookbackWindowsAndRejectsExtraGaps() {
        ObjectNode profile = LiquidationDailyStressProfileV1.frozenContract();
        ObjectNode plan = LiquidationV2PhysicalDataV1.frozenPlan(profile);
        ObjectNode exclusion = (ObjectNode) plan.path("daily_feature_gap_exclusions").get(0);
        assertEquals(7, exclusion.path("missing_event_days_utc").size());
        assertEquals(234, exclusion.path("suppressed_decision_days").asInt());
        assertEquals("2022-11-11", exclusion.path("suppressed_decision_ranges_utc").get(0).path("from_inclusive").asText());
        assertEquals("2023-04-03", exclusion.path("suppressed_decision_ranges_utc").get(0).path("to_exclusive").asText());
        assertEquals("2023-05-15", exclusion.path("suppressed_decision_ranges_utc").get(1).path("from_inclusive").asText());
        assertEquals("2023-08-14", exclusion.path("suppressed_decision_ranges_utc").get(1).path("to_exclusive").asText());

        long requiredStart = Instant.parse(plan.path("source_start").asText()).toEpochMilli();
        long requiredEnd = Instant.parse(plan.path("decision_end_exclusive").asText()).minusSeconds(2 * 86_400L).toEpochMilli();
        ObjectNode group = JsonHashes.mapper().createObjectNode().put("key", "feature/AAVE/daily_liquidation_usd/1d/LONG");
        List<Long> missing = new ArrayList<>();
        exclusion.path("missing_event_days_utc").forEach(day -> missing.add(Instant.parse(day.asText() + "T00:00:00Z").toEpochMilli()));
        group.put("row_count", ((requiredEnd - requiredStart) / 86_400_000L) - missing.size());
        com.fasterxml.jackson.databind.node.ArrayNode segments = group.putArray("segments");
        long cursor = requiredStart;
        for (long missingStart : missing) {
            if (missingStart > cursor) segments.addArray().add(cursor).add(missingStart).add((missingStart - cursor) / 86_400_000L);
            cursor = missingStart + 86_400_000L;
        }
        if (cursor < requiredEnd) segments.addArray().add(cursor).add(requiredEnd).add((requiredEnd - cursor) / 86_400_000L);
        ObjectNode coverage = JsonHashes.mapper().createObjectNode(); coverage.putArray("groups").add(group);
        assertTrue(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(coverage,
                group.path("key").asText(), requiredStart, requiredEnd, plan));
        ArrayNode damagedFirstSegment = (ArrayNode) group.path("segments").get(0);
        damagedFirstSegment.set(1, JsonHashes.mapper().getNodeFactory().numberNode(damagedFirstSegment.path(1).asLong() - 86_400_000L));
        assertFalse(LiquidationV2PhysicalDataV1.coverageMatchesDeclaredDailyGaps(coverage,
                group.path("key").asText(), requiredStart, requiredEnd, plan));
    }

    @Test
    void fundingCoveragePreservesSevenMillisecondOffsetsAndRejectsScheduleDriftBeyondTolerance() {
        long start = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
        List<ObjectNode> rows = List.of(fundingRow(start + 7, "f1"), fundingRow(start + 8 * 3_600_000L + 7, "f2"));
        ObjectNode partition = LiquidationV2PhysicalDataV1.partitionCoverage("funding", rows);
        ObjectNode aggregate = LiquidationV2PhysicalDataV1.aggregateCoverage(
                JsonHashes.mapper().createArrayNode().add(partition.path("groups").get(0)));
        JsonNode group = aggregate.path("groups").get(0);
        assertTrue(group.path("funding_schedule_valid").asBoolean());
        assertEquals(start + 7, group.path("funding_expected_slots").get(0).path("actual_settlement_time_ms").asLong());
        assertEquals(start + 8 * 3_600_000L + 7,
                group.path("funding_expected_slots").get(1).path("actual_settlement_time_ms").asLong());
        assertEquals(0, group.path("funding_expected_slots").get(1).path("offset_ms").asLong());

        // The observed source settles seven milliseconds after the expected slot;
        // this test drifts by twenty seconds, beyond the frozen fifteen-second allowance.
        List<ObjectNode> drifted = List.of(fundingRow(start + 7, "f1"),
                fundingRow(start + 8 * 3_600_000L + 20_007, "f2"));
        ObjectNode driftPartition = LiquidationV2PhysicalDataV1.partitionCoverage("funding", drifted);
        ObjectNode driftAggregate = LiquidationV2PhysicalDataV1.aggregateCoverage(
                JsonHashes.mapper().createArrayNode().add(driftPartition.path("groups").get(0)));
        assertFalse(driftAggregate.path("groups").get(0).path("funding_schedule_valid").asBoolean());
    }

    @Test
    void physicalFeatureChecksUseObservedOiTimeAndVerifiedNyseCloseClock() {
        long oiTime = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
        ObjectNode oi = JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT")
                .put("series_id", "open_interest_base").put("timeframe", "5m").put("event_time", oiTime)
                .put("availability_time", oiTime).put("value", 10);
        LiquidationV2PhysicalDataV1.validateRows("feature", List.of(oi));

        Instant earlyClose = Instant.parse("2024-07-03T17:00:00Z");
        Instant nextClose = Instant.parse("2024-07-05T20:00:00Z");
        LiquidationStructureRouterV1.validateMacroClose(earlyClose, nextClose);
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationStructureRouterV1.validateMacroClose(Instant.parse("2024-07-03T20:00:00Z"), nextClose));
        assertThrows(IllegalArgumentException.class,
                () -> LiquidationStructureRouterV1.validateMacroClose(earlyClose, Instant.parse("2024-07-05T19:59:00Z")));
    }

    @Test
    void featureRowsEnforceUnitsCadenceCompletionAndSideIdentity() {
        long start = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();

        ObjectNode misaligned = priceRow("BTC", "BTCUSDT", start + 60_000L);
        assertFailureContains("align to its declared UTC cadence", () -> LiquidationV2PhysicalDataV1.validateRows("feature", List.of(misaligned)));

        ObjectNode earlyPrice = priceRow("BTC", "BTCUSDT", start);
        earlyPrice.put("availability_time", start + 14_400_000L - 1);
        assertFailureContains("no earlier than completed-bar time", () -> LiquidationV2PhysicalDataV1.validateRows("feature", List.of(earlyPrice)));

        ObjectNode badBounds = priceRow("BTC", "BTCUSDT", start);
        badBounds.put("high", 98);
        assertFailureContains("OHLC bounds are inconsistent", () -> LiquidationV2PhysicalDataV1.validateRows("feature", List.of(badBounds)));

        ObjectNode earlyDaily = dailyRow("BTC", "BTCUSDT_PERP.A", "LONG", start, 1);
        earlyDaily.put("availability_time", start + 172_800_000L - 1);
        assertFailureContains("frozen t+48h assumption", () -> LiquidationV2PhysicalDataV1.validateRows("feature", List.of(earlyDaily)));

        ObjectNode badSide = dailyRow("BTC", "BTCUSDT_PERP.A", "ALL", start, 1);
        assertFailureContains("preserve asset and position side", () -> LiquidationV2PhysicalDataV1.validateRows("feature", List.of(badSide)));

        ObjectNode oi = oiRow("BTC", start, start, 10);
        LiquidationV2PhysicalDataV1.validateRows("feature", List.of(oi));
        oi.put("availability_time", start - 1);
        assertFailureContains("cannot be available before its event time", () -> LiquidationV2PhysicalDataV1.validateRows("feature", List.of(oi)));

        ObjectNode ambiguousOi = oiRow("BTC", start, start, 10).put("sum_open_interest_value", 10_000);
        assertFailureContains("undeclared field", () -> LiquidationV2PhysicalDataV1.validateRows("feature", List.of(ambiguousOi)));

        ObjectNode sparsePrice = priceRow("BTC", "BTCUSDT", start);
        sparsePrice.remove("base_volume");
        byte[] canonical = LiquidationV2PhysicalDataV1.canonicalRoleRowsJsonl("feature", List.of(sparsePrice));
        ObjectNode canonicalRow;
        try {
            canonicalRow = (ObjectNode) JsonHashes.mapper().readTree(canonical);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        assertTrue(canonicalRow.has("base_volume") && canonicalRow.path("base_volume").isNull());
        assertTrue(canonicalRow.has("side") && canonicalRow.path("side").isNull());
    }

    @Test
    void executionMarkAndFundingRowsRejectInvalidMinuteBarsAndAmbiguousSettlementSlots() {
        long start = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
        ObjectNode execution = executionRow("BTC", start + 1);
        assertFailureContains("aligned to UTC minute", () -> LiquidationV2PhysicalDataV1.validateRows("execution", List.of(execution)));

        ObjectNode brokenExecution = executionRow("BTC", start);
        brokenExecution.put("low", 102);
        assertFailureContains("execution OHLC bounds are inconsistent", () -> LiquidationV2PhysicalDataV1.validateRows("execution", List.of(brokenExecution)));

        ObjectNode mark = markRow("BTC", start + 1);
        assertFailureContains("aligned to UTC minute", () -> LiquidationV2PhysicalDataV1.validateRows("mark", List.of(mark)));
        ObjectNode brokenMark = markRow("BTC", start);
        brokenMark.put("high", 98);
        assertFailureContains("mark OHLC bounds are inconsistent", () -> LiquidationV2PhysicalDataV1.validateRows("mark", List.of(brokenMark)));

        ObjectNode first = fundingRow(start + 7, "event-1");
        ObjectNode duplicateSlot = fundingRow(start + 7, "event-2");
        assertFailureContains("duplicate observed settlement slots", () -> LiquidationV2PhysicalDataV1.validateRows("funding", List.of(first, duplicateSlot)));

        ObjectNode nonIntegralInterval = fundingRow(start + 7, "event-3");
        nonIntegralInterval.put("funding_interval_hours", 8.5);
        assertFailureContains("integer number of hours", () -> LiquidationV2PhysicalDataV1.validateRows("funding", List.of(nonIntegralInterval)));

        ObjectNode legacyMissingInterval = fundingRow(start + 7, "event-4");
        legacyMissingInterval.remove("funding_interval_hours");
        LiquidationV2PhysicalDataV1.validateRows("funding", List.of(legacyMissingInterval));
    }

    @Test
    void metadataIntervalsRequireOrderedTiersSharedCostsAndExhaustionCap() {
        long start = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();
        long end = start + 86_400_000L;
        ObjectNode tierOne = metadataTier(start, end, 1, 100_000, 0.005, false);
        ObjectNode tierTwo = metadataTier(start, end, 2, 200_000, 0.01, true);
        LiquidationV2PhysicalDataV1.validateRows("metadata", List.of(tierOne, tierTwo));

        ObjectNode skippedTier = metadataTier(start, end, 3, 300_000, 0.02, true);
        assertFailureContains("tier indexes must be contiguous", () -> LiquidationV2PhysicalDataV1.validateRows("metadata", List.of(tierOne, skippedTier)));

        ObjectNode changedFee = tierTwo.deepCopy().put("taker_fee_rate", 0.001);
        assertFailureContains("contract costs/filters differ", () -> LiquidationV2PhysicalDataV1.validateRows("metadata", List.of(tierOne, changedFee)));

        ObjectNode overlappingInterval = metadataTier(start + 1, end + 1, 1, 300_000, 0.02, true);
        assertFailureContains("effective intervals overlap", () -> LiquidationV2PhysicalDataV1.validateRows("metadata",
                List.of(tierOne, tierTwo, overlappingInterval)));

        ObjectNode nonterminalTop = metadataTier(start, end, 2, 200_000, 0.01, false);
        IllegalArgumentException missingTerminal = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.validateRows("metadata", List.of(tierOne, nonterminalTop)));
        assertEquals("exactly the last maintenance tier must declare terminal cap coverage", missingTerminal.getMessage());

        ObjectNode decreasingRate = tierTwo.deepCopy().put("maintenance_margin_rate", 0.004);
        assertFailureContains("nondecreasing rates", () -> LiquidationV2PhysicalDataV1.validateRows("metadata", List.of(tierOne, decreasingRate)));
    }

    @Test
    void verifiedSessionBoundsRangeAssetsAndRejectsPostOpenPartitionTampering() throws Exception {
        Fixture fixture = proxyFixture(temporary);
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options());
        ObjectNode options = fixture.options();
        options.set("manifest", manifest);
        LiquidationV2PhysicalDataV1.VerifiedDevelopmentSession session = LiquidationV2PhysicalDataV1.openVerifiedDevelopment(options);

        assertFailureContains("time range must be positive", () -> LiquidationV2PhysicalDataV1.readRoleRows(session,
                "feature", "2022-08-12T00:00:00Z", "2022-08-11T00:00:00Z", List.of("BTC")));
        assertFailureContains("outside the frozen identity map", () -> LiquidationV2PhysicalDataV1.readRoleRows(session,
                "feature", "2022-08-11T00:00:00Z", "2022-08-12T00:00:00Z", List.of("DOGE")));
        assertFailureContains("unsupported v002 physical role", () -> LiquidationV2PhysicalDataV1.readRoleRows(session,
                "outcome", "2022-08-11T00:00:00Z", "2022-08-12T00:00:00Z", List.of("BTC")));

        JsonNode firstFeaturePartition = manifest.path("artifacts").path("feature").path("partitions").get(0);
        Path parquet = temporary.resolve(firstFeaturePartition.path("path").asText());
        byte[] bytes = Files.readAllBytes(parquet);
        bytes[0] ^= 1;
        Files.write(parquet, bytes);
        assertFailureContains("selected v002 Parquet partition changed", () -> LiquidationV2PhysicalDataV1.readRoleRows(session,
                "feature", "2022-08-11T00:00:00Z", "2022-08-12T00:00:00Z", List.of("BTC")));
    }

    @Test
    void physicalQualificationAssessmentMustMatchReopenedManifestAndSourceBytes() throws Exception {
        Fixture fixture = proxyFixture(temporary);
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildDevelopment(fixture.options());
        ObjectNode options = fixture.options();
        options.set("manifest", manifest);
        ObjectNode assessment = LiquidationInputQualificationV1.assessPhysicalDevelopment(options);

        ObjectNode forgedAssessment = assessment.deepCopy();
        forgedAssessment.put("development_replay_permitted", true);
        forgedAssessment.put("content_sha256", JsonHashes.ownHash(forgedAssessment));
        assertFailureContains("does not match reopened source and Parquet bytes", () ->
                LiquidationInputQualificationV1.verifyPhysicalDevelopmentAssessment(options, forgedAssessment));

        JsonNode sourcePartition = manifest.path("artifacts").path("feature").path("partitions").get(0);
        Path source = temporary.resolve(sourcePartition.path("source_path").asText());
        Files.write(source, new byte[] {'{', '}', '\n'});
        assertThrows(IllegalArgumentException.class, () ->
                LiquidationInputQualificationV1.verifyPhysicalDevelopmentAssessment(options, assessment));
    }

    @Test
    void baseOpenInterestNormalizerIsRecomputedFromTheBaseQuantityField() throws Exception {
        Fixture valid = proxyFixture(temporary.resolve("base-oi-valid"));
        addBaseOiPartition(valid, 10, 10);
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildDevelopment(valid.options());
        ObjectNode featurePartitions = (ObjectNode) manifest.path("artifacts").path("feature");
        assertTrue(featurePartitions.path("partitions").findValuesAsText("transformation_status")
                .contains("RECOMPUTED_BINANCE_BASE_OI_MAPPING"));

        Fixture tampered = proxyFixture(temporary.resolve("base-oi-tampered"));
        addBaseOiPartition(tampered, 10, 11);
        assertFailureContains("do not match deterministic derivation", () ->
                LiquidationV2PhysicalDataV1.buildDevelopment(tampered.options()));
    }

    private Fixture proxyFixture(Path root) throws Exception {
        ObjectNode inputs = JsonHashes.mapper().createObjectNode();
        long sourceStart = Instant.parse("2022-08-11T00:00:00Z").toEpochMilli();
        long decisionStart = Instant.parse("2022-11-11T00:00:00Z").toEpochMilli();
        long executionEnd = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli();
        addPartition(root, inputs, "feature", "coinalyze", "COINALYZE_PROXY", dailyRow("BTC", "BTCUSDT_PERP.A", "LONG", sourceStart, 100), sourceStart, sourceStart + 86_400_000L, null);
        addPartition(root, inputs, "feature", "price", "PUBLIC_ARCHIVE", priceRow("BTC", "BTCUSDT", sourceStart), sourceStart, sourceStart + 14_400_000L, null);
        addPartition(root, inputs, "label", "labels", "DERIVED_OUTCOME_LABEL", JsonHashes.mapper().createObjectNode()
                .put("asset", "BTC").put("episode_id", "fixture-episode").put("decision_time", decisionStart)
                .put("label", "UNINSPECTED"), decisionStart, decisionStart + 60_000L, null);
        addPartition(root, inputs, "execution", "execution", "PUBLIC_ARCHIVE", JsonHashes.mapper().createObjectNode()
                .put("asset", "BTC").put("open_time", decisionStart).put("open", 100).put("high", 101)
                .put("low", 99).put("close", 100).put("base_volume", 10), decisionStart, decisionStart + 60_000L, null);
        addPartition(root, inputs, "mark", "mark", "PUBLIC_ARCHIVE", JsonHashes.mapper().createObjectNode()
                .put("asset", "BTC").put("timestamp", decisionStart).put("open", 100).put("high", 101)
                .put("low", 99).put("close", 100), decisionStart, decisionStart + 60_000L, null);
        addPartition(root, inputs, "funding", "funding", "PUBLIC_ARCHIVE", JsonHashes.mapper().createObjectNode()
                .put("asset", "BTC").put("settlement_time", decisionStart + 7).put("event_id", "fixture-funding")
                .put("funding_rate", 0).put("mark_price", 100).put("funding_interval_hours", 8),
                decisionStart, decisionStart + 60_000L, null);
        ObjectNode metadata = JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("tier_index", 1)
                .put("effective_from", sourceStart).put("effective_until", executionEnd).put("lot_size", 0.001)
                .put("minimum_notional", 5).put("taker_fee_rate", 0.0005).put("slippage_rate", 0)
                .put("liquidation_fee_rate", 0.01).put("tier_notional_cap", 1_000_000)
                .put("maintenance_margin_rate", 0.005).put("maintenance_deduction", 0).put("terminal_tier", true);
        ObjectNode assumption = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-metadata-assumption/1")
                .put("version", 1).put("source_class", "HISTORICAL_APPROXIMATION")
                .put("assumption_description", "Present contract metadata is a disclosed historical approximation for this fixture.")
                .put("historical_availability_proven", false).put("historical_revision_proven", false).put("authoritative", false);
        addPartition(root, inputs, "metadata", "metadata", "PUBLIC_ARCHIVE", metadata, sourceStart, executionEnd, assumption);
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("root", root.toAbsolutePath().normalize().toString());
        options.set("profile", LiquidationDailyStressProfileV1.frozenContract());
        options.set("inputs", inputs);
        return new Fixture(options, inputs);
    }

    private void addPartition(Path root, ObjectNode inputs, String role, String name, String sourceClass,
            ObjectNode row, long start, long end, ObjectNode assumptionTemplate) throws Exception {
        String normalizedPath = "normalized/" + name + ".jsonl";
        String sourcePath = "raw/" + name + ".jsonl";
        String receiptPath = "receipts/" + name + ".json";
        byte[] normalized = LiquidationV2PhysicalDataV1.canonicalRoleRowsJsonl(role, List.of(row));
        byte[] source = normalized.clone();
        write(root, normalizedPath, normalized); write(root, sourcePath, source);
        ObjectNode receipt = LiquidationV2PhysicalDataV1.createRoleJsonlCopyReceipt(role, normalizedPath,
                normalized, sourcePath, source, Instant.ofEpochMilli(start).toString(), Instant.ofEpochMilli(end).toString(), sourceClass);
        byte[] receiptBytes = JsonHashes.canonicalBytes(receipt); write(root, receiptPath, receiptBytes);
        ObjectNode partition = JsonHashes.mapper().createObjectNode().put("path", normalizedPath)
                .put("sha256", JsonHashes.sha256(normalized)).put("source_path", sourcePath)
                .put("source_sha256", JsonHashes.sha256(source)).put("normalization_receipt_path", receiptPath)
                .put("normalization_receipt_sha256", JsonHashes.sha256(receiptBytes))
                .put("window_start", Instant.ofEpochMilli(start).toString())
                .put("window_end_exclusive", Instant.ofEpochMilli(end).toString());
        if (assumptionTemplate != null) {
            ObjectNode assumption = assumptionTemplate.deepCopy().put("source_path", sourcePath)
                    .put("source_byte_sha256", JsonHashes.sha256(source))
                    .put("window_start", Instant.ofEpochMilli(start).toString())
                    .put("window_end_exclusive", Instant.ofEpochMilli(end).toString());
            assumption.put("content_sha256", JsonHashes.ownHash(assumption));
            String assumptionPath = "receipts/metadata-assumption.json";
            byte[] assumptionBytes = JsonHashes.canonicalBytes(assumption); write(root, assumptionPath, assumptionBytes);
            partition.put("assumption_path", assumptionPath).put("assumption_sha256", JsonHashes.sha256(assumptionBytes));
        }
        if (!inputs.has(role)) inputs.putObject(role).putArray("partitions");
        ((com.fasterxml.jackson.databind.node.ArrayNode) inputs.path(role).path("partitions")).add(partition);
    }

    private void addBaseOiPartition(Fixture fixture, double sourceBaseQuantity, double normalizedValue) throws Exception {
        Path root = Path.of(fixture.options().path("root").asText());
        long start = Instant.parse("2022-08-11T00:00:00Z").toEpochMilli();
        long end = start + 300_000L;
        ObjectNode rawRow = JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT")
                .put("series_id", "open_interest_base").put("timeframe", "5m")
                .put("event_time", start).put("availability_time", start)
                .put("sum_open_interest", sourceBaseQuantity).put("sum_open_interest_value", 1_000_000);
        ObjectNode normalizedRow = oiRow("BTC", start, start, normalizedValue);
        String normalizedPath = "normalized/base-oi.jsonl", sourcePath = "raw/base-oi.jsonl";
        String receiptPath = "receipts/base-oi.json";
        byte[] normalized = LiquidationV2PhysicalDataV1.canonicalRoleRowsJsonl("feature", List.of(normalizedRow));
        byte[] source = (JsonHashes.canonicalString(rawRow) + "\n").getBytes(StandardCharsets.UTF_8);
        write(root, normalizedPath, normalized); write(root, sourcePath, source);
        ObjectNode receipt = JsonHashes.mapper().createObjectNode()
                .put("schema", "liquidation-v2-normalization-receipt/1").put("version", 1)
                .put("role", "feature").put("normalized_path", normalizedPath)
                .put("normalized_byte_sha256", JsonHashes.sha256(normalized))
                .put("source_path", sourcePath).put("source_byte_sha256", JsonHashes.sha256(source))
                .put("row_count", 1).put("window_start", Instant.ofEpochMilli(start).toString())
                .put("window_end_exclusive", Instant.ofEpochMilli(end).toString())
                .put("normalizer_id", "liquidation-v2-binance-oi-base-map/1")
                .put("normalizer_sha256", JsonHashes.sha256("liquidation-v2-binance-oi-base-map/1|strict-jsonl|source.sum_open_interest-to-feature.value|reject-usd-sum_open_interest_value|frozen-feature-schema"))
                .put("source_class", "PUBLIC_ARCHIVE").put("verified", false).put("authoritative", false)
                .put("historical_availability_proven", false).put("historical_revision_proven", false);
        receipt.putArray("series_scope").add("open_interest_base");
        receipt.put("availability_basis", "NOT_APPLICABLE");
        ObjectNode physicalIdentity = LiquidationV2PhysicalDataV1.frozenPhysicalIdentity();
        receipt.set("physical_identity", physicalIdentity);
        receipt.put("physical_identity_sha256", JsonHashes.canonicalSha256(physicalIdentity));
        receipt.put("content_sha256", JsonHashes.ownHash(receipt));
        byte[] receiptBytes = JsonHashes.canonicalBytes(receipt);
        write(root, receiptPath, receiptBytes);
        ObjectNode partition = JsonHashes.mapper().createObjectNode().put("path", normalizedPath)
                .put("sha256", JsonHashes.sha256(normalized)).put("source_path", sourcePath)
                .put("source_sha256", JsonHashes.sha256(source)).put("normalization_receipt_path", receiptPath)
                .put("normalization_receipt_sha256", JsonHashes.sha256(receiptBytes))
                .put("window_start", Instant.ofEpochMilli(start).toString())
                .put("window_end_exclusive", Instant.ofEpochMilli(end).toString());
        ((ArrayNode) fixture.inputs().path("feature").path("partitions")).add(partition);
    }

    private static void write(Path root, String relative, byte[] bytes) throws Exception {
        Path file = root.resolve(relative); Files.createDirectories(file.getParent()); Files.write(file, bytes);
    }

    private static ObjectNode dailyRow(String asset, String symbol, String side, long eventTime, double value) {
        return JsonHashes.mapper().createObjectNode().put("asset", asset).put("symbol", symbol)
                .put("series_id", "daily_liquidation_usd").put("timeframe", "1d").put("side", side)
                .put("event_time", eventTime).put("availability_time", eventTime + 172_800_000L).put("value", value);
    }

    private static ObjectNode priceRow(String asset, String symbol, long eventTime) {
        return JsonHashes.mapper().createObjectNode().put("asset", asset).put("symbol", symbol)
                .put("series_id", "price_ohlc").put("timeframe", "4h").put("event_time", eventTime)
                .put("availability_time", eventTime + 14_400_000L).put("open", 99).put("high", 101)
                .put("low", 98).put("close", 100).put("base_volume", 10);
    }

    private static ObjectNode oiRow(String asset, long eventTime, long availableTime, double baseQuantity) {
        return JsonHashes.mapper().createObjectNode().put("asset", asset).put("symbol", "BTCUSDT")
                .put("series_id", "open_interest_base").put("timeframe", "5m")
                .put("event_time", eventTime).put("availability_time", availableTime).put("value", baseQuantity);
    }

    private static ObjectNode executionRow(String asset, long openTime) {
        return JsonHashes.mapper().createObjectNode().put("asset", asset).put("open_time", openTime)
                .put("open", 100).put("high", 101).put("low", 99).put("close", 100).put("base_volume", 10);
    }

    private static ObjectNode markRow(String asset, long timestamp) {
        return JsonHashes.mapper().createObjectNode().put("asset", asset).put("timestamp", timestamp)
                .put("open", 100).put("high", 101).put("low", 99).put("close", 100);
    }

    private static ObjectNode metadataTier(long start, long end, int tier, double cap, double rate, boolean terminal) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("tier_index", tier)
                .put("effective_from", start).put("effective_until", end).put("lot_size", 0.001)
                .put("minimum_notional", 5).put("taker_fee_rate", 0.0005).put("slippage_rate", 0)
                .put("liquidation_fee_rate", 0.01).put("tier_notional_cap", cap)
                .put("maintenance_margin_rate", rate).put("maintenance_deduction", 0)
                .put("terminal_tier", terminal);
    }

    private static void assertFailureContains(String messagePart, Executable action) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, action);
        assertTrue(error.getMessage().contains(messagePart), error.getMessage());
    }

    private static ObjectNode fundingRow(long settlementTime, String eventId) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("settlement_time", settlementTime)
                .put("event_id", eventId).put("funding_rate", 0).put("mark_price", 100).put("funding_interval_hours", 8);
    }

    private record Fixture(ObjectNode options, ObjectNode inputs) {}
}

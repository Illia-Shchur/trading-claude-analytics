package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.junit.jupiter.api.Test;

/** Receipt inventory vectors that count physical coverage without claiming historical PIT proof. */
class LiquidationQualificationCoverageInventoryMatrixV1Test {
    @Test
    void createReceiptReopensAllFrozenAssetsAndKeepsCoverageAndAvailabilityClaimsSeparate() {
        ArrayNode sourceRows = JsonHashes.mapper().createArrayNode();
        sourceRows.addObject().put("asset", "SOL").put("day_start_utc", "2022-08-13T00:00:00Z");
        sourceRows.addObject().put("asset", "BTC").put("day_start_utc", "2022-08-11T00:00:00Z");
        sourceRows.addObject().put("asset", "BTC").put("day_start_utc", "2022-08-12T00:00:00Z");
        sourceRows.addObject().put("asset", "ETH").put("day_start_utc", "2022-08-12T00:00:00Z");
        ObjectNode receipt = createReceipt(sourceRows);
        LiquidationInputQualificationV1.validateReceipt(receipt);

        JsonNode inputs = receipt.path("inputs");
        assertEquals(9, inputs.size());
        assertEquals("PROXY_DISCLOSED", inputs.get(0).path("qualification_status").asText());
        assertEquals("UNAVAILABLE", inputs.get(1).path("qualification_status").asText());
        assertEquals("UNKNOWN", inputs.get(6).path("qualification_status").asText());
        assertTrue(inputs.get(0).path("coverage_sufficient_for_intended_use").isBoolean());
        assertFalse(inputs.get(0).path("coverage_sufficient_for_intended_use").asBoolean());
        assertEquals("UNKNOWN", inputs.get(0).path("availability_status").asText());
        assertEquals("UNKNOWN", inputs.get(0).path("revision_status").asText());
        assertFalse(receipt.path("historical_availability_proven").asBoolean());

        ArrayNode perAsset = (ArrayNode) inputs.get(0).path("coverage").path("by_asset");
        assertEquals("BTC", perAsset.get(0).path("asset").asText());
        assertEquals(2, perAsset.get(0).path("observed_rows").asInt());
        assertEquals("2022-08-11", perAsset.get(0).path("first_observed").asText());
        assertEquals("2022-08-12", perAsset.get(0).path("last_observed").asText());
        assertEquals(1_499, perAsset.get(0).path("missing_days").asInt());
        assertEquals("ETH", perAsset.get(1).path("asset").asText());
        assertEquals(1, perAsset.get(1).path("observed_rows").asInt());
        assertEquals("AAVE", perAsset.get(3).path("asset").asText());
        assertEquals(0, perAsset.get(3).path("observed_rows").asInt());
        assertEquals("", perAsset.get(3).path("first_observed").asText());
        assertEquals("", perAsset.get(3).path("last_observed").asText());
        assertEquals(1_501, perAsset.get(3).path("missing_days").asInt());
    }

    @Test
    void dailyCoverageInputRejectsUnexpectedAssetsDuplicateUtcBucketsAndNonArrayRows() {
        ArrayNode unexpected = JsonHashes.mapper().createArrayNode();
        unexpected.addObject().put("asset", "DOGE").put("day_start_utc", "2022-08-11T00:00:00Z");
        IllegalArgumentException unexpectedError = assertThrows(IllegalArgumentException.class,
                () -> createReceipt(unexpected));
        assertTrue(unexpectedError.getMessage().contains("unexpected asset"));

        ArrayNode duplicates = JsonHashes.mapper().createArrayNode();
        duplicates.addObject().put("asset", "ETH").put("day_start_utc", "2022-08-11T00:00:00Z");
        duplicates.addObject().put("asset", "ETH").put("day_start_utc", "2022-08-11T23:00:00Z");
        IllegalArgumentException duplicateError = assertThrows(IllegalArgumentException.class,
                () -> createReceipt(duplicates));
        assertTrue(duplicateError.getMessage().contains("duplicate daily liquidation bucket"));

        ArrayNode malformedTime = JsonHashes.mapper().createArrayNode();
        malformedTime.addObject().put("asset", "BTC").put("day_start_utc", "not-an-instant");
        assertThrows(DateTimeParseException.class, () -> createReceipt(malformedTime));
        IllegalArgumentException nonArray = assertThrows(IllegalArgumentException.class,
                () -> createReceipt(JsonHashes.mapper().createObjectNode()));
        assertTrue(nonArray.getMessage().contains("rows are not an array"));
    }

    private static ObjectNode createReceipt(JsonNode rows) {
        ObjectNode precommit = JsonHashes.mapper().createObjectNode().put("precommit_id", "liquidation-daily-stress-v002")
                .put("content_sha256", "a".repeat(64));
        ObjectNode acquisition = JsonHashes.mapper().createObjectNode().put("captured_at", "2026-09-20T09:00:00Z")
                .put("content_sha256", "b".repeat(64));
        byte[] precommitBytes = JsonHashes.canonicalBytes(precommit);
        byte[] acquisitionBytes = JsonHashes.canonicalBytes(acquisition);
        return LiquidationInputQualificationV1.createReceipt(precommit, Path.of("precommit.json"), precommitBytes,
                acquisition, Path.of("acquisition.json"), acquisitionBytes, rows);
    }
}

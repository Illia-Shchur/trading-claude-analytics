package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Public constructor boundaries for canonical normalization receipts, with an accepted row control. */
class LiquidationNormalizationReceiptConstructorBoundaryV1Test {
    private static final long EVENT = Instant.parse("2022-08-11T00:00:00Z").toEpochMilli();
    private static final long DAY = 86_400_000L;
    private static final String NORMALIZED_PATH = "normalized/feature.jsonl";
    private static final String SOURCE_PATH = "raw/feature.jsonl";
    private static final byte[] SOURCE_BYTES = "preserved source bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    void acceptedReceiptAndMalformedRoleJsonlTimeAndWindowInputsAreCheckedAtTheirOwnBoundary() {
        byte[] canonical = LiquidationV2PhysicalDataV1.canonicalRoleRowsJsonl("feature", List.of(dailyRow()));
        ObjectNode accepted = create("feature", canonical, time(EVENT), time(EVENT + DAY));
        assertEquals("liquidation-v2-normalization-receipt/1", accepted.path("schema").asText());
        assertEquals("feature", accepted.path("role").asText());
        assertEquals(JsonHashes.sha256(canonical), accepted.path("normalized_byte_sha256").asText());
        assertEquals(JsonHashes.sha256(SOURCE_BYTES), accepted.path("source_byte_sha256").asText());

        expect("unsupported v002 normalization role unknown-role",
                () -> create("unknown-role", canonical, time(EVENT), time(EVENT + DAY)));
        expect("feature JSONL cannot be empty", () -> create("feature", new byte[0], time(EVENT), time(EVENT + DAY)));
        expect("invalid feature JSONL", () -> create("feature", "{".getBytes(StandardCharsets.UTF_8), time(EVENT), time(EVENT + DAY)));
        expect("feature JSONL rows must be objects",
                () -> create("feature", "[]\n".getBytes(StandardCharsets.UTF_8), time(EVENT), time(EVENT + DAY)));
        expect("normalized JSONL must use canonical v002 role serialization",
                () -> create("feature", ("\n" + new String(canonical, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8),
                        time(EVENT), time(EVENT + DAY)));
        expect("feature window_start must be an integral epoch millisecond or ISO UTC timestamp",
                () -> create("feature", canonical, "not-a-time", time(EVENT + DAY)));
        expect("normalization window must have positive duration",
                () -> create("feature", canonical, time(EVENT), time(EVENT)));
        expect("feature row falls outside its declared half-open partition window",
                () -> create("feature", canonical, time(EVENT + 1_000L), time(EVENT + 2_000L)));
    }

    private static ObjectNode create(String role, byte[] normalized, String start, String end) {
        return LiquidationV2PhysicalDataV1.createRoleJsonlCopyReceipt(role, NORMALIZED_PATH, normalized,
                SOURCE_PATH, SOURCE_BYTES, start, end, "COINALYZE_PROXY");
    }

    private static void expect(String expected, Executable action) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, action);
        assertTrue(error.getMessage().contains(expected),
                "expected [" + expected + "], got [" + error.getMessage() + "]");
    }

    private static ObjectNode dailyRow() {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT_PERP.A")
                .put("series_id", "daily_liquidation_usd").put("timeframe", "1d").put("side", "LONG")
                .put("event_time", EVENT).put("availability_time", EVENT + 2 * DAY).put("value", 100);
    }

    private static String time(long value) { return Instant.ofEpochMilli(value).toString(); }
}

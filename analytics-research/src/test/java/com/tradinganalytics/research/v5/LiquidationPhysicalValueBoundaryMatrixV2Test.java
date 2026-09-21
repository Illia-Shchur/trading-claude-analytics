package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Test-only role-value boundary matrix for the frozen physical input contracts. */
class LiquidationPhysicalValueBoundaryMatrixV2Test {
    private static final long DAY = 86_400_000L;
    private static final long T = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();

    @Test
    void featureClockAndUnitBoundariesAreSpecificAndKeepLegitimateZeroObservations() {
        valid(price("1m", T, T + 60_000L), "minute price bar");
        valid(price("1h", T, T + 3_600_000L), "hour price bar");
        valid(daily("LONG", T, T + 2 * DAY, 0), "zero liquidation is a valid no-stress observation");
        valid(daily("SHORT", T, T + 2 * DAY, 1), "short-side source row");
        valid(oi(T, T, 1), "OI is an observation that can be available at its observed timestamp");
        valid(macro(Instant.parse("2024-01-02T21:00:00Z"), Instant.parse("2024-01-03T21:00:00Z")),
                "verified NYSE session close");

        invalid(price("5m", T, T + 300_000L), "price bars require a frozen instrument");
        invalid(price("4h", T + 1, T + 14_400_000L), "align to its declared UTC cadence");
        invalid(price("4h", T, T + 14_399_999L), "no earlier than completed-bar time");
        invalid(price("4h", T, T + 14_400_000L).put("symbol", "BTC!USDT"), "symbol is not canonical");
        invalid(price("4h", T, T + 14_400_000L).put("symbol", "ETHUSDT"), "symbol does not match");
        invalid(price("4h", T, T + 14_400_000L).put("close", 0), "close has an invalid value");
        invalid(price("4h", T, T + 14_400_000L).put("base_volume", Double.POSITIVE_INFINITY),
                "base_volume must be finite numeric data");
        invalid(price("4h", T, T + 14_400_000L).put("event_time", 1.5), "must be integral epoch milliseconds");
        ObjectNode iso = price("4h", T, T + 14_400_000L);
        iso.put("event_time", "2024-01-01T00:00:00Z").put("availability_time", "2024-01-01T04:00:00Z");
        invalid(iso, "must be epoch milliseconds");
        invalid(without(price("4h", T, T + 14_400_000L), "symbol"), "feature rows require an explicit source symbol");
        invalid(price("4h", T, T + 14_400_000L).put("series_id", "derived_signal"), "not a frozen underlying input");

        invalid(daily("BOTH", T, T + 2 * DAY, 1), "must preserve asset and position side");
        invalid(daily("LONG", T, T + 2 * DAY - 1, 1), "t+48h assumption");
        invalid(daily("LONG", T, T + 2 * DAY, -1), "value has an invalid value");
        invalid(oi(T + 1, T + 1, 1), "open interest event_time must align");
        invalid(oi(T, T - 1, 1), "cannot be available before its event time");
        invalid(oi(T, T, 0), "value has an invalid value");
        invalid(oi(T, T, 1).put("symbol", "ETHUSDT"), "symbol does not match");
        invalid(oi(T, T, 1).put("timeframe", "1m"), "base quantity at five-minute cadence");
        invalid(macro(Instant.parse("2024-01-02T21:00:00Z"), Instant.parse("2024-01-03T20:59:00Z")),
                "at or after the next completed US equity session");

        ObjectNode duplicate = price("4h", T, T + 14_400_000L);
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2PhysicalDataV1.validateRows(
                "feature", List.of(duplicate, duplicate.deepCopy())), "duplicate feature identities are rejected");
    }

    @Test
    void outcomeExecutionAndMarkFormsEnforceTheirDistinctTimeAndNullContracts() {
        ObjectNode outcomeOnly = JsonHashes.mapper().createObjectNode().put("asset", "ETH")
                .put("episode_id", "resolved-no-trade").put("decision_time", T).put("outcome", "NO_TRADE");
        validRole("label", outcomeOnly, "label may carry the explicit outcome form");
        invalidRole("label", without(outcomeOnly.deepCopy(), "outcome"), "explicit label/outcome field");
        invalidRole("label", outcomeOnly.deepCopy().put("asset", "eth"), "canonical uppercase frozen instrument");
        invalidRole("label", without(outcomeOnly.deepCopy(), "decision_time"), "requires decision_time");
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2PhysicalDataV1.validateRows(
                "label", List.of(outcomeOnly, outcomeOnly.deepCopy())), "label episode identities must be unique");

        ObjectNode execution = execution(T);
        execution.put("base_volume", 0);
        validRole("execution", execution, "zero observed base volume is allowed");
        invalidRole("execution", execution(T + 1), "aligned to UTC minute");
        invalidRole("execution", without(execution(T), "base_volume"), "base_volume must be finite numeric data");
        invalidRole("execution", execution(T).put("open", -1), "open has an invalid value");
        invalidRole("execution", execution(T).put("high", 99), "OHLC bounds are inconsistent");

        ObjectNode mark = mark(T);
        validRole("mark", mark, "valid minute mark OHLC");
        invalidRole("mark", mark(T + 1), "aligned to UTC minute");
        invalidRole("mark", mark(T).put("low", 102), "OHLC bounds are inconsistent");
        invalidRole("mark", mark(T).put("close", Double.NaN), "close must be finite numeric data");
    }

    @Test
    void fundingAndMetadataBoundariesKeepIrregularButExplicitContractsAuditable() {
        validRole("funding", funding(T + 7, "positive-offset", 1), "one-hour contractual funding interval");
        validRole("funding", funding(T + 7, "negative-rate", 24).put("funding_rate", -0.01),
                "negative funding is a valid signed observation");
        validRole("funding", without(funding(T + 7, "legacy-no-interval", 8), "funding_interval_hours"),
                "legacy event remains physical without an asserted interval");
        validRole("funding", funding(T + 7, "null-interval", 8).putNull("funding_interval_hours"),
                "an explicit null interval remains distinct from a fabricated schedule");
        invalidRole("funding", funding(T + 7, "too-short", 0), "integer number of hours in [1,24]");
        invalidRole("funding", funding(T + 7, "too-long", 25), "integer number of hours in [1,24]");
        invalidRole("funding", funding(T + 7, "fractional", 8.5), "integer number of hours in [1,24]");
        invalidRole("funding", funding(T + 7, "bad-id/", 8), "event_id is not canonical");
        invalidRole("funding", funding(T + 7, "bad-rate", 8).put("funding_rate", "0.01"),
                "funding_rate must be finite numeric data");
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2PhysicalDataV1.validateRows("funding",
                List.of(funding(T + 7, "duplicate-a", 8), funding(T + 7, "duplicate-b", 8))),
                "two event identities cannot claim one physical asset settlement slot");

        validRole("metadata", metadata("1", T, T + DAY, 100, 0, true),
                "canonical textual maintenance tier and zero fee values");
        invalidRole("metadata", metadata("01", T, T + DAY, 100, 0.01, true), "canonical positive integer");
        invalidRole("metadata", metadata("1.5", T, T + DAY, 100, 0.01, true), "canonical positive integer");
        invalidRole("metadata", metadata("0", T, T + DAY, 100, 0.01, true), "canonical positive integer");
        invalidRole("metadata", metadata("1", T + DAY, T, 100, 0.01, true), "effective interval must be positive");
        invalidRole("metadata", metadata("1", T, T + DAY, 100, 1.0, true), "rate must be less than one");

        ObjectNode first = metadata("1", T, T + DAY, 100, 0.01, false);
        ObjectNode skipped = metadata("3", T, T + DAY, 300, 0.02, true);
        invalidRows("metadata", List.of(first, skipped), "indexes must be contiguous");
        ObjectNode differentIntervalEnd = metadata("2", T, T + 2 * DAY, 200, 0.02, true);
        invalidRows("metadata", List.of(first, differentIntervalEnd), "tiers in one effective interval disagree");
        ObjectNode duplicateTier = metadata("1", T, T + DAY, 200, 0.02, true);
        // Row identity includes asset, effective start, and tier index, so this malformed
        // duplicate is rejected before maintenance schedule aggregation.
        invalidRows("metadata", List.of(first, duplicateTier), "duplicate identity");
        ObjectNode missingTerminal = metadata("2", T, T + DAY, 200, 0.02, false);
        invalidRows("metadata", List.of(first, missingTerminal), "exactly the last maintenance tier");
    }

    private static ObjectNode price(String timeframe, long time, long available) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT")
                .put("series_id", "price_ohlc").put("timeframe", timeframe).put("event_time", time)
                .put("availability_time", available).put("open", 100).put("high", 101).put("low", 99)
                .put("close", 100).put("base_volume", 0);
    }

    private static ObjectNode daily(String side, long time, long available, double value) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT_PERP.A")
                .put("series_id", "daily_liquidation_usd").put("timeframe", "1d").put("side", side)
                .put("event_time", time).put("availability_time", available).put("value", value);
    }

    private static ObjectNode oi(long time, long available, double value) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT")
                .put("series_id", "open_interest_base").put("timeframe", "5m").put("event_time", time)
                .put("availability_time", available).put("value", value);
    }

    private static ObjectNode macro(Instant close, Instant available) {
        return JsonHashes.mapper().createObjectNode().put("asset", "SP500").put("symbol", "SP500")
                .put("series_id", "sp500_close").put("timeframe", "1d").put("event_time", close.toEpochMilli())
                .put("availability_time", available.toEpochMilli()).put("close", 4_700);
    }

    private static ObjectNode execution(long time) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("open_time", time)
                .put("open", 100).put("high", 101).put("low", 99).put("close", 100).put("base_volume", 10);
    }

    private static ObjectNode mark(long time) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("timestamp", time)
                .put("open", 100).put("high", 101).put("low", 99).put("close", 100);
    }

    private static ObjectNode funding(long time, String id, double interval) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("settlement_time", time)
                .put("event_id", id).put("funding_rate", 0).put("mark_price", 100).put("funding_interval_hours", interval);
    }

    private static ObjectNode metadata(String tier, long start, long end, double cap, double rate, boolean terminal) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("tier_index", tier)
                .put("effective_from", start).put("effective_until", end).put("lot_size", 0.001)
                .put("minimum_notional", 5).put("taker_fee_rate", 0).put("slippage_rate", 0)
                .put("liquidation_fee_rate", 0).put("tier_notional_cap", cap).put("maintenance_margin_rate", rate)
                .put("maintenance_deduction", 0).put("terminal_tier", terminal);
    }

    private static void valid(ObjectNode row, String context) {
        validRole("feature", row, context);
    }

    private static void validRole(String role, ObjectNode row, String context) {
        assertDoesNotThrow(() -> LiquidationV2PhysicalDataV1.validateRows(role, List.of(row)), context);
    }

    private static void invalid(ObjectNode row, String expected) {
        invalidRole("feature", row, expected);
    }

    private static void invalidRole(String role, ObjectNode row, String expected) {
        invalidRows(role, List.of(row), expected);
    }

    private static void invalidRows(String role, List<ObjectNode> rows, String expected) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.validateRows(role, rows));
        assertTrue(error.getMessage().contains(expected), "expected '" + expected + "' but got: " + error.getMessage());
    }

    private static ObjectNode without(ObjectNode value, String field) {
        value.remove(field);
        return value;
    }
}

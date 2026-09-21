package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Independent negative vectors for the role-specific physical data contract. */
class LiquidationPhysicalRoleValidationMatrixV1Test {
    private static final long DAY = 86_400_000L;
    private static final long T = 1_704_067_200_000L; // 2024-01-01T00:00:00Z

    @Test
    void featureRowsRejectWrongInstrumentClockIdentityAvailabilityAndValues() {
        valid(feature("BTC", "BTCUSDT", "price_ohlc", "4h", T, T + 14_400_000L), "valid BTC H4 source bar");
        valid(feature("BTC", "BTCUSDT", "open_interest_base", "5m", T, T), "valid base-quantity OI snapshot");
        valid(feature("BTC", "BTCUSDT_PERP.A", "daily_liquidation_usd", "1d", T, T + 2 * DAY),
                "valid disclosed daily liquidation bucket");
        bad(feature("DOGE", "BTCUSDT", "price_ohlc", "4h", T, T + 14_400_000L), "feature instrument scope",
                "feature asset must be a frozen instrument");
        bad(feature("BTC", "ETHUSDT", "price_ohlc", "4h", T, T + 14_400_000L), "Binance price symbol",
                "symbol does not match the frozen asset mapping");
        bad(feature("BTC", "BTCUSDT", "price_ohlc", "4h", T, T + 14_399_999L), "completed-bar availability",
                "availability must be no earlier than completed-bar time");
        ObjectNode unavailable = feature("BTC", "BTCUSDT", "price_ohlc", "4h", T, T + 14_400_000L);
        unavailable.put("availability_time", T - 1);
        bad(unavailable, "event-time availability ordering", "cannot be available before its event time");
        ObjectNode injected = feature("BTC", "BTCUSDT", "price_ohlc", "4h", T, T + 14_400_000L);
        injected.put("outcome", 1);
        bad(injected, "feature schema whitelist", "undeclared field: outcome");
        ObjectNode invalidPrice = feature("BTC", "BTCUSDT", "price_ohlc", "4h", T, T + 14_400_000L);
        invalidPrice.put("high", 98);
        bad(invalidPrice, "price OHLC envelope", "OHLC bounds are inconsistent");
        ObjectNode negativeVolume = feature("BTC", "BTCUSDT", "price_ohlc", "1m", T, T + 60_000L);
        negativeVolume.put("base_volume", -1);
        bad(negativeVolume, "negative optional volume", "base_volume has an invalid value");

        bad(feature("BTC", "ETHUSDT", "open_interest_base", "5m", T, T), "OI frozen instrument map",
                "symbol does not match the frozen asset mapping");
        bad(feature("BTC", "BTCUSDT", "open_interest_base", "5m", T + 1, T + 1), "OI UTC alignment",
                "open interest event_time must align");
        ObjectNode zeroOi = feature("BTC", "BTCUSDT", "open_interest_base", "5m", T, T);
        zeroOi.put("value", 0);
        bad(zeroOi, "OI must be positive base quantity", "value has an invalid value");

        bad(feature("BTC", "ETHUSDT_PERP.A", "daily_liquidation_usd", "1d", T, T + 2 * DAY),
                "Coinalyze symbol map", "symbol does not match the frozen asset mapping");
        ObjectNode wrongSide = feature("BTC", "BTCUSDT_PERP.A", "daily_liquidation_usd", "1d", T, T + 2 * DAY);
        wrongSide.put("side", "BOTH");
        bad(wrongSide, "daily liquidation position side", "must preserve asset and position side");
        bad(feature("BTC", "BTCUSDT_PERP.A", "daily_liquidation_usd", "1d", T, T + 2 * DAY - 1),
                "strict t+48h availability", "unavailable before its frozen t+48h assumption");
        bad(feature("SP500", "SP500", "price_ohlc", "1d", T, T + DAY), "S&P context may not be instrument OHLC",
                "price bars require a frozen instrument");
    }

    @Test
    void outcomeExecutionAndMarkRolesRejectMissingValuesMisalignmentAndInvalidMarketBars() {
        valid(label(T), "valid outcome label");
        valid(execution(T), "valid execution minute");
        valid(mark(T), "valid mark minute");
        ObjectNode emptyLabel = JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("episode_id", "e");
        bad(emptyLabel, "label has neither label nor outcome", "explicit label/outcome field");
        ObjectNode missingDecision = label(T);
        missingDecision.remove("decision_time");
        bad(missingDecision, "label decision time required", "requires decision_time");

        ObjectNode unalignedExecution = execution(T + 1);
        bad(unalignedExecution, "execution minute alignment", "aligned to UTC minute");
        ObjectNode badExecutionBounds = execution(T);
        badExecutionBounds.put("low", 102);
        bad(badExecutionBounds, "execution OHLC consistency", "OHLC bounds are inconsistent");
        ObjectNode zeroExecutionOpen = execution(T);
        zeroExecutionOpen.put("open", 0);
        bad(zeroExecutionOpen, "execution prices strictly positive", "open has an invalid value");
        ObjectNode callerTrade = execution(T);
        callerTrade.put("trade_id", "caller-authored");
        bad(callerTrade, "execution schema rejects caller-authored trade IDs", "undeclared field: trade_id");

        ObjectNode unalignedMark = mark(T + 1);
        bad(unalignedMark, "mark minute alignment", "aligned to UTC minute");
        ObjectNode badMarkBounds = mark(T);
        badMarkBounds.put("high", 98);
        bad(badMarkBounds, "mark OHLC consistency", "OHLC bounds are inconsistent");
        ObjectNode zeroMarkClose = mark(T);
        zeroMarkClose.put("close", 0);
        bad(zeroMarkClose, "mark prices strictly positive", "close has an invalid value");
    }

    @Test
    void fundingAndMetadataValidateIdentityAndCoverageInputsWithoutInventingRows() {
        valid(funding(T + 7, "funding-1", 8), "valid signed funding settlement");
        valid(metadata(1, T, T + DAY, 100, 0.01, true), "valid terminal maintenance tier");
        ObjectNode malformedId = funding(T + 7, "bad/event", 8);
        bad(malformedId, "funding event identity", "event_id is not canonical");
        ObjectNode nonfinite = funding(T + 7, "funding-1", 8);
        nonfinite.put("funding_rate", Double.NaN);
        bad(nonfinite, "funding rate finite", "funding_rate must be finite numeric data");
        ObjectNode missingMark = funding(T + 7, "funding-2", 8);
        missingMark.remove("mark_price");
        bad(missingMark, "funding mark required", "mark_price must be finite numeric data");

        ObjectNode mismatchedContractCosts = metadata(1, T, T + DAY, 100, 0.01, false);
        ObjectNode tierTwo = metadata(2, T, T + DAY, 200, 0.02, true);
        tierTwo.put("taker_fee_rate", 0.001);
        bad(List.of(mismatchedContractCosts, tierTwo), "tiers share transaction costs", "costs/filters differ");
        ObjectNode nonmonotoneCapOne = metadata(1, T, T + DAY, 200, 0.01, false);
        ObjectNode nonmonotoneCapTwo = metadata(2, T, T + DAY, 100, 0.02, true);
        bad(List.of(nonmonotoneCapOne, nonmonotoneCapTwo), "maintenance cap increasing", "increasing notional caps");
        ObjectNode nonmonotoneRateOne = metadata(1, T, T + DAY, 100, 0.03, false);
        ObjectNode nonmonotoneRateTwo = metadata(2, T, T + DAY, 200, 0.02, true);
        bad(List.of(nonmonotoneRateOne, nonmonotoneRateTwo), "maintenance rate nondecreasing", "nondecreasing rates");
        ObjectNode overlapping = metadata(1, T + DAY / 2, T + 2 * DAY, 100, 0.01, true);
        bad(List.of(metadata(1, T, T + DAY, 100, 0.01, true), overlapping), "historical metadata overlap",
                "historical metadata effective intervals overlap");
        ObjectNode malformedTerminal = metadata(1, T, T + DAY, 100, 0.01, true);
        malformedTerminal.put("terminal_tier", "yes");
        bad(malformedTerminal, "terminal tier must be typed boolean", "terminal_tier must be boolean");
    }

    private static void bad(ObjectNode row, String context) {
        bad(List.of(row), context, null);
    }

    private static void bad(ObjectNode row, String context, String expectedMessage) {
        bad(List.of(row), context, expectedMessage);
    }

    private static void bad(List<ObjectNode> rows, String context) {
        bad(rows, context, null);
    }

    private static void bad(List<ObjectNode> rows, String context, String expectedMessage) {
        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> LiquidationV2PhysicalDataV1.validateRows(role(rows), rows), context);
        assertTrue(error.getMessage() != null && !error.getMessage().isBlank(), context + " should fail with an auditable reason");
        if (expectedMessage != null) assertTrue(error.getMessage().contains(expectedMessage),
                context + " should reach its own rule, got: " + error.getMessage());
    }

    private static void valid(ObjectNode row, String context) {
        assertDoesNotThrow(() -> LiquidationV2PhysicalDataV1.validateRows(role(List.of(row)), List.of(row)), context);
    }

    private static String role(List<ObjectNode> rows) {
        ObjectNode row = rows.get(0);
        if (row.has("series_id")) return "feature";
        if (row.has("decision_time") || row.has("episode_id")) return "label";
        if (row.has("open_time")) return "execution";
        if (row.has("timestamp")) return "mark";
        if (row.has("settlement_time")) return "funding";
        return "metadata";
    }

    private static ObjectNode feature(String asset, String symbol, String series, String timeframe, long event, long availability) {
        ObjectNode row = JsonHashes.mapper().createObjectNode().put("asset", asset).put("symbol", symbol)
                .put("series_id", series).put("timeframe", timeframe).put("event_time", event)
                .put("availability_time", availability);
        switch (series) {
            case "price_ohlc" -> row.put("open", 100).put("high", 102).put("low", 98).put("close", 101).put("base_volume", 1);
            case "open_interest_base" -> row.put("value", 1_000);
            case "daily_liquidation_usd" -> row.put("side", "LONG").put("value", 1_000);
            default -> { }
        }
        return row;
    }

    private static ObjectNode label(long time) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("episode_id", "e")
                .put("decision_time", time).put("label", "UNINSPECTED");
    }

    private static ObjectNode execution(long time) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("open_time", time)
                .put("open", 100).put("high", 102).put("low", 98).put("close", 101).put("base_volume", 1);
    }

    private static ObjectNode mark(long time) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("timestamp", time)
                .put("open", 100).put("high", 102).put("low", 98).put("close", 101);
    }

    private static ObjectNode funding(long time, String eventId, double hours) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("settlement_time", time)
                .put("event_id", eventId).put("funding_rate", 0.001).put("mark_price", 100)
                .put("funding_interval_hours", hours);
    }

    private static ObjectNode metadata(int index, long from, long until, double cap, double rate, boolean terminal) {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("tier_index", index)
                .put("effective_from", from).put("effective_until", until).put("lot_size", 0.001)
                .put("minimum_notional", 5).put("taker_fee_rate", 0.0005).put("slippage_rate", 0.0001)
                .put("liquidation_fee_rate", 0.01).put("tier_notional_cap", cap)
                .put("maintenance_margin_rate", rate).put("maintenance_deduction", 0)
                .put("terminal_tier", terminal);
    }
}

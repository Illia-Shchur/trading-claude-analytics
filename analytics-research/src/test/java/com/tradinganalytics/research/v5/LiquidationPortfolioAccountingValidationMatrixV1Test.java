package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import org.junit.jupiter.api.Test;

/** Rule-specific rejection vectors for the shared account event and position contract. */
class LiquidationPortfolioAccountingValidationMatrixV1Test {
    private static final long T = 1_700_000_000_000L;

    @Test
    void accountAndPositionEnvelopesRejectInvalidCapitalAndFrozenAssetIdentity() {
        ObjectNode unsupported = base();
        unsupported.put("schema", "unrecognized-account/1");
        error("unsupported shared-account fixture schema", () -> LiquidationPortfolioAccountingV1.replayFixture(unsupported));

        ObjectNode overReserved = base();
        overReserved.put("reserved_costs_usdt", 20_001);
        error("account reserves exceed the 100% margin cap", () -> LiquidationPortfolioAccountingV1.replayFixture(overReserved));

        ObjectNode empty = base();
        empty.putArray("positions");
        error("positions must be a nonempty array", () -> LiquidationPortfolioAccountingV1.replayFixture(empty));

        ObjectNode unknown = base();
        ((ObjectNode) unknown.path("positions").get(0)).put("asset", "DOGE");
        error("position assets must be distinct frozen symbols", () -> LiquidationPortfolioAccountingV1.replayFixture(unknown));

        ObjectNode duplicate = base();
        duplicate.withArray("positions").add(position("BTC", "SHORT", 110));
        error("position assets must be distinct frozen symbols", () -> LiquidationPortfolioAccountingV1.replayFixture(duplicate));

        ObjectNode badDirection = base();
        ((ObjectNode) badDirection.path("positions").get(0)).put("direction", "SIDEWAYS");
        error("position direction must be LONG or SHORT", () -> LiquidationPortfolioAccountingV1.replayFixture(badDirection));

        ObjectNode crossedLong = base();
        ((ObjectNode) crossedLong.path("positions").get(0)).put("recovery_target", 90);
        error("long stop must be below fixed recovery target", () -> LiquidationPortfolioAccountingV1.replayFixture(crossedLong));

        ObjectNode crossedShort = base();
        ObjectNode shortPosition = (ObjectNode) crossedShort.path("positions").get(0);
        shortPosition.put("direction", "SHORT").put("common_stop", 90).put("recovery_target", 110);
        error("short stop must be above fixed recovery target", () -> LiquidationPortfolioAccountingV1.replayFixture(crossedShort));
    }

    @Test
    void maintenanceTierWindowsAndNotionalLaddersAreValidatedBeforeReplay() {
        ObjectNode emptyWindow = base();
        tiers(emptyWindow).addObject().put("effective_from", T).put("effective_until", T)
                .put("tier_notional_cap", 10_000).put("maintenance_margin_rate", 0.05).put("maintenance_deduction", 0);
        error("maintenance tier effective interval must be nonempty", () -> LiquidationPortfolioAccountingV1.replayFixture(emptyWindow));

        ObjectNode duplicateCaps = base();
        tiers(duplicateCaps).addObject().put("effective_from", T).put("effective_until", T + 10_000)
                .put("tier_notional_cap", 10_000).put("maintenance_margin_rate", 0.05).put("maintenance_deduction", 0);
        tiers(duplicateCaps).addObject().put("effective_from", T).put("effective_until", T + 10_000)
                .put("tier_notional_cap", 10_000).put("maintenance_margin_rate", 0.04).put("maintenance_deduction", 0);
        error("maintenance notional tier caps must be strictly increasing", () -> LiquidationPortfolioAccountingV1.replayFixture(duplicateCaps));

        ObjectNode overlap = base();
        tiers(overlap).addObject().put("effective_from", T).put("effective_until", T + 20_000)
                .put("tier_notional_cap", 10_000).put("maintenance_margin_rate", 0.05).put("maintenance_deduction", 0);
        tiers(overlap).addObject().put("effective_from", T + 10_000).put("effective_until", T + 30_000)
                .put("tier_notional_cap", 20_000).put("maintenance_margin_rate", 0.04).put("maintenance_deduction", 0);
        error("maintenance tier effective intervals must not overlap", () -> LiquidationPortfolioAccountingV1.replayFixture(overlap));

        ObjectNode invalidTierRate = base();
        tiers(invalidTierRate).addObject().put("effective_from", T).put("effective_until", T + 10_000)
                .put("tier_notional_cap", 10_000).put("maintenance_margin_rate", -0.01).put("maintenance_deduction", 0);
        error("maintenance_margin_rate cannot be negative", () -> LiquidationPortfolioAccountingV1.replayFixture(invalidTierRate));
    }

    @Test
    void eventRowsRejectUnknownAssetsDuplicateIdentityAndMalformedPathData() {
        ObjectNode missingEvents = base();
        missingEvents.remove("events");
        error("events must be an array", () -> LiquidationPortfolioAccountingV1.replayFixture(missingEvents));

        ObjectNode unknownAsset = base();
        mark(unknownAsset, "ETH", T, 100);
        error("event asset has no position specification", () -> LiquidationPortfolioAccountingV1.replayFixture(unknownAsset));

        ObjectNode duplicateFundingId = base();
        funding(duplicateFundingId, "duplicate-id", T, 0.001);
        funding(duplicateFundingId, "duplicate-id", T + 60_000, 0.001);
        error("funding event ids must be unique portfolio-wide", () -> LiquidationPortfolioAccountingV1.replayFixture(duplicateFundingId));

        ObjectNode duplicateEventKey = base();
        mark(duplicateEventKey, "BTC", T, 100);
        mark(duplicateEventKey, "BTC", T, 101);
        error("duplicate event identity for type, asset, time, and stage", () -> LiquidationPortfolioAccountingV1.replayFixture(duplicateEventKey));

        ObjectNode invalidBarClock = base();
        bar(invalidBarClock, "BAR_PRE", T + 60_000, T, 100, 101, 99, 100);
        error("BAR_PRE time must be the minute opening boundary", () -> LiquidationPortfolioAccountingV1.replayFixture(invalidBarClock));

        ObjectNode invalidOhlc = base();
        bar(invalidOhlc, "BAR_PRE", T, T, 100, 99, 101, 100);
        error("trade OHLC is internally inconsistent", () -> LiquidationPortfolioAccountingV1.replayFixture(invalidOhlc));

        ObjectNode missingStopSource = base();
        ObjectNode stop = missingStopSource.withArray("events").addObject().put("type", "STOP_UPDATE")
                .put("asset", "BTC").put("time", T).put("stop", 95);
        error("source_bar_close_time must be integer epoch milliseconds", () -> LiquidationPortfolioAccountingV1.replayFixture(missingStopSource));
    }

    @Test
    void streamingSessionRejectsOutOfOrderAndRepeatedSameTimeIdentityWithoutLosingPriorSnapshot() {
        ObjectNode request = base();
        var session = LiquidationPortfolioAccountingV1.startStreamingSession(request, ignored -> {});
        ObjectNode first = JsonHashes.mapper().createObjectNode().put("type", "MARK").put("asset", "BTC")
                .put("time", T).put("price", 100);
        session.accept(first);
        ObjectNode beforeDuplicate = session.snapshot();
        ObjectNode duplicate = first.deepCopy().put("price", 101);
        error("account event identity is repeated", () -> session.accept(duplicate));
        assertTrue(JsonHashes.canonicalSha256(beforeDuplicate).equals(JsonHashes.canonicalSha256(session.snapshot())),
                "rejected duplicate leaves the accepted ledger unchanged");

        var ordered = LiquidationPortfolioAccountingV1.startStreamingSession(request, ignored -> {});
        ordered.accept(JsonHashes.mapper().createObjectNode().put("type", "MARK").put("asset", "BTC")
                .put("time", T + 60_000).put("price", 100));
        error("account events must arrive in deterministic chronological order", () -> ordered.accept(
                JsonHashes.mapper().createObjectNode().put("type", "MARK").put("asset", "BTC")
                        .put("time", T).put("price", 100)));
    }

    @Test
    void validSharedFixtureAndCaseNormalizedEventArePositiveControls() {
        ObjectNode empty = LiquidationPortfolioAccountingV1.replayFixture(base());
        assertTrue(empty.path("positions").isArray());
        assertTrue(empty.path("events").isEmpty());

        ObjectNode request = base();
        request.withArray("events").addObject().put("type", "mark").put("asset", "btc").put("time", T).put("price", 101);
        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);
        assertTrue(result.path("events").get(0).path("mark_price").asDouble() == 101.0);
        assertTrue(result.path("events").get(0).path("type").asText().equals("MARK"));
    }

    @Test
    void addAttemptsRetainSpecificStageStopTargetCapacityAndLifecycleRejections() {
        assertAddReason("INVALID_OR_NONSEQUENTIAL_STAGE", addCase(2, 100, 90, null, "CONTINUATION", 10_000, 100));
        assertAddReason("REVERSAL_TARGET_MISSING", addCase(1, 100, 90, null, "REVERSAL", 10_000, 100));
        assertAddReason("INVALID_LONG_STOP", addCase(1, 100, 100, null, "CONTINUATION", 10_000, 100));
        assertAddReason("INVALID_RECOVERY_TARGET", addCase(1, 100, 90, 99.0, "REVERSAL", 10_000, 100));
        assertAddReason("REVERSAL_ADDITION_RR_BELOW_1R_OR_TARGET_REACHED",
                addCase(1, 100, 90, 101.0, "REVERSAL", 10_000, 100));
        assertAddReason("REVERSAL_TARGET_ALREADY_REACHED",
                addCase(1, 100, 90, 105.0, "REVERSAL", 10_000, 105));
        assertAddReason("NO_PRIOR_COMPLETED_MINUTE_CAPACITY", addCase(1, 100, 90, null, "CONTINUATION", 0, 100));

        ObjectNode sameHour = base();
        add(sameHour, 1, T, 100, 10_000, 90, null, "CONTINUATION", 100);
        add(sameHour, 2, T + 60_000, 100, 10_000, 90, null, "CONTINUATION", 100);
        ObjectNode sameHourLedger = LiquidationPortfolioAccountingV1.replayFixture(sameHour);
        assertTrue(sameHourLedger.path("events").get(0).path("type").asText().equals("ADD_FILLED"));
        assertTrue(sameHourLedger.path("events").get(1).path("reason").asText().equals("ENTRY_HOUR_ALREADY_USED"));

        ObjectNode expired = base();
        add(expired, 1, T, 100, 10_000, 90, null, "CONTINUATION", 100);
        add(expired, 2, T + 60L * 86_400_000L, 100, 10_000, 90, null, "CONTINUATION", 100);
        ObjectNode expiredLedger = LiquidationPortfolioAccountingV1.replayFixture(expired);
        assertTrue(expiredLedger.path("events").get(1).path("reason").asText().equals("SIXTY_DAY_LIFECYCLE_EXPIRED"));

        ObjectNode closed = base();
        add(closed, 1, T, 100, 10_000, 90, null, "CONTINUATION", 100);
        closed.withArray("events").addObject().put("type", "EXIT").put("asset", "BTC").put("time", T + 60_000)
                .put("price", 100).put("reason", "TEST_CLOSE");
        add(closed, 2, T + 120_000, 100, 10_000, 90, null, "CONTINUATION", 100);
        ObjectNode closedLedger = LiquidationPortfolioAccountingV1.replayFixture(closed);
        assertTrue(closedLedger.path("events").get(2).path("reason").asText().equals("POSITION_ALREADY_CLOSED"));
    }

    @Test
    void metadataRowsCheckFieldsAndEffectiveIntervalAtAdmission() {
        ObjectNode invalidWindow = base();
        metadata(invalidWindow, T, T + 1_000, T);
        error("metadata validity start cannot be after its end", () -> LiquidationPortfolioAccountingV1.replayFixture(invalidWindow));

        ObjectNode outside = base();
        metadata(outside, T, T + 1_000, T + 2_000);
        error("metadata event is outside its declared effective interval", () -> LiquidationPortfolioAccountingV1.replayFixture(outside));

        ObjectNode valid = base();
        metadata(valid, T, T, T + 1_000);
        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(valid);
        assertTrue(result.path("events").get(0).path("type").asText().equals("METADATA_APPLIED"));
    }

    private static ObjectNode base() {
        ObjectNode request = JsonHashes.mapper().createObjectNode().put("schema", "liquidation-v2-shared-account-fixture/1")
                .put("initial_equity_usdt", 20_000).put("reserved_costs_usdt", 0);
        request.putArray("positions").add(position("BTC", "LONG", 90));
        request.putArray("events");
        return request;
    }

    private static ObjectNode position(String asset, String direction, double stop) {
        return JsonHashes.mapper().createObjectNode().put("asset", asset).put("direction", direction).put("common_stop", stop)
                .putNull("recovery_target").put("lot_size", 0.01).put("minimum_notional", 5)
                .put("taker_fee_rate", 0).put("slippage_rate", 0).put("liquidation_fee_rate", 0).put("initial_mark_price", 100);
    }

    private static ArrayNode tiers(ObjectNode request) {
        ObjectNode position = (ObjectNode) request.path("positions").get(0);
        return position.withArray("maintenance_tiers");
    }

    private static ObjectNode addCase(int stage, double price, double stop, Double target, String mode,
            double capacity, double decisionMark) {
        ObjectNode request = base();
        add(request, stage, T, price, capacity, stop, target, mode, decisionMark);
        return request;
    }

    private static void assertAddReason(String expected, ObjectNode request) {
        ObjectNode result = LiquidationPortfolioAccountingV1.replayFixture(request);
        assertTrue(result.path("events").get(0).path("reason").asText().equals(expected),
                () -> "expected " + expected + " but got " + result.path("events").get(0));
    }

    private static void add(ObjectNode request, int stage, long time, double price, double capacity, double stop,
            Double target, String mode, double decisionMark) {
        ObjectNode row = request.withArray("events").addObject().put("type", "ADD").put("asset", "BTC")
                .put("time", time).put("stage", stage).put("setup_id", "fixture-setup-" + stage)
                .put("direction", "LONG").put("mode", mode)
                .put("price", price).put("decision_mark", decisionMark).put("common_stop", stop)
                .put("previous_completed_minute_base_volume", capacity);
        if (target == null) row.putNull("recovery_target"); else row.put("recovery_target", target);
    }

    private static void metadata(ObjectNode request, long time, long from, long until) {
        request.withArray("events").addObject().put("type", "METADATA").put("asset", "BTC").put("time", time)
                .put("effective_from", from).put("effective_until", until).put("lot_size", 0.01)
                .put("minimum_notional", 5).put("taker_fee_rate", 0).put("slippage_rate", 0)
                .put("liquidation_fee_rate", 0);
    }

    private static void mark(ObjectNode request, String asset, long time, double price) {
        request.withArray("events").addObject().put("type", "MARK").put("asset", asset).put("time", time).put("price", price);
    }

    private static void funding(ObjectNode request, String id, long time, double rate) {
        request.withArray("events").addObject().put("type", "FUNDING").put("asset", "BTC").put("time", time)
                .put("event_id", id).put("funding_rate", rate).put("mark_price", 100);
    }

    private static void bar(ObjectNode request, String type, long time, long start, double open, double high, double low, double close) {
        request.withArray("events").addObject().put("type", type).put("asset", "BTC").put("time", time).put("bar_start_time", start)
                .put("trade_open", open).put("trade_high", high).put("trade_low", low).put("trade_close", close)
                .put("mark_open", open).put("mark_high", high).put("mark_low", low).put("mark_close", close);
    }

    private static void error(String fragment, Runnable action) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
        assertTrue(failure.getMessage().contains(fragment), () -> "Expected error containing '" + fragment + "', got: " + failure.getMessage());
    }
}

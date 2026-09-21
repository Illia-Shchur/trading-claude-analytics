package com.tradinganalytics.research.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public bounded-reader paths and synthetic macro availability are tested from reopened Parquet roles. */
class LiquidationPhysicalReadRangeAndMacroBindingMatrixV1Test {
    private static final long SOURCE = Instant.parse("2022-08-11T00:00:00Z").toEpochMilli();
    private static final long DECISION = Instant.parse("2022-11-11T00:00:00Z").toEpochMilli();
    private static final long EXECUTION_END = Instant.parse("2026-09-20T00:00:00Z").toEpochMilli();
    private static final long MACRO_CLOSE = Instant.parse("2024-01-02T21:00:00Z").toEpochMilli();
    private static final long MACRO_AVAILABLE = Instant.parse("2024-01-03T21:00:00Z").toEpochMilli();

    @TempDir Path temporary;

    @Test
    void boundedReaderSupportsOptionalTimeAssetAndRoleSpecificIntervalPredicates() throws Exception {
        Fixture fixture = fixture("range-read", true);
        LiquidationV2PhysicalDataV1.VerifiedDevelopmentSession session = fixture.session();

        assertEquals(3, LiquidationV2PhysicalDataV1.readRoleRows(session, "feature", null, null, null).size());
        assertEquals(3, LiquidationV2PhysicalDataV1.readRoleRows(session, "feature", null, null, List.of()).size());
        assertEquals(2, LiquidationV2PhysicalDataV1.readRoleRows(session, "feature", null, null, List.of("BTC", "BTC")).size());
        assertEquals(2, LiquidationV2PhysicalDataV1.readRoleRows(session, "feature",
                "2022-08-11T00:00:00Z", null, List.of("BTC")).size());
        assertEquals(2, LiquidationV2PhysicalDataV1.readRoleRows(session, "feature",
                null, "2022-08-12T00:00:00Z", List.of("BTC")).size());
        assertEquals(0, LiquidationV2PhysicalDataV1.readRoleRows(session, "feature",
                "2022-08-12T00:00:00Z", null, List.of("BTC")).size());
        assertEquals(1, LiquidationV2PhysicalDataV1.readRoleRows(session, "feature",
                null, null, List.of("SP500")).size());

        assertEquals(1, LiquidationV2PhysicalDataV1.readRoleRows(session, "execution",
                "2022-11-11T00:00:00Z", "2022-11-11T00:01:00Z", List.of("BTC")).size());
        assertEquals(1, LiquidationV2PhysicalDataV1.readRoleRows(session, "mark",
                "2022-11-11T00:00:00Z", "2022-11-11T00:01:00Z", List.of("BTC")).size());
        assertEquals(1, LiquidationV2PhysicalDataV1.readRoleRows(session, "label",
                "2022-11-11T00:00:00Z", null, List.of("BTC")).size());
        assertEquals(1, LiquidationV2PhysicalDataV1.readRoleRows(session, "funding",
                null, "2022-11-11T00:01:00Z", List.of("BTC")).size());
        assertEquals(1, LiquidationV2PhysicalDataV1.readRoleRows(session, "metadata",
                "2023-01-01T00:00:00Z", "2023-01-02T00:00:00Z", List.of("BTC")).size());
        assertEquals(0, LiquidationV2PhysicalDataV1.readRoleRows(session, "metadata",
                "2026-09-20T00:00:00Z", "2026-09-21T00:00:00Z", List.of("BTC")).size());

        assertThrows(IllegalArgumentException.class, () -> LiquidationV2PhysicalDataV1.readRoleRows(session,
                "feature", "2022-08-11T00:00:00.000000001Z", null, List.of("BTC")));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2PhysicalDataV1.readRoleRows(session,
                "metadata", null, null, List.of("SP500")));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2PhysicalDataV1.readRoleRows(session,
                "unknown-role", null, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> LiquidationV2PhysicalDataV1.readRoleRows(session,
                "feature", null, null, List.of("DOGE")));
    }

    @Test
    void boundedReaderRechecksSelectedParquetBytesAfterSessionReopen() throws Exception {
        Fixture fixture = fixture("range-byte-tamper", true);
        var session = fixture.session();
        ObjectNode feature = (ObjectNode) session.manifest().path("artifacts").path("feature");
        Path parquet = session.root().resolve(feature.path("path").asText());
        Files.write(parquet, new byte[] {0x01}, java.nio.file.StandardOpenOption.APPEND);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LiquidationV2PhysicalDataV1.readRoleRows(session, "feature", null, null, List.of("BTC")));
        assertTrue(error.getMessage().contains("selected synthetic v002 Parquet role changed after verification"));
    }

    @Test
    void syntheticMacroReceiptIsBoundToScopeBasisAndFrozenExecutionEnvelope() throws Exception {
        Fixture withMacro = fixture("macro-present", true);
        assertTrue(LiquidationV2PhysicalDataV1.macroAvailabilityReceiptBound(withMacro.session(), MACRO_CLOSE));
        assertFalse(LiquidationV2PhysicalDataV1.macroAvailabilityReceiptBound(withMacro.session(), SOURCE - 1));
        assertFalse(LiquidationV2PhysicalDataV1.macroAvailabilityReceiptBound(withMacro.session(), EXECUTION_END));

        Fixture noMacro = fixture("macro-absent", false);
        assertFalse(LiquidationV2PhysicalDataV1.macroAvailabilityReceiptBound(noMacro.session(), MACRO_CLOSE));
    }

    private Fixture fixture(String name, boolean includeMacro) throws Exception {
        Path root = Files.createDirectories(temporary.resolve(name)).toRealPath();
        ObjectNode inputs = JsonHashes.mapper().createObjectNode();
        List<ObjectNode> feature = new java.util.ArrayList<>();
        feature.add(daily()); feature.add(price());
        if (includeMacro) feature.add(macro());
        addInput(root, inputs, "feature", feature);
        addInput(root, inputs, "label", List.of(JsonHashes.mapper().createObjectNode().put("asset", "BTC")
                .put("episode_id", "bounded-reader").put("decision_time", DECISION).put("label", "UNINSPECTED")));
        addInput(root, inputs, "execution", List.of(JsonHashes.mapper().createObjectNode().put("asset", "BTC")
                .put("open_time", DECISION).put("open", 100).put("high", 101).put("low", 99)
                .put("close", 100).put("base_volume", 10)));
        addInput(root, inputs, "mark", List.of(JsonHashes.mapper().createObjectNode().put("asset", "BTC")
                .put("timestamp", DECISION).put("open", 100).put("high", 101).put("low", 99).put("close", 100)));
        addInput(root, inputs, "funding", List.of(JsonHashes.mapper().createObjectNode().put("asset", "BTC")
                .put("settlement_time", DECISION + 7).put("event_id", "read-range-funding").put("funding_rate", 0)
                .put("mark_price", 100).put("funding_interval_hours", 8)));
        addInput(root, inputs, "metadata", List.of(JsonHashes.mapper().createObjectNode().put("asset", "BTC")
                .put("tier_index", 1).put("effective_from", SOURCE).put("effective_until", EXECUTION_END)
                .put("lot_size", 0.001).put("minimum_notional", 5).put("taker_fee_rate", 0.0005)
                .put("slippage_rate", 0.0001).put("liquidation_fee_rate", 0.01).put("tier_notional_cap", 1_000_000_000)
                .put("maintenance_margin_rate", 0.005).put("maintenance_deduction", 0).put("terminal_tier", true)));
        ObjectNode options = JsonHashes.mapper().createObjectNode().put("root", root.toString());
        options.set("profile", LiquidationDailyStressProfileV1.frozenContract()); options.set("inputs", inputs);
        ObjectNode manifest = LiquidationV2PhysicalDataV1.buildSyntheticDevelopment(options);
        options.set("manifest", manifest);
        return new Fixture(LiquidationV2PhysicalDataV1.openVerifiedDevelopment(options));
    }

    private static void addInput(Path root, ObjectNode inputs, String role, List<ObjectNode> rows) throws Exception {
        byte[] bytes = LiquidationV2PhysicalDataV1.canonicalRoleRowsJsonl(role, rows);
        String relative = "inputs/" + role + ".jsonl";
        Path path = root.resolve(relative); Files.createDirectories(path.getParent()); Files.write(path, bytes);
        inputs.putObject(role).put("path", relative).put("sha256", JsonHashes.sha256(bytes));
    }

    private static ObjectNode daily() {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT_PERP.A")
                .put("series_id", "daily_liquidation_usd").put("timeframe", "1d").put("side", "LONG")
                .put("event_time", SOURCE).put("availability_time", SOURCE + 172_800_000L).put("value", 100);
    }

    private static ObjectNode price() {
        return JsonHashes.mapper().createObjectNode().put("asset", "BTC").put("symbol", "BTCUSDT")
                .put("series_id", "price_ohlc").put("timeframe", "4h").put("event_time", SOURCE)
                .put("availability_time", SOURCE + 14_400_000L).put("open", 100).put("high", 101)
                .put("low", 99).put("close", 100).put("base_volume", 10);
    }

    private static ObjectNode macro() {
        return JsonHashes.mapper().createObjectNode().put("asset", "SP500").put("symbol", "SP500")
                .put("series_id", "sp500_close").put("timeframe", "1d").put("event_time", MACRO_CLOSE)
                .put("availability_time", MACRO_AVAILABLE).put("close", 4_700);
    }

    private record Fixture(LiquidationV2PhysicalDataV1.VerifiedDevelopmentSession session) {}
}

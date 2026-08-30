package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.LifecycleTrustService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TradeLifecycleV5NodeOracleTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    @TempDir Path temporary;

    @Test
    void fixtureLifecycleOutputsAndFailuresMatchNodeExactly() throws Exception {
        ArrayNode requests = MAPPER.createArrayNode();
        requests.add(longPartialTarget());
        requests.add(shortFundingTimeStop());
        requests.add(atrAndTrailing());
        requests.add(gapFailure());
        requests.add(spotShortFailure());
        requests.add(incompleteRightEdgeFailure());

        ArrayNode expected = frozenOracle();
        TradeLifecycleV5 java = new TradeLifecycleV5();
        ArrayNode actual = MAPPER.createArrayNode();
        for (JsonNode request : requests) {
            ObjectNode result = MAPPER.createObjectNode();
            try {
                result.put("ok", true).set("value", java.normalizeTradeLifecycleV5((ObjectNode) request));
            } catch (RuntimeException error) {
                result.put("ok", false).put("error", error.getMessage());
            }
            actual.add(result);
        }
        assertJson(actual, expected);
        for (JsonNode row : actual) {
            if (!row.path("ok").asBoolean()) continue;
            JsonNode value = row.path("value");
            assertThat(value.path("content_sha256").asText())
                    .isEqualTo(JsonHashes.ownHash(value));
            assertThat(value.path("remaining_quantity").asDouble()).isZero();
        }
    }

    @Test
    void aliasesAndSpecValidationPreserveThePublicContract() {
        ObjectNode request = longPartialTarget();
        TradeLifecycleV5 lifecycle = new TradeLifecycleV5();
        assertJson(lifecycle.simulateTradeLifecycleV5(request), lifecycle.normalizeTradeLifecycleV5(request));
        assertJson(lifecycle.simulateLifecycleV5(request), lifecycle.normalizeTradeLifecycleV5(request));
        assertJson(lifecycle.executeTradeIntentV5(request), lifecycle.normalizeTradeLifecycleV5(request));
        assertThat(TradeLifecycleV5.validateLifecycleSpecV5(request.path("intent").path("lifecycle"), "long", "SPOT"))
                .isTrue();
        assertThat(TradeLifecycleV5.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThatThrownBy(() -> TradeLifecycleV5.validateLifecycleSpecV5(MAPPER.createObjectNode()))
                .hasMessage("mandatory maximum time stop is missing");
    }

    @Test
    void authoritativeExecutionReopensPhysicalReceiptsAndRejectsMutation() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("physical"));
        ObjectNode request = longPartialTarget();
        ObjectNode intent = (ObjectNode) request.path("intent");
        intent.remove("fixtureOnly");
        intent.remove("contract");
        ObjectNode contract = MAPPER.createObjectNode()
                .put("contract_multiplier", 1).put("step_size", .001).put("min_qty", .001)
                .put("min_notional", 1).put("max_notional", 1_000_000).put("max_qty", 100_000);
        ObjectNode model = MAPPER.createObjectNode()
                .put("taker_fee_rate", .001).put("slippage_bps", 10).put("impact_bps", 2);
        ObjectNode capacity = MAPPER.createObjectNode()
                .put("available_liquidity_usd", 10_000_000).put("participation_cap", .25).put("impact_bps", 1);
        ArrayNode bars = (ArrayNode) request.path("bars");
        Map<String, LifecycleTrustService.ReceiptReference> receipts = new LinkedHashMap<>();
        receipts.put("contract_spec", writeReceipt(root, "contract.json", contract, null));
        receipts.put("execution_model", writeReceipt(root, "model.json", model, null));
        receipts.put("capacity", writeReceipt(root, "capacity.json", capacity, null));
        receipts.put("bars", writeReceipt(root, "bars.json", bars, JsonHashes.canonicalSha256(bars)));
        String lifecycleHash = JsonHashes.canonicalSha256(intent.path("lifecycle"));
        LifecycleTrustService trustService = new LifecycleTrustService();
        LifecycleTrustService.Token token = trustService.openLifecycleTrustV5(
                root, "fixture-dataset", receipts, Map.of("lifecycle_spec_sha256", lifecycleHash), true);

        ObjectNode result = new TradeLifecycleV5(trustService).normalizeTradeLifecycleV5(request, token);
        assertThat(result.path("provenance").asText()).isEqualTo("AUTHORITATIVE");
        assertThat(result.path("physical_execution_lineage").path("lifecycle_trust_sha256").asText())
                .isEqualTo(token.bundleSha256());
        assertThat(result.path("physical_execution_lineage").path("bars_rows_sha256").asText())
                .isEqualTo(receipts.get("bars").rowsSha256());
        assertThat(result.path("capacity_debit_usd").asDouble()).isPositive();
        assertThat(result.path("content_sha256").asText()).isEqualTo(JsonHashes.ownHash(result));

        Files.writeString(root.resolve("model.json"), "{\"taker_fee_rate\":0.5}\n");
        assertThatThrownBy(() -> new TradeLifecycleV5(trustService).normalizeTradeLifecycleV5(request, token))
                .hasMessageMatching("(?i).*(tampered|changed).*");
    }

    private static ObjectNode longPartialTarget() {
        ObjectNode request = MAPPER.createObjectNode().put("interval_ms", 60_000);
        ObjectNode intent = request.putObject("intent")
                .put("fixtureOnly", true).put("direction", "long").put("instrument_type", "spot")
                .put("decision_time", "2026-01-01T00:00:00Z");
        ObjectNode lifecycle = intent.putObject("lifecycle").put("max_lifecycle_ms", 240_000).put("gap_policy", "OPEN");
        lifecycle.putObject("stop").put("type", "PERCENT").put("value", .05);
        lifecycle.putObject("target").put("type", "R").put("multiple", 2);
        lifecycle.putObject("sizing").put("mode", "RISK_USD").put("risk_usd", 100);
        lifecycle.putArray("partial_exits").addObject().put("trigger_r", 1).put("fraction", .5);
        intent.putObject("contract").put("contract_multiplier", 1).put("step_size", .01)
                .put("min_qty", .01).put("min_notional", 1).put("max_notional", 1_000_000);
        request.putObject("execution").put("fee_rate", .001).put("slippage_bps", 10);
        ArrayNode bars = request.putArray("bars");
        addBar(bars, "2026-01-01T00:00:00Z", 100, 102, 99, 101);
        addBar(bars, "2026-01-01T00:01:00Z", 101, 106, 100, 105);
        addBar(bars, "2026-01-01T00:02:00Z", 105, 111, 104, 110);
        addBar(bars, "2026-01-01T00:03:00Z", 110, 111, 109, 110);
        return request;
    }

    private static ObjectNode shortFundingTimeStop() {
        ObjectNode request = MAPPER.createObjectNode().put("interval_ms", 60_000);
        ObjectNode intent = request.putObject("intent")
                .put("fixtureOnly", true).put("direction", "short").put("instrument_type", "perpetual")
                .put("decision_time", 1_767_225_600_000L).put("contract_multiplier", 1);
        ObjectNode lifecycle = intent.putObject("lifecycle").put("max_lifecycle_ms", 180_000);
        lifecycle.putObject("stop").put("type", "PERCENT").put("percent", .05);
        lifecycle.putObject("sizing").put("mode", "FIXED_NOTIONAL").put("notional_usd", 2_000);
        request.putObject("execution").put("fee_rate", .0005).put("slippage_bps", 3);
        ArrayNode bars = request.putArray("bars");
        addBar(bars, "2026-01-01T00:00:00Z", 200, 202, 198, 200);
        addBar(bars, "2026-01-01T00:01:00Z", 199, 201, 197, 198);
        addBar(bars, "2026-01-01T00:02:00Z", 198, 199, 194, 195);
        ArrayNode funding = request.putArray("funding");
        funding.addObject().put("event_time", "2026-01-01T00:01:00Z").put("event_id", "f1")
                .put("rate", .001).put("mark_price", 198);
        funding.addObject().put("event_time", "2026-01-01T00:02:00Z").put("event_id", "f2")
                .put("funding_rate", -.0005);
        request.putArray("marks").addObject().put("event_time", "2026-01-01T00:02:00Z").put("price", 196);
        return request;
    }

    private static ObjectNode atrAndTrailing() {
        long start = Instant.parse("2026-02-01T00:00:00Z").toEpochMilli();
        ObjectNode request = MAPPER.createObjectNode().put("interval_ms", 60_000);
        ObjectNode intent = request.putObject("intent").put("fixtureOnly", true).put("direction", "long")
                .put("instrument_type", "spot").put("decision_time", start + 14 * 60_000L);
        ObjectNode lifecycle = intent.putObject("lifecycle").put("max_lifecycle_ms", 180_000);
        lifecycle.putObject("stop").put("type", "ATR").put("period", 14).put("multiple", 2);
        lifecycle.putObject("sizing").put("mode", "FIXED_NOTIONAL").put("notional_usd", 1_000);
        lifecycle.putObject("trailing").put("type", "PERCENT").put("percent", .02);
        request.putObject("execution").put("fee_rate", 0).put("slippage_bps", 0);
        ArrayNode bars = request.putArray("bars");
        for (int index = 0; index < 17; index++) {
            double open = 100 + index * .2;
            addBar(bars, start + index * 60_000L, open, open + 1.2, open - 1, open + .3);
        }
        return request;
    }

    private static ObjectNode gapFailure() {
        ObjectNode request = longPartialTarget();
        ((ObjectNode) request.path("intent").path("lifecycle")).put("gap_policy", "FAIL");
        ((ObjectNode) request.path("bars").get(1)).put("open", 106).put("low", 100).put("high", 107).put("close", 106);
        return request;
    }

    private static ObjectNode spotShortFailure() {
        ObjectNode request = longPartialTarget();
        ((ObjectNode) request.path("intent")).put("direction", "short");
        return request;
    }

    private static ObjectNode incompleteRightEdgeFailure() {
        ObjectNode request = longPartialTarget();
        ((ObjectNode) request.path("intent").path("lifecycle")).remove("target");
        ((ObjectNode) request.path("intent").path("lifecycle")).remove("partial_exits");
        ((ArrayNode) request.path("bars")).remove(3);
        return request;
    }

    private static void addBar(ArrayNode bars, String time, double open, double high, double low, double close) {
        bars.addObject().put("event_time", time).put("open", open).put("high", high).put("low", low).put("close", close);
    }

    private static void addBar(ArrayNode bars, long time, double open, double high, double low, double close) {
        bars.addObject().put("event_time", time).put("open", open).put("high", high).put("low", low).put("close", close);
    }

    private static LifecycleTrustService.ReceiptReference writeReceipt(
            Path root, String name, JsonNode value, String rowsHash) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(value);
        Files.write(root.resolve(name), bytes);
        return new LifecycleTrustService.ReceiptReference(
                name, JsonHashes.ownHash(value), JsonHashes.sha256(bytes), (long) bytes.length, rowsHash, null);
    }

    private static ArrayNode frozenOracle() throws IOException {
        try (InputStream input = Objects.requireNonNull(
                TradeLifecycleV5NodeOracleTest.class.getResourceAsStream(
                        "/oracles/trade-lifecycle-v5.json"),
                "frozen trade-lifecycle oracle is missing")) {
            return (ArrayNode) MAPPER.readTree(input);
        }
    }

    private static void assertJson(JsonNode actual, JsonNode expected) {
        assertThat(CanonicalJson.canonicalize(actual)).isEqualTo(CanonicalJson.canonicalize(expected));
    }

}

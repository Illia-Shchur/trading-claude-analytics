package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.LifecycleTrustService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reviewer fixtures invoke the production trust-token path, without real market claims. */
final class TradeLifecycleClosureReviewTest {
    @TempDir Path root;

    @Test
    void physicalClosureUsesActualReopeningOpenForAnAdverseGap() throws Exception {
        ObjectNode result = execute(policy(), "BINANCE", 180);
        assertThat(result.path("exits").get(0).path("price").asDouble()).isEqualTo(90);
        assertThat(result.path("exits").get(0).path("fill_type").asText()).isEqualTo("GAP_OPEN");
    }

    @Test
    void untypedIntervalArrayIsNotASourceBoundClosurePolicy() {
        assertThatThrownBy(() -> execute(policy().path("intervals"), "BINANCE", 180))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void binancePolicyCannotExcuseAnotherVenuesMissingBars() {
        assertThatThrownBy(() -> execute(policy(), "OTHER", 180))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closureCannotExtendTheFrozenWallClockDeadline() {
        assertThatThrownBy(() -> execute(policy(), "BINANCE", 30))
                .hasMessageContaining("right-edge lifecycle is incomplete");
    }

    private ObjectNode execute(JsonNode policy, String contractVenue, int horizonMinutes) throws Exception {
        ObjectNode request = JsonHashes.mapper().createObjectNode().put("interval_ms", 60_000);
        ObjectNode intent = request.putObject("intent").put("direction", "long")
                .put("instrument_type", "SPOT").put("instrument", "BINANCE_SPOT")
                .put("venue", "BINANCE").put("asset", "eth").put("symbol", "ETHUSDT")
                .put("decision_time", "2021-09-29T06:59:00Z");
        ObjectNode lifecycle = intent.putObject("lifecycle").put("max_lifecycle_ms", horizonMinutes * 60_000L)
                .put("gap_policy", "OPEN");
        lifecycle.putObject("stop").put("type", "PERCENT").put("value", .06);
        lifecycle.putObject("sizing").put("mode", "FIXED_NOTIONAL").put("notional_usd", 1000);
        ArrayNode bars = request.putArray("bars");
        bars.addObject().put("event_time", "2021-09-29T06:59:00Z")
                .put("open", 100).put("high", 100).put("low", 100).put("close", 100);
        bars.addObject().put("event_time", "2021-09-29T09:00:00Z")
                .put("open", 90).put("high", 92).put("low", 89).put("close", 91);
        request.putObject("execution").set("allowed_non_trading_intervals",
                policy.isArray() ? policy : policy.path("intervals"));
        ObjectNode contract = JsonHashes.mapper().createObjectNode().put("contract_multiplier", 1)
                .put("step_size", .001).put("min_qty", .001).put("max_qty", 100000)
                .put("min_notional", 1).put("max_notional", 1000000)
                .put("asset", "eth").put("symbol", "ETHUSDT")
                .put("venue", contractVenue).put("instrument", "BINANCE_SPOT");
        ObjectNode model = JsonHashes.mapper().createObjectNode().put("taker_fee_rate", .001)
                .put("slippage_bps", 5).put("impact_bps", 0);
        ObjectNode capacity = JsonHashes.mapper().createObjectNode().put("available_liquidity_usd", 10000000)
                .put("participation_cap", .25).put("impact_bps", 0);
        Map<String, LifecycleTrustService.ReceiptReference> refs = new LinkedHashMap<>();
        refs.put("bars", write("bars.json", bars));
        refs.put("contract_spec", write("contract.json", contract));
        refs.put("execution_model", write("model.json", model));
        refs.put("capacity", write("capacity.json", capacity));
        refs.put("non_trading_intervals", write("closure.json", policy));
        LifecycleTrustService trust = new LifecycleTrustService();
        var token = trust.openLifecycleTrustV5(root, "review-synthetic-closure", refs,
                Map.of("lifecycle_spec_sha256", JsonHashes.canonicalSha256(lifecycle)), true);
        return new TradeLifecycleV5(trust).normalizeTradeLifecycleV5(request, token);
    }

    private LifecycleTrustService.ReceiptReference write(String name, JsonNode value) throws Exception {
        byte[] bytes = JsonHashes.mapper().writeValueAsBytes(value);
        Files.write(root.resolve(name), bytes);
        return new LifecycleTrustService.ReceiptReference(name, JsonHashes.ownHash(value), JsonHashes.sha256(bytes),
                (long) bytes.length, value.isArray() ? JsonHashes.canonicalSha256(value) : null, null);
    }

    private static ObjectNode policy() {
        ObjectNode value = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-fixed-baseline-non-trading/1").put("version", 1)
                .put("status", "FROZEN").put("policy_id", "NON_TRADING_CLOSURE_V001")
                .put("provenance", "OFFICIAL_BINANCE_NOTICE_AND_PUBLIC_MONTHLY_ARCHIVE_CHECKSUMS")
                .put("venue", "BINANCE").put("instrument", "BINANCE_SPOT")
                .put("asset_scope", "EXPLICIT_INTERVAL_ASSETS_ONLY")
                .put("interval_semantics", "[start_ms,end_ms)")
                .put("missing_bar_policy", "NO_SYNTHETIC_BARS_OR_FILLS;_RESUME_AT_FIRST_REOPENING_BAR");
        value.putArray("intervals").addObject().put("asset", "eth").put("symbol", "ETHUSDT")
                .put("venue", "BINANCE").put("instrument", "BINANCE_SPOT").put("reason", "NON_TRADING")
                .put("start_ms", 1632898800000L).put("end_ms", 1632906000000L)
                .put("notice_url", "https://www.binance.com/en/support/announcement/detail/e2f674fc961d48af9b28edd82896607c")
                .put("archive_zip_sha256", "5bbfb9f41522b2fd1472e4f4b164a5ba4711b02aab37eebe0b2b25a1286c90a9");
        value.put("content_sha256", JsonHashes.ownHash(value));
        return value;
    }
}

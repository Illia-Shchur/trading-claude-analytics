package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.marketdata.PublicDataAdapters;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Public current-catalog filtering and fixture/transport provenance boundaries. */
final class StrategyResearchDataV5CurrentDatedCatalogBoundaryTest {
    private static final String RESPONSE_SHA = "b".repeat(64);
    private static final String CAPTURED_AT = "2026-09-01T00:00:00.000Z";

    @Test
    void publicFixtureFiltersToV5UsdtQuarterliesAndBindsObservedDates() {
        ObjectNode options = fixtureOptions();
        ArrayNode rows = array();
        rows.add(contract("BTCUSDT_260925", "BTC", "CURRENT_QUARTER", "USDT", 1_780_000_000_000L, 1_790_323_200_000L));
        rows.add(contract("ETHUSDT_261225", "ETH", "NEXT_QUARTER", "USDT", 0, 0));
        rows.add(contract("DOGEUSDT_260925", "DOGE", "CURRENT_QUARTER", "USDT", 1, 2));
        rows.add(contract("SOLUSDT_260925", "SOL", "PERPETUAL", "USDT", 1, 2));
        rows.add(contract("ADAUSDC_260925", "ADA", "CURRENT_QUARTER", "USDC", 1, 2));
        options.set("exchangeInfoRows", rows);

        ObjectNode result = StrategyResearchDataV5.discoverBinanceDatedFutures(options);
        assertThat(result.path("status").asText()).isEqualTo("PUBLIC_OBSERVED");
        assertThat(result.path("contracts")).hasSize(2);
        ObjectNode first = (ObjectNode) result.path("contracts").get(0);
        assertThat(first.path("asset").asText()).isEqualTo("btc");
        assertThat(first.path("source_sha256").asText()).isEqualTo(RESPONSE_SHA);
        assertThat(first.path("onboard_at").asText()).isEqualTo(iso(1_780_000_000_000L));
        assertThat(first.path("expiry").asText()).isEqualTo(iso(1_790_323_200_000L));
        ObjectNode second = (ObjectNode) result.path("contracts").get(1);
        assertThat(second.path("asset").asText()).isEqualTo("eth");
        assertThat(second.path("onboard_at").isNull()).isTrue();
        assertThat(second.path("expiry").isNull()).isTrue();
        assertThat(result.path("limitations").toString()).contains("CURRENT_CATALOG_ONLY_HISTORICAL_EXPIRED_DATED_FUTURES_NOT_BOUND");
    }

    @Test
    void emptyCurrentCatalogDisclosesWhyNoContractIsTradeable() {
        ObjectNode options = fixtureOptions();
        options.set("exchangeInfoRows", array().add(contract("XRPUSDC_260925", "XRP", "CURRENT_QUARTER", "USDC", 1, 2)));

        ObjectNode result = StrategyResearchDataV5.discoverBinanceDatedFutures(options);
        assertThat(result.path("contracts")).isEmpty();
        assertThat(result.path("limitations").toString()).contains("CURRENT_BINANCE_USDM_DATED_FUTURES_CATALOG_EMPTY");
    }

    @Test
    void injectedExchangeInfoCannotBeUsedWithoutFixtureMode() {
        ObjectNode options = fixtureOptions().put("fixtureOnly", false);
        options.set("exchangeInfoRows", array().add(contract("BTCUSDT_260925", "BTC", "CURRENT_QUARTER", "USDT", 1, 2)));

        assertThatThrownBy(() -> StrategyResearchDataV5.discoverBinanceDatedFutures(options))
                .hasMessageContaining("injected exchange-info rows are fixture-only");
    }

    @Test
    void transportCaptureUsesObservedResponseTimeAndAdapterReceipt() {
        byte[] exchangeInfo = ("{\"symbols\":[{\"symbol\":\"BTCUSDT_260925\",\"baseAsset\":\"BTC\","
                + "\"quoteAsset\":\"USDT\",\"contractType\":\"CURRENT_QUARTER\","
                + "\"onboardDate\":1780000000000,\"deliveryDate\":1790323200000}]}" )
                .getBytes(StandardCharsets.UTF_8);
        String expectedResponseSha = StrategyResearchDataV5.hash(exchangeInfo);
        PublicDataAdapters.InjectableHttpClient transport = (uri, headers) -> new PublicDataAdapters.FetchResponse(
                200, exchangeInfo, Map.of("date", List.of("Tue, 01 Sep 2026 00:00:00 GMT")));
        ObjectNode options = object().put("fixtureOnly", false);

        ObjectNode result = StrategyResearchDataV5.discoverBinanceDatedFutures(options, transport);
        assertThat(result.path("contracts")).hasSize(1);
        assertThat(result.path("captured_at").asText()).isEqualTo(CAPTURED_AT);
        assertThat(result.path("source_sha256").asText()).isEqualTo(expectedResponseSha);
        assertThat(result.path("contracts").get(0).path("source").asText())
                .isEqualTo("binance-public-linear-exchange-info/1");
    }

    private static ObjectNode fixtureOptions() {
        return object().put("fixtureOnly", true).put("capturedAt", CAPTURED_AT)
                .put("responseSha256", RESPONSE_SHA).put("adapterId", "FIXTURE_EXCHANGE_INFO");
    }

    private static ObjectNode contract(String symbol, String asset, String type, String quote, long onboard, long expiry) {
        return object().put("symbol", symbol).put("baseAsset", asset).put("quoteAsset", quote)
                .put("contractType", type).put("onboardDate", onboard).put("deliveryDate", expiry);
    }

    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }
    private static ArrayNode array() { return JsonHashes.mapper().createArrayNode(); }
    private static String iso(long millis) { return Instant.ofEpochMilli(millis).toString().replace("Z", ".000Z"); }
}

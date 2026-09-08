package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.marketdata.PublicDataAdapters;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public historical dated-futures discovery and listing-byte custody boundaries. */
final class StrategyResearchDataV5DatedDiscoveryBoundaryTest {
    private static final String SYMBOL = "BTCUSDT_260925";
    private static final String CAPTURED_AT = "2026-10-01T00:00:00.000Z";
    private static final long FOUR_HOURS = 14_400_000L;
    private static final long START = Instant.parse("2026-09-25T00:00:00Z").toEpochMilli();
    private static final long EXPIRY = Instant.parse("2026-09-25T08:00:00Z").toEpochMilli();

    @Test
    void rawOutputRootRetainsListingBytesAndCatalogReopensThem(@TempDir Path root) {
        ObjectNode result = discover(root, (uri, headers) -> response(uri, false));
        assertThat(result.path("source").path("persistence_status").asText()).isEqualTo("RAW_RECEIPTS_BOUND");
        assertThat(result.path("source").path("raw_receipts")).hasSize(1);
        String relative = result.path("source").path("raw_receipts").get(0).path("path").asText();
        assertThat(Files.exists(root.resolve(relative))).isTrue();
        ObjectNode request = object();
        request.set("catalog", result);
        request.put("root", root.toString());
        assertThat(StrategyResearchDataV5.validateDatedFuturesCatalog(request)).isTrue();
        assertThat(result.path("contracts").get(0).path("history_status").asText())
                .isEqualTo("SIGNAL_HISTORY_AVAILABLE");
    }

    @Test
    void historyProbeFailureLeavesAnExplicitUnavailableContract(@TempDir Path root) {
        ObjectNode result = discover(root, (uri, headers) -> {
            throw new IOException("fixture probe unavailable");
        });
        ObjectNode contract = (ObjectNode) result.path("contracts").get(0);
        assertThat(contract.path("history_status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(result.path("status").asText()).isEqualTo("PUBLIC_OBSERVED_UNAVAILABLE");
        assertThat(result.path("limitations").toString()).contains("HISTORY_PROBE_FAILED");
    }

    @Test
    void discoveryRejectsMalformedListingBoundsAndNonFixtureCaptureTime() {
        ObjectNode malformed = baseRequest();
        malformed.set("listingResponses", array().add(object().put("endpoint", listingEndpoint())
                .put("body", "<NotAListBucketResult><IsTruncated>false</IsTruncated>")));
        assertThatThrownBy(() -> StrategyResearchDataV5.discoverBinanceHistoricalDatedFutures(
                malformed, (uri, headers) -> response(uri, false)))
                .hasMessageContaining("catalog response is not XML");

        ObjectNode nonFixture = baseRequest().put("fixtureOnly", false).put("capturedAt", CAPTURED_AT);
        assertThatThrownBy(() -> StrategyResearchDataV5.discoverBinanceHistoricalDatedFutures(
                nonFixture, (uri, headers) -> response(uri, false)))
                .hasMessageContaining("caller-supplied capturedAt is fixture-only");

        ObjectNode reversed = baseRequest().put("endAt", iso(START));
        assertThatThrownBy(() -> StrategyResearchDataV5.discoverBinanceHistoricalDatedFutures(
                reversed, (uri, headers) -> response(uri, false)))
                .hasMessageContaining("catalog bounds are invalid");
    }

    @Test
    void discoveryRejectsAnUnsupportedListingInventory() {
        ObjectNode request = baseRequest();
        request.set("assets", array().add("sol"));
        request.remove("listingResponses");
        assertThatThrownBy(() -> StrategyResearchDataV5.discoverBinanceHistoricalDatedFutures(
                request, (uri, headers) -> response(uri, false)))
                .hasMessageContaining("catalog has no supported asset listing responses");
    }

    private static ObjectNode discover(Path root, PublicDataAdapters.InjectableHttpClient transport) {
        ObjectNode request = baseRequest();
        request.put("rawOutputRoot", root.toString());
        return StrategyResearchDataV5.discoverBinanceHistoricalDatedFutures(request, transport);
    }

    private static ObjectNode baseRequest() {
        ObjectNode request = object().put("fixtureOnly", true).put("capturedAt", CAPTURED_AT)
                .put("startAt", iso(START)).put("endAt", iso(EXPIRY + FOUR_HOURS));
        request.set("assets", array().add("btc"));
        request.set("listingResponses", array().add(object().put("endpoint", listingEndpoint())
                .put("body", listingXml())));
        return request;
    }

    private static PublicDataAdapters.FetchResponse response(java.net.URI uri, boolean ignored) {
        byte[] body = uri.toString().contains("limit=1")
                ? kline(START, 100) : kline(EXPIRY - FOUR_HOURS, 101);
        return new PublicDataAdapters.FetchResponse(200, body,
                Map.of("date", List.of("Thu, 01 Oct 2026 00:00:00 GMT")));
    }

    private static String listingEndpoint() {
        return "https://s3-ap-northeast-1.amazonaws.com/data.binance.vision?delimiter=%2F&prefix=data%2Ffutures%2Fum%2Fmonthly%2Fklines%2FBTCUSDT_";
    }

    private static String listingXml() {
        return "<ListBucketResult><IsTruncated>false</IsTruncated><Prefix>"
                + "data/futures/um/monthly/klines/" + SYMBOL + "/</Prefix></ListBucketResult>";
    }

    private static byte[] kline(long event, double close) {
        ArrayNode row = array().add(event).add(String.valueOf(close - 1)).add(String.valueOf(close + 1))
                .add(String.valueOf(close - 2)).add(String.valueOf(close)).add("1")
                .add(event + FOUR_HOURS - 1);
        return array().add(row).toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String iso(long millis) { return Instant.ofEpochMilli(millis).toString(); }
    private static ObjectNode object() { return JsonHashes.mapper().createObjectNode(); }
    private static ArrayNode array() { return JsonHashes.mapper().createArrayNode(); }
}

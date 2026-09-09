package com.tradinganalytics.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.infrastructure.marketdata.PublicDataAdapters;
import com.tradinganalytics.marketdata.http.MarketHttpClient;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublicDataSmokeServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long NOW = 1_750_000_000_000L;

    @Test
    void productionFactoryCreatesTheRealBoundaryWithoutPerformingIo() {
        assertThat(PublicDataSmokeService.production()).isNotNull();
    }

    @Test
    void hermeticRoutingOutputIsByteIdenticalToCapturedNodeOracle() throws Exception {
        PublicDataSmokeService service = service((uri, headers) -> {
            throw new AssertionError("hermetic smoke must not access the network");
        });

        String expected;
        try (InputStream input = getClass().getResourceAsStream("/oracles/strategy-public-smoke-v1.json")) {
            assertThat(input).as("frozen public-smoke oracle").isNotNull();
            expected = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(NodePrettyJson.write(service.run(false))).isEqualTo(expected);
    }

    @Test
    void networkModeRequiresCompletedBarsFromBothPublicRoutes() {
        PublicDataSmokeService service = service((uri, headers) -> {
            String query = uri.getQuery();
            if (query != null && query.contains("XRPUSDT") && "api.binance.com".equals(uri.getHost())) {
                return response(429, "{}");
            }
            return response(200, "[[100,\"1\",\"2\",\"0.5\",\"1.5\",\"9\","
                    + (NOW - 1) + "],[200,\"1\",\"2\",\"0.5\",\"1.5\",\"9\"," + (NOW + 1) + "]]");
        });

        JsonNode output = service.run(true);
        assertThat(output.path("pass").asBoolean()).isFalse();
        assertThat(output.path("results")).hasSize(8);
        JsonNode btc = output.path("results").get(0);
        assertThat(btc.path("status").asText()).isEqualTo("OK");
        assertThat(btc.path("completed_bar_open_time").asLong()).isEqualTo(100L);
        assertThat(btc.path("completed_usdm_bar_open_time").asLong()).isEqualTo(100L);
        JsonNode xrp = output.path("results").get(4);
        assertThat(xrp.path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(xrp.path("error").asText()).isEqualTo("HTTP_429");
        assertThat(xrp.has("derivatives_route")).isFalse();
    }

    @Test
    void malformedAndFutureOnlyResponsesFailClosedPerAsset() {
        PublicDataSmokeService malformed = service((uri, headers) -> response(200, "[]"));
        JsonNode malformedRow = malformed.run(true).path("results").get(0);
        assertThat(malformedRow.path("error").asText()).isEqualTo("malformed Binance kline response");

        PublicDataSmokeService future = service((uri, headers) -> response(200,
                "[[1,0,0,0,0,0," + (NOW + 1) + "],[2,0,0,0,0,0," + (NOW + 2) + "]]"));
        JsonNode futureRow = future.run(true).path("results").get(0);
        assertThat(futureRow.path("error").asText()).isEqualTo("no completed spot bar");
    }

    @Test
    void completedBarBoundaryIsInclusiveAndRowsMustContainCloseTimeColumn() {
        PublicDataSmokeService inclusive = service((uri, headers) -> response(200,
                "[[1,0,0,0,0,0," + NOW + "],[2,0,0,0,0,0," + (NOW + 1) + "]]"));
        assertThat(inclusive.run(true).path("results").get(0).path("status").asText()).isEqualTo("OK");

        PublicDataSmokeService shortRow = service((uri, headers) -> response(200,
                "[[1,0,0,0,0,0],[2,0,0,0,0,0]]"));
        assertThat(shortRow.run(true).path("results").get(0).path("error").asText())
                .isEqualTo("no completed spot bar");
    }

    private static PublicDataSmokeService service(PublicDataAdapters.InjectableHttpClient getter) {
        MarketHttpClient http = new MarketHttpClient(getter, null, millis -> { }, JSON);
        return new PublicDataSmokeService(http, JSON, () -> NOW);
    }

    private static PublicDataAdapters.FetchResponse response(int status, String body) {
        return new PublicDataAdapters.FetchResponse(status, body.getBytes(StandardCharsets.UTF_8), Map.of());
    }
}

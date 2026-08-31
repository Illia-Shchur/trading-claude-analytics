package com.tradinganalytics.marketdata.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinganalytics.infrastructure.marketdata.PublicDataAdapters;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MarketHttpClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final URI URI_VALUE = URI.create("https://example.test/data");

    @Test
    void retriesServerFailuresButNeverRetriesClientFailures() throws Exception {
        AtomicInteger serverCalls = new AtomicInteger();
        MarketHttpClient server = client((uri, headers) -> serverCalls.incrementAndGet() < 3
                ? response(503, "down") : response(200, "{\"ok\":true}"));
        assertThat(server.getJson(URI_VALUE).path("ok").asBoolean()).isTrue();
        assertThat(serverCalls).hasValue(3);

        AtomicInteger clientCalls = new AtomicInteger();
        MarketHttpClient client = client((uri, headers) -> {
            clientCalls.incrementAndGet();
            return response(404, "missing");
        });
        assertThatThrownBy(() -> client.getJson(URI_VALUE))
                .isInstanceOf(MarketHttpClient.HttpStatusException.class)
                .hasMessageStartingWith("404 ");
        assertThat(clientCalls).hasValue(1);
    }

    @Test
    void mergesUserAgentAndCallerHeaders() throws Exception {
        MarketHttpClient client = client((uri, headers) -> {
            assertThat(headers).containsEntry("User-Agent", MarketHttpClient.USER_AGENT)
                    .containsEntry("X-Key", "secret");
            return response(200, "[]");
        });
        assertThat(client.getJson(URI_VALUE, 0, Map.of("X-Key", "secret")).isArray()).isTrue();
    }

    @Test
    void postUsesJsonContentTypeAndPropagatesStatus() throws Exception {
        MarketHttpClient.Poster poster = (uri, headers, body, timeout) -> {
            assertThat(headers).containsEntry("Content-Type", "application/json");
            assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("{\"x\":1}");
            return new MarketHttpClient.Response(200, "{\"y\":2}".getBytes(StandardCharsets.UTF_8), Map.of());
        };
        MarketHttpClient client = new MarketHttpClient((uri, headers) -> response(200, "{}"),
                poster, millis -> { }, JSON);
        assertThat(client.postJson(URI_VALUE, JSON.readTree("{\"x\":1}")).path("y").asInt()).isEqualTo(2);
    }

    private static MarketHttpClient client(PublicDataAdapters.InjectableHttpClient getter) {
        return new MarketHttpClient(getter, null, millis -> { }, JSON);
    }

    private static PublicDataAdapters.FetchResponse response(int status, String body) {
        return new PublicDataAdapters.FetchResponse(status, body.getBytes(StandardCharsets.UTF_8), Map.of());
    }
}

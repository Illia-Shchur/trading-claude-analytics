package com.tradinganalytics.research.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deterministic HTTP boundary coverage for the cache-aware historical source. */
class SwingBackfillHttpSourceCoverageTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long BAR = SwingBackfill.BAR_MS;
    private static final long DAY = SwingBackfill.DAY_MS;
    private static final long START = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli();
    private static final long END = START + DAY;

    @TempDir Path temporary;

    @Test
    void httpSourceNormalizesAllHistoricalEndpointsAndReusesEveryCache() throws Exception {
        StubHttpClient client = new StubHttpClient(false, false);
        SwingBackfill.HttpHistoricalSource source = new SwingBackfill.HttpHistoricalSource(client);
        Path cache = temporary.resolve("all-sources");

        ArrayNode spot = source.klines("eth", START, START + 2 * BAR, false, cache);
        ArrayNode futures = source.klines("eth", START, START + 2 * BAR, true, cache);
        ArrayNode funding = source.funding("eth", START, END, cache);
        ArrayNode metrics = source.metrics("eth", START, END, cache);
        ArrayNode macro = source.macro(START, END, cache);
        ArrayNode sentiment = source.sentiment(START, END, cache);
        ArrayNode valuation = source.valuation("eth", START, END, cache);

        assertThat(spot).hasSize(2);
        assertThat(spot.get(0).path("source").asText()).isEqualTo("Binance spot klines");
        assertThat(spot.get(0).path("quote_volume").asDouble()).isEqualTo(1_000);
        assertThat(spot.get(0).path("taker_sell_quote").asDouble()).isEqualTo(400);
        assertThat(spot.get(1).path("time").asLong()).isEqualTo(START + BAR);
        assertThat(futures.get(0).path("source").asText()).isEqualTo("Binance USD-M futures klines");

        assertThat(funding).hasSize(1);
        assertThat(funding.get(0).path("time").asLong()).isEqualTo(START);
        assertThat(funding.get(0).path("rate").asDouble()).isCloseTo(.001, org.assertj.core.data.Offset.offset(1e-12));

        assertThat(metrics).hasSize(1);
        assertThat(metrics.get(0).path("value").asDouble()).isEqualTo(12_345.5);
        assertThat(metrics.get(0).path("oi").asDouble()).isEqualTo(42);
        assertThat(metrics.get(0).path("top_trader_account_ratio").asDouble()).isEqualTo(1.1);
        assertThat(metrics.get(0).path("taker_long_short_ratio").asDouble()).isEqualTo(1.4);

        assertThat(macro).hasSize(1);
        assertThat(macro.get(0).path("date").asText()).isEqualTo("2026-08-01");
        assertThat(macro.get(0).path("available_at").asLong()).isEqualTo(START + 16 * 60 * 60 * 1000L + DAY);

        assertThat(sentiment).hasSize(2);
        assertThat(sentiment.get(0).path("value").asDouble()).isEqualTo(20);
        assertThat(sentiment.get(0).path("classification").asText()).isEqualTo("Fear");
        assertThat(sentiment.get(1).path("classification").isNull()).isTrue();

        assertThat(valuation).hasSize(1);
        assertThat(valuation.get(0).path("date").asText()).isEqualTo("2026-07-31");
        assertThat(valuation.get(0).path("mvrv").asDouble()).isEqualTo(1.25);

        int networkCalls = client.sendCount;
        assertThat(networkCalls).isEqualTo(7);
        assertThat(Files.exists(cache.resolve("zip-metrics-ETHUSDT-2026-08-01.zip"))).isTrue();
        client.failOnSend = true;

        assertThat(source.klines("eth", START, START + 2 * BAR, false, cache)).isEqualTo(spot);
        assertThat(source.klines("eth", START, START + 2 * BAR, true, cache)).isEqualTo(futures);
        assertThat(source.funding("eth", START, END, cache)).isEqualTo(funding);
        assertThat(source.metrics("eth", START, END, cache)).isEqualTo(metrics);
        assertThat(source.macro(START, END, cache)).isEqualTo(macro);
        assertThat(source.sentiment(START, END, cache)).isEqualTo(sentiment);
        assertThat(source.valuation("eth", START, END, cache)).isEqualTo(valuation);
        assertThat(source.valuation("sol", START, END, cache)).isEmpty();
        assertThat(client.sendCount).isEqualTo(networkCalls);
    }

    @Test
    void endpointFailuresRespectSourceSpecificContractsAndNullCacheStillWorks() throws Exception {
        StubHttpClient unavailableArchive = new StubHttpClient(true, false);
        SwingBackfill.HttpHistoricalSource source = new SwingBackfill.HttpHistoricalSource(unavailableArchive);
        assertThat(source.metrics("btc", START, END, temporary.resolve("missing-metrics"))).isEmpty();
        assertThat(unavailableArchive.sendCount).isEqualTo(1);

        StubHttpClient textFailure = new StubHttpClient(false, true);
        SwingBackfill.HttpHistoricalSource failingSource = new SwingBackfill.HttpHistoricalSource(textFailure);
        assertThatThrownBy(() -> failingSource.macro(START, END, null))
                .isInstanceOf(IOException.class).hasMessageContaining("503");

        StubHttpClient noCacheClient = new StubHttpClient(false, false);
        SwingBackfill.HttpHistoricalSource noCacheSource = new SwingBackfill.HttpHistoricalSource(noCacheClient);
        ArrayNode macro = noCacheSource.macro(START, END, null);
        assertThat(macro).hasSize(1);
        assertThat(noCacheClient.sendCount).isEqualTo(1);
    }

    private static final class StubHttpClient extends HttpClient {
        private final boolean unavailableArchive;
        private final boolean failMacro;
        private int sendCount;
        private boolean failOnSend;

        private StubHttpClient(boolean unavailableArchive, boolean failMacro) {
            this.unavailableArchive = unavailableArchive;
            this.failMacro = failMacro;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> handler)
                throws IOException {
            if (failOnSend) throw new IOException("cache miss unexpectedly reached network");
            sendCount++;
            String url = request.uri().toString();
            if (url.contains("data.binance.vision")) {
                return response(request, unavailableArchive ? 404 : 200, metricsZip());
            }
            if (url.contains("fred.stlouisfed.org")) {
                return response(request, failMacro ? 503 : 200, macroCsv());
            }
            if (url.contains("alternative.me")) return response(request, 200, sentimentJson());
            if (url.contains("community-api.coinmetrics.io")) return response(request, 200, valuationJson());
            if (url.contains("fundingRate")) return response(request, 200, fundingJson());
            if (url.contains("/fapi/v1/klines")) return response(request, 200, klineJson(true));
            if (url.contains("/api/v3/klines")) return response(request, 200, klineJson(false));
            return response(request, 404, "{}");
        }

        private static String klineJson(boolean futures) throws IOException {
            ArrayNode batch = JSON.createArrayNode();
            batch.add(kline(START, futures ? 100.1 : 100));
            batch.add(kline(START + BAR, futures ? 101.1 : 101));
            batch.add(kline(START + 2 * BAR, futures ? 102.1 : 102));
            return JSON.writeValueAsString(batch);
        }

        private static ArrayNode kline(long time, double close) {
            return JSON.createArrayNode().add(time).add(close - .5).add(close + 1)
                    .add(close - 1).add(close).add(10).add("unused").add(1_000)
                    .add("unused").add("unused").add(600).add("unused");
        }

        private static String fundingJson() throws IOException {
            ArrayNode batch = JSON.createArrayNode();
            batch.addObject().put("fundingTime", START).put("fundingRate", ".001");
            batch.addObject().put("fundingTime", START + BAR).put("fundingRate", "not-a-number");
            batch.addObject().put("fundingTime", END).put("fundingRate", ".002");
            return JSON.writeValueAsString(batch);
        }

        private static byte[] metricsZip() throws IOException {
            String csv = "create_time,sum_open_interest_value,sum_open_interest,"
                    + "count_toptrader_long_short_ratio,sum_toptrader_long_short_ratio,"
                    + "count_long_short_ratio,sum_taker_long_short_vol_ratio\n"
                    + "2026-08-01 00:00:00,12345.5,42,1.1,1.2,1.3,1.4\n"
                    + "not-a-time,broken,broken,broken,broken,broken,broken\n";
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                zip.putNextEntry(new ZipEntry("metrics.csv"));
                zip.write(csv.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return bytes.toByteArray();
        }

        private static String macroCsv() {
            return "observation_date,DTWEXBGS,DFII10\n"
                    + "2026-08-01,100.25,1.2\n"
                    + "2026-08-02,.,1.3\n"
                    + "bad-date,100,1.0\n";
        }

        private static String sentimentJson() throws IOException {
            long first = START / 1000;
            return JSON.createObjectNode().set("data", JSON.createArrayNode()
                    .add(JSON.createObjectNode().put("timestamp", Long.toString(first)).put("value", "20")
                            .put("value_classification", "Fear"))
                    .add(JSON.createObjectNode().put("timestamp", Long.toString(first + 12 * 60 * 60))
                            .put("value", "25"))).toString();
        }

        private static String valuationJson() {
            return "{\"data\":["
                    + "{\"time\":\"2026-07-31T00:00:00Z\",\"CapMVRVCur\":\"1.25\"},"
                    + "{\"time\":\"2026-08-01T00:00:00Z\",\"CapMVRVCur\":\"bad\"},"
                    + "{\"time\":\"2026-07-30T00:00:00Z\",\"CapMVRVCur\":\"2.0\"}]}";
        }

        @SuppressWarnings("unchecked")
        private static <T> HttpResponse<T> response(HttpRequest request, int status, Object body) {
            return (HttpResponse<T>) new StubResponse<>(request, status, body);
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(1)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { try { return SSLContext.getDefault(); } catch (Exception e) { throw new AssertionError(e); } }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> handler) { throw new UnsupportedOperationException(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> handler, PushPromiseHandler<T> pushPromiseHandler) { throw new UnsupportedOperationException(); }
    }

    private static final class StubResponse<T> implements HttpResponse<T> {
        private final HttpRequest request;
        private final int status;
        private final Object body;

        private StubResponse(HttpRequest request, int status, Object body) {
            this.request = request;
            this.status = status;
            this.body = body instanceof byte[] ? body : body.toString();
        }

        @SuppressWarnings("unchecked") @Override public T body() { return (T) body; }
        @Override public int statusCode() { return status; }
        @Override public HttpRequest request() { return request; }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}

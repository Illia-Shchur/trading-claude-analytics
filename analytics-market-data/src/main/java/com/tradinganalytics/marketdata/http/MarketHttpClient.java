package com.tradinganalytics.marketdata.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradinganalytics.infrastructure.marketdata.PublicDataAdapters;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Timeout/retry HTTP boundary matching the live-fetch transport contract. */
public final class MarketHttpClient {
    public static final String USER_AGENT = "Mozilla/5.0 (trading-claude-analytics toolchain)";

    @FunctionalInterface
    public interface Poster {
        Response post(URI uri, Map<String, String> headers, byte[] body, Duration timeout) throws Exception;
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public record Response(int status, byte[] body, Map<String, List<String>> headers) {
        public Response {
            body = body == null ? new byte[0] : body.clone();
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
        @Override public byte[] body() { return body.clone(); }
    }

    public static final class JdkPoster implements Poster {
        private final HttpClient client;
        public JdkPoster() {
            this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
        }
        public JdkPoster(HttpClient client) {
            this.client = client;
        }
        @Override
        public Response post(URI uri, Map<String, String> headers, byte[] body, Duration timeout) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(request::header);
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(response.statusCode(), response.body(), response.headers().map());
        }
    }

    private final PublicDataAdapters.InjectableHttpClient getter;
    private final Poster poster;
    private final Sleeper sleeper;
    private final ObjectMapper json;

    public MarketHttpClient(PublicDataAdapters.InjectableHttpClient getter, Poster poster,
                            Sleeper sleeper, ObjectMapper json) {
        this.getter = getter == null ? new PublicDataAdapters.JdkInjectableHttpClient() : getter;
        this.poster = poster == null ? new JdkPoster() : poster;
        this.sleeper = sleeper == null ? Thread::sleep : sleeper;
        this.json = json == null ? new ObjectMapper() : json;
    }

    public static MarketHttpClient production() {
        return new MarketHttpClient(null, null, null, null);
    }

    public JsonNode getJson(URI uri) throws IOException {
        return getJson(uri, 2, Map.of());
    }

    public JsonNode getJson(URI uri, int retries, Map<String, String> headers) throws IOException {
        return parseJson(getBytes(uri, retries, headers));
    }

    public byte[] getBytes(URI uri, int retries, Map<String, String> headers) throws IOException {
        Exception last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                Map<String, String> merged = new LinkedHashMap<>();
                merged.put("User-Agent", USER_AGENT);
                if (headers != null) merged.putAll(headers);
                PublicDataAdapters.FetchResponse response = getter.fetch(uri, merged);
                if (response.status() >= 200 && response.status() < 300) return response.body();
                HttpStatusException failure = new HttpStatusException(response.status(), uri);
                if (response.status() < 500) throw failure;
                last = failure;
            } catch (HttpStatusException exception) {
                if (exception.status() < 500) throw exception;
                last = exception;
            } catch (Exception exception) {
                last = exception;
            }
            if (attempt < retries) {
                try {
                    sleeper.sleep(300L * (attempt + 1));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("HTTP retry interrupted", exception);
                }
            }
        }
        if (last instanceof IOException io) throw io;
        throw new IOException(last == null ? "HTTP request failed: " + uri : last.getMessage(), last);
    }

    public JsonNode postJson(URI uri, JsonNode body) throws IOException {
        try {
            Map<String, String> headers = Map.of(
                    "User-Agent", USER_AGENT,
                    "Content-Type", "application/json");
            Response response = poster.post(uri, headers, json.writeValueAsBytes(body), Duration.ofSeconds(15));
            if (response.status() < 200 || response.status() >= 300) {
                throw new HttpStatusException(response.status(), uri);
            }
            return parseJson(response.body());
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException(exception.getMessage(), exception);
        }
    }

    public String getText(URI uri) throws IOException {
        return new String(getBytes(uri, 0, Map.of()), java.nio.charset.StandardCharsets.UTF_8);
    }

    private JsonNode parseJson(byte[] bytes) throws IOException {
        return json.readTree(bytes);
    }

    public static final class HttpStatusException extends IOException {
        private final int status;
        public HttpStatusException(int status, URI uri) {
            super(status + " " + uri);
            this.status = status;
        }
        public int status() { return status; }
    }
}

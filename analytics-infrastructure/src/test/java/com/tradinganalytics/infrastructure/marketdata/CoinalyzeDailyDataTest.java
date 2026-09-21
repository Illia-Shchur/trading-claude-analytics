package com.tradinganalytics.infrastructure.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CoinalyzeDailyDataTest {
    @TempDir Path temporary;

    @Test
    void qualifiesExactBinanceStablePerpsAndKeepsGapsAndOpenDayOutOfRows() {
        LocalDate from = LocalDate.parse("2024-01-01");
        Instant asOf = Instant.parse("2024-01-03T00:00:00Z");
        ObjectNode history = object("symbol", "BTCUSDT_PERP.A");
        ArrayNode points = history.putArray("history");
        point(points, from, 12.5, 4.25);
        point(points, from.plusDays(2), 99, 88); // the UTC day that is still open at asOf

        ObjectNode result = CoinalyzeDailyData.qualify(
                exchanges(), markets(), Map.of("BTCUSDT_PERP.A", arrayOf(history)),
                from, from.plusDays(4), asOf);

        assertThat(result.path("pit_verified").asBoolean()).isFalse();
        assertThat(result.path("symbols").path("BTC").path("symbol").asText()).isEqualTo("BTCUSDT_PERP.A");
        assertThat(result.path("rows").size()).isEqualTo(1);
        JsonNode row = result.path("rows").get(0);
        assertThat(row.path("asset").asText()).isEqualTo("BTC");
        assertThat(row.path("day_start_utc").asText()).isEqualTo("2024-01-01T00:00:00Z");
        assertThat(row.path("long_liquidations_usd").asDouble()).isEqualTo(12.5);
        assertThat(row.path("short_liquidations_usd").asDouble()).isEqualTo(4.25);
        assertThat(result.path("coverage").path("by_asset").path("BTC").path("expected_days").asInt()).isEqualTo(2);
        assertThat(result.path("coverage").path("by_asset").path("BTC").path("observed_days").asInt()).isEqualTo(1);
        assertThat(result.path("coverage").path("by_asset").path("BTC").path("missing_days").asInt()).isEqualTo(1);
        assertThat(result.path("coverage").path("by_asset").path("ETH").path("observed_days").asInt()).isZero();
        assertThat(result.path("rows").toString()).doesNotContain("ETH");
    }

    @Test
    void rejectsDuplicateNonMidnightNegativeNonfiniteAndOutOfRangeRows() {
        LocalDate from = LocalDate.parse("2024-01-01");
        Instant asOf = Instant.parse("2024-01-05T00:00:00Z");
        assertInvalidHistory(from, asOf, List.of(pointNode(from, 1, 2), pointNode(from, 1, 2)),
                "duplicate Coinalyze symbol/day");
        assertInvalidHistory(from, asOf, List.of(timestampNode(from.atStartOfDay(ZoneOffset.UTC)
                .toInstant().plusSeconds(60).getEpochSecond(), 1, 2)), "not UTC midnight");
        assertInvalidHistory(from, asOf, List.of(pointNode(from, -1, 2)), "finite and nonnegative");
        assertInvalidHistory(from, asOf, List.of(pointNode(from, Double.NaN, 2)), "finite and nonnegative");
        assertInvalidHistory(from, asOf, List.of(pointNode(from.minusDays(1), 1, 2)), "outside the requested date range");
    }

    @Test
    void rejectsAmbiguousMarketsButLeavesUnavailableSymbolsExplicitlyMissing() {
        JsonNode ambiguous = JsonHashes.parse(("[" + market("AAVE", "AAVEUSDT_PERP.A") + ","
                + market("AAVE", "AAVEUSDT_PERP.A2") + "]").getBytes(StandardCharsets.UTF_8), "fixture");
        assertThatThrownBy(() -> CoinalyzeDailyData.qualify(exchanges(), ambiguous, Map.of(),
                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-01-02"),
                Instant.parse("2024-01-03T00:00:00Z")))
                .hasMessageContaining("ambiguous for AAVE");

        JsonNode onlyBtc = JsonHashes.parse(("[" + market("BTC", "BTCUSDT_PERP.A") + "]")
                .getBytes(StandardCharsets.UTF_8), "fixture");
        ObjectNode result = CoinalyzeDailyData.qualify(exchanges(), onlyBtc, Map.of(),
                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-01-02"),
                Instant.parse("2024-01-03T00:00:00Z"));
        assertThat(result.path("symbols").path("AAVE").isNull()).isTrue();
        assertThat(result.path("coverage").path("by_asset").path("AAVE").path("observed_days").asInt()).isZero();
        assertThat(result.path("coverage").path("by_asset").path("AAVE").path("reason").asText())
                .isEqualTo("binance_stable_usdt_perpetual_not_listed");
    }

    @Test
    void acquiresHeaderOnlyRetriesAndReopensVerifiedImmutableRawReceipts() throws Exception {
        String key = "test-secret-never-write";
        List<URI> uris = new ArrayList<>();
        List<Map<String, String>> headers = new ArrayList<>();
        List<Long> sleeps = new ArrayList<>();
        List<PublicDataAdapters.FetchResponse> responses = new ArrayList<>();
        responses.add(new PublicDataAdapters.FetchResponse(429, new byte[0], Map.of("Retry-After", List.of("2"))));
        responses.add(response("[{\"name\":\"Binance\",\"code\":\"A\"}]"));
        responses.add(response(markets().toString()));
        responses.add(response("[{\"symbol\":\"BTCUSDT_PERP.A\",\"history\":["
                + "{\"t\":1704067200,\"l\":12.5,\"s\":4.25}]}]"));
        PublicDataAdapters.InjectableHttpClient client = (uri, sentHeaders) -> {
            uris.add(uri);
            headers.add(sentHeaders);
            return responses.remove(0);
        };
        Path root = temporary.resolve("receipt");
        ObjectNode manifest = CoinalyzeDailyData.acquire(new CoinalyzeDailyData.Options(
                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-01-04"),
                Instant.parse("2024-01-04T00:00:00Z"), root, key, client,
                sleeps::add, () -> 0L));

        assertThat(sleeps).containsExactly(2_000L);
        assertThat(uris).hasSize(4);
        assertThat(uris).allSatisfy(uri -> {
            assertThat(uri.getHost()).isEqualTo("api.coinalyze.net");
            assertThat(uri.toString()).doesNotContain(key);
        });
        assertThat(headers).allSatisfy(sent -> assertThat(sent).containsEntry("api_key", key));
        assertThat(uris.get(3).getRawQuery()).contains("interval=daily", "convert_to_usd=true");
        assertThat(manifest.path("diagnostic").asBoolean()).isTrue();
        assertThat(manifest.path("pit_verified").asBoolean()).isFalse();
        assertThat(manifest.path("raw_responses").size()).isEqualTo(3);
        assertThat(Files.exists(root.resolve("raw/exchanges.json"))).isTrue();
        assertThat(Files.exists(root.resolve("raw/future-markets.json"))).isTrue();
        assertThat(Files.exists(root.resolve("raw/liquidation-history-2024.json"))).isTrue();
        assertThat(Files.readString(root.resolve(CoinalyzeDailyData.MANIFEST_NAME))).doesNotContain(key);
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try { assertThat(Files.readString(path)).doesNotContain(key); }
                catch (Exception error) { throw new AssertionError(error); }
            });
        }

        ObjectNode reopened = CoinalyzeDailyData.reopenAndQualify(root);
        assertThat(reopened.path("rows")).isEqualTo(manifest.path("rows"));
        assertThat(reopened.path("rows").size()).isEqualTo(1);
        assertThat(reopened.path("raw_responses").size()).isEqualTo(3);
        assertThatThrownBy(() -> CoinalyzeDailyData.acquire(new CoinalyzeDailyData.Options(
                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-01-02"),
                Instant.parse("2024-01-03T00:00:00Z"), root, key, client, sleeps::add, () -> 0L)))
                .hasMessageContaining("not empty; refusing overwrite");

        Files.writeString(root.resolve("raw/exchanges.json"), "[]");
        assertThatThrownBy(() -> CoinalyzeDailyData.reopenAndQualify(root))
                .hasMessageContaining("SHA-256 mismatch");
    }

    @Test
    void slidingRateLimiterAccountsForSymbolsAndWaitsAtFortyPerMinute() throws Exception {
        AtomicLong nanos = new AtomicLong();
        List<Long> sleeps = new ArrayList<>();
        PublicDataAdapters.InjectableHttpClient client = (uri, headers) -> {
            String path = uri.getPath();
            if (path.endsWith("/exchanges")) return response("[{\"name\":\"Binance\",\"code\":\"A\"}]");
            if (path.endsWith("/future-markets")) return response(markets().toString());
            return response("[]");
        };
        CoinalyzeDailyData.Sleeper clockedSleeper = millis -> {
            sleeps.add(millis);
            nanos.addAndGet(millis * 1_000_000L);
        };
        CoinalyzeDailyData.acquire(new CoinalyzeDailyData.Options(
                LocalDate.parse("2010-01-01"), LocalDate.parse("2020-01-01"),
                Instant.parse("2020-01-01T00:00:00Z"), temporary.resolve("rate-limit"),
                "key", client, clockedSleeper, nanos::get));
        assertThat(sleeps).contains(60_001L);
    }

    @Test
    void validatesOptionsAndRedactsCredentialsFromOptionAndTransportErrors() throws Exception {
        LocalDate from = LocalDate.parse("2024-01-01");
        LocalDate to = from.plusDays(1);
        Instant asOf = Instant.parse("2024-01-02T00:00:00Z");
        Path root = temporary.resolve("options");
        assertThatThrownBy(() -> new CoinalyzeDailyData.Options(null, to, asOf, root,
                "key", null, null, null)).hasMessageContaining("requires from, to, as-of, and root");
        assertThatThrownBy(() -> new CoinalyzeDailyData.Options(from, from, asOf, root,
                "key", null, null, null)).hasMessageContaining("must be before exclusive --to");
        assertThatThrownBy(() -> new CoinalyzeDailyData.Options(from, to, asOf, root,
                null, null, null, null)).hasMessageContaining("COINALYZE_API_KEY is required");
        assertThatThrownBy(() -> new CoinalyzeDailyData.Options(from, to, asOf, root,
                "   ", null, null, null)).hasMessageContaining("COINALYZE_API_KEY is required");

        var defaults = new CoinalyzeDailyData.Options(from, to, asOf, root,
                "private-token", null, null, null);
        assertThat(defaults.client()).isNotNull();
        assertThat(defaults.sleeper()).isNotNull();
        assertThat(defaults.nanoTime()).isNotNull();
        assertThat(defaults.toString()).doesNotContain("private-token").contains("[redacted]");

        assertThatThrownBy(() -> CoinalyzeDailyData.acquire(null))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        String key = "private-transport-token";
        PublicDataAdapters.InjectableHttpClient leakingClient = (uri, headers) -> {
            throw new IOException("transport diagnostics included " + key);
        };
        assertThatThrownBy(() -> CoinalyzeDailyData.acquire(new CoinalyzeDailyData.Options(
                from, to, asOf, temporary.resolve("transport-failure"), key,
                leakingClient, millis -> {}, () -> 0L)))
                .hasMessageNotContaining(key).hasMessageContaining("[redacted]").hasNoCause();
    }

    @Test
    void failsClosedForAuthenticationRedirectBadJsonAndCredentialEcho() {
        String key = "private-api-token";
        LocalDate from = LocalDate.parse("2024-01-01");
        LocalDate to = from.plusDays(1);
        Instant asOf = Instant.parse("2024-01-02T00:00:00Z");
        assertAcquisitionError("401", new PublicDataAdapters.FetchResponse(401,
                ("rejected " + key).getBytes(StandardCharsets.UTF_8), Map.of()), key,
                "returned HTTP 401");
        assertAcquisitionError("redirect", new PublicDataAdapters.FetchResponse(302,
                new byte[0], Map.of()), key, "refusing to forward API key");
        assertAcquisitionError("bad-json", response("not-json"), key, "returned invalid JSON");
        assertAcquisitionError("echo", response("{\"credential\":\"" + key + "\"}"), key,
                "unexpectedly contains credential material");

        for (String retryAfter : List.of("bad", "-1", "901")) {
            List<Long> sleeps = new ArrayList<>();
            PublicDataAdapters.InjectableHttpClient rateLimited = (uri, headers) ->
                    new PublicDataAdapters.FetchResponse(429, new byte[0], Map.of("Retry-After", List.of(retryAfter)));
            assertThatThrownBy(() -> CoinalyzeDailyData.acquire(new CoinalyzeDailyData.Options(
                    from, to, asOf, temporary.resolve("retry-invalid-" + retryAfter), key,
                    rateLimited, sleeps::add, () -> 0L)))
                    .hasMessageContaining("Retry-After value is invalid or exceeds 900 seconds")
                    .hasMessageNotContaining(key);
            assertThat(sleeps).isEmpty();
        }
        PublicDataAdapters.InjectableHttpClient noRetryAfter = (uri, headers) ->
                new PublicDataAdapters.FetchResponse(429, new byte[0], Map.of());
        assertThatThrownBy(() -> CoinalyzeDailyData.acquire(new CoinalyzeDailyData.Options(
                from, to, asOf, temporary.resolve("retry-missing"), key, noRetryAfter,
                millis -> {}, () -> 0L))).hasMessageContaining("omitted Retry-After");

        AtomicLong calls = new AtomicLong();
        List<Long> sleeps = new ArrayList<>();
        PublicDataAdapters.InjectableHttpClient always429 = (uri, headers) -> {
            calls.incrementAndGet();
            return new PublicDataAdapters.FetchResponse(429, new byte[0], Map.of("Retry-After", List.of("0")));
        };
        assertThatThrownBy(() -> CoinalyzeDailyData.acquire(new CoinalyzeDailyData.Options(
                from, to, asOf, temporary.resolve("retry-exhausted"), key, always429,
                sleeps::add, () -> 0L))).hasMessageContaining("retry limit exceeded");
        assertThat(calls.get()).isEqualTo(4);
        assertThat(sleeps).containsExactly(1L, 1L, 1L);
    }

    @Test
    void reopensRawReceiptsAndRejectsForgedRowsCoverageSymbolsAndReceiptMetadata() throws Exception {
        assertReopenRejected("forged-rows", manifest -> ((ObjectNode) manifest.withArray("rows").get(0))
                .put("long_liquidations_usd", 999),
                "normalized rows do not match");
        assertReopenRejected("forged-coverage", manifest -> ((ObjectNode) manifest.path("coverage")
                .path("by_asset").path("BTC")).put("missing_days", 99),
                "coverage does not match");
        assertReopenRejected("forged-symbols", manifest -> ((ObjectNode) manifest.path("symbols").path("BTC"))
                .put("symbol", "BTCUSD_PERP.A"),
                "symbol mapping does not match");
        assertReopenRejected("receipt-status", manifest -> ref(manifest, 0).put("status", 201),
                "receipt metadata mismatch");
        assertReopenRejected("receipt-bytes", manifest -> ref(manifest, 0).put("bytes", 100),
                "receipt metadata mismatch");
        assertReopenRejected("receipt-endpoint", manifest -> ref(manifest, 0).put("endpoint", "future-markets"),
                "endpoint/kind mismatch");
        assertReopenRejected("receipt-time", manifest -> ref(manifest, 0).put("captured_at", "yesterday"),
                "captured_at is invalid");
        assertReopenRejected("catalogue-request", manifest -> ref(manifest, 0).putObject("request").put("api", "x"),
                "catalogue request metadata is invalid");
        assertReopenRejected("catalogue-request-not-object", manifest -> ref(manifest, 0)
                .putArray("request").add("unexpected"), "catalogue request metadata is invalid");
        assertReopenRejected("traversal", manifest -> ref(manifest, 0).put("path", "../outside.json"),
                "path escapes acquisition root");
        assertReopenRejected("unnormalized", manifest -> ref(manifest, 0).put("path", "raw/.."),
                "path escapes acquisition root");
        assertReopenRejected("wrong-directory", manifest -> ref(manifest, 0).put("path", "elsewhere/file.json"),
                "path escapes acquisition root");
        assertReopenRejected("extra-depth", manifest -> ref(manifest, 0).put("path", "raw/sub/file.json"),
                "path escapes acquisition root");
        assertReopenRejected("absolute-path", manifest -> ref(manifest, 0)
                .put("path", temporary.resolve("outside.json").toAbsolutePath().toString()),
                "path escapes acquisition root");
        assertReopenRejected("bad-interval", manifest -> ((ObjectNode) ref(manifest, 2).path("request"))
                .put("interval", "1hour"), "not daily USD data");
        assertReopenRejected("bad-usd-mode", manifest -> ((ObjectNode) ref(manifest, 2).path("request"))
                .put("convert_to_usd", "false"), "not daily USD data");
        assertReopenRejected("missing-symbols", manifest -> ((ObjectNode) ref(manifest, 2).path("request"))
                .remove("symbols"), "request symbols are missing");
        assertReopenRejected("incomplete-symbols", manifest -> ((ArrayNode) ref(manifest, 2)
                .path("request").path("symbols")).remove(1), "does not match qualified symbols");
        assertReopenRejected("empty-symbols", manifest -> ((ArrayNode) ref(manifest, 2)
                .path("request").path("symbols")).removeAll(), "request symbols are missing");
        assertReopenRejected("duplicate-request-symbol", manifest -> ((ArrayNode) ref(manifest, 2)
                .path("request").path("symbols")).add("BTCUSDT_PERP.A"), "request symbols are invalid");
        assertReopenRejected("blank-request-symbol", manifest -> ((ArrayNode) ref(manifest, 2)
                .path("request").path("symbols")).set(0, JsonHashes.mapper().getNodeFactory().textNode(" ")),
                "request symbols are invalid");
        assertReopenRejected("nontext-request-symbol", manifest -> ((ArrayNode) ref(manifest, 2)
                .path("request").path("symbols")).set(0, JsonHashes.mapper().getNodeFactory().numberNode(1)),
                "request symbols are invalid");
        assertReopenRejected("request-from-string", manifest -> ((ObjectNode) ref(manifest, 2)
                .path("request")).put("from", "1704067200"), "field from must be an integer");
        assertReopenRejected("request-from-nonmidnight", manifest -> ((ObjectNode) ref(manifest, 2)
                .path("request")).put("from", 1704067201L), "bounds are invalid");
        assertReopenRejected("request-to-nonmidnight", manifest -> ((ObjectNode) ref(manifest, 2)
                .path("request")).put("to", 1704326401L), "bounds are invalid");
        assertReopenRejected("request-to-before-from", manifest -> ((ObjectNode) ref(manifest, 2)
                .path("request")).put("to", 1703980800L), "bounds are invalid");
        assertReopenRejected("request-crosses-year", manifest -> ((ObjectNode) ref(manifest, 2)
                .path("request")).put("from", 1703980800L), "not bounded to one year");
        assertReopenRejected("request-out-of-window", manifest -> ((ObjectNode) ref(manifest, 2)
                .path("request")).put("from", 1704412800L).put("to", 1704499200L),
                "exceeds the closed acquisition window");
        assertReopenRejected("manifest-open-day", manifest -> manifest.put("as_of", "2024-01-01T00:00:00Z"),
                "exceeds the closed acquisition window");
        assertReopenRejected("manifest-empty-window", manifest -> manifest.put("to_exclusive", "2024-01-01"),
                "manifest window is invalid");
        assertReopenRejected("manifest-bad-date", manifest -> manifest.put("from_inclusive", "today"),
                "from_inclusive is invalid");
        assertReopenRejected("manifest-bad-as-of", manifest -> manifest.put("as_of", "yesterday"),
                "as_of is invalid");
        assertReopenRejected("manifest-wrong-schema", manifest -> manifest.put("schema", "other/1"),
                "unsupported or non-diagnostic");
        assertReopenRejected("manifest-not-immutable", manifest -> manifest.put("immutable", false),
                "unsupported or non-diagnostic");
        assertReopenRejected("manifest-not-diagnostic", manifest -> manifest.put("diagnostic", false),
                "unsupported or non-diagnostic");
        assertReopenRejected("manifest-claims-pit", manifest -> manifest.put("pit_verified", true),
                "unsupported or non-diagnostic");
    }

    @Test
    void reopenerRejectsUnsafeFilesAndOutOfOrderOrMissingRawReferences() throws Exception {
        Path missing = acquireFixture("missing-raw");
        Files.delete(missing.resolve("raw/exchanges.json"));
        assertThatThrownBy(() -> CoinalyzeDailyData.reopenAndQualify(missing))
                .hasMessageContaining("missing or unsafe");

        Path symlinked = acquireFixture("symlink-raw");
        Path outside = temporary.resolve("outside-exchanges.json");
        Files.writeString(outside, "[]");
        Files.delete(symlinked.resolve("raw/exchanges.json"));
        Files.createSymbolicLink(symlinked.resolve("raw/exchanges.json"), outside);
        assertThatThrownBy(() -> CoinalyzeDailyData.reopenAndQualify(symlinked))
                .hasMessageContaining("missing or unsafe");

        Path symlinkedDirectory = acquireFixture("symlink-raw-directory");
        Files.move(symlinkedDirectory.resolve("raw"), symlinkedDirectory.resolve("raw-files"));
        Files.createSymbolicLink(symlinkedDirectory.resolve("raw"), symlinkedDirectory.resolve("raw-files"));
        assertThatThrownBy(() -> CoinalyzeDailyData.reopenAndQualify(symlinkedDirectory))
                .hasMessageContaining("missing or unsafe");

        assertReopenRejected("history-before-catalogue", manifest -> {
            ArrayNode refs = (ArrayNode) manifest.path("raw_responses");
            JsonNode history = refs.remove(2);
            refs.insert(0, history);
        }, "precedes its catalogues");
        assertReopenRejected("duplicate-exchanges", manifest -> {
            ArrayNode refs = (ArrayNode) manifest.path("raw_responses");
            refs.add(refs.get(0).deepCopy());
        }, "duplicate Coinalyze exchanges receipt");
        assertReopenRejected("no-catalogues", manifest -> manifest.putArray("raw_responses"),
                "lacks required catalogues");
        assertReopenRejected("ref-array-type", manifest -> manifest.set("raw_responses", JsonHashes.mapper().createObjectNode()),
                "raw_responses must be an array");

        Path manifestLinkRoot = acquireFixture("symlink-manifest");
        Path link = manifestLinkRoot.resolve("manifest-link.json");
        Files.createSymbolicLink(link, manifestLinkRoot.resolve(CoinalyzeDailyData.MANIFEST_NAME));
        assertThatThrownBy(() -> CoinalyzeDailyData.reopenAndQualify(link))
                .hasMessageContaining("manifest must not be a symlink");

        Path rootLinkTarget = acquireFixture("symlink-root-target");
        Path rootLink = temporary.resolve("symlink-root");
        Files.createSymbolicLink(rootLink, rootLinkTarget);
        assertThatThrownBy(() -> CoinalyzeDailyData.reopenAndQualify(rootLink.resolve(CoinalyzeDailyData.MANIFEST_NAME)))
                .hasMessageContaining("manifest root is invalid");
    }

    @Test
    void qualificationCoversEmptyAndCompleteWindowsAndRejectsMismatchedInputs() {
        LocalDate from = LocalDate.parse("2024-01-01");
        ObjectNode history = object("symbol", "BTCUSDT_PERP.A");
        ArrayNode points = history.putArray("history");
        point(points, from, 1, 2);
        point(points, from.plusDays(1), 3, 4);
        ObjectNode complete = CoinalyzeDailyData.qualify(exchanges(), markets(),
                Map.of("BTCUSDT_PERP.A", arrayOf(history)), from, from.plusDays(3),
                Instant.parse("2024-01-03T00:00:00Z"));
        JsonNode btc = complete.path("coverage").path("by_asset").path("BTC");
        assertThat(btc.path("status").asText()).isEqualTo("COMPLETE");
        assertThat(btc.path("complete").asBoolean()).isTrue();
        assertThat(btc.path("missing_day_ranges").size()).isZero();

        ObjectNode noClosedDays = CoinalyzeDailyData.qualify(exchanges(), markets(), Map.of(),
                from, from.plusDays(2), Instant.parse("2023-12-31T23:59:59Z"));
        assertThat(noClosedDays.path("coverage").path("by_asset").path("BTC").path("status").asText())
                .isEqualTo("NO_CLOSED_DAYS");

        assertThatThrownBy(() -> CoinalyzeDailyData.qualify(exchanges(), markets(), Map.of(), null,
                from.plusDays(1), Instant.parse("2024-01-03T00:00:00Z")))
                .hasMessageContaining("invalid Coinalyze qualification window");
        assertThatThrownBy(() -> CoinalyzeDailyData.qualify(exchanges(), markets(),
                Map.of("OTHER_PERP.A", arrayOf(history)), from, from.plusDays(1),
                Instant.parse("2024-01-03T00:00:00Z")))
                .hasMessageContaining("does not match resolved market");
        assertThatThrownBy(() -> CoinalyzeDailyData.qualify(JsonHashes.mapper().createObjectNode(), markets(),
                Map.of(), from, from.plusDays(1), Instant.parse("2024-01-03T00:00:00Z")))
                .hasMessageContaining("catalogues must be JSON arrays");
        assertThatThrownBy(() -> CoinalyzeDailyData.qualify(exchanges(), JsonHashes.mapper().createObjectNode(),
                Map.of(), from, from.plusDays(1), Instant.parse("2024-01-03T00:00:00Z")))
                .hasMessageContaining("catalogues must be JSON arrays");
        assertThatThrownBy(() -> CoinalyzeDailyData.qualify(arrayOf(object("name", "Other")), markets(),
                Map.of(), from, from.plusDays(1), Instant.parse("2024-01-03T00:00:00Z")))
                .hasMessageContaining("Binance exchange is missing");
        assertThatThrownBy(() -> CoinalyzeDailyData.qualify(arrayOf(object("name", "Binance")), markets(),
                Map.of(), from, from.plusDays(1), Instant.parse("2024-01-03T00:00:00Z")))
                .hasMessageContaining("field code is missing");
        assertThatThrownBy(() -> CoinalyzeDailyData.qualify(JsonHashes.parse(
                        "[{\"name\":\"Binance\",\"code\":\"A\"},{\"name\":\"BINANCE\",\"code\":\"B\"}]"
                                .getBytes(StandardCharsets.UTF_8), "fixture"), markets(), Map.of(), from,
                from.plusDays(1), Instant.parse("2024-01-03T00:00:00Z")))
                .hasMessageContaining("Binance exchange is ambiguous");
    }

    private void assertAcquisitionError(String name, PublicDataAdapters.FetchResponse response,
                                        String key, String message) {
        PublicDataAdapters.InjectableHttpClient client = (uri, headers) -> response;
        assertThatThrownBy(() -> CoinalyzeDailyData.acquire(new CoinalyzeDailyData.Options(
                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-01-02"),
                Instant.parse("2024-01-02T00:00:00Z"), temporary.resolve("http-" + name),
                key, client, millis -> {}, () -> 0L)))
                .hasMessageContaining(message).hasMessageNotContaining(key);
    }

    private void assertReopenRejected(String name, Consumer<ObjectNode> edit, String message) throws Exception {
        Path root = acquireFixture(name);
        rewriteManifest(root, edit);
        assertThatThrownBy(() -> CoinalyzeDailyData.reopenAndQualify(root))
                .hasMessageContaining(message);
    }

    private Path acquireFixture(String name) throws IOException {
        PublicDataAdapters.InjectableHttpClient client = (uri, headers) -> switch (uri.getPath()) {
            case "/v1/exchanges" -> response(exchanges().toString());
            case "/v1/future-markets" -> response(markets().toString());
            default -> response("[{\"symbol\":\"BTCUSDT_PERP.A\",\"history\":["
                    + "{\"t\":1704067200,\"l\":12.5,\"s\":4.25}]}]");
        };
        Path root = temporary.resolve("forged-" + name);
        CoinalyzeDailyData.acquire(new CoinalyzeDailyData.Options(
                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-01-04"),
                Instant.parse("2024-01-04T00:00:00Z"), root, "fixture-key",
                client, millis -> {}, () -> 0L));
        return root;
    }

    private static void rewriteManifest(Path root, Consumer<ObjectNode> edit) throws IOException {
        Path path = root.resolve(CoinalyzeDailyData.MANIFEST_NAME);
        ObjectNode manifest = (ObjectNode) JsonHashes.parse(Files.readAllBytes(path), "manifest fixture");
        edit.accept(manifest);
        manifest.remove("content_sha256");
        manifest.put("content_sha256", JsonHashes.canonicalSha256(manifest));
        Files.write(path, JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
    }

    private static ObjectNode ref(ObjectNode manifest, int index) {
        return (ObjectNode) manifest.path("raw_responses").get(index);
    }

    private void assertInvalidHistory(LocalDate from, Instant asOf, List<JsonNode> rows, String message) {
        ObjectNode history = object("symbol", "BTCUSDT_PERP.A");
        ArrayNode points = history.putArray("history");
        rows.forEach(points::add);
        assertThatThrownBy(() -> CoinalyzeDailyData.qualify(exchanges(), markets(),
                Map.of("BTCUSDT_PERP.A", arrayOf(history)), from, from.plusDays(4), asOf))
                .hasMessageContaining(message);
    }

    private static JsonNode pointNode(LocalDate day, double longs, double shorts) {
        return timestampNode(day.atStartOfDay(ZoneOffset.UTC).toEpochSecond(), longs, shorts);
    }

    private static JsonNode timestampNode(long timestamp, double longs, double shorts) {
        ObjectNode node = JsonHashes.mapper().createObjectNode();
        node.put("t", timestamp);
        node.put("l", longs);
        node.put("s", shorts);
        return node;
    }

    private static void point(ArrayNode target, LocalDate day, double longs, double shorts) {
        target.add(pointNode(day, longs, shorts));
    }

    private static ObjectNode object(String key, String value) {
        ObjectNode node = JsonHashes.mapper().createObjectNode();
        node.put(key, value);
        return node;
    }

    private static JsonNode arrayOf(JsonNode item) {
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        result.add(item);
        return result;
    }

    private static JsonNode exchanges() {
        ObjectNode exchange = object("name", "Binance");
        exchange.put("code", "A");
        return arrayOf(exchange);
    }

    private static JsonNode markets() {
        ArrayNode result = JsonHashes.mapper().createArrayNode();
        for (String asset : List.of("BTC", "ETH", "SOL", "AAVE")) {
            result.add(JsonHashes.parse(market(asset, asset + "USDT_PERP.A")
                    .getBytes(StandardCharsets.UTF_8), "market"));
        }
        result.add(JsonHashes.parse(marketWith("BTC", "BTCUSD_PERP.A", "USD", true, "STABLE")
                .getBytes(StandardCharsets.UTF_8), "market"));
        return result;
    }

    private static String market(String asset, String symbol) {
        return marketWith(asset, symbol, "USDT", true, "STABLE");
    }

    private static String marketWith(String asset, String symbol, String quote, boolean perpetual, String margined) {
        return "{\"symbol\":\"" + symbol + "\",\"exchange\":\"A\","
                + "\"symbol_on_exchange\":\"" + asset + quote + "\",\"base_asset\":\"" + asset + "\","
                + "\"quote_asset\":\"" + quote + "\",\"is_perpetual\":" + perpetual
                + ",\"margined\":\"" + margined + "\",\"oi_lq_vol_denominated_in\":\"BASE_ASSET\"}";
    }

    private static PublicDataAdapters.FetchResponse response(String body) {
        return new PublicDataAdapters.FetchResponse(200, body.getBytes(StandardCharsets.UTF_8), Map.of());
    }
}

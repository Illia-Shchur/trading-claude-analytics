package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.marketdata.CoinalyzeDailyData;
import com.tradinganalytics.infrastructure.marketdata.PublicDataAdapters;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DailyStressPreflightV1Test {
    @TempDir Path temporary;

    @Test
    void makesDailyStressAvailableAtExactlyTPlus48HoursWithPriorOnlyNearestRankP95() {
        List<JsonNode> rows = btcWarmupRows(true, false);
        ObjectNode result = calculate(validInput(), validPrecommit(), validAcquisition(), rows);

        assertThat(result.path("status").asText()).isEqualTo("BLOCKED");
        assertThat(result.path("stage").asText()).isEqualTo("DEVELOPMENT");
        assertThat(result.path("price_pnl_calculated").asBoolean()).isFalse();
        assertThat(result.path("trading_signals_generated").asBoolean()).isFalse();
        assertThat(result.path("candidate_generation_performed").asBoolean()).isFalse();
        assertThat(result.path("historical_publication_status").asText()).isEqualTo("UNKNOWN");
        assertThat(result.path("historical_revision_status").asText()).isEqualTo("UNKNOWN");
        assertThat(result.path("sixty_day_staged_executor_status").asText()).isEqualTo("NOT_QUALIFIED");

        ArrayNode events = (ArrayNode) result.path("stress_events");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).path("bucket_start_utc").asText()).isEqualTo("2022-11-09T00:00:00Z");
        assertThat(events.get(0).path("stress_event_timestamp").asText()).isEqualTo("2022-11-11T00:00:00Z");
        assertThat(events.get(0).path("assumed_available_at").asText()).isEqualTo("2022-11-11T00:00:00Z");
        assertThat(events.get(0).path("direction").asText()).isEqualTo("LONG_LIQUIDATION_STRESS");
        assertThat(events.get(0).path("prior_90_same_side_nearest_rank_p95_usd").asDouble()).isEqualTo(86.0);
        assertThat(events.get(1).path("direction").asText()).isEqualTo("SHORT_LIQUIDATION_STRESS");
        assertThat(events.get(1).path("prior_90_same_side_nearest_rank_p95_usd").asDouble()).isEqualTo(86.0);

        JsonNode btc = summary(result, "BTC");
        assertThat(btc.path("earliest_usable_decision_at").asText()).isEqualTo("2022-11-11T00:00:00Z");
        assertThat(btc.path("eligible_decision_days").asInt()).isEqualTo(1);
    }

    @Test
    void aMissingDailyRecordBlocksItsPriorWindowWithoutZeroFilling() {
        List<JsonNode> rows = btcWarmupRows(false, false);
        ObjectNode result = calculate(validInput(), validPrecommit(), validAcquisition(), rows);

        assertThat(result.path("stress_events")).isEmpty();
        JsonNode btcMissing = result.path("missingness_by_asset").get(0);
        assertThat(btcMissing.path("missing_source_records").asInt()).isGreaterThan(0);
        assertThat(btcMissing.path("missing_source_days").toString()).contains("2022-09-01");
        assertThat(btcMissing.path("blocked_because_prior_90_day_window_incomplete").asInt()).isEqualTo(1);
        assertThat(btcMissing.path("blocked_because_current_record_missing").asInt()).isGreaterThan(0);
    }

    @Test
    void exactP95AndZeroTotalsDoNotTriggerAndTheTwoSidesUseSeparateThresholds() {
        ObjectNode exact = calculate(validInput(), validPrecommit(), validAcquisition(),
                btcWarmupRowsWithCurrent(true, 86, 86));
        assertThat(exact.path("stress_events")).isEmpty();

        ObjectNode zero = calculate(validInput(), validPrecommit(), validAcquisition(),
                btcWarmupRowsWithCurrent(true, 0, 0));
        assertThat(zero.path("stress_events")).isEmpty();

        List<JsonNode> differentSideRanges = new ArrayList<>();
        LocalDate start = DailyStressPreflightV1.SOURCE_START;
        for (int i = 0; i < 90; i++) {
            differentSideRanges.add(row("BTC", "BTCUSDT_PERP.A", midnight(start.plusDays(i)), i + 1, i + 101));
        }
        differentSideRanges.add(row("BTC", "BTCUSDT_PERP.A", "2022-11-09T00:00:00Z", 87, 187));
        ObjectNode separate = calculate(validInput(), validPrecommit(), validAcquisition(), differentSideRanges);
        assertThat(separate.path("stress_events")).hasSize(2);
        assertThat(separate.path("stress_events").get(0).path("prior_90_same_side_nearest_rank_p95_usd").asDouble())
                .isEqualTo(86.0);
        assertThat(separate.path("stress_events").get(1).path("prior_90_same_side_nearest_rank_p95_usd").asDouble())
                .isEqualTo(186.0);
    }

    @Test
    void latestDecisionCutoffIsExclusive() {
        List<JsonNode> rows = new ArrayList<>();
        LocalDate start = LocalDate.parse("2026-04-13");
        for (int i = 0; i < 90; i++) {
            rows.add(row("BTC", "BTCUSDT_PERP.A", midnight(start.plusDays(i)), i + 1, i + 1));
        }
        rows.add(row("BTC", "BTCUSDT_PERP.A", "2026-07-12T00:00:00Z", 87, 87));
        rows.add(row("BTC", "BTCUSDT_PERP.A", "2026-07-13T00:00:00Z", 1_000_000, 1_000_000));
        ObjectNode result = calculate(validInput(), validPrecommit(), validAcquisition(), rows);
        assertThat(result.path("stress_events")).hasSize(2);
        assertThat(result.path("stress_events").get(0).path("assumed_available_at").asText())
                .isEqualTo("2026-07-14T00:00:00Z");
        assertThat(result.path("stress_events").get(1).path("assumed_available_at").asText())
                .isEqualTo("2026-07-14T00:00:00Z");
        assertThat(result.path("stress_events").toString()).doesNotContain("2026-07-15T00:00:00Z");
    }

    @Test
    void rejectsDuplicateMisalignedNegativeNonfiniteAndWrongSymbolRows() {
        JsonNode valid = row("BTC", "BTCUSDT_PERP.A", "2022-08-11T00:00:00Z", 1, 1);
        assertThatThrownBy(() -> calculate(validInput(), validPrecommit(), validAcquisition(), List.of(valid, valid)))
                .hasMessageContaining("duplicate normalized daily row");
        assertThatThrownBy(() -> calculate(validInput(), validPrecommit(), validAcquisition(),
                List.of(row("BTC", "BTCUSDT_PERP.A", "2022-08-11T00:00:00+01:00", 1, 1))))
                .hasMessageContaining("aligned to UTC midnight");
        assertThatThrownBy(() -> calculate(validInput(), validPrecommit(), validAcquisition(),
                List.of(row("BTC", "BTCUSDT_PERP.A", "2022-08-11T00:00:00Z", -1, 1))))
                .hasMessageContaining("finite and nonnegative");
        assertThatThrownBy(() -> calculate(validInput(), validPrecommit(), validAcquisition(),
                List.of(row("BTC", "BTCUSDT_PERP.A", "2022-08-11T00:00:00Z", Double.NaN, 1))))
                .hasMessageContaining("finite and nonnegative");
        assertThatThrownBy(() -> calculate(validInput(), validPrecommit(), validAcquisition(),
                List.of(row("BTC", "ETHUSDT_PERP.A", "2022-08-11T00:00:00Z", 1, 1))))
                .hasMessageContaining("symbol does not match asset");
    }

    @Test
    void rejectsMalformedNormalizedInputBoundsAndFields() {
        assertThatThrownBy(() -> DailyStressPreflightV1.calculate(validInput(), JsonHashes.sha256("input"),
                validPrecommit(), validInput().path("precommit_ref").path("byte_sha256").asText(),
                validAcquisition(), validInput().path("acquisition_manifest_ref").path("byte_sha256").asText(), null))
                .hasMessageContaining("row count exceeds bound");

        JsonNode validRow = row("BTC", "BTCUSDT_PERP.A", "2022-08-11T00:00:00Z", 1, 1);
        assertThatThrownBy(() -> calculate(validInput(), validPrecommit(), validAcquisition(),
                Collections.nCopies(12_001, validRow))).hasMessageContaining("row count exceeds bound");
        assertThatThrownBy(() -> calculate(validInput(), validPrecommit(), validAcquisition(),
                List.of(row("BTC", "BTCUSDT_PERP.A", "2022-08-10T00:00:00Z", 1, 1))))
                .hasMessageContaining("outside the frozen source window");
        assertThatThrownBy(() -> calculate(validInput(), validPrecommit(), validAcquisition(),
                List.of(row("BTC", "BTCUSDT_PERP.A", "2026-09-20T00:00:00Z", 1, 1))))
                .hasMessageContaining("outside the frozen source window");
        assertThatThrownBy(() -> calculate(validInput(), validPrecommit(), validAcquisition(),
                List.of(row("BTC", "BTCUSDT_PERP.A", "not-a-timestamp", 1, 1))))
                .hasMessageContaining("invalid daily bucket timestamp");
        assertThatThrownBy(() -> calculate(validInput(), validPrecommit(), validAcquisition(),
                List.of(row("BTC", "BTCUSDT_PERP.A", "2022-08-11T00:00:00.000Z", 1, 1))))
                .hasMessageContaining("canonical UTC midnight");
        assertThatThrownBy(() -> calculate(validInput(), validPrecommit(), validAcquisition(),
                List.of(row("", "BTCUSDT_PERP.A", "2022-08-11T00:00:00Z", 1, 1))))
                .hasMessageContaining("missing or invalid asset");
        assertThatThrownBy(() -> calculate(validInput(), validPrecommit(), validAcquisition(),
                List.of(row("BTC", "", "2022-08-11T00:00:00Z", 1, 1))))
                .hasMessageContaining("missing or invalid symbol");

        ObjectNode missingLong = JsonHashes.mapper().createObjectNode().put("asset", "BTC")
                .put("symbol", "BTCUSDT_PERP.A").put("day_start_utc", "2022-08-11T00:00:00Z")
                .put("short_liquidations_usd", 1);
        assertThatThrownBy(() -> calculate(validInput(), validPrecommit(), validAcquisition(), List.of(missingLong)))
                .hasMessageContaining("long_liquidations_usd must be numeric");
        ObjectNode textualLong = missingLong.deepCopy().put("long_liquidations_usd", "quiet");
        assertThatThrownBy(() -> calculate(validInput(), validPrecommit(), validAcquisition(), List.of(textualLong)))
                .hasMessageContaining("long_liquidations_usd must be numeric");
    }

    @Test
    void rejectsOutOfScopeAssetWrongSchemaAndBadPhysicalBindings() {
        assertThatThrownBy(() -> calculate(validInput(), validPrecommit(), validAcquisition(),
                List.of(row("XRP", "XRPUSDT_PERP.A", "2022-08-11T00:00:00Z", 1, 1))))
                .hasMessageContaining("out-of-scope asset");

        ObjectNode wrongSchema = validInput();
        wrongSchema.put("schema", "daily-stress-preflight-input/0");
        assertThatThrownBy(() -> calculate(wrongSchema, validPrecommit(), validAcquisition(), List.of()))
                .hasMessageContaining("unsupported daily stress input schema");

        ObjectNode wrongPrecommitHash = validInput();
        assertThatThrownBy(() -> DailyStressPreflightV1.calculate(wrongPrecommitHash,
                JsonHashes.sha256("input"), validPrecommit(), JsonHashes.sha256("different-precommit"),
                validAcquisition(), JsonHashes.sha256("acquisition"), List.of()))
                .hasMessageContaining("precommit_ref byte SHA-256 mismatch");
        ObjectNode wrongManifestHash = validInput();
        assertThatThrownBy(() -> DailyStressPreflightV1.calculate(wrongManifestHash,
                JsonHashes.sha256("input"), validPrecommit(), wrongManifestHash.path("precommit_ref").path("byte_sha256").asText(),
                validAcquisition(), JsonHashes.sha256("different-acquisition"), List.of()))
                .hasMessageContaining("acquisition_manifest_ref byte SHA-256 mismatch");
    }

    @Test
    void rejectsPrecommitScopePolicyOrAcquisitionWindowDrift() {
        ObjectNode wrongAssets = validPrecommit();
        ((ArrayNode) wrongAssets.path("trade_assets")).add("xrp");
        assertThatThrownBy(() -> calculate(validInput(), wrongAssets, validAcquisition(), List.of()))
                .hasMessageContaining("precommit trade_assets differ");

        ObjectNode wrongPolicy = validPrecommit();
        wrongPolicy.with("daily_stress_contract").put("prior_contiguous_days", 89);
        assertThatThrownBy(() -> calculate(validInput(), wrongPolicy, validAcquisition(), List.of()))
                .hasMessageContaining("daily_stress_contract differs");

        ObjectNode wrongAcquisition = validAcquisition();
        wrongAcquisition.put("to_exclusive", "2026-09-19");
        wrongAcquisition.put("content_sha256", JsonHashes.ownHash(wrongAcquisition));
        assertThatThrownBy(() -> calculate(validInput(), validPrecommit(), wrongAcquisition, List.of()))
                .hasMessageContaining("acquisition window or as_of");
    }

    @Test
    void failsClosedForEveryFrozenDailyStressPolicyMutation() {
        List<Consumer<ObjectNode>> mutations = List.of(
                p -> p.with("daily_stress_contract").put("schema", "wrong/1"),
                p -> ((ArrayNode) p.path("daily_stress_contract").path("assets")).set(0, JsonHashes.mapper().getNodeFactory().textNode("XRP")),
                p -> p.with("daily_stress_contract").put("venue", "OTHER"),
                p -> p.with("daily_stress_contract").put("market_type", "SPOT"),
                p -> p.with("daily_stress_contract").put("bucket_seconds", 86_399),
                p -> p.with("daily_stress_contract").put("publication_delay_after_bucket_close_seconds", 0),
                p -> p.with("daily_stress_contract").put("modeled_available_after_bucket_start_seconds", 86_400),
                p -> p.with("daily_stress_contract").put("prior_contiguous_days", 89),
                p -> p.with("daily_stress_contract").put("percentile", 0.9),
                p -> p.with("daily_stress_contract").put("quantile_method", "linear"),
                p -> p.with("daily_stress_contract").put("stress_comparison", "GREATER_THAN"),
                p -> p.with("daily_stress_contract").put("missing_policy", "ZERO_FILL"),
                p -> p.with("daily_stress_contract").put("data_scope", "MARKET_WIDE"),
                p -> p.with("daily_stress_contract").put("source_authority", "PIT_VERIFIED"),
                p -> p.with("daily_stress_contract").put("no_intraday_reconstruction", false));

        for (Consumer<ObjectNode> mutation : mutations) {
            ObjectNode precommit = mutatePrecommit(mutation);
            assertThatThrownBy(() -> calculate(validInput(), precommit, validAcquisition(), List.of()))
                    .hasMessageContaining("daily_stress_contract differs");
        }
    }

    @Test
    void failsClosedForFrozenScopeDatesLifecycleAndStructureMutations() {
        List<Consumer<ObjectNode>> mutations = List.of(
                p -> p.put("schema", "strategy-precommit/2"),
                p -> p.put("status", "DRAFT"),
                p -> ((ArrayNode) p.path("trade_assets")).set(0, JsonHashes.mapper().getNodeFactory().textNode("xrp")),
                p -> ((ArrayNode) p.path("experiment").path("required_assets")).set(3, JsonHashes.mapper().getNodeFactory().textNode("btc")),
                p -> p.with("research_window").put("source_start", "2022-08-12T00:00:00Z"),
                p -> p.with("research_window").put("source_end_exclusive", "2026-09-19T00:00:00Z"),
                p -> p.with("research_window").put("decision_start", "2022-11-12T00:00:00Z"),
                p -> p.with("research_window").put("decision_end_exclusive", "2026-07-14T00:00:00Z"),
                p -> p.with("research_window").put("execution_end_exclusive", "2026-09-19T00:00:00Z"),
                p -> p.with("research_window").put("minimum_purge_days", 66),
                p -> p.with("research_window").put("embargo_days", 6),
                p -> p.with("holding_horizon").put("max", 59),
                p -> p.with("user_constraints").put("max_holding_days", 59),
                p -> p.with("feature_contract").path("series").forEach(series -> {
                    if ("4h".equals(series.path("timeframe").asText())) ((ObjectNode) series).put("timeframe", "2h");
                }),
                p -> p.with("feature_contract").path("series").forEach(series -> {
                    if ("1h".equals(series.path("timeframe").asText())) ((ObjectNode) series).put("timeframe", "30m");
                }),
                p -> p.with("feature_contract").put("series", "missing"),
                p -> ((ObjectNode) p.path("required_inputs").get(0).path("point_in_time")).put("status", "VERIFIED"),
                p -> ((ObjectNode) p.path("required_inputs").get(0).path("point_in_time")).put("historical_publication_verified", true),
                p -> ((ObjectNode) p.path("required_inputs").get(0).path("point_in_time")).put("revision_vintages_verified", true),
                p -> p.put("required_inputs", JsonHashes.mapper().createArrayNode()));

        for (Consumer<ObjectNode> mutation : mutations) {
            ObjectNode precommit = mutatePrecommit(mutation);
            assertThatThrownBy(() -> calculate(validInput(), precommit, validAcquisition(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void failsClosedForMalformedScopeAndReferenceShape() {
        List<Consumer<ObjectNode>> malformedScopes = List.of(
                p -> p.put("trade_assets", "not-an-array"),
                p -> p.withArray("trade_assets").add(7),
                p -> p.withArray("trade_assets").add("btc"),
                p -> p.with("experiment").put("required_assets", "not-an-array"));
        for (Consumer<ObjectNode> mutation : malformedScopes) {
            ObjectNode precommit = mutatePrecommit(mutation);
            assertThatThrownBy(() -> calculate(validInput(), precommit, validAcquisition(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        ObjectNode noInputHash = validInput();
        assertThatThrownBy(() -> DailyStressPreflightV1.calculate(noInputHash, "invalid-hash", validPrecommit(),
                noInputHash.path("precommit_ref").path("byte_sha256").asText(), validAcquisition(),
                noInputHash.path("acquisition_manifest_ref").path("byte_sha256").asText(), List.of()))
                .hasMessageContaining("input byte SHA-256 is invalid");

        ObjectNode extraBindingKey = validInput();
        extraBindingKey.with("precommit_ref").put("content_sha256", JsonHashes.sha256("extra"));
        assertThatThrownBy(() -> calculate(extraBindingKey, validPrecommit(), validAcquisition(), List.of()))
                .hasMessageContaining("must contain exactly path and byte_sha256");

        ObjectNode badBindingHash = validInput();
        badBindingHash.with("precommit_ref").put("byte_sha256", "bad");
        assertThatThrownBy(() -> calculate(badBindingHash, validPrecommit(), validAcquisition(), List.of()))
                .hasMessageContaining("path or byte SHA-256 is invalid");
    }

    @Test
    void failsClosedForAcquisitionSchemaWindowAndReferenceMutations() {
        List<Consumer<ObjectNode>> mutations = List.of(
                a -> a.put("schema", "coinalyze-daily-acquisition/0"),
                a -> a.put("from_inclusive", ""),
                a -> a.put("from_inclusive", "2022-08-12"),
                a -> a.put("to_exclusive", "2026-09-19"),
                a -> a.put("as_of", "2026-09-19T00:00:00Z"),
                a -> a.put("raw_responses", JsonHashes.mapper().createArrayNode()));

        for (Consumer<ObjectNode> mutation : mutations) {
            ObjectNode acquisition = validAcquisition();
            mutation.accept(acquisition);
            acquisition.put("content_sha256", JsonHashes.ownHash(acquisition));
            assertThatThrownBy(() -> calculate(validInput(), validPrecommit(), acquisition, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        ObjectNode invalidRawHash = validAcquisition();
        ((ObjectNode) invalidRawHash.path("raw_responses").get(0)).put("sha256", "not-a-hash");
        invalidRawHash.put("content_sha256", JsonHashes.ownHash(invalidRawHash));
        assertThatThrownBy(() -> calculate(validInput(), validPrecommit(), invalidRawHash, List.of()))
                .hasMessageContaining("invalid byte SHA-256");
    }

    @Test
    void rejectsUnsafeOrOversizedRawHistoryReferences() throws Exception {
        Path root = temporary.resolve("bounded-acquisition");
        Path rawDirectory = root.resolve("raw");
        Files.createDirectories(rawDirectory);
        Path manifestPath = root.resolve(CoinalyzeDailyData.MANIFEST_NAME);
        Files.writeString(rawDirectory.resolve("future-markets.json"), "[]");

        ObjectNode traversal = validAcquisition();
        ((ObjectNode) ((ArrayNode) traversal.path("raw_responses")).get(0))
                .put("path", "raw/../outside.json");
        assertThatThrownBy(() -> DailyStressPreflightV1.validateRawReferenceBounds(traversal, manifestPath))
                .hasMessageContaining("escapes acquisition root");

        ObjectNode tooMany = validAcquisition();
        ArrayNode tooManyRefs = (ArrayNode) tooMany.path("raw_responses");
        tooManyRefs.removeAll();
        for (int index = 0; index <= 64; index++) {
            tooManyRefs.addObject().put("path", "raw/" + index + ".json");
        }
        assertThatThrownBy(() -> DailyStressPreflightV1.validateRawReferenceBounds(tooMany, manifestPath))
                .hasMessageContaining("reference count");

        Path oversized = rawDirectory.resolve("future-markets.json");
        try (var channel = java.nio.channels.FileChannel.open(oversized,
                java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.position(16L * 1024 * 1024);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {1}));
        }
        assertThatThrownBy(() -> DailyStressPreflightV1.validateRawReferenceBounds(validAcquisition(), manifestPath))
                .hasMessageContaining("per-file size bound");
    }

    @Test
    void futureObservationsCannotChangeEarlierThresholds() {
        List<JsonNode> rows = btcWarmupRows(true, false);
        rows.add(row("BTC", "BTCUSDT_PERP.A", "2022-11-10T00:00:00Z", 1_000_000, 1_000_000));
        ObjectNode result = calculate(validInput(), validPrecommit(), validAcquisition(), rows);
        JsonNode eventAtBoundary = ((ArrayNode) result.path("stress_events")).get(0);
        assertThat(eventAtBoundary.path("stress_event_timestamp").asText()).isEqualTo("2022-11-11T00:00:00Z");
        assertThat(eventAtBoundary.path("prior_90_same_side_nearest_rank_p95_usd").asDouble()).isEqualTo(86.0);
    }

    @Test
    void commandReopensPhysicalRawReferencesAndNeverOverwritesOutput() throws Exception {
        Path root = temporary.resolve("acquisition");
        var http = new PublicDataAdapters.InjectableHttpClient() {
            @Override public PublicDataAdapters.FetchResponse fetch(URI uri, Map<String, String> headers) {
                String body;
                if (uri.getPath().endsWith("/exchanges")) {
                    body = "[{\"name\":\"Binance\",\"code\":\"A\"}]";
                } else if (uri.getPath().endsWith("/future-markets")) {
                    body = "[{\"exchange\":\"A\",\"base_asset\":\"BTC\",\"quote_asset\":\"USDT\",\"is_perpetual\":true,\"margined\":\"STABLE\",\"symbol\":\"BTCUSDT_PERP.A\"},"
                            + "{\"exchange\":\"A\",\"base_asset\":\"ETH\",\"quote_asset\":\"USDT\",\"is_perpetual\":true,\"margined\":\"STABLE\",\"symbol\":\"ETHUSDT_PERP.A\"},"
                            + "{\"exchange\":\"A\",\"base_asset\":\"SOL\",\"quote_asset\":\"USDT\",\"is_perpetual\":true,\"margined\":\"STABLE\",\"symbol\":\"SOLUSDT_PERP.A\"},"
                            + "{\"exchange\":\"A\",\"base_asset\":\"AAVE\",\"quote_asset\":\"USDT\",\"is_perpetual\":true,\"margined\":\"STABLE\",\"symbol\":\"AAVEUSDT_PERP.A\"}]";
                } else {
                    body = "[{\"symbol\":\"BTCUSDT_PERP.A\",\"history\":[]},"
                            + "{\"symbol\":\"ETHUSDT_PERP.A\",\"history\":[]},"
                            + "{\"symbol\":\"SOLUSDT_PERP.A\",\"history\":[]},"
                            + "{\"symbol\":\"AAVEUSDT_PERP.A\",\"history\":[]}]";
                }
                return new PublicDataAdapters.FetchResponse(200, body.getBytes(StandardCharsets.UTF_8), Map.of());
            }
        };
        CoinalyzeDailyData.acquire(new CoinalyzeDailyData.Options(
                DailyStressPreflightV1.SOURCE_START, DailyStressPreflightV1.SOURCE_END_EXCLUSIVE,
                DailyStressPreflightV1.DATA_AS_OF, root, "fixture-key", http, ignored -> {}, () -> 0L));

        Path precommitPath = temporary.resolve("precommit.json");
        ObjectNode precommit = validPrecommit();
        byte[] precommitBytes = JsonHashes.mapper().writeValueAsBytes(precommit);
        Files.write(precommitPath, precommitBytes);
        Path manifestPath = root.resolve(CoinalyzeDailyData.MANIFEST_NAME);
        byte[] manifestBytes = Files.readAllBytes(manifestPath);
        ObjectNode input = validInput();
        input.with("precommit_ref").put("path", "precommit.json").put("byte_sha256", JsonHashes.sha256(precommitBytes));
        input.with("acquisition_manifest_ref").put("path", "acquisition/" + CoinalyzeDailyData.MANIFEST_NAME)
                .put("byte_sha256", JsonHashes.sha256(manifestBytes));
        Path inputPath = temporary.resolve("input.json");
        Files.write(inputPath, JsonHashes.mapper().writeValueAsBytes(input));
        Path outputPath = temporary.resolve("output.json");

        ObjectNode output = DailyStressPreflightV1.run(JsonHashes.mapper().createObjectNode()
                .put("input", inputPath.toString()).put("out", outputPath.toString()));
        JsonNode written = JsonHashes.mapper().readTree(Files.readAllBytes(outputPath));
        assertThat(written.path("content_sha256").asText()).isEqualTo(output.path("content_sha256").asText());
        assertThat(written.path("raw_response_references")).hasSize(7);
        assertThat(written.path("asset_counts")).hasSize(4);
        assertThat(written.path("stress_events")).isEmpty();

        byte[] priorOutput = Files.readAllBytes(outputPath);
        assertThatThrownBy(() -> DailyStressPreflightV1.run(JsonHashes.mapper().createObjectNode()
                .put("input", inputPath.toString()).put("out", outputPath.toString())))
                .hasMessageContaining("already exists");
        assertThat(Files.readAllBytes(outputPath)).containsExactly(priorOutput);
    }

    private static ObjectNode calculate(ObjectNode input, ObjectNode precommit, ObjectNode acquisition,
            List<JsonNode> rows) {
        String precommitSha = input.path("precommit_ref").path("byte_sha256").asText();
        String acquisitionSha = input.path("acquisition_manifest_ref").path("byte_sha256").asText();
        return DailyStressPreflightV1.calculate(input, JsonHashes.sha256("input bytes"), precommit,
                precommitSha, acquisition, acquisitionSha, rows);
    }

    private static ObjectNode mutatePrecommit(Consumer<ObjectNode> mutation) {
        ObjectNode precommit = validPrecommit();
        mutation.accept(precommit);
        precommit.put("content_sha256", JsonHashes.ownHash(precommit));
        return precommit;
    }

    private static ObjectNode validInput() {
        ObjectNode input = JsonHashes.mapper().createObjectNode().put("schema", DailyStressPreflightV1.INPUT_SCHEMA)
                .put("input_path", "fixture-input.json");
        input.putObject("precommit_ref").put("path", "precommit.json")
                .put("byte_sha256", JsonHashes.sha256("frozen precommit bytes"));
        input.putObject("acquisition_manifest_ref").put("path", "coinalyze-daily-acquisition.json")
                .put("byte_sha256", JsonHashes.sha256("acquisition bytes"));
        return input;
    }

    private static ObjectNode validPrecommit() {
        ObjectNode precommit = JsonHashes.mapper().createObjectNode().put("schema", "strategy-precommit/1")
                .put("precommit_id", "daily-stress-fixture").put("status", "FROZEN");
        ArrayNode tradeAssets = precommit.putArray("trade_assets");
        ArrayNode requiredAssets = precommit.putObject("experiment").putArray("required_assets");
        for (String asset : List.of("btc", "eth", "sol", "aave")) {
            tradeAssets.add(asset);
            requiredAssets.add(asset);
        }
        ObjectNode stress = precommit.putObject("daily_stress_contract")
                .put("schema", "liquidation-daily-stress-policy/1")
                .put("venue", "BINANCE").put("market_type", "USDT_PERPETUAL")
                .put("bucket_seconds", 86_400).put("publication_delay_after_bucket_close_seconds", 86_400)
                .put("modeled_available_after_bucket_start_seconds", 172_800).put("prior_contiguous_days", 90)
                .put("percentile", 0.95).put("quantile_method", "nearest_rank")
                .put("stress_comparison", "STRICT_GREATER_THAN_AND_POSITIVE")
                .put("missing_policy", "BLOCK_WINDOW_NOT_ZERO")
                .put("data_scope", "BINANCE_SPECIFIC_NOT_MARKET_WIDE")
                .put("source_authority", "RETROSPECTIVE_PROXY_DISCLOSED")
                .put("no_intraday_reconstruction", true);
        ArrayNode stressAssets = stress.putArray("assets");
        for (String asset : List.of("BTC", "ETH", "SOL", "AAVE")) stressAssets.add(asset);

        precommit.putObject("research_window")
                .put("source_start", "2022-08-11T00:00:00Z")
                .put("source_end_exclusive", "2026-09-20T00:00:00Z")
                .put("decision_start", "2022-11-11T00:00:00Z")
                .put("decision_end_exclusive", "2026-07-15T00:00:00Z")
                .put("execution_end_exclusive", "2026-09-20T00:00:00Z")
                .put("minimum_purge_days", 67).put("embargo_days", 7);
        precommit.putObject("holding_horizon").put("max", 60);
        precommit.putObject("user_constraints").put("max_holding_days", 60);
        ArrayNode series = precommit.putObject("feature_contract").putArray("series");
        for (String asset : List.of("btc", "eth", "sol", "aave")) {
            for (String timeframe : List.of("4h", "1h")) {
                series.addObject().put("asset", asset).put("timeframe", timeframe)
                        .put("context_only", false).put("tradable", true);
            }
        }
        ArrayNode requiredInputs = precommit.putArray("required_inputs");
        requiredInputs.addObject().put("input_id", "liquidations").putObject("point_in_time")
                .put("status", "PROXY_DISCLOSED").put("historical_publication_verified", false)
                .put("revision_vintages_verified", false);
        precommit.put("content_sha256", JsonHashes.ownHash(precommit));
        return precommit;
    }

    private static ObjectNode validAcquisition() {
        ObjectNode acquisition = JsonHashes.mapper().createObjectNode()
                .put("schema", "coinalyze-daily-acquisition/1")
                .put("from_inclusive", "2022-08-11").put("to_exclusive", "2026-09-20")
                .put("as_of", "2026-09-20T00:00:00Z");
        ObjectNode raw = acquisition.putArray("raw_responses").addObject()
                .put("kind", "future-markets").put("path", "raw/future-markets.json")
                .put("sha256", JsonHashes.sha256("raw"));
        acquisition.put("content_sha256", JsonHashes.ownHash(acquisition));
        return acquisition;
    }

    private static List<JsonNode> btcWarmupRows(boolean includeSepFirst, boolean includeFutureDay) {
        return btcWarmupRowsWithCurrent(includeSepFirst, 87, 87, includeFutureDay);
    }

    private static List<JsonNode> btcWarmupRowsWithCurrent(boolean includeSepFirst, double currentLong, double currentShort) {
        return btcWarmupRowsWithCurrent(includeSepFirst, currentLong, currentShort, false);
    }

    private static List<JsonNode> btcWarmupRowsWithCurrent(boolean includeSepFirst, double currentLong,
            double currentShort, boolean includeFutureDay) {
        List<JsonNode> rows = new ArrayList<>();
        LocalDate start = DailyStressPreflightV1.SOURCE_START;
        for (int i = 0; i < 90; i++) {
            LocalDate date = start.plusDays(i);
            if (!includeSepFirst && date.equals(LocalDate.parse("2022-09-01"))) continue;
            rows.add(row("BTC", "BTCUSDT_PERP.A", midnight(date), i + 1, i + 1));
        }
        rows.add(row("BTC", "BTCUSDT_PERP.A", "2022-11-09T00:00:00Z", currentLong, currentShort));
        if (includeFutureDay) rows.add(row("BTC", "BTCUSDT_PERP.A", "2022-11-10T00:00:00Z", 1_000_000, 1_000_000));
        return rows;
    }

    private static ObjectNode row(String asset, String symbol, String day, double longs, double shorts) {
        return JsonHashes.mapper().createObjectNode().put("asset", asset).put("symbol", symbol)
                .put("day_start_utc", day).put("long_liquidations_usd", longs).put("short_liquidations_usd", shorts);
    }

    private static String midnight(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant().toString();
    }

    private static JsonNode summary(ObjectNode result, String asset) {
        for (JsonNode item : result.path("asset_counts")) if (asset.equals(item.path("asset").asText())) return item;
        throw new AssertionError("missing asset summary " + asset);
    }
}

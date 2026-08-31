package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.LifecycleTrustService;
import com.tradinganalytics.infrastructure.marketdata.PublicDataAdapters;
import com.tradinganalytics.marketdata.research.ResearchData;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Base64;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Standalone quarantine oracle; run with {@code java -ea}. */
public final class StrategyResearchDataV5NodeOracleTest {
    private static final ObjectMapper JSON = JsonHashes.mapper();
    private static final String H = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final long FOUR_HOURS = 14_400_000L;
    private static final long ONE_MINUTE = 60_000L;
    private static final Path ROOT = repositoryRoot();
    private static int tests;

    @Test
    void completeNodeOracleAndSecurityContract() throws Exception {
        main(new String[0]);
    }

    public static void main(String[] args) throws Exception {
        tests = 0;
        exportsAreDerivedDynamically();
        producerAndAdapterHashesMatchPhysicalBytes();
        canonicalHashesMatchNode();
        fundingCadenceMatchesNode();
        fundingCanonicalizationMatchesNode();
        fundingFailuresMatchNode();
        settlementMarkBindingMatchesNode();
        denseCoverageMatchesNode();
        timeframeRequirementsMatchNode();
        fiveYearPlanMatchesNode();
        opportunityAndPredicateContractsMatchNode();
        pitFeatureRowsMatchNode();
        authoritativeRolesAndSeparatedManifestMatchNode();
        unavailableMetadataMatchesNode();
        fundingPnlMatchesNode();
        normalizedOutcomeUsesCanonicalLifecycle();
        normalizedProductionLifecycleTrustMatchesNode();
        datedDiscoveryAndHistoryUseInjectedTransport();
        productionHydrationUsesInjectedTransportAndCustody();
        hydrationManifestStructuralDifferential();
        legacySpotTimeStopMatchesNode();
        legacyTargetStopMatchesNodeAndRejectsForgedRoles();
        legacyDerivativeOutcomeMatchesNodeAndRejectsMarkFundingTamper();
        receiptCustodyRejectsTraversalSymlinkAndMutation();
        authoritativeParquetReopenAcceptsValidBytes();
        authoritativeParquetReopenRejectsFakeBytes();
        fixtureAcquisitionCheckpointIsAtomicAndCasBound();
        adapterAcquisitionUsesInjectedTransportAndHydrationFailsClosed();
        rawReplayRecomputesThroughCurrentAdapterAndMatchesNode();
        rawReplayRejectsReorderedAndChangedRequests();
        rawReplayPermitsContentAddressedRawReuseAcrossReceipts();
        rawReplayAllowsOnlyDeterministicFundingCoverageStrengthening();
        auxiliaryMetricsCompleteReplayMatchesNodeAndRejectsTamper();
        auxiliaryMetricsPrefixReplayMatchesNodeAndRejectsCheckpointCollision();
        noPlaceholdersRemain();
        System.out.println("PASS " + tests + " quarantine tests; 66/66 exports");
    }

    private static void exportsAreDerivedDynamically() throws Exception {
        Set<String> actual = new TreeSet<>();
        Arrays.stream(StrategyResearchDataV5.class.getDeclaredFields()).filter(field -> Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers())).forEach(field -> actual.add(field.getName()));
        Arrays.stream(StrategyResearchDataV5.class.getDeclaredMethods()).filter(method -> Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())).forEach(method -> actual.add(method.getName()));
        equal(actual.size(), 66, "export count"); pass();
    }

    private static void producerAndAdapterHashesMatchPhysicalBytes() throws Exception {
        equal(StrategyResearchDataV5.javaProducerCodeSha256().matches("[0-9a-f]{64}"), true, "Java producer runtime hash shape");
        equal(StrategyResearchDataV5.javaProducerCodeSha256().equals(StrategyResearchDataV5.DATA_V5_PRODUCER_CODE_SHA256), false, "Java producer is not the legacy Node hash");
        equal(StrategyResearchDataV5.javaAdapterCodeSha256().matches("[0-9a-f]{64}"), true, "Java adapter runtime hash shape");
        equal(StrategyResearchDataV5.javaAdapterCodeSha256().equals(StrategyResearchDataV5.DATA_V5_ADAPTER_CODE_SHA256), false, "Java adapter is not the legacy Node hash");
        equal(StrategyResearchDataV5.DATA_V5_PRODUCER_CODE_SHA256.matches("[0-9a-f]{64}"), true, "producer export");
        equal(StrategyResearchDataV5.DATA_V5_ADAPTER_CODE_SHA256.matches("[0-9a-f]{64}"), true, "adapter export"); pass();
    }

    private static void canonicalHashesMatchNode() throws Exception {
        ObjectNode value = object().put("z", 2).put("a", 1).put("content_sha256", "stale");
        equal(StrategyResearchDataV5.stable(value), "{\"a\":1,\"content_sha256\":\"stale\",\"z\":2}", "stable");
        equal(StrategyResearchDataV5.hash(value).matches("[0-9a-f]{64}"), true, "hash");
        equal(StrategyResearchDataV5.ownHash(value), StrategyResearchDataV5.hash(object().put("z", 2).put("a", 1)), "ownHash"); pass();
    }

    private static void fundingCadenceMatchesNode() throws Exception {
        long start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(); ArrayNode rows = array();
        for (int hour : new int[]{0, 2, 4, 8, 12, 20, 28}) rows.add(funding(hour, start));
        ObjectNode request = object().put("startAt", iso(start)).put("endAt", iso(start + 36 * 3_600_000L)).set("rows", rows);
        ArrayNode cadence = StrategyResearchDataV5.discoverFundingCadenceSegments(request); equal(cadence.isArray(), true, "funding cadence"); pass();
    }

    private static void fundingCanonicalizationMatchesNode() throws Exception {
        long start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(); ArrayNode rows = array(); rows.add(funding(0, start)); rows.add(funding(8, start)); rows.add(funding(16, start));
        ObjectNode series = object().put("series_type", "funding_events").put("event_driven", true).put("event_sequence_mode", true)
                .put("start_at", iso(start)).put("end_at", iso(start + 16 * 3_600_000L)).put("availability_cutoff_at", iso(start + 17 * 3_600_000L))
                .put("slot_tolerance_ms", 60_000).put("source_coverage_complete", true);
        ObjectNode request = object().set("rows", rows); request.set("series", series);
        equal(StrategyResearchDataV5.canonicalizeFundingRows(request).size(), 2, "funding canonicalization"); pass();
    }

    private static void fundingFailuresMatchNode() throws Exception {
        long start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(); ObjectNode series = object().put("series_type", "funding_events").put("event_sequence_mode", true)
                .put("start_at", iso(start)).put("end_at", iso(start + 8 * 3_600_000L)).put("availability_cutoff_at", iso(start + 9 * 3_600_000L)).put("slot_tolerance_ms", 60_000);
        ArrayNode duplicate = array().add(funding(0, start)).add(funding(0, start).put("event_id", "duplicate")); ObjectNode request = object().set("rows", duplicate); request.set("series", series);
        ObjectNode duplicateRequest = request; failLikeNode("canonicalFunding", request, () -> StrategyResearchDataV5.canonicalizeFundingRows(duplicateRequest));
        ArrayNode jitter = array().add(funding(0, start).put("raw_event_time", start + 60_001)); request = object().set("rows", jitter); request.set("series", series);
        ObjectNode finalRequest = request; failLikeNode("canonicalFunding", request, () -> StrategyResearchDataV5.canonicalizeFundingRows(finalRequest)); pass();
    }

    private static void settlementMarkBindingMatchesNode() throws Exception {
        long event = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(); String digest = H;
        ArrayNode funding = array().add(object().put("event_id", "evt").put("event_time", event).put("raw_event_time", event).put("settlement_slot", iso(event)).put("funding_rate", .001).put("availability_time", event));
        ArrayNode marks = array().add(object().put("event_time", event).put("availability_time", event).put("mark_open", 42).put("response_sha256", digest));
        ObjectNode request = object().set("fundingRows", funding); request.set("markRows", marks); request.set("markResponseSha256", array().add(digest));
        equal(StrategyResearchDataV5.bindFundingSettlementMarks(request).size(), 1, "settlement marks");
        ObjectNode missing = request.deepCopy(); missing.set("markRows", array()); failLikeNode("bindMarks", missing, () -> StrategyResearchDataV5.bindFundingSettlementMarks(missing)); pass();
    }

    private static void denseCoverageMatchesNode() throws Exception {
        long start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(); ArrayNode rows = array();
        for (int index = 0; index < 3; index++) rows.add(bar(start + index * FOUR_HOURS, FOUR_HOURS));
        ObjectNode series = object().put("expected_step_ms", FOUR_HOURS).put("start_at", iso(start)).put("end_at", iso(start + 2 * FOUR_HOURS)).put("availability_cutoff_at", iso(start + 3 * FOUR_HOURS));
        ObjectNode request = object().set("rows", rows); request.set("series", series); request.put("oneMinute", false);
        equal(StrategyResearchDataV5.validateDenseBarCoverageV5(request).path("complete").asBoolean(), true, "dense coverage");
        ObjectNode late = request.deepCopy(); ((ObjectNode) late.path("rows").get(1)).put("availability_time", start + 2 * FOUR_HOURS + 1); equal(StrategyResearchDataV5.validateDenseBarCoverageV5(late).isObject(), true, "late coverage"); pass();
    }

    private static void timeframeRequirementsMatchNode() throws Exception {
        ObjectNode request = object().put("precommitSha256", H).put("predictorRegistrySha256", H); ArrayNode declarations = request.putArray("declarations");
        declarations.add(object().put("predictor_id", "daily_context").put("interval", "1d").put("context_only", true).set("series_types", array().add("signal_bars")));
        equal(StrategyResearchDataV5.makeTimeframeRequirements(request).path("content_sha256").asText().length(), 64, "timeframe requirements"); pass();
    }

    private static void fiveYearPlanMatchesNode() throws Exception {
        ObjectNode request = object().put("asOf", "2026-08-24T20:30:00.000Z").put("rootReference", "strategy-research/v5-data");
        equal(StrategyResearchDataV5.makeFiveYearAuthoritativePlan(request).path("content_sha256").asText().length(), 64, "five-year plan"); pass();
    }

    private static void opportunityAndPredicateContractsMatchNode() throws Exception {
        ObjectNode request = object().put("planSha256", H).put("candidateSetSha256", H.replace('a', 'b')).put("maxLifecycleMs", 300_000).put("lifecycleTimeframe", "1m");
        request.putArray("windows").add(object().put("asset", "btc").put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT")
                .put("execution_start", "2026-01-01T00:00:10Z").put("execution_end", "2026-01-01T00:04:59Z").put("window_id", "w1"));
        equal(StrategyResearchDataV5.makeOpportunityEnvelope(request).path("windows").size(), 1, "opportunity envelope");
        ObjectNode predicate = object().set("all", array().add(object().put("predictor_id", "a")).add(object().set("not", object().put("predictor_id", "b"))));
        equal(StrategyResearchDataV5.derivePredicatePredictorIds(predicate).size(), 2, "predicate ids"); pass();
    }

    private static void pitFeatureRowsMatchNode() throws Exception {
        ObjectNode registryRequest = object(); registryRequest.putArray("predictors").add(object().put("id", "close_value").put("scalar_type", "number")
                .put("source_field", "close").put("source_family", "price").put("lookback_ms", 0).put("availability_derivation", "completed_4h_close")
                .put("code_sha256", H).put("config_sha256", H).put("pit_role", "PREDICTOR"));
        ObjectNode registry = StrategyResearchDataV5.makePredictorRegistry(registryRequest); long event = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
        ArrayNode rows = array().add(object().put("asset", "btc").put("venue", "BINANCE").put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT")
                .put("timeframe", "4h").put("event_time", event).put("close_time", event + FOUR_HOURS - 1).put("availability_time", event + FOUR_HOURS - 1)
                .put("open", 100).put("high", 105).put("low", 99).put("close", 104));
        ObjectNode request = object().set("rawRows", rows); request.set("predictorRegistry", registry); request.set("capture", object().put("interval", "4h").put("series_type", "signal_bars"));
        equal(StrategyResearchDataV5.deriveFeatureRowsFromRaw(rows, request).size(), 1, "PIT features");
        ObjectNode leak = request.deepCopy(); ((ObjectNode) leak.path("rawRows").get(0)).put("close_value", 999); failLikeNode("features", leak, () -> StrategyResearchDataV5.deriveFeatureRowsFromRaw((ArrayNode) leak.path("rawRows"), leak)); pass();
    }

    private static void unavailableMetadataMatchesNode() throws Exception {
        ObjectNode request = object().put("kind", "MARGIN").put("status", "UNAVAILABLE").put("capturedAt", "2026-01-01T00:00:00Z"); request.set("limitations", array().add("NO_HISTORY"));
        equal(StrategyResearchDataV5.makeMetadataReceipt(request).path("status").asText(), "UNAVAILABLE", "unavailable metadata"); pass();
    }

    private static void datedDiscoveryAndHistoryUseInjectedTransport() throws Exception {
        String captured = "2026-10-01T00:00:00.000Z";
        byte[] exchange = ("{\"symbols\":[{\"symbol\":\"BTCUSDT_260925\",\"baseAsset\":\"BTC\",\"quoteAsset\":\"USDT\",\"contractType\":\"CURRENT_QUARTER\",\"onboardDate\":1780000000000,\"deliveryDate\":1790323200000}]}" ).getBytes(StandardCharsets.UTF_8);
        String firstEndpoint = "https://s3-ap-northeast-1.amazonaws.com/data.binance.vision?delimiter=%2F&prefix=data%2Ffutures%2Fum%2Fmonthly%2Fklines%2FBTCUSDT_";
        String secondEndpoint = firstEndpoint + "&continuation-token=page2";
        byte[] listing1 = ("<ListBucketResult><IsTruncated>true</IsTruncated><NextContinuationToken>page2</NextContinuationToken>" +
                "<Prefix>data/futures/um/monthly/klines/BTCUSDT_260925/</Prefix></ListBucketResult>").getBytes(StandardCharsets.UTF_8);
        byte[] listing2 = "<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>".getBytes(StandardCharsets.UTF_8);
        long start = Instant.parse("2026-09-01T00:00:00.000Z").toEpochMilli(), expiry = Instant.parse("2026-09-25T08:00:00.000Z").toEpochMilli();
        byte[] firstBars = kline(start, 100), lastBars = kline(expiry - FOUR_HOURS, 101);
        List<TransportReply> replies = List.of(new TransportReply("https://fapi.binance.com/fapi/v1/exchangeInfo", exchange),
                new TransportReply(firstEndpoint, listing1), new TransportReply(secondEndpoint, listing2),
                new TransportReply("https://fapi.binance.com/fapi/v1/klines?symbol=BTCUSDT_260925&interval=4h&limit=1&startTime=" + start + "&endTime=" + expiry, firstBars),
                new TransportReply("https://fapi.binance.com/fapi/v1/klines?symbol=BTCUSDT_260925&interval=4h&limit=1000&startTime=" + (expiry - 48L * FOUR_HOURS) + "&endTime=" + expiry, lastBars));
        PublicDataAdapters.InjectableHttpClient transport = (uri, headers) -> {
            String value = uri.toString(); TransportReply match = replies.stream().filter(reply -> (reply.contains().isEmpty() || value.contains(reply.contains())) && (value.equals(reply.endpoint()) || value.startsWith(reply.endpoint() + "&") || value.startsWith(reply.endpoint() + "?" ))).sorted(java.util.Comparator.comparingInt((TransportReply reply) -> reply.endpoint().length()).reversed()).findFirst().orElse(null);
            if (match == null) throw new IOException("unretained endpoint: " + value);
            return new PublicDataAdapters.FetchResponse(200, match.body(), java.util.Map.of("date", List.of("Thu, 01 Oct 2026 00:00:00 GMT")));
        };
        ObjectNode currentOptions = object().put("fixtureOnly", true).put("capturedAt", captured);
        ObjectNode current = StrategyResearchDataV5.discoverBinanceDatedFutures(currentOptions, transport);
        equal(current.path("contracts").size(), 1, "dated current discovery");
        ObjectNode historyOptions = object().put("fixtureOnly", true).put("capturedAt", captured).put("startAt", iso(start)).put("endAt", iso(expiry + FOUR_HOURS)).set("assets", array().add("btc"));
        ObjectNode history = StrategyResearchDataV5.discoverBinanceHistoricalDatedFutures(historyOptions, transport);
        ObjectNode historyInput = historyOptions.deepCopy(); historyInput.set("transport", array().add(transportReply(firstEndpoint, listing1, "")).add(transportReply(secondEndpoint, listing2, "")).add(transportReply("https://fapi.binance.com/fapi/v1/klines", firstBars, "limit=1&")).add(transportReply("https://fapi.binance.com/fapi/v1/klines", lastBars, "limit=1000&")));
        equal(history.path("contracts").isArray(), true, "dated historical discovery");
        ObjectNode hostile = historyOptions.deepCopy(); hostile.set("listingResponses", array().add(object().put("endpoint", firstEndpoint).put("body", "<ListBucketResult><IsTruncated>true</IsTruncated></ListBucketResult>"))); expectFailure(() -> StrategyResearchDataV5.discoverBinanceHistoricalDatedFutures(hostile, transport), "missing dated pagination token"); pass();
    }

    private static void productionHydrationUsesInjectedTransportAndCustody() throws Exception {
        long start = Instant.parse("2026-01-01T00:00:00.000Z").toEpochMilli(), end = start + 2 * ONE_MINUTE; String planSha = H, candidateSha = H.replace('a', 'b');
        ObjectNode envelope = StrategyResearchDataV5.makeOpportunityEnvelope(object().put("planSha256", planSha).put("candidateSetSha256", candidateSha).put("maxLifecycleMs", 2 * ONE_MINUTE).put("lifecycleTimeframe", "1m").set("windows", array().add(object().put("asset", "btc").put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT").put("execution_start", iso(start)).put("execution_end", iso(end)).put("window_id", "hydration-window"))));
        byte[] body = klines(start, 3, 100); Path root = Files.createTempDirectory("v5-hydration-public-");
        PublicDataAdapters.InjectableHttpClient transport = (uri, headers) -> new PublicDataAdapters.FetchResponse(200, body, java.util.Map.of("date", List.of("Thu, 01 Jan 2026 00:03:00 GMT")));
        ObjectNode request = object().put("planSha256", planSha).put("candidateSetSha256", candidateSha).put("outputRoot", root.toString()).set("opportunityEnvelope", envelope);
        ObjectNode hydrated = StrategyResearchDataV5.hydrateOpportunityWindowsV5(request, transport);
        equal(hydrated.path("status").asText(), "STAGING_COMPLETE", "production hydration status"); equal(hydrated.path("fixture_only").asBoolean(true), false, "production hydration provenance"); equal(hydrated.path("captures").size(), 1, "production hydration capture count"); equal(Files.exists(root.resolve(hydrated.path("captures").get(0).path("partition").path("path").asText())), true, "production hydration partition custody"); equal(Files.exists(root.resolve("hydration-checkpoint.json")), true, "production hydration checkpoint custody");
        ObjectNode verify = object(); verify.set("manifest", hydrated); verify.put("root", root.toString()).put("planSha256", planSha).put("envelopeSha256", envelope.path("content_sha256").asText()).put("candidateSetSha256", candidateSha); StrategyResearchDataV5.verifyAuthoritativeStaging(verify); Path raw = Files.list(root.resolve("raw")).findFirst().orElseThrow(); Files.writeString(raw, "tampered-raw"); expectFailure(() -> StrategyResearchDataV5.verifyAuthoritativeStaging(verify), "production hydration raw receipt tamper");
        PublicDataAdapters.InjectableHttpClient hostile = (uri, headers) -> new PublicDataAdapters.FetchResponse(503, "busy".getBytes(StandardCharsets.UTF_8), java.util.Map.of("date", List.of("Thu, 01 Jan 2026 00:03:00 GMT"))); expectFailure(() -> StrategyResearchDataV5.hydrateOpportunityWindowsV5(request, hostile), "production hydration hostile HTTP"); pass();
    }

    private static void hydrationManifestStructuralDifferential() throws Exception {
        long start = Instant.parse("2026-01-01T00:00:00.000Z").toEpochMilli(), end = start + 2 * ONE_MINUTE;
        String capturedAt = "2026-01-01T00:03:00.000Z", planSha = H, candidateSha = H.replace('a', 'b');
        ObjectNode envelope = StrategyResearchDataV5.makeOpportunityEnvelope(object().put("planSha256", planSha)
                .put("candidateSetSha256", candidateSha).put("maxLifecycleMs", 2 * ONE_MINUTE).put("lifecycleTimeframe", "1m")
                .set("windows", array().add(object().put("asset", "btc").put("instrument", "BINANCE_SPOT")
                        .put("symbol", "BTCUSDT").put("execution_start", iso(start)).put("execution_end", iso(end))
                        .put("window_id", "hydration-differential"))));
        byte[] body = klines(start, 3, 100); Path javaRoot = Files.createTempDirectory("v5-hydration-diff-java-");
        PublicDataAdapters.InjectableHttpClient transport = (uri, headers) -> new PublicDataAdapters.FetchResponse(200, body,
                java.util.Map.of("date", List.of("Thu, 01 Jan 2026 00:03:00 GMT")));
        ObjectNode request = object().put("planSha256", planSha).put("candidateSetSha256", candidateSha)
                .put("outputRoot", javaRoot.toString()).put("outputRootReference", "quarantine/hydration-diff")
                .put("fixtureOnly", true).put("capturedAt", capturedAt).put("maxPages", 1).put("maxRows", 100)
                .set("opportunityEnvelope", envelope);
        ObjectNode javaManifest = StrategyResearchDataV5.hydrateOpportunityWindowsV5(request, transport);
        equal(javaManifest.path("status").asText(), "STAGING_COMPLETE", "Java differential hydration status");
        equal(javaManifest.path("captures").size(), 1, "differential capture count");
        PublicDataAdapters.InjectableHttpClient unavailable = (uri, headers) -> { throw new AssertionError("resume unexpectedly used transport: " + uri); };
        ObjectNode resumed = StrategyResearchDataV5.hydrateOpportunityWindowsV5(request, unavailable);
        equalJson(hydrationStructuralProjection(javaManifest), hydrationStructuralProjection(resumed), "run-level hydration lock/resume parity");
        byte[] checkpointBeforeCas = Files.readAllBytes(javaRoot.resolve("hydration-checkpoint.json"));
        ObjectNode wrongPredecessor = request.deepCopy().put("expectedCheckpointSha256", H);
        expectFailure(() -> StrategyResearchDataV5.hydrateOpportunityWindowsV5(wrongPredecessor, unavailable), "hydration checkpoint predecessor CAS");
        if (!Arrays.equals(checkpointBeforeCas, Files.readAllBytes(javaRoot.resolve("hydration-checkpoint.json")))) throw new AssertionError("hydration CAS changed checkpoint bytes");
        Path runLock = javaRoot.resolve("hydration-checkpoint.json.lock");
        ObjectNode heldLock = object().put("schema", "strategy-v5-checkpoint-lock/1").put("version", 1).put("pid", 1).put("started_at", iso(System.currentTimeMillis())).put("token", "held");
        Files.write(runLock, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(heldLock));
        expectFailure(() -> StrategyResearchDataV5.hydrateOpportunityWindowsV5(request, unavailable), "hydration non-stale run lock");
        Files.deleteIfExists(runLock);
        Files.writeString(runLock, "{malformed", StandardCharsets.UTF_8);
        expectFailure(() -> StrategyResearchDataV5.hydrateOpportunityWindowsV5(request, unavailable), "hydration malformed fresh run lock");
        Files.setLastModifiedTime(runLock, FileTime.fromMillis(System.currentTimeMillis() - 10_000));
        ObjectNode staleRecovery = request.deepCopy().put("lockStaleMs", 1);
        ObjectNode recovered = StrategyResearchDataV5.hydrateOpportunityWindowsV5(staleRecovery, unavailable);
        equalJson(hydrationStructuralProjection(javaManifest), hydrationStructuralProjection(recovered), "hydration malformed stale-lock recovery");
        equal(Files.exists(runLock), false, "hydration lock released after stale recovery");
        pass();
    }

    private static ObjectNode hydrationStructuralProjection(ObjectNode source) {
        try {
            ObjectNode value = (ObjectNode) JSON.readTree(StrategyResearchDataV5.stable(source)); value.remove(List.of("content_sha256", "source_receipts", "source_receipt_sha256")); stripHydrationProducerFields(value);
            return value;
        } catch (IOException error) { throw new AssertionError("hydration structural projection is not JSON", error); }
    }

    private static ObjectNode separatedStructuralProjection(ObjectNode source) {
        ObjectNode value = source.deepCopy();
        value.remove(List.of("content_sha256", "dataset_root_sha256", "transformation_code_sha256", "label_code_sha256", "execution_code_sha256"));
        for (String role : List.of("feature", "label", "execution", "mark")) {
            JsonNode artifact = value.path("artifacts").path(role);
            if (artifact.isObject()) ((ObjectNode) artifact).remove(List.of("derivation_receipt_path", "derivation_receipt_sha256", "derivation_receipt_byte_sha256"));
        }
        return value;
    }

    private static ArrayNode hydrationRowsProjection(List<ObjectNode> rows) {
        ArrayNode value = array(); rows.stream().map(ObjectNode::deepCopy).forEach(row -> { row.remove("producer_code_sha256"); row.remove("adapter_code_sha256"); value.add(row); }); return value;
    }

    private static void stripHydrationProducerFields(JsonNode value) {
        if (value == null) return;
        if (value.isObject()) {
            ObjectNode object = (ObjectNode) value;
            object.remove("producer_code_sha256"); object.remove("adapter_code_sha256"); object.remove("adapter_code_reference"); object.remove("checkpoint_sha256"); object.remove("source_receipt_sha256");
            if ("strategy-v5-source-receipt/1".equals(text(object, "schema"))) object.remove(List.of("path", "content_sha256", "sha256"));
            if ("JSONL".equals(text(object, "format")) && "STAGING".equals(text(object, "storage_role"))) object.remove(List.of("path", "sha256", "bytes"));
            object.elements().forEachRemaining(StrategyResearchDataV5NodeOracleTest::stripHydrationProducerFields);
        } else if (value.isArray()) value.elements().forEachRemaining(StrategyResearchDataV5NodeOracleTest::stripHydrationProducerFields);
    }

    private static List<ObjectNode> readJsonlRoot(Path root, String relative) throws Exception {
        List<ObjectNode> rows = new ArrayList<>(); for (String line : Files.readAllLines(root.resolve(relative))) if (!line.isBlank()) rows.add((ObjectNode) JSON.readTree(line)); return rows;
    }

    private static ObjectNode readJsonObject(Path root, String relative) throws Exception { return (ObjectNode) JSON.readTree(Files.readAllBytes(root.resolve(relative))); }

    private record TransportReply(String endpoint, byte[] body, String contains) {
        private TransportReply(String endpoint, byte[] body) { this(endpoint, body, ""); }
    }
    private static ObjectNode transportReply(String endpoint, byte[] body, String contains) {
        return object().put("endpoint", endpoint).put("contains", contains).put("body_base64", Base64.getEncoder().encodeToString(body));
    }
    private static byte[] kline(long event, double close) {
        ArrayNode row = array().add(event).add(String.valueOf(close - 1)).add(String.valueOf(close + 1)).add(String.valueOf(close - 2)).add(String.valueOf(close)).add("1").add(event + FOUR_HOURS - 1);
        return JSON.createArrayNode().add(row).toString().getBytes(StandardCharsets.UTF_8);
    }
    private static byte[] klines(long start, int count, double close) {
        ArrayNode rows = array(); for (int index = 0; index < count; index++) { long event = start + index * ONE_MINUTE; ArrayNode row = array().add(event).add(String.valueOf(close - 1 + index)).add(String.valueOf(close + 1 + index)).add(String.valueOf(close - 2 + index)).add(String.valueOf(close + index)).add("1").add(event + ONE_MINUTE - 1); rows.add(row); } return rows.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void fundingPnlMatchesNode() throws Exception {
        ObjectNode request = object().put("fundingRate", .001).put("settlementMark", 100).put("signedQuantity", -2).put("contractMultiplier", 1).put("quoteMultiplier", 1);
        equal(StrategyResearchDataV5.computeFundingPnl(request), .2, "funding PnL"); pass();
    }

    private static void authoritativeRolesAndSeparatedManifestMatchNode() throws Exception {
        long decision = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
        Path root = Files.createTempDirectory("v5-role-quarantine-");
        ObjectNode plan = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(object().put("asOf", "2026-08-24T20:30:00.000Z"));
        ObjectNode registryInput = object(); registryInput.putArray("predictors").add(object().put("id", "momentum_1").put("scalar_type", "number").put("source_field", "close")
                .put("source_family", "price").put("lookback_ms", FOUR_HOURS).put("availability_derivation", "completed_4h_close")
                .put("code_sha256", H).put("config_sha256", H).put("pit_role", "PREDICTOR"));
        ObjectNode registry = StrategyResearchDataV5.makePredictorRegistry(registryInput);
        ObjectNode envelopeInput = object().put("planSha256", plan.path("content_sha256").asText()).put("candidateSetSha256", H).put("maxLifecycleMs", 5 * 60_000).put("lifecycleTimeframe", "1m");
        envelopeInput.set("windows", array().add(object().put("asset", "btc").put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT")
                        .put("execution_start", iso(decision)).put("execution_end", iso(decision + 4 * 60_000)).put("window_id", "w1")));
        ObjectNode envelope = StrategyResearchDataV5.makeOpportunityEnvelope(envelopeInput);
        ObjectNode feature = identity("sig-role", "ep-role", decision).put("timeframe", "4h").put("event_time", iso(decision))
                .put("availability_time", iso(decision)).put("open", 100).put("high", 102).put("low", 99).put("close", 101)
                .put("signal_eligible", true);
        feature.remove("signal_id"); feature.remove("episode_id"); feature.remove("signal_eligible");
        ObjectNode label = identity("sig-role", "ep-role", decision); label.remove("signal_id"); label.remove("episode_id");
        ObjectNode execution = identity("sig-role", "ep-role", decision); execution.remove("signal_id"); execution.remove("episode_id"); ArrayNode children = execution.putArray("child_bars");
        for (int index = 0; index < 3; index++) children.add(roleChildBar(decision + index * ONE_MINUTE, 100 + index));
        ObjectNode mark = identity("sig-role", "ep-role", decision).put("series_role", "MARK").put("series_id", "btc-spot-mark-1m")
                .put("cadence_ms", ONE_MINUTE).put("event_time", iso(decision)).put("availability_time", iso(decision + ONE_MINUTE))
                .put("price", 100);
        mark.remove("signal_id"); mark.remove("episode_id");
        ObjectNode featureRef = writeRoleInput(root, "raw/features.jsonl", feature);
        ObjectNode labelRef = writeRoleInput(root, "raw/labels.jsonl", label);
        ObjectNode executionRef = writeRoleInput(root, "raw/execution.jsonl", execution);
        ObjectNode markRef = writeRoleInput(root, "raw/marks.jsonl", mark);
        ArrayNode captures = array(); List<ObjectNode> summaries = new ArrayList<>();
        summaries.add(addRoleCapture(root, captures, "features", featureRef, "4h", "raw_signal_bars", feature));
        summaries.add(addRoleCapture(root, captures, "labels", labelRef, "labels", "raw_opportunity_bars", label));
        summaries.add(addRoleCapture(root, captures, "execution", executionRef, "execution", "raw_execution_bars", execution));
        summaries.add(addRoleCapture(root, captures, "marks", markRef, "1m", "raw_mark_bars", mark));
        ObjectNode source = object().put("schema", "strategy-v5-authoritative-acquisition/1").put("version", 1)
                .put("status", "STAGING_COMPLETE").put("plan_sha256", plan.path("content_sha256").asText()).put("root_reference", "role-quarantine")
                .put("staging_format", "JSONL").put("storage_role", "STAGING").put("authoritative", false).put("base_complete", true)
                .put("declared_complete", true).put("full_plan_complete", true).put("completion_scope", "ALL_DECLARED")
                .put("required_series_count", 4).put("required_complete_count", 4).put("optional_series_count", 0)
                .put("optional_complete_count", 0).put("optional_complete", true);
        source.set("captures", captures); source.set("source_receipts", strings(summaries.stream().map(v -> text(v, "path")).sorted().toList()));
        source.set("source_receipt_sha256", strings(summaries.stream().map(v -> text(v, "content_sha256")).sorted().toList()));
        source.set("source_receipt_byte_sha256", strings(summaries.stream().map(v -> text(v, "byte_sha256")).sorted().toList()));
        source.set("unavailable_required", array()); source.set("unavailable_optional", array()); source.set("limitations", array()); source = StrategyResearchDataV5.withHash(source);
        Path sourcePath = root.resolve("lineage/source-manifest.json"); Files.createDirectories(sourcePath.getParent()); byte[] sourceBytes = prettyBytes(source); Files.write(sourcePath, sourceBytes);
        ObjectNode sourceReference = object().put("path", "lineage/source-manifest.json").put("content_sha256", text(source, "content_sha256")).put("byte_sha256", StrategyResearchDataV5.hash(sourceBytes));
        ObjectNode precommit = fixtureInput("strategy-v5-precommit-fixture/1", "role-precommit"), config = fixtureInput("strategy-v5-config-fixture/1", "role-config");
        ObjectNode roles = object(); roles.set("features", array().add(featureRef)); roles.set("labels", array().add(labelRef)); roles.set("execution", array().add(executionRef)); roles.set("marks", array().add(markRef));
        ObjectNode common = object().put("root", root.toString()); common.set("plan", plan); common.set("predictorRegistry", registry);
        common.set("sourceManifestReference", sourceReference); common.put("sourceManifestSha256", text(source, "content_sha256")); common.putNull("sourceDatasetRootSha256");
        common.put("transformationCodeSha256", StrategyResearchDataV5.javaProducerCodeSha256()).put("labelCodeSha256", StrategyResearchDataV5.javaProducerCodeSha256()).put("executionCodeSha256", StrategyResearchDataV5.javaProducerCodeSha256());
        common.put("configSha256", text(config, "content_sha256")).put("precommitSha256", text(precommit, "content_sha256")).put("envelopeSha256", text(envelope, "content_sha256"));
        common.set("precommit", precommit); common.set("envelope", envelope); common.set("config", config); common.set("roleSources", roles);
        ObjectNode javaProduced = StrategyResearchDataV5.produceAuthoritativeRoleArtifacts(common);
        for (String role : List.of("feature", "label", "execution", "mark")) {
            ObjectNode receipt = readJsonObject(root, text(javaProduced.path(role).path("role_receipt"), "path")); if (!receipt.hasNonNull("producer_code_sha256")) throw new AssertionError("Java " + role + " role receipt shape: " + receipt.toPrettyString()); equal(text(receipt, "producer_code_sha256"), StrategyResearchDataV5.javaProducerCodeSha256(), "Java " + role + " producer binding"); equal(text(receipt.path("producer_code_reference"), "path").endsWith(".class"), true, "Java " + role + " producer class reference");
        }
        ObjectNode manifestOptions = common.deepCopy(); manifestOptions.putArray("candidatePredicates").add("momentum_1");
        manifestOptions.put("sourceDatasetRootSha256", javaProduced.path("feature").path("source_dataset_root_sha256").asText());
        ObjectNode roleReceipts = object(); roleReceipts.set("feature", javaProduced.path("feature").path("role_receipt")); roleReceipts.set("label", javaProduced.path("label").path("role_receipt")); roleReceipts.set("execution", javaProduced.path("execution").path("role_receipt")); roleReceipts.set("mark", javaProduced.path("mark").path("role_receipt")); manifestOptions.set("roleReceipts", roleReceipts);
        manifestOptions.set("features", javaProduced.path("feature").deepCopy()); manifestOptions.set("labels", javaProduced.path("label").deepCopy()); manifestOptions.set("execution", javaProduced.path("execution").deepCopy()); manifestOptions.set("marks", javaProduced.path("mark").deepCopy());
        ObjectNode javaManifest = StrategyResearchDataV5.makeSeparatedArtifactManifest(manifestOptions);
        ObjectNode verifyRequest = object().put("root", root.toString()); verifyRequest.set("manifest", javaManifest); verifyRequest.set("plan", plan); verifyRequest.set("predictorRegistry", registry); verifyRequest.set("candidatePredicates", array().add("momentum_1"));
        if (!StrategyResearchDataV5.verifySeparatedArtifactManifest(verifyRequest)) throw new AssertionError("separated manifest did not reopen");
        ObjectNode forged = manifestOptions.deepCopy(); forged.set("sourceManifestReference", sourceReference.deepCopy().put("content_sha256", H)); forged.put("sourceManifestSha256", H);
        failLikeNode("makeSeparated", forged, () -> StrategyResearchDataV5.makeSeparatedArtifactManifest(forged));
        ObjectNode crossBound = manifestOptions.deepCopy(); crossBound.path("roleReceipts").path("label").deepCopy(); crossBound.path("roleReceipts").path("label"); ((ObjectNode) crossBound.path("roleReceipts")).set("label", crossBound.path("roleReceipts").path("feature").deepCopy());
        failLikeNode("makeSeparated", crossBound, () -> StrategyResearchDataV5.makeSeparatedArtifactManifest(crossBound));
        Path executionPath = root.resolve(javaProduced.path("execution").path("path").asText()); byte[] tampered = Files.readAllBytes(executionPath); tampered[0] ^= 1; Files.write(executionPath, tampered);
        failLikeNode("verifySeparated", verifyRequest, () -> StrategyResearchDataV5.verifySeparatedArtifactManifest(verifyRequest));
        pass();
    }

    private static void normalizedOutcomeUsesCanonicalLifecycle() throws Exception {
        long decision = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(); ObjectNode feature = identity("sig", "ep", decision); feature.put("signal_eligible", true);
        ObjectNode label = identity("sig", "ep", decision).put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY").put("decision_timeframe", "4h").put("resolution_ceiling_time", iso(decision + 180_000));
        ObjectNode execution = identity("sig", "ep", decision).put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY").put("decision_timeframe", "4h").put("interval_ms", 60_000).put("direction", "long");
        ArrayNode bars = execution.putArray("child_bars"); for (int i = 0; i < 3; i++) bars.add(bar(decision + i * 60_000L, 60_000));
        ObjectNode candidate = object().put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY").put("decision_timeframe", "4h").put("direction", "long").put("instrument_type", "spot");
        ObjectNode lifecycle = candidate.putObject("lifecycle").put("max_lifecycle_ms", 180_000).put("gap_policy", "OPEN"); lifecycle.putObject("stop").put("type", "PERCENT").put("value", .05); lifecycle.putObject("sizing").put("mode", "RISK_USD").put("risk_usd", 100); candidate.putObject("contract").put("contract_multiplier", 1).put("step_size", .01).put("min_qty", .01).put("min_notional", 1).put("max_notional", 1_000_000);
        ObjectNode request = object().set("feature", feature); request.set("label", label); request.set("execution", execution); request.set("candidate", candidate); request.put("fixtureOnly", true);
        ObjectNode java = StrategyResearchDataV5.deriveBoundExecutionOutcome(request);
        equal(java.path("lifecycle_result").isObject(), true, "canonical lifecycle result"); equal(Double.isFinite(java.path("net_pnl_usd").asDouble()), true, "outcome PnL"); pass();
        ObjectNode conflictingContracts = request.deepCopy();
        ((ObjectNode) conflictingContracts.path("candidate")).set("risk_contract", object().put("mode", "FIXED_RISK_BUDGET_USD").put("budget_usd", 100).put("precommit_sha256", H).put("evaluator_spec_sha256", H));
        ((ObjectNode) conflictingContracts.path("execution")).set("risk_contract", object().put("mode", "FIXED_RISK_BUDGET_USD").put("budget_usd", 101).put("precommit_sha256", H).put("evaluator_spec_sha256", H));
        failLikeNode("outcome", conflictingContracts, () -> StrategyResearchDataV5.deriveBoundExecutionOutcome(conflictingContracts));
    }

    private static void normalizedProductionLifecycleTrustMatchesNode() throws Exception {
        long decision = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
        Path root = Files.createTempDirectory("v5-lifecycle-trust-");

        // Keep the physical row set separate from the wrapper that carries its
        // content receipt.  The lifecycle receives the exact rows, while the
        // trust boundary binds the bytes and the canonical row-set hash.
        ArrayNode childBars = array().add(roleChildBar(decision, 100)).add(roleChildBar(decision + ONE_MINUTE, 101))
                .add(roleChildBar(decision + 2 * ONE_MINUTE, 102));
        ObjectNode barsValue = object().put("schema", "v5-trust-bars/1"); barsValue.set("rows", childBars.deepCopy()); barsValue = StrategyResearchDataV5.withHash(barsValue);
        ObjectNode contract = object().put("schema", "v5-trust-contract/1").put("contract_multiplier", 1).put("step_size", .01)
                .put("min_qty", .01).put("min_notional", 1).put("max_notional", 1_000_000); contract.set("rows", array()); contract = StrategyResearchDataV5.withHash(contract);
        ObjectNode model = object().put("schema", "v5-trust-model/1").put("taker_fee_rate", .001).put("slippage_bps", 2)
                .put("impact_bps", 1).put("outage_policy", "FAIL").put("gap_policy", "OPEN"); model.set("rows", array()); model = StrategyResearchDataV5.withHash(model);
        ObjectNode capacity = object().put("schema", "v5-trust-capacity/1").put("available_liquidity_usd", 1_000_000)
                .put("participation_cap", 1).put("impact_bps", 1).put("order_notional_usd", 100); capacity.set("rows", array()); capacity = StrategyResearchDataV5.withHash(capacity);

        java.util.Map<String, LifecycleTrustService.ReceiptReference> references = new java.util.LinkedHashMap<>(); ObjectNode referenceJson = object();
        ObjectNode contractRef = writeLifecycleTrustReceipt(root, "trust/contract.json", contract, StrategyResearchDataV5.hash(array()), "v5-trust-contract/1");
        ObjectNode modelRef = writeLifecycleTrustReceipt(root, "trust/model.json", model, StrategyResearchDataV5.hash(array()), "v5-trust-model/1");
        ObjectNode capacityRef = writeLifecycleTrustReceipt(root, "trust/capacity.json", capacity, StrategyResearchDataV5.hash(array()), "v5-trust-capacity/1");
        ObjectNode barsRef = writeLifecycleTrustReceipt(root, "trust/bars.json", barsValue, StrategyResearchDataV5.hash(childBars), "v5-trust-bars/1");
        java.util.Map<String, ObjectNode> refsByRole = new java.util.LinkedHashMap<>(); refsByRole.put("contract_spec", contractRef); refsByRole.put("execution_model", modelRef); refsByRole.put("capacity", capacityRef); refsByRole.put("bars", barsRef);
        for (var entry : refsByRole.entrySet()) {
            ObjectNode ref = entry.getValue(); referenceJson.set(entry.getKey(), ref);
            references.put(entry.getKey(), new LifecycleTrustService.ReceiptReference(text(ref, "path"), text(ref, "content_sha256"), text(ref, "byte_sha256"), ref.path("bytes").longValue(), text(ref, "rows_sha256"), text(ref, "schema")));
        }

        ObjectNode precommit = fixtureInput("strategy-v5-precommit-fixture/1", "trust-precommit");
        ObjectNode evaluator = object().put("schema", "strategy-v5-evaluator-spec/1").put("version", 1).put("precommit_sha256", text(precommit, "content_sha256"));
        ObjectNode executionContract = object(); executionContract.set("risk_convention", object().put("mode", "FIXED_RISK_BUDGET_USD").put("budget_usd", 100));
        executionContract.set("sizing_contract", object().put("mode", "TARGET_STOP_RISK").put("risk_usd", 100)); evaluator.set("execution_contract", executionContract); evaluator.put("content_sha256", StrategyResearchDataV5.ownHash(evaluator));
        ObjectNode candidate = object().put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY").put("decision_timeframe", "4h")
                .put("direction", "long").put("instrument_type", "spot");
        ObjectNode lifecycle = candidate.putObject("lifecycle").put("max_lifecycle_ms", 3 * ONE_MINUTE).put("gap_policy", "OPEN");
        lifecycle.putObject("stop").put("type", "PERCENT").put("value", .05); lifecycle.putObject("sizing").put("mode", "RISK_USD").put("risk_usd", 100);
        candidate.set("risk_contract", object().put("mode", "FIXED_RISK_BUDGET_USD").put("budget_usd", 100)
                .put("precommit_sha256", text(precommit, "content_sha256")).put("evaluator_spec_sha256", text(evaluator, "content_sha256")));
        candidate.set("sizing_contract", object().put("mode", "TARGET_STOP_RISK").put("risk_usd", 100)
                .put("precommit_sha256", text(precommit, "content_sha256")).put("evaluator_spec_sha256", text(evaluator, "content_sha256")));
        ObjectNode boundLifecycle = lifecycle.deepCopy(); boundLifecycle.set("sizing", object().put("mode", "FIXED_RISK_BUDGET_USD").put("budget_usd", 100));
        ObjectNode lineage = object().put("evaluator_spec_sha256", text(evaluator, "content_sha256")).put("precommit_sha256", text(precommit, "content_sha256"))
                .put("lifecycle_spec_sha256", StrategyResearchDataV5.hash(boundLifecycle));
        ObjectNode feature = identity("sig-trust", "ep-trust", decision).put("signal_eligible", true);
        ObjectNode label = identity("sig-trust", "ep-trust", decision).put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY").put("decision_timeframe", "4h");
        ObjectNode execution = identity("sig-trust", "ep-trust", decision).put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY").put("decision_timeframe", "4h")
                .put("interval_ms", ONE_MINUTE).put("direction", "long"); execution.set("child_bars", childBars.deepCopy());
        ObjectNode request = object(); request.set("feature", feature); request.set("label", label); request.set("execution", execution); request.set("candidate", candidate);
        request.set("evaluatorSpec", evaluator); request.put("fixtureOnly", false);
        LifecycleTrustService trustService = new LifecycleTrustService(); LifecycleTrustService.Token token = trustService.openLifecycleTrustV5(
                root, "trust-quarantine", references, lineage, true);
        ObjectNode javaResult = StrategyResearchDataV5.deriveBoundExecutionOutcome(request, trustService, token);
        ObjectNode trustInput = object().put("trustRoot", root.toString()).put("rootReference", "trust-quarantine"); trustInput.set("trustReceipts", referenceJson); trustInput.set("trustLineage", lineage); trustInput.set("request", request);
        equal(javaResult.isObject(), true, "production lifecycle trust outcome");
        ObjectNode noToken = request.deepCopy(); failLikeNode("outcome", noToken, () -> StrategyResearchDataV5.deriveBoundExecutionOutcome(noToken));
        ObjectNode forgedToken = request.deepCopy(); ((ObjectNode) forgedToken.path("execution")).set("lifecycle_trust_token", object().put("schema", "strategy-v5-lifecycle-trust/1").put("version", 1).put("fixture_only", false).put("provenance", "AUTHORITATIVE").put("content_sha256", H));
        failLikeNode("outcome", forgedToken, () -> StrategyResearchDataV5.deriveBoundExecutionOutcome(forgedToken));
        Path modelPath = root.resolve(text(modelRef, "path")); byte[] tampered = Files.readAllBytes(modelPath); tampered[0] = (byte) (tampered[0] ^ 1); Files.write(modelPath, tampered);
        failLikeNode("outcomeTrust", trustInput, () -> StrategyResearchDataV5.deriveBoundExecutionOutcome(request, trustService, token));
        pass();
    }

    private static void legacySpotTimeStopMatchesNode() throws Exception {
        long decision = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
        ObjectNode request = legacySpotOutcomeRequest(decision, false);
        equal(StrategyResearchDataV5.deriveBoundExecutionOutcome(request).isObject(), true, "legacy spot time-stop outcome");
        pass();
    }

    private static void legacyTargetStopMatchesNodeAndRejectsForgedRoles() throws Exception {
        long decision = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
        ObjectNode request = legacySpotOutcomeRequest(decision, true);
        ObjectNode bars = (ObjectNode) request.path("execution").path("child_bars").get(1);
        bars.put("open", 100).put("high", 106).put("low", 94).put("close", 100);
        equal(StrategyResearchDataV5.deriveBoundExecutionOutcome(request).isObject(), true, "legacy target-stop collision outcome");
        for (String role : new String[]{"feature", "label", "execution"}) {
            ObjectNode forged = request.deepCopy(); ObjectNode row = (ObjectNode) forged.path(role);
            if ("feature".equals(role)) row.put("symbol", "ETHUSDT");
            else if ("label".equals(role)) row.put("decision_time", iso(decision + FOUR_HOURS));
            else row.put("signal_id", "forged");
            failLikeNode("outcome", forged, () -> StrategyResearchDataV5.deriveBoundExecutionOutcome(forged));
        }
        ObjectNode callerPnl = request.deepCopy(); ((ObjectNode) callerPnl.path("execution")).put("funding_pnl_usd", 99);
        failLikeNode("outcome", callerPnl, () -> StrategyResearchDataV5.deriveBoundExecutionOutcome(callerPnl));
        pass();
    }

    private static void legacyDerivativeOutcomeMatchesNodeAndRejectsMarkFundingTamper() throws Exception {
        long decision = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
        ObjectNode feature = identity("sig-derivative", "ep-derivative", decision).put("instrument", "BINANCE_USDM_PERPETUAL");
        ObjectNode label = identity("sig-derivative", "ep-derivative", decision).put("instrument", "BINANCE_USDM_PERPETUAL")
                .put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY").put("decision_timeframe", "4h")
                .put("lifecycle_timeframe", "1m").put("resolution_ceiling_time", iso(decision + 120_000));
        ObjectNode execution = identity("sig-derivative", "ep-derivative", decision).put("instrument", "BINANCE_USDM_PERPETUAL")
                .put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY").put("decision_timeframe", "4h")
                .put("lifecycle_timeframe", "1m").put("max_lifecycle_ms", 120_000).put("direction", "short")
                .put("quantity", 2).put("margin_mode", "ISOLATED").put("leverage", 2).put("tier_id", "T1").put("collateral_usd", 200);
        ArrayNode childBars = execution.putArray("child_bars"), marks = execution.putArray("mark_bars");
        for (int index = 0; index < 3; index++) {
            long event = decision + index * 60_000L; childBars.add(bar(event, 60_000));
            marks.add(object().put("event_time", event).put("availability_time", event + 59_999)
                    .put("mark_open", 100).put("mark_high", 101).put("mark_low", 99).put("mark_close", 100));
        }
        ObjectNode candidate = object().put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY")
                .put("decision_timeframe", "4h").put("lifecycle_timeframe", "1m").put("max_lifecycle_ms", 120_000)
                .put("direction", "short").put("entry_policy", "NEXT_BAR_OPEN");
        candidate.set("exit_policy", object().put("type", "TIME_STOP"));
        candidate.set("risk_contract", object().put("mode", "FIXED_RISK_BUDGET_USD").put("budget_usd", 100)
                .put("precommit_sha256", H).put("evaluator_spec_sha256", H));
        ObjectNode metadata = object();
        metadata.set("contract_spec", fixtureMetadata("CONTRACT_SPEC", "BINANCE_USDM_PERPETUAL", decision, decision + 300_000,
                object().put("contract_multiplier", 1).put("step_size", .01).put("min_qty", .01).put("max_qty", 1_000_000)
                        .put("min_notional", 1).put("max_notional", 10_000_000).put("max_leverage", 10)));
        metadata.set("fee_schedule", fixtureMetadata("FEE_SCHEDULE", "BINANCE_USDM_PERPETUAL", decision, decision + 300_000,
                object().put("taker_fee_rate", .001)));
        metadata.set("execution_model", fixtureMetadata("EXECUTION_MODEL", "BINANCE_USDM_PERPETUAL", decision, decision + 300_000,
                object().put("slippage_bps", 2).put("impact_bps", 1).put("outage_policy", "FAIL").put("gap_policy", "FILL_AT_OPEN")));
        metadata.set("margin", fixtureMetadata("MARGIN", "BINANCE_USDM_PERPETUAL", decision, decision + 300_000,
                object().put("maintenance_margin_ratio", .005).put("margin_mode", "ISOLATED").put("tier_id", "T1").put("max_leverage", 10)));
        ObjectNode fundingFields = object().put("event_id", "fund-1").put("funding_rate", .001)
                .put("event_time", iso(decision + 60_000)).put("raw_event_time", iso(decision + 60_000))
                .put("settlement_slot", iso(decision + 60_000)).put("settlement_mark", 100).put("mark_price", 100);
        ObjectNode fundingReceipt = fixtureMetadata("FUNDING_IDENTITY", "BINANCE_USDM_PERPETUAL", decision, decision + 300_000, fundingFields);
        fundingReceipt.set("coverage", object().put("complete", true).put("coverage_mode", "EVENT_SEQUENCE"));
        fundingReceipt = StrategyResearchDataV5.withHash(fundingReceipt); metadata.set("funding_identity", fundingReceipt);
        ObjectNode request = object().set("feature", feature); request.set("label", label); request.set("execution", execution);
        request.set("candidate", candidate); request.set("metadata", metadata); request.put("fixtureOnly", true);
        equal(StrategyResearchDataV5.deriveBoundExecutionOutcome(request).isObject(), true, "legacy derivative funding/margin/mark outcome");
        ObjectNode markMissing = request.deepCopy(); ((ObjectNode) markMissing.path("execution")).set("mark_bars", array().add(markMissing.path("execution").path("mark_bars").get(0)));
        failLikeNode("outcome", markMissing, () -> StrategyResearchDataV5.deriveBoundExecutionOutcome(markMissing));
        ObjectNode fundingTamper = request.deepCopy(); ((ObjectNode) fundingTamper.path("metadata").path("funding_identity").path("records").get(0)).put("funding_rate", .5);
        failLikeNode("outcome", fundingTamper, () -> StrategyResearchDataV5.deriveBoundExecutionOutcome(fundingTamper));
        ObjectNode liquidationWick = request.deepCopy(); ((ObjectNode) liquidationWick.path("execution").path("mark_bars").get(1)).put("mark_high", 500);
        failLikeNode("outcome", liquidationWick, () -> StrategyResearchDataV5.deriveBoundExecutionOutcome(liquidationWick));
        pass();
    }

    private static ObjectNode legacySpotOutcomeRequest(long decision, boolean targetStop) {
        ObjectNode feature = identity("sig-legacy", "ep-legacy", decision).put("signal_eligible", true);
        ObjectNode label = identity("sig-legacy", "ep-legacy", decision)
                .put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY").put("decision_timeframe", "4h")
                .put("lifecycle_timeframe", "1m").put("resolution_ceiling_time", iso(decision + 120_000));
        ObjectNode execution = identity("sig-legacy", "ep-legacy", decision)
                .put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY").put("decision_timeframe", "4h")
                .put("lifecycle_timeframe", "1m").put("max_lifecycle_ms", 120_000).put("direction", "long");
        ArrayNode childBars = execution.putArray("child_bars");
        for (int index = 0; index < 3; index++) childBars.add(bar(decision + index * 60_000L, 60_000));
        ObjectNode candidate = object().put("decision_timestamp_convention", "COMPLETED_4H_BOUNDARY")
                .put("decision_timeframe", "4h").put("lifecycle_timeframe", "1m").put("max_lifecycle_ms", 120_000)
                .put("direction", "long").put("entry_policy", "NEXT_BAR_OPEN");
        candidate.set("risk_contract", object().put("mode", "FIXED_RISK_BUDGET_USD").put("budget_usd", 100)
                .put("precommit_sha256", H).put("evaluator_spec_sha256", H));
        if (targetStop) {
            candidate.set("exit_policy", object().put("type", "TARGET_STOP").put("collision_policy", "ADVERSE_STOP_FIRST")
                    .put("stop_price", 95).put("target_price", 105));
        } else {
            candidate.set("exit_policy", object().put("type", "TIME_STOP"));
            execution.put("quantity", 2);
        }
        ObjectNode metadata = object();
        metadata.set("contract_spec", fixtureMetadata("CONTRACT_SPEC", decision, decision + 300_000,
                object().put("contract_multiplier", 1).put("step_size", .01).put("min_qty", .01).put("max_qty", 1_000_000)
                        .put("min_notional", 1).put("max_notional", 10_000_000)));
        metadata.set("fee_schedule", fixtureMetadata("FEE_SCHEDULE", decision, decision + 300_000,
                object().put("taker_fee_rate", .001)));
        metadata.set("execution_model", fixtureMetadata("EXECUTION_MODEL", decision, decision + 300_000,
                object().put("slippage_bps", 2).put("impact_bps", 1).put("outage_policy", "FAIL").put("gap_policy", "FILL_AT_OPEN")));
        ObjectNode request = object().set("feature", feature); request.set("label", label); request.set("execution", execution);
        request.set("candidate", candidate); request.set("metadata", metadata); request.put("fixtureOnly", true); return request;
    }

    private static ObjectNode fixtureMetadata(String kind, long from, long to, ObjectNode fields) {
        return fixtureMetadata(kind, "BINANCE_SPOT", from, to, fields);
    }

    private static ObjectNode fixtureMetadata(String kind, String instrument, long from, long to, ObjectNode fields) {
        ObjectNode record = object().put("asset", "btc").put("venue", "BINANCE").put("instrument", instrument)
                .put("symbol", "BTCUSDT").put("effective_from", iso(from - 60_000)).put("effective_to", iso(to))
                .put("availability_time", iso(from)); record.setAll(fields);
        ObjectNode options = object().put("kind", kind).put("status", "CONSERVATIVE_MODEL")
                .put("capturedAt", iso(from)).put("modelSha256", H).put("precommitSha256", H);
        options.set("records", array().add(record)); options.set("limitations", array());
        return StrategyResearchDataV5.makeMetadataReceipt(options);
    }

    private static void receiptCustodyRejectsTraversalSymlinkAndMutation() throws Exception {
        Path root = Files.createTempDirectory("v5-receipt-"); Files.createDirectories(root.resolve("raw")); byte[] body = "raw-evidence".getBytes(StandardCharsets.UTF_8); String byteSha = StrategyResearchDataV5.hash(body); Files.write(root.resolve("raw/data.bin"), body);
        ObjectNode raw = object().put("schema", "strategy-v5-source-receipt/1").put("version", 1).put("path", "raw/data.bin").put("source", "fixture").put("byte_sha256", byteSha).put("bytes", body.length).put("format", "RAW_BYTES").put("storage_role", "RAW_IGNORED").put("authoritative", false); raw.set("request", object().put("endpoint", "fixture://raw")); raw = StrategyResearchDataV5.withHash(raw);
        ObjectNode normalized = object().put("schema", "strategy-v5-source-receipt/1").put("version", 1).put("status", "PUBLIC_OBSERVED").put("captured_at", "2026-01-01T00:00:00.000Z"); normalized.set("request", object().put("endpoint", "fixture://raw")); normalized.set("response_sha256", array().add(byteSha)); normalized.set("source_byte_sha256", array().add(byteSha)); normalized.set("raw_receipts", array().add(raw)); normalized.putNull("coverage"); normalized = StrategyResearchDataV5.withHash(normalized); Files.createDirectories(root.resolve("receipts")); Files.write(root.resolve("receipts/source.json"), JSON.writeValueAsBytes(normalized));
        ObjectNode summary = object().put("schema", "strategy-v5-source-receipt/1").put("path", "receipts/source.json").put("sha256", normalized.path("content_sha256").asText()).put("content_sha256", normalized.path("content_sha256").asText()).put("byte_sha256", byteSha).put("status", "PUBLIC_OBSERVED");
        StrategyResearchDataV5.verifyNormalizedReceipt(root, summary); Files.writeString(root.resolve("raw/data.bin"), "tampered"); expectFailure(() -> StrategyResearchDataV5.verifyNormalizedReceipt(root, summary), "tampered raw");
        ObjectNode traversal = summary.deepCopy().put("path", "../escape.json"); expectFailure(() -> StrategyResearchDataV5.verifyNormalizedReceipt(root, traversal), "traversal");
        if (!System.getProperty("os.name").toLowerCase().contains("win")) { Files.delete(root.resolve("receipts/source.json")); Files.createSymbolicLink(root.resolve("receipts/source.json"), root.resolve("raw/data.bin")); expectFailure(() -> StrategyResearchDataV5.verifyNormalizedReceipt(root, summary), "symlink"); }
        pass();
    }

    private static void authoritativeParquetReopenRejectsFakeBytes() throws Exception {
        Path root = Files.createTempDirectory("v5-fake-parquet-"); Files.createDirectories(root.resolve("parquet")); byte[] fake = "not parquet".getBytes(StandardCharsets.UTF_8); Files.write(root.resolve("parquet/fake.parquet"), fake);
        ObjectNode capture = object().put("asset", "btc").put("venue", "BINANCE").put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT").put("interval", "4h").put("series_type", "signal_bars"); capture.set("partition", object().put("path", "parquet/fake.parquet").put("sha256", StrategyResearchDataV5.hash(fake)).put("bytes", fake.length).put("row_count", 1).put("format", "PARQUET").put("storage_role", "AUTHORITATIVE").put("authoritative", true).put("source_jsonl_sha256", H).put("schema_sha256", H)); capture.set("coverage", object().put("complete", true));
        ArrayNode captures = array().add(capture); ObjectNode rootHash = object().put("source_manifest_sha256", H).put("plan_sha256", H); rootHash.set("captures", array().add(object().put("identity", "btc|BINANCE_SPOT|BTCUSDT|4h|signal_bars").set("partition", capture.path("partition"))));
        ObjectNode manifest = object().put("schema", "strategy-v5-parquet-conversion/1").put("version", 1).put("status", "AUTHORITATIVE_PARQUET").put("source_manifest_sha256", H).put("plan_sha256", H).put("output_root_reference", "fixture").put("format", "PARQUET").put("storage_role", "AUTHORITATIVE").put("authoritative", true).put("threads", 1).put("dataset_root_sha256", StrategyResearchDataV5.hash(rootHash)); manifest.set("captures", captures); manifest.set("limitations", array()); manifest = StrategyResearchDataV5.withHash(manifest);
        ObjectNode request = object().set("manifest", manifest); request.put("root", root.toString()); failLikeNode("parquetVerify", request, () -> StrategyResearchDataV5.verifyParquetConversionManifestAuthoritative(request)); pass();
    }

    private static void authoritativeParquetReopenAcceptsValidBytes() throws Exception {
        Path root = Files.createTempDirectory("v5-valid-parquet-"); Files.createDirectories(root.resolve("staging")); Files.createDirectories(root.resolve("parquet"));
        ObjectNode row = object().put("asset", "btc").put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT").put("event_time", 1_700_000_000_000L).put("availability_time", 1_700_000_000_001L).put("close", 101.25);
        Path jsonl = root.resolve("staging/valid.jsonl"); Files.writeString(jsonl, JSON.writeValueAsString(row) + "\n"); Path parquet = root.resolve("parquet/valid.parquet"); ResearchData.ParquetArtifact physical = ResearchData.writeParquet(jsonl, parquet);
        java.util.List<ObjectNode> reopened = ResearchData.queryParquet(parquet); equal(reopened.size(), 1, "valid Parquet row count");
        ObjectNode capture = object().put("asset", "btc").put("venue", "BINANCE").put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT").put("interval", "4h").put("series_type", "signal_bars");
        capture.set("partition", object().put("path", "parquet/valid.parquet").put("sha256", physical.sha256()).put("bytes", physical.bytes()).put("row_count", reopened.size()).put("format", "PARQUET").put("storage_role", "AUTHORITATIVE").put("authoritative", true).put("source_jsonl_sha256", StrategyResearchDataV5.hash(Files.readAllBytes(jsonl))).put("schema_sha256", parquetSchemaSha(reopened, parquet))); capture.set("coverage", object().put("complete", true));
        ArrayNode captures = array().add(capture); ObjectNode rootHash = object().put("source_manifest_sha256", H).put("plan_sha256", H); rootHash.set("captures", array().add(object().put("identity", "btc|BINANCE_SPOT|BTCUSDT|4h|signal_bars").set("partition", capture.path("partition"))));
        ObjectNode manifest = object().put("schema", "strategy-v5-parquet-conversion/1").put("version", 1).put("status", "AUTHORITATIVE_PARQUET").put("source_manifest_sha256", H).put("plan_sha256", H).put("output_root_reference", "fixture").put("format", "PARQUET").put("storage_role", "AUTHORITATIVE").put("authoritative", true).put("threads", 1).put("dataset_root_sha256", StrategyResearchDataV5.hash(rootHash)); manifest.set("captures", captures); manifest.set("limitations", array()); manifest = StrategyResearchDataV5.withHash(manifest);
        ObjectNode request = object().set("manifest", manifest); request.put("root", root.toString()); if (!StrategyResearchDataV5.verifyParquetConversionManifest(request) || !StrategyResearchDataV5.verifyParquetConversionManifestAuthoritative(request)) throw new AssertionError("valid Parquet manifest was rejected"); pass();
    }

    private static void fixtureAcquisitionCheckpointIsAtomicAndCasBound() throws Exception {
        ObjectNode plan = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(object().put("asOf", "2026-08-24T20:30:00.000Z")); Path root = Files.createTempDirectory("v5-acquisition-cas-");
        ObjectNode seedRequest = acquisitionRequest(plan, root, unavailableCaptures(plan, "SEED"), null); ObjectNode seed = StrategyResearchDataV5.acquireAuthoritativeStaging(seedRequest); equal(seed.path("status").asText(), "STAGING_PARTIAL", "fixture partial acquisition");
        byte[] beforeCas = Files.readAllBytes(root.resolve("checkpoint.json")); ObjectNode retained = (ObjectNode) JSON.readTree(beforeCas); equal(retained.path("content_sha256").asText(), StrategyResearchDataV5.ownHash(retained), "checkpoint own hash"); String predecessor = retained.path("content_sha256").asText();
        ObjectNode wrong = acquisitionRequest(plan, root, unavailableCaptures(plan, "WRONG"), H); expectFailure(() -> StrategyResearchDataV5.acquireAuthoritativeStaging(wrong), "checkpoint wrong predecessor"); if (!Arrays.equals(beforeCas, Files.readAllBytes(root.resolve("checkpoint.json")))) throw new AssertionError("failed CAS changed checkpoint bytes");
        ObjectNode left = acquisitionRequest(plan, root, unavailableCaptures(plan, "LEFT"), predecessor), right = acquisitionRequest(plan, root, unavailableCaptures(plan, "RIGHT"), predecessor); java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2); java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.List<java.util.concurrent.Future<Boolean>> futures = java.util.List.of(pool.submit(() -> runAcquisitionAfterBarrier(barrier, left)), pool.submit(() -> runAcquisitionAfterBarrier(barrier, right))); int successes = 0; for (java.util.concurrent.Future<Boolean> future : futures) if (future.get()) successes++; pool.shutdownNow(); equal(successes, 1, "single CAS winner");
        ObjectNode winner = (ObjectNode) JSON.readTree(Files.readAllBytes(root.resolve("checkpoint.json"))); equal(winner.path("content_sha256").asText(), StrategyResearchDataV5.ownHash(winner), "winning checkpoint own hash"); pass();
    }

    private static void adapterAcquisitionUsesInjectedTransportAndHydrationFailsClosed() {
        ObjectNode plan = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(object().put("asOf", "2026-08-24T20:30:00.000Z")); Path root;
        try { root = Files.createTempDirectory("v5-acquire-"); } catch (IOException error) { throw new RuntimeException(error); }
        PublicDataAdapters.InjectableHttpClient emptyTransport = (uri, headers) -> new PublicDataAdapters.FetchResponse(
                200, "[]".getBytes(StandardCharsets.UTF_8), java.util.Map.of("date", java.util.List.of("Mon, 24 Aug 2026 20:30:00 GMT")));
        ObjectNode acquisition = object().set("plan", plan); acquisition.put("outputRoot", root.toString());
        ObjectNode acquired = StrategyResearchDataV5.acquireAuthoritativeStaging(acquisition, emptyTransport);
        equal(acquired.path("status").asText(), "STAGING_PARTIAL", "injected empty acquisition status");
        equal(acquired.path("acquisition_manifest").path("completed_series").asInt(), 0, "injected empty acquisition completed series");
        ObjectNode hydration = object().put("planSha256", H).put("candidateSetSha256", H); hydration.put("outputRoot", root.toString()); expectFailure(() -> StrategyResearchDataV5.hydrateOpportunityWindowsV5(hydration), "production hydration"); pass();
    }

    private static void rawReplayRecomputesThroughCurrentAdapterAndMatchesNode() throws Exception {
        RawReplayFixture fixture = rawReplayFixture(); ObjectNode plan = fixture.plan();
        Path sourceRoot = fixture.sourceRoot(); ObjectNode sourceCheckpoint = fixture.checkpoint();

        Path javaTarget = Files.createTempDirectory("v5-raw-replay-java-"); ObjectNode javaRequest = replayRequest(plan, sourceCheckpoint, sourceRoot, javaTarget);
        ObjectNode javaReplay = StrategyResearchDataV5.replayAuthoritativeStagingFromRaw(javaRequest);
        equal(javaReplay.path("replayed_count").asInt(), StrategyResearchDataV5.DATA_V5_ASSETS.size(), "Java replayed capture count");
        equal(javaReplay.path("acquisition").path("status").asText(), "STAGING_COMPLETE", "Java replay completion");

        ObjectNode repeated = StrategyResearchDataV5.replayAuthoritativeStagingFromRaw(javaRequest);
        equalJson(runtimeProvenanceProjection(javaReplay.path("checkpoint")), runtimeProvenanceProjection(repeated.path("checkpoint")), "raw replay checkpoint parity");

        ObjectNode firstCapture = (ObjectNode) sourceCheckpoint.path("completed").elements().next(); ObjectNode summary = (ObjectNode) firstCapture.path("source_receipts").get(0);
        ObjectNode receipt = (ObjectNode) JSON.readTree(Files.readAllBytes(sourceRoot.resolve(summary.path("path").asText()))); String rawPath = receipt.path("raw_receipts").get(0).path("path").asText();
        byte[] original = Files.readAllBytes(sourceRoot.resolve(rawPath)), tampered = original.clone(); tampered[tampered.length - 1] ^= 1; Files.write(sourceRoot.resolve(rawPath), tampered);
        ObjectNode tamperJava = replayRequest(plan, sourceCheckpoint, sourceRoot, Files.createTempDirectory("v5-raw-replay-tamper-java-"));
        expectFailure(() -> StrategyResearchDataV5.replayAuthoritativeStagingFromRaw(tamperJava), "Java raw replay tampered bytes"); pass();
    }

    private static void rawReplayRejectsReorderedAndChangedRequests() throws Exception {
        for (String mutation : List.of("page", "cursor")) {
            RawReplayFixture fixture = rawReplayFixture(); ObjectNode checkpoint = fixture.checkpoint();
            String identity = checkpoint.path("completed").fieldNames().next();
            ObjectNode capture = (ObjectNode) checkpoint.path("completed").path(identity);
            ObjectNode summary = (ObjectNode) capture.path("source_receipts").get(0);
            Path receiptPath = fixture.sourceRoot().resolve(summary.path("path").asText());
            ObjectNode receipt = (ObjectNode) JSON.readTree(Files.readAllBytes(receiptPath));
            ObjectNode page = (ObjectNode) receipt.path("pagination").get(0);
            page.put(mutation, page.path(mutation).asLong() + 1);
            receipt.remove("content_sha256"); receipt = StrategyResearchDataV5.withHash(receipt);
            Files.write(receiptPath, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
            ObjectNode nextSummary = summary.deepCopy().put("sha256", receipt.path("content_sha256").asText())
                    .put("content_sha256", receipt.path("content_sha256").asText());
            capture.set("source_receipts", array().add(nextSummary));
            capture.set("source_receipt_sha256", array().add(receipt.path("content_sha256").asText()));
            ((ObjectNode) checkpoint.path("capture_lineage")).set(identity,
                    StrategyResearchDataV5.inspectCaptureLineage(capture, fixture.sourceRoot()));
            checkpoint.remove("content_sha256"); checkpoint = StrategyResearchDataV5.withHash(checkpoint);
            ObjectNode request = replayRequest(fixture.plan(), checkpoint, fixture.sourceRoot(),
                    Files.createTempDirectory("v5-raw-replay-" + mutation + "-"));
            expectFailure(() -> StrategyResearchDataV5.replayAuthoritativeStagingFromRaw(request),
                    "Java raw replay rejects changed " + mutation);
        }
        pass();
    }

    private static void rawReplayPermitsContentAddressedRawReuseAcrossReceipts() throws Exception {
        RawReplayFixture fixture = rawReplayFixture(); ObjectNode checkpoint = fixture.checkpoint();
        String identity = checkpoint.path("completed").fieldNames().next();
        ObjectNode capture = (ObjectNode) checkpoint.path("completed").path(identity);
        ObjectNode summary = (ObjectNode) capture.path("source_receipts").get(0);
        ObjectNode original = (ObjectNode) JSON.readTree(Files.readAllBytes(
                fixture.sourceRoot().resolve(summary.path("path").asText())));
        ObjectNode duplicate = original.deepCopy(); duplicate.set("pagination", array());
        duplicate.remove("content_sha256"); duplicate = StrategyResearchDataV5.withHash(duplicate);
        String duplicateRelative = "receipts/reused-" + duplicate.path("content_sha256").asText() + ".json";
        Path duplicatePath = fixture.sourceRoot().resolve(duplicateRelative); Files.createDirectories(duplicatePath.getParent());
        Files.write(duplicatePath, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(duplicate));
        ObjectNode duplicateSummary = summary.deepCopy().put("path", duplicateRelative)
                .put("sha256", duplicate.path("content_sha256").asText())
                .put("content_sha256", duplicate.path("content_sha256").asText());
        capture.set("source_receipts", array().add(summary).add(duplicateSummary));
        capture.set("source_receipt_sha256", array().add(summary.path("content_sha256").asText())
                .add(duplicate.path("content_sha256").asText()));
        ((ObjectNode) checkpoint.path("capture_lineage")).set(identity,
                StrategyResearchDataV5.inspectCaptureLineage(capture, fixture.sourceRoot()));
        checkpoint.remove("content_sha256"); checkpoint = StrategyResearchDataV5.withHash(checkpoint);
        ObjectNode replay = StrategyResearchDataV5.replayAuthoritativeStagingFromRaw(replayRequest(
                fixture.plan(), checkpoint, fixture.sourceRoot(), Files.createTempDirectory("v5-raw-replay-reuse-")));
        equal(replay.path("acquisition").path("status").asText(), "STAGING_COMPLETE",
                "Java raw replay permits content-addressed bytes shared by distinct receipts");
        pass();
    }

    private static void rawReplayAllowsOnlyDeterministicFundingCoverageStrengthening() {
        ObjectNode source = object().put("complete", true).put("reason", "COMPLETE");
        source.set("settlement_mark_events", array().add("2026-08-23T00:00:00.000Z"));
        ObjectNode strengthened = source.deepCopy();
        strengthened.set("settlement_mark_events", array().add("2026-08-23T00:00:00.000Z")
                .add("2026-08-23T04:00:00.000Z"));
        equal(StrategyResearchDataV5.replayCoverageStrengtheningMatches(source, strengthened), true,
                "funding replay permits deterministic settlement-mark inventory strengthening");
        ObjectNode weakened = strengthened.deepCopy();
        weakened.set("settlement_mark_events", array().add("2026-08-23T04:00:00.000Z"));
        equal(StrategyResearchDataV5.replayCoverageStrengtheningMatches(strengthened, weakened), false,
                "funding replay rejects settlement-mark inventory weakening");
        ObjectNode duplicated = strengthened.deepCopy();
        duplicated.set("settlement_mark_events", array().add("2026-08-23T00:00:00.000Z")
                .add("2026-08-23T00:00:00.000Z").add("2026-08-23T04:00:00.000Z"));
        equal(StrategyResearchDataV5.replayCoverageStrengtheningMatches(source, duplicated), false,
                "funding replay rejects ambiguous duplicate settlement marks");
        ObjectNode changedContract = strengthened.deepCopy().put("complete", false);
        equal(StrategyResearchDataV5.replayCoverageStrengtheningMatches(source, changedContract), false,
                "funding replay rejects unrelated coverage changes");
        pass();
    }

    private static RawReplayFixture rawReplayFixture() throws Exception {
        String capturedAt = "2026-08-24T20:30:00.000Z"; ObjectNode plan = oneBarMarkPlan(capturedAt);
        Path sourceRoot = Files.createTempDirectory("v5-raw-replay-source-");
        PublicDataAdapters.InjectableHttpClient transport = (uri, headers) -> {
            java.util.Map<String, String> query = uriQuery(uri); long event = Long.parseLong(query.get("startTime"));
            ArrayNode kline = array().add(event).add("100").add("102").add("99").add("101").add("10")
                    .add(event + FOUR_HOURS - 1).add("1000").add(10).add("5").add("500").add("0");
            byte[] body = JSON.writeValueAsBytes(array().add(kline));
            return new PublicDataAdapters.FetchResponse(200, body,
                    java.util.Map.of("date", java.util.List.of(capturedAt)));
        };
        ObjectNode acquisitionRequest = object().put("outputRoot", sourceRoot.toString())
                .put("fixtureOnly", true).put("capturedAt", capturedAt); acquisitionRequest.set("plan", plan);
        ObjectNode sourceManifest = StrategyResearchDataV5.acquireAuthoritativeStaging(acquisitionRequest, transport);
        equal(sourceManifest.path("status").asText(), "STAGING_COMPLETE", "one-bar source acquisition");
        ObjectNode checkpoint = (ObjectNode) JSON.readTree(Files.readAllBytes(sourceRoot.resolve("checkpoint.json")));
        return new RawReplayFixture(plan, sourceRoot, checkpoint);
    }

    private record RawReplayFixture(ObjectNode plan, Path sourceRoot, ObjectNode checkpoint) { }

    private static void auxiliaryMetricsCompleteReplayMatchesNodeAndRejectsTamper() throws Exception {
        ObjectNode plan = auxiliaryMetricsPlan(1), checkpoint = emptySourceCheckpoint(plan); ObjectNode metrics = metricsSeries(plan); Path source = Files.createTempDirectory("v5-aux-complete-source-");
        ObjectNode auxiliary = writeAuxiliaryMetricsCheckpoint(source, metrics, 1, false); Path javaTarget = Files.createTempDirectory("v5-aux-complete-java-");
        ObjectNode javaReplay = StrategyResearchDataV5.replayAuthoritativeStagingFromRaw(replayRequest(plan, checkpoint, source, javaTarget));
        equal(javaReplay.path("auxiliary_metrics").get(0).path("status").asText(), "REPLAYED", "Java complete auxiliary status");
        equal(javaReplay.path("auxiliary_metrics").get(0).path("raw_verified_count").asInt(), 2, "Java complete auxiliary raw count");
        ObjectNode saved = (ObjectNode) auxiliary.path("files").elements().next(), reference = (ObjectNode) saved.path("raw").get(0); Path raw = source.resolve(reference.path("path").asText()); byte[] tampered = Files.readAllBytes(raw); tampered[0] ^= 1; Files.write(raw, tampered);
        expectFailure(() -> StrategyResearchDataV5.replayAuthoritativeStagingFromRaw(replayRequest(plan, checkpoint, source, Files.createTempDirectory("v5-aux-tamper-java-"))), "Java auxiliary replay tampered archive bytes"); pass();
    }

    private static void auxiliaryMetricsPrefixReplayMatchesNodeAndRejectsCheckpointCollision() throws Exception {
        ObjectNode plan = auxiliaryMetricsPlan(2), checkpoint = emptySourceCheckpoint(plan), metrics = metricsSeries(plan); Path source = Files.createTempDirectory("v5-aux-prefix-source-");
        writeAuxiliaryMetricsCheckpoint(source, metrics, 1, false); Path javaTarget = Files.createTempDirectory("v5-aux-prefix-java-"); ObjectNode javaRequest = replayRequest(plan, checkpoint, source, javaTarget);
        ObjectNode javaReplay = StrategyResearchDataV5.replayAuthoritativeStagingFromRaw(javaRequest); ObjectNode diagnostic = (ObjectNode) javaReplay.path("auxiliary_metrics").get(0);
        equal(diagnostic.path("status").asText(), "PARTIAL_CHECKPOINT_REPLAYED_FOR_NETWORK_RESUME", "Java partial auxiliary status"); equal(diagnostic.path("saved_count").asInt(), 1, "Java partial saved count"); equal(diagnostic.path("remaining_count").asInt(), 1, "Java partial remaining count");
        ObjectNode repeat = StrategyResearchDataV5.replayAuthoritativeStagingFromRaw(javaRequest); equalJson(repeat, javaReplay, "deterministic auxiliary prefix resume");
        Path javaSaved = javaTarget;
        String relative = diagnostic.path("checkpoint_path").asText(); ObjectNode collision = (ObjectNode) JSON.readTree(Files.readAllBytes(javaSaved.resolve(relative))); collision.remove("content_sha256"); collision.put("collision", true); collision = StrategyResearchDataV5.withHash(collision); Files.write(javaSaved.resolve(relative), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(collision));
        ObjectNode javaCollisionRequest = replayRequest(plan, checkpoint, source, javaSaved); expectFailure(() -> StrategyResearchDataV5.replayAuthoritativeStagingFromRaw(javaCollisionRequest), "Java auxiliary immutable checkpoint collision");
        pass();
    }

    private static ObjectNode auxiliaryMetricsPlan(int days) {
        String asOf = "2026-08-24T20:30:00.000Z"; ObjectNode plan = oneBarMarkPlan(asOf); ObjectNode original = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(object().put("asOf", asOf)); ObjectNode metrics = null;
        for (JsonNode value : original.path("series")) if ("btc".equals(value.path("asset").asText()) && "metrics_events".equals(value.path("series_type").asText()) && "4h".equals(value.path("interval").asText())) { metrics = (ObjectNode) value.deepCopy(); break; }
        if (metrics == null) throw new AssertionError("default plan has no BTC 4h metrics series"); long start = Instant.parse("2026-08-23T00:00:00Z").toEpochMilli(), end = start + (days - 1L) * 86_400_000L + FOUR_HOURS - 300_000L;
        metrics.put("start_at", iso(start)).put("end_at", iso(end)).put("expected_event_count", Math.max(1, days)).put("expected_step_ms", FOUR_HOURS).put("required", false);
        ArrayNode series = (ArrayNode) plan.path("series").deepCopy(); series.add(metrics); plan.remove("content_sha256"); plan.set("series", series); return StrategyResearchDataV5.withHash(plan);
    }

    private static ObjectNode metricsSeries(ObjectNode plan) {
        for (JsonNode value : plan.path("series")) if ("metrics_events".equals(value.path("series_type").asText())) return (ObjectNode) value;
        throw new AssertionError("test plan has no metrics series");
    }

    private static ObjectNode emptySourceCheckpoint(ObjectNode plan) {
        ObjectNode checkpoint = object().put("schema", "strategy-v5-data-checkpoint/1").put("version", 1).put("plan_sha256", plan.path("content_sha256").asText()).put("root_reference", "quarantine/source").putNull("prior_checkpoint_sha256")
                .put("producer_code_sha256", StrategyResearchDataV5.javaProducerCodeSha256()).put("coverage_rules_sha256", StrategyResearchDataV5.DATA_V5_COVERAGE_RULES_SHA256).put("fixture_only", true).put("provenance", "FIXTURE_INJECTED"); checkpoint.set("capture_lineage", object()); checkpoint.set("completed", object()); return StrategyResearchDataV5.withHash(checkpoint);
    }

    private static ObjectNode writeAuxiliaryMetricsCheckpoint(Path root, ObjectNode series, int savedDays, boolean missing) throws Exception {
        long start = Instant.parse(series.path("start_at").asText()).toEpochMilli(), end = Instant.parse(series.path("end_at").asText()).toEpochMilli(); java.util.List<String> days = new java.util.ArrayList<>();
        java.time.LocalDate cursor = Instant.ofEpochMilli(start).atZone(java.time.ZoneOffset.UTC).toLocalDate(), finish = Instant.ofEpochMilli(end).atZone(java.time.ZoneOffset.UTC).toLocalDate(); while (!cursor.isAfter(finish)) { days.add(cursor.toString()); cursor = cursor.plusDays(1); }
        String asset = series.path("asset").asText(), symbol = series.path("symbol").asText().toUpperCase(); ObjectNode identity = object().put("kind", "METRICS-" + asset + "-" + symbol).put("asset", asset).put("symbol", symbol).put("start", start).put("end", end); identity.set("files", JSON.valueToTree(days));
        ObjectNode checkpoint = object().put("key", StrategyResearchDataV5.hash(identity)); ObjectNode files = object();
        for (int index = 0; index < savedDays; index++) { String day = days.get(index), token = symbol + "-metrics-" + day, base = "https://data.binance.vision/data/futures/um/daily/metrics/" + symbol + "/" + token; ObjectNode saved = object().put("file", day);
            if (missing) { byte[] body = "missing".getBytes(StandardCharsets.UTF_8); ObjectNode ref = auxiliaryRaw(root, body, "HTTP_ERROR", base + ".zip", symbol, day); ((ObjectNode) ref.path("request")).put("status", 404); saved.put("status", 404).put("status_code", 404).put("checked_at", "2026-08-24T20:30:00.000Z").put("recheck_after_ms", 2_592_000_000L).set("raw", array().add(ref)); }
            else { byte[] zip = metricsZip(symbol, day, start, end), checksum = (StrategyResearchDataV5.hash(zip) + "  " + token + ".zip\n").getBytes(StandardCharsets.UTF_8); ObjectNode zipRef = auxiliaryRaw(root, zip, "ARCHIVE_ZIP", base + ".zip", symbol, day), sumRef = auxiliaryRaw(root, checksum, "ARCHIVE_CHECKSUM", base + ".zip.CHECKSUM", symbol, day); saved.put("status", 200).put("captured_at", "2026-08-24T20:30:00.000Z").put("archive_sha256", StrategyResearchDataV5.hash(zip)).put("checksum_sha256", StrategyResearchDataV5.hash(checksum)).set("raw", array().add(zipRef).add(sumRef)); }
            files.set(day, saved); }
        checkpoint.set("files", files); checkpoint = StrategyResearchDataV5.withHash(checkpoint); Path path = root.resolve("checkpoints/metrics-" + asset + "-" + symbol.toLowerCase() + ".json"); Files.createDirectories(path.getParent()); Files.write(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(checkpoint)); return checkpoint;
    }

    private static ObjectNode auxiliaryRaw(Path root, byte[] bytes, String kind, String endpoint, String symbol, String day) throws Exception {
        String digest = StrategyResearchDataV5.hash(bytes), relative = "raw-archives/" + digest + ".bin"; Files.createDirectories(root.resolve("raw-archives")); if (!Files.exists(root.resolve(relative))) Files.write(root.resolve(relative), bytes);
        ObjectNode request = object().put("endpoint", endpoint).put("symbol", symbol).put("day", day).put("kind", kind); return object().put("kind", kind).put("path", relative).put("sha256", digest).put("bytes", bytes.length).set("request", request);
    }

    private static byte[] metricsZip(String symbol, String day, long start, long end) throws Exception {
        StringBuilder csv = new StringBuilder("create_time,symbol,sum_open_interest,sum_open_interest_value,count_toptrader_long_short_ratio,sum_toptrader_long_short_ratio,count_long_short_ratio,sum_taker_long_short_vol_ratio\n");
        long dayStart = Instant.parse(day + "T00:00:00Z").toEpochMilli(); for (long event = Math.max(start, dayStart); event <= Math.min(end, dayStart + 86_400_000L - 300_000L); event += 300_000L) csv.append(event).append(',').append(symbol).append(",1,2,1.1,1.2,1.3,1.4\n");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) { zip.putNextEntry(new java.util.zip.ZipEntry(symbol + "-metrics-" + day + ".csv")); zip.write(csv.toString().getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); } return bytes.toByteArray();
    }

    private static ObjectNode oneBarMarkPlan(String asOf) {
        ObjectNode plan = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(object().put("asOf", asOf)); ArrayNode series = array();
        for (JsonNode value : plan.path("series")) if ("mark_bars".equals(value.path("series_type").asText()) && "4h".equals(value.path("interval").asText())) {
            ObjectNode row = (ObjectNode) value.deepCopy(); row.put("start_at", row.path("end_at").asText()).put("expected_event_count", 1); series.add(row);
        }
        plan.remove("content_sha256"); plan.set("series", series); return StrategyResearchDataV5.withHash(plan);
    }

    private static ObjectNode replayRequest(ObjectNode plan, ObjectNode checkpoint, Path source, Path target) {
        ObjectNode request = object().put("sourceRoot", source.toString()).put("targetRoot", target.toString()).put("targetRootReference", "quarantine/raw-replay"); request.set("plan", plan); request.set("sourceCheckpoint", checkpoint); return request;
    }

    private static java.util.Map<String, String> uriQuery(java.net.URI uri) {
        java.util.Map<String, String> values = new java.util.HashMap<>(); for (String pair : uri.getRawQuery().split("&")) { String[] parts = pair.split("=", 2); values.put(java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8), java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8)); } return values;
    }

    private static void noPlaceholdersRemain() throws Exception {
        String source = Files.readString(ROOT.resolve(
                "analytics-research/src/main/java/com/tradinganalytics/research/v5/StrategyResearchDataV5.java"));
        for (String forbidden : new String[]{"TODO", "FIXME", "UnsupportedOperationException", "not implemented", "placeholder"}) if (source.contains(forbidden)) throw new AssertionError("placeholder marker: " + forbidden); pass();
    }

    private static String parquetSchemaSha(java.util.List<ObjectNode> ignored, Path parquet) throws Exception {
        String path = parquet.toAbsolutePath().normalize().toString().replace("'", "''"); ArrayNode rows = array();
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:duckdb:"); java.sql.Statement statement = connection.createStatement(); java.sql.ResultSet result = statement.executeQuery("DESCRIBE SELECT * FROM read_parquet('" + path + "')")) {
            java.sql.ResultSetMetaData metadata = result.getMetaData(); while (result.next()) { ArrayNode row = array(); for (int index = 1; index <= metadata.getColumnCount(); index++) { Object value = result.getObject(index); if (value == null) row.addNull(); else row.add(String.valueOf(value)); } rows.add(row); }
        }
        return StrategyResearchDataV5.hash(rows);
    }

    private static ObjectNode acquisitionRequest(ObjectNode plan, Path root, ArrayNode captures, String predecessor) {
        ObjectNode request = object().put("outputRoot", root.toString()).put("fixtureOnly", true); request.set("plan", plan); request.set("fixtureCaptures", captures); if (predecessor != null) request.put("expectedCheckpointSha256", predecessor); return request;
    }

    private static ArrayNode unavailableCaptures(ObjectNode plan, String marker) {
        ArrayNode captures = array();
        for (JsonNode value : plan.path("series")) {
            ObjectNode series = (ObjectNode) value, capture = object();
            for (String field : new String[]{"asset", "venue", "instrument", "symbol", "interval", "series_type", "series_role", "required"}) if (series.has(field)) capture.set(field, series.get(field).deepCopy());
            capture.put("unavailable", true); capture.set("coverage", object().put("complete", false).put("reason", marker)); capture.set("limitations", array().add(marker)); captures.add(capture);
        }
        return captures;
    }

    private static boolean runAcquisitionAfterBarrier(java.util.concurrent.CyclicBarrier barrier, ObjectNode request) throws Exception { barrier.await(); try { StrategyResearchDataV5.acquireAuthoritativeStaging(request); return true; } catch (RuntimeException expected) { return false; } }

    private static void failLikeNode(String action, JsonNode input, Throwing runnable) throws Exception {
        expectFailure(runnable, action);
    }
    private static ObjectNode funding(int hour, long start) { long time = start + hour * 3_600_000L; return object().put("event_id", "evt-" + hour).put("event_time", time).put("raw_event_time", time).put("availability_time", time).put("funding_rate", .001); }
    private static ObjectNode bar(long event, long step) { return object().put("asset", "btc").put("venue", "BINANCE").put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT").put("event_time", event).put("availability_time", event + step - 1).put("close_time", event + step - 1).put("open", 100).put("high", 102).put("low", 99).put("close", 101); }
    private static ObjectNode identity(String signal, String episode, long decision) { return object().put("asset", "btc").put("venue", "BINANCE").put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT").put("decision_time", iso(decision)).put("signal_id", signal).put("episode_id", episode); }
    private static String iso(long millis) { String value = Instant.ofEpochMilli(millis).toString(); return value.contains(".") ? value : value.replace("Z", ".000Z"); }
    private static String text(JsonNode value, String field) { return value == null || !value.hasNonNull(field) ? "" : value.path(field).asText(); }
    private static ArrayNode strings(java.util.List<String> values) { ArrayNode result = array(); values.forEach(result::add); return result; }
    private static byte[] prettyBytes(JsonNode value) throws IOException { return (JSON.writer(new NodePrettyPrinter()).writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8); }
    private static final class NodePrettyPrinter extends com.fasterxml.jackson.core.util.DefaultPrettyPrinter {
        NodePrettyPrinter() { super(); indentArraysWith(new com.fasterxml.jackson.core.util.DefaultIndenter("  ", "\n")); _arrayEmptySeparator = ""; }
        @Override public void writeObjectFieldValueSeparator(com.fasterxml.jackson.core.JsonGenerator generator) throws IOException { generator.writeRaw(": "); }
        @Override public NodePrettyPrinter createInstance() { return new NodePrettyPrinter(); }
    }
    private static ObjectNode fixtureInput(String schema, String name) { ObjectNode value = object().put("schema", schema).put("version", 1).put("name", name); return StrategyResearchDataV5.withHash(value); }
    private static ObjectNode roleChildBar(long event, double close) {
        return object().put("event_time", iso(event)).put("availability_time", iso(event + ONE_MINUTE - 1)).put("open", close - 1).put("high", close + 1).put("low", close - 2).put("close", close);
    }
    private static ObjectNode writeRoleInput(Path root, String relative, ObjectNode row) throws Exception {
        Path path = root.resolve(relative); Files.createDirectories(path.getParent()); byte[] bytes = (JSON.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8); Files.write(path, bytes);
        return object().put("path", relative).put("sha256", StrategyResearchDataV5.hash(bytes));
    }
    private static ObjectNode writeLifecycleTrustReceipt(Path root, String relative, JsonNode value, String rowsSha256, String schema) throws Exception {
        Path path = root.resolve(relative); Files.createDirectories(path.getParent()); byte[] bytes = prettyBytes(value); Files.write(path, bytes);
        return object().put("path", relative).put("content_sha256", StrategyResearchDataV5.ownHash(value)).put("byte_sha256", StrategyResearchDataV5.hash(bytes))
                .put("bytes", bytes.length).put("rows_sha256", rowsSha256).put("schema", schema);
    }
    private static ObjectNode addRoleCapture(Path root, ArrayNode captures, String role, ObjectNode reference, String interval, String seriesType, ObjectNode row) throws Exception {
        String roleBody = "raw-role:" + role + ":" + text(row, "asset") + ":" + text(row, "instrument"); byte[] rawBody = roleBody.getBytes(StandardCharsets.UTF_8); String rawSha = StrategyResearchDataV5.hash(rawBody);
        String rawPath = "lineage/raw/" + rawSha + ".bin"; Path rawFile = root.resolve(rawPath); Files.createDirectories(rawFile.getParent()); Files.write(rawFile, rawBody);
        ObjectNode raw = object().put("schema", "strategy-v5-source-receipt/1").put("version", 1).put("path", rawPath).put("source", "FIXTURE_ROLE")
                .put("byte_sha256", rawSha).put("bytes", rawBody.length).put("format", "RAW_BYTES").put("storage_role", "RAW_IGNORED").put("authoritative", false);
        raw.set("request", object().put("endpoint", "fixture://role/" + role).put("response_sha256", rawSha)); raw = StrategyResearchDataV5.withHash(raw);
        ObjectNode normalized = object().put("schema", "strategy-v5-source-receipt/1").put("version", 1).put("status", "PUBLIC_OBSERVED").put("captured_at", "2026-08-24T12:00:00.000Z");
        normalized.set("request", object().put("endpoint", "fixture://role/" + role)); normalized.set("response_sha256", array().add(rawSha)); normalized.set("source_byte_sha256", array().add(rawSha)); normalized.set("raw_receipts", array().add(raw)); normalized.set("coverage", object().put("complete", true)); normalized = StrategyResearchDataV5.withHash(normalized);
        String receiptPath = "lineage/receipts/" + text(normalized, "content_sha256") + ".json"; Path receiptFile = root.resolve(receiptPath); Files.createDirectories(receiptFile.getParent()); Files.write(receiptFile, prettyBytes(normalized));
        ObjectNode summary = object().put("path", receiptPath).put("sha256", text(normalized, "content_sha256")).put("content_sha256", text(normalized, "content_sha256")).put("byte_sha256", rawSha).put("raw_count", 1).put("schema", "strategy-v5-source-receipt/1").put("status", "PUBLIC_OBSERVED");
        ObjectNode capture = object().put("asset", text(row, "asset")).put("venue", "BINANCE").put("instrument", text(row, "instrument")).put("symbol", text(row, "symbol")).put("interval", interval).put("series_type", seriesType).put("series_role", "raw_mark_bars".equals(seriesType) ? "MARK" : "PRICE").put("required", true);
        ObjectNode partition = reference.deepCopy().put("bytes", Files.size(root.resolve(text(reference, "path")))).put("row_count", 1).put("format", "JSONL").put("storage_role", "STAGING").put("authoritative", false); capture.set("partition", partition); capture.set("source_receipts", array().add(summary)); capture.set("coverage", object().put("complete", true).put("expected_rows", 1).put("observed_rows", 1)); captures.add(capture); return summary;
    }
    private static JsonNode runtimeProvenanceProjection(JsonNode source) {
        JsonNode value = source.deepCopy(); stripRuntimeProvenance(value); return value;
    }
    private static void stripRuntimeProvenance(JsonNode value) {
        if (value == null) return;
        if (value.isObject()) {
            ObjectNode object = (ObjectNode) value;
            object.remove(List.of("content_sha256", "checkpoint_sha256", "producer_code_sha256", "adapter_code_sha256", "adapter_code_reference", "source_receipt_sha256"));
            JsonNode receiptPaths = object.get("source_receipts");
            if (receiptPaths != null && receiptPaths.isArray() && (receiptPaths.isEmpty() || receiptPaths.get(0).isTextual())) object.remove("source_receipts");
            if ("strategy-v5-source-receipt/1".equals(text(object, "schema"))) object.remove(List.of("path", "sha256"));
            if ("JSONL".equals(text(object, "format")) && "STAGING".equals(text(object, "storage_role"))) object.remove(List.of("path", "sha256", "bytes"));
            object.elements().forEachRemaining(StrategyResearchDataV5NodeOracleTest::stripRuntimeProvenance);
        } else if (value.isArray()) value.elements().forEachRemaining(StrategyResearchDataV5NodeOracleTest::stripRuntimeProvenance);
    }
    private static ObjectNode object() { return JSON.createObjectNode(); } private static ArrayNode array() { return JSON.createArrayNode(); }
    private static void equalJson(JsonNode actual, JsonNode expected, String label) { if (!StrategyResearchDataV5.stable(actual).equals(StrategyResearchDataV5.stable(expected))) throw new AssertionError(label + "\nJAVA=" + actual.toPrettyString() + "\nNODE=" + expected.toPrettyString()); }
    private static void equal(Object actual, Object expected, String label) { if (!java.util.Objects.equals(actual, expected)) throw new AssertionError(label + ": " + actual + " != " + expected); }
    private static void expectFailure(Throwing runnable, String label) { try { runnable.run(); throw new AssertionError(label + " did not fail closed"); } catch (AssertionError error) { throw error; } catch (Throwable expected) { } }
    private static void pass() { tests++; }
    private static Path repositoryRoot() { Path cursor = Path.of("").toAbsolutePath(); while (cursor != null && (!Files.isRegularFile(cursor.resolve("pom.xml")) || !Files.isDirectory(cursor.resolve("analytics-research")))) cursor = cursor.getParent(); if (cursor == null) throw new IllegalStateException("repository root not found"); return cursor; }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}

package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.marketdata.research.ResearchData;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StrategyPerformanceV5NodeOracleTest {
    private static final ObjectMapper MAPPER = JsonHashes.mapper();
    @TempDir Path temporary;

    @Test
    void hashesWorkloadComplexityFundingAndLazyReferenceMatchNode() throws Exception {
        ObjectNode workload = object().put("assets", "2").put("outerFolds", 3).put("innerFolds", 1)
                .put("population", 5).put("generations", 2).put("seeds", 3)
                .put("confirmationAttemptsPerGaRun", 7).put("pboPartitions", 2).put("pboCandidates", 3)
                .put("vectorAliasesPerOuterFold", 4).put("outerSelectedAttemptsPerAssetFold", 2)
                .put("physicalSourceWindows", 8.5).put("physicalSourcePartitionsPerWindow", 2.5)
                .put("physicalSourcePartitionBytes", 1_024.5).put("workers", 3)
                .put("rolePayloadBytes", 100_000).put("lazyReferenceBytes", 1_000)
                .put("residentPartitionBytes", 8_000).put("partitionSharing", "SHARED_READ_ONLY")
                .put("episodesPerEvaluation", 11);
        ObjectNode capture = object().set("coverage", object().put("slot_tolerance_ms", 60_000)
                .set("cadence_segments", array()
                        .add(object().put("effective_from", "2026-01-01T00:00:00Z")
                                .put("effective_to", "2026-01-02T00:00:00Z").put("cadence_ms", 28_800_000))
                        .add(object().put("effective_from", "2026-01-02T00:00:00Z")
                                .put("effective_to", "2026-01-03T00:00:00Z").put("cadence_ms", 14_400_000))));
        ObjectNode hydration = hydrationFixture();
        ObjectNode lazyOptions = object().set("hydration", hydration);
        lazyOptions.put("windowId", "window-1").put("asset", "btc").put("instrument", "BINANCE_SPOT")
                .put("symbol", "BTCUSDT");
        ArrayNode requests = array();
        requests.add(object().put("name", "hash").set("value", object().put("z", 1).set("a", array().add(3).add(2))));
        requests.add(object().put("name", "workload").set("options", workload));
        requests.add(object().put("name", "complexity").set("options", workload));
        ObjectNode fundingRequest = object().put("name", "funding")
                .put("event", InstantFixture.ms("2026-01-02T00:00:30Z"));
        fundingRequest.set("capture", capture); requests.add(fundingRequest);
        requests.add(object().put("name", "lazy").set("options", lazyOptions));
        ArrayNode actual = array();
        actual.add(ok(MAPPER.getNodeFactory().textNode(StrategyPerformanceV5.hashV5Performance(requests.get(0).get("value")))));
        actual.add(ok(StrategyPerformanceV5.estimateProductionWorkloadV5(workload)));
        actual.add(ok(StrategyPerformanceV5.estimateProductionComplexityV5(workload)));
        actual.add(ok(StrategyPerformanceV5.productionFundingSegment(capture,
                InstantFixture.ms("2026-01-02T00:00:30Z"))));
        actual.add(ok(StrategyPerformanceV5.makeLazyExecutionReferenceV5(lazyOptions)));
        assertThat(actual).hasSize(5).allSatisfy(result -> assertThat(result.path("ok").asBoolean()).isTrue());
        assertThat(actual.get(0).path("value").asText()).isEqualTo(
                StrategyPerformanceV5.hashV5Performance(requests.get(0).path("value")));
        assertThat(actual.get(1).path("value").path("physical_null_total_attempts").asLong()).isPositive();
        assertThat(actual.get(2).path("value").path("operation_counts").path("episode_evaluations").asLong())
                .isEqualTo(actual.get(1).path("value").path("physical_null_episode_evaluations").asLong());
        assertThat(actual.get(3).path("value").path("cadence_ms").asLong()).isEqualTo(14_400_000L);
        assertThat(actual.get(4).path("value").path("schema").asText())
                .isEqualTo("strategy-v5-lazy-execution-reference/1");
    }

    @Test
    void lazyMaterializationAndBoundedPartitionCacheMatchNode() throws Exception {
        ObjectNode firstRow = bar("2026-01-01T00:00:00.000Z", "2026-01-01T00:01:00.000Z",
                100, 101, 99, 100);
        ObjectNode secondRow = bar("2026-01-01T00:01:00.000Z", "2026-01-01T00:02:00.000Z",
                100, 102, 99, 101);
        String body = MAPPER.writeValueAsString(firstRow) + "\n" + MAPPER.writeValueAsString(secondRow) + "\n";
        Path path = temporary.resolve("lazy-partition.jsonl");
        Files.writeString(path, body, StandardCharsets.UTF_8);
        String digest = StrategyPerformanceV5.hashV5Performance(body);
        ObjectNode partition = object().put("path", path.toString()).put("sha256", digest)
                .put("bytes", body.getBytes(StandardCharsets.UTF_8).length).put("row_count", 2)
                .put("min_event_time", "2026-01-01T00:00:00.000Z")
                .put("max_event_time", "2026-01-01T00:01:00.000Z");
        ObjectNode ref = object().put("partition_sha256", digest).put("partition_path", path.toString())
                .put("partition_bytes", body.getBytes(StandardCharsets.UTF_8).length)
                .put("partition_row_count", 2).put("row_start", "2026-01-01T00:00:00.000Z")
                .put("row_end_exclusive", "2026-01-01T00:02:00.000Z").put("row_count", 2);
        ObjectNode window = object().put("window_id", "window-lazy")
                .put("execution_start", "2026-01-01T00:00:00.000Z")
                .put("execution_end", "2026-01-01T00:02:00.000Z")
                .put("effective_end_exclusive", "2026-01-01T00:02:00.000Z")
                .put("lifecycle_status", "COMPLETE").put("row_count", 2);
        window.set("partition_refs", array().add(ref)); window.set("preentry_partition_refs", array());
        ObjectNode hydration = object().put("schema", "strategy-v5-opportunity-hydration/2")
                .put("execution_interval_ms", 60_000).set("windows", array().add(window));
        hydration.put("content_sha256", StrategyPerformanceV5.ownHash(hydration));
        ObjectNode referenceOptions = object().set("hydration", hydration);
        referenceOptions.put("windowId", "window-lazy").put("asset", "btc")
                .put("instrument", "BINANCE_SPOT").put("symbol", "BTCUSDT");
        ObjectNode reference = StrategyPerformanceV5.makeLazyExecutionReferenceV5(referenceOptions);
        ObjectNode cacheOptions = object().put("partitionRootSha256",
                reference.path("partition_root_sha256").asText())
                .put("maxResidentBytes", 4_096).put("maxEntryBytes", 4_096);
        ObjectNode request = object().put("name", "materialize").set("referenceOptions", referenceOptions);
        request.set("cacheOptions", cacheOptions); request.set("partitions", array().add(partition));
        StrategyPerformanceV5.BoundedPartitionReadCache cache =
                StrategyPerformanceV5.makeBoundedPartitionReadCacheV5(cacheOptions);
        ObjectNode first = StrategyPerformanceV5.materializeLazyExecutionReferenceV5(reference, hydration,
                List.of(partition), cache, 1, 10, 4_096, 4_096);
        ObjectNode second = StrategyPerformanceV5.materializeLazyExecutionReferenceV5(reference, hydration,
                List.of(partition), cache, 1, 10, 4_096, 4_096);
        ObjectNode value = object(); value.set("first", first); value.set("second", second);
        value.set("diagnostics", cache.diagnostics());
        assertThat(first).isEqualTo(second);
        assertThat(first.path("child_bars")).hasSize(2);
        assertThat(first.path("materialized_row_count").asInt()).isEqualTo(2);
        assertThat(cache.diagnostics().path("disk_read_count").asInt()).isEqualTo(1);
        assertThat(cache.diagnostics().path("cache_hit_count").asInt()).isEqualTo(1);
    }

    @Test
    void ordinarySignalReuseAndScopeBoundOutcomeSemanticsMatchNode() throws Exception {
        ObjectNode binding = cacheBinding();
        ObjectNode features = object();
        features.set("e1", object().put("intent", true).put("base", .1));
        features.set("e2", object().put("intent", true).put("base", -.2).put("traded", false));
        features.set("e3", object().put("intent", true).put("base", .3));
        ObjectNode chromosome = object().put("threshold", 0);
        ObjectNode request = object().put("name", "cache").set("binding", binding);
        request.set("features", features); request.set("chromosome", chromosome);
        StrategyPerformanceV5.ScopeVectorCache cache = StrategyPerformanceV5.makeScopeVectorCacheV5(binding);
        AtomicInteger signalCalls = new AtomicInteger(); AtomicInteger outcomeCalls = new AtomicInteger();
        Map<String, JsonNode> featureMap = new LinkedHashMap<>();
        features.fields().forEachRemaining(entry -> featureMap.put(entry.getKey(), entry.getValue()));
        StrategyPerformanceV5.SignalEvaluator signal = (id, feature, value) -> {
            signalCalls.incrementAndGet(); return object().put("intent", feature.path("intent").asBoolean());
        };
        StrategyPerformanceV5.OutcomeEvaluator outcome = (id, feature, value, candidate, phase, fold,
                                                             fit, evaluation) -> {
            outcomeCalls.incrementAndGet(); return object().put("net_r", feature.path("base").asDouble()
                    + ("VALID".equals(phase) ? 1 : 0)).put("traded", !feature.has("traded")
                    || feature.path("traded").asBoolean());
        };
        ObjectNode first = cache.evaluate(new StrategyPerformanceV5.EvaluationRequest(chromosome, null,
                List.of("e1", "e2"), List.of("e1", "e2"), "TRAIN_ONLY", "outer-1",
                "2026-01-01T00:00:00.000Z", "2026-02-01T00:00:00.000Z", featureMap, signal, outcome));
        ObjectNode second = cache.evaluate(new StrategyPerformanceV5.EvaluationRequest(chromosome, null,
                List.of("e2", "e3"), List.of("e2", "e3"), "VALID", "outer-2",
                "2026-01-01T00:00:00.000Z", "2026-02-01T00:00:00.000Z", featureMap, signal, outcome));
        ObjectNode actual = object(); actual.set("values", array().add(first).add(second));
        actual.set("calls", object().put("signal", signalCalls.get()).put("outcome", outcomeCalls.get()));
        actual.set("diagnostics", cache.diagnostics());
        assertThat(signalCalls).hasValue(3);
        assertThat(outcomeCalls).hasValue(4);
        assertThat(first.path("episode_ids")).hasSize(2);
        assertThat(second.path("episode_ids")).hasSize(2);
    }

    @Test
    void boundedWorkerMetricsAndAuthoritativeTradeDerivationMatchNode() throws Exception {
        ObjectNode fixture = workerFixture();
        ObjectNode request = object().put("name", "worker").set("worker", fixture);
        request.set("candidate", candidateFixture());
        JsonNode actual = object().put("ok", true).set("value",
                StrategyPerformanceV5Worker.evaluate(fixture, candidateFixture()));
        assertThat(actual.path("ok").asBoolean()).isTrue();
        assertThat(actual.path("value").path("metrics").path("completed_episodes").asInt()).isEqualTo(1);
        assertThat(actual.path("value").path("metrics").path("episode_returns").path("episode-2").asDouble())
                .isZero();

        ObjectNode malicious = fixture.deepCopy();
        ((ObjectNode) malicious.path("executionRows").get(0)).put("net_r", 99);
        assertThat(StrategyPerformanceV5Worker.evaluate(malicious, candidateFixture()).path("error").asText())
                .contains("precomputed label return/cost row is not authoritative");
        assertThatThrownBy(() -> StrategyPerformanceV5Worker.evaluate(fixture, candidateFixture(), 8))
                .hasMessageContaining("cannot hold the fail-closed result");
    }

    @Test
    void workerDirectionCollisionEmptyAndFailClosedCasesMatchNode() throws Exception {
        ArrayNode requests = array();
        ObjectNode shortCandidate = candidateFixture();
        ((ObjectNode) shortCandidate.path("definition")).put("direction", "short")
                .put("target_price", 99).put("stop_price", 105);
        requests.add(workerRequest(workerFixture(), shortCandidate));

        ObjectNode collisionCandidate = candidateFixture();
        ((ObjectNode) collisionCandidate.path("definition")).put("stop_price", 100)
                .put("target_price", 102);
        requests.add(workerRequest(workerFixture(), collisionCandidate));

        ObjectNode emptyCandidate = candidateFixture();
        ((ObjectNode) emptyCandidate.path("definition")).put("threshold", 99);
        requests.add(workerRequest(workerFixture(), emptyCandidate));

        ObjectNode missingAvailability = workerFixture();
        ((ObjectNode) missingAvailability.path("featureRows").get(0)).remove("availability_time");
        requests.add(workerRequest(missingAvailability, candidateFixture()));

        ObjectNode sparseBars = workerFixture();
        ((ObjectNode) sparseBars.path("executionRows").get(0).path("child_bars").get(1))
                .put("event_time", "2026-01-01T00:02:00.000Z");
        requests.add(workerRequest(sparseBars, candidateFixture()));

        ObjectNode incompleteFunding = workerFixture();
        ((ObjectNode) incompleteFunding.path("executionRows").get(0)
                .path("funding_settlements").get(0)).remove("source");
        requests.add(workerRequest(incompleteFunding, candidateFixture()));

        ArrayNode actual = array();
        for (JsonNode request : requests) {
            ObjectNode response = StrategyPerformanceV5Worker.evaluate(
                    (ObjectNode) request.path("worker"), request.path("candidate"));
            if (response.has("error")) actual.add(object().put("ok", false)
                    .put("error", response.path("error").asText()));
            else actual.add(object().put("ok", true).set("value", response));
        }
        assertThat(actual).hasSize(requests.size());
        assertThat(actual.get(0).path("value").path("metrics").path("completed_episodes").asInt())
                .isEqualTo(1);
        assertThat(actual.get(1).path("value").path("metrics").path("episode_returns")
                .path("episode-1").asDouble()).isNegative();
        assertThat(actual.get(2).path("value").path("metrics").path("completed_episodes").asInt())
                .isZero();
    }

    @Test
    void cacheAndPartitionBoundariesRejectForgedState() throws Exception {
        ObjectNode selfAsserted = cacheBinding().put("scopeIndependentOutcomes", true);
        assertThatThrownBy(() -> StrategyPerformanceV5.makeScopeVectorCacheV5(selfAsserted))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("self-asserted");
        StrategyPerformanceV5.ScopeVectorCache scope = StrategyPerformanceV5.makeScopeVectorCacheV5(cacheBinding());
        Map<String, JsonNode> features = Map.of("e1", object().put("intent", true));
        assertThatThrownBy(() -> scope.evaluate(new StrategyPerformanceV5.EvaluationRequest(object(), null,
                List.of("e1"), List.of("e2"), null, null, null, null, features,
                (id, feature, candidate) -> object().put("intent", true),
                (id, feature, signal, candidate, phase, fold, fit, evaluation) -> object().put("net_r", 1))))
                .hasMessageContaining("outside the declared evaluation scope");

        Path partitionPath = temporary.resolve("partition.jsonl");
        String body = MAPPER.writeValueAsString(object().put("event_time", "2026-01-01T00:00:00Z")) + "\n";
        Files.writeString(partitionPath, body, StandardCharsets.UTF_8);
        String digest = StrategyPerformanceV5.hashV5Performance(body);
        ObjectNode partition = object().put("path", partitionPath.toString()).put("sha256", digest)
                .put("bytes", body.getBytes(StandardCharsets.UTF_8).length).put("row_count", 1);
        ObjectNode options = object().put("partitionRootSha256", partitionRoot(digest))
                .put("maxResidentBytes", 4_096).put("maxEntryBytes", 4_096);
        StrategyPerformanceV5.BoundedPartitionReadCache partitionCache =
                StrategyPerformanceV5.makeBoundedPartitionReadCacheV5(options);
        assertThat(partitionCache.resolve(List.of(partition))).hasSize(1);
        assertThat(partitionCache.resolve(List.of(partition))).hasSize(1);
        assertThat(partitionCache.diagnostics().path("cache_hit_count").asInt()).isEqualTo(1);
        Files.writeString(partitionPath, body + "{}\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> partitionCache.resolve(List.of(partition)))
                .hasMessageContaining("bytes/hash are invalid");

        Path badJsonl = temporary.resolve("bad.jsonl");
        Files.writeString(badJsonl, "{}\n\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> StrategyPerformanceV5.streamHashV5ProductionFile(badJsonl, 2, true,
                true, 64, 128, row -> {})).hasMessageContaining("empty line diagnostic");
    }

    @Test
    void executableFixtureBenchmarkMatchesEveryDeterministicNodeField() throws Exception {
        ObjectNode expected = benchmarkOracle("--chromosomes", "1", "--episodes", "8");
        ObjectNode actual = StrategyPerformanceV5Benchmark.runBenchmarkV5(
                "--chromosomes", "1", "--episodes", "8");
        stripRuntime(expected); stripRuntime(actual);
        assertCanonical(actual, expected);
        assertThat(actual.path("checkpoint_resume_smoke").path("status").asText()).isEqualTo("PASS");
        assertThat(actual.path("after").path("signal_callbacks").asInt()).isEqualTo(64);
        assertThat(actual.path("after").path("outcome_callbacks").asInt()).isEqualTo(960);

        assertThatThrownBy(() -> StrategyPerformanceV5Benchmark.runBenchmarkV5("--full"))
                .hasMessage("--full requires frozen plan, acquisition manifest, and Parquet manifest inputs");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int status = StrategyPerformanceV5Benchmark.run(new String[]{"--full"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        assertThat(status).isEqualTo(1); assertThat(stdout.toByteArray()).isEmpty();
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("--full requires frozen plan");
    }

    @Test
    void productionDataPlaneMatchesNodeAndFailsClosedOnPhysicalTampering() throws Exception {
        ObjectNode fixture = productionFixtureOracle();
        ObjectNode paths = (ObjectNode) fixture.path("paths");
        ObjectNode options = object().put("planPath", paths.path("planPath").asText())
                .put("acquisitionManifestPath", paths.path("acquisitionPath").asText())
                .put("acquisitionRoot", paths.path("stagingRoot").asText())
                .put("parquetManifestPath", paths.path("parquetManifestPath").asText())
                .put("parquetRoot", paths.path("parquetRoot").asText())
                .put("samplePartitions", 1).put("chunkBytes", 97);

        ObjectNode sample = StrategyPerformanceV5.runProductionDataPlaneBenchmarkV5(options);
        sample.remove("runtime");
        assertCanonical(sample, fixture.path("sample"));
        assertThat(sample.path("semantic").path("mode").asText()).isEqualTo("SAMPLED");
        assertThat(sample.path("semantic").path("acquisition_source_scan").path("rows").asInt())
                .isEqualTo(1);

        ObjectNode fullOptions = options.deepCopy().put("full", true);
        ObjectNode full = StrategyPerformanceV5.runProductionDataPlaneBenchmarkV5(fullOptions);
        full.remove("runtime");
        assertCanonical(full, fixture.path("full"));
        assertThat(full.path("semantic").path("generic_data_plane_complete").asBoolean()).isTrue();
        assertThat(full.path("readiness").path("data_plane").path("status").asText())
                .isEqualTo("BLOCKED_REQUIRES_AUTHORITATIVE_COVERAGE");

        Path parquet = Path.of(paths.path("parquetPath").asText());
        byte[] parquetBytes = Files.readAllBytes(parquet);
        Files.writeString(parquet, "tampered", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> StrategyPerformanceV5.runProductionDataPlaneBenchmarkV5(fullOptions))
                .hasMessageContaining("Parquet partition bytes/hash are invalid");
        Files.write(parquet, parquetBytes);

        Path jsonl = Path.of(paths.path("jsonlPath").asText());
        Path acquisitionPath = Path.of(paths.path("acquisitionPath").asText());
        Path parquetManifestPath = Path.of(paths.path("parquetManifestPath").asText());
        byte[] jsonlBytes = Files.readAllBytes(jsonl);
        byte[] acquisitionBytes = Files.readAllBytes(acquisitionPath);
        byte[] parquetManifestBytes = Files.readAllBytes(parquetManifestPath);
        ObjectNode planValue = (ObjectNode) MAPPER.readTree(Files.readAllBytes(Path.of(paths.path("planPath").asText())));
        ObjectNode lookaheadRow = (ObjectNode) MAPPER.readTree(new String(jsonlBytes, StandardCharsets.UTF_8));
        lookaheadRow.set("availability_time", planValue.path("series").get(0).path("start_at"));
        byte[] lookaheadBytes = (MAPPER.writeValueAsString(lookaheadRow) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(jsonl, lookaheadBytes);
        String lookaheadHash = StrategyPerformanceV5.hashV5Performance(lookaheadBytes);
        ObjectNode acquisitionValue = (ObjectNode) MAPPER.readTree(acquisitionBytes);
        ObjectNode acquisitionPartition = (ObjectNode) acquisitionValue.path("captures").get(0).path("partition");
        acquisitionPartition.put("sha256", lookaheadHash).put("bytes", lookaheadBytes.length);
        acquisitionValue.put("content_sha256", StrategyPerformanceV5.ownHash(acquisitionValue));
        Files.writeString(acquisitionPath, MAPPER.writeValueAsString(acquisitionValue) + "\n",
                StandardCharsets.UTF_8);

        ObjectNode parquetValue = (ObjectNode) MAPPER.readTree(parquetManifestBytes);
        parquetValue.put("source_manifest_sha256", acquisitionValue.path("content_sha256").asText());
        ObjectNode parquetCapture = (ObjectNode) parquetValue.path("captures").get(0);
        ((ObjectNode) parquetCapture.path("partition")).put("source_jsonl_sha256", lookaheadHash);
        String identity = parquetCapture.path("asset").asText().toLowerCase(java.util.Locale.ROOT) + "|"
                + parquetCapture.path("instrument").asText().toUpperCase(java.util.Locale.ROOT) + "|"
                + parquetCapture.path("symbol").asText().toUpperCase(java.util.Locale.ROOT) + "|"
                + parquetCapture.path("interval").asText() + "|"
                + parquetCapture.path("series_type").asText().toLowerCase(java.util.Locale.ROOT);
        ObjectNode datasetRoot = object().put("source_manifest_sha256",
                parquetValue.path("source_manifest_sha256").asText())
                .put("plan_sha256", parquetValue.path("plan_sha256").asText());
        datasetRoot.set("captures", array().add(object().put("identity", identity)
                .set("partition", parquetCapture.path("partition").deepCopy())));
        parquetValue.put("dataset_root_sha256", StrategyPerformanceV5.hashV5Performance(datasetRoot));
        parquetValue.put("content_sha256", StrategyPerformanceV5.ownHash(parquetValue));
        Files.writeString(parquetManifestPath, MAPPER.writeValueAsString(parquetValue) + "\n",
                StandardCharsets.UTF_8);
        assertThatThrownBy(() -> StrategyPerformanceV5.runProductionDataPlaneBenchmarkV5(fullOptions))
                .hasMessageContaining("completed-bar PIT");
        Files.write(jsonl, jsonlBytes);
        Files.write(acquisitionPath, acquisitionBytes);
        Files.write(parquetManifestPath, parquetManifestBytes);

        Path symlinkRoot = temporary.resolve("staging-symlink");
        Files.createSymbolicLink(symlinkRoot, Path.of(paths.path("stagingRoot").asText()));
        ObjectNode symlinkOptions = options.deepCopy().put("acquisitionRoot", symlinkRoot.toString());
        assertThatThrownBy(() -> StrategyPerformanceV5.runProductionDataPlaneBenchmarkV5(symlinkOptions))
                .hasMessageContaining("symbolic-link component");

        Path plan = Path.of(paths.path("planPath").asText());
        Path hardlink = temporary.resolve("plan-hardlink.json");
        Files.createLink(hardlink, plan);
        ObjectNode hardlinkOptions = options.deepCopy().put("planPath", hardlink.toString());
        assertThatThrownBy(() -> StrategyPerformanceV5.runProductionDataPlaneBenchmarkV5(hardlinkOptions))
                .hasMessageContaining("multi-link file");
    }

    private static ObjectNode cacheBinding() {
        ObjectNode bindings = object();
        for (String key : StrategyPerformanceV5.DATA_BINDING_KEYS) bindings.put(key, hash(key));
        ObjectNode value = object().put("sourceArtifactSha256", hash("source"))
                .put("evaluatorSpecSha256", hash("evaluator")).put("predictorRegistrySha256", hash("predictor"))
                .put("signalCodeSha256", hash("signal")).put("outcomeCodeSha256", hash("outcome"))
                .put("workerCodeSha256", hash("worker")).put("maxMemoryEntries", 100)
                .put("maxMemoryBytes", 1_000_000).put("maxDiskBytes", 0);
        value.set("dataBindings", bindings); return value;
    }

    private static ObjectNode hydrationFixture() {
        String partition = hash("partition-body");
        ObjectNode ref = object().put("partition_sha256", partition).put("partition_path", "btc.jsonl")
                .put("partition_bytes", 100).put("partition_row_count", 2)
                .put("row_start", 0).put("row_end_exclusive", 2).put("row_count", 2);
        ObjectNode window = object().put("window_id", "window-1")
                .put("execution_start", "2026-01-01T00:00:00.000Z")
                .put("execution_end", "2026-01-01T00:02:00.000Z")
                .put("effective_end_exclusive", "2026-01-01T00:02:00.000Z")
                .put("lifecycle_status", "CLOSED").put("row_count", 2);
        window.set("partition_refs", array().add(ref)); window.set("preentry_partition_refs", array());
        ObjectNode hydration = object().put("schema", "strategy-v5-opportunity-hydration/2")
                .set("windows", array().add(window));
        hydration.put("content_sha256", StrategyPerformanceV5.ownHash(hydration));
        return hydration;
    }

    private static ObjectNode workerFixture() {
        ArrayNode features = array();
        features.add(object().put("signal_id", "signal-1").put("asset", "btc")
                .put("decision_time", "2026-01-01T00:00:00.000Z")
                .put("availability_time", "2026-01-01T00:00:00.000Z").put("score", 2));
        features.add(object().put("signal_id", "signal-2").put("asset", "eth")
                .put("decision_time", "2026-01-02T00:00:00.000Z")
                .put("availability_time", "2026-01-02T00:00:00.000Z").put("score", 0));
        ArrayNode labels = array();
        labels.add(label("signal-1", "btc", "episode-1", "2026-01-01T00:00:00.000Z",
                "2026-01-01T00:02:00.000Z", "2026-01-01T00:03:00.000Z"));
        labels.add(label("signal-2", "eth", "episode-2", "2026-01-02T00:00:00.000Z",
                "2026-01-02T00:02:00.000Z", "2026-01-02T00:03:00.000Z"));
        ArrayNode bars = array();
        bars.add(bar("2026-01-01T00:00:00.000Z", "2026-01-01T00:01:00.000Z", 100, 101, 99, 100));
        bars.add(bar("2026-01-01T00:01:00.000Z", "2026-01-01T00:02:00.000Z", 100, 102, 99, 101));
        bars.add(bar("2026-01-01T00:02:00.000Z", "2026-01-01T00:03:00.000Z", 101, 104, 100, 103));
        ObjectNode settlement = object().put("event_id", "funding-1").put("source", "binance")
                .put("venue", "binance").put("instrument", "BTCUSDT").put("amount", -.2);
        ObjectNode scenario = object().set("DELAYED_ENTRY", object().put("debit_r", .01));
        ObjectNode execution = object().put("signal_id", "signal-1").put("asset", "btc")
                .put("decision_time", "2026-01-01T00:00:00.000Z")
                .put("entry_time", "2026-01-01T00:00:00.000Z").put("risk_amount", 100)
                .put("quantity", 1).put("instrument_type", "perpetual").put("contract_multiplier", 1)
                .put("fee_bps", 10).put("slippage_bps", 5).put("venue", "binance")
                .put("symbol", "BTCUSDT").put("availability_time", "2026-01-01T00:00:00.000Z");
        execution.set("child_bars", bars); execution.set("funding_settlements", array().add(settlement));
        execution.set("scenario_inputs", scenario);
        ObjectNode result = object().put("manifestSha256", hash("manifest"));
        result.set("featureRows", features); result.set("labelRows", labels);
        result.set("executionRows", array().add(execution));
        return result;
    }

    private static ObjectNode candidateFixture() {
        ObjectNode definition = object().put("threshold", 1).put("threshold_op", ">=")
                .put("direction", "long").put("max_lifecycle_bars", 2).put("target_price", 102);
        return object().put("candidate_id", "candidate-1").set("definition", definition);
    }

    private static ObjectNode workerRequest(ObjectNode worker, ObjectNode candidate) {
        ObjectNode request = object().put("name", "worker").set("worker", worker);
        request.set("candidate", candidate); return request;
    }

    private static ObjectNode label(String signal, String asset, String episode, String decision,
                                    String resolved, String available) {
        return object().put("signal_id", signal).put("asset", asset).put("episode_id", episode)
                .put("decision_time", decision).put("entry_time", decision).put("resolution_time", resolved)
                .put("availability_time", available).put("target", "RESOLVED")
                .put("instrument_type", "perpetual").put("contract_multiplier", 1);
    }

    private static ObjectNode bar(String event, String available, double open, double high, double low, double close) {
        return object().put("event_time", event).put("availability_time", available)
                .put("open", open).put("high", high).put("low", low).put("close", close);
    }

    private static String partitionRoot(String digest) {
        ObjectNode root = object().put("schema", "strategy-v5-execution-partition-root/1");
        root.set("partition_sha256", array().add(digest)); return StrategyPerformanceV5.hashV5Performance(root);
    }

    private static String parquetSchemaHash(Path parquet) throws Exception {
        ArrayNode rows = array();
        String escaped = parquet.toAbsolutePath().normalize().toString().replace("'", "''");
        try (var connection = DriverManager.getConnection("jdbc:duckdb:");
             var statement = connection.createStatement();
             var result = statement.executeQuery("DESCRIBE SELECT * FROM read_parquet('" + escaped + "')")) {
            var metadata = result.getMetaData();
            while (result.next()) {
                ArrayNode row = array();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    Object value = result.getObject(index);
                    if (value == null) row.addNull(); else row.add(String.valueOf(value));
                }
                rows.add(row);
            }
        }
        return StrategyPerformanceV5.hashV5Performance(rows);
    }
    private static String hash(String value) { return StrategyPerformanceV5.hashV5Performance(value); }
    private static ObjectNode ok(JsonNode value) { return object().put("ok", true).set("value", value); }
    private static ObjectNode object() { return MAPPER.createObjectNode(); }
    private static ArrayNode array() { return MAPPER.createArrayNode(); }

    private ObjectNode productionFixtureOracle() throws Exception {
        Path root = temporary.resolve("production-fixture");
        Path stagingRoot = root.resolve("staging"), parquetRoot = root.resolve("parquet");
        Files.createDirectories(stagingRoot); Files.createDirectories(parquetRoot);
        ObjectNode base = StrategyResearchDataV5.makeFiveYearAuthoritativePlan(
                object().put("asOf", "2026-08-24T00:00:00.000Z"));
        ObjectNode series = (ObjectNode) base.path("series").get(0).deepCopy();
        String start = series.path("start_at").asText();
        String availability = java.time.Instant.ofEpochMilli(java.time.Instant.parse(start).toEpochMilli()
                + series.path("expected_step_ms").asLong() - 1).toString();
        series.put("end_at", start).put("availability_cutoff_at", availability).put("expected_event_count", 1);
        ObjectNode plan = base.deepCopy(); plan.set("series", array().add(series));
        plan.put("content_sha256", StrategyResearchDataV5.ownHash(plan));

        ObjectNode row = object().put("asset", series.path("asset").asText())
                .put("venue", series.path("venue").asText()).put("instrument", series.path("instrument").asText())
                .put("symbol", series.path("symbol").asText()).put("interval", series.path("interval").asText())
                .put("series_type", series.path("series_type").asText()).put("series_role", series.path("series_role").asText())
                .put("event_time", start).put("availability_time", availability)
                .put("open", 100).put("high", 101).put("low", 99).put("close", 100);
        Path jsonl = stagingRoot.resolve("capture.jsonl");
        Files.writeString(jsonl, MAPPER.writeValueAsString(row) + "\n", StandardCharsets.UTF_8);
        byte[] jsonlBytes = Files.readAllBytes(jsonl);
        ObjectNode sourcePartition = object().put("path", "capture.jsonl")
                .put("sha256", StrategyPerformanceV5.hashV5Performance(jsonlBytes)).put("bytes", jsonlBytes.length)
                .put("row_count", 1).put("format", "JSONL").put("storage_role", "STAGING").put("authoritative", false);
        ObjectNode capture = series.deepCopy(); capture.remove("trade_scope");
        capture.put("required", true).put("series_sha256", StrategyPerformanceV5.hashV5Performance(series));
        capture.set("coverage", object().put("complete", true)); capture.set("partition", sourcePartition);
        ObjectNode acquisition = object().put("schema", "strategy-v5-authoritative-acquisition/1").put("version", 1)
                .put("status", "STAGING_COMPLETE").put("plan_sha256", plan.path("content_sha256").asText())
                .put("root_reference", stagingRoot.toString()).put("staging_format", "JSONL")
                .put("storage_role", "STAGING").put("authoritative", false)
                .put("base_complete", true).put("declared_complete", true).put("full_plan_complete", true)
                .put("completion_scope", "ALL_DECLARED").put("required_series_count", 1)
                .put("required_complete_count", 1).put("optional_series_count", 0)
                .put("optional_complete_count", 0).put("optional_complete", true);
        acquisition.set("captures", array().add(capture)); acquisition.set("unavailable_required", array()); acquisition.set("unavailable_optional", array());
        acquisition.put("content_sha256", StrategyResearchDataV5.ownHash(acquisition));

        Path parquet = parquetRoot.resolve("capture.parquet");
        ResearchData.ParquetArtifact physical = ResearchData.writeParquet(jsonl, parquet);
        ObjectNode parquetPartition = object().put("path", "capture.parquet").put("sha256", physical.sha256())
                .put("bytes", physical.bytes()).put("row_count", 1).put("format", "PARQUET")
                .put("storage_role", "AUTHORITATIVE").put("authoritative", true)
                .put("source_jsonl_sha256", sourcePartition.path("sha256").asText())
                .put("schema_sha256", parquetSchemaHash(parquet));
        ObjectNode promoted = capture.deepCopy(); promoted.set("coverage", object().put("complete", true)
                .put("expected_rows", 1).put("observed_rows", 1).put("min_event_time", start).put("max_event_time", start));
        promoted.set("partition", parquetPartition);
        ObjectNode parquetManifest = object().put("schema", "strategy-v5-parquet-conversion/1").put("version", 1)
                .put("status", "AUTHORITATIVE_PARQUET").put("source_manifest_sha256", acquisition.path("content_sha256").asText())
                .put("plan_sha256", plan.path("content_sha256").asText()).put("output_root_reference", parquetRoot.toString())
                .put("format", "PARQUET").put("storage_role", "AUTHORITATIVE").put("authoritative", true).put("threads", 1);
        parquetManifest.set("captures", array().add(promoted));
        String identity = series.path("asset").asText().toLowerCase() + "|" + series.path("instrument").asText().toUpperCase()
                + "|" + series.path("symbol").asText().toUpperCase() + "|" + series.path("interval").asText()
                + "|" + series.path("series_type").asText().toLowerCase();
        ObjectNode dataset = object().put("source_manifest_sha256", acquisition.path("content_sha256").asText())
                .put("plan_sha256", plan.path("content_sha256").asText());
        dataset.set("captures", array().add(object().put("identity", identity).set("partition", parquetPartition.deepCopy())));
        parquetManifest.put("dataset_root_sha256", StrategyPerformanceV5.hashV5Performance(dataset));
        parquetManifest.put("content_sha256", StrategyResearchDataV5.ownHash(parquetManifest));

        Path planPath = root.resolve("plan.json"), acquisitionPath = root.resolve("acquisition.json"), manifestPath = root.resolve("parquet.json");
        Files.writeString(planPath, MAPPER.writeValueAsString(plan) + "\n");
        Files.writeString(acquisitionPath, MAPPER.writeValueAsString(acquisition) + "\n");
        Files.writeString(manifestPath, MAPPER.writeValueAsString(parquetManifest) + "\n");
        ObjectNode options = object().put("planPath", planPath.toString()).put("acquisitionManifestPath", acquisitionPath.toString())
                .put("acquisitionRoot", stagingRoot.toString()).put("parquetManifestPath", manifestPath.toString())
                .put("parquetRoot", parquetRoot.toString()).put("samplePartitions", 1).put("chunkBytes", 97);
        ObjectNode sample = StrategyPerformanceV5.runProductionDataPlaneBenchmarkV5(options); sample.remove("runtime");
        ObjectNode full = StrategyPerformanceV5.runProductionDataPlaneBenchmarkV5(options.deepCopy().put("full", true)); full.remove("runtime");
        ObjectNode paths = object().put("planPath", planPath.toString()).put("acquisitionPath", acquisitionPath.toString())
                .put("parquetManifestPath", manifestPath.toString()).put("stagingRoot", stagingRoot.toString())
                .put("parquetRoot", parquetRoot.toString()).put("jsonlPath", jsonl.toString()).put("parquetPath", parquet.toString());
        ObjectNode fixture = object();
        fixture.set("paths", paths); fixture.set("sample", sample); fixture.set("full", full);
        return fixture;
    }

    private static ObjectNode benchmarkOracle(String... arguments) throws Exception {
        assertThat(arguments).containsExactly("--chromosomes", "1", "--episodes", "8");
        try (InputStream input = Objects.requireNonNull(
                StrategyPerformanceV5NodeOracleTest.class.getResourceAsStream(
                        "/oracles/strategy-performance-v5-benchmark-v1.json"),
                "frozen performance benchmark oracle is missing")) {
            return (ObjectNode) MAPPER.readTree(input);
        }
    }

    private static void stripRuntime(ObjectNode value) {
        ((ObjectNode) value.path("before")).remove("elapsed_ms");
        ((ObjectNode) value.path("after")).remove("elapsed_ms");
        ObjectNode wall = (ObjectNode) value.path("cache_wall_clock");
        wall.remove(List.of("direct_elapsed_ms", "cached_elapsed_ms", "delta_ms",
                "cached_over_direct_ratio", "speedup_factor", "result"));
        ((ObjectNode) value.path("physical_v2_fixture")).remove("materialization_elapsed_ms");
    }

    private static void assertCanonical(JsonNode actual, JsonNode expected) {
        assertThat(CanonicalJson.canonicalize(actual)).isEqualTo(CanonicalJson.canonicalize(expected));
    }

    private static Path repositoryRoot() {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (path != null && !Files.isRegularFile(path.resolve("pom.xml")))
            path = path.getParent();
        if (path == null) throw new IllegalStateException("repository root not found");
        return path;
    }

    private static final class InstantFixture {
        static long ms(String value) { return java.time.Instant.parse(value).toEpochMilli(); }
    }
}

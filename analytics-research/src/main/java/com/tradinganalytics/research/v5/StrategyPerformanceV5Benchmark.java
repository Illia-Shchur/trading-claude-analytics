package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Directly executable performance benchmark and production data-plane verifier.
 */
public final class StrategyPerformanceV5Benchmark {
    public static final String BENCHMARK_SCHEMA = "strategy-v5-performance-benchmark/3";
    private static final ObjectMapper MAPPER = StrategyPerformanceV5.jsonMapper();
    private static final ResearchSchemaRegistry SCHEMAS = ResearchSchemaRegistry.defaultRegistry();
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");
    private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private StrategyPerformanceV5Benchmark() {}

    /** Parse and execute the exact benchmark command surface. */
    public static ObjectNode runBenchmarkV5(String... arguments) {
        ObjectNode args = parseArguments(arguments == null ? new String[0] : arguments);
        boolean productionInput = truthy(firstTruthy(args, "plan", "frozen-plan", "acquisition-manifest",
                "acquisition", "parquet-manifest", "parquet_manifest"));
        boolean full = trueFlag(args.get("full"));
        if (full && !productionInput)
            throw failure("--full requires frozen plan, acquisition manifest, and Parquet manifest inputs");
        if (productionInput) {
            ObjectNode options = object();
            copyTruthy(options, "planPath", args, "plan", "frozen-plan");
            copyTruthy(options, "acquisitionManifestPath", args, "acquisition-manifest", "acquisition");
            copyTruthy(options, "acquisitionRoot", args, "acquisition-root");
            copyTruthy(options, "parquetManifestPath", args, "parquet-manifest", "parquet_manifest");
            copyTruthy(options, "parquetRoot", args, "parquet-root", "parquet_root");
            copyTruthy(options, "coveragePath", args, "coverage");
            options.put("full", full);
            options.put("samplePartitions", commandNumber(firstTruthy(args, "sample-partitions", "sample"), 1));
            options.put("chunkBytes", commandNumber(firstTruthy(args, "chunk-bytes"), 1024 * 1024));
            return runProductionDataPlaneBenchmarkV5(options);
        }
        return runFixtureBenchmarkV5(args);
    }

    /** Stream-adapter entry point used by Spring/Picocli and direct Java callers. */
    public static int run(String[] arguments, PrintStream stdout, PrintStream stderr) {
        try {
            stdout.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(
                    runBenchmarkV5(arguments == null ? new String[0] : arguments)));
            return 0;
        } catch (RuntimeException | IOException error) {
            stderr.println(rootMessage(error));
            return 1;
        }
    }

    public static void main(String[] arguments) {
        int status = run(arguments, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    /** Deterministic fixture-sized counterpart of the Node benchmark's smoke branch. */
    public static ObjectNode runFixtureBenchmarkV5(ObjectNode arguments) {
        ObjectNode args = arguments == null ? object() : arguments;
        int assets = 8, outerFolds = 8, innerFolds = 2;
        int sampleChromosomes = Math.max(1, (int) commandNumber(args.get("chromosomes"), 8));
        int episodesPerAsset = Math.max(8, (int) commandNumber(args.get("episodes"), 96));
        ObjectNode dataBindings = object().put("feature_artifact_sha256", hash("synthetic-features"))
                .put("label_artifact_sha256", hash("synthetic-labels"))
                .put("execution_artifact_sha256", hash("synthetic-execution"))
                .put("mark_artifact_sha256", hash("synthetic-marks"))
                .put("metadata_artifact_sha256", hash("synthetic-metadata"));
        ObjectNode binding = object().put("sourceArtifactSha256", hash("synthetic-source"))
                .put("evaluatorSpecSha256", hash("synthetic-evaluator"))
                .put("predictorRegistrySha256", hash("synthetic-predictors"))
                .put("signalCodeSha256", hash("synthetic-signal-code"))
                .put("outcomeCodeSha256", hash("synthetic-outcome-code"))
                .put("workerCodeSha256", hash("synthetic-worker"))
                .put("maxMemoryEntries", 1_000_000).put("maxMemoryBytes", 512L * 1024 * 1024)
                .put("maxDiskBytes", 0);
        binding.set("dataBindings", dataBindings);
        Map<String, JsonNode> features = new LinkedHashMap<>();
        List<String> assetNames = new ArrayList<>();
        for (int assetIndex = 0; assetIndex < assets; assetIndex++) {
            String asset = "asset-" + (assetIndex + 1); assetNames.add(asset);
            for (int index = 0; index < episodesPerAsset; index++)
                features.put(asset + "-e" + String.format(Locale.ROOT, "%04d", index), object().put("edge", 1));
        }
        List<ObjectNode> chromosomes = new ArrayList<>();
        for (int index = 0; index < sampleChromosomes; index++)
            chromosomes.add(object().put("threshold", 0).put("chromosome_id", index));
        long directSignal = 0, directOutcome = 0;
        long beforeStarted = System.nanoTime();
        for (int fold = 0; fold < outerFolds; fold++) for (String asset : assetNames)
            for (List<String> scope : scopes(features, asset)) for (ObjectNode ignored : chromosomes)
                for (String ignoredId : scope) { directSignal++; directOutcome++; }
        double beforeMs = elapsedMillis(beforeStarted);

        StrategyPerformanceV5.ScopeVectorCache cache = StrategyPerformanceV5.makeScopeVectorCacheV5(binding);
        long afterStarted = System.nanoTime();
        for (int fold = 0; fold < outerFolds; fold++) for (String asset : assetNames) {
            for (List<String> scope : scopes(features, asset)) for (ObjectNode chromosome : chromosomes) {
                cache.evaluate(new StrategyPerformanceV5.EvaluationRequest(chromosome, null, scope, scope,
                        "TRAIN_ONLY", "outer-" + fold,
                        "2026-01-" + String.format(Locale.ROOT, "%02d", fold + 1) + "T00:00:00.000Z",
                        "2026-02-" + String.format(Locale.ROOT, "%02d", fold + 1) + "T00:00:00.000Z",
                        features, (id, feature, value) -> object().put("intent", true),
                        (id, feature, signal, value, phase, foldId, fit, evaluation) ->
                                object().put("net_r", .01).put("traded", true)));
            }
        }
        double afterMs = elapsedMillis(afterStarted); ObjectNode diagnostics = cache.diagnostics();
        double signalReduction = 1 - diagnostics.path("signal_compute_count").asDouble() / directSignal;
        double outcomeReduction = 1 - diagnostics.path("outcome_compute_count").asDouble() / directOutcome;
        double callbackReduction = 1 - (diagnostics.path("signal_compute_count").asDouble()
                + diagnostics.path("outcome_compute_count").asDouble()) / (directSignal + directOutcome);
        double wallDelta = afterMs - beforeMs;
        Double wallRatio = beforeMs > 0 ? afterMs / beforeMs : null;
        Double speedup = afterMs > 0 ? beforeMs / afterMs : null;

        long physicalStarted = System.nanoTime(); List<ObjectNode> references = new ArrayList<>();
        long nestedBytes = 0, referenceBytes = 0, partitionBytes = 0;
        int partitionCount = 0; long physicalCacheHits = 0, physicalDiskReads = 0;
        for (int assetIndex = 0; assetIndex < assets; assetIndex++) {
            String asset = "asset-" + (assetIndex + 1);
            String decision = "2026-01-01T00:" + String.format(Locale.ROOT, "%02d", assetIndex) + ":00.000Z";
            long start = Instant.parse(decision).toEpochMilli(); ArrayNode bars = array();
            for (int offset = 0; offset < 96; offset++) {
                bars.add(object().put("event_time", ISO_MILLIS.format(Instant.ofEpochMilli(start + offset * 60_000L)))
                        .put("open", 100 + offset).put("high", 101 + offset)
                        .put("low", 99 + offset).put("close", 100 + offset));
            }
            ObjectNode partitionArgs = object().put("partition_ms", 15 * 60_000); partitionArgs.set("bars", bars);
            ObjectNode partitionSet = OpportunityV5.makeContentAddressedPartitionsV5(partitionArgs);
            ArrayNode partitions = (ArrayNode) partitionSet.path("partitions");
            ObjectNode feature = object().put("asset", asset).put("instrument", "BINANCE_SPOT")
                    .put("symbol", asset.toUpperCase(Locale.ROOT) + "USDT").put("decision_time", decision)
                    .put("availability_time", decision).put("score", 1);
            ObjectNode gene = object().put("name", "threshold").put("type", "continuous")
                    .put("min", 0).put("max", 2);
            ObjectNode predicateValue = object().put("$gene", "threshold");
            ObjectNode predicate = object().put("predictor_id", "score").put("op", "GTE");
            predicate.set("value", predicateValue);
            ObjectNode envelopeArgs = object().put("fixtureOnly", true).put("max_lifecycle_ms", 30 * 60_000);
            envelopeArgs.set("featureRows", array().add(feature));
            envelopeArgs.set("geneSpace", object().set("genes", array().add(gene)));
            envelopeArgs.set("predicate", predicate);
            ObjectNode envelope = OpportunityV5.makeOpportunityEnvelopeV5(envelopeArgs);
            ObjectNode hydrationArgs = object().put("fixtureOnly", true); hydrationArgs.set("envelope", envelope);
            hydrationArgs.set("partitions", partitions);
            ObjectNode hydration = OpportunityV5.hydrateOpportunityEnvelopeV5(hydrationArgs);
            String windowId = text(envelope.path("windows").get(0).get("window_id"));
            ObjectNode referenceArgs = object().put("windowId", windowId).put("asset", asset)
                    .put("instrument", "BINANCE_SPOT").put("symbol", asset.toUpperCase(Locale.ROOT) + "USDT");
            referenceArgs.set("hydration", hydration);
            ObjectNode reference = StrategyPerformanceV5.makeLazyExecutionReferenceV5(referenceArgs);
            StrategyPerformanceV5.BoundedPartitionReadCache partitionCache =
                    StrategyPerformanceV5.makeBoundedPartitionReadCacheV5(object()
                            .put("partitionRootSha256", text(reference.get("partition_root_sha256")))
                            .put("maxResidentBytes", 16 * 1024 * 1024).put("maxEntryBytes", 16 * 1024 * 1024));
            List<ObjectNode> partitionList = new ArrayList<>(); partitions.forEach(row -> partitionList.add((ObjectNode) row));
            ObjectNode materialized = StrategyPerformanceV5.materializeLazyExecutionReferenceV5(reference,
                    hydration, partitionList, partitionCache, 4_096, 100_000,
                    192L * 1024 * 1024, 16L * 1024 * 1024);
            StrategyPerformanceV5.materializeLazyExecutionReferenceV5(reference, hydration, partitionList,
                    partitionCache, 4_096, 100_000, 192L * 1024 * 1024, 16L * 1024 * 1024);
            ObjectNode partitionDiagnostics = partitionCache.diagnostics(); references.add(reference);
            nestedBytes += compactBytes(materialized.get("child_bars")); referenceBytes += compactBytes(reference);
            for (JsonNode partition : partitions) partitionBytes += partition.path("bytes").asLong();
            partitionCount += partitions.size(); physicalCacheHits += partitionDiagnostics.path("cache_hit_count").asLong();
            physicalDiskReads += partitionDiagnostics.path("disk_read_count").asLong();
        }
        double physicalMs = elapsedMillis(physicalStarted);
        ObjectNode checkpointSmoke = statisticalCheckpointSmoke();
        ObjectNode estimateOptions = object().put("assets", assets).put("outerFolds", outerFolds)
                .put("innerFolds", innerFolds).put("physicalSourceWindows", references.size())
                .put("physicalSourcePartitionsPerWindow", partitionCount / (double) Math.max(1, references.size()))
                .put("physicalSourcePartitionBytes", partitionBytes).put("rolePayloadBytes", 2_000_000_000L)
                .put("lazyReferenceBytes", 2_000_000).put("residentPartitionBytes", 192L * 1024 * 1024)
                .put("workers", 2).put("partitionSharing", "PER_WORKER");
        ObjectNode estimate = StrategyPerformanceV5.estimateProductionWorkloadV5(estimateOptions);

        ObjectNode shape = object().put("assets", assets).put("outer_folds", outerFolds)
                .put("inner_folds_per_asset", innerFolds).put("sample_chromosomes", sampleChromosomes)
                .put("episodes_per_asset", episodesPerAsset);
        ObjectNode before = object().put("signal_callbacks", directSignal).put("outcome_callbacks", directOutcome)
                .put("elapsed_ms", round(beforeMs, 3));
        ObjectNode after = object().put("signal_callbacks", diagnostics.path("signal_compute_count").asLong())
                .put("outcome_callbacks", diagnostics.path("outcome_compute_count").asLong())
                .put("signal_cache_hits", diagnostics.path("signal_hit_count").asLong())
                .put("outcome_cache_hits", diagnostics.path("outcome_hit_count").asLong())
                .put("disk_revalidation_count", diagnostics.path("disk_revalidation_count").asLong())
                .put("elapsed_ms", round(afterMs, 3)).put("signal_callback_reduction_fraction", round(signalReduction, 6))
                .put("outcome_callback_reduction_fraction", round(outcomeReduction, 6))
                .put("callback_reduction_fraction", round(callbackReduction, 6))
                .put("scope_independent_outcome_reuse", false);
        ObjectNode wall = object().put("direct_elapsed_ms", round(beforeMs, 3))
                .put("cached_elapsed_ms", round(afterMs, 3)).put("delta_ms", round(wallDelta, 3));
        putNullable(wall, "cached_over_direct_ratio", wallRatio == null ? null : round(wallRatio, 6));
        putNullable(wall, "speedup_factor", speedup == null ? null : round(speedup, 6));
        wall.put("result", afterMs <= beforeMs ? "CACHE_WALL_CLOCK_FASTER_ON_THIS_FIXTURE"
                : "CACHE_WALL_CLOCK_SLOWER_ON_THIS_FIXTURE");
        wall.put("interpretation", "CALLBACK_REDUCTION_AND_WALL_CLOCK_ARE_SEPARATE; THIS FIXTURE IS NOT A REPRESENTATIVE PRODUCTION SPEEDUP BENCHMARK");
        ObjectNode physical = object().put("representative", true).put("production_data", false)
                .put("assets", references.size()).put("reference_bytes", referenceBytes)
                .put("nested_child_bytes", nestedBytes).put("partition_bytes", partitionBytes)
                .put("partition_count", partitionCount).put("partition_cache_hit_count", physicalCacheHits)
                .put("partition_disk_read_count", physicalDiskReads).put("materialization_elapsed_ms", round(physicalMs, 3));
        putNullable(physical, "reference_payload_reduction_fraction",
                nestedBytes > 0 ? round(1 - referenceBytes / (double) nestedBytes, 6) : null);
        ObjectNode result = object().put("schema", BENCHMARK_SCHEMA); result.set("shape", shape);
        result.set("before", before); result.set("after", after); result.set("cache_wall_clock", wall);
        result.set("checkpoint_resume_smoke", checkpointSmoke); result.set("physical_v2_fixture", physical);
        result.set("production_readiness", object().put("ready", false)
                .put("status", "BLOCKED_REQUIRES_AUTHORITATIVE_V2_PRODUCTION_BENCHMARK"));
        result.set("production_estimate", estimate);
        result.set("production_complexity", StrategyPerformanceV5.estimateProductionComplexityV5(estimateOptions));
        return result;
    }

    /** Return the discovered funding cadence segment covering an event. */
    public static ObjectNode productionFundingSegment(ObjectNode capture, long event) {
        JsonNode rawSegments = capture == null ? null : capture.path("coverage").get("cadence_segments");
        ArrayNode segments = rawSegments instanceof ArrayNode rows ? rows : StrategyPerformanceV5.jsonMapper().createArrayNode();
        JsonNode rawTolerance = firstNonNull(capture == null ? null : capture.path("coverage"), "slot_tolerance_ms");
        if (nullish(rawTolerance)) rawTolerance = firstNonNull(capture, "slot_tolerance_ms");
        double parsedTolerance = jsNumber(rawTolerance);
        long tolerance = Double.isFinite(parsedTolerance) && parsedTolerance >= 0 ? (long) parsedTolerance : 0;
        for (int index = 0; index < segments.size(); index++) {
            JsonNode row = segments.get(index);
            Long from = epochMillis(row.get("effective_from"));
            Long to = epochMillis(row.get("effective_to"));
            if (from != null && to != null && event >= from - tolerance
                    && (index == segments.size() - 1 ? event <= to + tolerance : event < to))
                return row instanceof ObjectNode object ? object.deepCopy() : null;
        }
        JsonNode last = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        return last instanceof ObjectNode object ? object.deepCopy() : null;
    }

    /** Reopen, validate, and measure the opt-in v5 physical data plane. */
    public static ObjectNode runProductionDataPlaneBenchmarkV5(ObjectNode options) {
        ObjectNode o = options == null ? object() : options;
        long started = System.nanoTime(); long rssStart = usedMemory(); long maxRss = rssStart; long maxChunk = 0;
        long scannedBytes = 0, scannedRows = 0, parquetChunks = 0, acquisitionBytes = 0, acquisitionRows = 0, acquisitionPartitions = 0, acquisitionChunks = 0;
        ProductionDocument planDocument = readDocument(firstTruthy(o, "planPath", "plan_path", "frozenPlan", "frozen_plan"), "frozen plan", Set.of(StrategyPerformanceV5.PLAN_SCHEMA));
        ObjectNode plan = validatePlan(planDocument);
        ProductionDocument acquisitionDocument = readDocument(firstTruthy(o, "acquisitionManifestPath", "acquisition_manifest_path", "acquisitionManifest", "acquisition_manifest"), "acquisition manifest", Set.of(StrategyPerformanceV5.ACQUISITION_SCHEMA));
        ObjectNode acquisition = validateAcquisition(acquisitionDocument, plan);
        ProductionDocument parquetDocument = readDocument(firstTruthy(o, "parquetManifestPath", "parquet_manifest_path", "parquetManifest", "parquet_manifest"), "Parquet manifest", Set.of(StrategyPerformanceV5.PARQUET_CONVERSION_SCHEMA, StrategyPerformanceV5.SEPARATED_ARTIFACT_SCHEMA));
        ParquetValidation parquetValidation = validateParquet(parquetDocument, plan, acquisition); ObjectNode parquet = parquetValidation.parquet(); List<ObjectNode> parquetRows = parquetValidation.rows();
        JsonNode coverageInput = firstTruthy(o, "coveragePath", "coverage_path", "coverage");
        ProductionDocument coverageDocument = truthy(coverageInput) ? readDocument(coverageInput, "coverage manifest", Set.of(StrategyPerformanceV5.AUTHORITATIVE_COVERAGE_SCHEMA, StrategyPerformanceV5.PROMOTED_COVERAGE_SCHEMA)) : null;
        ObjectNode coverage = validateCoverage(coverageDocument, plan, acquisition, parquet);
        boolean full = truthy(first(o, "full"));
        if (full && (planDocument.path() == null || acquisitionDocument.path() == null || parquetDocument.path() == null || coverageDocument != null && coverageDocument.path() == null))
            throw failure("full production data-plane benchmark requires file-backed plan, acquisition, Parquet, and coverage inputs");
        String sourceRoot = text(firstTruthy(o, "acquisitionRoot", "acquisition_root"));
        if (sourceRoot.isEmpty() && acquisitionDocument.path() != null) sourceRoot = acquisitionDocument.path().getParent().toString();
        String physicalRoot = text(firstTruthy(o, "parquetRoot", "parquet_root"));
        if (physicalRoot.isEmpty() && parquetDocument.path() != null) physicalRoot = parquetDocument.path().getParent().toString();
        if (full && sourceRoot.isEmpty()) throw failure("full production data-plane benchmark requires an acquisition root");
        if (physicalRoot.isEmpty()) throw failure("Parquet root is required for a physical benchmark");
        if (!sourceRoot.isEmpty()) safeRoot(sourceRoot, "acquisition root"); safeRoot(physicalRoot, "Parquet root");
        if (!sourceRoot.isEmpty()) assertRootReference(sourceRoot, acquisition.get("root_reference"), "acquisition");
        assertRootReference(physicalRoot, parquet.get("output_root_reference"), "Parquet");
        int declaredCount = parquetRows.size(); double requested = jsNumber(firstTruthy(o, "samplePartitions", "sample_partitions")); if (!Double.isFinite(requested) || requested == 0) requested = 1;
        int requestedSample = Math.max(1, (int) Math.floor(requested));
        List<ObjectNode> selectedRows = full ? parquetRows : parquetRows.subList(0, Math.min(requestedSample, declaredCount));
        Set<String> selectedIdentities = new HashSet<>(); if (!full) for (ObjectNode row : selectedRows) selectedIdentities.add(text(row.get("identity")));
        Map<String, ObjectNode> planByIdentity = new HashMap<>(); for (JsonNode series : array(plan.get("series"))) planByIdentity.put(identity(series), (ObjectNode) series);
        Map<String, ObjectNode> sourceCaptureByIdentity = new HashMap<>(); for (JsonNode capture : array(acquisition.get("captures"))) sourceCaptureByIdentity.put(identity(capture), (ObjectNode) capture);
        ArrayNode sourceDiagnostics = array();
        int chunkBytes = intDefault(firstTruthy(o, "chunkBytes", "chunk_bytes"), 1_024 * 1_024);
        if (!sourceRoot.isEmpty()) for (JsonNode rawCapture : array(acquisition.get("captures"))) {
            ObjectNode capture = (ObjectNode) rawCapture;
            String captureIdentity = identity(capture);
            if (nullish(capture.get("partition")) || capture.path("unavailable").asBoolean(false) || !full && !selectedIdentities.contains(captureIdentity)) continue;
            Path sourcePath = safePath(sourceRoot, text(capture.path("partition").get("path")), "acquisition partition", "staging");
            SemanticAccumulator accumulator = new SemanticAccumulator(planByIdentity.get(captureIdentity), capture);
            long[] line = {0};
            StrategyPerformanceV5.StreamHashResult source = StrategyPerformanceV5.streamHashV5ProductionFile(sourcePath, chunkBytes, true, true, 16 * 1024 * 1024, 8L * 1024 * 1024 * 1024,
                    row -> validateJsonlRow(accumulator, row, ++line[0], sourcePath));
            ObjectNode semanticSource = finishSemantic(accumulator, sourcePath);
            JsonNode partition = capture.get("partition");
            if (!source.sha256().equals(text(partition.get("sha256"))) || source.bytes() != (long) jsNumber(partition.get("bytes")))
                throw failure("acquisition partition bytes/hash are invalid: " + text(partition.get("path")));
            if (!java.util.Objects.equals(source.lineCount(), (long) jsNumber(partition.get("row_count"))))
                throw failure("acquisition partition row count is invalid: " + text(partition.get("path")));
            JsonNode expectedCoverage = capture.path("coverage");
            compareSemanticBound(expectedCoverage.get("min_event_time"), semanticSource.get("min_event_time"), "acquisition partition minimum event bound is invalid: " + text(partition.get("path")));
            compareSemanticBound(expectedCoverage.get("max_event_time"), semanticSource.get("max_event_time"), "acquisition partition maximum event bound is invalid: " + text(partition.get("path")));
            compareSemanticBound(expectedCoverage.get("min_availability_time"), semanticSource.get("min_availability_time"), "acquisition partition minimum availability bound is invalid: " + text(partition.get("path")));
            compareSemanticBound(expectedCoverage.get("max_availability_time"), semanticSource.get("max_availability_time"), "acquisition partition maximum availability bound is invalid: " + text(partition.get("path")));
            JsonNode exported = findByIdentity(array(coverage == null ? null : coverage.get("series")), captureIdentity);
            if (exported != null && exported.path("complete").asBoolean(false)) {
                double exportedRows = jsNumber(firstNonNull(exported, "observed_rows", "expected_rows"));
                if (!Double.isFinite(exportedRows) || exportedRows != Math.rint(exportedRows) || exportedRows != source.lineCount())
                    throw failure("acquisition rows differ from authoritative coverage: " + text(partition.get("path")));
                for (String[] field : List.of(new String[]{"observed_min_event_time", "min_event_time"}, new String[]{"observed_max_event_time", "max_event_time"}, new String[]{"observed_min_availability_time", "min_availability_time"}, new String[]{"observed_max_availability_time", "max_availability_time"}))
                    compareSemanticBound(exported.get(field[0]), semanticSource.get(field[1]), "acquisition " + field[0] + " differs from authoritative coverage: " + text(partition.get("path")));
            }
            ObjectNode diagnostic = object().put("identity", captureIdentity).put("path", text(partition.get("path"))).put("bytes", source.bytes()).put("rows", source.lineCount()).put("chunks", source.chunks()).put("max_line_bytes_observed", source.maxLineBytesObserved());
            diagnostic.set("semantic", semanticSource); sourceDiagnostics.add(diagnostic); acquisitionBytes += source.bytes(); acquisitionRows += source.lineCount(); acquisitionPartitions++; acquisitionChunks += source.chunks(); maxChunk = Math.max(maxChunk, source.maxChunkBytes()); maxRss = Math.max(maxRss, usedMemory());
        }
        ArrayNode scanned = array();
        try {
            Class.forName("org.duckdb.DuckDBDriver");
            Properties properties = new Properties();
            properties.setProperty("threads", "1");
            properties.setProperty("enable_external_access", "true");
            try (Connection connection = DriverManager.getConnection("jdbc:duckdb:", properties)) {
                for (ObjectNode row : selectedRows) {
                    JsonNode partition = row.get("partition"); Path path = safePath(physicalRoot, text(partition.get("path")), "Parquet partition", "parquet");
                    StrategyPerformanceV5.StreamHashResult stream = StrategyPerformanceV5.streamHashV5ProductionFile(path, chunkBytes, false, false, 16 * 1024 * 1024, 8L * 1024 * 1024 * 1024, null);
                    if (!stream.sha256().equals(text(partition.get("sha256"))) || stream.bytes() != (long) jsNumber(partition.get("bytes")))
                        throw failure("Parquet partition bytes/hash are invalid: " + text(partition.get("path")));
                    parquetChunks += stream.chunks(); maxChunk = Math.max(maxChunk, stream.maxChunkBytes()); maxRss = Math.max(maxRss, usedMemory());
                    ObjectNode series = planByIdentity.get(text(row.get("identity")));
                    ObjectNode reopened = reopenParquet(path, connection, series, (ObjectNode) partition, row.path("coverage") instanceof ObjectNode c ? c : null);
                    if (reopened.path("row_count").asLong() != (long) jsNumber(partition.get("row_count"))) throw failure("reopened Parquet row count differs from the manifest: " + text(partition.get("path")));
                    if (!text(reopened.get("schema_sha256")).equals(text(partition.get("schema_sha256")))) throw failure("reopened Parquet schema differs from the manifest: " + text(partition.get("path")));
                    JsonNode rowCoverage = row.path("coverage"); JsonNode minBound = firstTruthy(rowCoverage, "min_event_time", "first_event_time"); JsonNode maxBound = firstTruthy(rowCoverage, "max_event_time", "last_event_time");
                    compareSemanticBound(minBound, reopened.get("min_event_time"), "reopened Parquet minimum event bound differs from coverage: " + text(partition.get("path")));
                    compareSemanticBound(maxBound, reopened.get("max_event_time"), "reopened Parquet maximum event bound differs from coverage: " + text(partition.get("path")));
                    JsonNode exported = findByIdentity(array(coverage == null ? null : coverage.get("series")), text(row.get("identity")));
                    if (exported != null && exported.path("complete").asBoolean(false)) {
                        if (jsNumber(firstNonNull(exported, "observed_rows", "expected_rows")) != reopened.path("row_count").asLong()) throw failure("reopened Parquet row count differs from exported coverage: " + text(partition.get("path")));
                        for (String[] field : List.of(new String[]{"observed_min_event_time", "min_event_time"}, new String[]{"observed_max_event_time", "max_event_time"}, new String[]{"observed_min_availability_time", "min_availability_time"}, new String[]{"observed_max_availability_time", "max_availability_time"}))
                            compareSemanticBound(exported.get(field[0]), reopened.get(field[1]), "reopened Parquet " + field[0].replace("observed_", "").replace('_', ' ') + " differs from exported coverage: " + text(partition.get("path")));
                    }
                    JsonNode coverageCount = firstNonNull(rowCoverage, "observed_rows", "observed_events"); if (nullish(coverageCount)) coverageCount = partition.get("row_count");
                    ObjectNode scannedRow = object().put("identity", text(row.get("identity")));
                    for (String field : List.of("role", "asset", "instrument", "symbol", "interval")) if (row.has(field) && !row.get(field).isNull()) scannedRow.put(field, text(row.get(field))); else scannedRow.putNull(field);
                    JsonNode type = firstTruthy(row, "series_type", "series_role"); if (!truthy(type)) scannedRow.putNull("series_type"); else scannedRow.put("series_type", text(type));
                    scannedRow.put("path", text(partition.get("path"))).put("sha256", text(partition.get("sha256"))).put("schema_sha256", text(partition.get("schema_sha256"))).put("bytes", (long) jsNumber(partition.get("bytes"))).put("row_count", (long) jsNumber(partition.get("row_count"))).put("coverage_complete", rowCoverage.path("complete").asBoolean(false)).put("coverage_count", jsNumber(coverageCount));
                    if (nullish(minBound)) scannedRow.putNull("coverage_min_event_time"); else scannedRow.set("coverage_min_event_time", minBound.deepCopy());
                    if (nullish(maxBound)) scannedRow.putNull("coverage_max_event_time"); else scannedRow.set("coverage_max_event_time", maxBound.deepCopy());
                    scannedRow.set("observed_min_event_time", reopened.get("min_event_time")); scannedRow.set("observed_max_event_time", reopened.get("max_event_time"));
                    if (jsNumber(coverageCount) != reopened.path("row_count").asLong() || !rowCoverage.path("complete").asBoolean(false)) throw failure("reopened Parquet coverage metadata is incomplete: " + text(partition.get("path")));
                    scanned.add(scannedRow); scannedBytes += stream.bytes(); scannedRows += reopened.path("row_count").asLong();
                }
            }
        } catch (ClassNotFoundException error) { throw failure("Parquet benchmark requires pinned DuckDB JDBC: " + error.getMessage()); }
        catch (SQLException error) { throw failure("Parquet benchmark failed: " + error.getMessage()); }
        RequiredSeries required = requiredSeries(plan, acquisition, parquetRows); DeclaredCompleteness declared = declaredCompleteness(plan, acquisition, parquetRows, coverage);
        boolean sourceComplete = true; for (JsonNode row : required.rows()) { ObjectNode capture = sourceCaptureByIdentity.get(text(row.get("identity"))); sourceComplete &= capture != null && !nullish(capture.get("partition")) && capture.path("coverage").path("complete").asBoolean(false) && !capture.path("unavailable").asBoolean(false); }
        boolean coverageComplete = coverage != null && (Set.of("COMPLETE", "OBSERVED_COMPLETE", "READY").contains(text(coverage.get("status"))) || coverage.path("complete").asBoolean(false) || coverage.path("all_complete").asBoolean(false));
        boolean allDeclared = selectedRows.size() == declaredCount; boolean physicalComplete = allDeclared && scanned.size() == declaredCount && required.parquetComplete();
        boolean genericComplete = full && physicalComplete && required.acquisitionComplete() && sourceComplete;
        Topology topology = topology(plan, required);
        boolean canonicalCoverage = coverage != null && StrategyPerformanceV5.AUTHORITATIVE_COVERAGE_SCHEMA.equals(text(coverage.get("schema"))) && "OBSERVED_COMPLETE".equals(text(coverage.get("status"))) && isHash(coverage.get("acquisition_sha256")) && isHash(coverage.get("parquet_sha256")) && isHash(coverage.get("dataset_root_sha256"));
        boolean canonicalAvailability = declared.availableComplete(); boolean ready = genericComplete && topology.pass() && canonicalCoverage && canonicalAvailability;
        List<String> declaredAssets = sortedDistinct(array(plan.get("assets")), null); List<String> scannedAssets = sortedDistinct(scanned, "asset");
        List<String> semanticViolations = declared.missingAvailable().stream().map(value -> "AVAILABLE_DECLARED_CAPTURE_MISSING:" + value).toList();
        ArrayNode topologyViolations = array(); java.util.stream.Stream.concat(topology.violations().stream(), semanticViolations.stream()).sorted().forEach(topologyViolations::add);
        ObjectNode canonical = object().put("universe", topology.universe()).put("genuine_window", topology.genuineWindow()).put("exact_required_topology", topology.exact()).put("required_series_count", topology.requiredCount()).put("required_series_target", topology.targetCount()).put("authoritative_coverage_observed_complete", canonicalCoverage).put("available_declared_capture_complete", canonicalAvailability).put("available_declared_count", declared.availableCount()).put("unavailable_proven_count", declared.unavailableCount()); canonical.set("violations", topologyViolations);
        ObjectNode sourceScan = object().put("partition_count", acquisitionPartitions).put("bytes", acquisitionBytes).put("rows", acquisitionRows).put("chunks", acquisitionChunks); sortArray(sourceDiagnostics, "path"); sourceScan.set("partitions", sourceDiagnostics);
        ObjectNode semanticBody = object().put("schema", StrategyPerformanceV5.DATA_PLANE_SEMANTIC_SCHEMA).put("version", 1).put("mode", full ? "FULL" : "SAMPLED").put("production_data", ready).put("generic_data_plane_complete", genericComplete); semanticBody.set("canonical_v5_contract", canonical);
        semanticBody.put("plan_sha256", text(plan.get("content_sha256"))).put("acquisition_sha256", text(acquisition.get("content_sha256"))).put("parquet_sha256", text(parquet.get("content_sha256"))); if (coverage == null) semanticBody.putNull("coverage_sha256"); else semanticBody.put("coverage_sha256", text(coverage.get("content_sha256")));
        semanticBody.put("parquet_manifest_schema", text(parquet.get("schema"))).put("declared_partition_count", declaredCount).put("scanned_partition_count", scanned.size()).put("declared_asset_count", declaredAssets.size()).put("scanned_asset_count", scannedAssets.size()); semanticBody.set("declared_assets", strings(declaredAssets)); semanticBody.set("scanned_assets", strings(scannedAssets));
        semanticBody.put("declared_bytes", sumPartition(parquetRows, "bytes")).put("scanned_bytes", scannedBytes).put("declared_rows", sumPartition(parquetRows, "row_count")).put("scanned_rows", scannedRows); semanticBody.set("acquisition_source_scan", sourceScan);
        semanticBody.put("required_series_count", required.count()).put("required_series_present_count", countTrue(required.rows(), "parquet_present")); semanticBody.set("required_series", requiredObject(required)); semanticBody.put("all_declared_partitions_scanned", allDeclared).put("source_complete", sourceComplete).put("coverage_complete", coverageComplete).put("available_declared_complete", canonicalAvailability); semanticBody.set("available_declared_missing", strings(declared.missingAvailable())); sortArray(scanned, "path"); semanticBody.set("partitions", scanned);
        ObjectNode semantic = semanticBody.deepCopy().put("semantic_sha256", hash(semanticBody));
        double elapsedMs = (System.nanoTime() - started) / 1_000_000D; maxRss = Math.max(maxRss, usedMemory()); long totalBytes = scannedBytes + acquisitionBytes;
        ObjectNode runtime = object().put("schema", StrategyPerformanceV5.DATA_PLANE_RUNTIME_SCHEMA).put("version", 1).put("wall_time_ms", round(elapsedMs, 3)); if (elapsedMs > 0) runtime.put("throughput_bytes_per_second", round(totalBytes / (elapsedMs / 1_000D), 3)); else runtime.putNull("throughput_bytes_per_second"); runtime.put("total_scanned_bytes", totalBytes).put("parquet_scanned_bytes", scannedBytes).put("parquet_scanned_rows", scannedRows).put("parquet_scanned_partitions", scanned.size()).put("parquet_stream_chunks", parquetChunks).put("acquisition_source_bytes", acquisitionBytes).put("acquisition_source_rows", acquisitionRows).put("acquisition_source_partitions", acquisitionPartitions).put("acquisition_source_chunks", acquisitionChunks).put("max_rss_bytes", maxRss).put("rss_start_bytes", rssStart).put("rss_end_bytes", usedMemory()).put("hash_chunk_bytes", chunkBytes).put("max_hash_chunk_bytes_observed", maxChunk).put("max_jsonl_line_bytes", 16 * 1024 * 1024).put("max_partition_bytes", 8L * 1024 * 1024 * 1024).put("bounded_memory_assertion", maxChunk <= chunkBytes).put("bounded_memory_scope", "STREAM_HASH_AND_JSONL_LINE_BUFFER_ONLY_DUCKDB_RSS_REPORTED_NOT_CLAIMED_BOUNDED").put("stream_hashing", true);
        String status = ready ? "DATA_PLANE_VERIFIED_FULL" : full && genericComplete && !canonicalCoverage ? "BLOCKED_REQUIRES_AUTHORITATIVE_COVERAGE" : full && genericComplete && !canonicalAvailability ? "BLOCKED_REQUIRES_AVAILABLE_DECLARED_CAPTURE_SET" : full && genericComplete ? "BLOCKED_V5_CANONICAL_PLAN_CONTRACT" : full ? "BLOCKED_INCOMPLETE_OR_UNVERIFIED_DATA_PLANE" : "NON_PRODUCTION_SAMPLED_SCAN";
        ObjectNode inputs = object(); inputs.set("plan", inputDescriptor(planDocument, plan)); inputs.set("acquisition", inputDescriptor(acquisitionDocument, acquisition)); inputs.set("parquet", inputDescriptor(parquetDocument, parquet)); if (coverageDocument == null) inputs.putNull("coverage"); else inputs.set("coverage", inputDescriptor(coverageDocument, coverage));
        ObjectNode readiness = object(); readiness.set("data_plane", object().put("ready", ready).put("generic_complete", genericComplete).put("status", status)); readiness.set("statistical", object().put("ready", false).put("status", "BLOCKED_REQUIRES_AUTHORITATIVE_WFO_AND_NULL_BENCHMARK")); readiness.set("physical_null", object().put("ready", false).put("status", "BLOCKED_REQUIRES_AUTHORITATIVE_PHYSICAL_NULL_BENCHMARK")); readiness.set("global", object().put("ready", false).put("status", "BLOCKED_REQUIRES_AUTHORITATIVE_WFO_AND_NULL_BENCHMARK"));
        ObjectNode output = object().put("schema", StrategyPerformanceV5.DATA_PLANE_SCHEMA).put("version", 1).put("semantic_sha256", text(semantic.get("semantic_sha256"))); output.set("inputs", inputs); output.set("semantic", semantic); output.set("runtime", runtime); output.set("readiness", readiness); return output;
    }

    private static Long epochMillis(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode() || value.isTextual() && value.textValue().isEmpty()) return null;
        if (value.isNumber()) {
            double number = value.doubleValue();
            if (!Double.isFinite(number)) return null;
            return (long) (Math.abs(number) < 100_000_000_000D ? number * 1_000D : number);
        }
        if (value.isObject() && value.has("micros")) {
            double micros = jsNumber(value.get("micros"));
            return Double.isFinite(micros) ? (long) (micros / 1_000D) : null;
        }
        try { return Instant.parse(value.asText()).toEpochMilli(); }
        catch (RuntimeException ignored) { return null; }
    }

    private static double jsNumber(JsonNode value) {
        if (value == null || value.isMissingNode()) return Double.NaN;
        if (value.isNull()) return 0;
        if (value.isNumber()) return value.doubleValue();
        if (value.isBoolean()) return value.booleanValue() ? 1 : 0;
        if (value.isTextual()) {
            String raw = value.textValue().trim();
            if (raw.isEmpty()) return 0;
            try { return Double.parseDouble(raw); } catch (NumberFormatException ignored) { return Double.NaN; }
        }
        return Double.NaN;
    }

    private record ProductionDocument(ObjectNode value, Path path, String byteSha256, Long byteLength) {}
    private record ParquetValidation(ObjectNode parquet, List<ObjectNode> rows) {}

    private static ObjectNode object() { return MAPPER.createObjectNode(); }
    private static ArrayNode array() { return MAPPER.createArrayNode(); }
    private static ArrayNode array(JsonNode value) { return value instanceof ArrayNode rows ? rows : array(); }
    private static boolean nullish(JsonNode value) { return value == null || value.isNull() || value.isMissingNode(); }
    private static boolean truthy(JsonNode value) {
        if (nullish(value)) return false;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.doubleValue() != 0 && !Double.isNaN(value.doubleValue());
        if (value.isTextual()) return !value.textValue().isEmpty();
        return true;
    }
    private static String text(JsonNode value) { return nullish(value) ? "" : value.asText(); }
    private static JsonNode first(JsonNode value, String... names) {
        if (value == null || !value.isObject()) return null;
        for (String name : names) if (value.has(name)) return value.get(name);
        return null;
    }
    private static JsonNode firstNonNull(JsonNode value, String... names) {
        if (value == null || !value.isObject()) return null;
        for (String name : names) {
            JsonNode candidate = value.get(name);
            if (!nullish(candidate)) return candidate;
        }
        return null;
    }
    private static String hash(JsonNode value) { return StrategyPerformanceV5.hashV5Performance(value); }
    private static String hash(String value) { return StrategyPerformanceV5.hashV5Performance(value); }
    private static String hash(byte[] value) { return StrategyPerformanceV5.hashV5Performance(value); }
    private static String ownHash(ObjectNode value) { return StrategyPerformanceV5.ownHash(value); }
    private static boolean isHash(JsonNode value) { return HASH.matcher(text(value)).matches(); }
    private static boolean same(JsonNode left, JsonNode right) {
        return JsonHashes.canonicalString(left == null ? NullNode.instance : left)
                .equals(JsonHashes.canonicalString(right == null ? NullNode.instance : right));
    }
    private static IllegalArgumentException failure(String message) { return new IllegalArgumentException(message); }

    private static String identity(JsonNode value) {
        return List.of("asset", "instrument", "symbol", "interval").stream()
                .map(name -> text(value == null ? null : value.get(name)).toLowerCase(Locale.ROOT))
                .reduce((left, right) -> left + "|" + right).orElse("")
                + "|" + text(firstTruthy(value, "series_type", "series_role")).toLowerCase(Locale.ROOT);
    }

    private static String datasetIdentity(JsonNode value) {
        return text(value.get("asset")).toLowerCase(Locale.ROOT) + "|"
                + text(value.get("instrument")).toUpperCase(Locale.ROOT) + "|"
                + text(value.get("symbol")).toUpperCase(Locale.ROOT) + "|"
                + text(value.get("interval")) + "|"
                + text(firstTruthy(value, "series_type", "series_role")).toLowerCase(Locale.ROOT);
    }

    private static Path canonicalAbsolute(Path input) {
        Path absolute = input.toAbsolutePath().normalize();
        // macOS exposes /tmp and /var through stable operating-system aliases.
        // Canonicalize only those top-level aliases; all lower components are
        // still inspected without following links.
        for (String aliasName : List.of("/tmp", "/var")) {
            Path alias = Path.of(aliasName);
            if ((absolute.equals(alias) || absolute.startsWith(alias)) && Files.isSymbolicLink(alias)) {
                try { absolute = alias.toRealPath().resolve(alias.relativize(absolute)).normalize(); }
                catch (IOException ignored) { /* component inspection reports the physical error */ }
                break;
            }
        }
        if (!absolute.isAbsolute()) throw failure("path is not physical");
        return absolute;
    }

    private static Path inspectPath(Path input, String label, boolean directory, boolean rejectHardlink) {
        Path absolute = canonicalAbsolute(input);
        if (absolute.getNameCount() == 0) throw failure(label + " is not a physical path");
        Path cursor = absolute.getRoot();
        for (Path component : absolute) {
            cursor = cursor.resolve(component);
            try {
                if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS))
                    throw failure(label + " is missing: " + absolute);
                if (Files.isSymbolicLink(cursor)) throw failure(label + " contains a symbolic-link component: " + cursor);
                if (!cursor.equals(absolute) && !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))
                    throw failure(label + " has a non-directory parent: " + cursor);
            } catch (SecurityException error) { throw failure(label + " is missing: " + absolute + " (" + error.getMessage() + ")"); }
        }
        if (directory ? !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS) : !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS))
            throw failure(label + " is not a regular " + (directory ? "directory" : "file") + ": " + absolute);
        if (rejectHardlink && !directory) {
            try {
                Object links = Files.getAttribute(absolute, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
                if (links instanceof Number number && number.longValue() != 1)
                    throw failure(label + " is a multi-link file (nlink=" + number.longValue() + "): " + absolute);
            } catch (UnsupportedOperationException | IOException ignored) { /* non-Unix file stores have no link count */ }
        }
        try {
            Path real = absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.equals(absolute)) throw failure(label + " realpath escapes the lstat path: " + absolute);
        } catch (IOException error) { throw failure(label + " cannot be realpath-verified: " + absolute + " (" + error.getMessage() + ")"); }
        return absolute;
    }

    private static Path safeRoot(String root, String label) {
        if (root == null || root.isEmpty()) throw failure(label + " is required");
        return inspectPath(Path.of(root), label, true, false);
    }

    private static Path safeFile(String path, String label) {
        return inspectPath(Path.of(path), label, false, true);
    }

    private static Path safePath(String root, String reference, String label, String storagePrefix) {
        if (reference == null || reference.isEmpty() || Path.of(reference).isAbsolute() || reference.indexOf('\\') >= 0)
            throw failure(label + " must be a relative path inside its root");
        Path base = safeRoot(root, label + " root");
        Path target = base.resolve(reference).normalize();
        if (target.equals(base) || !target.startsWith(base)) throw failure(label + " escapes its root");
        try { return inspectPath(target, label, false, true); }
        catch (IllegalArgumentException original) {
            String prefix = storagePrefix == null ? "" : storagePrefix.replaceAll("^/+|/+$", "");
            if (!prefix.isEmpty() && reference.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT) + "/")) {
                Path shortened = base.resolve(reference.substring(prefix.length() + 1)).normalize();
                if (!shortened.equals(base) && shortened.startsWith(base)) return inspectPath(shortened, label, false, true);
            }
            throw original;
        }
    }

    private static void assertRootReference(String root, JsonNode reference, String label) {
        if (reference == null || !reference.isTextual() || reference.textValue().isEmpty())
            throw failure(label + " manifest root reference is missing");
        Path expected;
        Path actual;
        try { expected = safeRoot(reference.textValue(), label + " manifest root reference"); actual = safeRoot(root, label + " supplied root"); }
        catch (IllegalArgumentException error) { throw failure(label + " root reference cannot be reopened: " + error.getMessage()); }
        if (!expected.equals(actual)) throw failure(label + " root does not match its manifest root reference: expected " + expected + ", supplied " + actual);
    }

    private static ProductionDocument readDocument(JsonNode input, String label, Set<String> schemas) {
        ObjectNode value;
        Path path = null;
        String byteSha256 = null;
        Long byteLength = null;
        if (input != null && input.isTextual()) {
            path = safeFile(input.textValue(), label);
            try {
                byte[] bytes = Files.readAllBytes(path);
                byteSha256 = hash(bytes);
                byteLength = (long) bytes.length;
                JsonNode parsed = MAPPER.readTree(bytes);
                if (!(parsed instanceof ObjectNode object)) throw failure(label + " JSON is invalid: root must be an object");
                value = object;
            } catch (IOException error) { throw failure(label + " JSON is invalid: " + error.getMessage()); }
        } else if (input instanceof ObjectNode object) value = object.deepCopy();
        else throw failure(label + " is required");
        if (!schemas.contains(text(value.get("schema")))) throw failure(label + " has unsupported schema " + (text(value.get("schema")).isEmpty() ? "?" : text(value.get("schema"))));
        try { SCHEMAS.validateKnownContractSchema(value); }
        catch (RuntimeException error) { throw failure(label + " schema validation failed: " + error.getMessage()); }
        if (!isHash(value.get("content_sha256")) || !text(value.get("content_sha256")).equals(ownHash(value)))
            throw failure(label + " content hash/schema is invalid");
        return new ProductionDocument(value, path, byteSha256, byteLength);
    }

    private static ObjectNode validatePlan(ProductionDocument document) {
        ObjectNode plan = document.value();
        if (!"PLAN_ONLY".equals(text(plan.get("status"))) || jsNumber(plan.path("window").get("years")) != 5)
            throw failure("frozen v5 plan must be PLAN_ONLY with a five-year window");
        ArrayNode assets = array(plan.get("assets"));
        Set<String> assetIds = new HashSet<>();
        for (JsonNode asset : assets) assetIds.add(text(asset));
        if (assets.isEmpty() || assetIds.size() != assets.size()) throw failure("frozen v5 plan assets are invalid");
        ArrayNode seriesRows = array(plan.get("series"));
        if (seriesRows.isEmpty()) throw failure("frozen v5 plan has no declared series");
        if (!plan.has("root_reference") || !plan.get("root_reference").isTextual() || text(plan.get("root_reference")).isEmpty())
            throw failure("frozen v5 plan is missing its root reference");
        Set<String> identities = new HashSet<>();
        for (JsonNode series : seriesRows) {
            String identity = identity(series);
            if (!identities.add(identity)) throw failure("frozen v5 plan contains duplicate series: " + identity);
            for (String field : List.of("asset", "venue", "instrument", "symbol", "interval", "series_type", "series_role", "start_at", "end_at", "availability_cutoff_at"))
                if (nullish(series.get(field)) || text(series.get(field)).isEmpty())
                    throw failure("frozen v5 plan series is missing " + field + ": " + identity);
        }
        return plan;
    }

    private static ObjectNode validateAcquisition(ProductionDocument document, ObjectNode plan) {
        ObjectNode acquisition = document.value();
        if (!text(acquisition.get("plan_sha256")).equals(text(plan.get("content_sha256"))))
            throw failure("acquisition manifest is bound to a different frozen plan");
        ArrayNode captures = array(acquisition.get("captures"));
        if (captures.isEmpty()) throw failure("acquisition manifest has no captures");
        if (!acquisition.has("root_reference") || !acquisition.get("root_reference").isTextual() || text(acquisition.get("root_reference")).isEmpty())
            throw failure("acquisition manifest is missing its root reference");
        Map<String, JsonNode> planByIdentity = new LinkedHashMap<>();
        for (JsonNode series : array(plan.get("series"))) planByIdentity.put(identity(series), series);
        Set<String> identities = new HashSet<>();
        for (JsonNode capture : captures) {
            String identity = identity(capture);
            JsonNode series = planByIdentity.get(identity);
            if (series == null) throw failure("acquisition capture is not declared by the frozen plan: " + identity);
            if (!identities.add(identity)) throw failure("acquisition manifest contains duplicate series: " + identity);
            String expectedSeriesHash = hash(series);
            if (!isHash(capture.get("series_sha256")) || !text(capture.get("series_sha256")).equals(expectedSeriesHash))
                throw failure("acquisition capture series binding is stale: " + identity + " expected=" + expectedSeriesHash + " actual=" + text(capture.get("series_sha256")));
            series.fields().forEachRemaining(entry -> {
                if (!Set.of("series_sha256", "trade_scope").contains(entry.getKey())
                        && (!capture.has(entry.getKey()) || !same(capture.get(entry.getKey()), entry.getValue())))
                    throw failure("acquisition capture does not match frozen plan field " + entry.getKey() + ": " + identity);
            });
            JsonNode partition = capture.get("partition");
            if (!nullish(partition)) {
                if (!"JSONL".equals(text(partition.get("format")).toUpperCase(Locale.ROOT))
                        || !partition.has("authoritative") || partition.get("authoritative").asBoolean(true))
                    throw failure("acquisition capture is not an explicit JSONL staging partition: " + identity);
                if (!isHash(partition.get("sha256")) || !nonNegativeInteger(partition.get("bytes"), false)
                        || jsNumber(partition.get("bytes")) < 1 || !nonNegativeInteger(partition.get("row_count"), true))
                    throw failure("acquisition capture partition metadata is invalid: " + identity);
                JsonNode coverage = capture.get("coverage");
                if (nullish(coverage) || coverage.path("complete").asBoolean(false) != true
                        && capture.path("required").asBoolean(false) && !"STAGING_PARTIAL".equals(text(acquisition.get("status"))))
                    throw failure("acquisition capture lacks complete physical coverage: " + identity);
                JsonNode observed = firstNonNull(coverage, "observed_rows", "observed_events");
                if (nullish(observed)) observed = partition.get("row_count");
                if (jsNumber(observed) != jsNumber(partition.get("row_count")))
                    throw failure("acquisition capture coverage count is not bound to its partition: " + identity);
            } else if (!capture.path("unavailable").asBoolean(false)
                    && !(capture.has("required") && capture.get("required").isBoolean() && !capture.get("required").booleanValue())) {
                throw failure("required acquisition capture has no partition: " + identity);
            }
            if (capture.path("unavailable").asBoolean(false)
                    && (capture.path("required").asBoolean(false) || series.path("required").asBoolean(false)))
                throw failure("required acquisition capture is marked unavailable: " + identity);
        }
        for (String identity : planByIdentity.keySet())
            if (!identities.contains(identity)) throw failure("acquisition manifest is missing a declared plan capture: " + identity);
        return acquisition;
    }

    private static List<ObjectNode> normalizeParquetPartitions(ObjectNode parquet) {
        List<ObjectNode> rows = new ArrayList<>();
        if (StrategyPerformanceV5.PARQUET_CONVERSION_SCHEMA.equals(text(parquet.get("schema")))) {
            ArrayNode captures = array(parquet.get("captures"));
            if (captures.isEmpty()) throw failure("Parquet conversion manifest has no captures");
            for (JsonNode capture : captures) {
                ObjectNode row = ((ObjectNode) capture).deepCopy();
                row.set("partition", capture.path("partition").deepCopy());
                row.put("identity", identity(capture));
                rows.add(row);
            }
        } else if (StrategyPerformanceV5.SEPARATED_ARTIFACT_SCHEMA.equals(text(parquet.get("schema")))) {
            JsonNode artifacts = parquet.get("artifacts");
            if (artifacts == null || !artifacts.isObject()) throw failure("separated Parquet manifest has no artifact roles");
            List<String> roles = new ArrayList<>(); artifacts.fieldNames().forEachRemaining(roles::add); roles.sort(String::compareTo);
            for (String role : roles) {
                ObjectNode row = object().put("role", role);
                if (artifacts.get(role) instanceof ObjectNode artifact) row.setAll(artifact.deepCopy());
                row.set("partition", artifacts.get(role).deepCopy());
                row.put("identity", "artifact|" + role.toLowerCase(Locale.ROOT));
                rows.add(row);
            }
        } else if (parquet.get("partitions") instanceof ArrayNode partitions) {
            for (JsonNode partition : partitions) {
                ObjectNode row = ((ObjectNode) partition).deepCopy();
                row.set("partition", partition.deepCopy()); row.put("identity", identity(partition)); rows.add(row);
            }
        } else throw failure("Parquet manifest has no declared captures, artifacts, or partitions");
        Set<String> paths = new HashSet<>();
        for (ObjectNode row : rows) {
            JsonNode partition = row.get("partition");
            String path = text(partition == null ? null : partition.get("path"));
            if (partition == null || !"PARQUET".equals(text(partition.get("format")).toUpperCase(Locale.ROOT))
                    || !"AUTHORITATIVE".equals(text(partition.get("storage_role"))) || !partition.path("authoritative").asBoolean(false))
                throw failure("declared Parquet partition is not authoritative: " + (path.isEmpty() ? "?" : path));
            if (path.isEmpty() || !paths.add(path)) throw failure("declared Parquet partition path is missing or duplicated: " + (path.isEmpty() ? "?" : path));
            if (!isHash(partition.get("sha256")) || !isHash(partition.get("schema_sha256"))
                    || !nonNegativeInteger(partition.get("bytes"), false) || jsNumber(partition.get("bytes")) < 1
                    || !nonNegativeInteger(partition.get("row_count"), true))
                throw failure("declared Parquet partition metadata is invalid: " + path);
        }
        rows.sort(Comparator.comparing(row -> text(row.path("partition").get("path"))));
        return List.copyOf(rows);
    }

    private static String recomputeParquetDatasetRoot(ObjectNode parquet, List<ObjectNode> rows) {
        if (StrategyPerformanceV5.PARQUET_CONVERSION_SCHEMA.equals(text(parquet.get("schema")))) {
            ArrayNode captures = array();
            rows.stream().sorted(Comparator.comparing(StrategyPerformanceV5Benchmark::datasetIdentity)).forEach(row -> {
                ObjectNode value = object().put("identity", datasetIdentity(row));
                value.set("partition", row.get("partition").deepCopy()); captures.add(value);
            });
            ObjectNode root = object();
            root.set("source_manifest_sha256", parquet.get("source_manifest_sha256"));
            root.set("plan_sha256", parquet.get("plan_sha256")); root.set("captures", captures);
            return hash(root);
        }
        if (StrategyPerformanceV5.SEPARATED_ARTIFACT_SCHEMA.equals(text(parquet.get("schema")))) {
            ObjectNode root = object();
            for (String field : List.of("plan_sha256", "predictor_registry_sha256", "source_manifest_sha256", "source_manifest_reference", "source_dataset_root_sha256", "transformation_code_sha256", "label_code_sha256", "execution_code_sha256", "config_sha256", "precommit_sha256", "envelope_sha256", "artifacts"))
                root.set(field, parquet.get(field) == null ? NullNode.instance : parquet.get(field).deepCopy());
            return hash(root);
        }
        return null;
    }

    private static ParquetValidation validateParquet(ProductionDocument document, ObjectNode plan, ObjectNode acquisition) {
        ObjectNode parquet = document.value();
        if (!text(parquet.get("plan_sha256")).equals(text(plan.get("content_sha256"))))
            throw failure("Parquet manifest is bound to a different frozen plan");
        if (!"AUTHORITATIVE_PARQUET".equals(text(parquet.get("status"))) || !"PARQUET".equals(text(parquet.get("format")))
                || !"AUTHORITATIVE".equals(text(parquet.get("storage_role"))) || !parquet.path("authoritative").asBoolean(false))
            throw failure("Parquet manifest is not authoritative output");
        if (StrategyPerformanceV5.PARQUET_CONVERSION_SCHEMA.equals(text(parquet.get("schema"))) && jsNumber(parquet.get("threads")) != 1)
            throw failure("Parquet conversion manifest must bind the single-threaded conversion");
        if (!isHash(parquet.get("dataset_root_sha256"))) throw failure("Parquet manifest is missing its dataset root hash");
        if (!parquet.has("output_root_reference") || !parquet.get("output_root_reference").isTextual() || text(parquet.get("output_root_reference")).isEmpty())
            throw failure("Parquet manifest is missing its output root reference");
        if (acquisition != null) {
            Set<String> accepted = new HashSet<>();
            JsonNode acquisitionHash = acquisition.get("content_sha256");
            JsonNode sourceManifestHash = acquisition.get("source_manifest_sha256");
            if (!nullish(acquisitionHash)) accepted.add(text(acquisitionHash));
            if (!nullish(sourceManifestHash)) accepted.add(text(sourceManifestHash));
            if (!accepted.contains(text(parquet.get("source_manifest_sha256"))))
                throw failure("Parquet manifest is not bound to the supplied acquisition manifest");
        }
        List<ObjectNode> rows = normalizeParquetPartitions(parquet);
        Map<String, JsonNode> planByIdentity = new LinkedHashMap<>(); for (JsonNode series : array(plan.get("series"))) planByIdentity.put(identity(series), series);
        Map<String, JsonNode> acquisitionByIdentity = new LinkedHashMap<>(); for (JsonNode capture : array(acquisition == null ? null : acquisition.get("captures"))) acquisitionByIdentity.put(identity(capture), capture);
        Set<String> seen = new HashSet<>();
        for (ObjectNode row : rows) {
            String rowIdentity = text(row.get("identity"));
            if (rowIdentity.startsWith("artifact|")) continue;
            JsonNode series = planByIdentity.get(rowIdentity);
            if (series == null) throw failure("Parquet capture is not declared by the frozen plan: " + rowIdentity);
            if (!seen.add(rowIdentity)) throw failure("Parquet manifest contains duplicate series: " + rowIdentity);
            if (!isHash(row.get("series_sha256")) || !text(row.get("series_sha256")).equals(hash(series)))
                throw failure("Parquet capture series binding is stale: " + rowIdentity);
            series.fields().forEachRemaining(entry -> {
                if (!Set.of("series_sha256", "trade_scope").contains(entry.getKey())
                        && (!row.has(entry.getKey()) || !same(row.get(entry.getKey()), entry.getValue())))
                    throw failure("Parquet capture does not match frozen plan field " + entry.getKey() + ": " + rowIdentity);
            });
            JsonNode coverage = row.get("coverage"); JsonNode partition = row.get("partition");
            if (coverage == null || !coverage.path("complete").asBoolean(false)) throw failure("Parquet partition lacks complete coverage: " + text(partition.get("path")));
            JsonNode expectedCount = firstNonNull(coverage, "observed_rows", "observed_events"); if (nullish(expectedCount)) expectedCount = partition.get("row_count");
            if (jsNumber(expectedCount) != jsNumber(partition.get("row_count"))) throw failure("Parquet coverage count is not bound to its partition: " + text(partition.get("path")));
            JsonNode firstBound = firstNonNull(coverage, "min_event_time", "first_event_time"); JsonNode lastBound = firstNonNull(coverage, "max_event_time", "last_event_time");
            if (epochMillis(firstBound) == null || epochMillis(lastBound) == null) throw failure("Parquet coverage bounds are missing: " + text(partition.get("path")));
            JsonNode sourceCapture = acquisitionByIdentity.get(rowIdentity); String source = text(sourceCapture == null ? null : sourceCapture.path("partition").get("sha256"));
            if (!source.isEmpty() && !source.equals(text(partition.get("source_jsonl_sha256"))))
                throw failure("Parquet partition is not linked to the matching acquisition bytes: " + text(partition.get("path")));
        }
        for (Map.Entry<String, JsonNode> entry : acquisitionByIdentity.entrySet()) {
            JsonNode capture = entry.getValue();
            if (capture.path("unavailable").asBoolean(false)) continue;
            if (!nullish(capture.get("partition")) && (!capture.has("required") || capture.path("required").asBoolean(true)) && !seen.contains(entry.getKey()))
                throw failure("Parquet manifest is missing a required acquisition capture: " + entry.getKey());
        }
        String root = recomputeParquetDatasetRoot(parquet, rows);
        if (root != null && !root.equals(text(parquet.get("dataset_root_sha256")))) throw failure("Parquet manifest dataset root is invalid");
        return new ParquetValidation(parquet, rows);
    }

    private static boolean nonNegativeInteger(JsonNode value, boolean zeroAllowed) {
        double number = jsNumber(value);
        return Double.isFinite(number) && number == Math.rint(number) && (zeroAllowed ? number >= 0 : number >= 0);
    }

    private static final class SemanticAccumulator {
        final ObjectNode series;
        final ObjectNode capture;
        long rows;
        Long minEvent;
        Long maxEvent;
        Long minAvailability;
        Long maxAvailability;
        Long previousEvent;
        Long previousFundingSlot;
        long duplicateEvents;
        long duplicateFundingSlots;
        final Set<String> eventIds = new HashSet<>();
        final Set<Long> fundingSlots = new HashSet<>();
        long cadenceViolations;
        final List<Long> cadenceViolationEvents = new ArrayList<>();

        SemanticAccumulator(ObjectNode series, ObjectNode capture) { this.series = series; this.capture = capture; }
    }

    private static ObjectNode irregularBar(ObjectNode capture, long event) {
        for (JsonNode entry : array(capture == null ? null : capture.path("coverage").get("irregular_bars"))) {
            Long candidate = epochMillis(entry.get("event_time"));
            if (candidate != null && candidate == event && entry instanceof ObjectNode object) return object;
        }
        return null;
    }

    private static Set<Long> irregularEventSet(ObjectNode capture) {
        Set<Long> result = new HashSet<>();
        for (JsonNode entry : array(capture == null ? null : capture.path("coverage").get("irregular_bars"))) {
            Long event = epochMillis(entry.get("event_time"));
            if ("EARLY_CLOSE_OUTAGE".equals(text(entry.get("classification"))) && event != null) result.add(event);
        }
        return result;
    }

    private static String rowIdentity(ObjectNode row, ObjectNode series) {
        ObjectNode value = row.deepCopy();
        if (!truthy(firstTruthy(value, "series_type", "series_role"))) value.set("series_type", firstTruthy(series, "series_type", "series_role"));
        if (!truthy(firstTruthy(value, "interval", "timeframe"))) value.set("interval", series.get("interval"));
        else if (!value.has("interval") && value.has("timeframe")) value.set("interval", value.get("timeframe"));
        return identity(value);
    }

    private static boolean finite(JsonNode value) { return Double.isFinite(jsNumber(value)); }
    private static String iso(Long epoch) { return epoch == null ? null : ISO_MILLIS.format(Instant.ofEpochMilli(epoch)); }

    private static void fundingCoverageCheck(SemanticAccumulator accumulator, Long rows, Long minEvent, Long maxEvent,
                                             String path, ObjectNode series, ObjectNode coverage) {
        long count = accumulator == null ? (rows == null ? 0 : rows) : accumulator.rows;
        Long observedMin = accumulator == null ? minEvent : accumulator.minEvent;
        Long observedMax = accumulator == null ? maxEvent : accumulator.maxEvent;
        boolean complete = coverage.path("complete").asBoolean(false);
        if (count == 0 && (complete || series.path("required").asBoolean(false)))
            throw failure("funding semantic coverage diagnostic: " + path + " has an empty event sequence");
        if (!complete) return;
        if (count < 2) throw failure("funding semantic coverage diagnostic: " + path + " has a truncated event sequence");
        if (!coverage.path("source_pagination_complete").asBoolean(false) || !coverage.path("boundaries_covered").asBoolean(false))
            throw failure("funding semantic coverage diagnostic: " + path + " lacks complete pagination/boundary proof");
        ArrayNode segments = array(coverage.get("cadence_segments"));
        if (segments.isEmpty()) throw failure("funding semantic coverage diagnostic: " + path + " lacks discovered cadence segments");
        double observed = jsNumber(firstNonNull(coverage, "observed_events", "observed_rows"));
        if (!Double.isFinite(observed) || observed != Math.rint(observed) || (long) observed != count)
            throw failure("funding semantic coverage diagnostic: " + path + " observed count is not bound to the rows");
        Long first = epochMillis(firstNonNull(coverage, "first_event_time", "min_event_time"));
        Long last = epochMillis(firstNonNull(coverage, "last_event_time", "max_event_time"));
        Long queryStart = epochMillis(coverage.get("query_start_at"));
        Long queryEnd = epochMillis(coverage.get("query_end_at"));
        if (first == null || last == null || !first.equals(observedMin) || !last.equals(observedMax))
            throw failure("funding semantic coverage diagnostic: " + path + " first/last event bounds differ from the reopened rows (declared " + first + "/" + last + ", observed " + observedMin + "/" + observedMax + ")");
        if (queryStart != null && observedMin < queryStart || queryEnd != null && observedMax > queryEnd)
            throw failure("funding semantic coverage diagnostic: " + path + " rows escape the query bounds");
        double rawTolerance = jsNumber(firstNonNull(coverage, "slot_tolerance_ms"));
        if (!Double.isFinite(rawTolerance)) rawTolerance = jsNumber(series.get("slot_tolerance_ms"));
        if (!Double.isFinite(rawTolerance)) rawTolerance = 60_000;
        long tolerance = (long) rawTolerance;
        long maxCadence = 0;
        for (JsonNode segment : segments) {
            double cadence = jsNumber(segment.get("cadence_ms"));
            if (Double.isFinite(cadence) && cadence > 0) maxCadence = Math.max(maxCadence, (long) cadence);
        }
        if (maxCadence == 0) throw failure("funding semantic coverage diagnostic: " + path + " has no positive discovered cadence");
        Long start = epochMillis(series.get("start_at")); Long end = epochMillis(series.get("end_at"));
        if (start == null || end == null || observedMin > start + maxCadence + tolerance || observedMax < end - maxCadence - tolerance)
            throw failure("funding semantic coverage diagnostic: " + path + " does not cover both frozen sequence boundaries");
        if (accumulator != null && accumulator.cadenceViolations > 0)
            throw failure("funding semantic cadence diagnostic: " + path + " has " + accumulator.cadenceViolations + " unexpected cadence gaps");
    }

    private static void validateJsonlRow(SemanticAccumulator accumulator, ObjectNode row, long lineNumber, Path path) {
        ObjectNode series = accumulator.series;
        String expectedIdentity = identity(series);
        if (!rowIdentity(row, series).equals(expectedIdentity)
                || !text(row.get("asset")).equalsIgnoreCase(text(series.get("asset")))
                || !text(row.get("instrument")).equalsIgnoreCase(text(series.get("instrument")))
                || !text(row.get("symbol")).equalsIgnoreCase(text(series.get("symbol")))
                || !text(row.get("venue")).equalsIgnoreCase(text(series.get("venue"))))
            throw failure("JSONL semantic identity diagnostic: " + path + ":" + lineNumber + " does not match " + expectedIdentity);
        String expectedRole = text(series.get("series_role")).toUpperCase(Locale.ROOT);
        if (!text(row.get("series_role")).toUpperCase(Locale.ROOT).equals(expectedRole))
            throw failure("JSONL semantic role diagnostic: " + path + ":" + lineNumber + " expected " + expectedRole);
        Long event = epochMillis(firstNonNull(row, "event_time", "raw_event_time"));
        if (event == null) throw failure("JSONL semantic event-time diagnostic: " + path + ":" + lineNumber + " is invalid");
        Long start = epochMillis(series.get("start_at")); Long end = epochMillis(series.get("end_at")); Long cutoff = epochMillis(series.get("availability_cutoff_at"));
        Long availability = epochMillis(firstNonNull(row, "availability_time", "settlement_mark_availability_time"));
        if (start == null || end == null || event < start || event > end)
            throw failure("JSONL semantic event bound diagnostic: " + path + ":" + lineNumber + " is outside the frozen series window");
        if (series.path("require_availability_time").asBoolean(false)
                && (availability == null || availability < event || cutoff != null && availability > cutoff))
            throw failure("JSONL semantic availability diagnostic: " + path + ":" + lineNumber + " is outside the PIT bound");
        if (accumulator.previousEvent != null && event < accumulator.previousEvent)
            throw failure("JSONL semantic ordering diagnostic: " + path + ":" + lineNumber + " is not event-time ordered");
        String seriesType = text(series.get("series_type"));
        double expectedStep = jsNumber(series.get("expected_step_ms"));
        if (accumulator.previousEvent != null && expectedStep > 0 && !"funding_events".equals(seriesType)
                && event - accumulator.previousEvent != (long) expectedStep) {
            accumulator.cadenceViolations++; accumulator.cadenceViolationEvents.add(event);
        }
        if (series.path("completed_bars_only").asBoolean(false) && expectedStep > 0 && !"funding_events".equals(seriesType)) {
            long expectedBoundary = event + (long) expectedStep;
            ObjectNode irregular = irregularBar(accumulator.capture, event);
            boolean earlyClose = availability == null || availability < expectedBoundary - 1_000;
            if (availability != null && (availability > expectedBoundary
                    || earlyClose && (irregular == null || !"EARLY_CLOSE_OUTAGE".equals(text(irregular.get("classification"))))))
                throw failure("JSONL completed-bar PIT diagnostic: " + path + ":" + lineNumber + " is available before bar close or has an unbound early-close exception");
            if (irregular != null) {
                Long irregularBoundary = epochMillis(irregular.get("expected_boundary_time"));
                Long irregularAvailability = epochMillis(irregular.get("availability_time"));
                if (irregularBoundary != null && irregularBoundary != expectedBoundary)
                    throw failure("JSONL completed-bar PIT diagnostic: " + path + ":" + lineNumber + " irregular boundary is inconsistent");
                if (irregularAvailability != null && !irregularAvailability.equals(availability))
                    throw failure("JSONL completed-bar PIT diagnostic: " + path + ":" + lineNumber + " irregular availability is inconsistent");
            }
        }
        if ("funding_events".equals(seriesType)) {
            String id = text(row.get("event_id"));
            if (id.isEmpty()) throw failure("JSONL funding semantic diagnostic: " + path + ":" + lineNumber + " is missing event_id");
            if (!accumulator.eventIds.add(id)) accumulator.duplicateEvents++;
            double rawTolerance = jsNumber(accumulator.capture.path("coverage").get("slot_tolerance_ms"));
            if (!Double.isFinite(rawTolerance)) rawTolerance = jsNumber(series.get("slot_tolerance_ms"));
            if (!Double.isFinite(rawTolerance)) rawTolerance = 60_000;
            long tolerance = (long) rawTolerance;
            Long settlementSlot = epochMillis(row.get("settlement_slot"));
            if (settlementSlot == null || Math.abs(settlementSlot - event) > tolerance)
                throw failure("JSONL funding semantic diagnostic: " + path + ":" + lineNumber + " has an invalid settlement slot identity");
            if (!accumulator.fundingSlots.add(settlementSlot)) accumulator.duplicateFundingSlots++;
            ObjectNode segment = productionFundingSegment(accumulator.capture, event);
            double cadence = jsNumber(row.get("cadence_ms"));
            if (!Double.isFinite(cadence) || cadence <= 0)
                throw failure("JSONL funding semantic diagnostic: " + path + ":" + lineNumber + " is missing discovered cadence_ms");
            if (segment != null && jsNumber(segment.get("cadence_ms")) != cadence)
                throw failure("JSONL funding semantic diagnostic: " + path + ":" + lineNumber + " cadence_ms is not bound to the discovered segment");
            if (accumulator.previousFundingSlot != null && Math.abs(settlementSlot - accumulator.previousFundingSlot - (long) cadence) > tolerance)
                accumulator.cadenceViolations++;
            accumulator.previousFundingSlot = settlementSlot;
            Long markEvent = epochMillis(row.get("settlement_mark_event_time"));
            Long markAvailable = epochMillis(row.get("settlement_mark_availability_time"));
            boolean provenance = settlementSlot.equals(markEvent) && settlementSlot.equals(markAvailable)
                    && truthy(row.get("settlement_mark_source")) && isHash(row.get("settlement_mark_source_response_sha256"));
            double mark = jsNumber(row.get("settlement_mark")); double markPrice = jsNumber(row.get("mark_price"));
            if (!finite(row.get("funding_rate")) || !Double.isFinite(mark) || mark <= 0 || !Double.isFinite(markPrice)
                    || markPrice <= 0 || markPrice != mark || !provenance)
                throw failure("JSONL funding semantic diagnostic: " + path + ":" + lineNumber + " has invalid exact settlement-mark identity");
        } else if ("metrics_events".equals(seriesType)) {
            for (JsonNode field : array(series.get("metric_required_fields")))
                if (!row.has(text(field)) || row.get(text(field)).isNull() || !finite(row.get(text(field))))
                    throw failure("JSONL metrics semantic diagnostic: " + path + ":" + lineNumber + " has invalid required " + text(field));
            for (String field : List.of("open_interest", "open_interest_value"))
                if (row.has(field) && !row.get(field).isNull() && !finite(row.get(field)))
                    throw failure("JSONL metrics semantic diagnostic: " + path + ":" + lineNumber + " has invalid " + field);
        } else {
            for (String field : List.of("open", "high", "low", "close"))
                if (!finite(row.get(field)) || jsNumber(row.get(field)) <= 0)
                    throw failure("JSONL OHLC semantic diagnostic: " + path + ":" + lineNumber + " has invalid " + field);
            double open = jsNumber(row.get("open")); double high = jsNumber(row.get("high")); double low = jsNumber(row.get("low")); double close = jsNumber(row.get("close"));
            if (high < Math.max(Math.max(open, close), low) || low > Math.min(Math.min(open, close), high))
                throw failure("JSONL OHLC semantic diagnostic: " + path + ":" + lineNumber + " violates high/low ordering");
            if (row.has("volume") && !row.get("volume").isNull() && (!finite(row.get("volume")) || jsNumber(row.get("volume")) < 0))
                throw failure("JSONL volume semantic diagnostic: " + path + ":" + lineNumber + " is invalid");
            if (series.path("completed_bars_only").asBoolean(false) && row.has("completed_bar") && !row.path("completed_bar").asBoolean(false))
                throw failure("JSONL completed-bar semantic diagnostic: " + path + ":" + lineNumber + " is not completed");
        }
        accumulator.rows++;
        accumulator.minEvent = accumulator.minEvent == null ? event : Math.min(accumulator.minEvent, event);
        accumulator.maxEvent = accumulator.maxEvent == null ? event : Math.max(accumulator.maxEvent, event);
        if (availability != null) {
            accumulator.minAvailability = accumulator.minAvailability == null ? availability : Math.min(accumulator.minAvailability, availability);
            accumulator.maxAvailability = accumulator.maxAvailability == null ? availability : Math.max(accumulator.maxAvailability, availability);
        }
        accumulator.previousEvent = event;
    }

    private static ObjectNode finishSemantic(SemanticAccumulator accumulator, Path path) {
        ObjectNode series = accumulator.series; ObjectNode capture = accumulator.capture; ObjectNode coverage = capture.path("coverage") instanceof ObjectNode object ? object : object();
        double rawExpected = jsNumber(series.get("expected_event_count")); Long expected = Double.isFinite(rawExpected) && rawExpected == Math.rint(rawExpected) ? (long) rawExpected : null;
        if (!capture.path("unavailable").asBoolean(false) && coverage.path("complete").asBoolean(false) && expected != null && accumulator.rows != expected)
            throw failure("JSONL semantic row-count diagnostic: " + path + " observed " + accumulator.rows + ", expected " + expected);
        if ("funding_events".equals(text(series.get("series_type"))))
            fundingCoverageCheck(accumulator, null, null, null, path.toString(), series, coverage);
        if (!capture.path("unavailable").asBoolean(false) && accumulator.rows > 0) {
            Long start = epochMillis(series.get("start_at")); Long end = epochMillis(series.get("end_at"));
            if (!"funding_events".equals(text(series.get("series_type")))
                    && (!start.equals(accumulator.minEvent) || !end.equals(accumulator.maxEvent)))
                throw failure("JSONL semantic bounds diagnostic: " + path + " does not cover the frozen start/end");
            Set<Long> irregular = irregularEventSet(capture);
            long unexpected = accumulator.cadenceViolationEvents.stream().filter(event -> !irregular.contains(event)).count();
            if (unexpected > 0) throw failure("JSONL semantic cadence diagnostic: " + path + " has " + unexpected + " unexpected cadence gaps");
            if (accumulator.duplicateEvents > 0 || accumulator.duplicateFundingSlots > 0)
                throw failure("JSONL semantic duplicate diagnostic: " + path + " has duplicate event identities");
        }
        ObjectNode result = object().put("rows", accumulator.rows);
        if (accumulator.minEvent == null) result.putNull("min_event_time"); else result.put("min_event_time", iso(accumulator.minEvent));
        if (accumulator.maxEvent == null) result.putNull("max_event_time"); else result.put("max_event_time", iso(accumulator.maxEvent));
        if (accumulator.minAvailability == null) result.putNull("min_availability_time"); else result.put("min_availability_time", iso(accumulator.minAvailability));
        if (accumulator.maxAvailability == null) result.putNull("max_availability_time"); else result.put("max_availability_time", iso(accumulator.maxAvailability));
        result.put("cadence_violations", accumulator.cadenceViolations);
        return result;
    }

    private static String sqlLiteral(Object value) { return "'" + String.valueOf(value).replace("'", "''") + "'"; }
    private static String sqlIdentifier(Object value) { return "\"" + String.valueOf(value).replace("\"", "\"\"") + "\""; }

    private static List<List<String>> queryRows(Connection connection, String sql) {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            ResultSetMetaData metadata = result.getMetaData(); int columns = metadata.getColumnCount(); List<List<String>> rows = new ArrayList<>();
            while (result.next()) {
                List<String> row = new ArrayList<>(columns);
                for (int column = 1; column <= columns; column++) {
                    Object value = result.getObject(column); row.add(value == null ? null : String.valueOf(value));
                }
                rows.add(row);
            }
            return rows;
        } catch (SQLException error) { throw failure("DuckDB Parquet query failed: " + error.getMessage()); }
    }

    private static long scalarLong(Connection connection, String sql, String label) {
        List<List<String>> rows = queryRows(connection, sql);
        if (rows.isEmpty() || rows.get(0).isEmpty()) throw failure(label);
        try { return Long.parseLong(rows.get(0).get(0)); }
        catch (RuntimeException error) { throw failure(label); }
    }

    private static void requireZero(Connection connection, String sql, String label) {
        long count = scalarLong(connection, sql, label);
        if (count != 0) throw failure(label + ": " + count + " invalid rows");
    }

    private static String parquetEpochExpression(List<List<String>> descriptor, String field) {
        for (List<String> row : descriptor) {
            if (!row.isEmpty() && row.get(0) != null && row.get(0).equalsIgnoreCase(field)) {
                String identifier = sqlIdentifier(row.get(0));
                return row.size() > 1 && row.get(1) != null && row.get(1).toUpperCase(Locale.ROOT).contains("TIMESTAMP")
                        ? "epoch_ms(" + identifier + ")" : "CAST(" + identifier + " AS BIGINT)";
            }
        }
        return null;
    }

    private static Long nullableLong(String value) {
        if (value == null) return null;
        try {
            if (value.matches("-?\\d+")) return Long.parseLong(value);
            return (long) Double.parseDouble(value);
        } catch (RuntimeException ignored) { return null; }
    }

    private static ObjectNode reopenParquet(Path path, Connection connection, ObjectNode series, ObjectNode partition, ObjectNode coverage) {
        String table = "read_parquet(" + sqlLiteral(path) + ")";
        List<List<String>> descriptor = queryRows(connection, "DESCRIBE SELECT * FROM " + table);
        long rowCount = scalarLong(connection, "SELECT count(*)::BIGINT AS row_count FROM " + table,
                "Parquet row count could not be reopened: " + path);
        if (rowCount < 0) throw failure("Parquet row count could not be reopened: " + path);
        Long observedMinEvent = null; Long observedMaxEvent = null; Long observedMinAvailability = null; Long observedMaxAvailability = null;
        Set<String> columns = new HashSet<>(); for (List<String> row : descriptor) if (!row.isEmpty() && row.get(0) != null) columns.add(row.get(0).toLowerCase(Locale.ROOT));
        if (series != null) {
            for (String field : List.of("asset", "instrument", "symbol", "venue", "series_role"))
                if (!columns.contains(field)) throw failure("Parquet semantic schema diagnostic: " + path + " is missing " + field);
            List<String> identityClauses = new ArrayList<>();
            identityClauses.add("lower(CAST(" + sqlIdentifier("asset") + " AS VARCHAR)) <> lower(" + sqlLiteral(text(series.get("asset"))) + ")");
            identityClauses.add("upper(CAST(" + sqlIdentifier("instrument") + " AS VARCHAR)) <> upper(" + sqlLiteral(text(series.get("instrument"))) + ")");
            identityClauses.add("upper(CAST(" + sqlIdentifier("symbol") + " AS VARCHAR)) <> upper(" + sqlLiteral(text(series.get("symbol"))) + ")");
            identityClauses.add("upper(CAST(" + sqlIdentifier("venue") + " AS VARCHAR)) <> upper(" + sqlLiteral(text(series.get("venue"))) + ")");
            identityClauses.add("upper(CAST(" + sqlIdentifier("series_role") + " AS VARCHAR)) <> upper(" + sqlLiteral(text(series.get("series_role"))) + ")");
            requireZero(connection, "SELECT count(*) FROM " + table + " WHERE " + String.join(" OR ", identityClauses),
                    "Parquet semantic identity diagnostic (" + path + ")");
            String seriesType = text(series.get("series_type"));
            String eventField = "funding_events".equals(seriesType) && columns.contains("raw_event_time") ? "raw_event_time" : "event_time";
            String eventExpression = parquetEpochExpression(descriptor, eventField);
            if (eventExpression == null) throw failure("Parquet semantic schema diagnostic: " + path + " is missing " + eventField);
            String availabilityExpression = columns.contains("availability_time") ? parquetEpochExpression(descriptor, "availability_time") : null;
            Long start = epochMillis(series.get("start_at")); Long end = epochMillis(series.get("end_at")); Long cutoff = epochMillis(series.get("availability_cutoff_at"));
            requireZero(connection, "SELECT count(*) FROM " + table + " WHERE " + eventExpression + " IS NULL OR " + eventExpression + " < " + start + " OR " + eventExpression + " > " + end,
                    "Parquet semantic event bounds diagnostic (" + path + ")");
            if (series.path("require_availability_time").asBoolean(false)) {
                if (availabilityExpression == null) throw failure("Parquet semantic schema diagnostic: " + path + " is missing availability_time");
                List<String> invalid = new ArrayList<>(List.of(availabilityExpression + " IS NULL", availabilityExpression + " < " + eventExpression, availabilityExpression + " > " + cutoff));
                double expectedStep = jsNumber(series.get("expected_step_ms"));
                if (series.path("completed_bars_only").asBoolean(false) && expectedStep > 0 && !"funding_events".equals(seriesType)) {
                    List<Long> irregularEvents = new ArrayList<>();
                    for (JsonNode entry : array(coverage == null ? null : coverage.get("irregular_bars"))) {
                        Long event = epochMillis(entry.get("event_time"));
                        if ("EARLY_CLOSE_OUTAGE".equals(text(entry.get("classification"))) && event != null) irregularEvents.add(event);
                    }
                    String allowed = irregularEvents.isEmpty() ? "FALSE" : eventExpression + " IN (" + irregularEvents.stream().map(String::valueOf).reduce((l, r) -> l + "," + r).orElse("") + ")";
                    invalid.add("(" + availabilityExpression + " > (" + eventExpression + " + " + (long) expectedStep + "))");
                    invalid.add("(" + availabilityExpression + " < (" + eventExpression + " + " + (long) expectedStep + " - 1000) AND NOT (" + allowed + "))");
                    for (JsonNode entry : array(coverage == null ? null : coverage.get("irregular_bars"))) {
                        Long event = epochMillis(entry.get("event_time")); Long availability = epochMillis(entry.get("availability_time"));
                        if ("EARLY_CLOSE_OUTAGE".equals(text(entry.get("classification"))) && event != null && availability != null)
                            invalid.add("(" + eventExpression + " = " + event + " AND " + availabilityExpression + " <> " + availability + ")");
                    }
                }
                requireZero(connection, "SELECT count(*) FROM " + table + " WHERE " + String.join(" OR ", invalid),
                        "Parquet semantic availability diagnostic (" + path + ")");
            }
            if ("funding_events".equals(seriesType)) {
                for (String field : List.of("event_id", "funding_rate", "cadence_ms", "settlement_slot", "settlement_mark", "settlement_mark_event_time", "settlement_mark_availability_time", "settlement_mark_source", "settlement_mark_source_response_sha256"))
                    if (!columns.contains(field)) throw failure("Parquet funding semantic schema diagnostic: " + path + " is missing " + field);
                String mark = columns.contains("settlement_mark") ? "settlement_mark" : columns.contains("mark_price") ? "mark_price" : null;
                if (mark == null) throw failure("Parquet funding semantic schema diagnostic: " + path + " is missing settlement mark");
                String settlementSlot = parquetEpochExpression(descriptor, "settlement_slot");
                String markEvent = parquetEpochExpression(descriptor, "settlement_mark_event_time");
                String markAvailability = parquetEpochExpression(descriptor, "settlement_mark_availability_time");
                if (settlementSlot == null || markEvent == null || markAvailability == null)
                    throw failure("Parquet funding semantic schema diagnostic: " + path + " has invalid settlement timestamp columns");
                double rawTolerance = jsNumber(coverage == null ? null : coverage.get("slot_tolerance_ms")); if (!Double.isFinite(rawTolerance)) rawTolerance = jsNumber(series.get("slot_tolerance_ms")); if (!Double.isFinite(rawTolerance)) rawTolerance = 60_000;
                long tolerance = (long) rawTolerance;
                String markSource = sqlIdentifier("settlement_mark_source"); String markSourceHash = sqlIdentifier("settlement_mark_source_response_sha256");
                List<String> bad = new ArrayList<>(List.of(sqlIdentifier("event_id") + " IS NULL",
                        "NOT isfinite(CAST(" + sqlIdentifier("funding_rate") + " AS DOUBLE))",
                        "NOT isfinite(CAST(" + sqlIdentifier(mark) + " AS DOUBLE))", "CAST(" + sqlIdentifier(mark) + " AS DOUBLE) <= 0",
                        "(" + markSource + " IS NULL OR " + markSourceHash + " IS NULL OR NOT regexp_matches(lower(CAST(" + markSourceHash + " AS VARCHAR)), '^[a-f0-9]{64}$') OR " + markEvent + " IS NULL OR " + markAvailability + " IS NULL OR " + markEvent + " <> " + settlementSlot + " OR " + markAvailability + " <> " + settlementSlot + ")",
                        "abs(" + settlementSlot + " - " + eventExpression + ") > " + tolerance));
                if (columns.contains("mark_price")) {
                    bad.add("NOT isfinite(CAST(" + sqlIdentifier("mark_price") + " AS DOUBLE))"); bad.add("CAST(" + sqlIdentifier("mark_price") + " AS DOUBLE) <= 0");
                    bad.add("CAST(" + sqlIdentifier("mark_price") + " AS DOUBLE) <> CAST(" + sqlIdentifier("settlement_mark") + " AS DOUBLE)");
                }
                requireZero(connection, "SELECT count(*) FROM " + table + " WHERE " + String.join(" OR ", bad),
                        "Parquet funding semantic value diagnostic (" + path + ")");
                if (scalarLong(connection, "SELECT count(*) - count(DISTINCT " + sqlIdentifier("event_id") + ") FROM " + table, "Parquet funding duplicate diagnostic") != 0)
                    throw failure("Parquet funding semantic duplicate diagnostic (" + path + ")");
                if (scalarLong(connection, "SELECT count(*) - count(DISTINCT " + settlementSlot + ") FROM " + table, "Parquet funding settlement-slot duplicate diagnostic") != 0)
                    throw failure("Parquet funding semantic settlement-slot duplicate diagnostic (" + path + ")");
                requireZero(connection, "SELECT count(*) FROM (SELECT " + settlementSlot + " AS slot_ms, CAST(" + sqlIdentifier("cadence_ms") + " AS BIGINT) AS cadence_ms, lag(" + settlementSlot + ") OVER (ORDER BY " + settlementSlot + ") AS previous_ms FROM " + table + ") ordered WHERE previous_ms IS NOT NULL AND abs(slot_ms - previous_ms - cadence_ms) > " + tolerance,
                        "Parquet funding semantic cadence diagnostic: " + path + " has unexpected cadence gaps");
            } else if ("metrics_events".equals(seriesType)) {
                if (!columns.contains("event_time")) throw failure("Parquet metrics semantic schema diagnostic: " + path + " is missing event_time");
                for (JsonNode fieldNode : array(series.get("metric_required_fields"))) {
                    String field = text(fieldNode);
                    if (!columns.contains(field.toLowerCase(Locale.ROOT))) throw failure("Parquet metrics semantic schema diagnostic: " + path + " is missing required " + field);
                    requireZero(connection, "SELECT count(*) FROM " + table + " WHERE " + sqlIdentifier(field) + " IS NULL OR NOT isfinite(CAST(" + sqlIdentifier(field) + " AS DOUBLE))",
                            "Parquet metrics semantic value diagnostic (" + path + ":" + field + ")");
                }
            } else {
                for (String field : List.of("open", "high", "low", "close")) if (!columns.contains(field))
                    throw failure("Parquet OHLC semantic schema diagnostic: " + path + " is missing " + field);
                String open = sqlIdentifier("open"), high = sqlIdentifier("high"), low = sqlIdentifier("low"), close = sqlIdentifier("close");
                requireZero(connection, "SELECT count(*) FROM " + table + " WHERE NOT isfinite(CAST(" + open + " AS DOUBLE)) OR NOT isfinite(CAST(" + high + " AS DOUBLE)) OR NOT isfinite(CAST(" + low + " AS DOUBLE)) OR NOT isfinite(CAST(" + close + " AS DOUBLE)) OR CAST(" + open + " AS DOUBLE) <= 0 OR CAST(" + high + " AS DOUBLE) <= 0 OR CAST(" + low + " AS DOUBLE) <= 0 OR CAST(" + close + " AS DOUBLE) <= 0 OR CAST(" + high + " AS DOUBLE) < greatest(CAST(" + open + " AS DOUBLE), CAST(" + low + " AS DOUBLE), CAST(" + close + " AS DOUBLE)) OR CAST(" + low + " AS DOUBLE) > least(CAST(" + open + " AS DOUBLE), CAST(" + high + " AS DOUBLE), CAST(" + close + " AS DOUBLE))",
                        "Parquet OHLC semantic value diagnostic (" + path + ")");
                if (columns.contains("volume")) requireZero(connection, "SELECT count(*) FROM " + table + " WHERE NOT isfinite(CAST(" + sqlIdentifier("volume") + " AS DOUBLE)) OR CAST(" + sqlIdentifier("volume") + " AS DOUBLE) < 0",
                        "Parquet volume semantic diagnostic (" + path + ")");
            }
            List<List<String>> eventStats = queryRows(connection, "SELECT min(" + eventExpression + "), max(" + eventExpression + "), count(*) FROM " + table);
            List<String> eventValues = eventStats.isEmpty() ? List.of() : eventStats.get(0);
            observedMinEvent = eventValues.size() > 0 ? nullableLong(eventValues.get(0)) : null;
            observedMaxEvent = eventValues.size() > 1 ? nullableLong(eventValues.get(1)) : null;
            if (availabilityExpression != null) {
                List<List<String>> values = queryRows(connection, "SELECT min(" + availabilityExpression + "), max(" + availabilityExpression + ") FROM " + table);
                List<String> row = values.isEmpty() ? List.of() : values.get(0);
                observedMinAvailability = row.size() > 0 ? nullableLong(row.get(0)) : null; observedMaxAvailability = row.size() > 1 ? nullableLong(row.get(1)) : null;
            }
            double rawExpected = jsNumber(series.get("expected_event_count"));
            if (coverage != null && coverage.path("complete").asBoolean(false) && Double.isFinite(rawExpected) && rawExpected != rowCount)
                throw failure("Parquet semantic expected-row diagnostic: " + path + " observed " + rowCount + ", expected " + (long) rawExpected);
            if ("funding_events".equals(seriesType)) fundingCoverageCheck(null, rowCount, observedMinEvent, observedMaxEvent, path.toString(), series, coverage == null ? object() : coverage);
            if (!"funding_events".equals(seriesType)) {
                if (!start.equals(observedMinEvent) || !end.equals(observedMaxEvent) || eventValues.size() < 3 || nullableLong(eventValues.get(2)) != rowCount)
                    throw failure("Parquet semantic coverage diagnostic: " + path + " does not match frozen bounds/count");
                double step = jsNumber(series.get("expected_step_ms"));
                if (step > 0) {
                    List<List<String>> gaps = queryRows(connection, "SELECT event_ms FROM (SELECT " + eventExpression + " AS event_ms, lag(" + eventExpression + ") OVER (ORDER BY " + eventExpression + ") AS previous_ms FROM " + table + ") ordered WHERE previous_ms IS NOT NULL AND event_ms - previous_ms <> " + (long) step);
                    Set<Long> irregular = irregularEventSet(object().set("coverage", coverage == null ? object() : coverage));
                    long unexpected = gaps.stream().filter(row -> !row.isEmpty()).map(row -> nullableLong(row.get(0))).filter(value -> value != null && !irregular.contains(value)).count();
                    if (unexpected > 0) throw failure("Parquet semantic cadence diagnostic: " + path + " has " + unexpected + " unexpected cadence gaps");
                }
            }
        }
        ArrayNode schemaRows = array(); for (List<String> row : descriptor) { ArrayNode value = array(); for (String field : row) if (field == null) value.addNull(); else value.add(field); schemaRows.add(value); }
        ObjectNode result = object().put("schema_sha256", hash(schemaRows)).put("row_count", rowCount);
        ArrayNode names = array(); for (List<String> row : descriptor) if (!row.isEmpty()) names.add(row.get(0)); result.set("columns", names);
        result.put("semantic_checked", series != null);
        if (observedMinEvent == null) result.putNull("min_event_time"); else result.put("min_event_time", iso(observedMinEvent));
        if (observedMaxEvent == null) result.putNull("max_event_time"); else result.put("max_event_time", iso(observedMaxEvent));
        if (observedMinAvailability == null) result.putNull("min_availability_time"); else result.put("min_availability_time", iso(observedMinAvailability));
        if (observedMaxAvailability == null) result.putNull("max_availability_time"); else result.put("max_availability_time", iso(observedMaxAvailability));
        return result;
    }

    private record RequiredSeries(ArrayNode rows, int count, boolean acquisitionComplete, boolean parquetComplete) {}
    private record DeclaredCompleteness(ArrayNode rows, int requiredCount, boolean requiredComplete,
                                        int availableCount, boolean availableComplete, int unavailableCount,
                                        List<String> missingAvailable) {}
    private record Topology(boolean pass, boolean universe, boolean genuineWindow, boolean exact,
                            int requiredCount, int targetCount, List<String> violations) {}

    private static ObjectNode validateCoverage(ProductionDocument document, ObjectNode plan, ObjectNode acquisition, ObjectNode parquet) {
        if (document == null) return null;
        ObjectNode coverage = document.value();
        boolean authoritative = StrategyPerformanceV5.AUTHORITATIVE_COVERAGE_SCHEMA.equals(text(coverage.get("schema")));
        for (String key : List.of("plan_sha256", "acquisition_sha256", "parquet_sha256", "dataset_root_sha256")) {
            JsonNode expected = switch (key) {
                case "plan_sha256" -> plan.get("content_sha256");
                case "acquisition_sha256" -> acquisition == null ? null : acquisition.get("content_sha256");
                case "parquet_sha256" -> parquet == null ? null : parquet.get("content_sha256");
                default -> parquet == null ? null : parquet.get("dataset_root_sha256");
            };
            boolean required = "plan_sha256".equals(key) || authoritative;
            if (required && (!isHash(coverage.get(key)) || nullish(expected) || !text(coverage.get(key)).equals(text(expected))))
                throw failure("coverage manifest " + key + " is missing or not linked to the supplied manifest");
            if (!required && coverage.has(key) && !coverage.get(key).isNull() && !nullish(expected) && !text(coverage.get(key)).equals(text(expected)))
                throw failure("coverage manifest " + key + " is not linked to the supplied manifest");
        }
        if (authoritative && "OBSERVED_COMPLETE".equals(text(coverage.get("status")))) {
            if (!same(coverage.get("window"), plan.get("window"))) throw failure("OBSERVED_COMPLETE coverage window is not bound to the frozen plan");
            List<String> coverageAssets = new ArrayList<>(); for (JsonNode asset : array(coverage.get("assets"))) coverageAssets.add(text(asset).toLowerCase(Locale.ROOT)); coverageAssets.sort(String::compareTo);
            List<String> planAssets = new ArrayList<>(); for (JsonNode asset : array(plan.get("assets"))) planAssets.add(text(asset).toLowerCase(Locale.ROOT)); planAssets.sort(String::compareTo);
            if (!coverageAssets.equals(planAssets)) throw failure("OBSERVED_COMPLETE coverage assets are not bound to the frozen plan");
            ArrayNode coverageRows = array(coverage.get("series")); if (coverageRows.isEmpty()) throw failure("OBSERVED_COMPLETE coverage has no series inventory");
            Map<String, JsonNode> expected = new LinkedHashMap<>(); for (JsonNode series : array(plan.get("series"))) expected.put(identity(series), series);
            Map<String, JsonNode> actual = new LinkedHashMap<>();
            for (JsonNode row : coverageRows) {
                String identity = identity(row); if (actual.containsKey(identity)) throw failure("coverage series inventory contains duplicate identity: " + identity);
                JsonNode series = expected.get(identity); if (series == null) throw failure("coverage series is not declared by the frozen plan: " + identity); actual.put(identity, row);
                for (String[] mapping : List.of(new String[]{"asset", "asset"}, new String[]{"venue", "venue"}, new String[]{"instrument", "instrument"}, new String[]{"symbol", "symbol"}, new String[]{"interval", "interval"}, new String[]{"series_type", "series_type"}, new String[]{"series_role", "series_role"}, new String[]{"requested_start_at", "start_at"}, new String[]{"requested_end_at", "end_at"}, new String[]{"availability_cutoff_at", "availability_cutoff_at"}))
                    if (!text(row.get(mapping[0])).equalsIgnoreCase(text(series.get(mapping[1]))))
                        throw failure("coverage series does not match frozen plan field " + mapping[1] + ": " + identity);
                if (row.path("required").asBoolean(false) != series.path("required").asBoolean(false)
                        || row.path("tradeable").asBoolean(false) != series.path("tradeable").asBoolean(false))
                    throw failure("coverage series flags do not match the frozen plan: " + identity);
                if (series.path("required").asBoolean(false) && !row.path("required").asBoolean(false)) throw failure("coverage required flag is false for required series: " + identity);
                if (series.path("required").asBoolean(false) && !row.path("complete").asBoolean(false)) throw failure("coverage required series is not complete: " + identity);
                if ((series.path("required").asBoolean(false) || row.path("complete").asBoolean(false))
                        && (nullish(row.get("jsonl_partition")) || nullish(row.get("parquet_partition"))))
                    throw failure("coverage " + (series.path("required").asBoolean(false) ? "required" : "complete") + " series is missing a physical partition projection: " + identity);
                JsonNode acquisitionCapture = findByIdentity(array(acquisition == null ? null : acquisition.get("captures")), identity);
                JsonNode parquetCapture = findByIdentity(array(parquet == null ? null : parquet.get("captures")), identity);
                if (!nullish(row.get("jsonl_partition"))) assertCoveragePartition(row.get("jsonl_partition"), acquisitionCapture == null ? null : acquisitionCapture.get("partition"), "JSONL", identity);
                if (!nullish(row.get("parquet_partition"))) assertCoveragePartition(row.get("parquet_partition"), parquetCapture == null ? null : parquetCapture.get("partition"), "Parquet", identity);
                double expectedRows = jsNumber(series.get("expected_event_count"));
                if (row.path("complete").asBoolean(false) && Double.isFinite(expectedRows) && jsNumber(row.get("expected_rows")) != expectedRows)
                    throw failure("coverage expected row count is stale: " + identity);
            }
            for (String identity : expected.keySet()) if (!actual.containsKey(identity)) throw failure("coverage series inventory is missing a plan series: " + identity);
            if (!coverage.has("dated_futures") || !coverage.get("dated_futures").isArray()) throw failure("OBSERVED_COMPLETE coverage has no dated-futures inventory");
            Map<String, JsonNode> datedExpected = new LinkedHashMap<>();
            for (JsonNode series : array(plan.get("series"))) if ("BINANCE_USDM_DATED_FUTURE".equals(text(series.get("instrument")).toUpperCase(Locale.ROOT)))
                datedExpected.put(text(series.get("asset")).toLowerCase(Locale.ROOT) + "|" + text(series.get("symbol")).toUpperCase(Locale.ROOT), series);
            Set<String> datedActual = new HashSet<>();
            for (JsonNode assetRow : array(coverage.get("dated_futures"))) {
                if (!assetRow.isObject() || text(assetRow.get("asset")).isEmpty() || !assetRow.path("contracts").isArray())
                    throw failure("OBSERVED_COMPLETE dated-futures inventory is malformed");
                for (JsonNode contract : array(assetRow.get("contracts"))) {
                    String asset = text(contract.get("asset")); if (asset.isEmpty()) asset = text(assetRow.get("asset"));
                    String key = asset.toLowerCase(Locale.ROOT) + "|" + text(contract.get("symbol")).toUpperCase(Locale.ROOT);
                    if (!datedActual.add(key)) throw failure("coverage dated-futures inventory contains duplicate identity: " + key);
                    JsonNode series = datedExpected.get(key); if (series == null) throw failure("coverage dated-futures contract is not declared by the frozen plan: " + key);
                    if (!"BINANCE_USDM_DATED_FUTURE".equals(text(contract.get("instrument")).toUpperCase(Locale.ROOT))
                            || !java.util.Objects.equals(epochMillis(contract.get("first_bar_at")), epochMillis(series.get("start_at")))
                            || !java.util.Objects.equals(epochMillis(contract.get("last_bar_at")), epochMillis(series.get("end_at"))))
                        throw failure("coverage dated-futures contract does not match frozen bounds: " + key);
                    String seriesIdentity = identity(series);
                    JsonNode acquisitionCapture = findByIdentity(
                            array(acquisition == null ? null : acquisition.get("captures")), seriesIdentity);
                    JsonNode parquetCapture = findByIdentity(
                            array(parquet == null ? null : parquet.get("captures")), seriesIdentity);
                    if (acquisitionCapture != null && acquisitionCapture.path("unavailable").asBoolean(false)) {
                        if ("ARCHIVE_INGESTED".equals(text(contract.get("archive_ingestion_status")))
                                || contract.path("archive_coverage_complete").asBoolean(false))
                            throw failure("coverage dated-futures unavailable contract claims physical ingestion: " + key);
                    } else {
                        if (!"ARCHIVE_INGESTED".equals(text(contract.get("archive_ingestion_status")))
                                || !contract.path("archive_coverage_complete").asBoolean(false))
                            throw failure("coverage dated-futures available contract lacks complete archive proof: " + key);
                        JsonNode refs = contract.get("archive_physical_capture_refs");
                        if (nullish(refs)
                                || !text(refs.get("jsonl_partition_sha256")).equals(text(acquisitionCapture == null
                                        ? null : acquisitionCapture.path("partition").get("sha256")))
                                || !text(refs.get("parquet_partition_sha256")).equals(text(parquetCapture == null
                                        ? null : parquetCapture.path("partition").get("sha256")))
                                || !text(refs.get("dataset_root_sha256")).equals(text(parquet == null
                                        ? null : parquet.get("dataset_root_sha256"))))
                            throw failure("coverage dated-futures physical refs are not bound: " + key);
                    }
                }
            }
            for (String key : datedExpected.keySet()) if (!datedActual.contains(key)) throw failure("coverage dated-futures inventory is missing a plan capture: " + key);
        }
        return coverage;
    }

    private static JsonNode findByIdentity(ArrayNode rows, String expected) {
        for (JsonNode row : rows) if (identity(row).equals(expected)) return row;
        return null;
    }

    private static void assertCoveragePartition(JsonNode declared, JsonNode actual, String label, String identity) {
        if (nullish(actual) || !text(declared.get("path")).equals(text(actual.get("path")))
                || !text(firstTruthy(declared, "byte_sha256", "sha256")).equals(text(actual.get("sha256")))
                || jsNumber(declared.get("bytes")) != jsNumber(actual.get("bytes"))
                || jsNumber(declared.get("row_count")) != jsNumber(actual.get("row_count")))
            throw failure("coverage " + label + " partition is not physically bound: " + identity);
    }

    private static RequiredSeries requiredSeries(ObjectNode plan, ObjectNode acquisition, List<ObjectNode> parquetRows) {
        Set<String> physical = new HashSet<>(); for (ObjectNode row : parquetRows) if (!text(row.get("identity")).startsWith("artifact|")) physical.add(text(row.get("identity")));
        Set<String> captures = new HashSet<>(); for (JsonNode row : array(acquisition == null ? null : acquisition.get("captures"))) captures.add(identity(row));
        ArrayNode rows = array();
        for (JsonNode series : array(plan.get("series"))) {
            if (series.has("required") && series.get("required").isBoolean() && !series.get("required").booleanValue()) continue;
            String id = identity(series); rows.add(object().put("identity", id).put("acquisition_present", captures.contains(id)).put("parquet_present", physical.contains(id)));
        }
        boolean acquisitionComplete = true, parquetComplete = true;
        for (JsonNode row : rows) { acquisitionComplete &= row.path("acquisition_present").asBoolean(); parquetComplete &= row.path("parquet_present").asBoolean(); }
        return new RequiredSeries(rows, rows.size(), acquisitionComplete, parquetComplete);
    }

    private static boolean unavailableProven(JsonNode capture, JsonNode coverageRow) {
        List<String> gaps = new ArrayList<>();
        for (JsonNode value : array(coverageRow == null ? null : coverageRow.get("gaps"))) gaps.add(text(value).toUpperCase(Locale.ROOT));
        for (JsonNode value : array(coverageRow == null ? null : coverageRow.get("limitations"))) gaps.add(text(value).toUpperCase(Locale.ROOT));
        String reason = text(capture == null ? null : capture.path("coverage").get("reason")); if (!reason.isEmpty()) gaps.add(reason.toUpperCase(Locale.ROOT));
        boolean explicit = gaps.stream().anyMatch(value -> value.equals("UNAVAILABLE") || value.contains(":UNAVAILABLE") || value.contains("SOURCE_CAPTURE_NOT_RETAINED") || value.contains("NOT_AVAILABLE"));
        if (capture != null && capture.path("unavailable").asBoolean(false)) return coverageRow == null || !coverageRow.path("complete").asBoolean(false) && explicit;
        return explicit && coverageRow != null && !coverageRow.path("complete").asBoolean(false);
    }

    private static DeclaredCompleteness declaredCompleteness(ObjectNode plan, ObjectNode acquisition, List<ObjectNode> parquetRows, ObjectNode coverage) {
        Map<String, JsonNode> acquisitionRows = new HashMap<>(); for (JsonNode row : array(acquisition == null ? null : acquisition.get("captures"))) acquisitionRows.put(identity(row), row);
        Map<String, ObjectNode> parquet = new HashMap<>(); for (ObjectNode row : parquetRows) if (!text(row.get("identity")).startsWith("artifact|")) parquet.put(text(row.get("identity")), row);
        Map<String, JsonNode> coverageRows = new HashMap<>(); for (JsonNode row : array(coverage == null ? null : coverage.get("series"))) coverageRows.put(identity(row), row);
        ArrayNode rows = array(); List<String> missing = new ArrayList<>(); int requiredCount = 0, availableCount = 0, unavailableCount = 0; boolean requiredComplete = true;
        for (JsonNode series : array(plan.get("series"))) {
            String id = identity(series); JsonNode acquisitionCapture = acquisitionRows.get(id); ObjectNode parquetCapture = parquet.get(id); JsonNode coverageRow = coverageRows.get(id);
            boolean unavailable = unavailableProven(acquisitionCapture, coverageRow); boolean available = !unavailable;
            boolean acquisitionPresent = acquisitionCapture != null && !nullish(acquisitionCapture.get("partition")) && !acquisitionCapture.path("unavailable").asBoolean(false);
            boolean parquetPresent = parquetCapture != null;
            boolean acquisitionComplete = acquisitionPresent && acquisitionCapture.path("coverage").path("complete").asBoolean(false);
            boolean parquetComplete = parquetPresent && parquetCapture.path("coverage").path("complete").asBoolean(false);
            boolean required = series.path("required").asBoolean(false);
            if (required) { requiredCount++; requiredComplete &= acquisitionComplete && parquetComplete; }
            if (available) { availableCount++; if (!acquisitionComplete || !parquetComplete) missing.add(id); } else unavailableCount++;
            rows.add(object().put("identity", id).put("required", required).put("available_declared", available).put("unavailable_proven", unavailable)
                    .put("acquisition_present", acquisitionPresent).put("parquet_present", parquetPresent).put("acquisition_complete", acquisitionComplete).put("parquet_complete", parquetComplete));
        }
        missing.sort(String::compareTo);
        return new DeclaredCompleteness(rows, requiredCount, requiredComplete, availableCount, missing.isEmpty(), unavailableCount, List.copyOf(missing));
    }

    private static Topology topology(ObjectNode plan, RequiredSeries required) {
        List<String> violations = new ArrayList<>();
        List<String> assets = new ArrayList<>(); for (JsonNode value : array(plan.get("assets"))) assets.add(text(value).toLowerCase(Locale.ROOT)); assets = assets.stream().distinct().sorted().toList();
        boolean universe = assets.equals(StrategyPerformanceV5.V5_CANONICAL_ASSETS);
        if (!universe) violations.add("CANONICAL_EIGHT_ASSET_UNIVERSE_REQUIRED");
        Long start = epochMillis(plan.path("window").get("start_at")); Long end = epochMillis(plan.path("window").get("end_at")); Long completed = epochMillis(plan.path("window").get("completed_through_at")); Long asOf = epochMillis(plan.get("as_of"));
        boolean genuine = false;
        if (start != null && end != null && completed != null && asOf != null) {
            long calendarEnd = java.time.ZonedDateTime.ofInstant(Instant.ofEpochMilli(start), ZoneOffset.UTC).plusYears(5).toInstant().toEpochMilli();
            long latest = Math.floorDiv(asOf, StrategyPerformanceV5.FOUR_HOURS_MS) * StrategyPerformanceV5.FOUR_HOURS_MS;
            genuine = calendarEnd == end && completed - end == StrategyPerformanceV5.FOUR_HOURS_MS && completed == latest
                    && start % StrategyPerformanceV5.FOUR_HOURS_MS == 0 && end % StrategyPerformanceV5.FOUR_HOURS_MS == 0;
        }
        if (!genuine) violations.add("GENUINE_FIVE_YEAR_COMPLETED_THROUGH_BOUNDARY_REQUIRED");
        Map<String, ObjectNode> expected = new LinkedHashMap<>();
        for (String asset : StrategyPerformanceV5.V5_CANONICAL_ASSETS) {
            String symbol = asset.toUpperCase(Locale.ROOT) + "USDT";
            for (String[] value : List.of(new String[]{"BINANCE_SPOT", "signal_bars", "PRICE", "4h"}, new String[]{"BINANCE_USDM_PERPETUAL", "signal_bars", "PRICE", "4h"}, new String[]{"BINANCE_USDM_PERPETUAL_MARK", "mark_bars", "MARK", "4h"}, new String[]{"BINANCE_USDM_PERPETUAL", "funding_events", "FUNDING", "event"})) {
                ObjectNode row = object().put("asset", asset).put("instrument", value[0]).put("symbol", symbol).put("interval", value[3]).put("series_type", value[1]).put("series_role", value[2]);
                expected.put(identity(row), row);
            }
        }
        Map<String, JsonNode> actual = new LinkedHashMap<>();
        for (JsonNode series : array(plan.get("series"))) if (series.path("required").asBoolean(false)) {
            String key = identity(series); actual.put(key, series); ObjectNode target = expected.get(key);
            if (target == null || !text(series.get("series_role")).equalsIgnoreCase(text(target.get("series_role")))
                    || !text(series.get("symbol")).equalsIgnoreCase(text(target.get("symbol"))) || !text(series.get("instrument")).equalsIgnoreCase(text(target.get("instrument")))
                    || !text(series.get("interval")).equalsIgnoreCase(text(target.get("interval"))) || !text(series.get("series_type")).equalsIgnoreCase(text(target.get("series_type")))
                    || !text(series.get("start_at")).equals(text(plan.path("window").get("start_at"))) || !text(series.get("end_at")).equals(text(plan.path("window").get("end_at")))
                    || !text(series.get("availability_cutoff_at")).equals(text(plan.path("window").get("completed_through_at")))) violations.add("WRONG_REQUIRED_SERIES_TOPOLOGY:" + key);
            if (target != null && !"event".equals(text(target.get("interval"))) && jsNumber(series.get("expected_step_ms")) != StrategyPerformanceV5.FOUR_HOURS_MS) violations.add("WRONG_REQUIRED_SERIES_CADENCE:" + key);
            if (target != null && "funding_events".equals(text(target.get("series_type"))) && (!series.path("event_driven").asBoolean(false) || !series.path("event_sequence_mode").asBoolean(false))) violations.add("WRONG_REQUIRED_SERIES_EVENT_CONTRACT:" + key);
        }
        for (String key : expected.keySet()) if (!actual.containsKey(key)) violations.add("MISSING_REQUIRED_SERIES:" + key);
        if (required.count() != expected.size() || actual.size() != expected.size()) violations.add("REQUIRED_SERIES_COUNT_OR_SET_MISMATCH");
        List<String> unique = new ArrayList<>(new TreeSet<>(violations));
        return new Topology(unique.isEmpty(), universe, genuine, unique.isEmpty(), required.count(), expected.size(), List.copyOf(unique));
    }

    private static JsonNode firstTruthy(JsonNode value, String... names) {
        JsonNode fallback = null;
        for (String name : names) {
            JsonNode candidate = value == null ? null : value.get(name);
            if (candidate != null && fallback == null) fallback = candidate;
            if (truthy(candidate)) return candidate;
        }
        return fallback;
    }

    private static ObjectNode statisticalCheckpointSmoke() {
        Path root = null;
        try {
            root = Files.createTempDirectory("strategy-v5-performance-smoke-");
            String dataset = hash("smoke-dataset"); String behavior = hash("smoke-behavior");
            ObjectNode headArgs = object().put("hypothesisFamily", "benchmark-smoke")
                    .put("datasetSha256", dataset);
            headArgs.set("entries", array().add(object().put("behavior_sha256", behavior)));
            ObjectNode head = StrategyStatisticalV5.makeExposureHead(headArgs);
            ArrayNode episodes = array();
            for (int index = 0; index < 12; index++) {
                String decision = ISO_MILLIS.format(Instant.parse("2026-02-01T00:00:00.000Z")
                        .plusSeconds(index * 86_400L));
                ObjectNode returns = object(); returns.set("smoke-baseline",
                        object().put("net_r", .01).put("traded", true));
                ObjectNode row = object().put("episode_id", "smoke-" + index).put("asset", "btc")
                        .put("decision_time", decision)
                        .put("resolution_time", ISO_MILLIS.format(Instant.parse(decision).plusSeconds(3_600)))
                        .put("eligible", true);
                row.set("candidate_returns", returns); episodes.add(row);
            }
            ObjectNode lineage = object().put("dataset_sha256", dataset)
                    .put("candidate_set_sha256", hash("smoke-candidates"))
                    .put("feature_set_sha256", hash("smoke-features"))
                    .put("label_set_sha256", hash("smoke-labels"))
                    .put("execution_set_sha256", hash("smoke-execution"));
            ObjectNode candidate = object().put("candidate_id", "smoke-baseline")
                    .put("behavior_sha256", behavior);
            ObjectNode artifactArgs = object(); artifactArgs.set("lineage", lineage);
            artifactArgs.set("candidates", array().add(candidate)); artifactArgs.set("episodes", episodes);
            artifactArgs.set("exposureHead", head);
            ObjectNode artifact = StrategyStatisticalV5.makeStatisticalArtifactSet(artifactArgs);
            StrategyEvaluatorV5.Evaluator evaluator = request -> {
                ArrayNode ids = request.path("episode_ids") instanceof ArrayNode rows ? rows : array();
                ObjectNode candidateReturns = object();
                for (JsonNode id : ids) candidateReturns.set(text(id),
                        object().put("net_r", .01).put("traded", true));
                ObjectNode metrics = object().put("cost_r", 0).put("coverage_fraction", 1)
                        .put("capacity_pass", true).put("max_drawdown_r", 0).put("profit_factor", 2);
                ObjectNode output = object(); output.set("candidate_returns", candidateReturns);
                output.set("metrics", metrics); return output;
            };
            ObjectNode gene = object().put("name", "threshold").put("type", "continuous")
                    .put("min", 0).put("max", 1).put("step", .1).put("default", .5);
            ObjectNode space = object(); space.set("genes", array().add(gene));
            ObjectNode config = object().put("population", 4).put("generations", 4)
                    .put("minGenerations", 3).put("plateauGenerations", 3);
            config.set("seeds", array().add(11).add(23).add(47));
            ArrayNode training = array(); for (int index = 0; index < 12; index++) training.add("smoke-" + index);
            Path checkpoint = root.resolve("checkpoint.json");
            ObjectNode interruptedArgs = geneticSmokeArgs(artifact, head, space, config.deepCopy(), training,
                    checkpoint); ((ObjectNode) interruptedArgs.get("config")).put("interruptAfterGeneration", 1);
            boolean interrupted = false;
            try { StrategyStatisticalV5.runGeneticSearchV5(interruptedArgs, evaluator); }
            catch (RuntimeException error) {
                if (!rootMessage(error).contains("CHECKPOINT_INTERRUPTED")) throw error;
                interrupted = true;
            }
            ObjectNode saved = StrategyStatisticalV5.readGeneticCheckpointFile(checkpoint);
            ObjectNode resumeArgs = geneticSmokeArgs(artifact, head, space, config, training, checkpoint);
            resumeArgs.set("checkpoint", saved);
            ObjectNode resumed = StrategyStatisticalV5.resumeGeneticSearchV5(resumeArgs, evaluator);
            ObjectNode freshArgs = geneticSmokeArgs(artifact, head, space, config, training, root.resolve("fresh.json"));
            ObjectNode fresh = StrategyStatisticalV5.runGeneticSearchV5(freshArgs, evaluator);
            String resumedHash = text(resumed.path("run").get("content_sha256"));
            String freshHash = text(fresh.path("run").get("content_sha256"));
            return object().put("status", interrupted && resumedHash.equals(freshHash) ? "PASS" : "FAIL")
                    .put("interrupted_checkpoint_sha256", text(saved.get("content_sha256")))
                    .put("resumed_run_sha256", resumedHash).put("fresh_run_sha256", freshHash)
                    .put("bounded_fixture", true);
        } catch (IOException error) {
            throw failure("cannot run bounded checkpoint smoke: " + error.getMessage());
        } finally {
            if (root != null) deleteTree(root);
        }
    }

    private static ObjectNode geneticSmokeArgs(ObjectNode artifact, ObjectNode head, ObjectNode space,
                                                ObjectNode config, ArrayNode training, Path checkpoint) {
        ObjectNode value = object().put("mode", "FIXTURE").put("checkpointPath", checkpoint.toString());
        value.set("artifact", artifact); value.set("geneSpace", space); value.set("trainingEpisodeIds", training);
        value.set("exposureHead", head); value.set("constraints", object().put("minEpisodes", 3));
        value.set("config", config); return value;
    }

    private static ObjectNode parseArguments(String[] arguments) {
        ObjectNode result = object();
        for (int index = 0; index < arguments.length; index++) {
            String token = String.valueOf(arguments[index]); if (!token.startsWith("--")) continue;
            String body = token.substring(2); int equals = body.indexOf('=');
            if (equals >= 0) {
                String key = body.substring(0, equals); String value = body.substring(equals + 1);
                if (value.isEmpty()) result.put(key, true); else result.put(key, value);
            } else if (index + 1 < arguments.length && !String.valueOf(arguments[index + 1]).startsWith("--")) {
                result.put(body, String.valueOf(arguments[++index]));
            } else result.put(body, true);
        }
        return result;
    }

    private static void copyTruthy(ObjectNode target, String output, JsonNode source, String... names) {
        JsonNode value = firstTruthy(source, names);
        if (truthy(value)) target.set(output, value.deepCopy());
    }

    private static boolean trueFlag(JsonNode value) {
        return value != null && (value.isBoolean() && value.booleanValue()
                || value.isTextual() && Set.of("true", "1").contains(value.textValue()));
    }

    private static double commandNumber(JsonNode value, double fallback) {
        JsonNode effective = truthy(value) ? value : MAPPER.getNodeFactory().numberNode(fallback);
        return jsNumber(effective);
    }

    private static List<List<String>> scopes(Map<String, JsonNode> features, String asset) {
        List<String> ids = features.keySet().stream().filter(id -> id.startsWith(asset + "-"))
                .toList();
        return List.of(List.copyOf(ids.subList(0, (int) Math.floor(ids.size() / 3D))),
                List.copyOf(ids.subList(0, (int) Math.floor(ids.size() * 2D / 3D))), List.copyOf(ids));
    }

    private static double elapsedMillis(long started) { return (System.nanoTime() - started) / 1_000_000D; }

    private static int compactBytes(JsonNode value) {
        try { return MAPPER.writeValueAsBytes(value).length; }
        catch (IOException error) { throw failure("benchmark fixture cannot be serialized"); }
    }

    private static void putNullable(ObjectNode target, String field, Double value) {
        if (value == null) target.putNull(field); else target.put(field, value);
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            List<Path> values = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path value : values) Files.deleteIfExists(value);
        } catch (IOException ignored) { /* bounded temporary smoke is best-effort cleanup */ }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error; while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static int intDefault(JsonNode value, int fallback) {
        double number = jsNumber(value);
        return Double.isFinite(number) ? (int) number : fallback;
    }

    private static void compareSemanticBound(JsonNode declared, JsonNode observed, String message) {
        if (nullish(declared)) return;
        Long epoch = epochMillis(declared);
        if (epoch == null || !iso(epoch).equals(text(observed))) throw failure(message);
    }

    private static long usedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static List<String> sortedDistinct(ArrayNode rows, String field) {
        TreeSet<String> values = new TreeSet<>();
        for (JsonNode row : rows) {
            JsonNode value = field == null ? row : row.get(field);
            if (!nullish(value) && !text(value).isEmpty()) values.add(text(value));
        }
        return List.copyOf(values);
    }

    private static ArrayNode strings(List<String> values) { ArrayNode rows = array(); values.forEach(rows::add); return rows; }

    private static void sortArray(ArrayNode rows, String field) {
        List<JsonNode> values = new ArrayList<>(); rows.forEach(values::add);
        values.sort(Comparator.comparing(value -> text(field == null ? value : value.get(field)))); rows.removeAll(); values.forEach(rows::add);
    }

    private static long sumPartition(List<ObjectNode> rows, String field) {
        long result = 0; for (ObjectNode row : rows) result += (long) jsNumber(row.path("partition").get(field)); return result;
    }

    private static int countTrue(ArrayNode rows, String field) { int count = 0; for (JsonNode row : rows) if (row.path(field).asBoolean(false)) count++; return count; }

    private static ObjectNode requiredObject(RequiredSeries required) {
        ObjectNode value = object(); value.set("rows", required.rows()); value.put("count", required.count()).put("acquisition_complete", required.acquisitionComplete()).put("parquet_complete", required.parquetComplete()); return value;
    }

    private static double round(double value, int places) {
        double factor = Math.pow(10, places); return Math.round(value * factor) / factor;
    }

    private static ObjectNode inputDescriptor(ProductionDocument document, ObjectNode value) {
        ObjectNode result = object(); if (document.path() == null) result.putNull("path"); else result.put("path", document.path().toString()); result.put("content_sha256", text(value.get("content_sha256"))); if (document.byteSha256() == null) result.putNull("byte_sha256"); else result.put("byte_sha256", document.byteSha256()); return result;
    }
}

package com.tradinganalytics.marketdata.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.file.Path;
import java.util.List;

/**
 * Compatibility facade for the PIT-safe research-lake primitives ported from
 * {@code tools/research-data.mjs}.
 *
 * <p>The public records and static methods intentionally remain here. Their
 * implementations live in focused package-private collaborators so callers
 * retain one stable API while parsing, manifests, storage, and publication
 * evolve independently.</p>
 */
public final class ResearchData {
    public static final String DATASET_MANIFEST_SCHEMA = "strategy-data-manifest/2";
    public static final String FEATURE_SET_SCHEMA = "research-feature-set/1";
    public static final String LABEL_SET_SCHEMA = "research-label-set/1";
    public static final List<String> PIT_TIERS = List.of(
            "T0_IMMUTABLE_EVENT", "T1_PUBLICATION_VINTAGE", "T2_CAPTURED_AS_OF",
            "T3_REVISED_OR_PROXY", "UNVERIFIED");
    public static final List<String> CORE_CRYPTO_ASSETS = List.of(
            "btc", "eth", "sol", "bnb", "xrp", "ada", "link", "aave");
    /**
     * Legacy manifest compatibility metadata. Runtime Parquet work uses the
     * embedded DuckDB JDBC driver; this image is not pulled or executed.
     */
    public static final String LEGACY_DUCKDB_IMAGE = "docker.io/duckdb/duckdb:1.4.4@sha256:"
            + "2a5c5fb1bf8a7a93a43893b583cf15fcfebc0b8e02a39110593582907f96d8ad";
    public static final String LEGACY_DUCKDB_IMAGE_DIGEST =
            "2a5c5fb1bf8a7a93a43893b583cf15fcfebc0b8e02a39110593582907f96d8ad";

    // Package-private because manifest/snapshot collaborators must preserve
    // the original Node-producer lineage digest without duplicating it.
    static final String RESEARCH_DATA_CODE_SHA256 =
            "bf5b0e130bc97ce5f497e5b400c4c782c7d834cbabaea4f954e5642d78727b7c";

    private ResearchData() {
    }

    public record NormalizeOptions(
            String asset, String venue, String instrument, String timeframe, String datasetId,
            String pitTier, String role, String source, String availabilityPolicy) {
        public NormalizeOptions {
            timeframe = valueOr(timeframe, "4h");
            datasetId = valueOr(datasetId, "dataset");
            pitTier = valueOr(pitTier, "UNVERIFIED");
            role = valueOr(role, "FEATURE");
            source = valueOr(source, "unknown");
            availabilityPolicy = valueOr(availabilityPolicy, "completed_bar");
        }

        public static NormalizeOptions defaults() {
            return new NormalizeOptions(null, null, null, "4h", "dataset", "UNVERIFIED",
                    "FEATURE", "unknown", "completed_bar");
        }
    }

    public record SplitRows(List<ObjectNode> features, List<ObjectNode> labels) {
        public SplitRows {
            features = ResearchDataJson.immutableNodes(features);
            labels = ResearchDataJson.immutableNodes(labels);
        }

        @Override
        public List<ObjectNode> features() {
            return ResearchDataJson.immutableNodes(features);
        }

        @Override
        public List<ObjectNode> labels() {
            return ResearchDataJson.immutableNodes(labels);
        }
    }

    public record ManifestOptions(
            String manifestId, List<ObjectNode> rows, List<ObjectNode> labelRows,
            List<ObjectNode> datasets, ObjectNode featureStore, String role, String source,
            String snapshotId, List<ObjectNode> gaps, boolean publicSource, ObjectNode lineage) {
        public ManifestOptions {
            rows = ResearchDataJson.immutableNodes(rows);
            labelRows = ResearchDataJson.immutableNodes(labelRows);
            datasets = ResearchDataJson.immutableNodes(datasets);
            featureStore = featureStore == null ? null : featureStore.deepCopy();
            role = valueOr(role, "FEATURE");
            source = valueOr(source, "unknown");
            gaps = ResearchDataJson.immutableNodes(gaps);
            lineage = lineage == null ? JsonHashes.mapper().createObjectNode() : lineage.deepCopy();
        }
    }

    public record FeatureSetOptions(
            String featureSetId, String dataManifestSha256, String featureCodeSha256,
            ArrayNode lineage, int warmupBars, ObjectNode coverage, ArrayNode partitions) {
    }

    public record LabelSetOptions(
            String labelSetId, String dataManifestSha256, String labelCodeSha256,
            ObjectNode horizon, ArrayNode partitions) {
    }

    public record ParquetArtifact(Path path, String sha256, long bytes) {
    }

    public record QueryOptions(Long from, Long to, List<String> assets) {
        public static QueryOptions all() {
            return new QueryOptions(null, null, null);
        }
    }

    public record SnapshotOptions(
            Path input, Path outputRoot, String datasetId, String asset, String venue,
            String instrument, String pitTier, String role, String format, String source,
            boolean publicSource, String adapterSha256, String codeSha256,
            String containerSha256, String configSha256, ObjectNode labelHorizon,
            String labelCodeSha256) {
        public SnapshotOptions {
            outputRoot = outputRoot == null ? Path.of("data/research-lake") : outputRoot;
            pitTier = valueOr(pitTier, "UNVERIFIED");
            role = valueOr(role, "FEATURE");
            format = valueOr(format, "parquet");
            source = valueOr(source, "public");
            labelHorizon = labelHorizon == null ? null : labelHorizon.deepCopy();
        }
    }

    public record SnapshotResult(
            String snapshotId, Path root, Path manifest, Path featureSet, Path labelSet,
            ObjectNode feature, ObjectNode labels) {
        public SnapshotResult {
            feature = feature == null ? null : feature.deepCopy();
            labels = labels == null ? null : labels.deepCopy();
        }
    }

    public record ValidationOptions(String phase, List<String> requiredAssets, Path root) {
        public ValidationOptions {
            phase = valueOr(phase, "DEVELOPMENT");
            requiredAssets = requiredAssets == null ? List.of() : List.copyOf(requiredAssets);
        }

        public static ValidationOptions development() {
            return new ValidationOptions("DEVELOPMENT", List.of(), null);
        }
    }

    public record CatalogResult(Path path, ObjectNode catalog) {
        public CatalogResult {
            catalog = catalog.deepCopy();
        }

        @Override
        public ObjectNode catalog() {
            return catalog.deepCopy();
        }
    }

    public static String canonicalHash(JsonNode value) {
        return canonicalHash(value, "content_sha256");
    }

    /** Exact own-hash behavior: clone, remove the named field, canonicalize, hash. */
    public static String canonicalHash(JsonNode value, String field) {
        JsonNode copy = value == null ? null : value.deepCopy();
        if (copy instanceof ObjectNode object) {
            object.remove(field);
        }
        return JsonHashes.canonicalSha256(copy);
    }

    public static ObjectNode withHash(ObjectNode value) {
        return withHash(value, "content_sha256");
    }

    public static ObjectNode withHash(ObjectNode value, String field) {
        ObjectNode copy = value.deepCopy();
        copy.put(field, canonicalHash(copy, field));
        return copy;
    }

    public static List<ObjectNode> readRows(Path path) {
        return ResearchDataRows.readRows(path);
    }

    public static long rowTime(JsonNode row) {
        return ResearchDataRows.rowTime(row);
    }

    public static long rowTime(JsonNode row, String name) {
        return ResearchDataRows.rowTime(row, name);
    }

    public static String findFutureLabel(JsonNode value) {
        return ResearchDataRows.findFutureLabel(value);
    }

    public static String findFutureLabel(JsonNode value, String path) {
        return ResearchDataRows.findFutureLabel(value, path);
    }

    public static List<ObjectNode> normalizeRows(List<? extends JsonNode> rows) {
        return normalizeRows(rows, NormalizeOptions.defaults());
    }

    public static List<ObjectNode> normalizeRows(List<? extends JsonNode> rows, NormalizeOptions options) {
        return ResearchDataRows.normalizeRows(rows, options);
    }

    public static SplitRows splitFeatureLabels(List<? extends JsonNode> rows) {
        return ResearchDataRows.splitFeatureLabels(rows);
    }

    public static ObjectNode buildManifest(ManifestOptions options) {
        return ResearchDataManifests.buildManifest(options);
    }

    public static ObjectNode buildFeatureSet(FeatureSetOptions options) {
        return ResearchDataManifests.buildFeatureSet(options);
    }

    public static ObjectNode buildLabelSet(LabelSetOptions options) {
        return ResearchDataManifests.buildLabelSet(options);
    }

    public static CatalogResult rebuildCatalog() {
        return ResearchDataSnapshots.rebuildCatalog();
    }

    public static CatalogResult rebuildCatalog(Path outputRoot) {
        return ResearchDataSnapshots.rebuildCatalog(outputRoot);
    }

    public static ParquetArtifact writeParquet(Path stagingJsonl, Path parquetPath) {
        return ResearchDataArtifacts.writeParquet(stagingJsonl, parquetPath);
    }

    public static List<ObjectNode> queryParquet(Path parquetPath) {
        return queryParquet(parquetPath, QueryOptions.all());
    }

    public static List<ObjectNode> queryParquet(Path parquetPath, QueryOptions options) {
        return ResearchDataArtifacts.queryParquet(parquetPath, options);
    }

    public static SnapshotResult snapshot(SnapshotOptions options) {
        return ResearchDataSnapshots.snapshot(options);
    }

    public static boolean validateManifest(ObjectNode manifest) {
        return validateManifest(manifest, ValidationOptions.development());
    }

    public static boolean validateManifest(ObjectNode manifest, ValidationOptions options) {
        return ResearchDataManifests.validateManifest(manifest, options);
    }

    private static String valueOr(String value, String fallback) {
        return value == null ? fallback : value;
    }
}

package com.tradinganalytics.marketdata.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.PathConfinement;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** PIT-safe research-lake primitives ported from {@code tools/research-data.mjs}. */
public final class ResearchData {
    public static final String DATASET_MANIFEST_SCHEMA = "strategy-data-manifest/2";
    public static final String FEATURE_SET_SCHEMA = "research-feature-set/1";
    public static final String LABEL_SET_SCHEMA = "research-label-set/1";
    public static final List<String> PIT_TIERS = List.of(
            "T0_IMMUTABLE_EVENT", "T1_PUBLICATION_VINTAGE", "T2_CAPTURED_AS_OF",
            "T3_REVISED_OR_PROXY", "UNVERIFIED");
    public static final List<String> CORE_CRYPTO_ASSETS = List.of(
            "btc", "eth", "sol", "bnb", "xrp", "ada", "link", "aave");
    public static final String DUCKDB_IMAGE = "docker.io/duckdb/duckdb:1.4.4@sha256:"
            + "2a5c5fb1bf8a7a93a43893b583cf15fcfebc0b8e02a39110593582907f96d8ad";
    public static final String DUCKDB_IMAGE_DIGEST =
            "2a5c5fb1bf8a7a93a43893b583cf15fcfebc0b8e02a39110593582907f96d8ad";

    // The Node producer digest is part of its observable lineage contract.
    private static final String RESEARCH_DATA_CODE_SHA256 =
            "bf5b0e130bc97ce5f497e5b400c4c782c7d834cbabaea4f954e5642d78727b7c";
    private static final Set<String> LABEL_FIELDS = Set.of(
            "outcome", "outcomes", "forward_return", "future_return", "forward_pnl",
            "future_pnl", "resolved_at", "resolution_bars", "label", "target");
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");
    private static final List<String> PARTITIONING = List.of(
            "dataset_version", "asset", "venue", "instrument", "timeframe",
            "utc_year", "utc_month");

    private ResearchData() {}

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
            features = immutableNodes(features);
            labels = immutableNodes(labels);
        }
        @Override public List<ObjectNode> features() { return immutableNodes(features); }
        @Override public List<ObjectNode> labels() { return immutableNodes(labels); }
    }

    public record ManifestOptions(
            String manifestId, List<ObjectNode> rows, List<ObjectNode> labelRows,
            List<ObjectNode> datasets, ObjectNode featureStore, String role, String source,
            String snapshotId, List<ObjectNode> gaps, boolean publicSource, ObjectNode lineage) {
        public ManifestOptions {
            rows = immutableNodes(rows);
            labelRows = immutableNodes(labelRows);
            datasets = immutableNodes(datasets);
            featureStore = featureStore == null ? null : featureStore.deepCopy();
            role = valueOr(role, "FEATURE");
            source = valueOr(source, "unknown");
            gaps = immutableNodes(gaps);
            lineage = lineage == null ? JsonHashes.mapper().createObjectNode() : lineage.deepCopy();
        }
    }

    public record FeatureSetOptions(
            String featureSetId, String dataManifestSha256, String featureCodeSha256,
            ArrayNode lineage, int warmupBars, ObjectNode coverage, ArrayNode partitions) {}

    public record LabelSetOptions(
            String labelSetId, String dataManifestSha256, String labelCodeSha256,
            ObjectNode horizon, ArrayNode partitions) {}

    public record ParquetArtifact(Path path, String sha256, long bytes) {}

    public record QueryOptions(Long from, Long to, List<String> assets) {
        public static QueryOptions all() { return new QueryOptions(null, null, null); }
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
        public CatalogResult { catalog = catalog.deepCopy(); }
        @Override public ObjectNode catalog() { return catalog.deepCopy(); }
    }

    public static String canonicalHash(JsonNode value) {
        return canonicalHash(value, "content_sha256");
    }

    /** Exact own-hash behavior: clone, remove the named field, canonicalize, hash. */
    public static String canonicalHash(JsonNode value, String field) {
        JsonNode copy = value == null ? null : value.deepCopy();
        if (copy instanceof ObjectNode object) object.remove(field);
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
        try {
            return parseRows(path, Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw failure(error.getMessage(), error);
        }
    }

    public static long rowTime(JsonNode row) { return rowTime(row, "event_time"); }

    public static long rowTime(JsonNode row, String name) {
        JsonNode raw = firstPresent(row, name, "time", "timestamp", "open_time");
        if (raw == null || raw.isNull()) throw failure("row " + name + " must be a valid timestamp");
        if (raw.isNumber()) {
            double value = raw.asDouble();
            if (!Double.isFinite(value)) throw failure("row " + name + " must be a valid timestamp");
            return (long) value;
        }
        Long parsed = parseTime(raw.asText());
        if (parsed == null) throw failure("row " + name + " must be a valid timestamp");
        return parsed;
    }

    public static String findFutureLabel(JsonNode value) { return findFutureLabel(value, ""); }

    public static String findFutureLabel(JsonNode value, String path) {
        if (value == null || !value.isContainerNode()) return null;
        if (value.isArray()) {
            for (int index = 0; index < value.size(); index++) {
                String nested = findFutureLabel(value.get(index), path.isEmpty()
                        ? String.valueOf(index) : path + "." + index);
                if (nested != null) return nested;
            }
            return null;
        }
        var fields = value.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String childPath = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
            if (LABEL_FIELDS.contains(field.getKey().toLowerCase(Locale.ROOT))) return childPath;
            String nested = findFutureLabel(field.getValue(), childPath);
            if (nested != null) return nested;
        }
        return null;
    }

    public static List<ObjectNode> normalizeRows(List<? extends JsonNode> rows) {
        return normalizeRows(rows, NormalizeOptions.defaults());
    }

    public static List<ObjectNode> normalizeRows(
            List<? extends JsonNode> rows, NormalizeOptions options) {
        Objects.requireNonNull(rows, "rows");
        options = options == null ? NormalizeOptions.defaults() : options;
        if (!PIT_TIERS.contains(options.pitTier())) {
            throw failure("unknown PIT tier " + options.pitTier());
        }
        List<ObjectNode> output = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            JsonNode raw = rows.get(index);
            if (raw == null || !raw.isObject()) throw failure("row " + index + " must be an object");
            if ("FEATURE".equals(options.role())) {
                String leaked = findFutureLabel(raw);
                if (leaked != null) throw failure("feature row contains future-label field " + leaked);
            }
            long event = rowTime(raw);
            JsonNode availabilityRaw = "LABEL".equals(options.role())
                    ? firstPresent(raw, "resolved_at", "resolution_time", "label_available_at")
                    : firstPresent(raw, "availability_time", "available_at", "as_of",
                            "close_time", "end_time");
            boolean eventNative = Pattern.compile(
                    "(?:trade|funding|liquidation|exchange[_-]?event|settlement)",
                    Pattern.CASE_INSENSITIVE).matcher(options.source()).find();
            if (availabilityRaw == null && !eventNative) {
                throw failure("row " + index + " availability_time is required for non-event-native source "
                        + options.source());
            }
            long availability = availabilityRaw == null ? event
                    : rowTime(JsonHashes.mapper().createObjectNode().set("time", availabilityRaw));
            if (availability < event && !"event_time".equals(options.availabilityPolicy())) {
                throw failure("row " + index + " availability_time precedes event_time");
            }
            String asset = textOr(raw.get("asset"), options.asset());
            asset = asset == null ? "" : asset.toLowerCase(Locale.ROOT);
            String assetClass = textOr(raw.get("asset_class"),
                    "CONTEXT".equals(options.role()) ? "context" : "crypto");
            assetClass = assetClass.toLowerCase(Locale.ROOT);
            if (asset.isEmpty()) throw failure("row " + index + " asset is required");
            boolean context = "CONTEXT".equals(options.role()) || "context".equals(assetClass);
            if (!context && !"crypto".equals(assetClass)) {
                throw failure("row " + index + " non-crypto data cannot enter " + options.role());
            }
            ObjectNode normalized = ((ObjectNode) raw).deepCopy();
            normalized.put("asset", asset);
            normalized.put("asset_class", assetClass);
            putNullable(normalized, "venue", nodeOrText(raw.get("venue"), options.venue()));
            putNullable(normalized, "instrument", nodeOrText(raw.get("instrument"), options.instrument()));
            putNullable(normalized, "timeframe", nodeOrText(raw.get("timeframe"), options.timeframe()));
            normalized.put("event_time", event);
            normalized.put("availability_time", availability);
            normalized.put("dataset_id", options.datasetId());
            normalized.set("dataset_version", raw.hasNonNull("dataset_version")
                    ? raw.get("dataset_version").deepCopy()
                    : JsonHashes.mapper().getNodeFactory().textNode(options.datasetId()));
            normalized.put("source", options.source());
            normalized.put("pit_tier", options.pitTier());
            normalized.put("revision_status", raw.hasNonNull("revision_status")
                    ? raw.get("revision_status").asText()
                    : "T3_REVISED_OR_PROXY".equals(options.pitTier())
                            ? "REVISED_OR_PROXY" : "ORIGINAL");
            normalized.put("role", options.role());
            output.add(normalized);
        }
        output.sort(Comparator.comparingLong((ObjectNode row) -> row.path("event_time").asLong())
                .thenComparing(row -> row.path("asset").asText()));
        return output;
    }

    public static SplitRows splitFeatureLabels(List<? extends JsonNode> rows) {
        List<ObjectNode> features = new ArrayList<>();
        List<ObjectNode> labels = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.isObject()) throw failure("row must be an object");
            ObjectNode feature = JsonHashes.mapper().createObjectNode();
            ObjectNode label = JsonHashes.mapper().createObjectNode();
            row.fields().forEachRemaining(field -> {
                (LABEL_FIELDS.contains(field.getKey().toLowerCase(Locale.ROOT)) ? label : feature)
                        .set(field.getKey(), field.getValue().deepCopy());
            });
            if (!label.isEmpty()) {
                copyIfPresent(row, label, "event_time", "asset", "dataset_id");
                labels.add(label);
            }
            features.add(feature);
        }
        return new SplitRows(features, labels);
    }

    public static ObjectNode buildManifest(ManifestOptions options) {
        options = options == null
                ? new ManifestOptions(null, List.of(), List.of(), List.of(), null,
                        "FEATURE", "unknown", null, List.of(), true, null)
                : options;
        ArrayNode datasetRows = options.datasets().isEmpty()
                ? deriveDatasets(options.rows(), options.source(), options.publicSource())
                : JsonHashes.mapper().valueToTree(options.datasets());
        ArrayNode labelDatasetRows = deriveDatasets(
                options.labelRows(), options.source(), options.publicSource());
        ObjectNode coverage = coverageSummary(datasetRows);
        ObjectNode labelCoverage = coverageSummary(labelDatasetRows);
        ObjectNode lineage = normalizedLineage(options.lineage(), options.source(), options.role(),
                options.publicSource());
        ObjectNode featureStore = options.featureStore() == null ? null : options.featureStore();
        ArrayNode gaps = JsonHashes.mapper().valueToTree(options.gaps());
        ObjectNode identity = JsonHashes.mapper().createObjectNode();
        identity.set("datasetRows", datasetRows);
        identity.set("labelDatasetRows", labelDatasetRows);
        identity.set("gaps", gaps);
        identity.set("featureStore", featureStore == null
                ? JsonHashes.mapper().nullNode() : featureStore);
        identity.set("lineage", lineage);
        String manifestId = options.manifestId() == null
                ? "snapshot-" + JsonHashes.canonicalSha256(identity).substring(0, 16)
                : options.manifestId();

        ObjectNode manifest = JsonHashes.mapper().createObjectNode();
        manifest.put("schema", DATASET_MANIFEST_SCHEMA);
        manifest.put("manifest_id", manifestId);
        putNullable(manifest, "snapshot_id", options.snapshotId());
        manifest.put("role", options.role());
        manifest.put("source", options.source());
        manifest.put("public_source", options.publicSource());
        manifest.set("partitioning", JsonHashes.mapper().valueToTree(PARTITIONING));
        manifest.set("lineage", lineage);
        manifest.set("feature_store", featureStore == null
                ? JsonHashes.mapper().nullNode() : featureStore.deepCopy());
        manifest.set("datasets", datasetRows);
        manifest.set("label_datasets", labelDatasetRows);
        manifest.set("coverage_summary", coverage);
        manifest.set("label_coverage_summary", labelCoverage);
        manifest.set("gaps", gaps);
        ObjectNode dataRoot = JsonHashes.mapper().createObjectNode();
        dataRoot.set("datasetRows", datasetRows);
        dataRoot.set("labelDatasetRows", labelDatasetRows);
        dataRoot.set("coverageSummary", coverage);
        dataRoot.set("labelCoverageSummary", labelCoverage);
        dataRoot.set("gaps", gaps);
        dataRoot.set("featureStore", featureStore == null
                ? JsonHashes.mapper().nullNode() : featureStore);
        dataRoot.set("lineage", lineage);
        manifest.put("data_root_sha256", JsonHashes.canonicalSha256(dataRoot));
        boolean authoritative = Set.of("FEATURE", "LABEL").contains(options.role());
        for (JsonNode dataset : concat(datasetRows, labelDatasetRows)) {
            authoritative &= Set.of("PIT_SAFE", "VERIFIED", "COMPLETED_BAR")
                    .contains(dataset.path("point_in_time_status").asText().toUpperCase(Locale.ROOT));
        }
        manifest.put("authoritative", authoritative);
        manifest.putNull("content_sha256");
        manifest.put("content_sha256", manifestOwnHash(manifest));
        return manifest;
    }

    public static ObjectNode buildFeatureSet(FeatureSetOptions options) {
        if (options == null || !isHash(options.dataManifestSha256())) {
            throw failure("feature set requires data_manifest_sha256");
        }
        if (!isHash(options.featureCodeSha256())) {
            throw failure("feature set requires feature_code_sha256");
        }
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("schema", FEATURE_SET_SCHEMA);
        value.put("feature_set_id", options.featureSetId() == null
                ? "features-" + options.dataManifestSha256().substring(0, 12)
                : options.featureSetId());
        value.put("data_manifest_sha256", options.dataManifestSha256());
        value.put("feature_code_sha256", options.featureCodeSha256());
        value.set("lineage", options.lineage() == null
                ? JsonHashes.mapper().createArrayNode() : options.lineage().deepCopy());
        value.put("warmup_bars", options.warmupBars());
        value.set("coverage", options.coverage() == null
                ? JsonHashes.mapper().createObjectNode() : options.coverage().deepCopy());
        value.set("partitions", options.partitions() == null
                ? JsonHashes.mapper().createArrayNode() : options.partitions().deepCopy());
        value.put("labels_allowed", false);
        return withHash(value);
    }

    public static ObjectNode buildLabelSet(LabelSetOptions options) {
        if (options == null || !isHash(options.dataManifestSha256())) {
            throw failure("label set requires data_manifest_sha256");
        }
        if (!isHash(options.labelCodeSha256())) {
            throw failure("label set requires label_code_sha256");
        }
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("schema", LABEL_SET_SCHEMA);
        value.put("label_set_id", options.labelSetId() == null
                ? "labels-" + options.dataManifestSha256().substring(0, 12)
                : options.labelSetId());
        value.put("data_manifest_sha256", options.dataManifestSha256());
        value.put("label_code_sha256", options.labelCodeSha256());
        value.set("horizon", options.horizon() == null
                ? JsonHashes.mapper().createObjectNode() : options.horizon().deepCopy());
        ObjectNode derivation = value.putObject("derivation");
        derivation.put("label_code_sha256", options.labelCodeSha256());
        derivation.put("source_manifest_sha256", options.dataManifestSha256());
        derivation.put("availability", "resolved_at_or_frozen_horizon_end");
        derivation.put("resolution_field", "resolved_at");
        derivation.put("predictor_eligible", false);
        value.set("partitions", options.partitions() == null
                ? JsonHashes.mapper().createArrayNode() : options.partitions().deepCopy());
        value.put("predictor_eligible", false);
        return withHash(value);
    }

    public static CatalogResult rebuildCatalog() {
        return rebuildCatalog(Path.of("data/research-lake"));
    }

    public static CatalogResult rebuildCatalog(Path outputRoot) {
        Path requestedRoot = outputRoot.toAbsolutePath().normalize();
        ArrayNode snapshots = JsonHashes.mapper().createArrayNode();
        try {
            Files.createDirectories(requestedRoot);
            Path root = PathConfinement.requireRealDirectory(requestedRoot, "research lake root");
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                List<Path> entries = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                    stream.forEach(entries::add);
                }
                entries.sort(Comparator.comparing(path -> path.getFileName().toString()));
                for (Path entry : entries) {
                    if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(entry)) continue;
                    Path manifestPath = entry.resolve("manifests/dataset-manifest.json");
                    if (!Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)) continue;
                    ObjectNode manifest = parseObject(Files.readAllBytes(manifestPath));
                    validateManifest(manifest, new ValidationOptions(
                            "DEVELOPMENT", List.of(), entry));
                    ObjectNode row = snapshots.addObject();
                    row.put("snapshot_id", manifest.path("snapshot_id").isNull()
                            ? entry.getFileName().toString()
                            : manifest.path("snapshot_id").asText(entry.getFileName().toString()));
                    row.put("root", entry.getFileName().toString());
                    row.put("manifest_sha256", manifest.path("content_sha256").asText());
                    row.put("data_root_sha256", manifest.path("data_root_sha256").asText());
                    ArrayNode datasets = row.putArray("datasets");
                    for (JsonNode dataset : concat(
                            manifest.path("datasets"), manifest.path("label_datasets"))) {
                        ObjectNode summary = datasets.addObject();
                        copyIfPresent(dataset, summary, "dataset_id", "asset", "asset_class",
                                "row_count", "min_time", "max_time", "coverage");
                        summary.put("role", dataset.hasNonNull("role")
                                ? dataset.path("role").asText() : manifest.path("role").asText());
                    }
                }
            }
            ObjectNode catalog = JsonHashes.mapper().createObjectNode();
            catalog.put("schema", "research-lake-catalog/1");
            catalog.set("snapshots", snapshots);
            catalog.putNull("content_sha256");
            catalog.put("content_sha256", canonicalHash(catalog));
            Path path = root.resolve("catalog.json");
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                PathConfinement.validateSinglyLinkedFile(path, "research lake catalog");
                ObjectNode previous = parseObject(Files.readAllBytes(path));
                if (!previous.path("content_sha256").asText().equals(canonicalHash(previous))) {
                    throw failure("research lake catalog retained-hash tampering");
                }
            }
            atomicReplace(path, prettyBytes(catalog));
            return new CatalogResult(path, catalog);
        } catch (IOException error) {
            throw failure(error.getMessage(), error);
        }
    }

    /** Creates immutable ZSTD Parquet through the in-process pinned DuckDB JDBC engine. */
    public static ParquetArtifact writeParquet(Path stagingJsonl, Path parquetPath) {
        Path input = stagingJsonl.toAbsolutePath().normalize();
        Path output = parquetPath.toAbsolutePath().normalize();
        try {
            Files.createDirectories(output.getParent());
            String digest = JsonHashes.sha256(input);
            Path candidate = output.resolveSibling("." + output.getFileName() + "."
                    + digest.substring(0, 16) + ".tmp");
            Files.deleteIfExists(candidate);
            try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
                    Statement statement = connection.createStatement()) {
                String sql = "COPY (SELECT * FROM read_json_auto('" + sql(input)
                        + "', union_by_name=true)) TO '" + sql(candidate)
                        + "' (FORMAT PARQUET, COMPRESSION ZSTD)";
                statement.execute(sql);
            }
            String candidateHash = JsonHashes.sha256(candidate);
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                PathConfinement.validateSinglyLinkedFile(output, "immutable Parquet artifact");
                if (!JsonHashes.sha256(output).equals(candidateHash)) {
                    throw failure("immutable Parquet artifact collision: " + output);
                }
                Files.delete(candidate);
            } else {
                moveNoReplace(candidate, output);
            }
            return new ParquetArtifact(parquetPath, candidateHash, Files.size(output));
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw failure("DuckDB Parquet conversion failed; JSONL staging remains at "
                    + stagingJsonl + ": " + error.getMessage(), error);
        }
    }

    public static List<ObjectNode> queryParquet(Path parquetPath) {
        return queryParquet(parquetPath, QueryOptions.all());
    }

    public static List<ObjectNode> queryParquet(Path parquetPath, QueryOptions options) {
        Path input = parquetPath.toAbsolutePath().normalize();
        if (!input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".parquet")) {
            throw failure("authoritative query requires a Parquet path; JSONL is staging/debug only");
        }
        if (!Files.exists(input, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("Parquet input does not exist: " + input);
        }
        PathConfinement.validateSinglyLinkedFile(input, "Parquet input");
        options = options == null ? QueryOptions.all() : options;
        List<String> clauses = new ArrayList<>();
        if (options.from() != null) clauses.add("event_time >= " + options.from());
        if (options.to() != null) clauses.add("event_time <= " + options.to());
        if (options.assets() != null && !options.assets().isEmpty()) {
            clauses.add("lower(cast(asset as varchar)) IN (" + options.assets().stream()
                    .map(asset -> "'" + asset.toLowerCase(Locale.ROOT).replace("'", "''") + "'")
                    .reduce((left, right) -> left + "," + right).orElse("") + ")");
        }
        String timeColumn = parquetColumns(input).contains("event_time") ? "event_time"
                : parquetColumns(input).contains("decision_time") ? "decision_time" : null;
        if ((!clauses.isEmpty() || options.from() != null || options.to() != null) && timeColumn == null) {
            throw failure("authoritative Parquet query requires event_time or decision_time");
        }
        if (timeColumn != null && !"event_time".equals(timeColumn)) {
            clauses.replaceAll(clause -> clause.replace("event_time", timeColumn));
        }
        String query = "SELECT * FROM read_parquet('" + sql(input) + "')"
                + (clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses))
                + (timeColumn == null ? "" : " ORDER BY " + timeColumn);
        List<ObjectNode> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(query)) {
            ResultSetMetaData metadata = result.getMetaData();
            while (result.next()) {
                ObjectNode row = JsonHashes.mapper().createObjectNode();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    putJdbc(row, metadata.getColumnLabel(index), result.getObject(index));
                }
                rows.add(row);
            }
            return rows;
        } catch (Exception error) {
            throw failure("authoritative Parquet query requires pinned DuckDB ("
                    + error.getMessage() + ")", error);
        }
    }

    private static Set<String> parquetColumns(Path input) {
        Set<String> columns = new LinkedHashSet<>();
        String query = "DESCRIBE SELECT * FROM read_parquet('" + sql(input) + "')";
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(query)) {
            while (result.next()) columns.add(result.getString("column_name"));
            return columns;
        } catch (Exception error) {
            throw failure("authoritative Parquet schema requires pinned DuckDB ("
                    + error.getMessage() + ")", error);
        }
    }

    public static SnapshotResult snapshot(SnapshotOptions options) {
        if (options == null || options.input() == null) throw failure("snapshot requires input");
        String datasetId = options.datasetId() == null
                ? stripExtension(options.input().getFileName().toString()) : options.datasetId();
        validateDatasetId(datasetId);
        byte[] inputBytes;
        try { inputBytes = Files.readAllBytes(options.input()); }
        catch (IOException error) { throw failure(error.getMessage(), error); }
        List<ObjectNode> raw = parseRows(options.input(), new String(inputBytes, StandardCharsets.UTF_8));
        SplitRows split = "FEATURE".equals(options.role())
                ? splitForSnapshot(raw, options.labelHorizon(), "4h")
                : "LABEL".equals(options.role())
                        ? labelOnly(raw, options.labelHorizon(), "4h")
                        : new SplitRows(raw, List.of());
        NormalizeOptions featureOptions = new NormalizeOptions(
                options.asset(), options.venue(), options.instrument(), "4h", datasetId,
                options.pitTier(), "CONTEXT".equals(options.role()) ? "CONTEXT" : "FEATURE",
                options.source(), "completed_bar");
        NormalizeOptions labelOptions = new NormalizeOptions(
                options.asset(), options.venue(), options.instrument(), "4h", datasetId,
                options.pitTier(), "LABEL", options.source(), "completed_bar");
        List<ObjectNode> features = split.features().isEmpty()
                ? List.of() : normalizeRows(split.features(), featureOptions);
        List<ObjectNode> labels = split.labels().isEmpty()
                ? List.of() : normalizeRows(split.labels(), labelOptions);
        List<ObjectNode> normalized = new ArrayList<>(features);
        normalized.addAll(labels);
        normalized.sort(Comparator.comparingLong(row -> row.path("event_time").asLong()));
        ObjectNode lineage = normalizedLineageForSnapshot(options, datasetId, normalized);
        ObjectNode identity = JsonHashes.mapper().createObjectNode();
        identity.put("dataset_id", datasetId);
        identity.put("input_bytes_sha256", JsonHashes.sha256(inputBytes));
        identity.put("input_sha256", JsonHashes.canonicalSha256(raw));
        identity.put("normalized_sha256", JsonHashes.canonicalSha256(normalized));
        putNullable(identity, "asset", options.asset());
        putNullable(identity, "venue", options.venue());
        putNullable(identity, "instrument", options.instrument());
        identity.put("pit_tier", options.pitTier());
        identity.put("role", options.role());
        identity.put("format", options.format());
        identity.put("source", options.source());
        identity.put("public_source", options.publicSource());
        identity.set("label_horizon", options.labelHorizon() == null
                ? JsonHashes.mapper().nullNode() : options.labelHorizon());
        putNullable(identity, "label_code_sha256", options.labelCodeSha256());
        identity.set("lineage", lineage);
        String snapshotId = datasetId + "-" + JsonHashes.canonicalSha256(identity).substring(0, 16);
        try {
            Path requestedLake = options.outputRoot().toAbsolutePath().normalize();
            Files.createDirectories(requestedLake);
            Path lake = PathConfinement.requireRealDirectory(requestedLake, "research lake root");
            Path requestedRoot = lake.resolve(snapshotId);
            Files.createDirectories(requestedRoot);
            Path root = PathConfinement.requireRealDirectory(requestedRoot, "snapshot root");
            validateExistingTree(root);
            Path identityPath = root.resolve("snapshot-identity.json");
            if (Files.exists(identityPath, LinkOption.NOFOLLOW_LINKS)) {
                PathConfinement.validateSinglyLinkedFile(identityPath, "snapshot identity");
                JsonNode previous = JsonHashes.parse(Files.readAllBytes(identityPath), "snapshot identity");
                if (!JsonHashes.canonicalSha256(previous).equals(JsonHashes.canonicalSha256(identity))) {
                    throw failure("immutable snapshot root collision: " + root);
                }
            } else {
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
                    if (entries.iterator().hasNext()) {
                        throw failure("existing snapshot root lacks identity receipt; refusing overwrite: "
                                + root);
                    }
                }
                writeNew(identityPath, prettyBytes(identity));
            }
            ObjectNode rawArtifact = writeJsonl(root.resolve("raw/" + datasetId + ".jsonl"), raw);
            ObjectNode normalizedArtifact = writeJsonl(
                    root.resolve("normalized/" + datasetId + ".jsonl"), normalized);
            List<ObjectNode> quality = qualityRows(normalized);
            ObjectNode qualityArtifact = writeJsonl(
                    root.resolve("quality/" + datasetId + ".jsonl"), quality);
            List<ObjectNode> gaps = quality.stream().filter(row -> row.path("gap_bars").asInt() > 0)
                    .map(row -> {
                        ObjectNode gap = JsonHashes.mapper().createObjectNode();
                        copyIfPresent(row, gap, "dataset_id", "asset", "event_time", "role",
                                "gap_bars", "max_gap_bars");
                        return gap;
                    }).toList();
            ObjectNode feature = writeLayer(root, datasetId, "features", features, options.format());
            ObjectNode label = writeLayer(root, datasetId, "labels", labels, options.format());
            ArrayNode featurePartitions = writePartitions(
                    root, datasetId, "features/partitions", features, options.format());
            ArrayNode labelPartitions = writePartitions(
                    root, datasetId, "labels/partitions", labels, options.format());
            ObjectNode featureStore = feature == null
                    ? JsonHashes.mapper().createObjectNode() : feature.deepCopy();
            featureStore.set("labels", label == null ? JsonHashes.mapper().nullNode() : label);
            featureStore.set("partitions", featurePartitions);
            featureStore.set("label_partitions", labelPartitions);
            featureStore.put("input_bytes_sha256", JsonHashes.sha256(inputBytes));
            featureStore.put("raw_path", relative(root, Path.of(rawArtifact.path("path").asText())));
            featureStore.put("raw_sha256", rawArtifact.path("sha256").asText());
            featureStore.put("normalized_path",
                    relative(root, Path.of(normalizedArtifact.path("path").asText())));
            featureStore.put("normalized_sha256", normalizedArtifact.path("sha256").asText());
            featureStore.put("quality_path",
                    relative(root, Path.of(qualityArtifact.path("path").asText())));
            featureStore.put("quality_sha256", qualityArtifact.path("sha256").asText());
            ObjectNode manifest = buildManifest(new ManifestOptions(
                    snapshotId, features, labels, List.of(), featureStore, options.role(),
                    options.source(), snapshotId, gaps, options.publicSource(), lineage));
            Path manifests = root.resolve("manifests");
            Files.createDirectories(manifests);
            Path manifestPath = manifests.resolve("dataset-manifest.json");
            writeImmutableJson(manifestPath, manifest);
            Path featureSetPath = manifests.resolve("feature-set.json");
            if (!features.isEmpty()) {
                ArrayNode featureLineage = JsonHashes.mapper().createArrayNode();
                ObjectNode first = featureLineage.addObject();
                first.put("dataset_id", datasetId);
                first.put("source", options.source());
                first.put("pit_tier", options.pitTier());
                writeImmutableJson(featureSetPath, buildFeatureSet(new FeatureSetOptions(
                        snapshotId + "-features", manifest.path("content_sha256").asText(),
                        JsonHashes.sha256("research-data/feature-normalize/v1"), featureLineage,
                        0, null, featurePartitions)));
            }
            Path labelSetPath = null;
            if (!labels.isEmpty()) {
                labelSetPath = manifests.resolve("label-set.json");
                ObjectNode horizon = options.labelHorizon() == null
                        ? JsonHashes.mapper().createObjectNode().put("bars", 1).put("unit", "bars")
                        : options.labelHorizon();
                writeImmutableJson(labelSetPath, buildLabelSet(new LabelSetOptions(
                        snapshotId + "-labels", manifest.path("content_sha256").asText(),
                        options.labelCodeSha256() == null
                                ? JsonHashes.sha256("research-data/labels/v1")
                                : options.labelCodeSha256(), horizon, labelPartitions)));
            }
            rebuildCatalog(options.outputRoot());
            return new SnapshotResult(snapshotId, root, manifestPath,
                    features.isEmpty() ? null : featureSetPath, labelSetPath, feature, label);
        } catch (IOException error) {
            throw failure(error.getMessage(), error);
        }
    }

    public static boolean validateManifest(ObjectNode manifest) {
        return validateManifest(manifest, ValidationOptions.development());
    }

    public static boolean validateManifest(ObjectNode manifest, ValidationOptions options) {
        options = options == null ? ValidationOptions.development() : options;
        if (manifest == null || !DATASET_MANIFEST_SCHEMA.equals(manifest.path("schema").asText())) {
            throw failure("unsupported dataset manifest "
                    + (manifest == null ? "missing" : manifest.path("schema").asText("missing")));
        }
        if (!manifest.path("content_sha256").asText().equals(manifestOwnHash(manifest))) {
            throw failure("dataset manifest content hash mismatch");
        }
        ArrayNode datasets = array(manifest.get("datasets"));
        ArrayNode labels = array(manifest.get("label_datasets"));
        if (datasets.isEmpty() && labels.isEmpty()) {
            throw failure("dataset manifest must contain datasets or label_datasets");
        }
        boolean development = "DEVELOPMENT".equals(options.phase());
        if (!development && !manifest.path("authoritative").asBoolean(false)) {
            throw failure("dataset manifest is not authoritative for " + options.phase());
        }
        JsonNode store = manifest.path("feature_store");
        JsonNode primary = "parquet".equals(store.path("format").asText())
                ? store : store.path("labels");
        if (!development && (!primary.isObject()
                || !"parquet".equalsIgnoreCase(primary.path("format").asText()))) {
            throw failure("JSONL/staging data cannot validate as authoritative "
                    + options.phase() + " evidence");
        }
        ObjectNode expected = JsonHashes.mapper().createObjectNode();
        expected.set("datasetRows", datasets);
        expected.set("labelDatasetRows", labels);
        expected.set("coverageSummary", manifest.get("coverage_summary"));
        expected.set("labelCoverageSummary", manifest.get("label_coverage_summary"));
        expected.set("gaps", manifest.has("gaps") ? manifest.get("gaps")
                : JsonHashes.mapper().createArrayNode());
        expected.set("featureStore", manifest.get("feature_store"));
        expected.set("lineage", manifest.get("lineage"));
        if (!manifest.path("data_root_sha256").asText()
                .equals(JsonHashes.canonicalSha256(expected))) {
            throw failure("dataset manifest data_root_sha256 mismatch");
        }
        if (options.root() != null) validateArtifacts(store, options.root(), development);
        Set<String> required = new LinkedHashSet<>();
        options.requiredAssets().forEach(asset -> required.add(asset.toLowerCase(Locale.ROOT)));
        Set<String> present = new HashSet<>();
        for (JsonNode dataset : concat(datasets, labels)) {
            String id = dataset.path("dataset_id").asText();
            if (id.isEmpty() || !dataset.path("row_count").canConvertToInt()
                    || dataset.path("source_sha256").asText().isEmpty()) {
                throw failure("dataset " + (id.isEmpty() ? "?" : id) + " is incomplete");
            }
            String asset = dataset.path("asset").asText().toLowerCase(Locale.ROOT);
            if ("doge".equals(asset)) throw failure("DOGE is excluded from the v3 research universe");
            boolean context = "context".equalsIgnoreCase(dataset.path("asset_class").asText());
            if (!required.isEmpty() && !required.contains(asset)
                    && !"CONTEXT".equals(manifest.path("role").asText()) && !context) {
                throw failure("dataset asset " + asset + " is not in required crypto universe");
            }
            if (!development && !Set.of("PIT_SAFE", "VERIFIED", "COMPLETED_BAR").contains(
                    dataset.path("point_in_time_status").asText().toUpperCase(Locale.ROOT))) {
                throw failure("dataset " + id + " is unsafe PIT for " + options.phase());
            }
            if (!development && Set.of("REVISED", "NON_PIT", "UNKNOWN").contains(
                    dataset.path("revision_status").asText().toUpperCase(Locale.ROOT))) {
                throw failure("dataset " + id + " is revised/non-PIT for " + options.phase());
            }
            if (!context && datasetsContains(datasets, dataset)) present.add(asset);
        }
        if (!required.isEmpty()) {
            List<String> missing = required.stream().filter(asset -> !present.contains(asset)).toList();
            if (!missing.isEmpty()) throw failure(
                    "dataset manifest is missing required crypto assets: " + String.join(", ", missing));
        }
        return true;
    }

    private static ArrayNode deriveDatasets(
            List<ObjectNode> rows, String source, boolean publicSource) {
        Map<String, List<ObjectNode>> groups = new LinkedHashMap<>();
        for (ObjectNode row : rows) {
            String key = String.join("|",
                    row.path("dataset_id").asText("dataset"),
                    row.path("dataset_version").asText(row.path("dataset_id").asText("dataset")),
                    row.path("asset").asText(), row.path("venue").asText(),
                    row.path("instrument").asText(), row.path("timeframe").asText("4h"));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        ArrayNode output = JsonHashes.mapper().createArrayNode();
        for (Map.Entry<String, List<ObjectNode>> entry : groups.entrySet()) {
            String[] key = entry.getKey().split("\\|", -1);
            List<ObjectNode> values = new ArrayList<>(entry.getValue());
            values.sort(Comparator.comparingLong(row -> row.path("event_time").asLong()));
            long interval = timeframeMs(values.get(0).path("timeframe").asText("4h"));
            long first = values.get(0).path("event_time").asLong();
            long last = values.get(values.size() - 1).path("event_time").asLong();
            long expected = values.size() > 1 ? ((last - first) / interval) + 1 : values.size();
            int gapCount = 0;
            int maxGap = 0;
            for (int index = 1; index < values.size(); index++) {
                int gap = Math.max(0, (int) Math.round(
                        (double) (values.get(index).path("event_time").asLong()
                                - values.get(index - 1).path("event_time").asLong()) / interval) - 1);
                gapCount += gap;
                maxGap = Math.max(maxGap, gap);
            }
            ObjectNode dataset = output.addObject();
            dataset.put("dataset_id", key[0]);
            dataset.put("dataset_version", key[1].isEmpty() ? key[0] : key[1]);
            dataset.put("asset", key[2]);
            dataset.put("asset_class", values.get(0).path("asset_class").asText("crypto"));
            putNullable(dataset, "venue", key[3].isEmpty() ? null : key[3]);
            putNullable(dataset, "instrument_id", key[4].isEmpty() ? null : key[4]);
            putNullable(dataset, "timeframe", key[5].isEmpty() ? null : key[5]);
            dataset.put("row_count", values.size());
            dataset.put("min_time", first);
            dataset.put("max_time", last);
            dataset.put("source_sha256", JsonHashes.canonicalSha256(values));
            dataset.put("availability_time_policy", "availability_time <= decision_time");
            boolean pitSafe = values.stream().allMatch(row -> Set.of(
                    "T0_IMMUTABLE_EVENT", "T1_PUBLICATION_VINTAGE", "T2_CAPTURED_AS_OF")
                    .contains(row.path("pit_tier").asText()));
            dataset.put("point_in_time_status", pitSafe ? "PIT_SAFE" : "NON_PIT");
            dataset.put("revision_status", values.stream().anyMatch(row ->
                    "REVISED_OR_PROXY".equals(row.path("revision_status").asText()))
                    ? "REVISED" : "ORIGINAL");
            Set<String> tiers = new java.util.TreeSet<>();
            values.forEach(row -> tiers.add(row.path("pit_tier").asText()));
            dataset.set("pit_tiers", JsonHashes.mapper().valueToTree(tiers));
            ObjectNode coverage = dataset.putObject("coverage");
            coverage.put("expected_rows", expected);
            coverage.put("observed_rows", values.size());
            coverage.put("observed_fraction", expected == 0 ? 0 : (double) values.size() / expected);
            coverage.put("gap_count", gapCount);
            coverage.put("max_gap_bars", maxGap);
            coverage.put("minimum_fraction", 0.95);
            coverage.put("frozen", true);
            dataset.put("source", source);
            dataset.put("public_source", publicSource);
        }
        return output;
    }

    private static ObjectNode coverageSummary(ArrayNode rows) {
        ObjectNode result = JsonHashes.mapper().createObjectNode();
        for (String kind : List.of("price", "derivatives", "funding")) {
            long expected = 0;
            long observed = 0;
            for (JsonNode row : rows) {
                String instrument = row.path("instrument_id").asText().toLowerCase(Locale.ROOT);
                String source = row.path("source").asText().toLowerCase(Locale.ROOT);
                boolean selected = switch (kind) {
                    case "price" -> "spot".equals(instrument)
                            || ("crypto".equalsIgnoreCase(row.path("asset_class").asText())
                                    && !instrument.contains("perp"));
                    case "derivatives" -> instrument.contains("perp") || instrument.contains("future");
                    default -> source.contains("funding");
                };
                if (selected) {
                    expected += row.path("coverage").path("expected_rows").asLong();
                    observed += row.path("coverage").path("observed_rows").asLong();
                }
            }
            if (expected == 0) result.putNull(kind + "_fraction");
            else result.put(kind + "_fraction", (double) observed / expected);
        }
        return result;
    }

    private static ObjectNode normalizedLineage(
            ObjectNode supplied, String source, String role, boolean publicSource) {
        ObjectNode lineage = JsonHashes.mapper().createObjectNode();
        lineage.put("adapter_sha256", textOr(supplied.get("adapter_sha256"),
                JsonHashes.sha256("adapter:" + source)));
        lineage.put("code_sha256", textOr(supplied.get("code_sha256"),
                RESEARCH_DATA_CODE_SHA256));
        lineage.put("container_sha256", textOr(supplied.get("container_sha256"),
                DUCKDB_IMAGE_DIGEST));
        ObjectNode config = JsonHashes.mapper().createObjectNode();
        config.put("source", source);
        config.put("role", role);
        config.put("publicSource", publicSource);
        config.set("partitioning", JsonHashes.mapper().valueToTree(PARTITIONING));
        lineage.put("config_sha256", textOr(supplied.get("config_sha256"),
                JsonHashes.canonicalSha256(config)));
        return lineage;
    }

    private static ObjectNode normalizedLineageForSnapshot(
            SnapshotOptions options, String datasetId, List<ObjectNode> normalized) {
        ObjectNode result = JsonHashes.mapper().createObjectNode();
        String rowAdapter = normalized.isEmpty() ? null
                : normalized.get(0).path("adapter_code_sha256").asText(null);
        result.put("adapter_sha256", firstNonNull(options.adapterSha256(), rowAdapter,
                JsonHashes.sha256("adapter:" + options.source())));
        result.put("code_sha256", firstNonNull(options.codeSha256(), RESEARCH_DATA_CODE_SHA256));
        result.put("container_sha256", firstNonNull(options.containerSha256(), DUCKDB_IMAGE_DIGEST));
        ObjectNode config = JsonHashes.mapper().createObjectNode();
        config.put("datasetId", datasetId);
        putNullable(config, "asset", options.asset());
        putNullable(config, "venue", options.venue());
        putNullable(config, "instrument", options.instrument());
        config.put("pitTier", options.pitTier());
        config.put("role", options.role());
        config.put("format", options.format());
        config.put("source", options.source());
        config.put("publicSource", options.publicSource());
        config.set("labelHorizon", options.labelHorizon() == null
                ? JsonHashes.mapper().nullNode() : options.labelHorizon());
        putNullable(config, "labelCodeSha256", options.labelCodeSha256());
        result.put("config_sha256", firstNonNull(options.configSha256(),
                JsonHashes.canonicalSha256(config)));
        return result;
    }

    private static SplitRows splitForSnapshot(
            List<ObjectNode> rows, ObjectNode horizon, String timeframe) {
        List<ObjectNode> features = new ArrayList<>();
        List<ObjectNode> labels = new ArrayList<>();
        for (ObjectNode row : rows) {
            ObjectNode feature = JsonHashes.mapper().createObjectNode();
            ObjectNode label = JsonHashes.mapper().createObjectNode();
            row.fields().forEachRemaining(field -> {
                (LABEL_FIELDS.contains(field.getKey().toLowerCase(Locale.ROOT)) ? label : feature)
                        .set(field.getKey(), field.getValue().deepCopy());
            });
            if (!label.isEmpty()) {
                copyIfPresent(row, label, "event_time", "time", "timestamp", "open_time", "asset",
                        "asset_class", "venue", "instrument", "timeframe", "dataset_version",
                        "resolution_bars", "horizon_bars", "resolved_at", "resolution_time",
                        "label_available_at");
                label.put("resolved_at", labelAvailability(label, horizon, timeframe));
                labels.add(label);
            }
            features.add(feature);
        }
        return new SplitRows(features, labels);
    }

    private static SplitRows labelOnly(
            List<ObjectNode> rows, ObjectNode horizon, String timeframe) {
        List<ObjectNode> labels = new ArrayList<>();
        for (ObjectNode row : rows) {
            ObjectNode label = row.deepCopy();
            label.put("resolved_at", labelAvailability(label, horizon, timeframe));
            labels.add(label);
        }
        return new SplitRows(List.of(), labels);
    }

    private static long labelAvailability(JsonNode row, ObjectNode horizon, String timeframe) {
        JsonNode explicit = firstPresent(row, "resolved_at", "resolution_time", "label_available_at");
        if (explicit != null) return rowTime(JsonHashes.mapper().createObjectNode().set("time", explicit));
        JsonNode rawBars = firstPresent(row, "resolution_bars", "horizon_bars");
        int bars = rawBars == null ? (horizon == null ? 0 : horizon.path("bars").asInt())
                : rawBars.asInt();
        if (bars <= 0) throw failure(
                "label row requires resolved_at or a positive frozen label horizon");
        return rowTime(row) + bars * timeframeMs(row.path("timeframe").asText(timeframe));
    }

    private static List<ObjectNode> qualityRows(List<ObjectNode> rows) {
        Map<String, ObjectNode> previous = new HashMap<>();
        List<ObjectNode> output = new ArrayList<>();
        for (ObjectNode row : rows) {
            String key = row.path("dataset_id").asText() + "|" + row.path("asset").asText()
                    + "|" + row.path("venue").asText() + "|" + row.path("instrument").asText()
                    + "|" + row.path("timeframe").asText();
            ObjectNode prior = previous.put(key, row);
            long interval = timeframeMs(row.path("timeframe").asText("4h"));
            int gap = prior == null ? 0 : Math.max(0, (int) Math.round(
                    (double) (row.path("event_time").asLong()
                            - prior.path("event_time").asLong()) / interval) - 1);
            ObjectNode quality = JsonHashes.mapper().createObjectNode();
            copyIfPresent(row, quality, "dataset_id", "asset", "event_time", "availability_time",
                    "pit_tier", "role");
            quality.put("coverage_ok", gap == 0);
            quality.put("gap_bars", gap);
            quality.put("max_gap_bars", gap);
            output.add(quality);
        }
        return output;
    }

    private static ObjectNode writeLayer(
            Path root, String datasetId, String layer, List<ObjectNode> rows, String format)
            throws IOException {
        if (rows.isEmpty()) return null;
        Path stage = root.resolve(layer).resolve(datasetId + ".jsonl");
        ObjectNode jsonl = writeJsonl(stage, rows);
        if ("jsonl".equals(format)) {
            ObjectNode portable = jsonl.deepCopy();
            portable.put("path", relative(root, stage));
            portable.put("format", "jsonl");
            portable.put("row_count", rows.size());
            portable.remove("rows");
            return portable;
        }
        if (!"parquet".equals(format)) {
            throw failure("unsupported snapshot format " + format + "; use parquet or jsonl");
        }
        Path parquet = root.resolve(layer).resolve(datasetId + ".parquet");
        ParquetArtifact artifact = writeParquet(stage, parquet);
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("path", relative(root, parquet));
        value.put("sha256", artifact.sha256());
        value.put("bytes", artifact.bytes());
        value.put("format", "parquet");
        value.put("row_count", rows.size());
        return value;
    }

    private static ArrayNode writePartitions(
            Path root, String datasetId, String layer, List<ObjectNode> rows, String format)
            throws IOException {
        Map<String, List<ObjectNode>> groups = new LinkedHashMap<>();
        for (ObjectNode row : rows) {
            Instant time = Instant.ofEpochMilli(row.path("event_time").asLong());
            String key = String.join("/",
                    pathSegment(row.path("dataset_version").asText(
                            row.path("dataset_id").asText(datasetId))),
                    pathSegment(row.path("asset").asText("unknown")),
                    pathSegment(row.path("venue").asText("unknown")),
                    pathSegment(row.path("instrument").asText("unknown")),
                    pathSegment(row.path("timeframe").asText("4h")),
                    pathSegment(String.valueOf(time.atZone(ZoneOffset.UTC).getYear())),
                    pathSegment(String.format("%02d", time.atZone(ZoneOffset.UTC).getMonthValue())));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        ArrayNode artifacts = JsonHashes.mapper().createArrayNode();
        for (Map.Entry<String, List<ObjectNode>> entry : groups.entrySet()) {
            Path directory = root.resolve(layer).resolve(entry.getKey());
            Path stage = directory.resolve("data.jsonl");
            ObjectNode jsonl = writeJsonl(stage, entry.getValue());
            ObjectNode artifact = artifacts.addObject();
            if ("parquet".equals(format)) {
                Path parquet = directory.resolve("data.parquet");
                ParquetArtifact value = writeParquet(stage, parquet);
                artifact.put("path", relative(root, parquet));
                artifact.put("sha256", value.sha256());
                artifact.put("bytes", value.bytes());
                artifact.put("format", "parquet");
            } else {
                artifact.put("path", relative(root, stage));
                artifact.put("sha256", jsonl.path("sha256").asText());
                artifact.put("format", "jsonl");
            }
            artifact.put("row_count", entry.getValue().size());
            artifact.put("rows", entry.getValue().size());
        }
        return artifacts;
    }

    private static ObjectNode writeJsonl(Path path, List<? extends JsonNode> rows) throws IOException {
        Files.createDirectories(path.getParent());
        StringBuilder content = new StringBuilder();
        for (JsonNode row : rows) content.append(JsonHashes.mapper().writeValueAsString(row)).append('\n');
        byte[] bytes = content.toString().getBytes(StandardCharsets.UTF_8);
        String digest = JsonHashes.sha256(bytes);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            PathConfinement.validateSinglyLinkedFile(path, "immutable artifact");
            if (!JsonHashes.sha256(path).equals(digest)) {
                throw failure("immutable artifact collision: " + path);
            }
        } else writeNew(path, bytes);
        ObjectNode result = JsonHashes.mapper().createObjectNode();
        result.put("path", path.toString());
        result.put("sha256", digest);
        result.put("rows", rows.size());
        return result;
    }

    private static void writeImmutableJson(Path path, ObjectNode value) throws IOException {
        Files.createDirectories(path.getParent());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            PathConfinement.validateSinglyLinkedFile(path, "immutable manifest");
            ObjectNode previous = parseObject(Files.readAllBytes(path));
            String actual = DATASET_MANIFEST_SCHEMA.equals(previous.path("schema").asText())
                    ? manifestOwnHash(previous) : canonicalHash(previous);
            if (!previous.path("content_sha256").asText().equals(actual)) {
                throw failure("immutable artifact retained-hash tampering: " + path);
            }
            if (!previous.path("content_sha256").asText()
                    .equals(value.path("content_sha256").asText())) {
                throw failure("immutable manifest collision: " + path);
            }
        } else writeNew(path, prettyBytes(value));
    }

    private static void validateArtifacts(JsonNode store, Path root, boolean development) {
        List<ObjectNode> artifacts = new ArrayList<>();
        if (store.isObject() && store.hasNonNull("path")) artifacts.add((ObjectNode) store);
        addArtifact(artifacts, store.get("labels"));
        addArtifacts(artifacts, store.get("partitions"));
        addArtifacts(artifacts, store.get("label_partitions"));
        addAuditArtifact(artifacts, store, "raw_path", "raw_sha256");
        addAuditArtifact(artifacts, store, "normalized_path", "normalized_sha256");
        addAuditArtifact(artifacts, store, "quality_path", "quality_sha256");
        for (ObjectNode artifact : artifacts) {
            String relative = artifact.path("path").asText();
            Path resolved;
            try {
                resolved = PathConfinement.resolve(root, relative, "manifest artifact",
                        PathConfinement.ExpectedType.FILE).absolute();
            } catch (RuntimeException error) {
                if (error.getMessage() != null && (error.getMessage().contains("singly-linked")
                        || error.getMessage().contains("symlink"))) {
                    throw failure(error.getMessage(), error);
                }
                throw failure("manifest artifact path is not repository-relative: "
                        + (relative.isEmpty() ? "?" : relative), error);
            }
            if (!artifact.path("sha256").asText().equals(JsonHashes.sha256(resolved))) {
                throw failure("manifest artifact hash mismatch: " + relative);
            }
            if (!development && !artifact.path("audit").asBoolean(false)
                    && !"parquet".equalsIgnoreCase(artifact.path("format").asText())) {
                throw failure("authoritative manifest artifact is not Parquet: " + relative);
            }
        }
    }

    private static void validateExistingTree(Path root) throws IOException {
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult preVisitDirectory(
                    Path directory, java.nio.file.attribute.BasicFileAttributes attributes) {
                if (attributes.isSymbolicLink()) {
                    throw failure("research snapshot contains a symlink: " + directory);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override public java.nio.file.FileVisitResult visitFile(
                    Path file, java.nio.file.attribute.BasicFileAttributes attributes) {
                if (attributes.isSymbolicLink()) {
                    throw failure("research snapshot contains a symlink: " + file);
                }
                if (!attributes.isRegularFile()) {
                    throw failure("research snapshot contains a non-regular file: " + file);
                }
                PathConfinement.requireSingleLink(file, "research snapshot file");
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static List<ObjectNode> parseRows(Path path, String source) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".csv")) return readDelimited(source);
            if (name.endsWith(".jsonl") || name.endsWith(".ndjson")) {
                List<ObjectNode> rows = new ArrayList<>();
                for (String line : source.split("\\R")) {
                    if (!line.isBlank()) rows.add(parseObject(line.getBytes(StandardCharsets.UTF_8)));
                }
                return rows;
            }
            JsonNode value = JsonHashes.mapper().readTree(source);
            JsonNode rows = value.isArray() ? value
                    : value.path("rows").isArray() ? value.path("rows") : value.path("data");
            List<ObjectNode> output = new ArrayList<>();
            if (rows.isArray()) for (JsonNode row : rows) {
                if (!row.isObject()) throw failure("row must be an object");
                output.add(((ObjectNode) row).deepCopy());
            }
            return output;
        } catch (JsonProcessingException error) {
            throw failure(error.getOriginalMessage(), error);
        }
    }

    private static List<ObjectNode> readDelimited(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (quoted) {
                if (character == '"' && index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    field.append('"'); index++;
                } else if (character == '"') quoted = false;
                else field.append(character);
            } else if (character == '"' && field.isEmpty()) quoted = true;
            else if (character == ',') { record.add(field.toString().trim()); field.setLength(0); }
            else if (character == '\n' || character == '\r') {
                if (character == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') index++;
                record.add(field.toString().trim()); field.setLength(0);
                if (record.stream().anyMatch(value -> !value.isEmpty())) records.add(record);
                record = new ArrayList<>();
            } else field.append(character);
        }
        if (quoted) throw failure("CSV contains an unterminated quoted field");
        if (!field.isEmpty() || !record.isEmpty()) {
            record.add(field.toString().trim());
            if (record.stream().anyMatch(value -> !value.isEmpty())) records.add(record);
        }
        if (records.isEmpty()) return List.of();
        List<String> headers = records.remove(0).stream().map(String::trim).toList();
        if (headers.stream().anyMatch(String::isEmpty)) throw failure("CSV header contains an empty field");
        List<ObjectNode> rows = new ArrayList<>();
        for (List<String> values : records) {
            ObjectNode row = JsonHashes.mapper().createObjectNode();
            for (int index = 0; index < headers.size(); index++) {
                row.put(headers.get(index), index < values.size() ? values.get(index) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private static long timeframeMs(String value) {
        var match = Pattern.compile("^(\\d+)(m|h|d)$", Pattern.CASE_INSENSITIVE)
                .matcher(valueOr(value, "4h"));
        if (!match.matches()) throw failure("unsupported timeframe " + value);
        long count = Long.parseLong(match.group(1));
        return count * switch (match.group(2).toLowerCase(Locale.ROOT)) {
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            default -> 86_400_000L;
        };
    }

    private static Long parseTime(String value) {
        try { return Instant.parse(value).toEpochMilli(); }
        catch (DateTimeParseException ignored) {
            try { return OffsetDateTime.parse(value).toInstant().toEpochMilli(); }
            catch (DateTimeParseException alsoIgnored) {
                try { return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(); }
                catch (DateTimeParseException invalid) { return null; }
            }
        }
    }

    private static String manifestOwnHash(ObjectNode value) {
        ObjectNode copy = value.deepCopy();
        copy.remove(List.of("content_sha256", "created_at"));
        return JsonHashes.canonicalSha256(copy);
    }

    private static void validateDatasetId(String value) {
        if (!SAFE_ID.matcher(value).matches() || ".".equals(value) || "..".equals(value)) {
            throw failure("datasetId must match " + SAFE_ID + ": " + value);
        }
    }

    private static String pathSegment(String value) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return "s-" + (encoded.isEmpty() ? "empty" : encoded);
    }

    private static String relative(Path root, Path path) {
        return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    private static void atomicReplace(Path target, byte[] bytes) throws IOException {
        Path temporary = target.resolveSibling("." + target.getFileName() + "."
                + JsonHashes.sha256(bytes) + ".tmp");
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)
                && !JsonHashes.sha256(temporary).equals(JsonHashes.sha256(bytes))) {
            Files.delete(temporary);
        }
        if (!Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) writeNew(temporary, bytes);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void moveNoReplace(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(source, target); }
    }

    private static void writeNew(Path path, byte[] bytes) throws IOException {
        Files.write(path, bytes, java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE);
    }

    private static byte[] prettyBytes(JsonNode value) {
        try {
            return (JsonHashes.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException error) {
            throw failure(error.getOriginalMessage(), error);
        }
    }

    private static ObjectNode parseObject(byte[] bytes) {
        JsonNode value = JsonHashes.parse(bytes, "research data JSON");
        if (!value.isObject()) throw failure("research data JSON must be an object");
        return ((ObjectNode) value).deepCopy();
    }

    private static void putJdbc(ObjectNode row, String name, Object value) {
        if (value == null) row.putNull(name);
        else if (value instanceof Boolean bool) row.put(name, bool);
        else if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) row.put(name, ((Number) value).longValue());
        else if (value instanceof Float || value instanceof Double) row.put(name, ((Number) value).doubleValue());
        else if (value instanceof BigDecimal decimal) row.put(name, decimal);
        else if (value instanceof byte[] bytes) row.put(name, Base64.getEncoder().encodeToString(bytes));
        else row.put(name, String.valueOf(value));
    }

    private static String sql(Path path) { return path.toString().replace("'", "''"); }

    private static void putNullable(ObjectNode target, String key, Object value) {
        if (value == null) target.putNull(key);
        else if (value instanceof JsonNode node) target.set(key, node.deepCopy());
        else target.put(key, String.valueOf(value));
    }

    private static Object nodeOrText(JsonNode node, String fallback) {
        return node != null && !node.isNull() ? node : fallback;
    }

    private static String textOr(JsonNode node, String fallback) {
        return node != null && !node.isNull() ? node.asText() : fallback;
    }

    private static String valueOr(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) if (value != null) return value;
        return null;
    }

    private static JsonNode firstPresent(JsonNode row, String... names) {
        if (row == null) return null;
        for (String name : names) if (row.has(name) && !row.get(name).isNull()) return row.get(name);
        return null;
    }

    private static void copyIfPresent(JsonNode source, ObjectNode target, String... names) {
        for (String name : names) if (source.has(name)) target.set(name, source.get(name).deepCopy());
    }

    private static ArrayNode array(JsonNode value) {
        return value instanceof ArrayNode array ? array : JsonHashes.mapper().createArrayNode();
    }

    private static List<JsonNode> concat(JsonNode left, JsonNode right) {
        List<JsonNode> output = new ArrayList<>();
        if (left != null && left.isArray()) left.forEach(output::add);
        if (right != null && right.isArray()) right.forEach(output::add);
        return output;
    }

    private static boolean datasetsContains(ArrayNode datasets, JsonNode target) {
        for (JsonNode dataset : datasets) if (dataset == target || dataset.equals(target)) return true;
        return false;
    }

    private static boolean isHash(String value) { return value != null && HASH.matcher(value).matches(); }

    private static List<ObjectNode> immutableNodes(List<ObjectNode> values) {
        if (values == null) return List.of();
        return values.stream().map(ObjectNode::deepCopy).toList();
    }

    private static void addArtifact(List<ObjectNode> output, JsonNode node) {
        if (node instanceof ObjectNode object && object.hasNonNull("path")) output.add(object.deepCopy());
    }

    private static void addArtifacts(List<ObjectNode> output, JsonNode nodes) {
        if (nodes != null && nodes.isArray()) nodes.forEach(node -> addArtifact(output, node));
    }

    private static void addAuditArtifact(
            List<ObjectNode> output, JsonNode store, String pathField, String hashField) {
        if (store.hasNonNull(pathField)) {
            ObjectNode artifact = JsonHashes.mapper().createObjectNode();
            artifact.put("path", store.path(pathField).asText());
            artifact.put("sha256", store.path(hashField).asText());
            artifact.put("format", "jsonl");
            artifact.put("audit", true);
            output.add(artifact);
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static IllegalArgumentException failure(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException failure(String message, Throwable cause) {
        return new IllegalArgumentException(message, cause);
    }
}

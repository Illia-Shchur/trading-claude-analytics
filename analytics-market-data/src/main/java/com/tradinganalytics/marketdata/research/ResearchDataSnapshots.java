package com.tradinganalytics.marketdata.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.PathConfinement;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Publishes snapshots and rebuilds the deterministic research-lake catalog. */
final class ResearchDataSnapshots {
    private ResearchDataSnapshots() {
    }

    static ResearchData.CatalogResult rebuildCatalog() {
        return rebuildCatalog(Path.of("data/research-lake"));
    }

    static ResearchData.CatalogResult rebuildCatalog(Path outputRoot) {
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
                    if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry)) {
                        continue;
                    }
                    Path manifestPath = entry.resolve("manifests/dataset-manifest.json");
                    if (!Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    ObjectNode manifest = ResearchDataArtifacts.parseObject(Files.readAllBytes(manifestPath));
                    ResearchDataManifests.validateManifest(manifest,
                            new ResearchData.ValidationOptions("DEVELOPMENT", List.of(), entry));
                    ObjectNode row = snapshots.addObject();
                    row.put("snapshot_id", manifest.path("snapshot_id").isNull()
                            ? entry.getFileName().toString() : manifest.path("snapshot_id").asText(entry.getFileName().toString()));
                    row.put("root", entry.getFileName().toString());
                    row.put("manifest_sha256", manifest.path("content_sha256").asText());
                    row.put("data_root_sha256", manifest.path("data_root_sha256").asText());
                    ArrayNode datasets = row.putArray("datasets");
                    for (JsonNode dataset : ResearchDataJson.concat(manifest.path("datasets"), manifest.path("label_datasets"))) {
                        ObjectNode summary = datasets.addObject();
                        ResearchDataJson.copyIfPresent(dataset, summary, "dataset_id", "asset", "asset_class",
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
            catalog.put("content_sha256", ResearchData.canonicalHash(catalog));
            Path path = root.resolve("catalog.json");
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                PathConfinement.validateSinglyLinkedFile(path, "research lake catalog");
                ObjectNode previous = ResearchDataArtifacts.parseObject(Files.readAllBytes(path));
                if (!previous.path("content_sha256").asText().equals(ResearchData.canonicalHash(previous))) {
                    throw failure("research lake catalog retained-hash tampering");
                }
            }
            ResearchDataArtifacts.atomicReplace(path, ResearchDataArtifacts.prettyBytes(catalog));
            return new ResearchData.CatalogResult(path, catalog);
        } catch (IOException error) {
            throw failure(error.getMessage(), error);
        }
    }

    static ResearchData.SnapshotResult snapshot(ResearchData.SnapshotOptions options) {
        if (options == null || options.input() == null) {
            throw failure("snapshot requires input");
        }
        String datasetId = options.datasetId() == null
                ? stripExtension(options.input().getFileName().toString()) : options.datasetId();
        validateDatasetId(datasetId);
        byte[] inputBytes;
        try {
            inputBytes = Files.readAllBytes(options.input());
        } catch (IOException error) {
            throw failure(error.getMessage(), error);
        }
        List<ObjectNode> raw = ResearchDataRows.parseRows(options.input(), new String(inputBytes, StandardCharsets.UTF_8));
        ResearchData.SplitRows split = "FEATURE".equals(options.role())
                ? ResearchDataRows.splitForSnapshot(raw, options.labelHorizon(), "4h")
                : "LABEL".equals(options.role())
                        ? ResearchDataRows.labelOnly(raw, options.labelHorizon(), "4h")
                        : new ResearchData.SplitRows(raw, List.of());
        ResearchData.NormalizeOptions featureOptions = new ResearchData.NormalizeOptions(
                options.asset(), options.venue(), options.instrument(), "4h", datasetId,
                options.pitTier(), "CONTEXT".equals(options.role()) ? "CONTEXT" : "FEATURE",
                options.source(), "completed_bar");
        ResearchData.NormalizeOptions labelOptions = new ResearchData.NormalizeOptions(
                options.asset(), options.venue(), options.instrument(), "4h", datasetId,
                options.pitTier(), "LABEL", options.source(), "completed_bar");
        List<ObjectNode> features = split.features().isEmpty() ? List.of()
                : ResearchDataRows.normalizeRows(split.features(), featureOptions);
        List<ObjectNode> labels = split.labels().isEmpty() ? List.of()
                : ResearchDataRows.normalizeRows(split.labels(), labelOptions);
        List<ObjectNode> normalized = new ArrayList<>(features);
        normalized.addAll(labels);
        normalized.sort(Comparator.comparingLong(row -> row.path("event_time").asLong()));
        ObjectNode lineage = normalizedLineageForSnapshot(options, datasetId, normalized);
        ObjectNode identity = JsonHashes.mapper().createObjectNode();
        identity.put("dataset_id", datasetId);
        identity.put("input_bytes_sha256", JsonHashes.sha256(inputBytes));
        identity.put("input_sha256", JsonHashes.canonicalSha256(raw));
        identity.put("normalized_sha256", JsonHashes.canonicalSha256(normalized));
        ResearchDataJson.putNullable(identity, "asset", options.asset());
        ResearchDataJson.putNullable(identity, "venue", options.venue());
        ResearchDataJson.putNullable(identity, "instrument", options.instrument());
        identity.put("pit_tier", options.pitTier());
        identity.put("role", options.role());
        identity.put("format", options.format());
        identity.put("source", options.source());
        identity.put("public_source", options.publicSource());
        identity.set("label_horizon", options.labelHorizon() == null
                ? JsonHashes.mapper().nullNode() : options.labelHorizon());
        ResearchDataJson.putNullable(identity, "label_code_sha256", options.labelCodeSha256());
        identity.set("lineage", lineage);
        String snapshotId = datasetId + "-" + JsonHashes.canonicalSha256(identity).substring(0, 16);
        try {
            Path requestedLake = options.outputRoot().toAbsolutePath().normalize();
            Files.createDirectories(requestedLake);
            Path lake = PathConfinement.requireRealDirectory(requestedLake, "research lake root");
            Path requestedRoot = lake.resolve(snapshotId);
            Files.createDirectories(requestedRoot);
            Path root = PathConfinement.requireRealDirectory(requestedRoot, "snapshot root");
            ResearchDataArtifacts.validateExistingTree(root);
            Path identityPath = root.resolve("snapshot-identity.json");
            if (Files.exists(identityPath, LinkOption.NOFOLLOW_LINKS)) {
                PathConfinement.validateSinglyLinkedFile(identityPath, "snapshot identity");
                JsonNode previous = ResearchDataArtifacts.parseObject(Files.readAllBytes(identityPath));
                if (!JsonHashes.canonicalSha256(previous).equals(JsonHashes.canonicalSha256(identity))) {
                    throw failure("immutable snapshot root collision: " + root);
                }
            } else {
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
                    if (entries.iterator().hasNext()) {
                        throw failure("existing snapshot root lacks identity receipt; refusing overwrite: " + root);
                    }
                }
                ResearchDataArtifacts.writeNew(identityPath, ResearchDataArtifacts.prettyBytes(identity));
            }
            ObjectNode rawArtifact = ResearchDataArtifacts.writeJsonl(root.resolve("raw/" + datasetId + ".jsonl"), raw);
            ObjectNode normalizedArtifact = ResearchDataArtifacts.writeJsonl(
                    root.resolve("normalized/" + datasetId + ".jsonl"), normalized);
            List<ObjectNode> quality = qualityRows(normalized);
            ObjectNode qualityArtifact = ResearchDataArtifacts.writeJsonl(
                    root.resolve("quality/" + datasetId + ".jsonl"), quality);
            List<ObjectNode> gaps = quality.stream().filter(row -> row.path("gap_bars").asInt() > 0)
                    .map(row -> {
                        ObjectNode gap = JsonHashes.mapper().createObjectNode();
                        ResearchDataJson.copyIfPresent(row, gap, "dataset_id", "asset", "event_time", "role",
                                "gap_bars", "max_gap_bars");
                        return gap;
                    }).toList();
            ObjectNode feature = ResearchDataArtifacts.writeLayer(root, datasetId, "features", features, options.format());
            ObjectNode label = ResearchDataArtifacts.writeLayer(root, datasetId, "labels", labels, options.format());
            ArrayNode featurePartitions = ResearchDataArtifacts.writePartitions(
                    root, datasetId, "features/partitions", features, options.format());
            ArrayNode labelPartitions = ResearchDataArtifacts.writePartitions(
                    root, datasetId, "labels/partitions", labels, options.format());
            ObjectNode featureStore = feature == null ? JsonHashes.mapper().createObjectNode() : feature.deepCopy();
            featureStore.set("labels", label == null ? JsonHashes.mapper().nullNode() : label);
            featureStore.set("partitions", featurePartitions);
            featureStore.set("label_partitions", labelPartitions);
            featureStore.put("input_bytes_sha256", JsonHashes.sha256(inputBytes));
            featureStore.put("raw_path", ResearchDataArtifacts.relative(root, Path.of(rawArtifact.path("path").asText())));
            featureStore.put("raw_sha256", rawArtifact.path("sha256").asText());
            featureStore.put("normalized_path", ResearchDataArtifacts.relative(root, Path.of(normalizedArtifact.path("path").asText())));
            featureStore.put("normalized_sha256", normalizedArtifact.path("sha256").asText());
            featureStore.put("quality_path", ResearchDataArtifacts.relative(root, Path.of(qualityArtifact.path("path").asText())));
            featureStore.put("quality_sha256", qualityArtifact.path("sha256").asText());
            ObjectNode manifest = ResearchDataManifests.buildManifest(new ResearchData.ManifestOptions(
                    snapshotId, features, labels, List.of(), featureStore, options.role(), options.source(),
                    snapshotId, gaps, options.publicSource(), lineage));
            Path manifests = root.resolve("manifests");
            Files.createDirectories(manifests);
            Path manifestPath = manifests.resolve("dataset-manifest.json");
            ResearchDataArtifacts.writeImmutableJson(manifestPath, manifest);
            Path featureSetPath = manifests.resolve("feature-set.json");
            if (!features.isEmpty()) {
                ArrayNode featureLineage = JsonHashes.mapper().createArrayNode();
                ObjectNode first = featureLineage.addObject();
                first.put("dataset_id", datasetId);
                first.put("source", options.source());
                first.put("pit_tier", options.pitTier());
                ResearchDataArtifacts.writeImmutableJson(featureSetPath, ResearchDataManifests.buildFeatureSet(
                        new ResearchData.FeatureSetOptions(snapshotId + "-features",
                                manifest.path("content_sha256").asText(),
                                JsonHashes.sha256("research-data/feature-normalize/v1"), featureLineage,
                                0, null, featurePartitions)));
            }
            Path labelSetPath = null;
            if (!labels.isEmpty()) {
                labelSetPath = manifests.resolve("label-set.json");
                ObjectNode horizon = options.labelHorizon() == null
                        ? JsonHashes.mapper().createObjectNode().put("bars", 1).put("unit", "bars")
                        : options.labelHorizon();
                ResearchDataArtifacts.writeImmutableJson(labelSetPath, ResearchDataManifests.buildLabelSet(
                        new ResearchData.LabelSetOptions(snapshotId + "-labels",
                                manifest.path("content_sha256").asText(),
                                options.labelCodeSha256() == null ? JsonHashes.sha256("research-data/labels/v1")
                                        : options.labelCodeSha256(), horizon, labelPartitions)));
            }
            rebuildCatalog(options.outputRoot());
            return new ResearchData.SnapshotResult(snapshotId, root, manifestPath,
                    features.isEmpty() ? null : featureSetPath, labelSetPath, feature, label);
        } catch (IOException error) {
            throw failure(error.getMessage(), error);
        }
    }

    private static ObjectNode normalizedLineageForSnapshot(ResearchData.SnapshotOptions options,
                                                            String datasetId, List<ObjectNode> normalized) {
        ObjectNode result = JsonHashes.mapper().createObjectNode();
        String rowAdapter = normalized.isEmpty() ? null : normalized.get(0).path("adapter_code_sha256").asText(null);
        result.put("adapter_sha256", ResearchDataJson.firstNonNull(options.adapterSha256(), rowAdapter,
                JsonHashes.sha256("adapter:" + options.source())));
        result.put("code_sha256", ResearchDataJson.firstNonNull(options.codeSha256(), ResearchData.RESEARCH_DATA_CODE_SHA256));
        result.put("container_sha256", ResearchDataJson.firstNonNull(options.containerSha256(), ResearchData.LEGACY_DUCKDB_IMAGE_DIGEST));
        ObjectNode config = JsonHashes.mapper().createObjectNode();
        config.put("datasetId", datasetId);
        ResearchDataJson.putNullable(config, "asset", options.asset());
        ResearchDataJson.putNullable(config, "venue", options.venue());
        ResearchDataJson.putNullable(config, "instrument", options.instrument());
        config.put("pitTier", options.pitTier());
        config.put("role", options.role());
        config.put("format", options.format());
        config.put("source", options.source());
        config.put("publicSource", options.publicSource());
        config.set("labelHorizon", options.labelHorizon() == null
                ? JsonHashes.mapper().nullNode() : options.labelHorizon());
        ResearchDataJson.putNullable(config, "labelCodeSha256", options.labelCodeSha256());
        result.put("config_sha256", ResearchDataJson.firstNonNull(options.configSha256(), JsonHashes.canonicalSha256(config)));
        return result;
    }

    private static List<ObjectNode> qualityRows(List<ObjectNode> rows) {
        Map<String, ObjectNode> previous = new HashMap<>();
        List<ObjectNode> output = new ArrayList<>();
        for (ObjectNode row : rows) {
            String key = row.path("dataset_id").asText() + "|" + row.path("asset").asText()
                    + "|" + row.path("venue").asText() + "|" + row.path("instrument").asText()
                    + "|" + row.path("timeframe").asText();
            ObjectNode prior = previous.put(key, row);
            long interval = ResearchDataRows.timeframeMs(row.path("timeframe").asText("4h"));
            int gap = prior == null ? 0 : Math.max(0, (int) Math.round((double) (row.path("event_time").asLong()
                    - prior.path("event_time").asLong()) / interval) - 1);
            ObjectNode quality = JsonHashes.mapper().createObjectNode();
            ResearchDataJson.copyIfPresent(row, quality, "dataset_id", "asset", "event_time", "availability_time",
                    "pit_tier", "role");
            quality.put("coverage_ok", gap == 0);
            quality.put("gap_bars", gap);
            quality.put("max_gap_bars", gap);
            output.add(quality);
        }
        return output;
    }

    private static void validateDatasetId(String value) {
        if (!value.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$") || ".".equals(value) || "..".equals(value)) {
            throw failure("datasetId must match ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$: " + value);
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

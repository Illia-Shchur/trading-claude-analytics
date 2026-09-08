package com.tradinganalytics.marketdata.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Constructs and validates the hash-linked research manifest artifacts. */
final class ResearchDataManifests {
    private static final List<String> PARTITIONING = List.of(
            "dataset_version", "asset", "venue", "instrument", "timeframe", "utc_year", "utc_month");

    private ResearchDataManifests() {
    }

    static ObjectNode buildManifest(ResearchData.ManifestOptions options) {
        options = options == null
                ? new ResearchData.ManifestOptions(null, List.of(), List.of(), List.of(), null,
                        "FEATURE", "unknown", null, List.of(), true, null)
                : options;
        ArrayNode datasetRows = options.datasets().isEmpty()
                ? deriveDatasets(options.rows(), options.source(), options.publicSource())
                : JsonHashes.mapper().valueToTree(options.datasets());
        ArrayNode labelDatasetRows = deriveDatasets(options.labelRows(), options.source(), options.publicSource());
        ObjectNode coverage = coverageSummary(datasetRows);
        ObjectNode labelCoverage = coverageSummary(labelDatasetRows);
        ObjectNode lineage = normalizedLineage(options.lineage(), options.source(), options.role(), options.publicSource());
        ObjectNode featureStore = options.featureStore() == null ? null : options.featureStore();
        ArrayNode gaps = JsonHashes.mapper().valueToTree(options.gaps());
        ObjectNode identity = JsonHashes.mapper().createObjectNode();
        identity.set("datasetRows", datasetRows);
        identity.set("labelDatasetRows", labelDatasetRows);
        identity.set("gaps", gaps);
        identity.set("featureStore", featureStore == null ? JsonHashes.mapper().nullNode() : featureStore);
        identity.set("lineage", lineage);
        String manifestId = options.manifestId() == null
                ? "snapshot-" + JsonHashes.canonicalSha256(identity).substring(0, 16) : options.manifestId();

        ObjectNode manifest = JsonHashes.mapper().createObjectNode();
        manifest.put("schema", ResearchData.DATASET_MANIFEST_SCHEMA);
        manifest.put("manifest_id", manifestId);
        ResearchDataJson.putNullable(manifest, "snapshot_id", options.snapshotId());
        manifest.put("role", options.role());
        manifest.put("source", options.source());
        manifest.put("public_source", options.publicSource());
        manifest.set("partitioning", JsonHashes.mapper().valueToTree(PARTITIONING));
        manifest.set("lineage", lineage);
        manifest.set("feature_store", featureStore == null ? JsonHashes.mapper().nullNode() : featureStore.deepCopy());
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
        dataRoot.set("featureStore", featureStore == null ? JsonHashes.mapper().nullNode() : featureStore);
        dataRoot.set("lineage", lineage);
        manifest.put("data_root_sha256", JsonHashes.canonicalSha256(dataRoot));
        boolean authoritative = Set.of("FEATURE", "LABEL").contains(options.role());
        for (JsonNode dataset : ResearchDataJson.concat(datasetRows, labelDatasetRows)) {
            authoritative &= Set.of("PIT_SAFE", "VERIFIED", "COMPLETED_BAR")
                    .contains(dataset.path("point_in_time_status").asText().toUpperCase(Locale.ROOT));
        }
        manifest.put("authoritative", authoritative);
        manifest.putNull("content_sha256");
        manifest.put("content_sha256", manifestOwnHash(manifest));
        return manifest;
    }

    static ObjectNode buildFeatureSet(ResearchData.FeatureSetOptions options) {
        if (options == null || !ResearchDataJson.isHash(options.dataManifestSha256())) {
            throw failure("feature set requires data_manifest_sha256");
        }
        if (!ResearchDataJson.isHash(options.featureCodeSha256())) {
            throw failure("feature set requires feature_code_sha256");
        }
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("schema", ResearchData.FEATURE_SET_SCHEMA);
        value.put("feature_set_id", options.featureSetId() == null
                ? "features-" + options.dataManifestSha256().substring(0, 12) : options.featureSetId());
        value.put("data_manifest_sha256", options.dataManifestSha256());
        value.put("feature_code_sha256", options.featureCodeSha256());
        value.set("lineage", options.lineage() == null ? JsonHashes.mapper().createArrayNode() : options.lineage().deepCopy());
        value.put("warmup_bars", options.warmupBars());
        value.set("coverage", options.coverage() == null ? JsonHashes.mapper().createObjectNode() : options.coverage().deepCopy());
        value.set("partitions", options.partitions() == null ? JsonHashes.mapper().createArrayNode() : options.partitions().deepCopy());
        value.put("labels_allowed", false);
        return withHash(value);
    }

    static ObjectNode buildLabelSet(ResearchData.LabelSetOptions options) {
        if (options == null || !ResearchDataJson.isHash(options.dataManifestSha256())) {
            throw failure("label set requires data_manifest_sha256");
        }
        if (!ResearchDataJson.isHash(options.labelCodeSha256())) {
            throw failure("label set requires label_code_sha256");
        }
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("schema", ResearchData.LABEL_SET_SCHEMA);
        value.put("label_set_id", options.labelSetId() == null
                ? "labels-" + options.dataManifestSha256().substring(0, 12) : options.labelSetId());
        value.put("data_manifest_sha256", options.dataManifestSha256());
        value.put("label_code_sha256", options.labelCodeSha256());
        value.set("horizon", options.horizon() == null ? JsonHashes.mapper().createObjectNode() : options.horizon().deepCopy());
        ObjectNode derivation = value.putObject("derivation");
        derivation.put("label_code_sha256", options.labelCodeSha256());
        derivation.put("source_manifest_sha256", options.dataManifestSha256());
        derivation.put("availability", "resolved_at_or_frozen_horizon_end");
        derivation.put("resolution_field", "resolved_at");
        derivation.put("predictor_eligible", false);
        value.set("partitions", options.partitions() == null ? JsonHashes.mapper().createArrayNode() : options.partitions().deepCopy());
        value.put("predictor_eligible", false);
        return withHash(value);
    }

    static boolean validateManifest(ObjectNode manifest, ResearchData.ValidationOptions options) {
        options = options == null ? ResearchData.ValidationOptions.development() : options;
        if (manifest == null || !ResearchData.DATASET_MANIFEST_SCHEMA.equals(manifest.path("schema").asText())) {
            throw failure("unsupported dataset manifest "
                    + (manifest == null ? "missing" : manifest.path("schema").asText("missing")));
        }
        if (!manifest.path("content_sha256").asText().equals(manifestOwnHash(manifest))) {
            throw failure("dataset manifest content hash mismatch");
        }
        ArrayNode datasets = ResearchDataJson.array(manifest.get("datasets"));
        ArrayNode labels = ResearchDataJson.array(manifest.get("label_datasets"));
        if (datasets.isEmpty() && labels.isEmpty()) {
            throw failure("dataset manifest must contain datasets or label_datasets");
        }
        boolean development = "DEVELOPMENT".equals(options.phase());
        if (!development && !manifest.path("authoritative").asBoolean(false)) {
            throw failure("dataset manifest is not authoritative for " + options.phase());
        }
        JsonNode store = manifest.path("feature_store");
        JsonNode primary = "parquet".equals(store.path("format").asText()) ? store : store.path("labels");
        if (!development && (!primary.isObject() || !"parquet".equalsIgnoreCase(primary.path("format").asText()))) {
            throw failure("JSONL/staging data cannot validate as authoritative " + options.phase() + " evidence");
        }
        ObjectNode expected = JsonHashes.mapper().createObjectNode();
        expected.set("datasetRows", datasets);
        expected.set("labelDatasetRows", labels);
        expected.set("coverageSummary", manifest.get("coverage_summary"));
        expected.set("labelCoverageSummary", manifest.get("label_coverage_summary"));
        expected.set("gaps", manifest.has("gaps") ? manifest.get("gaps") : JsonHashes.mapper().createArrayNode());
        expected.set("featureStore", manifest.get("feature_store"));
        expected.set("lineage", manifest.get("lineage"));
        if (!manifest.path("data_root_sha256").asText().equals(JsonHashes.canonicalSha256(expected))) {
            throw failure("dataset manifest data_root_sha256 mismatch");
        }
        if (options.root() != null) {
            ResearchDataArtifacts.validateArtifacts(store, options.root(), development);
        }
        Set<String> required = new LinkedHashSet<>();
        options.requiredAssets().forEach(asset -> required.add(asset.toLowerCase(Locale.ROOT)));
        Set<String> present = new java.util.HashSet<>();
        for (JsonNode dataset : ResearchDataJson.concat(datasets, labels)) {
            String id = dataset.path("dataset_id").asText();
            if (id.isEmpty() || !dataset.path("row_count").canConvertToInt() || dataset.path("source_sha256").asText().isEmpty()) {
                throw failure("dataset " + (id.isEmpty() ? "?" : id) + " is incomplete");
            }
            String asset = dataset.path("asset").asText().toLowerCase(Locale.ROOT);
            if ("doge".equals(asset)) {
                throw failure("DOGE is excluded from the v3 research universe");
            }
            boolean context = "context".equalsIgnoreCase(dataset.path("asset_class").asText());
            if (!required.isEmpty() && !required.contains(asset) && !"CONTEXT".equals(manifest.path("role").asText()) && !context) {
                throw failure("dataset asset " + asset + " is not in required crypto universe");
            }
            if (!development && !Set.of("PIT_SAFE", "VERIFIED", "COMPLETED_BAR")
                    .contains(dataset.path("point_in_time_status").asText().toUpperCase(Locale.ROOT))) {
                throw failure("dataset " + id + " is unsafe PIT for " + options.phase());
            }
            if (!development && Set.of("REVISED", "NON_PIT", "UNKNOWN")
                    .contains(dataset.path("revision_status").asText().toUpperCase(Locale.ROOT))) {
                throw failure("dataset " + id + " is revised/non-PIT for " + options.phase());
            }
            if (!context && datasetsContains(datasets, dataset)) {
                present.add(asset);
            }
        }
        if (!required.isEmpty()) {
            List<String> missing = required.stream().filter(asset -> !present.contains(asset)).toList();
            if (!missing.isEmpty()) {
                throw failure("dataset manifest is missing required crypto assets: " + String.join(", ", missing));
            }
        }
        return true;
    }

    private static ArrayNode deriveDatasets(List<ObjectNode> rows, String source, boolean publicSource) {
        Map<String, List<ObjectNode>> groups = new LinkedHashMap<>();
        for (ObjectNode row : rows) {
            String key = String.join("|", row.path("dataset_id").asText("dataset"),
                    row.path("dataset_version").asText(row.path("dataset_id").asText("dataset")),
                    row.path("asset").asText(), row.path("venue").asText(), row.path("instrument").asText(),
                    row.path("timeframe").asText("4h"));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        ArrayNode output = JsonHashes.mapper().createArrayNode();
        for (Map.Entry<String, List<ObjectNode>> entry : groups.entrySet()) {
            String[] key = entry.getKey().split("\\|", -1);
            List<ObjectNode> values = new ArrayList<>(entry.getValue());
            values.sort(Comparator.comparingLong(row -> row.path("event_time").asLong()));
            long interval = ResearchDataRows.timeframeMs(values.get(0).path("timeframe").asText("4h"));
            long first = values.get(0).path("event_time").asLong();
            long last = values.get(values.size() - 1).path("event_time").asLong();
            long expected = values.size() > 1 ? ((last - first) / interval) + 1 : values.size();
            int gapCount = 0;
            int maxGap = 0;
            for (int index = 1; index < values.size(); index++) {
                int gap = Math.max(0, (int) Math.round((double) (values.get(index).path("event_time").asLong()
                        - values.get(index - 1).path("event_time").asLong()) / interval) - 1);
                gapCount += gap;
                maxGap = Math.max(maxGap, gap);
            }
            ObjectNode dataset = output.addObject();
            dataset.put("dataset_id", key[0]);
            dataset.put("dataset_version", key[1].isEmpty() ? key[0] : key[1]);
            dataset.put("asset", key[2]);
            dataset.put("asset_class", values.get(0).path("asset_class").asText("crypto"));
            ResearchDataJson.putNullable(dataset, "venue", key[3].isEmpty() ? null : key[3]);
            ResearchDataJson.putNullable(dataset, "instrument_id", key[4].isEmpty() ? null : key[4]);
            ResearchDataJson.putNullable(dataset, "timeframe", key[5].isEmpty() ? null : key[5]);
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
                    "REVISED_OR_PROXY".equals(row.path("revision_status").asText())) ? "REVISED" : "ORIGINAL");
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
                            || ("crypto".equalsIgnoreCase(row.path("asset_class").asText()) && !instrument.contains("perp"));
                    case "derivatives" -> instrument.contains("perp") || instrument.contains("future");
                    default -> source.contains("funding");
                };
                if (selected) {
                    expected += row.path("coverage").path("expected_rows").asLong();
                    observed += row.path("coverage").path("observed_rows").asLong();
                }
            }
            if (expected == 0) {
                result.putNull(kind + "_fraction");
            } else {
                result.put(kind + "_fraction", (double) observed / expected);
            }
        }
        return result;
    }

    private static ObjectNode normalizedLineage(ObjectNode supplied, String source, String role, boolean publicSource) {
        ObjectNode lineage = JsonHashes.mapper().createObjectNode();
        lineage.put("adapter_sha256", ResearchDataJson.textOr(supplied.get("adapter_sha256"),
                JsonHashes.sha256("adapter:" + source)));
        lineage.put("code_sha256", ResearchDataJson.textOr(supplied.get("code_sha256"), ResearchData.RESEARCH_DATA_CODE_SHA256));
        lineage.put("container_sha256", ResearchDataJson.textOr(supplied.get("container_sha256"), ResearchData.LEGACY_DUCKDB_IMAGE_DIGEST));
        ObjectNode config = JsonHashes.mapper().createObjectNode();
        config.put("source", source);
        config.put("role", role);
        config.put("publicSource", publicSource);
        config.set("partitioning", JsonHashes.mapper().valueToTree(PARTITIONING));
        lineage.put("config_sha256", ResearchDataJson.textOr(supplied.get("config_sha256"), JsonHashes.canonicalSha256(config)));
        return lineage;
    }

    static String manifestOwnHash(ObjectNode value) {
        ObjectNode copy = value.deepCopy();
        copy.remove(List.of("content_sha256", "created_at"));
        return JsonHashes.canonicalSha256(copy);
    }

    private static boolean datasetsContains(ArrayNode datasets, JsonNode target) {
        for (JsonNode dataset : datasets) {
            if (dataset == target || dataset.equals(target)) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode withHash(ObjectNode value) {
        ObjectNode copy = value.deepCopy();
        copy.put("content_sha256", ResearchData.canonicalHash(copy));
        return copy;
    }

    private static IllegalArgumentException failure(String message) {
        return new IllegalArgumentException(message);
    }
}

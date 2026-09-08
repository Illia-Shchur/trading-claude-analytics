package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.marketdata.research.ResearchData;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fail-closed boundary coverage for the public readiness audit's physical data chain.
 * Each case starts from a tiny fixture with data-role bytes and source references
 * hash-bound, then changes one custody edge. Derivation receipts remain named
 * contract fields because this audit only checks their declared binding.
 */
final class StrategyReadinessV5PhysicalReadinessMatrixContractsTest {
    private static final long NOW = Instant.parse("2025-08-24T01:50:00Z").toEpochMilli();
    private static final String GENERATED_AT = "2025-08-24T01:50:00.000Z";
    private static final String DATA_SCHEMA = "strategy-v5-separated-artifacts/1";
    private static final List<String> DATA_ROLES = List.of("feature", "label", "execution", "mark");

    @TempDir
    Path temporary;

    @Test
    void dataAdmissionMatrixKeepsManifestBindingVisibleAndFailsClosed() throws Exception {
        for (DataCase dataCase : DataCase.values()) {
            Fixture fixture = dataFixture(temporary.resolve(dataCase.name()));
            ObjectNode baseline = audit(fixture);
            assertThat(baseline.path("artifact_verification").get(0).path("verified").asBoolean())
                    .as(dataCase.name()).isTrue();
            assertThat(baseline.path("limitations")).extracting(JsonNode::asText)
                    .doesNotContain(dataCase.failure);
            dataCase.apply(fixture);
            ObjectNode audit = audit(fixture);
            JsonNode row = audit.path("artifact_verification").get(0);
            assertThat(row.path("id").asText()).isEqualTo("data");
            assertThat(audit.path("limitations")).extracting(JsonNode::asText)
                    .contains(dataCase.failure);
            if (dataCase.manifestRemainsVerified) {
                assertThat(row.path("verified").asBoolean()).as(dataCase.name()).isTrue();
                assertThat(audit.path("limitations")).extracting(JsonNode::asText)
                        .doesNotContain("lineage:DATA_MANIFEST_NOT_VERIFIED");
            } else {
                assertThat(row.path("verified").asBoolean()).as(dataCase.name()).isFalse();
                assertThat(audit.path("limitations")).extracting(JsonNode::asText)
                        .contains("lineage:DATA_MANIFEST_NOT_VERIFIED");
            }
        }
    }

    @Test
    void sourceAndConversionReferenceMatrixReportsTheExactPhysicalFailure() throws Exception {
        for (ReferenceCase referenceCase : ReferenceCase.values()) {
            Fixture fixture = dataFixture(temporary.resolve(referenceCase.name()));
            ObjectNode baseline = audit(fixture);
            assertThat(baseline.path("artifact_verification").get(0).path("verified").asBoolean())
                    .as(referenceCase.name()).isTrue();
            assertThat(baseline.path("limitations")).extracting(JsonNode::asText)
                    .doesNotContain(referenceCase.failure);
            referenceCase.apply(fixture);
            ObjectNode audit = audit(fixture);
            assertThat(audit.path("artifact_verification").get(0).path("verified").asBoolean())
                    .as(referenceCase.name()).isTrue();
            assertThat(audit.path("limitations")).extracting(JsonNode::asText)
                    .contains(referenceCase.failure)
                    .doesNotContain("lineage:DATA_MANIFEST_NOT_VERIFIED");
        }
    }

    private enum DataCase {
        ROLE_MISSING("lineage:DATA_ROLE_FEATURE_MISSING", false) {
            @Override void apply(Fixture fixture) throws Exception {
                ((ObjectNode) fixture.data.path("artifacts")).remove("feature");
                fixture.rewrite(true);
            }
        },
        STAGING_ROLE_SUBSTITUTE("lineage:PHYSICAL_PARQUET_REOPEN_FAILED", true) {
            @Override void apply(Fixture fixture) throws Exception {
                ObjectNode label = (ObjectNode) fixture.data.path("artifacts").path("label");
                label.put("storage_role", "STAGING").put("authoritative", false);
                fixture.rewrite(true);
            }
        },
        DATASET_ROOT_DRIFT("lineage:DATASET_ROOT_RECOMPUTATION_FAILED", true) {
            @Override void apply(Fixture fixture) throws Exception {
                fixture.data.put("source_dataset_root_sha256", "f".repeat(64));
                fixture.rewrite(false);
            }
        };

        private final String failure;
        private final boolean manifestRemainsVerified;

        DataCase(String failure, boolean manifestRemainsVerified) {
            this.failure = failure;
            this.manifestRemainsVerified = manifestRemainsVerified;
        }

        abstract void apply(Fixture fixture) throws Exception;
    }

    private enum ReferenceCase {
        SOURCE_BYTES_TAMPERED("lineage:SOURCE_MANIFEST:PHYSICAL_BYTES_MISSING_OR_TAMPERED") {
            @Override void apply(Fixture fixture) throws Exception {
                Files.writeString(fixture.sourcePath, "tampered source\n", StandardCharsets.UTF_8);
            }
        },
        SOURCE_NOT_JSON("lineage:SOURCE_MANIFEST:NOT_JSON") {
            @Override void apply(Fixture fixture) throws Exception {
                byte[] bytes = "{not-json".getBytes(StandardCharsets.UTF_8);
                Files.write(fixture.sourcePath, bytes);
                ((ObjectNode) fixture.data.path("source_manifest_reference"))
                        .put("byte_sha256", StrategyReadinessV5.hash(bytes));
                fixture.rewrite(true);
            }
        },
        SOURCE_CONTENT_UNBOUND("lineage:SOURCE_MANIFEST:CONTENT_BINDING_FAILED") {
            @Override void apply(Fixture fixture) throws Exception {
                fixture.source.put("content_sha256", "f".repeat(64));
                byte[] bytes = json(fixture.source);
                Files.write(fixture.sourcePath, bytes);
                ((ObjectNode) fixture.data.path("source_manifest_reference"))
                        .put("byte_sha256", StrategyReadinessV5.hash(bytes));
                fixture.rewrite(true);
            }
        },
        CONVERSION_BYTES_TAMPERED("lineage:PARQUET_SOURCE_MANIFEST:PHYSICAL_BYTES_MISSING_OR_TAMPERED") {
            @Override void apply(Fixture fixture) throws Exception {
                Files.writeString(fixture.conversionPath, "tampered conversion\n", StandardCharsets.UTF_8);
            }
        },
        CONVERSION_NOT_JSON("lineage:PARQUET_SOURCE_MANIFEST:NOT_JSON") {
            @Override void apply(Fixture fixture) throws Exception {
                byte[] bytes = "[not-json".getBytes(StandardCharsets.UTF_8);
                Files.write(fixture.conversionPath, bytes);
                ((ObjectNode) fixture.data.path("conversion")
                        .path("source_artifact_manifest_reference"))
                        .put("byte_sha256", StrategyReadinessV5.hash(bytes));
                fixture.rewrite(true);
            }
        },
        CONVERSION_CONTENT_UNBOUND("lineage:PARQUET_SOURCE_MANIFEST:CONTENT_BINDING_FAILED") {
            @Override void apply(Fixture fixture) throws Exception {
                fixture.conversion.put("content_sha256", "e".repeat(64));
                byte[] bytes = json(fixture.conversion);
                Files.write(fixture.conversionPath, bytes);
                ((ObjectNode) fixture.data.path("conversion")
                        .path("source_artifact_manifest_reference"))
                        .put("byte_sha256", StrategyReadinessV5.hash(bytes));
                fixture.rewrite(true);
            }
        };

        private final String failure;

        ReferenceCase(String failure) {
            this.failure = failure;
        }

        abstract void apply(Fixture fixture) throws Exception;
    }

    private static ObjectNode audit(Fixture fixture) throws Exception {
        ObjectNode options = JsonHashes.mapper().createObjectNode()
                .put("now", NOW).put("generatedAt", GENERATED_AT);
        byte[] bytes = fixture.manifestBytes;
        options.putObject("evidence").set("data", JsonHashes.mapper().createObjectNode()
                .put("path", fixture.manifestPath.toString())
                .put("sha256", StrategyReadinessV5.hash(bytes)).put("schema", DATA_SCHEMA));
        return StrategyReadinessV5.buildReadinessAuditV5(options);
    }

    private static Fixture dataFixture(Path root) throws Exception {
        Files.createDirectories(root);
        JsonNode source = loadPhysicalNullFixture();
        ObjectNode data = (ObjectNode) source.path("fixture").path("manifest").deepCopy();
        ObjectNode artifacts = (ObjectNode) data.path("artifacts");
        for (String role : DATA_ROLES) {
            Path path = root.resolve("parquet").resolve(role + ".parquet");
            Files.createDirectories(path.getParent());
            Path jsonl = root.resolve("jsonl").resolve(role + ".jsonl");
            Files.createDirectories(jsonl.getParent());
            ObjectNode physicalRow = JsonHashes.mapper().createObjectNode().put("role", role)
                    .put("event_time", 1_735_689_600_000L)
                    .put("availability_time", 1_735_689_659_000L);
            Files.writeString(jsonl, JsonHashes.mapper().writeValueAsString(physicalRow) + "\n",
                    StandardCharsets.UTF_8);
            ResearchData.writeParquet(jsonl, path);
            byte[] bytes = Files.readAllBytes(path);
            ObjectNode artifact = (ObjectNode) artifacts.path(role);
            artifact.put("path", root.relativize(path).toString())
                    .put("sha256", StrategyReadinessV5.hash(bytes)).put("bytes", bytes.length)
                    .put("derivation_receipt_sha256", "d".repeat(64))
                    .put("derivation_receipt_path", "receipts/" + role + ".json");
        }

        ObjectNode sourceManifest = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-v5-source-bundle/1").put("version", 1)
                .put("status", "PUBLIC_OBSERVED");
        sourceManifest = StrategyReadinessV5.withHash(sourceManifest);
        Path sourcePath = root.resolve("lineage/source-manifest.json");
        byte[] sourceBytes = json(sourceManifest);
        Files.createDirectories(sourcePath.getParent());
        Files.write(sourcePath, sourceBytes);
        ((ObjectNode) data.path("source_manifest_reference"))
                .put("path", root.relativize(sourcePath).toString())
                .put("content_sha256", sourceManifest.path("content_sha256").asText())
                .put("byte_sha256", StrategyReadinessV5.hash(sourceBytes));
        data.put("source_manifest_sha256", sourceManifest.path("content_sha256").asText());

        ObjectNode conversion = (ObjectNode) data.path("conversion");
        ObjectNode conversionManifest = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-v5-source-bundle/1").put("version", 1)
                .put("status", "PUBLIC_OBSERVED");
        conversionManifest = StrategyReadinessV5.withHash(conversionManifest);
        Path conversionPath = root.resolve("lineage/conversion-manifest.json");
        byte[] conversionBytes = json(conversionManifest);
        Files.write(conversionPath, conversionBytes);
        ((ObjectNode) conversion.path("source_artifact_manifest_reference"))
                .put("path", root.relativize(conversionPath).toString())
                .put("content_sha256", conversionManifest.path("content_sha256").asText())
                .put("byte_sha256", StrategyReadinessV5.hash(conversionBytes));
        conversion.put("source_artifact_manifest_sha256", conversionManifest.path("content_sha256").asText());
        Fixture fixture = new Fixture(data, sourceManifest, conversionManifest,
                sourcePath, conversionPath, root.resolve("data-manifest.json"));
        fixture.rewrite(true);
        return fixture;
    }

    private static String datasetRoot(ObjectNode data) {
        ObjectNode basis = JsonHashes.mapper().createObjectNode();
        for (String field : List.of("plan_sha256", "predictor_registry_sha256", "source_manifest_sha256",
                "source_manifest_reference", "source_dataset_root_sha256", "transformation_code_sha256",
                "label_code_sha256", "execution_code_sha256", "config_sha256", "precommit_sha256",
                "envelope_sha256", "artifacts")) if (data.has(field))
            basis.set(field, data.get(field).deepCopy());
        return StrategyReadinessV5.hash(basis);
    }

    private static byte[] json(JsonNode value) throws Exception {
        return (JsonHashes.mapper().writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static JsonNode loadPhysicalNullFixture() throws Exception {
        try (InputStream input = Objects.requireNonNull(
                StrategyReadinessV5PhysicalReadinessMatrixContractsTest.class.getResourceAsStream(
                        "/oracles/strategy-evaluator-v5-physical-null.json"),
                "physical readiness fixture is missing")) {
            return JsonHashes.mapper().readTree(input);
        }
    }

    private static final class Fixture {
        private final ObjectNode data;
        private final ObjectNode source;
        private final ObjectNode conversion;
        private final Path sourcePath;
        private final Path conversionPath;
        private final Path manifestPath;
        private byte[] manifestBytes;

        private Fixture(ObjectNode data, ObjectNode source, ObjectNode conversion,
                Path sourcePath, Path conversionPath, Path manifestPath) {
            this.data = data;
            this.source = source;
            this.conversion = conversion;
            this.sourcePath = sourcePath;
            this.conversionPath = conversionPath;
            this.manifestPath = manifestPath;
        }

        private void rewrite(boolean recomputeDatasetRoot) throws Exception {
            if (recomputeDatasetRoot) data.put("dataset_root_sha256", datasetRoot(data));
            data.put("content_sha256", StrategyReadinessV5.ownHash(data));
            manifestBytes = json(data);
            Files.write(manifestPath, manifestBytes);
        }
    }
}

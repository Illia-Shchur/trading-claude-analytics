package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/** Public readiness audit contracts for physical artifact reopening and hash binding. */
final class StrategyReadinessV5PhysicalAuditContractsTest {
    private static final long NOW = Instant.parse("2025-08-24T01:50:00Z").toEpochMilli();
    private static final String GENERATED_AT = "2025-08-24T01:50:00.000Z";
    private static final String COMMAND_SCHEMA = "strategy-v5-authoritative-command-receipt/1";
    private static final String DATA_SCHEMA = "strategy-v5-separated-artifacts/1";
    private static final List<String> DATA_ROLES = List.of("feature", "label", "execution", "mark");

    @TempDir
    Path temporary;

    @Test
    void physicalReferenceAndHashShapeFailuresRemainVisibleInThePublicAudit() throws Exception {
        ObjectNode missing = fixed();
        missing.putObject("evidence").set("cycle", artifact(temporary.resolve("missing.json"),
                "a".repeat(64), COMMAND_SCHEMA));
        assertThat(StrategyReadinessV5.buildReadinessAuditV5(missing).path("artifact_verification"))
                .isEmpty();

        Path malformedPath = temporary.resolve("malformed.json");
        byte[] malformedBytes = "{not-json".getBytes(StandardCharsets.UTF_8);
        Files.write(malformedPath, malformedBytes);
        ObjectNode malformed = fixed();
        malformed.putObject("evidence").set("cycle", artifact(malformedPath,
                StrategyReadinessV5.hash(malformedBytes), COMMAND_SCHEMA));
        ObjectNode malformedAudit = StrategyReadinessV5.buildReadinessAuditV5(malformed);
        assertArtifactFailures(malformedAudit, "ARTIFACT_NOT_JSON", "UNSUPPORTED_OR_MISMATCHED_SCHEMA");

        Path invalidShaPath = temporary.resolve("invalid-sha.json");
        Files.write(invalidShaPath, json(commandReceipt()));
        ObjectNode invalidSha = fixed();
        invalidSha.putObject("evidence").set("cycle", artifact(invalidShaPath, "short", COMMAND_SCHEMA));
        assertThat(StrategyReadinessV5.buildReadinessAuditV5(invalidSha).path("artifact_verification"))
                .isEmpty();
    }

    @Test
    void artifactVerificationSeparatesByteSchemaContentBindingAndExpiryFailures() throws Exception {
        Path path = temporary.resolve("receipt.json");
        ObjectNode receipt = commandReceipt();
        byte[] bytes = json(receipt);
        Files.write(path, bytes);

        ObjectNode byteMismatch = fixed();
        byteMismatch.putObject("evidence").set("cycle", artifact(path, "b".repeat(64), COMMAND_SCHEMA));
        assertArtifactFailures(StrategyReadinessV5.buildReadinessAuditV5(byteMismatch),
                "ARTIFACT_BYTE_HASH_MISMATCH");

        ObjectNode schemaMismatch = fixed();
        schemaMismatch.putObject("evidence").set("cycle", artifact(path,
                StrategyReadinessV5.hash(bytes), "strategy-v5-source-bundle/1"));
        assertArtifactFailures(StrategyReadinessV5.buildReadinessAuditV5(schemaMismatch),
                "UNSUPPORTED_OR_MISMATCHED_SCHEMA");

        ObjectNode contentMismatchValue = receipt.deepCopy();
        contentMismatchValue.putArray("limitations").add("changed-after-signing");
        Path contentPath = temporary.resolve("content-mismatch.json");
        Files.write(contentPath, json(contentMismatchValue));
        ObjectNode contentMismatch = fixed();
        contentMismatch.putObject("evidence").set("cycle", artifact(contentPath,
                StrategyReadinessV5.hash(json(contentMismatchValue)), COMMAND_SCHEMA));
        assertArtifactFailures(StrategyReadinessV5.buildReadinessAuditV5(contentMismatch),
                "CONTENT_HASH_MISMATCH");

        ObjectNode bindingMismatch = fixed();
        bindingMismatch.putObject("evidence").set("cycle", artifact(path,
                StrategyReadinessV5.hash(bytes), COMMAND_SCHEMA).put("content_sha256", "c".repeat(64)));
        assertArtifactFailures(StrategyReadinessV5.buildReadinessAuditV5(bindingMismatch),
                "CONTENT_HASH_BINDING_MISMATCH");

        ObjectNode expired = fixed();
        expired.putObject("evidence").set("cycle", artifact(path,
                StrategyReadinessV5.hash(bytes), COMMAND_SCHEMA).put("max_age_ms", 1));
        assertArtifactFailures(StrategyReadinessV5.buildReadinessAuditV5(expired), "ARTIFACT_EXPIRED");

        ObjectNode invalidDate = commandReceipt().put("generated_at", "not-a-timestamp");
        Path invalidDatePath = temporary.resolve("invalid-date.json");
        byte[] invalidDateBytes = json(invalidDate);
        Files.write(invalidDatePath, invalidDateBytes);
        ObjectNode invalidDateOptions = fixed();
        invalidDateOptions.putObject("evidence").set("cycle", artifact(invalidDatePath,
                StrategyReadinessV5.hash(invalidDateBytes), COMMAND_SCHEMA).put("max_age_ms", 1));
        assertArtifactFailures(StrategyReadinessV5.buildReadinessAuditV5(invalidDateOptions),
                "ARTIFACT_EXPIRED");

        ObjectNode centralInvalid = commandReceipt();
        centralInvalid.remove("status");
        centralInvalid.put("content_sha256", StrategyReadinessV5.ownHash(centralInvalid));
        Path centralInvalidPath = temporary.resolve("central-invalid.json");
        byte[] centralInvalidBytes = json(centralInvalid);
        Files.write(centralInvalidPath, centralInvalidBytes);
        ObjectNode centralInvalidOptions = fixed();
        centralInvalidOptions.putObject("evidence").set("cycle", artifact(centralInvalidPath,
                StrategyReadinessV5.hash(centralInvalidBytes), COMMAND_SCHEMA));
        assertArtifactFailures(StrategyReadinessV5.buildReadinessAuditV5(centralInvalidOptions),
                "CENTRAL_SCHEMA_VALIDATION_FAILED");
    }

    @Test
    void dependencyEvidenceIsReopenedAndDuplicateIdsAreRejected() throws Exception {
        ObjectNode parent = commandReceipt();
        ObjectNode child = commandReceipt().put("command", "validate");
        child.put("content_sha256", StrategyReadinessV5.ownHash(child));
        Path parentPath = temporary.resolve("parent.json");
        Path childPath = temporary.resolve("child.json");
        byte[] parentBytes = json(parent);
        byte[] childBytes = json(child);
        Files.write(parentPath, parentBytes);
        Files.write(childPath, childBytes);
        ObjectNode parentSpec = artifact(parentPath, StrategyReadinessV5.hash(parentBytes), COMMAND_SCHEMA);
        parentSpec.putArray("dependencies").add(artifact(childPath,
                StrategyReadinessV5.hash(childBytes), COMMAND_SCHEMA).put("id", "child"));
        ObjectNode options = fixed();
        options.putObject("evidence").set("root", parentSpec);
        ObjectNode audit = StrategyReadinessV5.buildReadinessAuditV5(options);
        assertThat(audit.path("artifact_verification")).hasSize(2);
        assertThat(audit.path("artifact_verification").get(0).path("verified").asBoolean()).isTrue();
        assertThat(audit.path("artifact_verification").get(1).path("id").asText()).isEqualTo("child");
        assertThat(audit.path("artifact_verification").get(1).path("verified").asBoolean()).isTrue();

        ObjectNode duplicateParent = parentSpec.deepCopy();
        ((ObjectNode) duplicateParent.path("dependencies").get(0)).put("id", "root");
        ObjectNode duplicate = fixed();
        duplicate.putObject("evidence").set("root", duplicateParent);
        assertThatThrownBy(() -> StrategyReadinessV5.buildReadinessAuditV5(duplicate))
                .hasMessageContaining("duplicate evidence id root");
    }

    @Test
    void evidenceManifestMustAgreeWithSuppliedPhysicalEntries() throws Exception {
        Path receiptPath = temporary.resolve("manifest-receipt.json");
        byte[] receiptBytes = json(commandReceipt());
        Files.write(receiptPath, receiptBytes);
        ObjectNode entry = artifact(receiptPath, StrategyReadinessV5.hash(receiptBytes), COMMAND_SCHEMA)
                .put("id", "cycle");
        ObjectNode manifest = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-readiness-evidence-manifest/1").put("version", 1)
                .put("status", "FROZEN");
        manifest.putArray("entries").add(entry);
        manifest = StrategyReadinessV5.withHash(manifest);
        Path manifestPath = temporary.resolve("manifest.json");
        byte[] manifestBytes = json(manifest);
        Files.write(manifestPath, manifestBytes);

        ObjectNode options = fixed();
        options.putObject("evidenceManifest")
                .put("path", manifestPath.toString())
                .put("sha256", StrategyReadinessV5.hash(manifestBytes))
                .put("content_sha256", manifest.path("content_sha256").asText());
        options.putObject("evidence").set("cycle", artifact(receiptPath,
                "d".repeat(64), COMMAND_SCHEMA));
        assertThatThrownBy(() -> StrategyReadinessV5.buildReadinessAuditV5(options))
                .hasMessageContaining("evidence manifest entry is not supplied exactly: cycle");
    }

    /** Genuine tiny Parquet files exercise the PAR1/size/hash guard without a large dataset. */
    @Test
    void separatedDataManifestReopensParquetMagicAndHashBoundary(@TempDir Path root) throws Exception {
        DataFixture fixture = dataFixture(root);
        ObjectNode options = fixed();
        options.putObject("evidence").set("data", artifact(fixture.manifestPath,
                StrategyReadinessV5.hash(fixture.manifestBytes), DATA_SCHEMA));
        ObjectNode audit = StrategyReadinessV5.buildReadinessAuditV5(options);
        JsonNode dataRow = audit.path("artifact_verification").get(0);
        assertThat(dataRow.path("verified").asBoolean()).isTrue();
        assertThat(audit.path("limitations")).extracting(JsonNode::asText)
                .doesNotContain("lineage:PHYSICAL_PARQUET_REOPEN_FAILED")
                .doesNotContain("lineage:SOURCE_MANIFEST:PHYSICAL_BYTES_MISSING_OR_TAMPERED")
                .doesNotContain("lineage:PARQUET_SOURCE_MANIFEST:PHYSICAL_BYTES_MISSING_OR_TAMPERED");
        assertThat(dimension(audit, "pit").path("capability").path("checks").get(0)
                .path("passed").asBoolean()).isTrue();
    }

    @Test
    void separatedDataManifestFailsClosedWhenAParquetRoleIsTampered(@TempDir Path root) throws Exception {
        DataFixture fixture = dataFixture(root);
        byte[] tampered = "not-parquet".getBytes(StandardCharsets.UTF_8);
        Files.write(fixture.rolePaths.get("feature"), tampered);
        ObjectNode options = fixed();
        options.putObject("evidence").set("data", artifact(fixture.manifestPath,
                StrategyReadinessV5.hash(fixture.manifestBytes), DATA_SCHEMA));
        ObjectNode audit = StrategyReadinessV5.buildReadinessAuditV5(options);
        assertThat(audit.path("limitations")).extracting(JsonNode::asText)
                .contains("lineage:PHYSICAL_PARQUET_REOPEN_FAILED",
                        "lineage:DATA_ROLE_FEATURE_BYTES_UNBOUND");
        assertThat(dimension(audit, "pit").path("capability").path("checks").get(0)
                .path("passed").asBoolean()).isFalse();
    }

    private static ObjectNode fixed() {
        return JsonHashes.mapper().createObjectNode().put("now", NOW).put("generatedAt", GENERATED_AT);
    }

    private static ObjectNode commandReceipt() {
        ObjectNode value = JsonHashes.mapper().createObjectNode()
                .put("schema", COMMAND_SCHEMA).put("version", 1)
                .put("command", "prospective-runner").put("status", "COMPLETE");
        value.putArray("inputs");
        value.putArray("outputs");
        value.putArray("limitations");
        value.putObject("details").put("active", false).put("activated", false);
        return StrategyReadinessV5.withHash(value);
    }

    private static ObjectNode artifact(Path path, String sha256, String schema) {
        return JsonHashes.mapper().createObjectNode().put("path", path.toString())
                .put("sha256", sha256).put("schema", schema);
    }

    private static byte[] json(JsonNode value) throws Exception {
        return (JsonHashes.mapper().writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void assertArtifactFailures(ObjectNode audit, String... failures) {
        assertThat(audit.path("artifact_verification")).hasSize(1);
        JsonNode row = audit.path("artifact_verification").get(0);
        assertThat(row.path("verified").asBoolean()).isFalse();
        assertThat(row.path("failures")).extracting(JsonNode::asText).contains(failures);
    }

    private static JsonNode dimension(ObjectNode audit, String id) {
        for (JsonNode row : audit.path("dimensions")) if (id.equals(row.path("id").asText())) return row;
        throw new AssertionError("missing dimension " + id);
    }

    private static DataFixture dataFixture(Path root) throws Exception {
        JsonNode source = loadPhysicalNullFixture();
        ObjectNode data = (ObjectNode) source.path("fixture").path("manifest").deepCopy();
        ObjectNode artifacts = (ObjectNode) data.path("artifacts");
        java.util.Map<String, Path> rolePaths = new java.util.LinkedHashMap<>();
        for (String role : DATA_ROLES) {
            Path path = root.resolve("parquet").resolve(role + ".parquet");
            Files.createDirectories(path.getParent());
            Path jsonl = root.resolve("jsonl").resolve(role + ".jsonl");
            Files.createDirectories(jsonl.getParent());
            ObjectNode physicalRow = JsonHashes.mapper().createObjectNode().put("role", role)
                    .put("event_time", 1_735_689_600_000L).put("availability_time", 1_735_689_659_000L);
            Files.writeString(jsonl, JsonHashes.mapper().writeValueAsString(physicalRow) + "\n",
                    StandardCharsets.UTF_8);
            ResearchData.writeParquet(jsonl, path);
            byte[] bytes = Files.readAllBytes(path);
            ObjectNode artifactRow = (ObjectNode) artifacts.path(role);
            artifactRow.put("path", root.relativize(path).toString());
            artifactRow.put("sha256", StrategyReadinessV5.hash(bytes));
            artifactRow.put("bytes", bytes.length);
            rolePaths.put(role, path);
        }

        ObjectNode sourceManifest = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-v5-source-bundle/1").put("version", 1)
                .put("status", "PUBLIC_OBSERVED");
        sourceManifest = StrategyReadinessV5.withHash(sourceManifest);
        Path sourcePath = root.resolve("lineage/source-manifest.json");
        byte[] sourceBytes = json(sourceManifest);
        Files.createDirectories(sourcePath.getParent());
        Files.write(sourcePath, sourceBytes);
        ObjectNode sourceReference = (ObjectNode) data.path("source_manifest_reference");
        sourceReference.put("path", root.relativize(sourcePath).toString())
                .put("content_sha256", sourceManifest.path("content_sha256").asText())
                .put("byte_sha256", StrategyReadinessV5.hash(sourceBytes));
        data.put("source_manifest_sha256", sourceManifest.path("content_sha256").asText());

        ObjectNode stagingManifest = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-v5-source-bundle/1").put("version", 1)
                .put("status", "PUBLIC_OBSERVED");
        stagingManifest = StrategyReadinessV5.withHash(stagingManifest);
        Path stagingPath = root.resolve("lineage/staging-manifest.json");
        byte[] stagingBytes = json(stagingManifest);
        Files.write(stagingPath, stagingBytes);
        ObjectNode stagingReference = (ObjectNode) data.path("conversion")
                .path("source_artifact_manifest_reference");
        stagingReference.put("path", root.relativize(stagingPath).toString())
                .put("content_sha256", stagingManifest.path("content_sha256").asText())
                .put("byte_sha256", StrategyReadinessV5.hash(stagingBytes));
        ((ObjectNode) data.path("conversion")).put("source_artifact_manifest_sha256",
                stagingManifest.path("content_sha256").asText());

        ObjectNode rootBasis = JsonHashes.mapper().createObjectNode();
        for (String field : List.of("plan_sha256", "predictor_registry_sha256", "source_manifest_sha256",
                "source_manifest_reference", "source_dataset_root_sha256", "transformation_code_sha256",
                "label_code_sha256", "execution_code_sha256", "config_sha256", "precommit_sha256",
                "envelope_sha256", "artifacts")) if (data.has(field))
            rootBasis.set(field, data.get(field).deepCopy());
        data.put("dataset_root_sha256", StrategyReadinessV5.hash(rootBasis));
        data.put("content_sha256", StrategyReadinessV5.ownHash(data));
        Path manifestPath = root.resolve("data-manifest.json");
        byte[] manifestBytes = json(data);
        Files.write(manifestPath, manifestBytes);
        return new DataFixture(manifestPath, manifestBytes, rolePaths);
    }

    private static JsonNode loadPhysicalNullFixture() throws Exception {
        try (InputStream input = Objects.requireNonNull(
                StrategyReadinessV5PhysicalAuditContractsTest.class.getResourceAsStream(
                        "/oracles/strategy-evaluator-v5-physical-null.json"),
                "physical readiness fixture is missing")) {
            return JsonHashes.mapper().readTree(input);
        }
    }

    private record DataFixture(Path manifestPath, byte[] manifestBytes,
            java.util.Map<String, Path> rolePaths) {}
}

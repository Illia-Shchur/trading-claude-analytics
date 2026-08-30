package com.tradinganalytics.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ScopeIndependentOutcomeCapabilityTest {
    @TempDir Path temporary;

    @Test
    void capabilityIsIdentityAddressedReopensAtEpochBoundariesAndOwnsOutcomes() throws Exception {
        Fixture fixture = fixture(true);
        PhysicalEvaluatorTrustRegistry registry = new PhysicalEvaluatorTrustRegistry();
        Object evaluator = new Object();
        AtomicInteger computations = new AtomicInteger();
        var configuration = fixture.configuration(context -> {
            computations.incrementAndGet();
            return Map.of("net_r", 0.2, "traded", true);
        });
        registry.register(evaluator, fixture.manifest(), temporary, fixture.provenance(), configuration);
        var capability = registry.getInternalScopeIndependentOutcomeCapability(evaluator);
        assertThat(capability).isNotNull();
        assertThat(registry.getInternalScopeIndependentOutcomeCapability(new Object())).isNull();
        assertThat(capability.schema())
                .isEqualTo(PhysicalEvaluatorTrustRegistry.OUTCOME_CAPABILITY_SCHEMA);
        assertThat(capability.descriptor().path("content_sha256").asText())
                .isEqualTo(JsonHashes.ownHash(capability.descriptor()));

        Map<String, Object> context = fixture.context();
        var epoch = capability.beginEvaluationScope(context);
        Map<String, Object> epochContext = new LinkedHashMap<>(context);
        epochContext.put("trustEpoch", epoch);
        epochContext.put("episodeId", "e1");
        epochContext.put("phase", "TRAIN_ONLY");
        assertThat(capability.verifyPitBoundary(epochContext)).isEqualTo(true);
        var outcome = capability.computeOutcome(epochContext);
        assertThat(outcome).isEqualTo(new PhysicalEvaluatorTrustRegistry.Outcome(0.2, true));
        Map<String, Object> verify = new LinkedHashMap<>(epochContext);
        verify.put("expectedOutcome", outcome);
        verify.put("result", outcome);
        assertThat(capability.verifyOutcome(verify)).isEqualTo(true);
        assertThat(computations).hasValue(1);
        assertThat(capability.endEvaluationScope(epoch)).isTrue();
        assertThat(capability.diagnostics()).isEqualTo(
                new PhysicalEvaluatorTrustRegistry.Diagnostics(
                        "strategy-v5-physical-trust-diagnostics/1", 2, 8, 2));

        assertThatThrownBy(() -> capability.computeOutcome(epochContext))
                .hasMessage("scope-independent outcome evaluation trust epoch is invalid or closed");
        assertThatThrownBy(() -> capability.endEvaluationScope(epoch))
                .hasMessage("scope-independent outcome evaluation trust epoch is invalid or already closed");

        Map<String, Object> unscoped = new LinkedHashMap<>(context);
        unscoped.put("episodeId", "e1");
        unscoped.put("result", Map.of("net_r", 999.0, "traded", true));
        assertThatThrownBy(() -> capability.verifyOutcome(unscoped))
                .hasMessageContaining("differs from the canonical physical recomputation for e1");

        Files.writeString(fixture.rawPath(), "post-load raw mutation\n");
        assertThatThrownBy(() -> capability.beginEvaluationScope(context))
                .hasMessageContaining("raw metadata bytes are missing or tampered");
        Files.write(fixture.rawPath(), fixture.rawBytes());
        Files.writeString(temporary.resolve("feature.parquet"), "post-load mutation\n");
        assertThatThrownBy(() -> capability.beginEvaluationScope(context))
                .hasMessage("scope-independent outcome capability feature bytes are missing or tampered");
    }

    @Test
    void capabilityRejectsMissingRoleBindingProofMetadataAndCallbackDeviations() throws Exception {
        Fixture fixture = fixture(true);
        PhysicalEvaluatorTrustRegistry registry = new PhysicalEvaluatorTrustRegistry();
        Map<String, PhysicalEvaluatorTrustRegistry.Artifact> withoutMark =
                new LinkedHashMap<>(fixture.manifest().artifacts());
        withoutMark.remove("mark");
        var missingMarkManifest = new PhysicalEvaluatorTrustRegistry.Manifest(
                fixture.manifest().contentSha256(), withoutMark);
        assertThatThrownBy(() -> registry.register(new Object(), missingMarkManifest, temporary,
                fixture.provenance(), fixture.configuration(context -> Map.of("net_r", 0.2))))
                .hasMessage("scope-independent outcome capability manifest lacks the mark role hash");

        Map<String, String> missingBinding = new LinkedHashMap<>(fixture.bindings());
        missingBinding.remove("metadata_artifact_sha256");
        var missingBindingConfiguration = new PhysicalEvaluatorTrustRegistry
                .ScopeIndependentOutcomeConfiguration(
                    fixture.evaluatorSpec(), missingBinding, fixture.proof(),
                    fixture.metadataBinding(), context -> true, context -> true,
                    context -> Map.of("net_r", 0.2));
        assertThatThrownBy(() -> registry.register(new Object(), fixture.manifest(), temporary,
                fixture.provenance(), missingBindingConfiguration))
                .hasMessageContaining("data bindings must contain exactly");

        ObjectNode badProof = fixture.proof().deepCopy();
        badProof.put("authority", "CALLER_ASSERTED");
        badProof.put("content_sha256", JsonHashes.ownHash(badProof));
        var badProofConfiguration = new PhysicalEvaluatorTrustRegistry
                .ScopeIndependentOutcomeConfiguration(
                    fixture.evaluatorSpec(), fixture.bindings(), badProof,
                    fixture.metadataBinding(), context -> true, context -> true,
                    context -> Map.of("net_r", 0.2));
        assertThatThrownBy(() -> registry.register(new Object(), fixture.manifest(), temporary,
                fixture.provenance(), badProofConfiguration))
                .hasMessage("scope-independent outcome capability proof is invalid or not loader-bound");

        ObjectNode badMetadata = fixture.metadataBinding().deepCopy();
        badMetadata.put("digest", "0".repeat(64));
        var badMetadataConfiguration = new PhysicalEvaluatorTrustRegistry
                .ScopeIndependentOutcomeConfiguration(
                    fixture.evaluatorSpec(), fixture.bindings(), fixture.proof(), badMetadata,
                    context -> true, context -> true, context -> Map.of("net_r", 0.2));
        assertThatThrownBy(() -> registry.register(new Object(), fixture.manifest(), temporary,
                fixture.provenance(), badMetadataConfiguration))
                .hasMessage("scope-independent outcome capability lacks the physically reopened metadata binding");

        var rejectingConfiguration = new PhysicalEvaluatorTrustRegistry
                .ScopeIndependentOutcomeConfiguration(
                    fixture.evaluatorSpec(), fixture.bindings(), fixture.proof(),
                    fixture.metadataBinding(), context -> false, context -> false,
                    context -> Map.of("net_r", 0.2));
        Object evaluator = new Object();
        registry.register(evaluator, fixture.manifest(), temporary,
                fixture.provenance(), rejectingConfiguration);
        var capability = registry.getInternalScopeIndependentOutcomeCapability(evaluator);
        assertThatThrownBy(() -> capability.verifyPitBoundary(fixture.context()))
                .hasMessage("loader-owned PIT verifier did not prove the physical episode boundary");
        Map<String, Object> outcomeContext = new LinkedHashMap<>(fixture.context());
        outcomeContext.put("episodeId", "e1");
        outcomeContext.put("result", Map.of("net_r", 0.2, "traded", true));
        assertThatThrownBy(() -> capability.verifyCachedOutcome(outcomeContext))
                .hasMessage("loader-owned cached outcome verifier did not prove the physical outcome");

        Map<String, Object> wrongContext = new LinkedHashMap<>(fixture.context());
        wrongContext.put("sourceArtifactSha256", "0".repeat(64));
        assertThatThrownBy(() -> capability.beginEvaluationScope(wrongContext))
                .hasMessage("scope-independent outcome capability context binding mismatch");
    }

    private Fixture fixture(boolean mark) throws Exception {
        Map<String, PhysicalEvaluatorTrustRegistry.Artifact> artifacts = new LinkedHashMap<>();
        for (String role : List.of("feature", "label", "execution", "mark")) {
            if (!mark && role.equals("mark")) continue;
            byte[] bytes = (role + "-physical-bytes\n").getBytes(StandardCharsets.UTF_8);
            Files.write(temporary.resolve(role + ".parquet"), bytes);
            artifacts.put(role, new PhysicalEvaluatorTrustRegistry.Artifact(
                    role + ".parquet", JsonHashes.sha256(bytes), (long) bytes.length));
        }
        String source = JsonHashes.sha256("source");
        String evaluatorSpec = JsonHashes.sha256("evaluator");
        var manifest = new PhysicalEvaluatorTrustRegistry.Manifest(source, artifacts);
        Path metadataDirectory = Files.createDirectories(temporary.resolve("metadata"));
        Path rawPath = metadataDirectory.resolve("raw.bin");
        byte[] rawBytes = "metadata-raw-physical-bytes\n".getBytes(StandardCharsets.UTF_8);
        Files.write(rawPath, rawBytes);
        ObjectNode rawReceipt = JsonHashes.mapper().createObjectNode();
        rawReceipt.put("schema", "strategy-v5-source-receipt/1");
        rawReceipt.put("version", 1);
        rawReceipt.put("path", "metadata/raw.bin");
        rawReceipt.put("source", "fixture");
        rawReceipt.put("byte_sha256", JsonHashes.sha256(rawBytes));
        rawReceipt.put("bytes", rawBytes.length);
        rawReceipt.put("content_sha256", JsonHashes.ownHash(rawReceipt));

        ObjectNode normalizedReceipt = JsonHashes.mapper().createObjectNode();
        normalizedReceipt.put("schema", "strategy-v5-source-receipt/1");
        normalizedReceipt.put("version", 1);
        normalizedReceipt.put("status", "PUBLIC_OBSERVED");
        normalizedReceipt.putArray("response_sha256").add(JsonHashes.sha256(rawBytes));
        normalizedReceipt.putArray("source_byte_sha256").add(JsonHashes.sha256(rawBytes));
        normalizedReceipt.putArray("raw_receipts").add(rawReceipt.deepCopy());
        normalizedReceipt.put("content_sha256", JsonHashes.ownHash(normalizedReceipt));
        byte[] normalizedBytes = (JsonHashes.mapper().writeValueAsString(normalizedReceipt) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        Path normalizedPath = metadataDirectory.resolve("contract.json");
        Files.write(normalizedPath, normalizedBytes);

        ObjectNode normalizedBinding = JsonHashes.mapper().createObjectNode();
        normalizedBinding.putObject("summary").put("path", "metadata/contract.json")
                .put("bytes", normalizedBytes.length);
        normalizedBinding.put("content_sha256",
                normalizedReceipt.path("content_sha256").asText());
        normalizedBinding.put("byte_sha256", JsonHashes.sha256(normalizedBytes));
        normalizedBinding.putArray("raw_byte_sha256").add(JsonHashes.sha256(rawBytes));
        normalizedBinding.putArray("raw_receipts").add(rawReceipt.deepCopy());
        ObjectNode receipts = JsonHashes.mapper().createObjectNode();
        for (String kind : List.of("CONTRACT_SPEC", "FEE_SCHEDULE", "EXECUTION_MODEL")) {
            ObjectNode receipt = receipts.putObject(kind);
            receipt.put("receipt_content_sha256", JsonHashes.sha256(kind + "-receipt"));
            receipt.put("receipt_byte_sha256", JsonHashes.sha256(kind + "-receipt-bytes"));
            receipt.putArray("normalized").add(normalizedBinding.deepCopy());
        }
        String metadataDigest = JsonHashes.canonicalSha256(receipts);
        ObjectNode metadataBinding = JsonHashes.mapper().createObjectNode();
        metadataBinding.put("root", temporary.toString());
        metadataBinding.set("receipts", receipts);
        metadataBinding.put("digest", metadataDigest);

        Map<String, String> bindings = new LinkedHashMap<>();
        bindings.put("feature_artifact_sha256", artifacts.get("feature").sha256());
        bindings.put("label_artifact_sha256", artifacts.get("label").sha256());
        bindings.put("execution_artifact_sha256", artifacts.get("execution").sha256());
        bindings.put("mark_artifact_sha256", mark ? artifacts.get("mark").sha256()
                : JsonHashes.sha256("missing mark"));
        bindings.put("metadata_artifact_sha256", metadataDigest);

        ObjectNode proof = JsonHashes.mapper().createObjectNode();
        proof.put("schema", PhysicalEvaluatorTrustRegistry.OUTCOME_PROOF_SCHEMA);
        proof.put("version", 1);
        proof.put("authority", "AUTHORITATIVE_V2_PHYSICAL_EVALUATOR");
        proof.put("verified", true);
        proof.put("source_artifact_sha256", source);
        proof.put("evaluator_spec_sha256", evaluatorSpec);
        proof.put("data_bindings_sha256", JsonHashes.canonicalSha256(bindings));
        proof.put("pit_boundary_contract", "CHECK_BEFORE_EVALUATION_AND_ON_CACHE_HIT");
        proof.put("outcome_role_contract",
                "FEATURE_LABEL_EXECUTION_MARK_METADATA_EXACT_BINDINGS");
        proof.put("one_episode_read_contract", true);
        proof.put("physical_evaluator_code_sha256", JsonHashes.sha256("physical-code"));
        proof.put("pit_validator_code_sha256", JsonHashes.sha256("pit-code"));
        proof.put("content_sha256", JsonHashes.ownHash(proof));

        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("source_manifest_sha256", source);
        provenance.put("feature_artifact_sha256", artifacts.get("feature").sha256());
        provenance.put("label_artifact_sha256", artifacts.get("label").sha256());
        provenance.put("execution_artifact_sha256", artifacts.get("execution").sha256());
        provenance.put("physical_role_binding", true);
        provenance.put("artifact_paths_bound", true);
        provenance.put("deterministic", true);
        return new Fixture(manifest, evaluatorSpec, bindings, proof, metadataBinding,
                provenance, rawPath, rawBytes);
    }

    private record Fixture(
            PhysicalEvaluatorTrustRegistry.Manifest manifest,
            String evaluatorSpec,
            Map<String, String> bindings,
            ObjectNode proof,
            ObjectNode metadataBinding,
            Map<String, Object> provenance,
            Path rawPath,
            byte[] rawBytes) {
        PhysicalEvaluatorTrustRegistry.ScopeIndependentOutcomeConfiguration configuration(
                PhysicalEvaluatorTrustRegistry.OutcomeComputer computer) {
            return new PhysicalEvaluatorTrustRegistry.ScopeIndependentOutcomeConfiguration(
                    evaluatorSpec, bindings, proof, metadataBinding,
                    context -> {
                        if (!"TRAIN_ONLY".equals(context.get("phase"))) {
                            throw new IllegalStateException("PIT context is not bound");
                        }
                        return true;
                    }, context -> true, computer);
        }

        Map<String, Object> context() {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("sourceArtifactSha256", manifest.contentSha256());
            context.put("evaluatorSpecSha256", evaluatorSpec);
            context.put("outcomeProofSha256", proof.path("content_sha256").asText());
            context.put("dataBindings", bindings);
            context.put("phase", "TRAIN_ONLY");
            context.put("episodeId", "e1");
            return context;
        }

        @Override public byte[] rawBytes() { return rawBytes.clone(); }
    }
}

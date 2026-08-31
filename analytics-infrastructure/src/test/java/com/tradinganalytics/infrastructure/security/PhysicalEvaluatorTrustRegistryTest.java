package com.tradinganalytics.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PhysicalEvaluatorTrustRegistryTest {
    @TempDir Path temporary;

    @Test
    void registrationBindsObjectIdentityManifestBytesAndProvenance() throws IOException {
        PhysicalEvaluatorTrustRegistry.Manifest manifest = manifest(temporary);
        Map<String, Object> provenance = provenance(manifest);
        Object evaluator = new Object();
        Object lookalike = new Object();
        PhysicalEvaluatorTrustRegistry registry = new PhysicalEvaluatorTrustRegistry();

        assertThat(registry.register(evaluator, manifest, temporary, provenance)).isSameAs(evaluator);
        assertThat(registry.isVerified(evaluator)).isTrue();
        assertThat(registry.isVerified(lookalike)).isFalse();
        assertThat(registry.isVerified(null)).isFalse();
    }

    @Test
    void registrationRejectsMissingTamperedLinkedOrMismatchedRoles() throws IOException {
        PhysicalEvaluatorTrustRegistry.Manifest manifest = manifest(temporary);
        Map<String, Object> provenance = provenance(manifest);
        PhysicalEvaluatorTrustRegistry registry = new PhysicalEvaluatorTrustRegistry();

        Files.writeString(temporary.resolve("label.bin"), "tampered");
        assertThatThrownBy(() -> registry.register(new Object(), manifest, temporary, provenance))
                .hasMessageContaining("tampered");

        Files.delete(temporary.resolve("label.bin"));
        Files.writeString(temporary.resolve("label.bin"), "label");
        PhysicalEvaluatorTrustRegistry.Manifest restored = manifestFromExisting(temporary);
        Map<String, Object> wrongProvenance = provenance(restored);
        wrongProvenance.put("execution_artifact_sha256", JsonHashes.sha256("wrong"));
        assertThatThrownBy(() -> registry.register(new Object(), restored, temporary, wrongProvenance))
                .hasMessageContaining("provenance");

        Files.createLink(temporary.resolve("feature-alias.bin"), temporary.resolve("feature.bin"));
        Map<String, PhysicalEvaluatorTrustRegistry.Artifact> artifacts = new LinkedHashMap<>(restored.artifacts());
        var feature = artifacts.get("feature");
        artifacts.put("feature", new PhysicalEvaluatorTrustRegistry.Artifact(
                "feature-alias.bin", feature.sha256(), feature.bytes()));
        PhysicalEvaluatorTrustRegistry.Manifest linked =
                new PhysicalEvaluatorTrustRegistry.Manifest(restored.contentSha256(), artifacts);
        assertThatThrownBy(() -> registry.register(new Object(), linked, temporary, provenance(linked)))
                .hasMessageContaining("singly-linked");
    }

    @Test
    void registrationRejectsSymlinkAndIncompleteManifest() throws IOException {
        PhysicalEvaluatorTrustRegistry.Manifest manifest = manifest(temporary);
        Path outside = temporary.resolve("outside.bin");
        Files.writeString(outside, "feature");
        Files.createSymbolicLink(temporary.resolve("feature-link.bin"), outside);
        Map<String, PhysicalEvaluatorTrustRegistry.Artifact> linkedArtifacts = new LinkedHashMap<>(manifest.artifacts());
        var feature = linkedArtifacts.get("feature");
        linkedArtifacts.put("feature", new PhysicalEvaluatorTrustRegistry.Artifact(
                "feature-link.bin", feature.sha256(), feature.bytes()));
        var linked = new PhysicalEvaluatorTrustRegistry.Manifest(manifest.contentSha256(), linkedArtifacts);
        assertThatThrownBy(() -> new PhysicalEvaluatorTrustRegistry().register(
                new Object(), linked, temporary, provenance(linked)))
                .hasMessageContaining("symlink");

        Map<String, PhysicalEvaluatorTrustRegistry.Artifact> incomplete = new LinkedHashMap<>(manifest.artifacts());
        incomplete.remove("execution");
        var missing = new PhysicalEvaluatorTrustRegistry.Manifest(manifest.contentSha256(), incomplete);
        assertThatThrownBy(() -> new PhysicalEvaluatorTrustRegistry().register(
                new Object(), missing, temporary, provenance(manifest)))
                .hasMessageContaining("lacks");
        assertThatThrownBy(() -> new PhysicalEvaluatorTrustRegistry().register(
                null, manifest, temporary, provenance(manifest)))
                .hasMessageContaining("evaluator");
    }

    private static PhysicalEvaluatorTrustRegistry.Manifest manifest(Path root) throws IOException {
        Files.writeString(root.resolve("feature.bin"), "feature");
        Files.writeString(root.resolve("label.bin"), "label");
        Files.writeString(root.resolve("execution.bin"), "execution");
        return manifestFromExisting(root);
    }

    private static PhysicalEvaluatorTrustRegistry.Manifest manifestFromExisting(Path root) throws IOException {
        Map<String, PhysicalEvaluatorTrustRegistry.Artifact> artifacts = new LinkedHashMap<>();
        for (String role : new String[] {"feature", "label", "execution"}) {
            String name = role + ".bin";
            byte[] bytes = Files.readAllBytes(root.resolve(name));
            artifacts.put(role, new PhysicalEvaluatorTrustRegistry.Artifact(
                    name, JsonHashes.sha256(bytes), (long) bytes.length));
        }
        return new PhysicalEvaluatorTrustRegistry.Manifest(JsonHashes.sha256("manifest"), artifacts);
    }

    private static Map<String, Object> provenance(PhysicalEvaluatorTrustRegistry.Manifest manifest) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("source_manifest_sha256", manifest.contentSha256());
        output.put("physical_role_binding", true);
        output.put("artifact_paths_bound", true);
        output.put("deterministic", true);
        manifest.artifacts().forEach((role, artifact) ->
                output.put(role + "_artifact_sha256", artifact.sha256()));
        return output;
    }
}

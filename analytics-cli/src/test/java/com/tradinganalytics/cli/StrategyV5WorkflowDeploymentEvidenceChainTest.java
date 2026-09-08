package com.tradinganalytics.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.research.v5.StrategyReadinessV5;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The deployment audit must admit a real signed SHADOW chain and block activation prerequisites. */
final class StrategyV5WorkflowDeploymentEvidenceChainTest {
    @TempDir Path temporary;

    @Test
    void signedCurrentChainIsShadowEligibleButActivationRemainsBlocked() throws Exception {
        Fixture fixture = fixture("valid");

        ObjectNode attestationOptions = JsonHashes.mapper().createObjectNode()
                .set("attestation", read(fixture.root().resolve("v5-actions-attestation.json")));
        attestationOptions.set("capture", read(fixture.root().resolve("github-deployment-settings-capture.json")));
        attestationOptions.set("trustedKeyRegistry", read(fixture.root().resolve("v5-attestation-key-registry.json")));
        attestationOptions.put("bytesSha256", JsonHashes.sha256(Files.readAllBytes(
                        fixture.root().resolve("github-deployment-settings-capture.json"))))
                .put("apiReceiptSha256", JsonHashes.sha256(Files.readAllBytes(
                        fixture.root().resolve("github-settings-api-receipt.json"))))
                .put("cycleReceiptSha256", JsonHashes.sha256(Files.readAllBytes(
                        fixture.root().resolve("v5-shadow-cycle-receipt.json"))))
                .put("trustedKeyRegistrySha256", read(fixture.root().resolve(
                        "v5-attestation-key-registry.json")).path("content_sha256").asText())
                .put("trustedKeyRegistryByteSha256", JsonHashes.sha256(Files.readAllBytes(
                        fixture.root().resolve("v5-attestation-key-registry.json"))))
                .put("pinnedFingerprint", fixture.environment().get("V5_ATTESTATION_KEY_FINGERPRINT"))
                .put("nowMs", System.currentTimeMillis());
        assertThat(StrategyReadinessV5.verifyActionsAttestation(attestationOptions)).isTrue();

        ObjectNode audit = StrategyV5WorkflowDeployment.makeDeploymentAudit(fixture.environment(), fixture.root());

        assertThat(audit.path("checks").path("github_settings_drift").asBoolean()).isTrue();
        assertThat(audit.path("checks").path("shadow_append_eligible").asBoolean()).isTrue();
        assertThat(audit.path("shadow_append_eligible").asBoolean()).isTrue();
        assertThat(audit.path("activation_eligible").asBoolean()).isFalse();
        assertThat(audit.path("blocked").asBoolean()).isTrue();
        assertThat(audit.path("blocked_until_external_prerequisites").asBoolean()).isTrue();
    }

    @Test
    void physicalApiPermissionAndPinnedKeyFaultsRemoveShadowEligibility() throws Exception {
        Fixture api = fixture("api-tamper");
        Files.writeString(api.root().resolve("github-settings-api-receipt.json"), "\n",
                java.nio.file.StandardOpenOption.APPEND);
        assertShadowBlocked(api);

        Fixture permissions = fixture("permissions-tamper");
        ObjectNode capture = read(permissions.root().resolve("github-deployment-settings-capture.json"));
        capture.with("actions_permissions").put("verified", false);
        writeJson(permissions.root().resolve("github-deployment-settings-capture.json"), withHash(capture));
        assertShadowBlocked(permissions);

        Fixture pinned = fixture("pinned-tamper");
        Map<String, String> wrongPin = new LinkedHashMap<>(pinned.environment());
        wrongPin.put("V5_ATTESTATION_KEY_FINGERPRINT", "0".repeat(64));
        ObjectNode audit = StrategyV5WorkflowDeployment.makeDeploymentAudit(wrongPin, pinned.root());
        assertThat(audit.path("shadow_append_eligible").asBoolean()).isFalse();
        assertThat(audit.path("blocked").asBoolean()).isTrue();
    }

    @Test
    void invalidCurrentOidcTimeAndCycleReceiptCannotBecomeShadowEligible() throws Exception {
        Fixture oidc = fixture("oidc-tamper");
        ObjectNode capture = read(oidc.root().resolve("github-deployment-settings-capture.json"));
        capture.with("oidc_claims").put("exp", 1L);
        writeJson(oidc.root().resolve("github-deployment-settings-capture.json"), withHash(capture));
        assertShadowBlocked(oidc);

        Fixture cycle = fixture("cycle-tamper");
        ObjectNode receipt = read(cycle.root().resolve("v5-shadow-cycle-receipt.json"));
        receipt.put("status", "ACTIVE");
        writeJson(cycle.root().resolve("v5-shadow-cycle-receipt.json"), withHash(receipt));
        assertShadowBlocked(cycle);
    }

    private static void assertShadowBlocked(Fixture fixture) {
        ObjectNode audit = StrategyV5WorkflowDeployment.makeDeploymentAudit(
                fixture.environment(), fixture.root());
        assertThat(audit.path("shadow_append_eligible").asBoolean()).isFalse();
        assertThat(audit.path("activation_eligible").asBoolean()).isFalse();
        assertThat(audit.path("blocked").asBoolean()).isTrue();
    }

    private Fixture fixture(String label) throws Exception {
        ObjectNode all = activationFixture();
        Path root = temporary.resolve(label);
        Files.createDirectories(root);
        Map<String, String> names = Map.of(
                "capture.json", "github-deployment-settings-capture.json",
                "api.json", "github-settings-api-receipt.json",
                "drift.json", "github-settings-drift-evidence.json",
                "cycle.json", "v5-shadow-cycle-receipt.json",
                "attestation.json", "v5-actions-attestation.json",
                "registry.json", "v5-attestation-key-registry.json",
                "writer.json", "github-writer-installation-receipt.json");
        for (Map.Entry<String, String> name : names.entrySet()) {
            byte[] bytes = Base64.getDecoder().decode(all.path("files").path(name.getKey()).asText());
            Files.write(root.resolve(name.getValue()), bytes);
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ObjectNode capture = read(root.resolve("github-deployment-settings-capture.json"));
        capture.put("captured_at", now.toString());
        ObjectNode claims = (ObjectNode) capture.path("oidc_claims");
        long nowSeconds = now.getEpochSecond();
        claims.put("iat", nowSeconds - 30).put("exp", nowSeconds + 600);
        capture = withHash(capture);
        writeJson(root.resolve("github-deployment-settings-capture.json"), capture);

        ObjectNode drift = read(root.resolve("github-settings-drift-evidence.json"));
        drift.put("current_capture_sha256", capture.path("content_sha256").asText())
                .put("compared_at", now.toString());
        drift = withHash(drift);
        ResearchSchemaRegistry.defaultRegistry().validateContractSchema(drift);
        writeJson(root.resolve("github-settings-drift-evidence.json"), drift);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = generator.generateKeyPair();
        String publicPem = pem("PUBLIC KEY", keyPair.getPublic().getEncoded());
        String privatePem = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
        String fingerprint = JsonHashes.sha256(publicPem);
        ObjectNode registry = read(root.resolve("v5-attestation-key-registry.json"));
        ObjectNode key = (ObjectNode) registry.path("keys").get(0);
        key.put("public_key_pem", publicPem).put("fingerprint", fingerprint)
                .put("valid_from", now.minusSeconds(60).toString())
                .put("valid_until", now.plusSeconds(3_600).toString());
        registry = withHash(registry);
        writeJson(root.resolve("v5-attestation-key-registry.json"), registry);

        byte[] captureBytes = Files.readAllBytes(root.resolve("github-deployment-settings-capture.json"));
        byte[] apiBytes = Files.readAllBytes(root.resolve("github-settings-api-receipt.json"));
        byte[] cycleBytes = Files.readAllBytes(root.resolve("v5-shadow-cycle-receipt.json"));
        byte[] registryBytes = Files.readAllBytes(root.resolve("v5-attestation-key-registry.json"));
        ObjectNode originalAttestation = read(root.resolve("v5-actions-attestation.json"));
        originalAttestation.put("public_key_pem", publicPem)
                .put("settings_capture_sha256", capture.path("content_sha256").asText())
                .put("settings_capture_byte_sha256", JsonHashes.sha256(captureBytes))
                .put("api_receipt_sha256", JsonHashes.sha256(apiBytes))
                .put("cycle_receipt_sha256", JsonHashes.sha256(cycleBytes))
                .put("trusted_key_registry_sha256", registry.path("content_sha256").asText())
                .put("trusted_key_registry_byte_sha256", JsonHashes.sha256(registryBytes))
                .put("issued_at", now.minusSeconds(30).toString())
                .put("expires_at", now.plusSeconds(599).toString());
        ObjectNode signOptions = JsonHashes.mapper().createObjectNode().put("privateKeyPem", privatePem);
        ObjectNode fields = originalAttestation.deepCopy();
        fields.remove(java.util.List.of("schema", "version", "protected", "signature",
                "attestation_payload_sha256", "content_sha256"));
        signOptions.set("fields", fields);
        writeJson(root.resolve("v5-actions-attestation.json"),
                StrategyReadinessV5.signActionsAttestationV5(signOptions));

        return new Fixture(root, Map.of("V5_ATTESTATION_KEY_FINGERPRINT", fingerprint));
    }

    private static ObjectNode activationFixture() throws Exception {
        try (InputStream input = Objects.requireNonNull(
                StrategyV5WorkflowDeploymentEvidenceChainTest.class.getResourceAsStream(
                        "/oracles/strategy-readiness-v5-activation.json"))) {
            ObjectNode encoded = (ObjectNode) JsonHashes.mapper().readTree(input);
            byte[] compressed = Base64.getDecoder().decode(encoded.path("payload").asText());
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                return (ObjectNode) JsonHashes.mapper().readTree(gzip);
            }
        }
    }

    private static ObjectNode read(Path path) throws Exception {
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(path));
    }

    private static ObjectNode withHash(ObjectNode value) {
        ObjectNode copy = value.deepCopy();
        copy.put("content_sha256", JsonHashes.ownHash(copy));
        return copy;
    }

    private static void writeJson(Path path, JsonNode value) throws Exception {
        Files.writeString(path, JsonHashes.mapper().writeValueAsString(value) + "\n");
    }

    private static String pem(String type, byte[] encoded) {
        String base64 = Base64.getEncoder().encodeToString(encoded);
        StringBuilder lines = new StringBuilder("-----BEGIN ").append(type).append("-----\n");
        for (int index = 0; index < base64.length(); index += 64) {
            lines.append(base64, index, Math.min(index + 64, base64.length())).append('\n');
        }
        return lines.append("-----END ").append(type).append("-----\n").toString();
    }

    private record Fixture(Path root, Map<String, String> environment) {}

}

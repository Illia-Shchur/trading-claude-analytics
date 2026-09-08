package com.tradinganalytics.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the complete physical prospective-snapshot chain, then one fault at a time. */
final class WorkflowSecurityV5EvidenceChainTest {
    @TempDir Path temporary;

    @Test
    void verifiesSignedSnapshotAgainstTrustedGenesisAndReturnsItsLedgerTip() throws Exception {
        Fixture fixture = fixture("valid");

        WorkflowSecurityV5.ProspectiveSnapshotVerification result = verify(fixture);

        assertThat(result.verified()).isTrue();
        assertThat(result.sequence()).isEqualTo(1);
        assertThat(result.trustedBaseSequence()).isEqualTo(0);
        assertThat(result.head()).isEqualTo(fixture.eventHead());
    }

    @Test
    void acceptsTheRawPublicKeySerializationProducedByTheAttestationSigner() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("raw-attestation"));
        Files.write(root.resolve("v5-actions-attestation.json"), activationFile("attestation.json"));

        assertThat(SafeTreeVerifier.verify(
                root, "attestation", SafeTreeVerifier.Options.EVIDENCE).files()).isEqualTo(1);
    }

    @Test
    void permitsSchemaBoundPublicKeyButRejectsMalformedOrMisplacedPem() throws Exception {
        byte[] raw = activationFile("attestation.json");
        String publicMarker = "-----BEGIN PUBLIC KEY-----";
        String endMarker = "-----END PUBLIC KEY-----";
        String escaped = "\\u002d".repeat(5) + "BEGIN PUBLIC KEY" + "\\u002d".repeat(5);
        String escapedEnd = "\\u002d".repeat(5) + "END PUBLIC KEY" + "\\u002d".repeat(5);

        Path escapedRoot = Files.createDirectory(temporary.resolve("escaped-attestation"));
        Files.writeString(escapedRoot.resolve("v5-actions-attestation.json"),
                new String(raw, StandardCharsets.UTF_8)
                        .replace(publicMarker, escaped).replace(endMarker, escapedEnd));
        assertThat(SafeTreeVerifier.verify(
                escapedRoot, "attestation", SafeTreeVerifier.Options.EVIDENCE).files()).isEqualTo(1);

        ObjectNode misplaced = (ObjectNode) JsonHashes.mapper().readTree(raw);
        String pem = misplaced.path("public_key_pem").asText();
        misplaced.remove("public_key_pem");
        misplaced.put("metadata", pem);
        Path misplacedRoot = Files.createDirectory(temporary.resolve("misplaced-attestation"));
        Files.writeString(misplacedRoot.resolve("v5-actions-attestation.json"),
                JsonHashes.mapper().writeValueAsString(misplaced) + "\n");
        assertThatThrownBy(() -> SafeTreeVerifier.verify(
                misplacedRoot, "attestation", SafeTreeVerifier.Options.EVIDENCE))
                .hasMessageContaining("contains key/PEM material");

        String escapedPrivateBegin = "\\u002d".repeat(5) + "BEGIN PRIVATE KEY" + "\\u002d".repeat(5);
        String escapedPrivateEnd = "\\u002d".repeat(5) + "END PRIVATE KEY" + "\\u002d".repeat(5);
        Path escapedPrivateRoot = Files.createDirectory(temporary.resolve("escaped-private-attestation"));
        String escapedPrivateJson = new String(raw, StandardCharsets.UTF_8)
                .replace("\"public_key_pem\"", "\"metadata\"")
                .replace(publicMarker, escapedPrivateBegin)
                .replace(endMarker, escapedPrivateEnd);
        Files.writeString(escapedPrivateRoot.resolve("v5-actions-attestation.json"), escapedPrivateJson);
        assertThatThrownBy(() -> SafeTreeVerifier.verify(
                escapedPrivateRoot, "attestation", SafeTreeVerifier.Options.EVIDENCE))
                .hasMessageContaining("contains key/PEM material");

        ObjectNode wrongSchema = (ObjectNode) JsonHashes.mapper().readTree(raw);
        wrongSchema.put("schema", "strategy-v5-actions-attestation/1");
        Path wrongSchemaRoot = Files.createDirectory(temporary.resolve("wrong-schema-attestation"));
        Files.writeString(wrongSchemaRoot.resolve("v5-actions-attestation.json"),
                JsonHashes.mapper().writeValueAsString(wrongSchema) + "\n");
        assertThatThrownBy(() -> SafeTreeVerifier.verify(
                wrongSchemaRoot, "attestation", SafeTreeVerifier.Options.EVIDENCE))
                .hasMessageContaining("contains key/PEM material");

        ObjectNode pemKey = JsonHashes.mapper().createObjectNode();
        pemKey.put(pem, true);
        Path pemKeyRoot = Files.createDirectory(temporary.resolve("pem-key-attestation"));
        Files.writeString(pemKeyRoot.resolve("v5-actions-attestation.json"),
                JsonHashes.mapper().writeValueAsString(pemKey) + "\n");
        assertThatThrownBy(() -> SafeTreeVerifier.verify(
                pemKeyRoot, "attestation", SafeTreeVerifier.Options.EVIDENCE))
                .hasMessageContaining("contains key/PEM material");
    }

    @Test
    void rejectsIndependentIdentityHashScopeTimeRegistryAndLedgerFaults() throws Exception {
        Fixture apiIdentity = fixture("api-identity");
        ObjectNode api = read(apiIdentity.root().resolve("github-settings-api-receipt.json"));
        api.put("repository", "other/repository");
        writeJson(apiIdentity.root().resolve("github-settings-api-receipt.json"), withHash(api));
        assertThatThrownBy(() -> verify(apiIdentity))
                .hasMessageContaining("settings API receipt is not bound");

        Fixture identity = fixture("identity");
        ObjectNode capture = read(identity.root().resolve("github-deployment-settings-capture.json"));
        capture.put("repository_id", 2L);
        writeJson(identity.root().resolve("github-deployment-settings-capture.json"), withHash(capture));
        assertThatThrownBy(() -> verify(identity))
                .hasMessageMatching(".*(capture|writer|repository|identity|binding).*" );

        Fixture writer = fixture("writer");
        ObjectNode writerReceipt = read(writer.root().resolve("github-writer-installation-receipt.json"));
        writerReceipt.put("installation_id", 999L);
        writeJson(writer.root().resolve("github-writer-installation-receipt.json"), withHash(writerReceipt));
        assertThatThrownBy(() -> verify(writer))
                .hasMessageMatching("(?s).*(writer installation|constant value).*" );

        Fixture attestation = fixture("attestation");
        ObjectNode attestationJson = read(attestation.root().resolve("v5-actions-attestation.json"));
        attestationJson.put("signature", "bad-signature");
        writeJson(attestation.root().resolve("v5-actions-attestation.json"), withHash(attestationJson));
        assertThatThrownBy(() -> verify(attestation)).hasMessageContaining("signature is invalid");

        Fixture apiBytes = fixture("api-bytes");
        Files.writeString(apiBytes.root().resolve("github-settings-api-receipt.json"), "\n",
                java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(() -> verify(apiBytes)).hasMessageContaining("not bound");

        Fixture hash = fixture("hash");
        Files.writeString(hash.root().resolve("v5-shadow-cycle-receipt.json"), "tampered\n");
        assertThatThrownBy(() -> verify(hash)).hasMessageContaining("not valid JSON");

        Fixture scope = fixture("scope");
        Files.writeString(scope.root().resolve("unexpected.json"), "{}\n");
        assertThatThrownBy(() -> verify(scope)).hasMessageContaining("root inventory is not exact");

        Fixture time = fixture("time");
        WorkflowSecurityV5.ProspectiveSnapshotOptions expired = new WorkflowSecurityV5.ProspectiveSnapshotOptions(
                time.root(), time.trusted(), null, time.attestationFingerprint(),
                time.nowAt() + 3 * 60_000L);
        assertThatThrownBy(() -> WorkflowSecurityV5.verifyProspectiveSnapshotV5(expired))
                .hasMessageMatching(".*(freshness|nonce|expired|lease).*" );

        Fixture registry = fixture("registry");
        ObjectNode registryJson = read(registry.root().resolve("v5-attestation-key-registry.json"));
        registryJson.put("generation", 2);
        writeJson(registry.root().resolve("v5-attestation-key-registry.json"), withHash(registryJson));
        assertThatThrownBy(() -> verify(registry)).hasMessageContaining("differs from the trusted-base");

        Fixture ledger = fixture("ledger");
        Path event = ledger.root().resolve(
                "ledger/events/000000000001-5e55419a3ddc65ed3140a20c9af2ef9be79fa51b6818edc3442a40d24ca4b23d.json");
        Files.writeString(event, "tampered-ledger-event\n");
        assertThatThrownBy(() -> verify(ledger)).hasMessageContaining("not valid JSON");
    }

    private WorkflowSecurityV5.ProspectiveSnapshotVerification verify(Fixture fixture) {
        return WorkflowSecurityV5.verifyProspectiveSnapshotV5(
                new WorkflowSecurityV5.ProspectiveSnapshotOptions(
                        fixture.root(), fixture.trusted(), null, fixture.attestationFingerprint(),
                        fixture.nowAt()));
    }

    private Fixture fixture(String label) throws Exception {
        ObjectNode encoded;
        try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream(
                "/oracles/strategy-readiness-v5-activation.json"))) {
            encoded = (ObjectNode) JsonHashes.mapper().readTree(input);
        }
        byte[] compressed = Base64.getDecoder().decode(encoded.path("payload").asText());
        ObjectNode all;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            all = (ObjectNode) JsonHashes.mapper().readTree(gzip);
        }
        ObjectNode options = (ObjectNode) all.path("options").deepCopy();
        Path parent = temporary.resolve(label);
        Files.createDirectories(parent);
        byte[] completedCycle = Base64.getDecoder().decode(
                all.path("files").path("cycle.json").asText());
        Path root = parent.resolve(JsonHashes.sha256(completedCycle));
        Files.createDirectories(root);
        Files.write(root.resolve("v5-shadow-cycle.json"), completedCycle);
        Map<String, String> names = Map.of(
                "cycle.json", "v5-shadow-cycle-receipt.json",
                "api.json", "github-settings-api-receipt.json",
                "capture.json", "github-deployment-settings-capture.json",
                "drift.json", "github-settings-drift-evidence.json",
                "attestation.json", "v5-actions-attestation.json",
                "registry.json", "v5-attestation-key-registry.json",
                "writer.json", "github-writer-installation-receipt.json");
        for (Map.Entry<String, String> name : names.entrySet()) {
            Files.write(root.resolve(name.getValue()),
                    Base64.getDecoder().decode(all.path("files").path(name.getKey()).asText()));
        }
        writeDeploymentAudit(root);
        copyLedger(all, root.resolve("ledger"));
        replace(options, "<TEMP_ROOT>", root.toString());

        Path trusted = parent.resolve("trusted");
        Path trustedRegistry = trusted.resolve("strategy-research/config/v5-attestation-key-registry.json");
        Files.createDirectories(trustedRegistry.getParent());
        byte[] registryBytes = Base64.getDecoder().decode(all.path("files").path("registry.json").asText());
        Files.write(trustedRegistry, registryBytes);
        ObjectNode proposedHead = read(root.resolve("ledger/HEAD.json"));
        String lineage = proposedHead.path("lineage_sha256").asText();
        Path trustedLedger = trusted.resolve("evidence/prospective-v5/" + "a".repeat(64) + "/ledger");
        Files.createDirectories(trustedLedger.resolve("events"));
        ObjectNode trustedHead = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-prospective-ledger-index/1")
                .put("version", 1).put("lineage_sha256", lineage).put("sequence", 0)
                .put("head_sha256", WorkflowSecurityV5.prospectiveLedgerGenesis(lineage))
                .putNull("prior_snapshot_root").putNull("prior_head_sha256");
        trustedHead.putArray("event_refs");
        trustedHead.putArray("assets").add("btc");
        trustedHead.put("frozen_start", "2025-08-23T01:50:00.000Z")
                .put("frozen_end", "2025-08-25T01:50:00.000Z");
        writeJson(trustedLedger.resolve("HEAD.json"), withHash(trustedHead));
        return new Fixture(root, trusted, options.path("nowAt").asLong(),
                options.path("githubAttestationPublicKeyFingerprint").asText(),
                proposedHead.path("head_sha256").asText());
    }

    private static void writeDeploymentAudit(Path root) throws Exception {
        ObjectNode audit = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-deployment-audit/1").put("version", 1)
                .putNull("settings_capture_sha256");
        audit.putObject("checks").put("shadow_append_eligible", false);
        audit.put("shadow_append_eligible", false).put("activation_eligible", false)
                .put("blocked", true).put("reason", "test fixture remains shadow-only")
                .put("blocked_until_external_prerequisites", true);
        audit.putArray("exact_external_verification_required")
                .add("test-only external authorization");
        writeJson(root.resolve("v5-deployment-audit.json"), withHash(audit));
    }

    private static void copyLedger(ObjectNode all, Path destination) throws Exception {
        Files.createDirectories(destination.resolve("events"));
        byte[] head = Base64.getDecoder().decode(all.path("files").path("ledger/HEAD.json").asText());
        byte[] event = Base64.getDecoder().decode(all.path("files")
                .path("ledger/events/000000000001-5e55419a3ddc65ed3140a20c9af2ef9be79fa51b6818edc3442a40d24ca4b23d.json")
                .asText());
        Files.write(destination.resolve("HEAD.json"), head);
        Files.write(destination.resolve(
                "events/000000000001-5e55419a3ddc65ed3140a20c9af2ef9be79fa51b6818edc3442a40d24ca4b23d.json"), event);
    }

    private static void replace(JsonNode node, String from, String to) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value.isTextual() && value.asText().contains(from)) {
                    ((ObjectNode) node).put(entry.getKey(), value.asText().replace(from, to));
                } else replace(value, from, to);
            });
        } else if (node.isArray()) node.forEach(value -> replace(value, from, to));
    }

    private static ObjectNode read(Path path) throws Exception {
        return (ObjectNode) JsonHashes.mapper().readTree(Files.readAllBytes(path));
    }

    private static ObjectNode withHash(ObjectNode value) {
        ObjectNode copy = value.deepCopy();
        copy.put("content_sha256", JsonHashes.ownHash(copy));
        return copy;
    }

    private static void writeJson(Path path, ObjectNode value) throws Exception {
        Files.writeString(path, JsonHashes.mapper().writeValueAsString(value) + "\n");
    }

    private byte[] activationFile(String name) throws Exception {
        try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream(
                "/oracles/strategy-readiness-v5-activation.json"))) {
            ObjectNode encoded = (ObjectNode) JsonHashes.mapper().readTree(input);
            byte[] compressed = Base64.getDecoder().decode(encoded.path("payload").asText());
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                JsonNode all = JsonHashes.mapper().readTree(gzip);
                return Base64.getDecoder().decode(all.path("files").path(name).asText());
            }
        }
    }

    private record Fixture(Path root, Path trusted, long nowAt, String attestationFingerprint,
                           String eventHead) {}
}

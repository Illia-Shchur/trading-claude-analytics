package com.tradinganalytics.research.v5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.CanonicalJson;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Node differentials and physical/security regression gates for strategy-readiness-v5. */
final class StrategyReadinessV5NodeOracleTest {
    private static final long NOW = Instant.parse("2025-08-24T01:50:00Z").toEpochMilli();
    private static final String GENERATED_AT = "2025-08-24T01:50:00.000Z";

    @Test
    void allTenNodeExportsHaveJavaBindings() {
        Set<String> expected = Set.of("buildReadinessAuditV5", "environmentReviewSafe", "hash", "ownHash",
                "renderReadinessMarkdown", "signActionsAttestationV5", "verifyActionsAttestation",
                "verifyActivationBundleV5", "withHash", "writeReadinessAudit");
        Set<String> actual = Set.of(StrategyReadinessV5.class.getDeclaredMethods()).stream()
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())
                        && java.lang.reflect.Modifier.isStatic(method.getModifiers()))
                .map(java.lang.reflect.Method::getName).collect(Collectors.toSet());
        assertThat(actual).containsAll(expected);
    }

    @Test
    void canonicalHashHelpersMatchNodeAcrossDeterministicProperties() throws Exception {
        for (int index = 0; index < 32; index++) {
            ObjectNode value = JsonHashes.mapper().createObjectNode();
            value.put("z", index * 17 - 9).put("unicode", "Київ-" + index)
                    .put("fraction", (index - 16) / 7.0).put("flag", (index & 1) == 0);
            value.putObject("nested").put("b", index % 3).put("a", "row-" + (31 - index));
            value.putArray("values").add(index).addNull().add(index / 10.0);
            JsonNode expected = frozen("hash").get(index).path("result");
            assertThat(StrategyReadinessV5.hash(value)).isEqualTo(expected.path("hash").asText());
            assertThat(StrategyReadinessV5.ownHash(value)).isEqualTo(expected.path("ownHash").asText());
            assertJson(StrategyReadinessV5.withHash(value), expected.path("withHash"));
        }
    }

    @Test
    void environmentReviewerSafetyMatrixMatchesNode() throws Exception {
        ArrayNode values = JsonHashes.mapper().createArrayNode();
        values.add(review(0, 0, 0, false));
        values.add(review(0, 1, 1, false));
        values.add(review(1, 1, 1, true));
        values.add(review(1, 1, 1, false));
        values.add(review(2, 2, 2, true));
        values.add(review(1, 2, 2, true));
        values.addObject().put("reviewer_count", "1").put("required_reviewer_rule_count", 1)
                .put("protection_rule_count", 1).put("prevent_self_review", true);
        values.addObject().put("reviewer_count", 1.5).put("required_reviewer_rule_count", 1)
                .put("protection_rule_count", 1).put("prevent_self_review", true);
        values.addObject().put("reviewer_count", 1).put("protection_rule_count", 1)
                .put("prevent_self_review", true);
        values.addObject().put("reviewer_count", 0).put("protection_rule_count", 1)
                .put("prevent_self_review", false);
        ArrayNode actual = JsonHashes.mapper().createArrayNode();
        values.forEach(value -> actual.add(StrategyReadinessV5.environmentReviewSafe(value)));
        assertJson(actual, frozen("environment"));
        assertThat(actual.toString()).isEqualTo("[true,false,true,false,true,false,false,false,true,false]");
    }

    @Test
    void emptyEvidenceAuditAndMarkdownMatchNodeExactly() throws Exception {
        ObjectNode options = fixedOptions();
        ObjectNode audit = StrategyReadinessV5.buildReadinessAuditV5(options);
        assertJson(audit, frozen("audit").path("empty").path("audit"));
        assertThat(audit.path("dimensions")).hasSize(8);
        assertThat(audit.path("content_sha256").asText())
                .isEqualTo("f0d46f46d9cda51903b87437e1a6ebadc74807a34e68b8e3f05ddf9e7145c163");
        assertThat(StrategyReadinessV5.renderReadinessMarkdown(audit))
                .isEqualTo(frozen("audit").path("empty").path("markdown").asText());
    }

    @Test
    void physicalArtifactAndTamperResultsMatchNode() throws Exception {
        Path root = Files.createTempDirectory("readiness-v5-physical-");
        String schema = "strategy-v5-authoritative-command-receipt/1";
        ObjectNode command = commandReceipt();
        Path path = root.resolve("cycle.json");
        byte[] bytes = compactBytes(command); Files.write(path, bytes);
        ObjectNode options = fixedOptions();
        options.putObject("evidence").set("githubCycleReceipt",
                artifact(path, StrategyReadinessV5.hash(bytes), schema));
        assertAuditMatchesFrozen(options, "physicalVerified", true);

        ObjectNode tampered = command.deepCopy().put("status", "TAMPERED");
        Files.write(path, compactBytes(tampered));
        ObjectNode actual = assertAuditMatchesFrozen(options, "physicalTampered", false);
        assertThat(actual.path("artifact_verification").get(0).path("failures"))
                .extracting(JsonNode::asText).contains("ARTIFACT_BYTE_HASH_MISMATCH", "CONTENT_HASH_MISMATCH");
    }

    @Test
    void duplicateAndExpiredPhysicalEvidenceFailClosedLikeNode() throws Exception {
        Path root = Files.createTempDirectory("readiness-v5-expiry-");
        String schema = "strategy-v5-authoritative-command-receipt/1";
        ObjectNode command = commandReceipt();
        Path path = root.resolve("cycle.json"); byte[] bytes = compactBytes(command); Files.write(path, bytes);
        ObjectNode spec = artifact(path, StrategyReadinessV5.hash(bytes), schema);
        ObjectNode duplicate = fixedOptions(); duplicate.putObject("evidence").putArray("githubCycleReceipt")
                .add(spec).add(spec.deepCopy());
        assertThatThrownBy(() -> StrategyReadinessV5.buildReadinessAuditV5(duplicate))
                .hasMessage(frozenFailure("duplicate"))
                .hasMessageContaining("duplicate evidence id");

        ObjectNode dated = StrategyReadinessV5.withHash(command.deepCopy()
                .put("generated_at", "2025-08-24T01:00:00.000Z"));
        Path datedPath = root.resolve("dated.json"); byte[] datedBytes = compactBytes(dated); Files.write(datedPath, datedBytes);
        ObjectNode expiry = fixedOptions(); ObjectNode expirySpec = artifact(datedPath,
                StrategyReadinessV5.hash(datedBytes), schema).put("max_age_ms", 1_000);
        expiry.putObject("evidence").set("githubCycleReceipt", expirySpec);
        ObjectNode actual = assertAuditMatchesFrozen(expiry, "expired", false);
        assertThat(actual.path("artifact_verification").get(0).path("failures"))
                .extracting(JsonNode::asText).contains("ARTIFACT_EXPIRED");
    }

    @Test
    void evidenceManifestAndManifestTamperMatchNode() throws Exception {
        Path root = Files.createTempDirectory("readiness-v5-manifest-");
        String schema = "strategy-v5-authoritative-command-receipt/1";
        ObjectNode command = commandReceipt(); Path commandPath = root.resolve("cycle.json");
        byte[] commandBytes = compactBytes(command); Files.write(commandPath, commandBytes);
        ObjectNode manifest = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-readiness-evidence-manifest/1").put("version", 1).put("status", "FROZEN");
        manifest.putArray("entries").add(artifact(commandPath, StrategyReadinessV5.hash(commandBytes), schema)
                .put("id", "githubCycleReceipt"));
        manifest = StrategyReadinessV5.withHash(manifest);
        Path manifestPath = root.resolve("manifest.json"); byte[] manifestBytes = compactBytes(manifest);
        Files.write(manifestPath, manifestBytes);
        ObjectNode options = fixedOptions(); options.putObject("evidenceManifest")
                .put("path", manifestPath.toString()).put("sha256", StrategyReadinessV5.hash(manifestBytes))
                .put("content_sha256", manifest.path("content_sha256").asText());
        ObjectNode actual = assertAuditMatchesFrozen(options, "manifestVerified", true);
        assertThat(actual.path("artifact_verification").get(0).path("id").asText()).isEqualTo("evidence-manifest");

        Files.write(manifestPath, compactBytes(manifest.deepCopy().put("status", "TAMPERED")));
        assertThatThrownBy(() -> StrategyReadinessV5.buildReadinessAuditV5(options))
                .hasMessage(frozenFailure("manifestTamper"))
                .hasMessageMatching(".*(schema/content hash|byte hash).*");
    }

    @Test
    void ed25519SigningAndVerificationMatchNodeExactly() throws Exception {
        SigningFixture fixture = signingFixture();
        ObjectNode attestation = StrategyReadinessV5.signActionsAttestationV5(fixture.signOptions());
        assertJson(attestation, frozen("signing").path("expected"));

        ObjectNode verify = fixture.verifyOptions(attestation);
        assertThat(StrategyReadinessV5.verifyActionsAttestation(verify)).isTrue();
        assertThat(frozen("signing").path("verify").asBoolean()).isTrue();
    }

    @Test
    void attestationKeyAndSignatureSubstitutionFailClosedLikeNode() throws Exception {
        SigningFixture fixture = signingFixture();
        ObjectNode attestation = StrategyReadinessV5.signActionsAttestationV5(fixture.signOptions());
        ObjectNode badPin = fixture.verifyOptions(attestation).put("pinnedFingerprint", "3".repeat(64));
        assertThatThrownBy(() -> StrategyReadinessV5.verifyActionsAttestation(badPin))
                .hasMessage(frozenFailure("badPin"))
                .hasMessageMatching(".*(trusted registry|pinned).*");

        ObjectNode tampered = attestation.deepCopy();
        String signature = tampered.path("signature").asText();
        tampered.put("signature", signature.substring(0, signature.length() - 2) + "AA");
        tampered.put("content_sha256", StrategyReadinessV5.ownHash(tampered));
        ObjectNode badSignature = fixture.verifyOptions(tampered);
        assertThatThrownBy(() -> StrategyReadinessV5.verifyActionsAttestation(badSignature))
                .hasMessage(frozenFailure("badSignature"))
                .hasMessageContaining("signature is invalid");

        ObjectNode privatePublic = fixture.signOptions().deepCopy();
        privatePublic.path("fields").deepCopy();
        ((ObjectNode) privatePublic.path("fields")).put("public_key_pem", fixture.privatePem());
        assertThatThrownBy(() -> StrategyReadinessV5.signActionsAttestationV5(privatePublic))
                .hasMessage(frozenFailure("privatePublic"))
                .hasMessageMatching(".*(public.*SPKI|Ed25519).*");
    }

    @Test
    void completeActivationAndPhysicalApiTamperMatchNode() throws Exception {
        JsonNode fixture = activationFixture();
        ObjectNode options = (ObjectNode) fixture.path("options");
        ObjectNode actual = StrategyReadinessV5.verifyActivationBundleV5(options);
        assertJson(actual, fixture.path("result"));
        assertThat(actual.path("activation").asText()).isEqualTo("VERIFIED_BUT_NO_STRATEGY_AUTHORIZATION");

        Path apiPath = Path.of(options.path("githubApiReceiptPath").asText());
        Files.write(apiPath, "tamper".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(() -> StrategyReadinessV5.verifyActivationBundleV5(options))
                .hasMessageContaining(frozenFailure("activationApiTamper"))
                .hasMessageContaining("API receipt byte hash mismatch");
    }

    private static ObjectNode assertAuditMatchesFrozen(ObjectNode options, String key, boolean verified)
            throws Exception {
        ObjectNode actual = StrategyReadinessV5.buildReadinessAuditV5(options);
        assertThat(actual.path("content_sha256").asText())
                .isEqualTo(StrategyReadinessV5.ownHash(actual));
        ObjectNode normalized = normalizeTempRoot(actual, options);
        ObjectNode expected = (ObjectNode) frozen("audit").path(key).deepCopy();
        normalized.put("content_sha256", "<TEMP_ROOT_AUDIT_HASH>");
        expected.put("content_sha256", "<TEMP_ROOT_AUDIT_HASH>");
        normalizeManifestHash(normalized);
        normalizeManifestHash(expected);
        assertJson(normalized, expected);
        if (actual.path("artifact_verification").size() == 1)
            assertThat(actual.path("artifact_verification").get(0).path("verified").asBoolean()).isEqualTo(verified);
        return actual;
    }

    private static void normalizeManifestHash(ObjectNode audit) {
        for (JsonNode row : audit.path("artifact_verification")) {
            if ("evidence-manifest".equals(row.path("id").asText()) && row.isObject()) {
                ((ObjectNode) row).put("byte_sha256", "<TEMP_ROOT_MANIFEST_BYTE_HASH>");
                ((ObjectNode) row).put("content_sha256", "<TEMP_ROOT_MANIFEST_CONTENT_HASH>");
            }
        }
    }

    private static ObjectNode fixedOptions() {
        return JsonHashes.mapper().createObjectNode().put("now", NOW).put("generatedAt", GENERATED_AT);
    }

    private static ObjectNode commandReceipt() {
        ObjectNode value = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-v5-authoritative-command-receipt/1").put("version", 1)
                .put("command", "prospective-runner").put("status", "COMPLETE");
        value.putArray("inputs"); value.putArray("outputs"); value.putArray("limitations");
        value.putObject("details").put("active", false).put("activated", false);
        return StrategyReadinessV5.withHash(value);
    }

    private static ObjectNode artifact(Path path, String sha256, String schema) {
        return JsonHashes.mapper().createObjectNode().put("path", path.toString())
                .put("sha256", sha256).put("schema", schema);
    }

    private static ObjectNode review(int reviewers, int required, int rules, boolean preventSelf) {
        return JsonHashes.mapper().createObjectNode().put("reviewer_count", reviewers)
                .put("required_reviewer_rule_count", required).put("protection_rule_count", rules)
                .put("prevent_self_review", preventSelf);
    }

    private static SigningFixture signingFixture() throws Exception {
        JsonNode frozen = frozen("signing");
        String publicPem = frozen.path("publicKeyPem").asText();
        String privatePem = frozen.path("privateKeyPem").asText();
        String fingerprint = frozen.path("fingerprint").asText();
        byte[] captureBytes = "physical-capture-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] apiBytes = "physical-api-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] cycleBytes = "physical-cycle-bytes".getBytes(StandardCharsets.UTF_8);
        ObjectNode registry = JsonHashes.mapper().createObjectNode()
                .put("schema", "strategy-github-attestation-key-registry/1").put("version", 1)
                .put("status", "FROZEN").put("repository", "owner/repo").put("repository_id", "1")
                .put("environment", "prospective-v5").put("generation", 1);
        registry.putArray("keys").addObject().put("key_id", "actions-1").put("role", "ACTIONS_ATTESTATION")
                .put("public_key_pem", publicPem).put("fingerprint", fingerprint)
                .put("valid_from", "2025-08-23T00:00:00.000Z").put("valid_until", "2025-08-25T00:00:00.000Z");
        registry.putNull("content_sha256"); registry.put("content_sha256", StrategyReadinessV5.ownHash(registry));
        byte[] registryBytes = JsonHashes.mapper().writeValueAsBytes(registry);
        ObjectNode claims = JsonHashes.mapper().createObjectNode().put("repository_id", "1")
                .put("repository_owner_id", "2").put("environment", "prospective-v5")
                .put("workflow_ref", "owner/repo/.github/workflows/prospective.yml@refs/heads/main")
                .put("workflow_sha", "a".repeat(64)).put("run_id", "42").put("run_attempt", 1)
                .put("sub", "repo:owner@2/repo@1:environment:prospective-v5").put("aud", "strategy-v5")
                .put("iss", "https://token.actions.githubusercontent.com").put("iat", 1_756_000_000L)
                .put("exp", 1_756_000_600L);
        ObjectNode capture = JsonHashes.mapper().createObjectNode()
                .put("schema", "github-deployment-settings-capture/1").put("repository", "owner/repo")
                .put("repository_id", 1).put("oidc_subject", claims.path("sub").asText())
                .put("oidc_signature_verified", true).put("evidence_branch", "strategy-v5-evidence")
                .put("evidence_branch_head_sha256", "b".repeat(64));
        capture.set("oidc_claims", claims); capture = StrategyReadinessV5.withHash(capture);
        ObjectNode fields = JsonHashes.mapper().createObjectNode().put("repository", "owner/repo")
                .put("repository_id", "1").put("workflow_sha256", "a".repeat(64))
                .put("workflow_ref", claims.path("workflow_ref").asText()).put("run_id", "42")
                .put("run_attempt", 1).put("environment", "prospective-v5")
                .put("oidc_subject", claims.path("sub").asText()).put("oidc_audience", "strategy-v5")
                .put("oidc_issuer", claims.path("iss").asText())
                .put("settings_capture_sha256", capture.path("content_sha256").asText())
                .put("settings_capture_byte_sha256", StrategyReadinessV5.hash(captureBytes))
                .put("api_receipt_sha256", StrategyReadinessV5.hash(apiBytes))
                .put("cycle_receipt_sha256", StrategyReadinessV5.hash(cycleBytes))
                .put("ledger_prior_head_sha256", "1".repeat(64)).put("ledger_new_head_sha256", "2".repeat(64))
                .put("ledger_sequence", 1).put("trusted_key_registry_sha256", registry.path("content_sha256").asText())
                .put("trusted_key_registry_byte_sha256", StrategyReadinessV5.hash(registryBytes))
                .put("evidence_branch", "strategy-v5-evidence").put("evidence_branch_head_sha256", "b".repeat(64))
                .put("issued_at", "2025-08-24T01:49:00.000Z").put("expires_at", "2025-08-24T01:52:00.000Z")
                .put("nonce", "nonce-attestation-12345").put("key_id", "actions-1").put("public_key_pem", publicPem);
        ObjectNode signOptions = JsonHashes.mapper().createObjectNode().put("privateKeyPem", privatePem);
        signOptions.set("fields", fields);
        return new SigningFixture(signOptions, capture, registry, fingerprint, privatePem,
                StrategyReadinessV5.hash(captureBytes), StrategyReadinessV5.hash(apiBytes),
                StrategyReadinessV5.hash(cycleBytes), StrategyReadinessV5.hash(registryBytes));
    }

    private record SigningFixture(ObjectNode signOptions, ObjectNode capture, ObjectNode registry,
            String fingerprint, String privatePem, String captureBytesSha, String apiSha, String cycleSha,
            String registryBytesSha) {
        ObjectNode verifyOptions(ObjectNode attestation) {
            ObjectNode options = JsonHashes.mapper().createObjectNode().put("bytesSha256", captureBytesSha)
                    .put("nowMs", NOW).put("pinnedFingerprint", fingerprint).put("apiReceiptSha256", apiSha)
                    .put("cycleReceiptSha256", cycleSha)
                    .put("trustedKeyRegistrySha256", registry.path("content_sha256").asText())
                    .put("trustedKeyRegistryByteSha256", registryBytesSha);
            options.set("attestation", attestation); options.set("capture", capture); options.putNull("publication");
            options.set("trustedKeyRegistry", registry); return options;
        }
    }

    private static JsonNode activationFixture() throws Exception {
        ObjectNode fixture = (ObjectNode) frozenActivation().deepCopy();
        Path root = Files.createTempDirectory("readiness-v5-activation-");
        fixture.path("files").fields().forEachRemaining(entry -> {
            try {
                Path path = root.resolve(entry.getKey());
                Files.createDirectories(path.getParent());
                Files.write(path, Base64.getDecoder().decode(entry.getValue().asText()));
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        });
        restoreTempRoot((ObjectNode) fixture.path("options"), root.toString());
        return fixture;
    }

    private static byte[] compactBytes(JsonNode value) throws Exception {
        return (JsonHashes.mapper().writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }


    private static void assertJson(JsonNode actual, JsonNode expected) {
        assertThat(CanonicalJson.canonicalize(actual)).isEqualTo(CanonicalJson.canonicalize(expected));
    }

    private static JsonNode frozen(String key) throws Exception {
        try (InputStream input = Objects.requireNonNull(
                StrategyReadinessV5NodeOracleTest.class.getResourceAsStream(
                        "/oracles/strategy-readiness-v5.json"),
                "frozen strategy-readiness oracle is missing")) {
            JsonNode value = JsonHashes.mapper().readTree(input).path(key);
            assertThat(value.isMissingNode()).as("frozen strategy-readiness case %s", key).isFalse();
            return value.deepCopy();
        }
    }

    private static JsonNode frozenActivation() throws Exception {
        try (InputStream input = Objects.requireNonNull(
                StrategyReadinessV5NodeOracleTest.class.getResourceAsStream(
                        "/oracles/strategy-readiness-v5-activation.json"),
                "frozen strategy-readiness activation oracle is missing")) {
            ObjectNode encoded = (ObjectNode) JsonHashes.mapper().readTree(input);
            assertThat(encoded.path("encoding").asText()).isEqualTo("gzip+base64");
            byte[] compressed = Base64.getDecoder().decode(encoded.path("payload").asText());
            try (java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(
                    new java.io.ByteArrayInputStream(compressed))) {
                return JsonHashes.mapper().readTree(gzip);
            }
        }
    }

    private static String frozenFailure(String key) throws Exception {
        return frozen("failures").path(key).asText();
    }

    private static ObjectNode normalizeTempRoot(JsonNode value, ObjectNode options) {
        String root = null;
        JsonNode evidence = options.path("evidence");
        if (evidence.isObject()) {
            var iterator = evidence.elements();
            if (iterator.hasNext()) {
                JsonNode first = iterator.next();
                if (first.isObject()) root = Path.of(first.path("path").asText()).getParent().toString();
            }
        }
        if (root == null && options.path("evidenceManifest").isObject()) {
            root = Path.of(options.path("evidenceManifest").path("path").asText()).getParent().toString();
        }
        ObjectNode copy = (ObjectNode) value.deepCopy();
        if (root != null) replaceTempRoot(copy, root);
        return copy;
    }

    private static void replaceTempRoot(ObjectNode node, String root) {
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isTextual() && value.asText().contains(root)) node.put(entry.getKey(), value.asText().replace(root, "<TEMP_ROOT>"));
            else if (value.isObject()) replaceTempRoot((ObjectNode) value, root);
            else if (value.isArray()) replaceTempRoot((ArrayNode) value, root);
        });
    }

    private static void replaceTempRoot(ArrayNode node, String root) {
        for (int index = 0; index < node.size(); index++) {
            JsonNode value = node.get(index);
            if (value.isTextual() && value.asText().contains(root)) node.set(index, JsonHashes.mapper().getNodeFactory().textNode(value.asText().replace(root, "<TEMP_ROOT>")));
            else if (value.isObject()) replaceTempRoot((ObjectNode) value, root);
            else if (value.isArray()) replaceTempRoot((ArrayNode) value, root);
        }
    }

    private static void restoreTempRoot(ObjectNode node, String root) {
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isTextual() && value.asText().contains("<TEMP_ROOT>"))
                node.put(entry.getKey(), value.asText().replace("<TEMP_ROOT>", root));
            else if (value.isObject()) restoreTempRoot((ObjectNode) value, root);
            else if (value.isArray()) restoreTempRoot((ArrayNode) value, root);
        });
    }

    private static void restoreTempRoot(ArrayNode node, String root) {
        for (int index = 0; index < node.size(); index++) {
            JsonNode value = node.get(index);
            if (value.isTextual() && value.asText().contains("<TEMP_ROOT>"))
                node.set(index, JsonHashes.mapper().getNodeFactory().textNode(value.asText().replace("<TEMP_ROOT>", root)));
            else if (value.isObject()) restoreTempRoot((ObjectNode) value, root);
            else if (value.isArray()) restoreTempRoot((ArrayNode) value, root);
        }
    }


}

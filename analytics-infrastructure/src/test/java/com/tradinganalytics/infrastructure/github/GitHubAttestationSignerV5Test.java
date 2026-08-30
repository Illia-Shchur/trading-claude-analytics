package com.tradinganalytics.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubAttestationSignerV5Test {
    private static final DateTimeFormatter NODE_ISO = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @TempDir
    Path temporary;

    private Fixture fixture;

    @BeforeEach
    void generateAuthoritativeJavaFixture() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("fixture"));
        fixture = generateFixture(root);
    }

    @Test
    void signsExactSchemaBoundAttestationAndWritesImmutable0600Bytes() throws Exception {
        Path output = temporary.resolve("java-attestation.json");
        GitHubAttestationSignerV5.Result result = sign(output, fixedNonce((byte) 0x2a));

        assertThat(result.output()).isEqualTo(output.toAbsolutePath().normalize());
        assertThat(Files.readString(output)).isEqualTo(NodePrettyJson.write(result.attestation()));
        assertThat(result.attestation().path("schema").asText())
                .isEqualTo("strategy-github-prospective-attestation/1");
        assertThat(result.attestation().path("protected").asBoolean()).isTrue();
        assertThat(result.attestation().path("content_sha256").asText())
                .isEqualTo(JsonHashes.ownHash(result.attestation()));
        assertThat(result.attestation().path("nonce").asText()).isEqualTo("2a".repeat(24));
        assertThat(result.summary().path("output").asText()).isEqualTo(output.toString());
        assertSignature(result.attestation());
        if (Files.getFileAttributeView(output,
                java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            assertThat(Files.getPosixFilePermissions(output)).isEqualTo(Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }

    @Test
    void bindsEveryPhysicalInputAndStableCryptographicField() throws Exception {
        Path javaOutput = temporary.resolve("java.json");
        ObjectNode javaAttestation = sign(javaOutput, fixedNonce((byte) 0x17)).attestation();

        assertSignature(javaAttestation);
        assertThat(javaAttestation.path("content_sha256").asText())
                .isEqualTo(JsonHashes.ownHash(javaAttestation));
        ObjectNode capture = read("capture");
        ObjectNode cycle = read("cycle");
        ObjectNode registry = read("registry");
        assertThat(javaAttestation.path("repository").asText()).isEqualTo("owner/repo");
        assertThat(javaAttestation.path("workflow_sha256").asText()).isEqualTo("e".repeat(64));
        assertThat(javaAttestation.path("settings_capture_sha256").asText())
                .isEqualTo(capture.path("content_sha256").asText());
        assertThat(javaAttestation.path("settings_capture_byte_sha256").asText())
                .isEqualTo(JsonHashes.sha256(Files.readAllBytes(path("capture"))));
        assertThat(javaAttestation.path("api_receipt_sha256").asText())
                .isEqualTo(JsonHashes.sha256(Files.readAllBytes(path("api"))));
        assertThat(javaAttestation.path("cycle_receipt_sha256").asText())
                .isEqualTo(JsonHashes.sha256(Files.readAllBytes(path("cycle"))));
        assertThat(javaAttestation.path("ledger_prior_head_sha256").asText())
                .isEqualTo(cycle.path("details").path("ledger_prior_head_sha256").asText());
        assertThat(javaAttestation.path("ledger_new_head_sha256").asText())
                .isEqualTo(cycle.path("details").path("ledger_new_head_sha256").asText());
        assertThat(javaAttestation.path("trusted_key_registry_sha256").asText())
                .isEqualTo(registry.path("content_sha256").asText());
        assertThat(javaAttestation.path("trusted_key_registry_byte_sha256").asText())
                .isEqualTo(JsonHashes.sha256(Files.readAllBytes(path("registry"))));
        assertThat(javaAttestation.path("key_id").asText()).isEqualTo("actions-1");
        assertThat(javaAttestation.path("public_key_pem").asText())
                .isEqualTo(fixture.descriptor().path("publicKeyPem").asText());
    }

    @Test
    void refusesOverwriteAndNeverSerializesProtectedKeyMaterial() throws Exception {
        Path output = temporary.resolve("occupied.json");
        Files.writeString(output, "owner bytes");
        String secret = fixture.descriptor().path("privateKeyPem").asText();

        assertThatThrownBy(() -> sign(output, fixedNonce((byte) 1)))
                .hasMessageContaining("immutable attestation output already exists")
                .hasMessageNotContaining(secret);
        assertThat(Files.readString(output)).isEqualTo("owner bytes");

        Map<String, String> invalid = environment(temporary.resolve("invalid.json"));
        invalid.put("ACTIONS_ATTESTATION_PRIVATE_KEY_B64",
                Base64.getEncoder().encodeToString("not a key".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> GitHubAttestationSignerV5.sign(options(invalid, fixedNonce((byte) 1))))
                .hasMessage("protected Actions attestation private key is invalid")
                .hasMessageNotContaining(invalid.get("ACTIONS_ATTESTATION_PRIVATE_KEY_B64"));
    }

    @Test
    void rejectsTamperedOrIncompletePhysicalApiReceiptBeforeSigning() throws Exception {
        Path apiPath = Path.of(fixture.descriptor().path("paths").path("api").asText());
        ObjectNode api = (ObjectNode) JsonHashes.mapper().readTree(apiPath.toFile());
        api.path("endpoints").path("repository").deepCopy();
        ((ObjectNode) api.path("endpoints").path("repository")).put("status", 500);
        api.put("content_sha256", JsonHashes.ownHash(api));
        Files.writeString(apiPath, NodePrettyJson.write(api));

        assertThatThrownBy(() -> sign(temporary.resolve("blocked.json"), fixedNonce((byte) 1)))
                .hasMessage("GitHub API receipt is blocked or incomplete");

        Files.writeString(Path.of(fixture.descriptor().path("paths").path("cycle").asText()), "{}\n");
        assertThatThrownBy(() -> sign(temporary.resolve("tampered.json"), fixedNonce((byte) 1)))
                .hasMessageContaining("completed-bar cycle receipt content hash is invalid");
    }

    @Test
    void rejectsAProtectedKeyThatDoesNotMatchFrozenRegistry() throws Exception {
        Path secondRoot = Files.createDirectory(temporary.resolve("second-fixture"));
        JsonNode other = generateFixture(secondRoot).descriptor();
        Map<String, String> environment = environment(temporary.resolve("mismatch.json"));
        environment.put("ACTIONS_ATTESTATION_PRIVATE_KEY_B64", Base64.getEncoder().encodeToString(
                other.path("privateKeyPem").asText().getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> GitHubAttestationSignerV5.sign(options(environment, fixedNonce((byte) 1))))
                .hasMessage("protected Actions attestation key is absent from the frozen registry");
    }

    @Test
    void reviewerGateCompatibilityIsFailClosedForAmbiguousLegacyShapes() {
        assertThat(GitHubAttestationSignerV5.environmentReviewSafe(null)).isFalse();
        assertThat(GitHubAttestationSignerV5.environmentReviewSafe(
                JsonHashes.mapper().createArrayNode())).isFalse();
        ObjectNode value = JsonHashes.mapper().createObjectNode()
                .put("reviewer_count", 0).put("protection_rule_count", 1)
                .put("prevent_self_review", false);
        assertThat(GitHubAttestationSignerV5.environmentReviewSafe(value)).isFalse();

        value.put("required_reviewer_rule_count", 0);
        assertThat(GitHubAttestationSignerV5.environmentReviewSafe(value)).isTrue();

        value.put("reviewer_count", 1).put("required_reviewer_rule_count", 1);
        assertThat(GitHubAttestationSignerV5.environmentReviewSafe(value)).isFalse();
        value.put("prevent_self_review", true);
        assertThat(GitHubAttestationSignerV5.environmentReviewSafe(value)).isTrue();

        value.put("reviewer_count", 9_007_199_254_740_992L);
        assertThat(GitHubAttestationSignerV5.environmentReviewSafe(value)).isFalse();

        value.put("reviewer_count", -1).put("protection_rule_count", 0);
        assertThat(GitHubAttestationSignerV5.environmentReviewSafe(value)).isFalse();
        value.remove("required_reviewer_rule_count");
        value.put("reviewer_count", 0).put("protection_rule_count", 0);
        assertThat(GitHubAttestationSignerV5.environmentReviewSafe(value)).isTrue();
        value.put("reviewer_count", 1).put("protection_rule_count", 1)
                .put("prevent_self_review", true);
        assertThat(GitHubAttestationSignerV5.environmentReviewSafe(value)).isTrue();
    }

    @Test
    void acceptsTheExactGitHubAppAuditorProofAndSecretCustodyContract() throws Exception {
        ObjectNode capture = read("capture");
        ObjectNode api = read("api");
        configureAppAuditor(capture, api);
        writeHashed("capture", capture);
        writeHashed("api", api);

        GitHubAttestationSignerV5.Result result = sign(
                temporary.resolve("app-attestation.json"), fixedNonce((byte) 0x3c));

        assertThat(result.attestation().path("protected").asBoolean()).isTrue();
        assertThat(result.attestation().path("nonce").asText()).isEqualTo("3c".repeat(24));
        assertSignature(result.attestation());
    }

    @Test
    void rejectsAppAuditorProofOrSecretDriftEvenWhenPhysicalHashesAreRecomputed() throws Exception {
        ObjectNode capture = read("capture");
        ObjectNode api = read("api");
        configureAppAuditor(capture, api);
        ((ObjectNode) capture.path("settings_auditor_installation")
                .path("accessible_repository")).put("full_name", "owner/other");
        ((ObjectNode) api.path("settings_auditor_installation")
                .path("accessible_repository")).put("full_name", "owner/other");
        writeHashed("capture", capture);
        writeHashed("api", api);

        assertThatThrownBy(() -> sign(temporary.resolve("proof-drift.json"), fixedNonce((byte) 1)))
                .hasMessage("GitHub API receipt is blocked or incomplete");

        ((ObjectNode) capture.path("settings_auditor_installation")
                .path("accessible_repository")).put("full_name", "owner/repo");
        api.set("settings_auditor_installation", capture.path("settings_auditor_installation").deepCopy());
        ((ObjectNode) capture.path("settings_token_secret")).put("repository_status", 200);
        ((ObjectNode) api.path("settings_token_secret")).put("repository_status", 200);
        writeHashed("capture", capture);
        writeHashed("api", api);
        assertThatThrownBy(() -> sign(temporary.resolve("secret-drift.json"), fixedNonce((byte) 1)))
                .hasMessage("GitHub API receipt is blocked or incomplete");

        ((ObjectNode) capture.path("settings_token_secret")).put("repository_status", 404);
        api.set("settings_token_secret", capture.path("settings_token_secret").deepCopy());
        ((ObjectNode) capture.path("settings_auditor_installation").path("permissions"))
                .put("actions", "write");
        api.set("settings_auditor_installation", capture.path("settings_auditor_installation").deepCopy());
        writeHashed("capture", capture);
        writeHashed("api", api);
        assertThatThrownBy(() -> sign(temporary.resolve("permission-drift.json"), fixedNonce((byte) 1)))
                .hasMessage("GitHub API receipt is blocked or incomplete");
    }

    @Test
    void acceptsRulesetOnlyBranchProtection404AndRejectsCrossDocumentPolicyDrift() throws Exception {
        ObjectNode capture = read("capture");
        ObjectNode api = read("api");
        ((ObjectNode) capture.path("branch_protection")).put("api_status", 404);
        ((ObjectNode) api.path("endpoints").path("branch_protection")).put("status", 404);
        writeHashed("capture", capture);
        writeHashed("api", api);
        assertThat(sign(temporary.resolve("branch-404.json"), fixedNonce((byte) 0x35))
                .attestation().path("protected").asBoolean()).isTrue();

        api = read("api");
        ((ObjectNode) api.path("writer_environment_protection")).put("reviewer_count", 1);
        writeHashed("api", api);
        assertThatThrownBy(() -> sign(temporary.resolve("policy-drift.json"), fixedNonce((byte) 1)))
                .hasMessage("GitHub API receipt is blocked or incomplete");
    }

    @Test
    void rejectsInexactWriterGateCollectionsBeforeApiEvaluation() throws Exception {
        ObjectNode capture = read("capture");
        ObjectNode writerGate = (ObjectNode) capture.path("rulesets").path("layers").get(1);
        writerGate.withArray("refs").add("refs/heads/other");
        writeHashed("capture", capture);
        assertThatThrownBy(() -> sign(temporary.resolve("refs.json"), fixedNonce((byte) 1)))
                .hasMessage("GitHub settings capture is not verified");

        capture = read("capture");
        writerGate = (ObjectNode) capture.path("rulesets").path("layers").get(1);
        writerGate.withArray("required_status_contexts").removeAll();
        writeHashed("capture", capture);
        assertThatThrownBy(() -> sign(temporary.resolve("context.json"), fixedNonce((byte) 1)))
                .hasMessage("GitHub settings capture is not verified");

        capture = read("capture");
        writerGate = (ObjectNode) capture.path("rulesets").path("layers").get(1);
        writerGate.withArray("required_status_check_integrations").removeAll();
        writeHashed("capture", capture);
        assertThatThrownBy(() -> sign(temporary.resolve("integration.json"), fixedNonce((byte) 1)))
                .hasMessage("GitHub settings capture is not verified");
    }

    @Test
    void rejectsSemanticCycleRegistryExpiryLedgerAndNonceFailures() throws Exception {
        ObjectNode cycle = read("cycle");
        ObjectNode originalCycle = cycle.deepCopy();
        ((ObjectNode) cycle.path("details")).put("active", true);
        writeHashed("cycle", cycle);
        assertThatThrownBy(() -> sign(temporary.resolve("active.json"), fixedNonce((byte) 1)))
                .hasMessageContaining("/details/active");

        writeHashed("cycle", originalCycle);
        ObjectNode registry = read("registry");
        ObjectNode originalRegistry = registry.deepCopy();
        registry.put("repository", "different/repository");
        writeHashed("registry", registry);
        assertThatThrownBy(() -> sign(temporary.resolve("registry.json"), fixedNonce((byte) 1)))
                .hasMessage("attestation key registry is not bound to the captured repository/id/environment");

        writeHashed("registry", originalRegistry);
        ObjectNode capture = read("capture");
        ObjectNode originalCapture = capture.deepCopy();
        ((ObjectNode) capture.path("oidc_claims")).put("exp",
                fixture.descriptor().path("now").asLong() / 1_000L);
        writeHashed("capture", capture);
        assertThatThrownBy(() -> sign(temporary.resolve("expired.json"), fixedNonce((byte) 1)))
                .hasMessage("GitHub OIDC claim is expired or unavailable");

        writeHashed("capture", originalCapture);
        cycle = originalCycle.deepCopy();
        ((ObjectNode) cycle.path("details")).put("ledger_sequence", 0);
        writeHashed("cycle", cycle);
        assertThatThrownBy(() -> sign(temporary.resolve("ledger.json"), fixedNonce((byte) 1)))
                .hasMessage("completed-bar cycle receipt lacks a valid cumulative ledger head transition");

        writeHashed("cycle", originalCycle);
        assertThatThrownBy(() -> sign(temporary.resolve("nonce.json"), () -> new byte[7]))
                .hasMessage("attestation nonce source is invalid");
    }

    @Test
    void defaultOptionsGenerateAProtectedRandomNonceAndRelativePathsResolveAgainstWorkingDirectory() {
        Path absoluteOutput = temporary.resolve("random-nonce.json");
        GitHubAttestationSignerV5.Result random = GitHubAttestationSignerV5.sign(environment(absoluteOutput));
        assertThat(random.attestation().path("nonce").asText())
                .matches("[a-f0-9]{48}").isNotEqualTo("0".repeat(48));

        Map<String, String> relative = environment(fixture.root().resolve("relative.json"));
        relative.put("V5_SETTINGS_CAPTURE_PATH", "capture.json");
        relative.put("V5_SETTINGS_RECEIPT_PATH", "api.json");
        relative.put("V5_CYCLE_RECEIPT_PATH", "cycle.json");
        relative.put("V5_ATTESTATION_KEY_REGISTRY_PATH", "registry.json");
        relative.put("V5_ATTESTATION_OUT", "relative.json");
        GitHubAttestationSignerV5.Options options = new GitHubAttestationSignerV5.Options(
                fixture.root(), relative,
                Clock.fixed(Instant.ofEpochMilli(fixture.descriptor().path("now").asLong()), ZoneOffset.UTC),
                fixedNonce((byte) 0x4d));

        GitHubAttestationSignerV5.Result resolved = GitHubAttestationSignerV5.sign(options);
        assertThat(resolved.output()).isEqualTo(fixture.root().resolve("relative.json"));

        Map<String, String> eightByteEnvironment = environment(temporary.resolve("eight-byte-nonce.json"));
        GitHubAttestationSignerV5.Result eightBytes = GitHubAttestationSignerV5.sign(
                options(eightByteEnvironment, () -> new byte[8]));
        assertThat(eightBytes.attestation().path("nonce").asText()).isEqualTo("0".repeat(16));
    }

    private static Fixture generateFixture(Path root) throws Exception {
        Files.createDirectories(root);
        String repository = "owner/repo";
        long repositoryId = 1;
        long now = System.currentTimeMillis();
        long seconds = now / 1_000L;

        ObjectNode claims = JsonHashes.mapper().createObjectNode();
        claims.put("repository_id", Long.toString(repositoryId));
        claims.put("repository_owner_id", "2");
        claims.put("environment", "prospective-v5");
        claims.put("workflow_ref",
                "owner/repo/.github/workflows/strategy-v5-prospective.yml@refs/heads/main");
        claims.put("workflow_sha", "e".repeat(64));
        claims.put("run_id", "123");
        claims.put("run_attempt", 2);
        claims.put("sub", "repo:owner@2/repo@1:environment:prospective-v5");
        claims.put("aud", "strategy-v5");
        claims.put("iss", "https://token.actions.githubusercontent.com");
        claims.put("iat", seconds - 60);
        claims.put("exp", seconds + 600);

        ObjectNode writerEnvironment = JsonHashes.mapper().createObjectNode();
        writerEnvironment.put("api_status", 200);
        writerEnvironment.put("reviewer_count", 0);
        writerEnvironment.put("required_reviewer_rule_count", 0);
        writerEnvironment.put("protection_rule_count", 0);
        writerEnvironment.put("can_admins_bypass", false);
        writerEnvironment.put("protected_branches", true);
        writerEnvironment.put("custom_branch_policies", false);
        writerEnvironment.put("prevent_self_review", false);
        writerEnvironment.put("verified", true);

        ObjectNode evidenceWriterSecret = JsonHashes.mapper().createObjectNode();
        evidenceWriterSecret.put("name", "V5_EVIDENCE_WRITER_APP_PRIVATE_KEY_PEM");
        evidenceWriterSecret.put("environment_status", 200);
        evidenceWriterSecret.put("environment_body_sha256", "1".repeat(64));
        evidenceWriterSecret.put("repository_status", 404);
        evidenceWriterSecret.put("repository_body_sha256", "2".repeat(64));
        evidenceWriterSecret.put("organization_status", 404);
        evidenceWriterSecret.put("organization_body_sha256", "3".repeat(64));
        evidenceWriterSecret.put("verified", true);

        ObjectNode rulesets = JsonHashes.mapper().createObjectNode();
        rulesets.putArray("ids").add(1).add(2);
        rulesets.putArray("protected_branch_ids").add(1).add(2);
        rulesets.putArray("immutable_ruleset_ids").add(1);
        rulesets.putArray("writer_gate_ruleset_ids").add(2);
        rulesets.put("status", 200);
        rulesets.put("evidence_writer_app_id", 4_716_299L);
        rulesets.put("evidence_writer_credential_configured", true);
        rulesets.putArray("actions_bypass_app_ids");
        for (String field : List.of("protected_ref_matches", "bypass_verified",
                "actions_only_bypass_verified", "immutable_policy_verified",
                "writer_gate_policy_verified", "layered_policy_verified",
                "enforcement_verified", "rules_verified", "detail_statuses_ok", "verified")) {
            rulesets.put(field, true);
        }
        ArrayNode layers = rulesets.putArray("layers");
        ObjectNode immutable = layers.addObject();
        immutable.put("id", 1).put("layer", "IMMUTABLE_CORE").put("status", 200)
                .put("target", "branch").put("enforcement", "active");
        immutable.putArray("refs").add("refs/heads/main");
        immutable.putArray("rule_types").add("deletion").add("non_fast_forward")
                .add("pull_request");
        immutable.putArray("required_status_contexts");
        immutable.putArray("required_status_check_integrations");
        immutable.put("strict_status_checks", false);
        immutable.putObject("pull_request_parameters")
                .put("required_approving_review_count", 0);
        immutable.putArray("bypass_actors");
        immutable.put("body_sha256", "4".repeat(64)).put("rules_verified", true);
        ObjectNode writerGate = layers.addObject();
        writerGate.put("id", 2).put("layer", "WRITER_GATE").put("status", 200)
                .put("target", "branch").put("enforcement", "active");
        writerGate.putArray("refs").add("refs/heads/strategy-v5-evidence");
        writerGate.putArray("rule_types").add("pull_request").add("required_status_checks");
        writerGate.putArray("required_status_contexts").add("strategy-v5-evidence-custody");
        writerGate.putArray("required_status_check_integrations").add(15_368);
        writerGate.put("strict_status_checks", true);
        writerGate.putObject("pull_request_parameters")
                .put("required_approving_review_count", 0);
        writerGate.putArray("bypass_actors");
        writerGate.put("body_sha256", "5".repeat(64)).put("rules_verified", true);

        ObjectNode capture = fromSchema("github-deployment-settings-capture-1");
        capture.put("schema", "github-deployment-settings-capture/1");
        capture.put("version", 1);
        capture.put("captured_at", NODE_ISO.format(Instant.ofEpochMilli(now - 1_000)));
        capture.put("repository", repository);
        capture.put("repository_id", repositoryId);
        capture.put("repository_private", true);
        capture.put("repository_visibility", "PRIVATE");
        capture.put("repository_visibility_verified", true);
        capture.put("evidence_branch", "strategy-v5-evidence");
        capture.put("evidence_branch_head_sha256", "f".repeat(64));
        ((ObjectNode) capture.path("branch_protection")).put("api_status", 200);
        capture.set("rulesets", rulesets);
        capture.set("writer_environment_protection", writerEnvironment);
        ((ObjectNode) capture.path("actions_permissions")).put("verified", true);
        ((ObjectNode) capture.path("actions_secret")).put("verified", true);
        capture.set("evidence_writer_secret", evidenceWriterSecret);
        ((ObjectNode) capture.path("settings_token_identity"))
                .put("token_kind", "PAT").put("verified", true);
        ((ObjectNode) capture.path("settings_token_secret")).put("verified", true);
        capture.put("oidc_signature_verified", true);
        capture.put("oidc_subject_restricted", true);
        capture.put("oidc_subject", claims.path("sub").asText());
        capture.set("oidc_claims", claims);
        capture.put("verified", true);
        capture.putNull("blocked_reason");
        hashAndValidate(capture);

        ObjectNode api = fromSchema("github-settings-api-receipt-1");
        Iterator<JsonNode> endpoints = api.path("endpoints").elements();
        while (endpoints.hasNext()) {
            ObjectNode endpoint = (ObjectNode) endpoints.next();
            endpoint.put("status", 200);
            if (!JsonHashes.isSha256(endpoint.path("body_sha256").asText())) {
                endpoint.put("body_sha256", "6".repeat(64));
            }
        }
        ((ObjectNode) api.path("endpoints").path("installation")).put("status", 0);
        ((ObjectNode) api.path("endpoints").path("evidence_writer_repository_secret"))
                .put("status", 404);
        ((ObjectNode) api.path("endpoints").path("evidence_writer_organization_secret"))
                .put("status", 404);
        api.put("schema", "github-settings-api-receipt/1");
        api.put("version", 1);
        api.put("repository", repository);
        api.put("captured_at", capture.path("captured_at").asText());
        api.put("evidence_branch", capture.path("evidence_branch").asText());
        api.put("repository_visibility", "PRIVATE");
        api.put("repository_visibility_verified", true);
        api.set("rulesets", rulesets.deepCopy());
        api.set("actions_permissions", capture.path("actions_permissions").deepCopy());
        api.set("actions_secret", capture.path("actions_secret").deepCopy());
        api.set("writer_environment_protection", writerEnvironment.deepCopy());
        api.set("evidence_writer_secret", evidenceWriterSecret.deepCopy());
        api.set("settings_token_identity", capture.path("settings_token_identity").deepCopy());
        api.set("settings_token_secret", capture.path("settings_token_secret").deepCopy());
        api.put("installation_proof_verified", false);
        api.put("oidc_signature_verified", true);
        api.put("verified", true);
        api.putArray("blockers");
        hashAndValidate(api);

        ObjectNode cycle = JsonHashes.mapper().createObjectNode();
        cycle.put("schema", "strategy-v5-authoritative-command-receipt/1");
        cycle.put("version", 1);
        cycle.put("command", "prospective-runner");
        cycle.put("status", "COMPLETE");
        cycle.putArray("inputs");
        cycle.putArray("outputs");
        cycle.putArray("limitations");
        ObjectNode details = cycle.putObject("details");
        details.put("active", false).put("activated", false);
        details.put("ledger_prior_head_sha256", "7".repeat(64));
        details.put("ledger_new_head_sha256", "8".repeat(64));
        details.put("ledger_sequence", 1);
        hashAndValidate(cycle);

        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKeyPem = pem("PUBLIC KEY", keys.getPublic().getEncoded());
        String privateKeyPem = pem("PRIVATE KEY", keys.getPrivate().getEncoded());
        String fingerprint = JsonHashes.sha256(publicKeyPem);
        ObjectNode registry = JsonHashes.mapper().createObjectNode();
        registry.put("schema", "strategy-github-attestation-key-registry/1");
        registry.put("version", 1);
        registry.put("status", "FROZEN");
        registry.put("repository", repository);
        registry.put("repository_id", repositoryId);
        registry.put("environment", "prospective-v5");
        registry.put("generation", 1);
        ObjectNode key = registry.putArray("keys").addObject();
        key.put("key_id", "actions-1");
        key.put("role", "ACTIONS_ATTESTATION");
        key.put("public_key_pem", publicKeyPem);
        key.put("fingerprint", fingerprint);
        key.put("valid_from", NODE_ISO.format(Instant.ofEpochMilli(now - 60_000)));
        key.put("valid_until", NODE_ISO.format(Instant.ofEpochMilli(now + 3_600_000)));
        hashAndValidate(registry);

        ObjectNode paths = JsonHashes.mapper().createObjectNode();
        paths.put("capture", write(root, "capture.json", capture).toString());
        paths.put("api", write(root, "api.json", api).toString());
        paths.put("cycle", write(root, "cycle.json", cycle).toString());
        paths.put("registry", write(root, "registry.json", registry).toString());
        ObjectNode descriptor = JsonHashes.mapper().createObjectNode();
        descriptor.put("now", now);
        descriptor.put("fingerprint", fingerprint);
        descriptor.put("publicKeyPem", publicKeyPem);
        descriptor.put("privateKeyPem", privateKeyPem);
        descriptor.set("paths", paths);
        return new Fixture(root, descriptor);
    }

    private static ObjectNode fromSchema(String name) throws Exception {
        JsonNode schema = JsonHashes.mapper().readTree(repositoryRoot()
                .resolve("schemas/" + name + ".schema.json").toFile());
        return (ObjectNode) minimal(schema, schema);
    }

    private static JsonNode minimal(JsonNode schema, JsonNode root) {
        if (schema.path("$ref").isTextual() && schema.path("$ref").asText().startsWith("#/")) {
            return minimal(root.at(schema.path("$ref").asText().substring(1)), root);
        }
        if (schema.has("const")) return schema.get("const").deepCopy();
        if (schema.path("enum").isArray() && !schema.path("enum").isEmpty()) {
            return schema.path("enum").get(0).deepCopy();
        }
        if (schema.path("oneOf").isArray() && !schema.path("oneOf").isEmpty()) {
            return minimal(schema.path("oneOf").get(0), root);
        }
        if (schema.path("anyOf").isArray() && !schema.path("anyOf").isEmpty()) {
            return minimal(schema.path("anyOf").get(0), root);
        }
        String type = schema.path("type").isArray()
                ? firstNonNullType(schema.path("type")) : schema.path("type").asText();
        if ("object".equals(type) || type.isEmpty() && schema.path("properties").isObject()) {
            ObjectNode value = JsonHashes.mapper().createObjectNode();
            for (JsonNode required : iterable(schema.path("required"))) {
                String field = required.asText();
                JsonNode property = schema.path("properties").get(field);
                value.set(field, minimal(property == null
                        ? JsonHashes.mapper().createObjectNode() : property, root));
            }
            for (JsonNode piece : iterable(schema.path("allOf"))) {
                JsonNode addition = minimal(piece, root);
                if (addition.isObject()) value.setAll((ObjectNode) addition);
            }
            return value;
        }
        if ("array".equals(type)) {
            ArrayNode value = JsonHashes.mapper().createArrayNode();
            int size = schema.path("minItems").asInt(0);
            for (int index = 0; index < size; index++) {
                value.add(minimal(schema.path("items"), root));
            }
            return value;
        }
        if ("integer".equals(type) || "number".equals(type)) {
            return schema.path("minimum").isNumber()
                    ? schema.path("minimum").deepCopy()
                    : JsonHashes.mapper().getNodeFactory().numberNode(1);
        }
        if ("boolean".equals(type)) return JsonHashes.mapper().getNodeFactory().booleanNode(false);
        if ("null".equals(type)) return JsonHashes.mapper().getNodeFactory().nullNode();
        String pattern = schema.path("pattern").asText();
        if (pattern.contains("a-f0-9") && pattern.contains("64")) {
            return JsonHashes.mapper().getNodeFactory().textNode("a".repeat(64));
        }
        if ("date-time".equals(schema.path("format").asText())) {
            return JsonHashes.mapper().getNodeFactory().textNode("2025-08-24T01:50:00.000Z");
        }
        int length = Math.max(1, schema.path("minLength").asInt(1));
        return JsonHashes.mapper().getNodeFactory().textNode("x".repeat(length));
    }

    private static String firstNonNullType(JsonNode types) {
        String fallback = "";
        for (JsonNode type : types) {
            fallback = type.asText();
            if (!"null".equals(fallback)) return fallback;
        }
        return fallback;
    }

    private static Iterable<JsonNode> iterable(JsonNode value) {
        return value != null && value.isArray() ? value : List.of();
    }

    private static void hashAndValidate(ObjectNode value) {
        value.put("content_sha256", JsonHashes.ownHash(value));
        ResearchSchemaRegistry.defaultRegistry().validateKnownContractSchema(value);
    }

    private static Path write(Path root, String name, JsonNode value) throws Exception {
        Path path = root.resolve(name).toAbsolutePath().normalize();
        Files.writeString(path, JsonHashes.mapper().writeValueAsString(value) + "\n");
        return path;
    }

    private static String pem(String label, byte[] encoded) {
        String base64 = Base64.getEncoder().encodeToString(encoded);
        StringBuilder value = new StringBuilder("-----BEGIN " + label + "-----\n");
        for (int offset = 0; offset < base64.length(); offset += 64) {
            value.append(base64, offset, Math.min(offset + 64, base64.length())).append('\n');
        }
        return value.append("-----END ").append(label).append("-----\n").toString();
    }

    private GitHubAttestationSignerV5.Result sign(Path output, java.util.function.Supplier<byte[]> nonce) {
        return GitHubAttestationSignerV5.sign(options(environment(output), nonce));
    }

    private GitHubAttestationSignerV5.Options options(Map<String, String> environment,
            java.util.function.Supplier<byte[]> nonce) {
        return new GitHubAttestationSignerV5.Options(repositoryRoot(), environment,
                Clock.fixed(Instant.ofEpochMilli(fixture.descriptor().path("now").asLong()), ZoneOffset.UTC), nonce);
    }

    private Map<String, String> environment(Path output) {
        ObjectNode descriptor = fixture.descriptor();
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("V5_SETTINGS_CAPTURE_PATH", descriptor.path("paths").path("capture").asText());
        environment.put("V5_SETTINGS_RECEIPT_PATH", descriptor.path("paths").path("api").asText());
        environment.put("V5_CYCLE_RECEIPT_PATH", descriptor.path("paths").path("cycle").asText());
        environment.put("V5_ATTESTATION_KEY_REGISTRY_PATH", descriptor.path("paths").path("registry").asText());
        environment.put("V5_ATTESTATION_OUT", output.toAbsolutePath().normalize().toString());
        environment.put("V5_ATTESTATION_KEY_FINGERPRINT", descriptor.path("fingerprint").asText());
        environment.put("ACTIONS_ATTESTATION_PRIVATE_KEY_B64", Base64.getEncoder().encodeToString(
                descriptor.path("privateKeyPem").asText().getBytes(StandardCharsets.UTF_8)));
        return environment;
    }

    private ObjectNode read(String name) throws Exception {
        return (ObjectNode) JsonHashes.mapper().readTree(path(name).toFile());
    }

    private void writeHashed(String name, ObjectNode value) throws Exception {
        value.put("content_sha256", JsonHashes.ownHash(value));
        Files.writeString(path(name), NodePrettyJson.write(value));
    }

    private Path path(String name) {
        return Path.of(fixture.descriptor().path("paths").path(name).asText());
    }

    private static void configureAppAuditor(ObjectNode capture, ObjectNode api) {
        ObjectNode identity = (ObjectNode) capture.path("settings_token_identity");
        identity.put("token_kind", "APP").put("app_id", 4_716_635L);
        ObjectNode secret = JsonHashes.mapper().createObjectNode();
        secret.put("name", "V5_GITHUB_SETTINGS_AUDITOR_APP_PRIVATE_KEY_PEM")
                .put("environment_status", 200)
                .put("environment_body_sha256", "9".repeat(64))
                .put("repository_status", 404)
                .put("repository_body_sha256", "a".repeat(64))
                .put("organization_status", 404)
                .put("organization_body_sha256", "b".repeat(64))
                .put("verified", true);
        ObjectNode proof = appAuditorProof();
        capture.set("settings_token_secret", secret.deepCopy());
        capture.set("settings_auditor_installation", proof.deepCopy());

        api.set("settings_token_identity", identity.deepCopy());
        api.set("settings_token_secret", secret.deepCopy());
        api.set("settings_auditor_installation", proof.deepCopy());
        api.put("installation_proof_verified", true);
        ((ObjectNode) api.path("endpoints").path("installation")).put("status", 200);
        ObjectNode endpoints = (ObjectNode) api.path("endpoints");
        for (String name : List.of("settings_auditor_app", "settings_auditor_installation",
                "settings_auditor_repositories")) {
            ObjectNode endpoint = endpoints.putObject(name);
            endpoint.put("status", 200).put("body_sha256", "c".repeat(64));
        }
    }

    private static ObjectNode appAuditorProof() {
        ObjectNode proof = JsonHashes.mapper().createObjectNode();
        proof.put("token_kind", "APP")
                .put("expected_app_id", 4_716_635L)
                .put("expected_installation_id", 156_531_963L)
                .put("expected_app_slug", "strategy-v5-settings-auditor")
                .put("app_endpoint_status", 200)
                .put("app_endpoint_body_sha256", "d".repeat(64))
                .put("installation_endpoint_status", 200)
                .put("installation_endpoint_body_sha256", "e".repeat(64))
                .put("repositories_endpoint_status", 200)
                .put("repositories_endpoint_body_sha256", "f".repeat(64))
                .put("app_id", 4_716_635L)
                .put("app_slug", "strategy-v5-settings-auditor")
                .put("installation_id", 156_531_963L)
                .put("repository_selection", "selected")
                .put("accessible_repository_count", 1)
                .put("verified", true);
        ObjectNode permissions = proof.putObject("permissions");
        permissions.put("actions", "read").put("administration", "read")
                .put("environments", "read").put("metadata", "read").put("secrets", "read");
        proof.set("installation_permissions", permissions.deepCopy());
        proof.putArray("events");
        proof.putArray("installation_events");
        proof.putObject("account").put("id", 99).put("login", "owner").put("type", "Organization");
        proof.putObject("accessible_repository").put("id", 1).put("full_name", "owner/repo");
        return proof;
    }

    private static java.util.function.Supplier<byte[]> fixedNonce(byte value) {
        return () -> {
            byte[] bytes = new byte[24];
            Arrays.fill(bytes, value);
            return bytes;
        };
    }

    private static void assertSignature(ObjectNode attestation) throws Exception {
        ObjectNode payload = attestation.deepCopy();
        payload.remove(List.of("signature", "content_sha256", "attestation_payload_sha256"));
        assertThat(attestation.path("attestation_payload_sha256").asText())
                .isEqualTo(JsonHashes.canonicalSha256(payload));
        String pem = attestation.path("public_key_pem").asText();
        byte[] der = Base64.getMimeDecoder().decode(pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", ""));
        PublicKey key = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(der));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(JsonHashes.canonicalBytes(payload));
        assertThat(verifier.verify(Base64.getDecoder().decode(
                attestation.path("signature").asText()))).isTrue();
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null && !Files.exists(cursor.resolve("tools"))) cursor = cursor.getParent();
        if (cursor == null) throw new IllegalStateException("repository root not found");
        return cursor;
    }

    private record Fixture(Path root, ObjectNode descriptor) {}
}

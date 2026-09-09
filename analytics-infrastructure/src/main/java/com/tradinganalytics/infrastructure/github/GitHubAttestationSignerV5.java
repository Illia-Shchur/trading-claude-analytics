package com.tradinganalytics.infrastructure.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.security.CustodyException;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.PathConfinement;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Exact, fail-closed Java port of {@code tools/sign-github-attestation.mjs}. */
public final class GitHubAttestationSignerV5 {
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern PUBLIC_SPKI = Pattern.compile(
            "^-----BEGIN PUBLIC KEY-----\\n(?:[A-Za-z0-9+/=]{1,64}\\n)+"
                    + "-----END PUBLIC KEY-----\\n?$");
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final long SETTINGS_AUDITOR_APP_ID = 4_716_635L;
    private static final String SETTINGS_AUDITOR_APP_SLUG = "strategy-v5-settings-auditor";
    private static final Map<String, String> SETTINGS_AUDITOR_PERMISSIONS = Map.of(
            "actions", "read", "administration", "read", "environments", "read",
            "metadata", "read", "secrets", "read");
    private static final DateTimeFormatter NODE_ISO = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final ResearchSchemaRegistry SCHEMAS = ResearchSchemaRegistry.defaultRegistry();

    private GitHubAttestationSignerV5() {}

    public record Options(Path workingDirectory, Map<String, String> environment, Clock clock,
                          Supplier<byte[]> nonceBytes) {
        public Options {
            workingDirectory = workingDirectory == null
                    ? Path.of("").toAbsolutePath().normalize() : workingDirectory.toAbsolutePath().normalize();
            environment = environment == null ? Map.of() : Map.copyOf(environment);
            clock = clock == null ? Clock.systemUTC() : clock;
            nonceBytes = nonceBytes == null ? GitHubAttestationSignerV5::secureNonce : nonceBytes;
        }
    }

    public record Result(Path output, ObjectNode attestation, ObjectNode summary) {}

    public static Result sign(Map<String, String> environment) {
        return sign(new Options(null, environment, null, null));
    }

    public static Result sign(Options options) {
        Map<String, String> env = options.environment();
        Physical registry = readPhysical(options.workingDirectory(),
                env.get("V5_ATTESTATION_KEY_REGISTRY_PATH"), "attestation key registry");
        Physical capture = readPhysical(options.workingDirectory(),
                env.getOrDefault("V5_SETTINGS_CAPTURE_PATH", "github-deployment-settings-capture.json"),
                "settings capture");
        Physical api = readPhysical(options.workingDirectory(),
                env.getOrDefault("V5_SETTINGS_RECEIPT_PATH", "github-settings-api-receipt.json"),
                "GitHub API receipt");
        Physical cycle = readPhysical(options.workingDirectory(),
                env.getOrDefault("V5_CYCLE_RECEIPT_PATH", "v5-shadow-cycle-receipt.json"),
                "completed-bar cycle receipt");

        requireCompleteShadowCycle(cycle.value());
        boolean layeredRules = requireVerifiedCapture(capture.value());
        requireVerifiedApiReceipt(api.value(), capture.value(), layeredRules);
        requireRegistry(registry.value(), capture.value());

        String encodedPrivateKey = required("ACTIONS_ATTESTATION_PRIVATE_KEY_B64",
                env.get("ACTIONS_ATTESTATION_PRIVATE_KEY_B64"));
        PrivateKey privateKey = privateKey(encodedPrivateKey);
        String pinnedFingerprint = env.getOrDefault("V5_ATTESTATION_KEY_FINGERPRINT", "");
        if (!HASH.matcher(pinnedFingerprint).matches()) {
            throw failure("externally pinned Actions attestation fingerprint is required");
        }
        TrustedKey trusted = trustedKey(registry.value(), pinnedFingerprint, privateKey);

        ObjectNode claims = object(capture.value().get("oidc_claims"), "settings capture OIDC claims");
        long now = options.clock().millis();
        long claimExpiry = safeInteger(claims.get("exp"), "GitHub OIDC expiry") * 1_000L;
        long expires = Math.min(Math.addExact(now, 5 * 60_000L), claimExpiry - 1_000L);
        if (expires <= now) throw failure("GitHub OIDC claim is expired or unavailable");

        ObjectNode details = object(cycle.value().get("details"), "completed-bar cycle details");
        String priorHead = required("cycle receipt ledger_prior_head_sha256",
                text(details.get("ledger_prior_head_sha256")));
        String newHead = required("cycle receipt ledger_new_head_sha256",
                text(details.get("ledger_new_head_sha256")));
        long sequence = safeInteger(details.get("ledger_sequence"), "completed-bar ledger sequence");
        if (!HASH.matcher(priorHead).matches() || !HASH.matcher(newHead).matches() || sequence < 1) {
            throw failure("completed-bar cycle receipt lacks a valid cumulative ledger head transition");
        }

        String workflowClaim = text(claims.get("workflow_sha"));
        String workflowHash = HASH.matcher(workflowClaim).matches()
                ? workflowClaim : JsonHashes.sha256(workflowClaim);
        long runAttempt = safeInteger(claims.get("run_attempt"), "GitHub OIDC run attempt");
        String audience = claims.path("aud").isArray() && !claims.path("aud").isEmpty()
                ? text(claims.path("aud").get(0)) : text(claims.get("aud"));
        byte[] nonce = options.nonceBytes().get();
        if (nonce == null || nonce.length < 8) throw failure("attestation nonce source is invalid");

        ObjectNode fields = JsonHashes.mapper().createObjectNode();
        fields.put("repository", text(capture.value().get("repository")));
        fields.put("repository_id", stringValue(capture.value().get("repository_id")));
        fields.put("workflow_sha256", workflowHash);
        fields.put("workflow_ref", text(claims.get("workflow_ref")));
        fields.put("run_id", stringValue(claims.get("run_id")));
        fields.put("run_attempt", runAttempt);
        fields.put("environment", text(claims.get("environment")));
        fields.put("oidc_subject", text(capture.value().get("oidc_subject")));
        fields.put("oidc_audience", audience);
        fields.put("oidc_issuer", text(claims.get("iss")));
        fields.put("settings_capture_sha256", text(capture.value().get("content_sha256")));
        fields.put("settings_capture_byte_sha256", capture.byteSha256());
        fields.put("api_receipt_sha256", api.byteSha256());
        fields.put("cycle_receipt_sha256", cycle.byteSha256());
        fields.put("ledger_prior_head_sha256", priorHead);
        fields.put("ledger_new_head_sha256", newHead);
        fields.put("ledger_sequence", sequence);
        fields.put("trusted_key_registry_sha256", text(registry.value().get("content_sha256")));
        fields.put("trusted_key_registry_byte_sha256", registry.byteSha256());
        fields.put("evidence_branch", text(capture.value().get("evidence_branch")));
        fields.put("evidence_branch_head_sha256", text(capture.value().get("evidence_branch_head_sha256")));
        fields.put("issued_at", NODE_ISO.format(options.clock().instant()));
        fields.put("expires_at", NODE_ISO.format(java.time.Instant.ofEpochMilli(expires)));
        fields.put("nonce", HexFormat.of().formatHex(nonce));
        fields.put("key_id", trusted.keyId());
        fields.put("public_key_pem", trusted.publicPem());

        ObjectNode attestation = signAttestation(fields, privateKey, trusted.publicKey());
        Path output = resolve(options.workingDirectory(),
                env.getOrDefault("V5_ATTESTATION_OUT", "v5-actions-attestation.json"));
        byte[] outputBytes = NodePrettyJson.write(attestation).getBytes(StandardCharsets.UTF_8);
        writeExclusive0600(output, outputBytes);

        ObjectNode summary = JsonHashes.mapper().createObjectNode();
        summary.put("schema", text(attestation.get("schema")));
        summary.put("content_sha256", text(attestation.get("content_sha256")));
        summary.put("key_id", trusted.keyId());
        summary.put("output", output.toString());
        return new Result(output, attestation, summary);
    }

    static boolean environmentReviewSafe(JsonNode value) {
        if (value == null || !value.isObject()) return false;
        Long reviewers = optionalSafeInteger(value.get("reviewer_count"));
        Long protections = optionalSafeInteger(value.get("protection_rule_count"));
        if (reviewers == null || protections == null || reviewers < 0 || protections < 0) return false;
        Long required;
        if (!value.has("required_reviewer_rule_count")) {
            if (reviewers > 0) required = 1L;
            else if (protections == 0) required = 0L;
            else required = null;
        } else required = optionalSafeInteger(value.get("required_reviewer_rule_count"));
        return required != null && required >= 0 && required <= protections
                && required.equals(reviewers)
                && (required == 0 || value.path("prevent_self_review").asBoolean(false));
    }

    private static void requireCompleteShadowCycle(ObjectNode cycle) {
        if (!"strategy-v5-authoritative-command-receipt/1".equals(text(cycle.get("schema")))
                || !"COMPLETE".equals(text(cycle.get("status")))
                || !cycle.path("details").has("active")
                || cycle.path("details").path("active").asBoolean(true)) {
            throw failure("completed-bar cycle is not a COMPLETE SHADOW receipt");
        }
    }

    private static boolean requireVerifiedCapture(ObjectNode capture) {
        JsonNode rulesets = capture.path("rulesets");
        boolean writerGate = false;
        boolean immutableMain = false;
        for (JsonNode layer : array(rulesets.get("layers"))) {
            if ("WRITER_GATE".equals(text(layer.get("layer")))
                    && exactStrings(layer.get("refs"), List.of("refs/heads/strategy-v5-evidence"))
                    && exactStrings(layer.get("rule_types"), List.of("pull_request", "required_status_checks"))
                    && containsText(layer.get("required_status_contexts"), "strategy-v5-evidence-custody")
                    && containsInteger(layer.get("required_status_check_integrations"), 15_368)
                    && layer.path("strict_status_checks").asBoolean(false)
                    && Long.valueOf(0).equals(optionalSafeInteger(
                            layer.path("pull_request_parameters").get("required_approving_review_count")))
                    && array(layer.get("bypass_actors")).isEmpty()) writerGate = true;
            if (exactStrings(layer.get("refs"), List.of("refs/heads/main"))
                    && exactStrings(layer.get("rule_types"),
                            List.of("deletion", "non_fast_forward", "pull_request"))
                    && array(layer.get("bypass_actors")).isEmpty()) immutableMain = true;
        }
        boolean layered = rulesets.path("verified").asBoolean(false)
                && rulesets.path("layered_policy_verified").asBoolean(false)
                && rulesets.path("immutable_policy_verified").asBoolean(false)
                && rulesets.path("writer_gate_policy_verified").asBoolean(false)
                && rulesets.path("protected_ref_matches").asBoolean(false)
                && rulesets.path("enforcement_verified").asBoolean(false)
                && rulesets.path("rules_verified").asBoolean(false)
                && array(rulesets.get("actions_bypass_app_ids")).isEmpty()
                && rulesets.path("layers").isArray() && immutableMain && writerGate;
        JsonNode writerEnvironment = capture.path("writer_environment_protection");
        if (!capture.path("verified").asBoolean(false)
                || !List.of("PUBLIC", "PRIVATE").contains(text(capture.get("repository_visibility")))
                || !capture.path("repository_visibility_verified").asBoolean(false)
                || !capture.path("oidc_signature_verified").asBoolean(false)
                || !capture.path("oidc_subject_restricted").asBoolean(false)
                || !capture.path("actions_permissions").path("verified").asBoolean(false)
                || !capture.path("actions_secret").path("verified").asBoolean(false)
                || !capture.path("settings_token_identity").path("verified").asBoolean(false)
                || !capture.path("settings_token_secret").path("verified").asBoolean(false)
                || !writerEnvironment.path("verified").asBoolean(false)
                || !writerEnvironment.has("can_admins_bypass")
                || writerEnvironment.path("can_admins_bypass").asBoolean(true)
                || !environmentReviewSafe(writerEnvironment)
                || !capture.path("evidence_writer_secret").path("verified").asBoolean(false)
                || !layered) {
            throw failure("GitHub settings capture is not verified");
        }
        return true;
    }

    private static void requireVerifiedApiReceipt(ObjectNode api, ObjectNode capture, boolean layeredRules) {
        boolean rulesetOnlyBranch404 = api.path("endpoints").path("branch_protection").path("status").asInt()
                == 404 && capture.path("branch_protection").path("api_status").asInt() == 404 && layeredRules;
        String tokenKind = text(capture.path("settings_token_identity").get("token_kind"));
        boolean installationUnprovenForPat = !api.path("installation_proof_verified").asBoolean(true)
                && "PAT".equals(tokenKind)
                && api.path("endpoints").path("installation").path("status").asInt() == 0;
        boolean auditorRequired = "APP".equals(tokenKind);
        boolean auditorEndpointsPresent = !auditorRequired;
        if (auditorRequired) {
            auditorEndpointsPresent = true;
            for (String key : List.of("settings_auditor_app", "settings_auditor_installation",
                    "settings_auditor_repositories")) {
                JsonNode endpoint = api.path("endpoints").get(key);
                auditorEndpointsPresent &= endpoint != null
                        && optionalSafeInteger(endpoint.get("status")) != null
                        && HASH.matcher(text(endpoint.get("body_sha256"))).matches();
            }
        }
        boolean auditorProofMatches = !auditorRequired
                || (exactAuditorProof(capture.path("settings_auditor_installation"),
                            text(capture.get("repository")), capture.get("repository_id"), tokenKind)
                    && exactAuditorProof(api.path("settings_auditor_installation"),
                            text(capture.get("repository")), capture.get("repository_id"), tokenKind)
                    && JsonHashes.canonicalSha256(api.path("settings_auditor_installation"))
                            .equals(JsonHashes.canonicalSha256(capture.path("settings_auditor_installation")))
                    && exactAuditorSecret(capture.path("settings_token_secret"), tokenKind)
                    && exactAuditorSecret(api.path("settings_token_secret"), tokenKind)
                    && JsonHashes.canonicalSha256(api.path("settings_token_secret"))
                            .equals(JsonHashes.canonicalSha256(capture.path("settings_token_secret"))));
        boolean endpointsComplete = true;
        Iterator<Map.Entry<String, JsonNode>> endpoints = api.path("endpoints").fields();
        while (endpoints.hasNext()) {
            Map.Entry<String, JsonNode> endpoint = endpoints.next();
            int status = endpoint.getValue().path("status").asInt(Integer.MIN_VALUE);
            boolean accepted = status == 200
                    || ("branch_protection".equals(endpoint.getKey()) && rulesetOnlyBranch404)
                    || ("installation".equals(endpoint.getKey()) && installationUnprovenForPat)
                    || (List.of("evidence_writer_repository_secret", "evidence_writer_organization_secret")
                            .contains(endpoint.getKey()) && status == 404)
                    || (!auditorRequired && endpoint.getKey().startsWith("settings_auditor_"));
            endpointsComplete &= accepted;
        }
        if (!"github-settings-api-receipt/1".equals(text(api.get("schema")))
                || !api.path("verified").asBoolean(false)
                || !api.path("oidc_signature_verified").asBoolean(false)
                || !api.path("actions_permissions").path("verified").asBoolean(false)
                || !api.path("actions_secret").path("verified").asBoolean(false)
                || !api.path("writer_environment_protection").path("verified").asBoolean(false)
                || !api.path("evidence_writer_secret").path("verified").asBoolean(false)
                || !sameHash(api.path("writer_environment_protection"),
                        capture.path("writer_environment_protection"))
                || !sameHash(api.path("evidence_writer_secret"), capture.path("evidence_writer_secret"))
                || !sameHash(api.path("rulesets"), capture.path("rulesets"))
                || !auditorEndpointsPresent || !auditorProofMatches
                || !api.path("blockers").isArray() || !api.path("blockers").isEmpty()
                || !endpointsComplete) {
            throw failure("GitHub API receipt is blocked or incomplete");
        }
    }

    private static void requireRegistry(ObjectNode registry, ObjectNode capture) {
        if (!"strategy-github-attestation-key-registry/1".equals(text(registry.get("schema")))
                || !"FROZEN".equals(text(registry.get("status")))
                || !text(registry.get("content_sha256")).equals(JsonHashes.ownHash(registry))) {
            throw failure("attestation key registry is not frozen and hash-valid");
        }
        if (!stringValue(registry.get("repository")).equals(stringValue(capture.get("repository")))
                || !stringValue(registry.get("repository_id")).equals(stringValue(capture.get("repository_id")))
                || !"prospective-v5".equals(text(registry.get("environment")))
                || !text(registry.get("environment")).equals(
                        text(capture.path("oidc_claims").get("environment")))) {
            throw failure("attestation key registry is not bound to the captured repository/id/environment");
        }
    }

    private static TrustedKey trustedKey(ObjectNode registry, String fingerprint, PrivateKey privateKey) {
        for (JsonNode row : array(registry.get("keys"))) {
            String pem = text(row.get("public_key_pem"));
            if (!"ACTIONS_ATTESTATION".equals(text(row.get("role")))
                    || !fingerprint.equals(text(row.get("fingerprint")))
                    || !fingerprint.equals(JsonHashes.sha256(pem))) continue;
            PublicKey publicKey = publicKey(pem);
            if (!canonicalPublicPem(publicKey).equals(pem)) continue;
            if (!keyPairMatches(privateKey, publicKey)) continue;
            return new TrustedKey(text(row.get("key_id")), pem, publicKey);
        }
        throw failure("protected Actions attestation key is absent from the frozen registry");
    }

    private static ObjectNode signAttestation(ObjectNode fields, PrivateKey privateKey, PublicKey publicKey) {
        ObjectNode value = JsonHashes.mapper().createObjectNode();
        value.put("schema", "strategy-github-prospective-attestation/1");
        value.put("version", 1);
        value.setAll(fields.deepCopy());
        value.put("protected", true);
        ObjectNode payload = value.deepCopy();
        payload.remove(List.of("signature", "content_sha256", "attestation_payload_sha256"));
        value.put("attestation_payload_sha256", JsonHashes.canonicalSha256(payload));
        byte[] signature = sign(privateKey, JsonHashes.canonicalBytes(payload));
        if (!verify(publicKey, JsonHashes.canonicalBytes(payload), signature)) {
            throw failure("protected Actions attestation private key does not match the frozen registry");
        }
        value.put("signature", Base64.getEncoder().encodeToString(signature));
        value.put("content_sha256", JsonHashes.ownHash(value));
        SCHEMAS.validateContractSchema(value);
        return value;
    }

    private static Physical readPhysical(Path root, String pathValue, String label) {
        Path path = resolve(root, required(label, pathValue));
        PathConfinement.validateSinglyLinkedFile(path, label);
        byte[] bytes = PathConfinement.readSinglyLinkedFile(path, label);
        JsonNode parsed = JsonHashes.parse(bytes, label);
        if (!(parsed instanceof ObjectNode value)) throw failure(label + " is not JSON");
        if (!value.path("schema").isTextual()
                || !text(value.get("content_sha256")).equals(JsonHashes.ownHash(value))) {
            throw failure(label + " content hash is invalid");
        }
        SCHEMAS.validateContractSchema(value);
        return new Physical(path, bytes, JsonHashes.sha256(bytes), value);
    }

    private static boolean exactAuditorSecret(JsonNode value, String tokenKind) {
        return !"APP".equals(tokenKind) || (value != null
                && "V5_GITHUB_SETTINGS_AUDITOR_APP_PRIVATE_KEY_PEM".equals(text(value.get("name")))
                && value.path("environment_status").asInt() == 200
                && value.path("repository_status").asInt() == 404
                && value.path("organization_status").asInt() == 404
                && value.path("verified").asBoolean(false));
    }

    private static boolean exactAuditorProof(JsonNode proof, String repository,
            JsonNode repositoryId, String tokenKind) {
        if (!"APP".equals(tokenKind)) return true;
        Long expectedInstallation = optionalSafeInteger(proof.get("expected_installation_id"));
        return proof.path("verified").asBoolean(false)
                && optionalSafeInteger(proof.get("expected_app_id")) != null
                && optionalSafeInteger(proof.get("expected_app_id")) == SETTINGS_AUDITOR_APP_ID
                && expectedInstallation != null && expectedInstallation > 0
                && SETTINGS_AUDITOR_APP_SLUG.equals(text(proof.get("expected_app_slug")))
                && proof.path("app_endpoint_status").asInt() == 200
                && proof.path("installation_endpoint_status").asInt() == 200
                && proof.path("repositories_endpoint_status").asInt() == 200
                && proof.path("app_id").asLong() == SETTINGS_AUDITOR_APP_ID
                && SETTINGS_AUDITOR_APP_SLUG.equals(text(proof.get("app_slug")))
                && proof.path("installation_id").asLong() == expectedInstallation
                && "selected".equals(text(proof.get("repository_selection")))
                && exactPermissions(proof.get("permissions"))
                && exactPermissions(proof.get("installation_permissions"))
                && array(proof.get("events")).isEmpty()
                && array(proof.get("installation_events")).isEmpty()
                && proof.path("account").path("id").asLong() > 0
                && repository.split("/", -1)[0].equals(text(proof.path("account").get("login")))
                && proof.path("accessible_repository_count").asInt() == 1
                && proof.path("accessible_repository").path("id").asLong() == numeric(repositoryId)
                && repository.equals(text(proof.path("accessible_repository").get("full_name")));
    }

    private static boolean exactPermissions(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != SETTINGS_AUDITOR_PERMISSIONS.size()) return false;
        return SETTINGS_AUDITOR_PERMISSIONS.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(text(value.get(entry.getKey()))));
    }

    private static PrivateKey privateKey(String encoded) {
        try {
            String pem = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            byte[] der = decodePem(pem, "PRIVATE KEY");
            PrivateKey key = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!"EdDSA".equalsIgnoreCase(key.getAlgorithm())
                    && !"Ed25519".equalsIgnoreCase(key.getAlgorithm())) throw new IllegalArgumentException();
            return key;
        } catch (RuntimeException | java.security.GeneralSecurityException error) {
            throw failure("protected Actions attestation private key is invalid");
        }
    }

    private static PublicKey publicKey(String pem) {
        try {
            if (!PUBLIC_SPKI.matcher(pem).matches()) throw new IllegalArgumentException();
            PublicKey key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(decodePem(pem, "PUBLIC KEY")));
            if (!"EdDSA".equalsIgnoreCase(key.getAlgorithm())
                    && !"Ed25519".equalsIgnoreCase(key.getAlgorithm())) throw new IllegalArgumentException();
            return key;
        } catch (RuntimeException | java.security.GeneralSecurityException error) {
            throw failure("protected Actions attestation public key is invalid");
        }
    }

    private static byte[] decodePem(String pem, String label) {
        String header = "-----BEGIN " + label + "-----";
        String footer = "-----END " + label + "-----";
        if (!pem.startsWith(header) || !pem.trim().endsWith(footer)) throw new IllegalArgumentException();
        return Base64.getMimeDecoder().decode(pem.replace(header, "").replace(footer, ""));
    }

    private static String canonicalPublicPem(PublicKey key) {
        String base64 = Base64.getEncoder().encodeToString(key.getEncoded());
        StringBuilder out = new StringBuilder("-----BEGIN PUBLIC KEY-----\n");
        for (int index = 0; index < base64.length(); index += 64) {
            out.append(base64, index, Math.min(index + 64, base64.length())).append('\n');
        }
        return out.append("-----END PUBLIC KEY-----\n").toString();
    }

    private static boolean keyPairMatches(PrivateKey privateKey, PublicKey publicKey) {
        byte[] probe = "strategy-v5-key-match".getBytes(StandardCharsets.UTF_8);
        return verify(publicKey, probe, sign(privateKey, probe));
    }

    private static byte[] sign(PrivateKey key, byte[] bytes) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(bytes);
            return signer.sign();
        } catch (java.security.GeneralSecurityException error) {
            throw failure("protected Actions attestation private key is invalid");
        }
    }

    private static boolean verify(PublicKey key, byte[] bytes, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(bytes);
            return verifier.verify(signature);
        } catch (java.security.GeneralSecurityException error) {
            return false;
        }
    }

    private static void writeExclusive0600(Path output, byte[] bytes) {
        Path parent = output.getParent();
        if (parent == null) throw failure("immutable attestation output has no parent");
        PathConfinement.requireRealDirectory(parent, "attestation output parent");
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("immutable attestation output already exists: " + output);
        }
        Set<OpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try (FileChannel channel = open0600(output, options)) {
            ByteBuffer input = ByteBuffer.wrap(bytes);
            while (input.hasRemaining()) channel.write(input);
            channel.force(true);
        } catch (FileAlreadyExistsException error) {
            throw failure("immutable attestation output already exists: " + output);
        } catch (IOException error) {
            throw new CustodyException("immutable attestation output cannot be written", error);
        }
    }

    private static FileChannel open0600(Path output, Set<OpenOption> options) throws IOException {
        try {
            return FileChannel.open(output, options,
                    PosixFilePermissions.asFileAttribute(Set.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException error) {
            return FileChannel.open(output, options);
        }
    }

    private static byte[] secureNonce() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String required(String name, String value) {
        if (value == null || value.isEmpty()) throw failure(name + " is required");
        return value;
    }

    private static long safeInteger(JsonNode value, String label) {
        Long parsed = optionalSafeInteger(value);
        if (parsed == null) throw failure(label + " is invalid");
        return parsed;
    }

    private static Long optionalSafeInteger(JsonNode value) {
        if (value == null || value.isNull() || value.isBoolean()) return null;
        try {
            java.math.BigDecimal number = new java.math.BigDecimal(value.isTextual()
                    ? value.textValue() : value.asText());
            long exact = number.longValueExact();
            return Math.abs(exact) <= MAX_SAFE_INTEGER ? exact : null;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static boolean exactStrings(JsonNode value, List<String> expected) {
        if (value == null || !value.isArray() || value.size() != expected.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            if (!expected.get(index).equals(text(value.get(index)))) return false;
        }
        return true;
    }

    private static boolean containsText(JsonNode value, String expected) {
        for (JsonNode row : array(value)) if (expected.equals(text(row))) return true;
        return false;
    }

    private static boolean containsInteger(JsonNode value, long expected) {
        for (JsonNode row : array(value)) {
            Long parsed = optionalSafeInteger(row);
            if (parsed != null && parsed == expected) return true;
        }
        return false;
    }

    private static boolean sameHash(JsonNode left, JsonNode right) {
        return JsonHashes.canonicalSha256(left).equals(JsonHashes.canonicalSha256(right));
    }

    private static Path resolve(Path root, String value) {
        Path path = Path.of(value);
        return path.isAbsolute() ? path.normalize() : root.resolve(path).toAbsolutePath().normalize();
    }

    private static ObjectNode object(JsonNode value, String label) {
        if (value instanceof ObjectNode object) return object;
        throw failure(label + " is not an object");
    }

    private static ArrayNode array(JsonNode value) {
        return value instanceof ArrayNode array ? array : JsonHashes.mapper().createArrayNode();
    }

    private static String text(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode() ? "" : value.asText();
    }

    private static String stringValue(JsonNode value) {
        return value == null || value.isMissingNode() ? "undefined"
                : value.isNull() ? "null" : value.asText();
    }

    private static long numeric(JsonNode value) {
        Long parsed = optionalSafeInteger(value);
        return parsed == null ? Long.MIN_VALUE : parsed;
    }

    private static CustodyException failure(String message) {
        return new CustodyException(message);
    }

    private record Physical(Path path, byte[] bytes, String byteSha256, ObjectNode value) {}
    private record TrustedKey(String keyId, String publicPem, PublicKey publicKey) {}
}

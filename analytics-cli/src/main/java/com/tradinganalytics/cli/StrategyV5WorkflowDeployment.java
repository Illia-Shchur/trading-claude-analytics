package com.tradinganalytics.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.schema.ResearchSchemaRegistry;
import com.tradinganalytics.infrastructure.github.GitHubSettingsCaptureV5;
import com.tradinganalytics.infrastructure.security.ActionsAttestationVerifierV5;
import com.tradinganalytics.infrastructure.security.JsonHashes;
import com.tradinganalytics.infrastructure.security.PathConfinement;
import com.tradinganalytics.infrastructure.security.WorkflowSecurityV5;
import com.tradinganalytics.research.v5.StrategyProspectiveV5;
import com.tradinganalytics.research.v5.StrategyReadinessV5;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.tradinganalytics.cli.StrategyV5WorkflowJson.*;
import static com.tradinganalytics.cli.StrategyV5WorkflowPaths.*;

/** Deployment-audit construction and physical evidence validation. */
final class StrategyV5WorkflowDeployment {
    static final String DEPLOYMENT_SCHEMA = "strategy-deployment-audit/1";
    private static final String DRIFT_SCHEMA = "github-settings-drift-evidence/1";
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");
    private static final List<String> EXACT_EXTERNAL_REQUIREMENTS = List.of(
            "GitHub branch/environment protection settings captured by API",
            "OIDC workflow subject restriction",
            "Actions-only secret physical receipt",
            "public approval keys",
            "offline activation trust root",
            "physical replay registry",
            "physical revocation registry",
            "bounded signed lease evidence");

    private StrategyV5WorkflowDeployment() {}

    static ObjectNode makeDeploymentAudit(Map<String, String> env, Path work) {
        ObjectNode capture = optionalObject(work.resolve("github-deployment-settings-capture.json"));
        ObjectNode api = optionalObject(work.resolve("github-settings-api-receipt.json"));
        ObjectNode drift = optionalObject(work.resolve("github-settings-drift-evidence.json"));
        ObjectNode cycle = optionalObject(work.resolve("v5-shadow-cycle-receipt.json"));
        ObjectNode attestation = optionalObject(work.resolve("v5-actions-attestation.json"));
        ObjectNode registry = optionalObject(work.resolve("v5-attestation-key-registry.json"));
        boolean captureValid = validSchemaHash(capture, "github-deployment-settings-capture/1")
                && capture.path("verified").asBoolean(false);
        boolean apiValid = validSchemaHash(api, "github-settings-api-receipt/1")
                && api.path("verified").asBoolean(false) && api.path("blockers").size() == 0;
        boolean driftValid = validDrift(drift, capture, api);
        boolean cycleValid = validSchemaHash(cycle, StrategyV5WorkflowReceipts.AUTHORITATIVE_SCHEMA)
                && "COMPLETE".equals(text(cycle.get("status")))
                && !cycle.path("details").path("active").asBoolean(true);
        boolean actionsCustody = captureValid && apiValid && cycleValid && driftValid
                && attestation != null && registry != null
                && validOwnHash(attestation) && validOwnHash(registry);
        if (actionsCustody) {
            try {
                for (ObjectNode value : List.of(capture, api, cycle, attestation, registry)) {
                    ResearchSchemaRegistry.defaultRegistry().validateContractSchema(value);
                }
            } catch (RuntimeException invalid) {
                actionsCustody = false;
            }
        }
        if (actionsCustody) {
            String tokenKind = text(capture.path("settings_token_identity").get("token_kind"));
            JsonNode captureSecret = capture.get("settings_token_secret");
            JsonNode apiSecret = api.get("settings_token_secret");
            JsonNode captureActionsSecret = capture.get("actions_secret");
            JsonNode apiActionsSecret = api.get("actions_secret");
            JsonNode capturePermissions = capture.get("actions_permissions");
            JsonNode apiPermissions = api.get("actions_permissions");
            JsonNode captureWriterEnvironment = capture.get("writer_environment_protection");
            JsonNode apiWriterEnvironment = api.get("writer_environment_protection");
            JsonNode captureWriterSecret = capture.get("evidence_writer_secret");
            JsonNode apiWriterSecret = api.get("evidence_writer_secret");
            JsonNode captureRulesets = capture.get("rulesets");
            JsonNode apiRulesets = api.get("rulesets");
            actionsCustody = (!"APP".equals(tokenKind)
                    || (auditorSecretExact(captureSecret) && auditorSecretExact(apiSecret)
                    && exactSettingsAuditorIdentity(capture.path("settings_token_identity"), tokenKind)
                    && exactSettingsAuditorIdentity(api.path("settings_token_identity"), tokenKind)
                    && exactSettingsAuditorInstallation(capture.get("settings_auditor_installation"),
                            text(capture.get("repository")), capture.get("repository_id"), tokenKind)
                    && exactSettingsAuditorInstallation(api.get("settings_auditor_installation"),
                            text(capture.get("repository")), capture.get("repository_id"), tokenKind)
                    && sameCanonical(captureSecret, apiSecret)))
                    && captureActionsSecret != null && apiActionsSecret != null
                    && captureActionsSecret.path("verified").asBoolean(false)
                    && apiActionsSecret.path("verified").asBoolean(false)
                    && sameCanonical(captureActionsSecret, apiActionsSecret)
                    && capturePermissions != null && apiPermissions != null
                    && capturePermissions.path("verified").asBoolean(false)
                    && apiPermissions.path("verified").asBoolean(false)
                    && sameCanonical(capturePermissions, apiPermissions)
                    && captureWriterEnvironment != null && apiWriterEnvironment != null
                    && captureWriterEnvironment.path("verified").asBoolean(false)
                    && apiWriterEnvironment.path("verified").asBoolean(false)
                    && !captureWriterEnvironment.path("can_admins_bypass").asBoolean(true)
                    && !apiWriterEnvironment.path("can_admins_bypass").asBoolean(true)
                    && StrategyReadinessV5.environmentReviewSafe(captureWriterEnvironment)
                    && StrategyReadinessV5.environmentReviewSafe(apiWriterEnvironment)
                    && sameCanonical(captureWriterEnvironment, apiWriterEnvironment)
                    && captureWriterSecret != null && apiWriterSecret != null
                    && captureWriterSecret.path("verified").asBoolean(false)
                    && apiWriterSecret.path("verified").asBoolean(false)
                    && sameCanonical(captureWriterSecret, apiWriterSecret)
                    && captureRulesets != null && apiRulesets != null
                    && captureRulesets.path("layered_policy_verified").asBoolean(false)
                    && apiRulesets.path("layered_policy_verified").asBoolean(false)
                    && captureRulesets.path("actions_bypass_app_ids").isArray()
                    && captureRulesets.path("actions_bypass_app_ids").isEmpty()
                    && apiRulesets.path("actions_bypass_app_ids").isArray()
                    && apiRulesets.path("actions_bypass_app_ids").isEmpty()
                    && sameCanonical(captureRulesets, apiRulesets);
        }
        if (actionsCustody) {
            ObjectNode options = object();
            options.set("attestation", attestation);
            options.set("capture", capture);
            options.set("publication", NullNode.instance);
            options.put("bytesSha256", hashIfPresent(work.resolve("github-deployment-settings-capture.json")))
                    .put("nowMs", System.currentTimeMillis())
                    .put("apiReceiptSha256", hashIfPresent(work.resolve("github-settings-api-receipt.json")))
                    .put("cycleReceiptSha256", hashIfPresent(work.resolve("v5-shadow-cycle-receipt.json")))
                    .put("trustedKeyRegistrySha256", text(registry.get("content_sha256")))
                    .put("trustedKeyRegistryByteSha256", hashIfPresent(work.resolve("v5-attestation-key-registry.json")));
            options.set("trustedKeyRegistry", registry);
            String pinned = env.getOrDefault("V5_ATTESTATION_KEY_FINGERPRINT", "");
            if (!pinned.isBlank()) options.put("pinnedFingerprint", pinned);
            try {
                actionsCustody = StrategyReadinessV5.verifyActionsAttestation(options);
            } catch (RuntimeException ignored) {
                actionsCustody = false;
            }
        }
        boolean shadowEligible = captureValid && actionsCustody && cycleValid && driftValid;
        ObjectNode checks = object();
        checks.put("repository_private", captureValid
                && capture.path("repository_visibility_verified").asBoolean(false)
                && Set.of("PUBLIC", "PRIVATE").contains(text(capture.get("repository_visibility"))));
        checks.put("append_only_branch_protected", captureValid
                && capture.path("branch_protection").path("verified").asBoolean(false)
                && !capture.path("branch_protection").path("allow_force_pushes").asBoolean(true)
                && !capture.path("branch_protection").path("allow_deletions").asBoolean(true));
        checks.put("prospective_environment_protected", captureValid
                && capture.path("environment_protection").path("verified").asBoolean(false)
                && StrategyReadinessV5.environmentReviewSafe(capture.path("environment_protection")));
        checks.put("oidc_subject_restricted", captureValid
                && capture.path("oidc_subject_restricted").asBoolean(false)
                && "https://token.actions.githubusercontent.com".equals(
                        text(capture.path("oidc_claims").get("iss"))));
        checks.put("actions_only_secret", actionsOnlySecret(env, work, capture, api, attestation, registry));
        checks.put("github_settings_drift", actionsCustody && driftValid);
        boolean trustRoot = verifyTrustRoot(env, work);
        checks.put("offline_trust_root_verified", trustRoot);
        ObjectNode keys = optionalObject(env.get("V5_PUBLIC_KEYS_PATH"), work);
        checks.put("asset_key_present", keys != null
                && ActionsAttestationVerifierV5.publicKeyFingerprint(
                        text(keys.get("asset_public_key_pem"))) != null);
        checks.put("portfolio_key_present", keys != null
                && ActionsAttestationVerifierV5.publicKeyFingerprint(
                        text(keys.get("portfolio_public_key_pem"))) != null);
        checks.put("activation_root_verified", trustRoot);
        ObjectNode trust = optionalObject(first(env.get("V5_TRUST_ROOT_PATH"), env.get("V5_TRUST_ROOT")), work);
        checks.put("distinct_approval_roles", distinctApprovals(env, work, trust, keys));
        checks.put("replay_protection", actionsCustody
                && validEvidencePath(env.get("V5_REPLAY_EVIDENCE_PATH"), work, "replay", env));
        checks.put("revocation_list", actionsCustody
                && validEvidencePath(env.get("V5_REVOCATION_EVIDENCE_PATH"), work, "revocation", env));
        checks.put("lease_enforced", actionsCustody
                && validEvidencePath(env.get("V5_LEASE_EVIDENCE_PATH"), work, "lease", env));
        checks.put("shadow_append_eligible", shadowEligible);
        List<String> failed = new ArrayList<>();
        checks.fields().forEachRemaining(entry -> {
            if (!entry.getValue().asBoolean()) failed.add(entry.getKey());
        });
        ObjectNode result = object().put("schema", DEPLOYMENT_SCHEMA).put("version", 1)
                .put("settings_capture_sha256", captureValid ? text(capture.get("content_sha256")) : null);
        result.set("checks", checks);
        result.put("shadow_append_eligible", shadowEligible)
                .put("activation_eligible", failed.isEmpty()).put("blocked", !failed.isEmpty())
                .put("reason", failed.isEmpty()
                        ? "deployment audit passed; external activation remains separately authorized"
                        : "deployment audit blocked: " + String.join(", ", failed))
                .put("blocked_until_external_prerequisites", !failed.isEmpty());
        result.set("exact_external_verification_required", strings(EXACT_EXTERNAL_REQUIREMENTS));
        result.put("content_sha256", StrategyProspectiveV5.ownHash(result));
        return result;
    }

    static String settingsPolicyHash(ObjectNode value) {
        return StrategyProspectiveV5.hash(settingsPolicy(value));
    }

    static String settingsApiPolicyHash(ObjectNode value) {
        return StrategyProspectiveV5.hash(apiPolicy(value));
    }

    static boolean validDrift(ObjectNode evidence, ObjectNode capture, ObjectNode api) {
        if (!validSchemaHash(evidence, DRIFT_SCHEMA) || capture == null || api == null
                || !text(evidence.get("repository")).equals(text(capture.get("repository")))
                || !text(evidence.get("repository_id")).equals(text(capture.get("repository_id")))
                || !text(evidence.get("current_capture_sha256"))
                        .equals(text(capture.get("content_sha256")))
                || !text(evidence.get("current_api_receipt_sha256"))
                        .equals(text(api.get("content_sha256")))) return false;
        try {
            ResearchSchemaRegistry.defaultRegistry().validateContractSchema(evidence);
        } catch (RuntimeException invalid) {
            return false;
        }
        String status = text(evidence.get("status"));
        if ("BASELINE_ESTABLISHED".equals(status)) return true;
        return "CLEAR".equals(status)
                && HASH.matcher(text(evidence.get("previous_capture_sha256"))).matches()
                && HASH.matcher(text(evidence.get("previous_api_receipt_sha256"))).matches()
                && evidence.path("changed_fields").isArray()
                && evidence.path("changed_fields").isEmpty();
    }

    private static ObjectNode settingsPolicy(ObjectNode value) {
        ObjectNode out = object();
        for (String key : List.of("repository", "repository_id", "evidence_branch",
                "repository_private", "repository_visibility", "repository_visibility_verified",
                "branch_protection", "rulesets", "environment_protection", "actions_permissions",
                "actions_secret", "settings_token_secret", "settings_token_identity",
                "settings_auditor_installation", "oidc_signature_verified", "oidc_subject_restricted"))
            if (value.has(key)) out.set(key, value.get(key).deepCopy());
        if (value.path("oidc_claims").isObject()) {
            ObjectNode oidc = object();
            for (String key : List.of("repository_id", "repository_owner_id", "environment",
                    "workflow_ref", "workflow_sha", "sub", "aud", "iss"))
                if (value.path("oidc_claims").has(key)) oidc.set(key,
                        value.path("oidc_claims").get(key).deepCopy());
            out.set("oidc", oidc);
        }
        return out;
    }

    private static ObjectNode apiPolicy(ObjectNode value) {
        ObjectNode out = object();
        if (!value.path("endpoints").isObject()) return out;
        value.path("endpoints").fields().forEachRemaining(entry -> {
            ObjectNode row = object().put("status", entry.getValue().path("status").asInt(0));
            if (!Set.of("branch_head", "oidc_subject_restriction").contains(entry.getKey()))
                row.put("body_sha256", text(entry.getValue().get("body_sha256")));
            else row.putNull("body_sha256");
            out.set(entry.getKey(), row);
        });
        return out;
    }

    private static boolean actionsOnlySecret(Map<String, String> env, Path work, ObjectNode capture,
                                             ObjectNode api, ObjectNode attestation, ObjectNode registry) {
        ObjectNode value = optionalObject(env.get("V5_ACTIONS_SECRET_EVIDENCE_PATH"), work);
        if (!validSchemaHash(value, "strategy-actions-only-secret-evidence/1")) return false;
        try {
            ResearchSchemaRegistry.defaultRegistry().validateContractSchema(value);
        } catch (RuntimeException invalid) {
            return false;
        }
        JsonNode capturedSecret = capture == null ? null : capture.get("actions_secret");
        JsonNode apiSecret = api == null ? null : api.get("actions_secret");
        return "BOUND".equals(text(value.get("status")))
                && "ACTIONS_ATTESTATION_ONLY".equals(text(value.get("scope")))
                && capture != null && api != null && attestation != null && registry != null
                && text(value.get("repository")).equals(text(capture.get("repository")))
                && text(value.get("repository_id")).equals(text(capture.get("repository_id")))
                && "prospective-v5".equals(text(value.get("environment")))
                && capturedSecret != null && capturedSecret.path("verified").asBoolean(false)
                && apiSecret != null && apiSecret.path("verified").asBoolean(false)
                && JsonHashes.canonicalSha256(capturedSecret)
                        .equals(JsonHashes.canonicalSha256(apiSecret))
                && text(value.get("secret_name")).equals(text(capturedSecret.get("name")))
                && value.path("environment_secret_status").asInt(-1)
                        == capturedSecret.path("environment_status").asInt(-2)
                && text(value.get("environment_secret_body_sha256"))
                        .equals(text(capturedSecret.get("environment_body_sha256")))
                && value.path("repository_secret_status").asInt(-1)
                        == capturedSecret.path("repository_status").asInt(-2)
                && text(value.get("repository_secret_body_sha256"))
                        .equals(text(capturedSecret.get("repository_body_sha256")))
                && value.path("organization_secret_status").asInt(-1)
                        == capturedSecret.path("organization_status").asInt(-2)
                && text(value.get("organization_secret_body_sha256"))
                        .equals(text(capturedSecret.get("organization_body_sha256")))
                && text(value.get("settings_capture_sha256")).equals(text(capture.get("content_sha256")))
                && text(value.get("api_receipt_sha256")).equals(text(api.get("content_sha256")))
                && text(value.get("attestation_sha256")).equals(text(attestation.get("content_sha256")))
                && text(value.get("registry_sha256")).equals(text(registry.get("content_sha256")))
                && !value.has("secret_value") && !value.has("private_key");
    }

    static boolean auditorSecretExact(JsonNode value) {
        return value != null
                && "V5_GITHUB_SETTINGS_AUDITOR_APP_PRIVATE_KEY_PEM".equals(text(value.get("name")))
                && value.path("environment_status").asInt(0) == 200
                && value.path("repository_status").asInt(0) == 404
                && value.path("organization_status").asInt(0) == 404
                && value.path("verified").asBoolean(false);
    }

    static boolean exactSettingsAuditorInstallation(JsonNode proof, String repository,
                                                    JsonNode repositoryId, String tokenKind) {
        if (!"APP".equals(tokenKind)) return true;
        if (proof == null || !proof.isObject() || repository == null || repository.isBlank()) return false;
        String owner = repository.split("/", -1)[0];
        return proof.path("verified").asBoolean(false)
                && proof.path("expected_app_id").asLong(Long.MIN_VALUE)
                        == GitHubSettingsCaptureV5.SETTINGS_AUDITOR_APP_ID
                && proof.path("expected_installation_id").asLong(Long.MIN_VALUE)
                        == GitHubSettingsCaptureV5.SETTINGS_AUDITOR_INSTALLATION_ID
                && GitHubSettingsCaptureV5.SETTINGS_AUDITOR_APP_SLUG.equals(
                        text(proof.get("expected_app_slug")))
                && proof.path("app_endpoint_status").asInt(-1) == 200
                && proof.path("installation_endpoint_status").asInt(-1) == 200
                && proof.path("repositories_endpoint_status").asInt(-1) == 200
                && proof.path("app_id").asLong(Long.MIN_VALUE)
                        == GitHubSettingsCaptureV5.SETTINGS_AUDITOR_APP_ID
                && GitHubSettingsCaptureV5.SETTINGS_AUDITOR_APP_SLUG.equals(text(proof.get("app_slug")))
                && proof.path("installation_id").asLong(Long.MIN_VALUE)
                        == GitHubSettingsCaptureV5.SETTINGS_AUDITOR_INSTALLATION_ID
                && "selected".equals(text(proof.get("repository_selection")))
                && exactSettingsAuditorPermissions(proof.get("permissions"))
                && exactSettingsAuditorPermissions(proof.get("installation_permissions"))
                && proof.path("events").isArray() && proof.path("events").isEmpty()
                && proof.path("installation_events").isArray()
                && proof.path("installation_events").isEmpty()
                && proof.path("account").path("id").asLong(0) > 0
                && owner.equals(text(proof.path("account").get("login")))
                && proof.path("accessible_repository_count").asLong(-1) == 1
                && proof.path("accessible_repository").path("id").asLong(Long.MIN_VALUE)
                        == numeric(repositoryId)
                && repository.equals(text(proof.path("accessible_repository").get("full_name")));
    }

    static boolean exactSettingsAuditorIdentity(JsonNode identity, String tokenKind) {
        return "APP".equals(tokenKind) && identity != null
                && identity.path("verified").asBoolean(false)
                && identity.path("app_id").asLong(Long.MIN_VALUE)
                        == GitHubSettingsCaptureV5.SETTINGS_AUDITOR_APP_ID;
    }

    private static boolean exactSettingsAuditorPermissions(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != 5) return false;
        return "read".equals(text(value.get("actions")))
                && "read".equals(text(value.get("administration")))
                && "read".equals(text(value.get("environments")))
                && "read".equals(text(value.get("metadata")))
                && "read".equals(text(value.get("secrets")));
    }

    private static boolean verifyTrustRoot(Map<String, String> env, Path work) {
        String rootPath = first(env.get("V5_TRUST_ROOT_PATH"), env.get("V5_TRUST_ROOT"));
        if (rootPath == null || env.getOrDefault("V5_TRUST_ROOT_FINGERPRINT", "").isBlank()
                || env.getOrDefault("V5_TRUST_ROOT_GENESIS_FINGERPRINT", "").isBlank()) return false;
        ObjectNode root = optionalObject(rootPath, work);
        if (root == null) return false;
        ObjectNode options = object().put("nowAt", System.currentTimeMillis())
                .put("pinnedFingerprint", env.get("V5_TRUST_ROOT_FINGERPRINT"))
                .put("pinnedGenesisFingerprint", env.get("V5_TRUST_ROOT_GENESIS_FINGERPRINT"));
        try {
            return StrategyProspectiveV5.verifyTrustRoot(root, options);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean distinctApprovals(Map<String, String> env, Path work,
                                             ObjectNode trustRoot, ObjectNode keys) {
        JsonNode root = optionalJson(first(env.get("V5_APPROVALS_PATH"), env.get("V5_APPROVALS")), work);
        if (root == null || trustRoot == null || keys == null) return false;
        List<JsonNode> approvals = root.isArray() ? rows(root) : rows(root.get("approvals"));
        Set<String> roles = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode row : approvals) {
            String role = text(row.get("role"));
            String keyId = text(row.get("key_id"));
            String publicPem = text(row.get("public_key_pem"));
            if (!Set.of("ASSET", "PORTFOLIO").contains(role)) continue;
            if (keyId.isBlank() || ActionsAttestationVerifierV5.publicKeyFingerprint(publicPem) == null
                    || text(row.get("trust_root_signature")).isBlank()) return false;
            JsonNode delegation = null;
            for (JsonNode candidate : rows(trustRoot.get("delegations"))) {
                if (role.toLowerCase(Locale.ROOT).equals(text(candidate.get("role")))
                        && keyId.equals(text(candidate.get("key_id")))
                        && publicPem.equals(text(candidate.get("public_key_pem")))) {
                    delegation = candidate;
                    break;
                }
            }
            if (delegation == null || rows(trustRoot.get("revoked_key_ids")).stream()
                    .anyMatch(revoked -> keyId.equals(text(revoked)))) return false;
            ObjectNode payload = object().put("role", role).put("key_id", keyId)
                    .put("public_key_sha256", StrategyProspectiveV5.hash(publicPem));
            try {
                if (!StrategyProspectiveV5.verifyPayload(payload,
                        text(row.get("trust_root_signature")), text(trustRoot.get("root_public_key_pem"))))
                    return false;
            } catch (RuntimeException invalid) {
                return false;
            }
            String expectedKey = "ASSET".equals(role)
                    ? text(keys.get("asset_public_key_pem"))
                    : text(keys.get("portfolio_public_key_pem"));
            if (!expectedKey.equals(publicPem)) return false;
            roles.add(role);
            ids.add(keyId);
        }
        return roles.size() == 2 && ids.size() == approvals.stream()
                .filter(row -> Set.of("ASSET", "PORTFOLIO").contains(text(row.get("role"))))
                .count();
    }

    /** Validate deployment evidence at its physical repository path. */
    private static boolean validEvidencePath(String value, Path work, String kind,
                                             Map<String, String> env) {
        if (value == null || value.isBlank()) return false;
        Path artifact;
        try {
            String relative = WorkflowSecurityV5.repositoryRelativePath(value,
                    kind + " evidence path");
            artifact = PathConfinement.resolve(work, relative, kind + " evidence",
                    PathConfinement.ExpectedType.FILE).absolute();
        } catch (RuntimeException invalidPath) {
            return false;
        }
        ObjectNode evidence = optionalObject(artifact);
        if (!validOwnHash(evidence)) return false;
        if ("replay".equals(kind) || "revocation".equals(kind)) {
            if (!verifyReplayRegistryArtifact(artifact, evidence)) return false;
            if ("replay".equals(kind)) return true;
            return verifyRevocationRegistryArtifact(evidence, env, work);
        }
        if (!"lease".equals(kind)
                || !"strategy-prospective-signed-evidence/2".equals(text(evidence.get("schema"))))
            return false;
        Instant expires = parseInstant(firstNode(evidence, "lease_expires_at", "expires_at"));
        Instant now = Instant.now(Clock.systemUTC());
        if (expires == null || !expires.isAfter(now)
                || expires.toEpochMilli() - now.toEpochMilli() > 90L * 86_400_000L) return false;
        String ledgerValue = first(env.get("V5_PROSPECTIVE_LEDGER_PATH"), env.get("V5_LEDGER_PATH"));
        String replayValue = first(env.get("V5_PROSPECTIVE_REPLAY_PATH"), env.get("V5_REPLAY_PATH"));
        String trustValue = first(env.get("V5_TRUST_ROOT_PATH"), env.get("V5_TRUST_ROOT"));
        if (ledgerValue == null || replayValue == null || trustValue == null
                || env.getOrDefault("V5_TRUST_ROOT_FINGERPRINT", "").isBlank()
                || env.getOrDefault("V5_TRUST_ROOT_GENESIS_FINGERPRINT", "").isBlank()) return false;
        try {
            Path ledger = PathConfinement.resolve(work,
                    WorkflowSecurityV5.repositoryRelativePath(ledgerValue, "ledger path"),
                    "prospective ledger", PathConfinement.ExpectedType.DIRECTORY).absolute();
            Path replay = PathConfinement.resolve(work,
                    WorkflowSecurityV5.repositoryRelativePath(replayValue, "replay path"),
                    "prospective replay registry", PathConfinement.ExpectedType.DIRECTORY).absolute();
            ObjectNode trustRoot = optionalObject(trustValue, work);
            if (trustRoot == null) return false;
            ObjectNode options = object().put("ledgerPath", ledger.toString())
                    .put("replayPath", replay.toString()).put("nowAt", now.toEpochMilli())
                    .put("pinnedTrustRootFingerprint", env.get("V5_TRUST_ROOT_FINGERPRINT"))
                    .put("pinnedTrustRootGenesisFingerprint",
                            env.get("V5_TRUST_ROOT_GENESIS_FINGERPRINT"));
            options.set("trustRoot", trustRoot);
            return StrategyProspectiveV5.verifyProspectivePublication(evidence, options) != null;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean verifyReplayRegistryArtifact(Path artifact, ObjectNode value) {
        if (!validSchemaHash(value, "strategy-prospective-replay-registry/1")) return false;
        try {
            ResearchSchemaRegistry.defaultRegistry().validateContractSchema(value);
        } catch (RuntimeException invalid) {
            return false;
        }
        List<JsonNode> entries = rows(value.get("entries"));
        List<JsonNode> refs = rows(value.get("entry_refs"));
        if (!HASH.matcher(text(value.get("lineage_sha256"))).matches()
                || !HASH.matcher(text(value.get("head_sha256"))).matches()
                || !text(value.get("current_head_sha256")).equals(text(value.get("head_sha256")))
                || refs.size() != entries.size() || value.path("sequence").asInt(-1) != entries.size()
                || entries.isEmpty()) return false;
        Path root = artifact.toAbsolutePath().normalize().getParent();
        if (root == null) return false;
        try {
            PathConfinement.requireRealDirectory(root, "replay registry root");
        } catch (RuntimeException invalid) {
            return false;
        }
        String previous = StrategyProspectiveV5.hash(object()
                .put("schema", "strategy-prospective-replay-genesis/1")
                .put("lineage_sha256", text(value.get("lineage_sha256"))));
        Map<String, List<String>> actions = new java.util.LinkedHashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            JsonNode entry = entries.get(index), ref = refs.get(index);
            List<String> prior = actions.computeIfAbsent(text(entry.get("nonce")),
                    ignored -> new ArrayList<>());
            String action = text(entry.get("action"));
            boolean lifecycle = "USE".equals(action)
                    ? prior.isEmpty() && HASH.matcher(text(entry.get("publication_payload_sha256"))).matches()
                    : "REVOKE".equals(action) && prior.equals(List.of("USE"))
                    && !text(entry.get("key_id")).isBlank() && !text(entry.get("signature")).isBlank()
                    && HASH.matcher(text(entry.get("trust_root_sha256"))).matches()
                    && entry.path("trust_root_generation").isIntegralNumber();
            if (entry.path("sequence").asInt(-1) != index + 1
                    || !text(entry.get("previous_head_sha256")).equals(previous)
                    || !HASH.matcher(text(entry.get("entry_sha256"))).matches()
                    || !text(entry.get("entry_sha256")).equals(
                            StrategyProspectiveV5.ownHash(entry, "entry_sha256"))
                    || text(entry.get("nonce")).isBlank() || !lifecycle) return false;
            prior.add(action);
            String relative = text(ref.get("path"));
            boolean absolutePath;
            try {
                absolutePath = Path.of(relative).isAbsolute();
            } catch (RuntimeException invalidPath) {
                return false;
            }
            if (ref.path("sequence").asInt(-1) != entry.path("sequence").asInt()
                    || !text(ref.get("entry_sha256")).equals(text(entry.get("entry_sha256")))
                    || !HASH.matcher(text(ref.get("byte_sha256"))).matches()
                    || relative.isBlank() || absolutePath
                    || relative.contains("..") || relative.contains("\\")) return false;
            Path child;
            byte[] bytes;
            try {
                child = PathConfinement.resolve(root, relative, "replay entry",
                        PathConfinement.ExpectedType.FILE).absolute();
                bytes = PathConfinement.readSinglyLinkedFile(child, "replay entry");
            } catch (RuntimeException invalid) {
                return false;
            }
            if (!StrategyProspectiveV5.hash(bytes).equals(text(ref.get("byte_sha256")))) return false;
            ObjectNode reopened;
            try {
                reopened = object(JsonHashes.parse(bytes, child.toString()), child.toString());
            } catch (RuntimeException invalid) {
                return false;
            }
            if (!sameCanonical(reopened, entry)
                    || !text(reopened.get("entry_sha256")).equals(text(entry.get("entry_sha256")))) return false;
            previous = text(entry.get("entry_sha256"));
        }
        return true;
    }

    private static boolean verifyRevocationRegistryArtifact(ObjectNode registry,
                                                             Map<String, String> env,
                                                             Path work) {
        ObjectNode trustRoot = optionalObject(
                first(env.get("V5_TRUST_ROOT_PATH"), env.get("V5_TRUST_ROOT")), work);
        String pin = env.getOrDefault("V5_TRUST_ROOT_FINGERPRINT", "");
        String genesis = env.getOrDefault("V5_TRUST_ROOT_GENESIS_FINGERPRINT", "");
        if (trustRoot == null || pin.isBlank() || genesis.isBlank()) return false;
        try {
            ObjectNode options = object().put("nowAt", System.currentTimeMillis())
                    .put("pinnedFingerprint", pin).put("pinnedGenesisFingerprint", genesis);
            if (env.containsKey("V5_PREVIOUS_TRUST_ROOT_PATH"))
                options.set("previousRoot", optionalObject(env.get("V5_PREVIOUS_TRUST_ROOT_PATH"), work));
            StrategyProspectiveV5.verifyTrustRoot(trustRoot, options);
            for (JsonNode entry : rows(registry.get("entries"))) {
                if (!"REVOKE".equals(text(entry.get("action")))
                        || !text(entry.get("trust_root_sha256")).equals(text(trustRoot.get("content_sha256")))
                        || entry.path("trust_root_generation").asInt(-1)
                                != trustRoot.path("generation").asInt()) continue;
                for (JsonNode delegated : rows(trustRoot.get("delegations"))) {
                    if (!"revocation".equals(text(delegated.get("role")))
                            || !text(delegated.get("key_id")).equals(text(entry.get("key_id")))
                            || rows(trustRoot.get("revoked_key_ids")).stream()
                                    .anyMatch(revoked -> text(revoked).equals(text(delegated.get("key_id"))))) continue;
                    ObjectNode payload = object();
                    for (String field : List.of("nonce", "action", "reason", "revoked_at",
                            "trust_root_sha256", "trust_root_generation"))
                        payload.set(field, entry.get(field));
                    if (StrategyProspectiveV5.verifyPayload(payload, text(entry.get("signature")),
                            text(delegated.get("public_key_pem")))) return true;
                }
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }
}
